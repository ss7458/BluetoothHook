package com.jingyu233.bluetoothhook.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

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

    private val TAG = "BTHook:BleScan"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 空回调——我们只关心 scan session 被注册，不关心结果 */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            // 不处理——我们只是占位，让 scanQueue 非空
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            // 不处理
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            isScanning = false
        }
    }

    private var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null

    @Volatile
    var isScanning: Boolean = false
        private set

    /**
     * 启动BLE扫描（注册空 scan session）
     * 延迟500ms后执行，确保权限完全生效
     * @return true 如果成功启动，false 如果失败
     */
    fun startScan(context: Context): Boolean {
        if (isScanning) return true

        // 在主线程延迟执行，确保权限已完全注册
        mainHandler.postDelayed({
            doStartScan(context.applicationContext)
        }, 500)

        // 先乐观返回true，实际结果由 doStartScan 设置
        isScanning = true
        Log.i(TAG, "BLE scan start requested (delayed 500ms for permission settling)")
        return true
    }

    private fun doStartScan(context: Context) {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (bluetoothManager == null) {
                Log.e(TAG, "BluetoothManager is null")
                isScanning = false
                return
            }

            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null) {
                Log.e(TAG, "BluetoothAdapter is null")
                isScanning = false
                return
            }

            if (!bluetoothAdapter.isEnabled) {
                Log.w(TAG, "Bluetooth is disabled")
                isScanning = false
                return
            }

            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.w(TAG, "BluetoothLeScanner is null")
                isScanning = false
                return
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bluetoothLeScanner?.startScan(null, settings, scanCallback)

            isScanning = true
            Log.i(TAG, "BLE scan started (placeholder session for virtual device injection)")

        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan permission denied", e)
            isScanning = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan: ${e.javaClass.simpleName}: ${e.message}", e)
            isScanning = false
        }
    }

    /**
     * 停止BLE扫描
     */
    fun stopScan() {
        if (!isScanning) return

        try {
            mainHandler.removeCallbacksAndMessages(null)
            bluetoothLeScanner?.stopScan(scanCallback)
            Log.i(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
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
