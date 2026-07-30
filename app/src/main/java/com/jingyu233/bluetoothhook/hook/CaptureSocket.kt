package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.CaptureProtocol
import com.jingyu233.bluetoothhook.utils.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 从 App 进程同步过来的配置快照
 * 通过 TCP 连接在 AUTH 握手后接收，完全绕过 XSharedPreferences
 */
data class SyncedConfig(
    val globalEnabled: Boolean = true,
    val captureEnabled: Boolean = false,
    val devicesJson: String = "[]",
    val classicIntervalMs: Int = 5000,
    val timestamp: Long = 0L
)

/**
 * Best-effort localhost socket client for sending scan capture and status data
 * to the App UI process on port 8899.
 *
 * Silently handles all failures – the App (server) not listening is not an error.
 *
 * 在 AUTH 握手后读取 App 推送的配置行（CFG|key|value），
 * 缓存到 [configCache] 供 BluetoothScanHook / VirtualDeviceInjector 使用。
 *
 * 所有 TCP I/O 在独立后台线程执行，避免 Android 15+ 的 NetworkOnMainThreadException。
 */
object CaptureSocket {

    private const val TAG = "BTHook:Hook:Socket"
    private const val HOST = "127.0.0.1"

    // 单线程后台执行器，用于所有 socket I/O（避免主线程网络操作）
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BTHook-Socket").apply { isDaemon = true }
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    /** 后台读线程，持续读取 App 推送的 CFG 配置更新 */
    @Volatile
    private var readerThread: Thread? = null

    /** Timestamp of last connect failure (ms), 0 = no prior failure */
    private var lastConnectFailMs = 0L

    /**
     * 从 App 进程同步过来的最新配置。
     * BluetoothScanHook 和 VirtualDeviceInjector 优先读取此缓存，
     * 仅在缓存不可用时回退到 XSharedPreferences。
     */
    @Volatile
    var configCache: SyncedConfig = SyncedConfig()

    /**
     * Send a single line (UTF-8, LF-terminated) to the App UI.
     * 在后台线程异步执行，避免主线程 NetworkOnMainThreadException。
     * Reconnects lazily if the socket is closed or was never opened.
     * On first connect, reads config lines from server after AUTH handshake.
     * Non-blocking, thread-safe. Never throws – best-effort only.
     */
    fun sendLine(line: String) {
        executor.submit { sendLineSync(line) }
    }

    /**
     * 实际的发送逻辑，在 executor 后台线程上执行。
     */
    private fun sendLineSync(line: String) {
        try {
            var s = socket
            var os = outputStream

            // (Re)connect if needed
            if (s == null || s.isClosed || !s.isConnected) {
                // Backoff: skip reconnect if last failure was < 1s ago
                val now = System.currentTimeMillis()
                if (lastConnectFailMs != 0L && now - lastConnectFailMs < 1000) {
                    return
                }

                try { s?.close() } catch (_: Throwable) {}
                Logger.Hook.d(TAG, "Attempting TCP connect to $HOST:${CaptureProtocol.PORT} ...")
                s = Socket(HOST, CaptureProtocol.PORT)
                s.soTimeout = 5000 // 5s read timeout for initial config (reset to 0 after)
                os = s.getOutputStream()
                Logger.Hook.i(TAG, "TCP connected to $HOST:${CaptureProtocol.PORT}")

                // AUTH handshake immediately after connect
                val authBytes = "${CaptureProtocol.AUTH_PREFIX}${CaptureProtocol.AUTH_TOKEN}\n"
                    .toByteArray(Charsets.UTF_8)
                os?.write(authBytes)
                os?.flush()
                Logger.Hook.d(TAG, "AUTH sent to server")

                // Read config lines pushed by the App
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), Charsets.UTF_8)
                )
                readConfigFromServer(reader)

                Logger.Hook.i(TAG, "AUTH+config sync complete, configCache updated: globalEnabled=${configCache.globalEnabled}, captureEnabled=${configCache.captureEnabled}, devices=${if (configCache.devicesJson == "[]") "none" else "present"}, timestamp=${configCache.timestamp}")

                // 初始配置读取完毕后禁用超时，让后台读线程无限等待服务端热推送
                // 避免 5 秒无数据时 readLine() 抛 SocketTimeoutException 导致读线程死亡
                s.soTimeout = 0

                // 启动后台读线程，持续接收 App 热推送的配置更新
                startReaderThread(reader, s)

                socket = s
                outputStream = os
                lastConnectFailMs = 0L
            }

            val data = (line + "\n").toByteArray(Charsets.UTF_8)
            os?.write(data)
            os?.flush()
        } catch (e: Throwable) {
            Logger.Hook.w(TAG, "sendLine failed: ${e.javaClass.simpleName}: ${e.message}")
            // Any failure --> record timestamp, tear down so next call reconnects
            lastConnectFailMs = System.currentTimeMillis()
            readerThread?.interrupt()
            readerThread = null
            try {
                outputStream?.close()
            } catch (_: Throwable) {}
            try {
                socket?.close()
            } catch (_: Throwable) {}
            socket = null
            outputStream = null
        }
    }

    /**
     * 后台读线程：持续从 socket 读取 App 热推送的 CFG 配置行。
     * 当 socket 断开时自动清理状态，下次 sendLine 会重连。
     */
    private fun startReaderThread(reader: BufferedReader, socket: Socket) {
        readerThread?.interrupt()
        val thread = Thread({
            try {
                var line: String?
                while (!Thread.currentThread().isInterrupted) {
                    line = reader.readLine() ?: break
                    if (line.startsWith("CFG|")) {
                        processConfigLine(line)
                    }
                }
            } catch (_: Throwable) {
                // Socket closed or error
            }
            // Reader thread ended — socket 已断开，清理状态以便重连
            Logger.Hook.d(TAG, "Reader thread ended, socket disconnected")
            try { socket.close() } catch (_: Throwable) {}
            this.socket = null
            this.outputStream = null
        }, "BTHook-SocketReader").apply {
            isDaemon = true
            start()
        }
        readerThread = thread
    }

    /**
     * 处理单条 CFG 配置行，更新 configCache。
     * 用于后台读线程接收 App 热推送的配置更新。
     */
    private fun processConfigLine(line: String) {
        try {
            if (line == "CFG|END") {
                Logger.Hook.d(TAG, "Config hot-push complete")
                return
            }
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return

            val current = configCache
            when (parts[1]) {
                "global_enabled" -> {
                    val value = parts[2].toBoolean()
                    configCache = current.copy(globalEnabled = value)
                    Logger.Hook.d(TAG, "Config hot-push: global_enabled=$value")
                }
                "capture_enabled" -> {
                    val value = parts[2].toBoolean()
                    configCache = current.copy(captureEnabled = value)
                    Logger.Hook.d(TAG, "Config hot-push: capture_enabled=$value")
                }
                "devices" -> {
                    val devicesJson = parts[2]
                    val devCount = devicesJson.count { it == '{' }
                    configCache = current.copy(devicesJson = devicesJson, timestamp = System.currentTimeMillis())
                    Logger.Hook.d(TAG, "Config hot-push: devices ($devCount device(s))")
                }
                "classic_interval" -> {
                    val interval = parts[2].toIntOrNull() ?: 5000
                    configCache = current.copy(classicIntervalMs = interval)
                    Logger.Hook.d(TAG, "Config hot-push: classic_interval=${interval}ms")
                }
            }
        } catch (e: Exception) {
            Logger.Hook.w(TAG, "processConfigLine error: ${e.message}")
        }
    }

    /**
     * 从服务器读取 CFG|key|value 行直到 CFG|END。
     * 解析后更新 [configCache]。
     */
    private fun readConfigFromServer(reader: BufferedReader) {
        try {
            var line: String?
            val current = configCache
            var globalEnabled = current.globalEnabled
            var captureEnabled = current.captureEnabled
            var devicesJson = current.devicesJson
            var classicIntervalMs = current.classicIntervalMs
            var timestamp = current.timestamp
            var configLineCount = 0

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                if (l == "CFG|END") { configLineCount++; break }
                if (!l.startsWith("CFG|")) continue

                val parts = l.split("|", limit = 3)
                if (parts.size < 3) continue
                configLineCount++

                when (parts[1]) {
                    "global_enabled" -> {
                        globalEnabled = parts[2].toBoolean()
                        Logger.Hook.d(TAG, "Config: global_enabled=$globalEnabled")
                    }
                    "capture_enabled" -> {
                        captureEnabled = parts[2].toBoolean()
                        Logger.Hook.d(TAG, "Config: capture_enabled=$captureEnabled")
                    }
                    "devices" -> {
                        devicesJson = parts[2]
                        timestamp = System.currentTimeMillis()
                        val devCount = parts[2].count { it == '{' }
                        Logger.Hook.d(TAG, "Config: devices ($devCount device(s))")
                    }
                    "classic_interval" -> {
                        classicIntervalMs = parts[2].toIntOrNull() ?: 5000
                        Logger.Hook.d(TAG, "Config: classic_interval=${classicIntervalMs}ms")
                    }
                }
            }

            configCache = SyncedConfig(
                globalEnabled = globalEnabled,
                captureEnabled = captureEnabled,
                devicesJson = devicesJson,
                classicIntervalMs = classicIntervalMs,
                timestamp = timestamp
            )
            Logger.Hook.d(TAG, "readConfigFromServer finished, $configLineCount line(s) read")
        } catch (e: Exception) {
            Logger.Hook.w(TAG, "readConfigFromServer error: ${e.message}")
            // 读取配置失败时保留旧缓存
        }
    }

    /**
     * 关闭读线程和 socket，清理所有状态。
     */
    fun shutdown() {
        readerThread?.interrupt()
        readerThread = null
        try { outputStream?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        outputStream = null
    }
}