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
)
