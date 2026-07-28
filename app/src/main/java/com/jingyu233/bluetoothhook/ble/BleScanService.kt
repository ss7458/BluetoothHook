package com.jingyu233.bluetoothhook.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jingyu233.bluetoothhook.MainActivity
import com.jingyu233.bluetoothhook.R
import com.jingyu233.bluetoothhook.utils.Logger

/**
 * BLE扫描前台服务
 * 
 * 在App内部启动一个无实际意义的BLE扫描，目的是在蓝牙系统进程中
 * 创建一个scan session（注册scan client到scanQueue），
 * 使Hook的虚拟设备注入有投递目标。
 * 
 * 不需要真正的BLE设备在附近——只需要有一个活跃的scan session。
 */
class BleScanService : Service() {

    companion object {
        private val TAG = Logger.Tags.SERVICE

        private const val CHANNEL_ID = "ble_scan_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.jingyu233.bluetoothhook.ble.START_SCAN"
        private const val ACTION_STOP = "com.jingyu233.bluetoothhook.ble.STOP_SCAN"

        /** 扫描状态变化广播 */
        const val ACTION_SCAN_STATE_CHANGED = "com.jingyu233.bluetoothhook.ble.SCAN_STATE_CHANGED"
        const val EXTRA_IS_SCANNING = "is_scanning"

        fun isRunning(): Boolean = _isRunning
        @Volatile
        private var _isRunning = false

        fun start(context: Context) {
            val intent = Intent(context, BleScanService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleScanService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null

    /** 空回调——我们只关心scan session被注册，不关心结果 */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            // 不处理——我们只是占位，让scanQueue非空
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.Hook.e(TAG, "BLE scan failed with error code: $errorCode")
            _isRunning = false
            sendStateChanged()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Logger.Hook.i(TAG, "BleScanService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBleScan()
            ACTION_STOP -> stopBleScan()
            else -> startBleScan()
        }
        return START_STICKY
    }

    private fun startBleScan() {
        if (_isRunning) return

        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                Logger.Hook.w(TAG, "Bluetooth adapter not available or disabled")
                stopSelf()
                return
            }

            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Logger.Hook.w(TAG, "BluetoothLeScanner not available")
                stopSelf()
                return
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // startScan with empty filter list — scans for everything
            // The real purpose is just to register a scan client in the bluetooth process
            bluetoothLeScanner?.startScan(null, settings, scanCallback)

            _isRunning = true
            startForeground(NOTIFICATION_ID, buildNotification())
            sendStateChanged()

            Logger.Hook.i(TAG, "BLE scan started (placeholder session for virtual device injection)")

        } catch (e: SecurityException) {
            Logger.Hook.e(TAG, "BLE scan permission denied", e)
            stopSelf()
        } catch (e: Exception) {
            Logger.Hook.e(TAG, "Failed to start BLE scan", e)
            stopSelf()
        }
    }

    private fun stopBleScan() {
        if (!_isRunning) return

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            Logger.Hook.i(TAG, "BLE scan stopped")
        } catch (e: Exception) {
            Logger.Hook.w(TAG, "Error stopping scan: ${e.message}")
        }

        _isRunning = false
        sendStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStateChanged() {
        val intent = Intent(ACTION_SCAN_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_SCANNING, _isRunning)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BLE扫描服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持BLE扫描运行以支持虚拟设备注入"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BluetoothHook")
            .setContentText("BLE扫描运行中 — 虚拟设备注入已激活")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopBleScan()
        super.onDestroy()
    }
}
