package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.data.model.VirtualDevice
import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.serialization.json.Json

/**
 * 蓝牙扫描Hook核心类（自适应版本）
 *
 * - 按顺序尝试多个候选类，反射发现 onScanResultInternal 方法
 * - 按参数类型（而非固定位置）提取真实扫描结果
 * - 字段/方法查找支持多候选名回退，适配不同AOSP版本
 * - 通过 localhost socket 将扫描数据和状态发给 App UI
 */
class BluetoothScanHook(
    private val classLoader: ClassLoader,
    private val prefs: XSharedPreferences
) {
    companion object {
        private val TAG = Logger.Tags.HOOK_SCANNER

        /** 候选类，按顺序尝试 */
        private val CANDIDATE_CLASSES = arrayOf(
            "com.android.bluetooth.le_scan.ScanController",
            "com.android.bluetooth.le_scan.TransitionalScanHelper",
            "com.android.bluetooth.gatt.ScanManager"
        )

        private const val METHOD_NAME = "onScanResultInternal"
        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }

    private lateinit var scanResultBuilder: ScanResultBuilder
    private lateinit var virtualDeviceInjector: VirtualDeviceInjector

    // ── 自适应发现/解析跟踪 ──────────────────────────────────
    private var classFound: String = "NONE"
    private var methodFound: String = "NONE"
    private val resolvedFields: MutableSet<String> = mutableSetOf()
    private var hasInjectedOnce = false

    /** 最近一次扫描回调的 ScanController 实例，供定时注入使用 */
    @Volatile
    private var cachedScanInstance: Any? = null

    // ── Cached reflection results (resolved once, reset on error) ─
    private var cachedScanManager: Any? = null
    private var cachedScannerMap: Any? = null
    private var cachedScanQueue: Collection<*>? = null
    private var resolveAttempted = false

    // ── Prefs reload throttling ──────────────────────────────
    private var lastPrefReloadMs = 0L
    private var cachedCaptureEnabled = false
    private var cachedGlobalEnabled = true

    // ── 初始化 ───────────────────────────────────────────────

    fun init() {
        try {
            Logger.Hook.i(TAG, "Initializing BluetoothScanHook (adaptive mode)")

            scanResultBuilder = ScanResultBuilder(classLoader)
            virtualDeviceInjector = VirtualDeviceInjector(scanResultBuilder, prefs)

            val hooked = hookScanResultInternal()

            if (hooked) {
                Logger.Hook.i(TAG, "Hooked $classFound.$methodFound")
                sendStatusLine(fieldsResolved = "pending")
                startPeriodicInjection()
            } else {
                Logger.Hook.e(TAG, "Failed to hook any candidate class", null)
            }
        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Fatal init error", e)
        }
    }

    // ── 候选类 + 方法发现 ────────────────────────────────────

    /**
     * 遍历候选类，对第一个找到的类搜索所有 onScanResultInternal 重载，
     * 选中参数中包含 byte[] 且参数最多的那个方法并 Hook。
     */
    private fun hookScanResultInternal(): Boolean {
        for (className in CANDIDATE_CLASSES) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                Logger.Hook.i(TAG, "Found class: $className")

                // 枚举所有 declared 方法（含父类）
                val method = findTargetMethod(clazz)
                if (method != null) {
                    classFound = clazz.name
                    methodFound = method.name
                    method.isAccessible = true

                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                handleScanResult(param)
                            } catch (e: Throwable) {
                                Logger.Hook.e(TAG, "Error in afterHookedMethod", e)
                            }
                        }
                    })

                    Logger.Hook.i(TAG, "Hooked ${clazz.name}.${method.name} " +
                            "(${method.parameterTypes.size} params)")
                    hookInstanceCachingMethods(clazz)
                    return true
                } else {
                    Logger.Hook.d(TAG, "No suitable method in $className")
                }
            } catch (e: Throwable) {
                Logger.Hook.d(TAG, "Skipping candidate class: $className – ${e.message}")
            }
        }
        return false
    }

    /**
     * 从类及其父类中找名为 METHOD_NAME 且参数包含 byte[] 的方法。
     * 若有多个含 byte[] 的重载，选参数最多的那个。
     */
    private fun findTargetMethod(clazz: Class<*>): java.lang.reflect.Method? {
        val allMethods = mutableListOf<java.lang.reflect.Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            for (m in current.declaredMethods) {
                if (m.name == METHOD_NAME) {
                    allMethods.add(m)
                }
            }
            current = current.superclass
        }

        val withByteArray = allMethods.filter { m ->
            m.parameterTypes.any { it == ByteArray::class.java }
        }
        if (withByteArray.isEmpty()) return null

        return withByteArray.maxByOrNull { it.parameterCount }
    }

    /** 扫描注册/启动时也缓存实例，便于无真实设备时定时注入 */
    private fun hookInstanceCachingMethods(clazz: Class<*>) {
        val methodNames = setOf("registerScanner", "startScan", "stopScan", "flushPendingBatchResults")
        var current: Class<*>? = clazz
        while (current != null) {
            for (m in current.declaredMethods) {
                if (m.name !in methodNames) continue
                try {
                    m.isAccessible = true
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            cachedScanInstance = param.thisObject
                        }
                    })
                    Logger.Hook.d(TAG, "Instance cache hook: ${current.name}.${m.name}")
                } catch (e: Throwable) {
                    Logger.Hook.d(TAG, "Skip instance cache hook ${m.name}: ${e.message}")
                }
            }
            current = current.superclass
        }
    }

    // ── afterHookedMethod 处理 ───────────────────────────────

    /**
     * 按参数类型而非固定位置提取真实扫描结果，然后执行 Capture + 注入。
     */
    private fun handleScanResult(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.isEmpty()) return

        cachedScanInstance = param.thisObject

        // ---- 1. 按类型提取参数 ----
        var scanRecordBytes: ByteArray? = null
        var scanRecordIndex = -1
        val stringParams = mutableListOf<String>()

        for (i in args.indices) {
            when (args[i]) {
                is ByteArray -> {
                    if (scanRecordBytes == null) {
                        scanRecordBytes = args[i] as ByteArray
                        scanRecordIndex = i
                    }
                }
                is String -> stringParams.add(args[i] as String)
            }
        }

        // address：优先匹配 MAC 格式，否则取第一个 String
        val address = stringParams.firstOrNull { it.matches(MAC_REGEX) }
            ?: stringParams.firstOrNull()
            ?: ""

        // ---- 2. 按 AOSP 相对偏移提取 int 参数 ----
        val si = scanRecordIndex
        if (si < 0) return // 没有 byte[]，无法定位

        val rssi: Int          = tryIntArg(args, si - 2, 0)
        val txPower: Int       = tryIntArg(args, si - 1, 0)
        val periodicAdvInt: Int = tryIntArg(args, si + 1, 0)
        val eventType: Int     = tryIntArg(args, si - 3, 0)
        val primaryPhy: Int    = tryIntArg(args, si - 4, 1)
        val addressType: Int   = tryIntArg(args, si - 5, 0)

        // ---- 3. Throttled prefs reload (max once per second) ----
        reloadPrefsIfNeeded()

        if (cachedCaptureEnabled) {
            val capLine = buildCaptureLine(
                timestampMs = System.currentTimeMillis(),
                mac = address,
                rssi = rssi,
                eventType = eventType,
                primaryPhy = primaryPhy,
                addressType = addressType,
                scanRecordBytes = scanRecordBytes
            )
            CaptureSocket.sendLine(capLine)
        }

        // ---- 4. 注入虚拟设备（使用缓存判断） ----
        if (cachedGlobalEnabled) {
            injectVirtualDevicesAdaptive(param.thisObject)
        }
    }

    /** 安全的 int 参数提取，越界/类型不匹配时返回默认值 */
    private fun tryIntArg(args: Array<Any>, index: Int, default: Int): Int {
        return if (index in args.indices && args[index] is Int) {
            args[index] as Int
        } else default
    }

    // ── Socket 消息构建 ──────────────────────────────────────

    private fun buildCaptureLine(
        timestampMs: Long,
        mac: String,
        rssi: Int,
        eventType: Int,
        primaryPhy: Int,
        addressType: Int,
        scanRecordBytes: ByteArray?
    ): String {
        val advDataHex = if (scanRecordBytes != null) {
            scanRecordBytes.joinToString("") { String.format("%02x", it) }
        } else ""
        return "CAP|$timestampMs|$mac|$rssi|$eventType|$primaryPhy|$addressType|$advDataHex"
    }

    private fun buildStatusLine(fieldsResolved: String): String {
        return "STATUS|${android.os.Build.VERSION.SDK_INT}|$classFound|$methodFound|$fieldsResolved|${System.currentTimeMillis()}"
    }

    /**
     * 发送 STATUS 行。允许两次调用：
     * 1) Hook 成功后 fieldsResolved="pending"
     * 2) 首次成功注入后 fieldsResolved=真实字段名
     */
    private fun sendStatusLine(fieldsResolved: String) {
        CaptureSocket.sendLine(buildStatusLine(fieldsResolved))
    }

    // ── 定时注入（不依赖周围是否有真实 BLE 设备） ─────────────

    @Volatile
    private var periodicInjectorStarted = false

    @Volatile
    private var periodicInjectorRunning = false

    private fun startPeriodicInjection() {
        if (periodicInjectorStarted) return
        periodicInjectorStarted = true
        periodicInjectorRunning = true
        Thread({
            Logger.Hook.i(TAG, "Periodic injection thread started")
            while (periodicInjectorRunning) {
                try {
                    Thread.sleep(500L)
                    reloadPrefsIfNeeded(force = true)
                    val instance = cachedScanInstance ?: continue
                    if (cachedGlobalEnabled) {
                        injectVirtualDevicesAdaptive(instance)
                    }
                } catch (_: InterruptedException) {
                    periodicInjectorRunning = false
                    break
                } catch (e: Throwable) {
                    Logger.Hook.d(TAG, "Periodic injection tick error: ${e.message}")
                }
            }
        }, "BTHook-PeriodicInject").apply {
            isDaemon = true
            start()
        }
    }

    private fun reloadPrefsIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPrefReloadMs <= 1000) return
        try {
            prefs.reload()
            cachedCaptureEnabled = prefs.getBoolean("capture_enabled", false)
            cachedGlobalEnabled = prefs.getBoolean("global_enabled", true)
            lastPrefReloadMs = now
        } catch (_: Throwable) {
            // keep stale cache
        }
    }

    // ── 注入（自适应字段解析） ────────────────────────────────

    /**
     * 自适应解析 scanManager / scannerMap / scanQueue，然后
     * 优先调用 VirtualDeviceInjector（保留现有逻辑），失败时
     * 使用自适应 fallback 逐客户端投递。
     */
    private fun injectVirtualDevicesAdaptive(instance: Any) {
        try {
            // global_enabled 已由 handleScanResult 节流缓存，无需重复 prefs.reload()
            if (!cachedGlobalEnabled) return

            // Resolve and cache on first attempt (or after failure)
            if (!resolveAttempted) {
                cachedScanManager = resolveScanManager(instance)
                if (cachedScanManager != null) {
                    cachedScannerMap = resolveScannerMap(instance, cachedScanManager!!)
                    cachedScanQueue = resolveScanQueue(cachedScanManager!!)
                }
                resolveAttempted = true
            }

            val scanManager = cachedScanManager ?: return
            val scannerMap  = cachedScannerMap ?: return
            val scanQueue   = cachedScanQueue ?: return
            if (scanQueue.isEmpty()) return

            // 优先走 VirtualDeviceInjector（保留现有注入逻辑），
            // 失败时用自适应字段名逐客户端投递作为 fallback
            var injectOk = false
            try {
                virtualDeviceInjector.injectDevices(instance, scanManager, scannerMap, scanQueue)
                injectOk = true
            } catch (e: Throwable) {
                Logger.Hook.w(TAG, "VirtualDeviceInjector failed, trying adaptive fallback: ${e.message}")
                try {
                    adaptivePerClientDelivery(scannerMap, scanQueue)
                    injectOk = true
                } catch (e2: Throwable) {
                    Logger.Hook.e(TAG, "Adaptive fallback also failed", e2)
                }
            }

            // 首次成功注入后发送 STATUS（含已解析字段清单）
            if (!hasInjectedOnce && injectOk) {
                hasInjectedOnce = true
                val fieldsStr = if (resolvedFields.isEmpty()) "N/A" else resolvedFields.joinToString(",")
                sendStatusLine(fieldsResolved = fieldsStr)
            }
        } catch (e: Throwable) {
            // Reset cache on error so next call retries
            cachedScanManager = null
            cachedScannerMap = null
            cachedScanQueue = null
            resolveAttempted = false
            Logger.Hook.e(TAG, "injectVirtualDevicesAdaptive error", e)
        }
    }

    // ── 自适应字段/方法解析 ──────────────────────────────────

    /** 解析 scanManager（多候选名回退） */
    private fun resolveScanManager(instance: Any): Any? {
        // 1) mScanManager
        var sm = tryGetField(instance, "mScanManager")
        // 2) scanManager
        if (sm == null) sm = tryGetField(instance, "scanManager")
        // 3) mScanHelper.mScanManager
        if (sm == null) {
            val helper = tryGetField(instance, "mScanHelper")
            if (helper != null) {
                resolvedFields.add("mScanHelper")
                sm = tryGetField(helper, "mScanManager")
            }
        }
        // 4) mScanHelper 本身作为 ScanManager 使用
        if (sm == null) sm = tryGetField(instance, "mScanHelper")
        return sm
    }

    /** 解析 scannerMap */
    private fun resolveScannerMap(instance: Any, scanManager: Any): Any? {
        // 直接在 instance 上找
        var map = tryGetField(instance, "mScannerMap")
        if (map == null) map = tryGetField(instance, "scannerMap")
        // 经 scanManager 找
        if (map == null) map = tryGetField(scanManager, "mScannerMap")
        if (map == null) map = tryGetField(scanManager, "scannerMap")
        // 经 mScanHelper 找
        if (map == null) {
            val helper = tryGetField(instance, "mScanHelper")
            if (helper != null) {
                map = tryGetField(helper, "mScannerMap")
            }
        }
        return map
    }

    /** 解析 scanQueue */
    private fun resolveScanQueue(scanManager: Any): Collection<*>? {
        try {
            val q = XposedHelpers.callMethod(scanManager, "getRegularScanQueue") as? Collection<*>
            if (q != null) {
                resolvedFields.add("getRegularScanQueue")
                return q
            }
        } catch (e: Throwable) {
            Logger.Hook.d(TAG, "reflect/op failed: getRegularScanQueue – ${e.message}")
        }
        try {
            val q = XposedHelpers.callMethod(scanManager, "getScanQueue") as? Collection<*>
            if (q != null) {
                resolvedFields.add("getScanQueue")
                return q
            }
        } catch (e: Throwable) {
            Logger.Hook.d(TAG, "reflect/op failed: getScanQueue – ${e.message}")
        }
        return null
    }

    /** 安全的 getObjectField，成功时记录字段名 */
    private fun tryGetField(obj: Any, fieldName: String): Any? {
        return try {
            val v = XposedHelpers.getObjectField(obj, fieldName)
            if (v != null) resolvedFields.add(fieldName)
            v
        } catch (e: Throwable) {
            Logger.Hook.d(TAG, "reflect/op failed: getObjectField($fieldName) – ${e.message}")
            null
        }
    }

    /** 安全的 getIntField，成功时记录字段名 */
    private fun tryGetIntField(obj: Any, fieldName: String): Int? {
        return try {
            val v = XposedHelpers.getIntField(obj, fieldName)
            resolvedFields.add(fieldName)
            v
        } catch (e: Throwable) {
            Logger.Hook.d(TAG, "reflect/op failed: getIntField($fieldName) – ${e.message}")
            null
        }
    }

    // ── 自适应逐客户端投递（fallback） ───────────────────────

    /**
     * 当 VirtualDeviceInjector 因字段名不匹配而失败时，
     * 此 fallback 使用适配后的字段名逐客户端投递。
     */
    private fun adaptivePerClientDelivery(scannerMap: Any, scanQueue: Collection<*>) {
        // 读取虚拟设备配置
        val devicesJson = try { prefs.getString("devices", "[]") ?: "[]" } catch (_: Throwable) { "[]" }
        if (devicesJson == "[]") return

        val devices = try {
            Json.decodeFromString<List<VirtualDevice>>(devicesJson)
        } catch (_: Throwable) { return }

        val enabledDevices = devices.filter { it.enabled }
        if (enabledDevices.isEmpty()) return

        for (device in enabledDevices) {
            try {
                val scanResult = scanResultBuilder.buildScanResult(
                    macAddress = device.mac,
                    rssi = device.rssi,
                    advDataHex = device.advDataHex,
                    scanResponseHex = device.scanResponseHex,
                    useExtendedAdvertising = device.useExtendedAdvertising,
                    deviceName = device.name
                ) ?: continue

                for (client in scanQueue) {
                    if (client == null) continue
                    try {
                        // scannerId: mScannerId / scannerId
                        val scannerId = tryGetIntField(client, "mScannerId")
                            ?: tryGetIntField(client, "scannerId")
                            ?: continue

                        // scannerApp: scannerMap.getById(scannerId)
                        val scannerApp = try {
                            XposedHelpers.callMethod(scannerMap, "getById", scannerId)
                        } catch (e: Throwable) {
                            Logger.Hook.d(TAG, "reflect/op failed: getById – ${e.message}")
                            null
                        } ?: continue

                        // callback: mCallback / callback
                        val callback = tryGetField(scannerApp, "mCallback")
                            ?: tryGetField(scannerApp, "callback")
                            ?: continue

                        XposedHelpers.callMethod(callback, "onScanResult", scanResult)
                    } catch (e: Throwable) {
                        Logger.Hook.d(TAG, "reflect/op failed: per-client delivery – ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                Logger.Hook.d(TAG, "reflect/op failed: per-device injection – ${e.message}")
            }
        }
    }
}
