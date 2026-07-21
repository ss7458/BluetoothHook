package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.CaptureProtocol
import java.io.OutputStream
import java.net.Socket

/**
 * Best-effort localhost socket client for sending scan capture and status data
 * to the App UI process on port 8899.
 *
 * Silently handles all failures – the App (server) not listening is not an error.
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
     * Send a single line (UTF-8, LF-terminated) to the App UI.
     * Reconnects lazily if the socket is closed or was never opened.
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
                os = s.getOutputStream()

                // AUTH handshake immediately after connect
                val authBytes = "${CaptureProtocol.AUTH_PREFIX}${CaptureProtocol.AUTH_TOKEN}\n"
                    .toByteArray(Charsets.UTF_8)
                os!!.write(authBytes)
                os!!.flush()

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
}
