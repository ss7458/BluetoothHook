package com.jingyu233.bluetoothhook.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 虚拟蓝牙设备数据模型
 */
@Serializable
@Entity(tableName = "virtual_devices")
data class VirtualDevice(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val name: String,
    val mac: String,           // 格式: AA:BB:CC:DD:EE:FF
    val rssi: Int,             // 范围: -100 to 0
    val rssiMin: Int = 0,      // 信号抖动下限 (-100 to 0)，0 表示未启用抖动
    val rssiMax: Int = 0,      // 信号抖动上限 (-100 to 0)，0 表示未启用抖动
    val advDataHex: String,    // 十六进制字符串 (最多31字节 for legacy, 254字节 for extended)
    val intervalMs: Long,      // 广播间隔（毫秒）
    val enabled: Boolean = true,

    val scanResponseHex: String = "",  // 扫描响应数据 (最多31字节)
    val useExtendedAdvertising: Boolean = false,  // 是否使用扩展广播

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 获取实际注入用的 RSSI 值。
     * 若启用了信号抖动（rssiMin/rssiMax 均非0），则在范围内随机取值；
     * 否则返回固定 rssi。
     */
    fun getInjectedRssi(): Int {
        return if (rssiMin != 0 && rssiMax != 0) {
            val (lo, hi) = if (rssiMin <= rssiMax) rssiMin to rssiMax else rssiMax to rssiMin
            if (hi <= lo) rssi else (lo..hi).random()
        } else {
            rssi
        }
    }

    /**
     * 验证设备数据是否有效
     */
    fun isValid(): Boolean {
        return name.isNotBlank() &&
                mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")) &&
                rssi in -100..0 &&
                advDataHex.matches(Regex("^(?:[0-9A-Fa-f]{2})*$")) &&
                scanResponseHex.matches(Regex("^(?:[0-9A-Fa-f]{2})*$")) &&
                advDataHex.length % 2 == 0 &&
                scanResponseHex.length % 2 == 0 &&
                isAdvDataValid() &&
                intervalMs in 1..10240L
    }

    /**
     * 验证广播数据是否有效（根据模式）
     */
    private fun isAdvDataValid(): Boolean {
        val advBytes = getAdvDataByteLength()
        val scanBytes = getScanResponseByteLength()

        return when {
            useExtendedAdvertising -> advBytes <= 254 && scanBytes == 0
            scanBytes > 0 -> advBytes <= 31 && scanBytes <= 31
            else -> advBytes <= 31
        }
    }

    /**
     * 获取广播数据的字节长度
     */
    fun getAdvDataByteLength(): Int {
        return advDataHex.length / 2
    }

    /**
     * 获取扫描响应数据的字节长度
     */
    fun getScanResponseByteLength(): Int {
        return scanResponseHex.length / 2
    }

    /**
     * 获取总数据字节长度
     */
    fun getTotalDataByteLength(): Int {
        return getAdvDataByteLength() + getScanResponseByteLength()
    }

    /**
     * 获取当前使用的广播模式
     */
    fun getAdvertisingMode(): AdvertisingMode {
        return when {
            useExtendedAdvertising -> AdvertisingMode.EXTENDED
            getScanResponseByteLength() > 0 -> AdvertisingMode.LEGACY_WITH_SCAN_RESPONSE
            else -> AdvertisingMode.LEGACY
        }
    }

    /**
     * 自动分割广播数据
     * 当advDataHex长度超过31字节时，自动分割为adv + scan response
     * @return Pair(advDataHex, scanResponseHex)
     */
    fun autoSplitAdvData(): Pair<String, String> {
        val totalBytes = getAdvDataByteLength()

        return when {
            totalBytes <= 31 -> Pair(advDataHex, "")
            totalBytes <= 62 -> AdvDataUtils.splitAtAdBoundary(advDataHex, maxAdvBytes = 31)
            else -> Pair(advDataHex, "")
        }
    }
}