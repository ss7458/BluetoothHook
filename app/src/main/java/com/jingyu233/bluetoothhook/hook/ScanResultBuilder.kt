package com.jingyu233.bluetoothhook.hook

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XposedHelpers

/**
 * ScanResult构造器
 * 根据逆向代码分析（ScanController.java line 431）构造符合系统要求的ScanResult对象
 */
class ScanResultBuilder(private val classLoader: ClassLoader) {

    companion object {
        private val TAG = Logger.Tags.HOOK_BUILDER
    }

    /**
     * 构造ScanResult对象
     */
    fun buildScanResult(
        macAddress: String,
        rssi: Int,
        advDataHex: String,
        scanResponseHex: String = "",
        useExtendedAdvertising: Boolean = false,
        deviceName: String? = null
    ): Any? {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Logger.Hook.w(TAG, "BluetoothAdapter null, skip inject")
                return null
            }
            val device = adapter.getRemoteDevice(macAddress)

            val scanRecordClass = XposedHelpers.findClass(
                "android.bluetooth.le.ScanRecord",
                classLoader
            )

            // Legacy：广播与扫描响应分别解析；Extended：仅使用合并后的广播数据
            val advBytes = hexStringToByteArray(advDataHex)
            val scanRecord = if (useExtendedAdvertising || scanResponseHex.isEmpty()) {
                XposedHelpers.callStaticMethod(
                    scanRecordClass,
                    "parseFromBytes",
                    advBytes
                )
            } else {
                val advOnlyRecord = XposedHelpers.callStaticMethod(
                    scanRecordClass,
                    "parseFromBytes",
                    advBytes
                )
                attachScanResponse(scanRecordClass, advOnlyRecord, hexStringToByteArray(scanResponseHex))
            }

            val scanResultClass = XposedHelpers.findClass(
                "android.bluetooth.le.ScanResult",
                classLoader
            )

            val timestampNanos = SystemClock.elapsedRealtimeNanos()
            val isSdk34Plus = android.os.Build.VERSION.SDK_INT >= 34
            val eventType = if (useExtendedAdvertising) {
                if (isSdk34Plus) 0x01 else 0x00
            } else {
                if (isSdk34Plus) 0x13 else 0x10
            }
            val txPower = if (isSdk34Plus) 127 else 0

            try {
                XposedHelpers.newInstance(
                    scanResultClass,
                    device,
                    eventType,
                    1,
                    if (useExtendedAdvertising) 1 else 0,
                    255,
                    txPower,
                    rssi,
                    0,
                    scanRecord,
                    timestampNanos
                )
            } catch (e: Exception) {
                XposedHelpers.newInstance(
                    scanResultClass,
                    device,
                    scanRecord,
                    rssi,
                    timestampNanos
                )
            }

        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Failed to build ScanResult for MAC=$macAddress", e)
            null
        }
    }

    /** 尝试将扫描响应附加到 ScanRecord（反射写字段） */
    private fun attachScanResponse(
        scanRecordClass: Class<*>,
        scanRecord: Any?,
        scanResponseBytes: ByteArray
    ): Any? {
        if (scanRecord == null || scanResponseBytes.isEmpty()) return scanRecord
        for (fieldName in arrayOf("mScanResponse", "scanResponse")) {
            try {
                XposedHelpers.setObjectField(scanRecord, fieldName, scanResponseBytes)
                return scanRecord
            } catch (_: Throwable) {
                // try next field name
            }
        }
        // 无法附加扫描响应时回退：仅使用广播段（避免错误拼接导致 parse 失败）
        Logger.Hook.w(TAG, "Could not attach scan response, using advertisement only")
        return scanRecord
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val cleanHex = hexString.replace(" ", "").replace(":", "")
        if (cleanHex.isEmpty()) return ByteArray(0)
        val len = cleanHex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun generateStandardAdvData(deviceName: String): String {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size
        val flags = "020106"
        val nameHex = StringBuilder()
        nameHex.append(String.format("%02X", nameLength + 1))
        nameHex.append("09")
        nameBytes.forEach { byte ->
            nameHex.append(String.format("%02X", byte))
        }
        return flags + nameHex.toString()
    }

    fun isValidMacAddress(mac: String): Boolean {
        return mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    }

    fun isValidRssi(rssi: Int): Boolean {
        return rssi in -100..0
    }

    fun isValidAdvDataLength(hexString: String, extended: Boolean = false): Boolean {
        val byteCount = hexString.replace(" ", "").replace(":", "").length / 2
        return if (extended) byteCount <= 254 else byteCount <= 31
    }
}
