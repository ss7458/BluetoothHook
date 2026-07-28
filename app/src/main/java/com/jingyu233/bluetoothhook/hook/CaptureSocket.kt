package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.CaptureProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

/**
 * 从 App 进程同步过来的配置快照
 * 通过 TCP 连接在 AUTH 握手后接收，完全绕过 XSharedPreferences
 */
data class SyncedConfig(
    val globalEnabled: Boolean = true,
    val captureEnabled: Boolean = false,
    val devicesJson: String = "[]",
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
 */
object CaptureSocket {

    private const val HOST = "127.0.0.1"

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

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
     * Reconnects lazily if the socket is closed or was never opened.
     * On first connect, reads config lines from server after AUTH handshake.
     * Thread-safe via @Synchronized.
     * Never throws – best-effort only.
     */
    @Synchronized
    fun sendLine(line: String) {
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

                s = Socket(HOST, CaptureProtocol.PORT)
                s.soTimeout = 5000 // 5s read timeout for config lines
                os = s.getOutputStream()

                // AUTH handshake immediately after connect
                val authBytes = "${CaptureProtocol.AUTH_PREFIX}${CaptureProtocol.AUTH_TOKEN}\n"
                    .toByteArray(Charsets.UTF_8)
                os?.write(authBytes)
                os?.flush()

                // ── 读取 App 推送的配置行 ──
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), Charsets.UTF_8)
                )
                readConfigFromServer(reader)

                socket = s
                outputStream = os
                lastConnectFailMs = 0L
            }

            val data = (line + "\n").toByteArray(Charsets.UTF_8)
            os?.write(data)
            os?.flush()
        } catch (e: Throwable) {
            // Any failure → record timestamp, tear down so next call reconnects
            lastConnectFailMs = System.currentTimeMillis()
            try {
                outputStream?.close()
            } catch (_: Throwable) { }
            try {
                socket?.close()
            } catch (_: Throwable) { }
            socket = null
            outputStream = null
        }
    }

    /**
     * 从服务器读取 CFG|key|value 行直到 CFG|END。
     * 解析后更新 [configCache]。
     */
    private fun readConfigFromServer(reader: BufferedReader) {
        try {
            var line: String?
            var globalEnabled = configCache.globalEnabled
            var captureEnabled = configCache.captureEnabled
            var devicesJson = configCache.devicesJson
            var timestamp = configCache.timestamp

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                if (l == "CFG|END") break
                if (!l.startsWith("CFG|")) continue

                val parts = l.split("|", limit = 3)
                if (parts.size < 3) continue

                when (parts[1]) {
                    "global_enabled" -> globalEnabled = parts[2].toBoolean()
                    "capture_enabled" -> captureEnabled = parts[2].toBoolean()
                    "devices" -> {
                        devicesJson = parts[2]
                        timestamp = System.currentTimeMillis()
                    }
                }
            }

            configCache = SyncedConfig(
                globalEnabled = globalEnabled,
                captureEnabled = captureEnabled,
                devicesJson = devicesJson,
                timestamp = timestamp
            )
        } catch (_: Exception) {
            // 读取配置失败时保留旧缓存
        }
    }
}