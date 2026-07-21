package com.jingyu233.bluetoothhook.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge.HookStatus
import com.jingyu233.bluetoothhook.data.local.SettingsDataStore
import com.jingyu233.bluetoothhook.data.model.CaptureRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val hookStatus: StateFlow<HookStatus?> = CaptureBridge.hookStatus
    val isListening: StateFlow<Boolean> = CaptureBridge.isListening

    // 抓包开关从 DataStore 派生（Application 的 collector 负责同步到 ConfigBridge）
    val captureEnabled: StateFlow<Boolean> = settingsDataStore.settingsFlow
        .map { it.captureEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // 服务端错误信息
    val serverError: StateFlow<String?> = CaptureBridge.serverError

    fun setCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.toggleCaptureEnabled(enabled)
        }
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
