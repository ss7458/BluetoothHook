package com.jingyu233.bluetoothhook

/**
 * 抓包 socket 的共享协议常量。
 * Hook 进程（客户端）与 App 进程（服务端）都必须引用同一份，避免两端不一致。
 */
object CaptureProtocol {
    const val PORT = 8899
    const val AUTH_TOKEN = "bluetooth_hook_local_capture_2024"
    const val AUTH_PREFIX = "AUTH|"
    const val STATUS_PREFIX = "STATUS|"
    const val CAP_PREFIX = "CAP|"
}
