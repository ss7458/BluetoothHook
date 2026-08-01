package com.jingyu233.bluetoothhook.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import com.jingyu233.bluetoothhook.data.model.VirtualDevice
import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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
            // AOSP / 原生 (ScanController 是 TransitionalScanHelper 的 wrapper，无 scan 回调)
            "com.android.bluetooth.le_scan.TransitionalScanHelper",
            "com.android.bluetooth.le_scan.ScanController",
            "com.android.bluetooth.le_scan.ScanManager",
            // 小米 HyperOS
            "com.android.bluetooth.le_scan.ScanControllerWrapper",
            "com.android.bluetooth.le_scan.BluetoothLeScannerImpl",
            // OPPO / ColorOS
            "com.android.bluetooth.le_scan.OplusScanController",
            "com.android.bluetooth.le_scan.OplusLeScanManager",
            // 三星 OneUI
            "com.android.bluetooth.le_scan.SemScanController",
            "com.android.bluetooth.le_scan.SemLeScanManager",
            // 华为 HarmonyOS / EMUI
            "com.android.bluetooth.le_scan.HwScanController",
            "com.android.bluetooth.le_scan.HwLeScanManager",
            // 通用 fallback
            "com.android.bluetooth.le_scan.LeScanManager"
        )

        private const val METHOD_NAME = "onScanResultInternal"
        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        private val json = Json { ignoreUnknownKeys = true }
    }

    private lateinit var scanResultBuilder: ScanResultBuilder
    private lateinit var virtualDeviceInjector: VirtualDeviceInjector

    // ── 自适应发现/解析跟踪 ──────────────────────────────────
    private var classFound: String = "NONE"
    private var methodFound: String = "NONE"
    private val resolvedFields: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile
    private var hasInjectedOnce = false

    /** 最近一次扫描回调的 ScanController 实例，供定时注入使用 */
    @Volatile
    private var cachedScanInstance: Any? = null

    // ── Cached reflection results (resolved once, reset on error) ─
    @Volatile
    private var cachedScanManager: Any? = null
    @Volatile
    private var cachedScannerMap: Any? = null
    @Volatile
    private var cachedScanQueue: Collection<*>? = null
    @Volatile
    private var resolveAttempted = false

    // ── Prefs reload throttling ──────────────────────────────
    @Volatile
    private var lastPrefReloadMs = 0L
    @Volatile
    private var cachedCaptureEnabled = false
    @Volatile
    private var cachedGlobalEnabled = true
    @Volatile
    private var cachedInjectionMode = "insert"

    // ── 初始化 ───────────────────────────────────────────────

    fun init() {
        try {
            Logger.Hook.i(TAG, "Initializing BluetoothScanHook (adaptive mode)")

            // 记录 TCP 配置缓存状态
            val synced = CaptureSocket.configCache
            Logger.Hook.i(TAG, "Config cache at init: globalEnabled=${synced.globalEnabled}, captureEnabled=${synced.captureEnabled}, devices=${if (synced.devicesJson == "[]") "none" else "present"}, timestamp=${synced.timestamp}")

            scanResultBuilder = ScanResultBuilder(classLoader)
            virtualDeviceInjector = VirtualDeviceInjector(scanResultBuilder, prefs)

            val hooked = hookScanResultInternal()

            if (hooked) {
                Logger.Hook.i(TAG, "Hooked $classFound.$methodFound")
                sendStatusLine(fieldsResolved = "pending")
                startPeriodicInjection()
            } else {
                Logger.Hook.e(TAG, "Failed to hook any candidate class", null)
                Logger.Hook.e(TAG, "Tried classes: ${CANDIDATE_CLASSES.joinToString(", ")}", null)
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
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (cachedInjectionMode == "override") {
                                param.setResult(null)
                            }
                        }

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

    /** 统计：记录 handleScanResult 被调用的次数 */
    private val scanResultCallCount = AtomicInteger(0)

    /**
     * 按参数类型而非固定位置提取真实扫描结果，然后执行 Capture + 注入。
     */
    private fun handleScanResult(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.isEmpty()) return

        scanResultCallCount.incrementAndGet()
        cachedScanInstance = param.thisObject

        // 前 5 次 + 每 100 次记录详细日志
        if (scanResultCallCount.get() <= 5 || scanResultCallCount.get() % 100 == 1) {
            val argTypes = args.map { it?.javaClass?.simpleName ?: "null" }.joinToString(", ")
            val argValues = args.mapIndexed { idx, arg ->
                when (arg) {
                    null -> "null"
                    is ByteArray -> "byte[${arg.size}]"
                    is String -> if (arg.length > 17) "\"${arg.take(17)}...\"" else "\"$arg\""
                    else -> arg.toString()
                }
            }.joinToString(", ")
            Logger.Hook.i(TAG, "handleScanResult #${scanResultCallCount.get()}: ${args.size} args, types=[$argTypes], values=[$argValues]")
        }

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

        // ---- 2. 按 AOSP 固定索引提取 int 参数 ----
        // onScanResultInternal 签名 (11 params, AOSP 标准):
        //   [0]=eventType, [1]=addressType, [2]=address(String), [3]=primaryPhy,
        //   [4]=secondaryPhy, [5]=advertisingSid, [6]=txPower, [7]=rssi,
        //   [8]=periodicAdvInterval, [9]=scanRecord(byte[]), [10]=timestampNanos(String)
        // 注意：之前用的相对偏移 (si-3, si-5) 会将 eventType 误读为 txPower 等，
        // 经 JADX 验证 TransitionalScanHelper 签名固定，故改用固定索引。
        val si = scanRecordIndex
        if (si < 0) return // 没有 byte[]，无法定位

        val eventType: Int     = tryIntArg(args, 0, 0)
        val addressType: Int   = tryIntArg(args, 1, 0)
        val primaryPhy: Int    = tryIntArg(args, 3, 1)
        val rssi: Int          = tryIntArg(args, 7, 0)
        val txPower: Int       = tryIntArg(args, 6, 0)
        val periodicAdvInt: Int = tryIntArg(args, 8, 0)

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
            var tickCount = 0
            var lastClassicBroadcastMs = 0L
            while (periodicInjectorRunning) {
                try {
                    Thread.sleep(500L)
                    tickCount++
                    reloadPrefsIfNeeded(force = true)
                    val instance = cachedScanInstance
                    if (instance == null) {
                        // 静默等待 scan session，不刷日志
                        continue
                    }
                    if (cachedGlobalEnabled) {
                        injectVirtualDevicesAdaptive(instance)
                        // 经典蓝牙发现广播：动态间隔（从 configCache 读取，1-30秒范围）
                        val classicIntervalMs = (CaptureSocket.configCache.classicIntervalMs
                            .coerceIn(1000, 30000))
                        val now = System.currentTimeMillis()
                        if (now - lastClassicBroadcastMs >= classicIntervalMs) {
                            lastClassicBroadcastMs = now
                            injectClassicDiscoveryBroadcast()
                        }
                    } else {
                        // global_enabled=false 时静默跳过，不刷日志
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

        // 保存旧值用于比较
        val prevCapture = cachedCaptureEnabled
        val prevGlobal = cachedGlobalEnabled

        // 1) 优先使用 TCP 同步的配置（绕过 XSharedPreferences SELinux 问题）
        val synced = CaptureSocket.configCache
        cachedCaptureEnabled = synced.captureEnabled
        cachedGlobalEnabled = synced.globalEnabled
        cachedInjectionMode = synced.injectionMode
        lastPrefReloadMs = now
        var prefsSource = "tcp"

        // 2) XSharedPreferences / 文件回退仅在 TCP 配置无效时使用
        if (synced.timestamp == 0L) {
            try {
                prefs.reload()
                val prefsCapture = prefs.getBoolean("capture_enabled", cachedCaptureEnabled)
                val prefsGlobal = prefs.getBoolean("global_enabled", cachedGlobalEnabled)
                if (prefsCapture != cachedCaptureEnabled || prefsGlobal != cachedGlobalEnabled) {
                    cachedCaptureEnabled = prefsCapture
                    cachedGlobalEnabled = prefsGlobal
                    prefsSource = "xsp"
                }
            } catch (e: Throwable) { }
        }

        // 3) 始终检查文件回退的设备列表（/data/local/tmp/bthook_config.json）
        //    TCP 热推送可能因连接断开而失败，文件回退作为安全网确保设备列表始终最新
        try {
            val fallbackFile = File("/data/local/tmp/bthook_config.json")
            if (fallbackFile.exists()) {
                val text = fallbackFile.readText()
                val obj = json.decodeFromString<Map<String, JsonElement>>(text)

                if (synced.timestamp == 0L) {
                    // TCP 无效时，也从文件读取 global/capture
                    val fileGlobalStr = obj["global_enabled"]?.toString()
                    val fileCaptureStr = obj["capture_enabled"]?.toString()
                    val fileGlobal = if (fileGlobalStr == "true") true else if (fileGlobalStr == "false") false else null
                    val fileCapture = if (fileCaptureStr == "true") true else if (fileCaptureStr == "false") false else null
                    if (fileGlobal != null) cachedGlobalEnabled = fileGlobal
                    if (fileCapture != null) cachedCaptureEnabled = fileCapture
                    prefsSource = "file"
                }
            }
        } catch (e: Throwable) { }

        // 仅在配置实际变化时记录日志，避免高频刷屏
        if (cachedCaptureEnabled != prevCapture || cachedGlobalEnabled != prevGlobal || force) {
            Logger.Hook.d(TAG, "Config reloaded: source=$prefsSource, global=$cachedGlobalEnabled, capture=$cachedCaptureEnabled")
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
            if (!cachedGlobalEnabled) {
                Logger.Hook.d(TAG, "injectVirtualDevicesAdaptive skipped: global_enabled=false")
                return
            }

            // Resolve and cache on first attempt (or after failure)
            if (!resolveAttempted) {
                cachedScanManager = resolveScanManager(instance)
                if (cachedScanManager != null) {
                    cachedScannerMap = resolveScannerMap(instance, cachedScanManager!!)
                    cachedScanQueue = resolveScanQueue(cachedScanManager!!)
                    resolveAttempted = true
                }
                Logger.Hook.i(TAG, "Resolution result: scanManager=${cachedScanManager != null}, scannerMap=${cachedScannerMap != null}, scanQueue=${cachedScanQueue?.size ?: "null"}")
            }

            val scanManager = cachedScanManager
            val scannerMap  = cachedScannerMap
            val scanQueue   = cachedScanQueue

            if (scanManager == null) {
                Logger.Hook.d(TAG, "Inject skip: scanManager not resolved")
                return
            }
            if (scannerMap == null) {
                Logger.Hook.d(TAG, "Inject skip: scannerMap not resolved")
                return
            }
            if (scanQueue == null) {
                Logger.Hook.d(TAG, "Inject skip: scanQueue not resolved")
                return
            }
            if (scanQueue.isEmpty()) {
                Logger.Hook.d(TAG, "Inject skip: scanQueue is empty (no apps scanning)")
                return
            }

            Logger.Hook.d(TAG, "Injecting to ${scanQueue.size} scan clients...")

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

    // ── 经典蓝牙发现广播注入 ────────────────────────────────

    /**
     * 读取设备 JSON 供经典蓝牙广播注入使用。
     * 优先使用 CaptureSocket 的 TCP 同步缓存，fallback 到 XSharedPreferences。
     */
    private fun readDevicesJsonForClassic(): String {
        val synced = CaptureSocket.configCache
        if (synced.devicesJson != "[]" && synced.timestamp > 0L) {
            return synced.devicesJson
        }
        try {
            prefs.reload()
            val fromPrefs = prefs.getString("devices", "[]") ?: "[]"
            if (fromPrefs != "[]") return fromPrefs
        } catch (_: Throwable) {}

        // 文件回退
        try {
            val fallbackFile = File("/data/local/tmp/bthook_config.json")
            if (fallbackFile.exists()) {
                val text = fallbackFile.readText()
                val obj = json.decodeFromString<Map<String, JsonElement>>(text)
                val devicesStr = obj["devices"]?.toString()
                if (devicesStr != null && devicesStr != "[]" && devicesStr != "\"[]\"") {
                    val cleaned = if (devicesStr.startsWith("\"") && devicesStr.endsWith("\"")) {
                        devicesStr.substring(1, devicesStr.length - 1).replace("\\\"", "\"")
                    } else devicesStr
                    if (cleaned != "[]") return cleaned
                }
            }
        } catch (_: Throwable) {}
        return "[]"
    }

    /**
     * 发送 [BluetoothDevice.ACTION_FOUND] 广播，使虚拟设备出现在系统蓝牙设置中。
     * 每 ~5 秒执行一次（由调用方控制频率）。
     */
    private fun injectClassicDiscoveryBroadcast() {
        try {
            val devicesJson = readDevicesJsonForClassic()
            if (devicesJson == "[]") return

            val devices = json.decodeFromString<List<VirtualDevice>>(devicesJson)
            val enabledDevices = devices.filter { it.enabled }
            if (enabledDevices.isEmpty()) return

            // 通过反射获取系统 Context（ActivityThread 是隐藏 API）
            val context = try {
                val atClass = Class.forName("android.app.ActivityThread")
                val method = atClass.getMethod("currentApplication")
                method.invoke(null) as? android.content.Context
            } catch (_: Throwable) { null }
            if (context == null) {
                Logger.Hook.d(TAG, "Classic BT: cannot get system context")
                return
            }

            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

            for (device in enabledDevices) {
                try {
                    val btDevice = adapter.getRemoteDevice(device.mac) ?: continue

                    val intent = Intent(BluetoothDevice.ACTION_FOUND).apply {
                        putExtra(BluetoothDevice.EXTRA_DEVICE, btDevice)
                        putExtra(BluetoothDevice.EXTRA_RSSI, device.rssi)
                        putExtra(BluetoothDevice.EXTRA_NAME, device.name)
                    }
                    context.sendBroadcast(intent)
                    Logger.Hook.i(TAG, "Classic BT broadcast: ${device.name} (${device.mac})")
                } catch (e: Throwable) {
                    Logger.Hook.d(TAG, "Classic BT per-device error: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Logger.Hook.d(TAG, "Classic BT inject error: ${e.message}")
        }
    }

    // ── 自适应逐客户端投递（fallback） ───────────────────────

    /**
     * 当 VirtualDeviceInjector 因字段名不匹配而失败时，
     * 此 fallback 使用适配后的字段名逐客户端投递。
     */
    private fun adaptivePerClientDelivery(scannerMap: Any, scanQueue: Collection<*>) {
        // 读取虚拟设备配置（优先 TCP 同步缓存，与 VirtualDeviceInjector 一致）
        val synced = CaptureSocket.configCache
        var devicesJson = if (synced.devicesJson != "[]" && synced.timestamp > 0L) {
            synced.devicesJson
        } else {
            try { prefs.getString("devices", "[]") ?: "[]" } catch (_: Throwable) { "[]" }
        }
        if (devicesJson == "[]") return

        val devices = try {
            json.decodeFromString<List<VirtualDevice>>(devicesJson)
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
