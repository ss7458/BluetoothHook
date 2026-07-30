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
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
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

    /** App 进程的 Application Context，由 BluetoothHookApplication.onCreate 设置 */
    @Volatile
    private var _appContext: android.content.Context? = null

    /** 设置 App Context（替代直接字段访问） */
    fun setAppContext(context: android.content.Context) {
        _appContext = context.applicationContext
    }

    /** startServer 原子性保护，防止并发启动 */
    private val isStarting = AtomicBoolean(false)

    /** 自增记录 ID */
    private val nextRecordId = AtomicLong(1L)

    /** 线程安全的记录缓存 */
    private val recordsCache = mutableListOf<CaptureRecord>()

    /** 防抖 emit 标志 */
    private val isEmitPending = AtomicBoolean(false)

    /** 已连接客户端的 OutputStream 列表（线程安全），用于配置热推送 */
    private val connectedClients = CopyOnWriteArrayList<OutputStream>()

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
        if (!isStarting.compareAndSet(false, true)) {
            Logger.App.w(TAG, "startServer called but already starting, ignored")
            return
        }
        if (_isListening.value) {
            isStarting.set(false)
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

                        // 鉴权通过 → 先推送配置行，再处理 STATUS/CAP 行
                        launch {
                            var clientOs: OutputStream? = null
                            try {
                                // 推送当前配置给客户端（绕过 XSharedPreferences）
                                clientOs = pushConfigToClient(client)

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
                                clientOs?.let { connectedClients.remove(it) }
                                try { reader.close() } catch (_: Exception) {}
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
                isStarting.set(false)
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

    /**
     * AUTH 鉴权通过后，向客户端推送当前配置行。
     * 格式：CFG|key|value，以 CFG|END 结尾。
     * 客户端（CaptureSocket）解析后缓存到 configCache，
     * 完全绕过 XSharedPreferences 的 SELinux 跨进程读取问题。
     */
    private fun pushConfigToClient(client: Socket): OutputStream? {
        try {
            val os: OutputStream = client.getOutputStream()
            val appContext = _appContext ?: run {
                Logger.App.w(TAG, "pushConfigToClient: appContext not set, skipping config push")
                return null
            }
            val configBridge = ConfigBridge(appContext)

            writeConfigLines(os, configBridge)

            // 注册到已连接客户端列表，以便后续热推送
            connectedClients.add(os)

            val devicesJson = configBridge.getDevicesJson()
            Logger.App.d(TAG, "Config pushed to client: global=${configBridge.getGlobalEnabled()}, capture=${configBridge.isCaptureEnabled()}, devices=${if (devicesJson == "[]") "none" else "present"}")
            return os
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to push config to client", e)
            return null
        }
    }

    /**
     * 向所有已连接的 Hook 客户端推送最新配置。
     * 在设备列表或开关状态变更时调用，确保 Hook 进程实时获取最新配置。
     */
    fun pushConfigUpdate() {
        if (connectedClients.isEmpty()) return
        val appContext = _appContext ?: return
        val configBridge = ConfigBridge(appContext)

        // 在 IO 线程执行，避免阻塞 UI
        scope.launch {
            val deadClients = mutableListOf<OutputStream>()
            for (os in connectedClients) {
                try {
                    writeConfigLines(os, configBridge)
                    os.flush()
                } catch (e: Exception) {
                    Logger.App.d(TAG, "Client push failed, marking as dead: ${e.message}")
                    deadClients.add(os)
                }
            }
            // 清理已断开的客户端
            connectedClients.removeAll(deadClients.toSet())
            if (deadClients.isNotEmpty()) {
                Logger.App.d(TAG, "Removed ${deadClients.size} dead client(s), ${connectedClients.size} remaining")
            }
        }
    }

    /**
     * 将配置行写入 OutputStream。
     * 格式：CFG|key|value，以 CFG|END 结尾。
     */
    private fun writeConfigLines(os: OutputStream, configBridge: ConfigBridge) {
        val globalEnabled = configBridge.getGlobalEnabled()
        os.write("CFG|global_enabled|$globalEnabled\n".toByteArray(Charsets.UTF_8))

        val captureEnabled = configBridge.isCaptureEnabled()
        os.write("CFG|capture_enabled|$captureEnabled\n".toByteArray(Charsets.UTF_8))

        val devicesJson = configBridge.getDevicesJson()
        os.write("CFG|devices|$devicesJson\n".toByteArray(Charsets.UTF_8))

        val classicIntervalMs = configBridge.getClassicIntervalMs()
        os.write("CFG|classic_interval|$classicIntervalMs\n".toByteArray(Charsets.UTF_8))

        os.write("CFG|END\n".toByteArray(Charsets.UTF_8))
        os.flush()
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
            // 先释放标志，再发射数据：这样在发射期间到达的新记录可以启动新的 emit
            isEmitPending.set(false)
            synchronized(recordsCache) {
                _captureRecords.value = recordsCache.toList()
            }
        }
    }
}
