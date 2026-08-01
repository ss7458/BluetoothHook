package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.data.model.ScaleSimulatorConfig
import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XposedHelpers
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 体重秤模拟注入器
 *
 * 依据 `体重/BLE_scale_forgery_guide.md`（3.pcapng 实测）模拟真实体重秤广播：
 *   - 载荷格式：<体重 大端uint16 ÷100=kg><阻抗 大端uint16 ÷10=Ω><0A11><状态1B><MAC6B>，AD 前缀 0DFF
 *   - 广播类型：Extended Advertising（真机 HCI 子事件 0x0d，非 legacy）
 *   - 广播间隔 ~100ms（真实 median 77ms，p25-p75 55-175ms）
 *   - RSSI 在基础值（默认 -63）±22dB 随机抖动（真实 -85~-56，均值 -63.7）
 *   - 上秤后体重从 0 爬升到目标值，先快后慢（ease-out 曲线，~1.8s），稳定后保持
 *   - 阻抗：爬升期与稳定期均为配置值（3.pcapng 实测 254/261 帧为 600Ω，仅个别帧 0Ω）
 *   - 状态字节：爬升期 0x24，稳定期 0x25（实测 0x25×171 / 0x24×90）
 *
 * 配置来源优先级：TCP 同步 configCache.scaleJson > 文件回退 /data/local/tmp/bthook_config.json
 */
class ScaleSimulatorInjector(
    private val scanResultBuilder: ScanResultBuilder
) {
    companion object {
        private val TAG = Logger.Tags.HOOK_INJECTOR
        private val json = Json { ignoreUnknownKeys = true }
    }

    // ── 状态机 ─────────────────────────────────────────────
    private enum class Phase { RAMPING, STABLE }

    @Volatile
    private var phase: Phase = Phase.STABLE

    /** 本次"称重"会话开始时间（毫秒），用于爬升计时 */
    @Volatile
    private var sessionStartMs = 0L

    /** 上次注入时间，控制广播间隔 */
    @Volatile
    private var lastInjectMs = 0L

    /** 上次读到的配置（避免每帧重读） */
    @Volatile
    private var cachedConfig: ScaleSimulatorConfig = ScaleSimulatorConfig()

    /**
     * 周期注入：根据当前爬升进度构造一帧体重秤广播并投递给所有扫描客户端。
     * 由 BluetoothScanHook 周期注入线程调用。
     *
     * @return 是否注入了至少一个客户端
     */
    fun injectOnce(
        scanQueue: Collection<*>,
        scannerMap: Any
    ): Boolean {
        try {
            val config = readConfig() ?: return false
            if (!config.enabled) return false

            // 广播间隔控制
            val now = System.currentTimeMillis()
            if (now - lastInjectMs < config.intervalMs) return false
            lastInjectMs = now

            // 配置变更时重置会话（重新从 0 爬升）
            if (config != cachedConfig) {
                cachedConfig = config
                phase = Phase.RAMPING
                sessionStartMs = now
                Logger.Hook.i(TAG, "Scale session started: target=${config.targetWeightKg}kg, imp=${config.impedanceOhm}Ω")
            }

            // 计算当前体重 / 阻抗 / 状态
            val (weightKg, impedanceOhm, statusByte) = computeFrame(config, now)

            // 手动模式：直接使用用户提供的完整 AD hex
            val advDataHex = if (config.manualMode && config.manualAdvHex.isNotBlank()) {
                config.manualAdvHex
            } else {
                config.buildAdvHex(weightKg, impedanceOhm, statusByte)
            }

            // RSSI 抖动：真实抓包 -85~-56，均值 -63.7；baseRssi=-63 时 ±22 覆盖全范围
            val rssi = (config.baseRssi + Random.nextInt(-22, 8)).coerceIn(-100, -30)

            // 构造 ScanResult 并投递（Extended Advertising，与真机广播类型一致）
            val scanResult = scanResultBuilder.buildScanResult(
                macAddress = config.mac,
                rssi = rssi,
                advDataHex = advDataHex,
                useExtendedAdvertising = true,
                deviceName = null
            ) ?: return false

            var deliveredCount = 0
            for (scanClient in scanQueue) {
                if (scanClient == null) continue
                try {
                    deliverToClient(scanClient, scannerMap, scanResult)
                    deliveredCount++
                } catch (e: Throwable) {
                    Logger.Hook.d(TAG, "Scale deliver failed: ${e.message}")
                }
            }

            if (deliveredCount > 0 && now % 2000 < config.intervalMs) {
                Logger.Hook.i(TAG, "Scale injected: ${"%.2f".format(weightKg)}kg imp=${impedanceOhm}Ω rssi=$rssi -> $deliveredCount client(s)")
            }
            return deliveredCount > 0
        } catch (e: Throwable) {
            Logger.Hook.d(TAG, "Scale inject error: ${e.message}")
            return false
        }
    }

    /**
     * 读取体重秤配置。
     * 优先级：TCP 同步 configCache > 文件回退。
     */
    private fun readConfig(): ScaleSimulatorConfig? {
        // 1) TCP 同步缓存
        val syncedJson = CaptureSocket.configCache.scaleJson
        if (syncedJson.isNotBlank()) {
            return try {
                json.decodeFromString<ScaleSimulatorConfig>(syncedJson)
            } catch (e: Exception) {
                Logger.Hook.d(TAG, "Scale config parse error: ${e.message}")
                null
            }
        }

        // 2) 文件回退（/data/local/tmp/bthook_config.json）
        return try {
            val fallbackFile = java.io.File("/data/local/tmp/bthook_config.json")
            if (!fallbackFile.exists()) return null
            val text = fallbackFile.readText()
            val obj = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(text)
            val raw = obj["scale_config"]?.toString() ?: return null
            // JsonElement.toString() 包裹引号，需要剥离
            val cleaned = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\\\"", "\"")
            } else raw
            if (cleaned.isBlank()) null
            else json.decodeFromString<ScaleSimulatorConfig>(cleaned)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 根据爬升进度计算当前帧的 (体重kg, 阻抗Ω, 状态字节)。
     *
     * 真实爬升行为（3.pcapng）：
     *   0.00 → 0.30 → 7.65 → 8.00 → ... → 10.15，约 1.7-2s，先快后慢，稳定后保持。
     */
    private fun computeFrame(
        config: ScaleSimulatorConfig,
        now: Long
    ): Triple<Double, Int, Int> {
        val elapsed = now - sessionStartMs

        if (phase == Phase.RAMPING) {
            val t = (elapsed.toFloat() / config.rampDurationMs).coerceIn(0f, 1f)
            if (t >= 1f) {
                // 达到目标，进入稳定阶段
                phase = Phase.STABLE
                Logger.Hook.i(TAG, "Scale reached target ${config.targetWeightKg}kg (stable)")
            } else {
                // ease-out 曲线：先快后慢 (1-(1-t)^2)
                val eased = 1f - (1f - t) * (1f - t)
                val weight = config.targetWeightKg * eased
                // 爬升期：阻抗同配置值（3.pcapng 实测爬升期也是 600Ω，非 0），状态 0x24（称重中）
                return Triple(weight, config.impedanceOhm, ScaleSimulatorConfig.STATUS_WEIGHING)
            }
        }

        // 稳定阶段：目标体重，配置阻抗，状态 0x25（体重确认/稳定，实测稳定期为连续 0x25）
        return Triple(
            config.targetWeightKg,
            config.impedanceOhm,
            ScaleSimulatorConfig.STATUS_CONFIRMED
        )
    }

    /**
     * 将 ScanResult 投递给单个扫描客户端（与 VirtualDeviceInjector 相同的链路）。
     */
    private fun deliverToClient(
        scanClient: Any,
        scannerMap: Any,
        scanResult: Any
    ) {
        val scannerId = tryGetIntField(scanClient, "scannerId")
            ?: tryGetIntField(scanClient, "mScannerId")
            ?: return

        val scannerApp = XposedHelpers.callMethod(scannerMap, "getById", scannerId) ?: return

        val callback = tryGetObjectField(scannerApp, "callback")
            ?: tryGetObjectField(scannerApp, "mCallback")
            ?: return

        XposedHelpers.callMethod(callback, "onScanResult", scanResult)
    }

    private fun tryGetIntField(obj: Any, fieldName: String): Int? {
        return try {
            XposedHelpers.getIntField(obj, fieldName)
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryGetObjectField(obj: Any, fieldName: String): Any? {
        return try {
            XposedHelpers.getObjectField(obj, fieldName)
        } catch (_: Throwable) {
            null
        }
    }
}
