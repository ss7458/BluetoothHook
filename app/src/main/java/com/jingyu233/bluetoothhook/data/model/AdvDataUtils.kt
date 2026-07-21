package com.jingyu233.bluetoothhook.data.model

/**
 * BLE 广播 AD 结构工具
 */
object AdvDataUtils {

    /**
     * 按 AD 结构边界分割数据，避免在结构中间截断。
     * @param hex 完整十六进制广播数据
     * @param maxAdvBytes 广播段最大字节数（Legacy 为 31）
     * @return Pair(广播段 hex, 扫描响应段 hex)
     */
    fun splitAtAdBoundary(hex: String, maxAdvBytes: Int = 31): Pair<String, String> {
        if (hex.isEmpty()) return Pair("", "")
        val bytes = hex.chunked(2).mapNotNull { chunk ->
            if (chunk.length == 2) chunk.toInt(16) else null
        }
        if (bytes.isEmpty()) return Pair("", "")

        var advEnd = 0
        var i = 0
        while (i < bytes.size) {
            val length = bytes[i]
            if (length == 0) break
            val adSize = length + 1
            if (advEnd + adSize > maxAdvBytes) break
            advEnd += adSize
            i += adSize
        }

        if (advEnd == 0) {
            advEnd = minOf(maxAdvBytes, bytes.size)
        }

        fun toHex(list: List<Int>) = list.joinToString("") { "%02X".format(it) }
        return Pair(toHex(bytes.take(advEnd)), toHex(bytes.drop(advEnd)))
    }
}
