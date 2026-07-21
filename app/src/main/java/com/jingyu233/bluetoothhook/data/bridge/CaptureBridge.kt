package com.jingyu233.bluetoothhook.data.bridge

import com.jingyu233.bluetoothhook.CaptureProtocol
import com.jingyu233.bluetoothhook.data.model.CaptureRecord
import com.jingyu233.bluetoothhook.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 抓包数据桥接器
 *
 * 在后台线程启动 localhost ServerSocket(8899)，接收 Hook 进程(CaptureSocket)推送的：
 *   - STATUS 行：Hook 状态信息
 *   - CAP 行：BLE 扫描记录
 *
 * 通过 StateFlow 将数据暴露给 UI 层（ViewModel / Screen）。
 */
object CaptureBridge {

    private const val TAG = "BTHook:Data:CaptureBridge"

    /** Socket 端口，必须与 hook/CaptureSocket.kt 的 PORT 相等 */
    const val PORT = 8899

    /** 抓包记录最大缓存条数 */
    private const val MAX_RECORDS = 500

    /** ServerSocket accept 超时（毫秒），用于响应取消信号 */
    private const val ACCEPT_TIMEOUT_MS = 3000L

    // -------------------- 公开 StateFlow --------------------

    private val _captureRecords = MutableStateFlow<List<CaptureRecord>>(emptyList())
    val captureRecords: StateFlow<List<CaptureRecord>> = _captureRecords.asStateFlow()

    private val _hookStatus = MutableStateFlow<HookStatus?>(null)
    val hookStatus: StateFlow<HookStatus?> = _hookStatus.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    // -------------------- Hook 状态模型 --------------------

    /**
     * Hook 进程推送的实时状态快照
     * @param sdkInt Android SDK 版本
     * @param classFound 反射找到的类名
     * @param methodFound 反射找到的方法名
     * @param fieldsResolved 已解析的字段信息
     * @param timestamp 状态产生时间戳（毫秒）
     */
    data class HookStatus(
        val sdkInt: Int,
        val classFound: String,
        val methodFound: String,
        val fieldsResolved: String,
        val timestamp: Long
    )

    // -------------------- 内部状态 --------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    /** 自增记录 ID */
    private val nextRecordId = AtomicLong(1L)

    /** 线程安全的记录缓存 */
    private val recordsCache = mutableListOf<CaptureRecord>()

    /** 防抖 emit 标志 */
    private val isEmitPending = AtomicBoolean(false)

    // -------------------- 公开 API --------------------

    /**
     * 启动 Socket 服务端。
     *
     * 在后台线程（Dispatchers.IO）中创建 ServerSocket 并绑定 127.0.0.1:PORT，
     * 循环 accept 客户端连接，按行读取并解析协议。
     *
     * 幂等：已在监听时直接返回。
     */
    fun startServer() {
        if (_isListening.value) {
            Logger.App.w(TAG, "startServer called but already listening, ignored")
            return
        }

        serverJob = scope.launch {
            try {
                @Suppress("BlockingMethodInNonBlockingContext")
                val ss = ServerSocket()
                serverSocket = ss
                ss.reuseAddress = true

                // 绑定端口，失败时设置错误状态
                try {
                    ss.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
                } catch (e: Exception) {
                    _serverError.value = e.message
                    _isListening.value = false
                    serverSocket = null
                    ss.close()
                    Logger.App.e(TAG, "Failed to bind server socket", e)
                    return@launch
                }

                _serverError.value = null
                ss.soTimeout = ACCEPT_TIMEOUT_MS.toInt()

                _isListening.value = true
                Logger.App.i(TAG, "Capture server listening on 127.0.0.1:$PORT")

                while (isActive) {
                    try {
                        @Suppress("BlockingMethodInNonBlockingContext")
                        val client = ss.accept()
                        Logger.App.d(TAG, "Client connected: ${client.inetAddress}")

                        // 鉴权：读取客户端第一行，必须为 AUTH|<token>
                        val reader = BufferedReader(
                            InputStreamReader(client.getInputStream(), Charsets.UTF_8)
                        )
                        val authLine = try {
                            reader.readLine()
                        } catch (e: Exception) {
                            null
                        }
                        val expectedAuth = "${CaptureProtocol.AUTH_PREFIX}${CaptureProtocol.AUTH_TOKEN}"
                        if (authLine != expectedAuth) {
                            Logger.App.w(TAG, "Auth failed: '$authLine', closing connection")
                            try { client.close() } catch (_: Exception) {}
                            continue
                        }

                        // 鉴权通过，每个客户端用独立协程处理后续 STATUS/CAP 行
                        launch {
                            try {
                                var line: String? = null
                                while (isActive && reader.readLine().also { line = it } != null) {
                                    line?.let { processLine(it) }
                                }
                            } catch (e: java.net.SocketException) {
                                // 客户端正常断开
                                Logger.App.d(TAG, "Client disconnected")
                            } catch (e: Exception) {
                                Logger.App.e(TAG, "Client handler error", e)
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // accept 超时，重新检查 isActive
                        continue
                    }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                // 协程被取消，正常退出
                throw e
            } catch (e: Exception) {
                _serverError.value = e.message ?: "Server error"
                Logger.App.e(TAG, "Server error", e)
            } catch (e: Throwable) {
                _serverError.value = e.message ?: "Unexpected server error"
                Logger.App.e(TAG, "Unexpected server error", e)
            } finally {
                serverSocket?.close()
                serverSocket = null
                _isListening.value = false
                Logger.App.i(TAG, "Capture server stopped")
            }
        }
    }

    /**
     * 停止 Socket 服务端。
     * 关闭 ServerSocket → 中断 accept → 协程 finally 块清理状态。
     */
    fun stopServer() {
        serverJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        // _isListening 由协程 finally 块置为 false
    }

    /**
     * 清空所有抓包记录。
     */
    fun clearRecords() {
        synchronized(recordsCache) {
            recordsCache.clear()
            _captureRecords.value = emptyList()
        }
    }

    // -------------------- 内部方法 --------------------

    /**
     * 解析一行协议并更新对应 StateFlow。
     *
     * 协议格式：
     *   STATUS|<sdkInt>|<classFound>|<methodFound>|<fieldsResolved>|<timestampMs>
     *   CAP|<timestampMs>|<mac>|<rssi>|<eventType>|<primaryPhy>|<addressType>|<advDataHex>
     */
    private fun processLine(line: String) {
        when {
            line.startsWith(CaptureProtocol.STATUS_PREFIX) -> parseStatus(line)
            line.startsWith(CaptureProtocol.CAP_PREFIX) -> parseCapture(line)
            else -> Logger.App.v(TAG, "Unknown line ignored: $line")
        }
    }

    private fun parseStatus(line: String) {
        val parts = line.split("|")
        if (parts.size < 6) {
            Logger.App.w(TAG, "Malformed STATUS line (${parts.size} parts): $line")
            return
        }
        _hookStatus.value = HookStatus(
            sdkInt = parts[1].toIntOrNull() ?: 0,
            classFound = parts[2],
            methodFound = parts[3],
            fieldsResolved = parts[4],
            timestamp = parts[5].toLongOrNull() ?: System.currentTimeMillis()
        )
        Logger.App.d(TAG, "HookStatus updated: SDK=${parts[1]}, class=${parts[2]}")
    }

    private fun parseCapture(line: String) {
        val parts = line.split("|")
        if (parts.size < 8) {
            Logger.App.w(TAG, "Malformed CAP line (${parts.size} parts): $line")
            return
        }
        val record = CaptureRecord(
            id = nextRecordId.getAndIncrement(),
            timestamp = parts[1].toLongOrNull() ?: System.currentTimeMillis(),
            mac = parts[2],
            rssi = parts[3].toIntOrNull() ?: 0,
            eventType = parts[4].toIntOrNull() ?: 0,
            primaryPhy = parts[5].toIntOrNull() ?: 0,
            addressType = parts[6].toIntOrNull() ?: 0,
            advDataHex = parts[7]
        )
        addRecord(record)
    }

    private fun addRecord(record: CaptureRecord) {
        synchronized(recordsCache) {
            recordsCache.add(record)
            if (recordsCache.size > MAX_RECORDS) {
                recordsCache.removeAt(0)
            }
        }
        scheduleEmit()
    }

    /** 防抖 emit：最多每 300ms 向 _captureRecords 发射一次 */
    private fun scheduleEmit() {
        if (isEmitPending.getAndSet(true)) return
        scope.launch {
            delay(300L)
            synchronized(recordsCache) {
                _captureRecords.value = recordsCache.toList()
            }
            isEmitPending.set(false)
        }
    }
}
