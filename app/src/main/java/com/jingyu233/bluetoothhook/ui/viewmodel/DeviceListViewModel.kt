package com.jingyu233.bluetoothhook.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyu233.bluetoothhook.ble.BleScanManager
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

    private val _bleScanning = MutableStateFlow(BleScanManager.isScanning)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    init {
        _globalEnabled.value = configBridge.getGlobalEnabled()
        refreshHookStatus()

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

    /**
     * 切换BLE扫描（调用方已确保权限已授予）
     */
    fun toggleBleScan() {
        val app = getApplication<Application>()
        val result = BleScanManager.toggle(app)
        _bleScanning.value = result
        Logger.App.i(TAG, "BLE scan toggled: $result")
    }
