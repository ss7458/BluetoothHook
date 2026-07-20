package com.jingyu233.bluetoothhook.hook

import java.io.OutputStream
import java.net.Socket

/**
 * Best-effort localhost socket client for sending scan capture and status data
 * to the App UI process on port 8899.
 *
 * Silently handles all failures – the App (server) not listening is not an error.
 */
object CaptureSocket {

    const val PORT = 8899
    private const val HOST = "127.0.0.1"

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var outputStream: OutputStream? = null

    /**
     * Send a single line (UTF-8, LF-terminated) to the App UI.
     * Reconnects lazily if the socket is closed or was never opened.
     * Never throws – best-effort only.
     */
    fun sendLine(line: String) {
        try {
            var s = socket
            var os = outputStream

            // (Re)connect if needed
            if (s == null || s.isClosed || !s.isConnected) {
                s = Socket(HOST, PORT)
                os = s.getOutputStream()
                socket = s
                outputStream = os
            }

            val data = (line + "\n").toByteArray(Charsets.UTF_8)
            os?.write(data)
            os?.flush()
        } catch (e: Throwable) {
            // Any failure → tear down so next call reconnects
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
