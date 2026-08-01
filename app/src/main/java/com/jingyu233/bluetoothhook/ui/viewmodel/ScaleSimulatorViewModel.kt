package com.jingyu233.bluetoothhook.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge
import com.jingyu233.bluetoothhook.data.bridge.ConfigBridge
import com.jingyu233.bluetoothhook.data.bridge.HookStatusHelper
import com.jingyu233.bluetoothhook.data.model.ScaleSimulatorConfig
import com.jingyu233.bluetoothhook.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 体重秤模拟页面 ViewModel
 *
 * 通过 [ConfigBridge] 读写体重秤模拟配置，每次修改立即保存，
 * ConfigBridge 会负责将配置热推送到 Hook 进程。
 */
class ScaleSimulatorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BTHook:UI:VM:ScaleSimulator"
    }

    private val configBridge = ConfigBridge(application)

    private val _config = MutableStateFlow(ScaleSimulatorConfig())
    val config: StateFlow<ScaleSimulatorConfig> = _config.asStateFlow()

    private val _hookStatus = MutableStateFlow(
        HookStatusHelper.resolve(null, HookStatusHelper.isModuleActive(application))
    )
    val hookStatus: StateFlow<HookStatusHelper.Status> = _hookStatus.asStateFlow()

    init {
        _config.value = configBridge.getScaleConfig()
        viewModelScope.launch {
            CaptureBridge.hookStatus.collect {
                _hookStatus.value = HookStatusHelper.resolve(
                    it,
                    HookStatusHelper.isModuleActive(getApplication())
                )
            }
        }
    }

    /** 更新配置并立即保存到 ConfigBridge */
    private fun save(update: (ScaleSimulatorConfig) -> ScaleSimulatorConfig) {
        val newConfig = update(_config.value)
        _config.value = newConfig
        configBridge.setScaleConfig(newConfig)
        Logger.App.d(TAG, "Scale config saved: enabled=${newConfig.enabled}, weight=${newConfig.targetWeightKg}kg")
    }

    fun setEnabled(enabled: Boolean) = save { it.copy(enabled = enabled) }

    fun setTargetWeightKg(weightKg: Double) = save {
        it.copy(targetWeightKg = weightKg.coerceIn(0.5, 200.0))
    }

    fun setImpedanceOhm(impedanceOhm: Int) = save {
        it.copy(impedanceOhm = impedanceOhm.coerceIn(0, 2000))
    }

    /** 广播源 MAC（不强制校验格式，界面负责提示） */
    fun setMac(mac: String) = save { it.copy(mac = mac) }

    fun setBaseRssi(baseRssi: Int) = save {
        it.copy(baseRssi = baseRssi.coerceIn(-100, -30))
    }

    fun setIntervalMs(intervalMs: Long) = save {
        it.copy(intervalMs = intervalMs.coerceIn(50, 1000))
    }

    fun setRampDurationMs(rampDurationMs: Long) = save {
        it.copy(rampDurationMs = rampDurationMs.coerceIn(500, 10000))
    }

    fun setManualMode(manualMode: Boolean) = save { it.copy(manualMode = manualMode) }

    fun setManualAdvHex(manualAdvHex: String) = save { it.copy(manualAdvHex = manualAdvHex) }

    fun refreshHookStatus() {
        _hookStatus.value = HookStatusHelper.resolve(
            CaptureBridge.hookStatus.value,
            HookStatusHelper.isModuleActive(getApplication())
        )
        Logger.App.d(TAG, "Refreshed hook status: ${_hookStatus.value.summary}")
    }
}
