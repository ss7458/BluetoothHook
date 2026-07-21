package com.jingyu233.bluetoothhook

import android.app.Application
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge
import com.jingyu233.bluetoothhook.data.bridge.ConfigBridge
import com.jingyu233.bluetoothhook.data.local.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BluetoothHookApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CaptureBridge.startServer()
        val settings = SettingsDataStore(this)
        val config = ConfigBridge(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.settingsFlow.map { it.captureEnabled }.collect { enabled ->
                config.setCaptureEnabled(enabled)
            }
        }
    }
}
