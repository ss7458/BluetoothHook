package com.jingyu233.bluetoothhook.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.jingyu233.bluetoothhook.utils.Logger

/**
 * BLE扫描管理器（无需前台服务）
 *
 * 直接在App进程中调用 BluetoothLeScanner.startScan()，
 * 在蓝牙系统进程中注册一个 scan client 到 scanQueue，
 * 使 Xposed Hook 的虚拟设备注入有投递目标。
 *
 * 不需要真正的BLE设备在附近——只需要有一个活跃的 scan session。
 */
object BleScanManager {

    private val TAG = Logger.Tags.SERVICE

    /** 空回调——我们只关心 scan session 被注册，不关心结果 */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            // 不处理——我们只是占位，让 scanQueue 非空
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            // 不处理
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.Hook.e(TAG, "BLE scan failed with error code: $errorCode")
            _isScanning = false
        }
    }

    private var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null

    @Volatile
    var isScanning: Boolean = false
        private set

    /**
     * 启动BLE扫描（注册空 scan session）
     * @return true 如果成功启动，false 如果失败
     */
    fun startScan(context: Context): Boolean {
        if (isScanning) return true

        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                Logger.Hook.w(TAG, "Bluetooth adapter not available or disabled")
                return false
            }

            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Logger.Hook.w(TAG, "BluetoothLeScanner not available")
                return false
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // startScan with empty filter — scans for everything
            // 真正目的：在蓝牙进程中注册一个 scan client
            bluetoothLeScanner?.startScan(null, settings, scanCallback)

            isScanning = true
            Logger.Hook.i(TAG, "BLE scan started (placeholder session for virtual device injection)")
            return true

        } catch (e: SecurityException) {
            Logger.Hook.e(TAG, "BLE scan permission denied", e)
            return false
        } catch (e: Exception) {
            Logger.Hook.e(TAG, "Failed to start BLE scan", e)
            return false
        }
    }

    /**
     * 停止BLE扫描
     */
    fun stopScan() {
        if (!isScanning) return

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            Logger.Hook.i(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Logger.Hook.w(TAG, "Error stopping scan: ${e.message}")
        }

        isScanning = false
        bluetoothLeScanner = null
    }

    /**
     * 切换扫描状态
     * @return 切换后的状态
     */
    fun toggle(context: Context): Boolean {
        return if (isScanning) {
            stopScan()
            false
        } else {
            startScan(context)
        }
    }
}
