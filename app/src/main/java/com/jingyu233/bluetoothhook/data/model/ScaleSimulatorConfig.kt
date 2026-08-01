package com.jingyu233.bluetoothhook.data.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * 体重秤模拟器配置
 *
 * 协议基于 `体重/BLE_scale_forgery_guide.md`（3.pcapng 实测）：
 *   载荷(13字节) = <体重 大端uint16 ÷100=kg> + <阻抗 大端uint16 ÷10=Ω> + <0A11> + <状态1B> + <MAC6B>
 *   空口 AD 结构 = 0D FF + 载荷 (15字节)
 *
 * 真实秤行为（3.pcapng 261 帧实测，用于模拟）：
 *   - 广播类型 Extended Advertising（HCI 子事件 0x0d）
 *   - 广播间隔 median 77ms（p25 55ms / p75 175ms）
 *   - RSSI -85~-56 dBm，均值 -63.7
 *   - 上秤后体重从 0 开始，先快后慢爬升（约 1.7-2s）到目标值，稳定后保持
 *   - 阻抗：254/261 帧为 600Ω（0x1770），仅 7 帧为 0；爬升期与稳定期都是 600Ω
 *   - 状态字节：称重/爬升期 0x24（90 帧），稳定/确认期 0x25（171 帧）
 */
@Serializable
data class ScaleSimulatorConfig(
    val enabled: Boolean = false,
    /** 广播源 MAC（必须与载荷内嵌 MAC 一致） */
    val mac: String = "98:F6:7A:A3:9E:F4",
    /** 目标体重 (kg)，2 位小数 */
    val targetWeightKg: Double = 10.15,
    /** 阻抗 (Ω)，÷10 写入载荷；真实秤爬升期与稳定期均为 600 */
    val impedanceOhm: Int = 600,
    /** RSSI 基础值 (dBm)，实测均值 -63.7 */
    val baseRssi: Int = -63,
    /** 广播间隔 (ms)，真实 ~100ms */
    val intervalMs: Long = 100,
    /** 爬升时长 (ms)，真实约 1.7-2s */
    val rampDurationMs: Long = 1800,
    /** 手动模式：直接使用 [manualAdvHex] 作为广播数据（不自动生成） */
    val manualMode: Boolean = false,
    /** 手动模式完整 AD hex（30 位十六进制 = 0D FF + 13字节载荷） */
    val manualAdvHex: String = ""
) {
    companion object {
        const val STATUS_WEIGHING = 0x24        // 称重中（体重爬升/未稳定）
        const val STATUS_CONFIRMED = 0x25       // 体重确认/稳定（3.pcapng 实测稳定期以 0x25 为主）
        const val USER_PRODUCT_ID = "0A11"      // 固定用户/产品 ID
    }

    /** 生成指定体重/阻抗/状态对应的完整 AD hex（大写） */
    fun buildAdvHex(
        weightKg: Double,
        impedanceOhm: Int,
        statusByte: Int
    ): String {
        val weightRaw = (weightKg * 100).roundToInt().coerceIn(0, 65535)
        val impRaw = (impedanceOhm * 10).coerceIn(0, 65535)
        val macHex = mac.replace(":", "").uppercase()
        return String.format(
            "0DFF%04X%04X%s%02X%s",
            weightRaw, impRaw, USER_PRODUCT_ID, statusByte, macHex
        )
    }

    /** 生成爬升期（未测阻抗 + 爬升状态）的 AD hex */
    fun buildRampingAdvHex(weightKg: Double): String =
        buildAdvHex(weightKg, 0, STATUS_WEIGHING)

    /** 生成稳定帧的 AD hex（稳定期状态 0x25） */
    fun buildStableAdvHex(weightKg: Double): String =
        buildAdvHex(weightKg, impedanceOhm, STATUS_CONFIRMED)
}

/**
 * 解析体重秤广播数据（支持完整 AD hex 或纯 13 字节载荷 hex）
 */
@Serializable
data class ParsedScaleData(
    val weightKg: Double,
    val impedanceOhm: Int,
    val statusByte: Int,
    val mac: String,
    val valid: Boolean
)

object ScalePayloadCodec {

    /**
     * 解析 hex 字符串为体重秤数据。
     * 支持两种输入：
     *   - 完整 AD：`0DFF` + 13字节载荷（30 hex）
     *   - 纯载荷：13字节（26 hex）
     */
    fun parse(hexInput: String): ParsedScaleData {
        var hex = hexInput.replace("\\s+".toRegex(), "").replace(":", "")
        if (hex.isEmpty()) return ParsedScaleData(0.0, 0, 0, "", false)

        // 去掉 0DFF 前缀（完整 AD 结构）
        if (hex.startsWith("0DFF", ignoreCase = true)) {
            hex = hex.substring(4)
        }
        if (hex.length < 26) return ParsedScaleData(0.0, 0, 0, "", false)
        hex = hex.substring(0, 26)

        val weightRaw = hex.substring(0, 4).toIntOrNull(16) ?: 0
        val impRaw = hex.substring(4, 8).toIntOrNull(16) ?: 0
        val statusByte = hex.substring(12, 14).toIntOrNull(16) ?: 0
        val macHex = hex.substring(14, 26)
        val mac = if (macHex.matches(Regex("^[0-9A-Fa-f]{12}$"))) {
            macHex.chunked(2).joinToString(":").uppercase()
        } else ""

        return ParsedScaleData(
            weightKg = weightRaw / 100.0,
            impedanceOhm = impRaw / 10,
            statusByte = statusByte,
            mac = mac,
            valid = weightRaw in 0..65535 && mac.isNotEmpty()
        )
    }
}
