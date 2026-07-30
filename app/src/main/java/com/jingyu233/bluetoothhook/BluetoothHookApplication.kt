package com.jingyu233.bluetoothhook

import android.app.Application
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge
import com.jingyu233.bluetoothhook.data.bridge.ConfigBridge
import com.jingyu233.bluetoothhook.data.local.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BluetoothHookApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CaptureBridge.setAppContext(this)
        CaptureBridge.startServer()
        val settings = SettingsDataStore(this)
        val config = ConfigBridge(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settingsFlow.collect { appSettings ->
                // 将所有需要同步到 Hook 进程的配置项写入 ConfigBridge
                // ConfigBridge 内部调用 CaptureBridge.pushConfigUpdate() 热推送到 Hook
                config.setCaptureEnabled(appSettings.captureEnabled)
                config.setClassicIntervalMs(appSettings.classicIntervalMs)
            }
        }
    }
}
