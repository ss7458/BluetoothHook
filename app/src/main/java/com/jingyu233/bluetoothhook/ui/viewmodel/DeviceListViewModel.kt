package com.jingyu233.bluetoothhook.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyu233.bluetoothhook.ble.BleScanService
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge
import com.jingyu233.bluetoothhook.data.bridge.ConfigBridge
import com.jingyu233.bluetoothhook.data.bridge.HookStatusHelper
import com.jingyu233.bluetoothhook.data.local.VirtualDeviceDatabase
import com.jingyu233.bluetoothhook.data.model.VirtualDevice
import com.jingyu233.bluetoothhook.data.repository.VirtualDeviceRepository
import com.jingyu233.bluetoothhook.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DeviceListViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val TAG = Logger.Tags.UI_VM_DEVICE_LIST
    }

    private val database = VirtualDeviceDatabase.getInstance(application)
    private val repository = VirtualDeviceRepository(database.virtualDeviceDao(), application)
    private val configBridge = ConfigBridge(application)

    val devices = repository.allDevices

    private val _globalEnabled = MutableStateFlow(true)
    val globalEnabled: StateFlow<Boolean> = _globalEnabled.asStateFlow()

    private val _hookStatus = MutableStateFlow(
        HookStatusHelper.resolve(null, HookStatusHelper.isModuleActive(application))
    )
    val hookStatus: StateFlow<HookStatusHelper.Status> = _hookStatus.asStateFlow()

    private val _bleScanning = MutableStateFlow(BleScanService.isRunning())
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    private val scanStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BleScanService.ACTION_SCAN_STATE_CHANGED) {
                _bleScanning.value = intent.getBooleanExtra(BleScanService.EXTRA_IS_SCANNING, false)
            }
        }
    }

    init {
        _globalEnabled.value = configBridge.getGlobalEnabled()
        refreshHookStatus()

        // 注册BLE扫描状态广播
        val filter = IntentFilter(BleScanService.ACTION_SCAN_STATE_CHANGED)
        application.registerReceiver(scanStateReceiver, filter)

        viewModelScope.launch {
            CaptureBridge.hookStatus.collect {
                _hookStatus.value = HookStatusHelper.resolve(
                    it,
                    HookStatusHelper.isModuleActive(getApplication())
                )
            }
        }
    }

    fun setGlobalEnabled(enabled: Boolean) {
        _globalEnabled.value = enabled
        configBridge.setGlobalEnabled(enabled)
        Logger.App.i(TAG, "Global enabled set to: $enabled")
    }

    fun toggleBleScan() {
        val app = getApplication<Application>()
        if (_bleScanning.value) {
            BleScanService.stop(app)
            Logger.App.i(TAG, "BLE scan stopped by user")
        } else {
            BleScanService.start(app)
            Logger.App.i(TAG, "BLE scan started by user")
        }
    }

    fun toggleDevice(device: VirtualDevice) {
        viewModelScope.launch {
            repository.toggleDevice(device)
            Logger.App.d(TAG, "Toggled device: ${device.name}")
        }
    }

    fun deleteDevice(device: VirtualDevice) {
        viewModelScope.launch {
            repository.deleteDevice(device)
            Logger.App.i(TAG, "Deleted device: ${device.name}")
        }
    }

    fun refreshHookStatus() {
        _hookStatus.value = HookStatusHelper.resolve(
            CaptureBridge.hookStatus.value,
            HookStatusHelper.isModuleActive(getApplication())
        )
        Logger.App.d(TAG, "Refreshed hook status: ${_hookStatus.value.summary}")
    }
}
