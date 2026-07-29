package com.jingyu233.bluetoothhook.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyu233.bluetoothhook.ble.BleScanManager
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge
import com.jingyu233.bluetoothhook.data.bridge.HookStatusHelper
import com.jingyu233.bluetoothhook.data.local.SettingsDataStore
import com.jingyu233.bluetoothhook.data.model.CaptureRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 抓包页面 ViewModel
 * 管理抓包记录、Hook 状态和抓包开关
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsDataStore = SettingsDataStore(application)

    // 直接暴露 CaptureBridge 的 StateFlow
    val captureRecords: StateFlow<List<CaptureRecord>> = CaptureBridge.captureRecords
    val isListening: StateFlow<Boolean> = CaptureBridge.isListening

    // Hook 状态通过 HookStatusHelper 统一解析（与首页一致）
    private val _hookStatus = MutableStateFlow(
        HookStatusHelper.resolve(null, HookStatusHelper.isModuleActive(application))
    )
    val hookStatus: StateFlow<HookStatusHelper.Status> = _hookStatus.asStateFlow()

    // 抓包开关从 DataStore 派生（Application 的 collector 负责同步到 ConfigBridge）
    val captureEnabled: StateFlow<Boolean> = settingsDataStore.settingsFlow
        .map { it.captureEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 服务端错误信息
    val serverError: StateFlow<String?> = CaptureBridge.serverError

    // BLE 扫描状态
    private val _bleScanning = MutableStateFlow(BleScanManager.isScanning)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    // ── 过滤 ────────────────────────────────────────────────
    val rssiMinFilter = MutableStateFlow<Int?>(null)
    val rssiMaxFilter = MutableStateFlow<Int?>(null)
    val macFilterPattern = MutableStateFlow("")

    /** 是否有激活的过滤条件 */
    val hasActiveFilter: StateFlow<Boolean> = combine(
        rssiMinFilter, rssiMaxFilter, macFilterPattern
    ) { min, max, mac ->
        min != null || max != null || mac.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 经过过滤后的记录列表 */
    val filteredRecords: StateFlow<List<CaptureRecord>> = combine(
        captureRecords, rssiMinFilter, rssiMaxFilter, macFilterPattern
    ) { records, min, max, mac ->
        records.filter { rec ->
            (min == null || rec.rssi >= min) &&
            (max == null || rec.rssi <= max) &&
            (mac.isBlank() || macMatches(rec.mac, mac))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), captureRecords.value)

    init {
        viewModelScope.launch {
            CaptureBridge.hookStatus.collect {
                _hookStatus.value = HookStatusHelper.resolve(
                    it, HookStatusHelper.isModuleActive(getApplication())
                )
            }
        }
    }

    fun setRssiMinFilter(value: Int?) { rssiMinFilter.value = value }
    fun setRssiMaxFilter(value: Int?) { rssiMaxFilter.value = value }
    fun setMacFilterPattern(value: String) { macFilterPattern.value = value }
    fun clearFilters() {
        rssiMinFilter.value = null
        rssiMaxFilter.value = null
        macFilterPattern.value = ""
    }

    /** 简单的 MAC 通配符匹配：?=单字符, *=多字符 */
    private fun macMatches(mac: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("?", ".")
            .replace("*", ".*")
            .replace(":", "\\:")
        return try {
            mac.matches(Regex(regex, RegexOption.IGNORE_CASE))
        } catch (_: Exception) { true }
    }

    fun setCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.toggleCaptureEnabled(enabled)
        }
    }

    fun toggleBleScan() {
        _bleScanning.value = BleScanManager.toggle(getApplication())
    }

    fun startBleScan() {
        _bleScanning.value = BleScanManager.startScan(getApplication())
    }

    fun stopBleScan() {
        BleScanManager.stopScan()
        _bleScanning.value = false
    }

    fun refreshHookStatus() {
        _hookStatus.value = HookStatusHelper.resolve(
            CaptureBridge.hookStatus.value,
            HookStatusHelper.isModuleActive(getApplication())
        )
    }

    /** 清空所有抓包记录 */
    fun clear() = CaptureBridge.clearRecords()

    /** 导出抓包记录为 CSV 文件 */
    fun exportTo(uri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 快照当前记录列表以确保线程安全
                val records = captureRecords.value.toList()
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.bufferedWriter().use { writer ->
                        // 写入 CSV 表头
                        writer.write("timestamp,mac,rssi,eventType,primaryPhy,addressType,advDataHex")
                        writer.newLine()
                        // 逐行写入记录
                        for (record in records) {
                            val line = buildString {
                                append(record.timestamp)
                                append(',')
                                append(record.mac)
                                append(',')
                                append(record.rssi)
                                append(',')
                                append(record.eventType)
                                append(',')
                                append(record.primaryPhy)
                                append(',')
                                append(record.addressType)
                                append(',')
                                append(record.advDataHex)
                            }
                            writer.write(line)
                            writer.newLine()
                        }
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e("CaptureViewModel", "Export CSV failed", e)
            }
        }
    }
}
