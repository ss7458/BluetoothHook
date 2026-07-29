package com.jingyu233.bluetoothhook.data.model

import kotlinx.serialization.Serializable

/**
 * BLE 扫描抓包记录
 * 由 Hook 进程通过 localhost socket 推送，CaptureBridge 解析并暴露给 UI
 */
@Serializable
data class CaptureRecord(
    val id: Long = 0L,
    val timestamp: Long,
    val mac: String,
    val rssi: Int,
    val eventType: Int,
    val primaryPhy: Int,
    val addressType: Int,
    val advDataHex: String
) {
    /** eventType 中文描述 */
    val eventTypeLabel: String get() = when {
        eventType == 0x13 -> "传统+可连接+可扫描"
        eventType == 0x10 -> "传统广播"
        eventType == 0x00 -> "可连接未定向"
        eventType == 0x01 -> "可连接定向"
        eventType == 0x02 -> "不可连接未定向"
        eventType == 0x03 -> "可扫描未定向"
        eventType == 0x04 -> "扫描响应"
        else -> {
            val parts = mutableListOf<String>()
            if (eventType and 0x10 != 0) parts.add("传统")
            if (eventType and 0x01 != 0) parts.add("可连接")
            if (eventType and 0x02 != 0) parts.add("可扫描")
            if (eventType and 0x04 != 0) parts.add("定向")
            if (eventType and 0x08 != 0) parts.add("扫描响应")
            parts.joinToString("+").ifEmpty { "未知(0x${eventType.toString(16)})" }
        }
    }

    /** primaryPhy 中文描述 */
    val phyLabel: String get() = when (primaryPhy) {
        1 -> "1M"
        2 -> "2M"
        3 -> "编码(Coded)"
        else -> "未知($primaryPhy)"
    }

    /** addressType 中文描述 */
    val addressTypeLabel: String get() = when (addressType) {
        0 -> "公共(Public)"
        1 -> "随机(Random)"
        else -> "未知($addressType)"
    }
}
