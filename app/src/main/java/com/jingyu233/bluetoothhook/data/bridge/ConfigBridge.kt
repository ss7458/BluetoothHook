package com.jingyu233.bluetoothhook.data.bridge

import android.content.Context
import com.jingyu233.bluetoothhook.data.model.ScaleSimulatorConfig
import com.jingyu233.bluetoothhook.data.model.VirtualDevice
import com.jingyu233.bluetoothhook.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 配置桥接器
 * 负责在UI进程和Hook进程之间同步配置数据
 * 使用LSPosed的XSharedPreferences特性读取配置（MODE_PRIVATE）
 */
class ConfigBridge(private val context: Context) {

    companion object {
        private val TAG = Logger.Tags.DATA_BRIDGE

        // SharedPreferences文件名（Hook进程将通过XSharedPreferences访问）
        const val PREF_NAME = "hook_config"

        // SharedPreferences键名
        private const val KEY_DEVICES = "devices"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_CLASSIC_INTERVAL_MS = "classic_interval_ms"
        private const val KEY_INJECTION_MODE = "injection_mode"
        private const val KEY_SCALE_CONFIG = "scale_config"
        private const val KEY_LAST_UPDATED = "last_updated"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }

    /**
     * 从UI进程写入设备配置
     * 将Room数据库的设备列表同步到SharedPreferences
     * Hook进程通过XSharedPreferences读取此文件
     */
    fun writeDeviceConfig(devices: List<VirtualDevice>) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

            // 序列化设备列表为JSON
            val devicesJson = json.encodeToString(devices)

            // 使用commit()而不是apply()确保立即写入
            // 注意：只写入设备列表相关数据，不重新写入 globalEnabled（避免 read-modify-write 竞态）
            prefs.edit()
                .putString(KEY_DEVICES, devicesJson)
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit() // 立即同步写入

            Logger.App.d(TAG, "Wrote ${devices.size} devices to SharedPreferences (MODE_PRIVATE)")

            // 确保文件对Hook进程可读
            ensurePrefsWorldReadable()
            // 写入文件回退（/data/local/tmp/bthook_config.json）
            writeFallbackConfigFile()

            // 通过 TCP 热推送配置到已连接的 Hook 进程
            com.jingyu233.bluetoothhook.data.bridge.CaptureBridge.pushConfigUpdate()

        } catch (e: SecurityException) {
            Logger.App.e(TAG, "MODE_PRIVATE — SecurityException unexpected, check LSPosed?", e)
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to write device config", e)
        }
    }

    /**
     * 设置全局开关状态
     */
    fun setGlobalEnabled(enabled: Boolean) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_GLOBAL_ENABLED, enabled)
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit()

            // 确保SharedPreferences文件对Hook进程可读（XSharedPreferences需要）
            ensurePrefsWorldReadable()
            writeFallbackConfigFile()

            Logger.App.d(TAG, "Set global_enabled = $enabled")

            // 通过 TCP 热推送配置到已连接的 Hook 进程
            com.jingyu233.bluetoothhook.data.bridge.CaptureBridge.pushConfigUpdate()

        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to set global enabled state", e)
        }
    }

    /**
     * 获取全局开关状态
     */
    fun getGlobalEnabled(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_GLOBAL_ENABLED, true)
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get global enabled state", e)
            true // 默认启用
        }
    }

    /**
     * 设置抓包开关状态
     */
    fun setCaptureEnabled(enabled: Boolean) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_CAPTURE_ENABLED, enabled)
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit()

            // 确保文件对Hook进程可读
            ensurePrefsWorldReadable()
            writeFallbackConfigFile()

            Logger.App.d(TAG, "Set capture_enabled = $enabled")

            // 通过 TCP 热推送配置到已连接的 Hook 进程
            com.jingyu233.bluetoothhook.data.bridge.CaptureBridge.pushConfigUpdate()

        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to set capture enabled state", e)
        }
    }

    /**
     * 获取抓包开关状态
     */
    fun isCaptureEnabled(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_CAPTURE_ENABLED, false)
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get capture enabled state", e)
            false // 默认关闭
        }
    }

    /**
     * 设置经典蓝牙发现广播间隔(毫秒)
     */
    fun setClassicIntervalMs(ms: Int) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_CLASSIC_INTERVAL_MS, ms)
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit()
            ensurePrefsWorldReadable()
            writeFallbackConfigFile()
            Logger.App.d(TAG, "Set classic_interval = ${ms}ms")

            // 通过 TCP 热推送配置到已连接的 Hook 进程
            com.jingyu233.bluetoothhook.data.bridge.CaptureBridge.pushConfigUpdate()
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to set classic interval", e)
        }
    }

    /**
     * 获取经典蓝牙发现广播间隔(毫秒)，默认 5000
     */
    fun getClassicIntervalMs(): Int {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getInt(KEY_CLASSIC_INTERVAL_MS, 5000)
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get classic interval", e)
            5000
        }
    }

    /**
     * 设置注入模式（insert / override）
     */
    fun setInjectionMode(mode: String) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_INJECTION_MODE, mode)
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit()
            ensurePrefsWorldReadable()
            writeFallbackConfigFile()
            Logger.App.d(TAG, "Set injection_mode = $mode")
            com.jingyu233.bluetoothhook.data.bridge.CaptureBridge.pushConfigUpdate()
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to set injection mode", e)
        }
    }

    /**
     * 获取注入模式，默认 "insert"
     */
    fun getInjectionMode(): String {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_INJECTION_MODE, "insert") ?: "insert"
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get injection mode", e)
            "insert"
        }
    }

    /**
     * 获取配置最后更新时间
     */
    fun getLastUpdatedTime(): Long {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getLong(KEY_LAST_UPDATED, 0L)
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get last updated time", e)
            0L
        }
    }

    /**
     * 获取设备列表 JSON（供 TCP 配置同步使用）
     */
    fun getDevicesJson(): String {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_DEVICES, "[]") ?: "[]"
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get devices JSON", e)
            "[]"
        }
    }

    /**
     * 清空所有配置
     *
     * 注意：此方法清除 SharedPreferences 中的配置数据，但无法清除
     * Preferences DataStore 中的数据（DataStore 使用独立的文件格式，
     * 不存储在 SharedPreferences 中）。DataStore 数据需要通过
     * SettingsDataStore.clearAll() 单独清除。
     */
    fun clearAll() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()

            val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            appPrefs.edit().clear().apply()

            Logger.App.w(TAG, "Cleared all configuration (SharedPreferences only; DataStore requires separate clear)")

            // 覆盖回退文件，防止 Hook 进程读到过期配置
            writeFallbackConfigFile()
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to clear config", e)
        }
    }

    /**
     * 设置体重秤模拟器配置
     */
    fun setScaleConfig(config: ScaleSimulatorConfig) {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_SCALE_CONFIG, json.encodeToString(config))
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit()
            ensurePrefsWorldReadable()
            writeFallbackConfigFile()
            Logger.App.d(TAG, "Set scale_config: enabled=${config.enabled}, weight=${config.targetWeightKg}kg")
            com.jingyu233.bluetoothhook.data.bridge.CaptureBridge.pushConfigUpdate()
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to set scale config", e)
        }
    }

    /**
     * 获取体重秤模拟器配置
     */
    fun getScaleConfig(): ScaleSimulatorConfig {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SCALE_CONFIG, null) ?: return ScaleSimulatorConfig()
            json.decodeFromString<ScaleSimulatorConfig>(raw)
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get scale config", e)
            ScaleSimulatorConfig()
        }
    }

    /**
     * 获取体重秤配置 JSON（供 TCP 配置同步使用）
     */
    fun getScaleConfigJson(): String {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_SCALE_CONFIG, null) ?: ""
        } catch (e: Exception) {
            Logger.App.e(TAG, "Failed to get scale config JSON", e)
            ""
        }
    }

    /**
     * 确保SharedPreferences文件对其他进程可读（XSharedPreferences需要）
     * Android 10+ 上 XSharedPreferences 无法跨进程读 MODE_PRIVATE 文件，
     * 必须从模块自身进程设置文件权限
     */
    private fun ensurePrefsWorldReadable() {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "${PREF_NAME}.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                // 同时设置目录可读（某些ROM需要）
                prefsDir.setReadable(true, false)
            }
        } catch (e: Exception) {
            Logger.App.w(TAG, "Failed to make prefs world-readable: ${e.message}")
        }
    }

    /**
     * 将配置写入文件回退位置（/data/local/tmp/bthook_config.json）。
     * 此位置通常对系统进程可读，作为 XSharedPreferences 和 TCP 同步的补充。
     * Hook 端在 TCP 配置不可用时尝试读取此文件。
     */
    fun writeFallbackConfigFile() {
        try {
            val fallbackDir = File("/data/local/tmp")
            if (!fallbackDir.exists() || !fallbackDir.canWrite()) return

            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val devicesJson = prefs.getString(KEY_DEVICES, "[]") ?: "[]"
            val globalEnabled = prefs.getBoolean(KEY_GLOBAL_ENABLED, true)
            val captureEnabled = prefs.getBoolean(KEY_CAPTURE_ENABLED, false)

            val classicInterval = prefs.getInt(KEY_CLASSIC_INTERVAL_MS, 5000)
            val scaleJson = prefs.getString(KEY_SCALE_CONFIG, "") ?: ""
            val configJson = """{"global_enabled":$globalEnabled,"capture_enabled":$captureEnabled,"classic_interval_ms":$classicInterval,"scale_config":$scaleJson,"devices":$devicesJson}"""
            val fallbackFile = File(fallbackDir, "bthook_config.json")
            val tmpFile = File(fallbackDir, "bthook_config.json.tmp")
            tmpFile.writeText(configJson, Charsets.UTF_8)
            tmpFile.renameTo(fallbackFile)
            fallbackFile.setReadable(true, false)

            Logger.App.d(TAG, "Fallback config written to ${fallbackFile.absolutePath}")
        } catch (e: Exception) {
            Logger.App.w(TAG, "Failed to write fallback config: ${e.message}")
        }
    }
}
