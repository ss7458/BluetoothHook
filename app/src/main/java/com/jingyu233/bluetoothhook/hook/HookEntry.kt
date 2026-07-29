package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed模块入口点
 * 负责初始化Hook并拦截com.android.bluetooth进程
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private val TAG = Logger.Tags.CORE_ENTRY
        private const val PACKAGE_BLUETOOTH = "com.android.bluetooth"
        private const val PREF_PACKAGE = "com.jingyu233.bluetoothhook"
        private const val PREF_NAME = "hook_config" // Must match ConfigBridge.PREF_NAME
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook自身Application以检测模块激活状态
        if (lpparam.packageName == PREF_PACKAGE) {
            hookModuleApplication(lpparam)
            return
        }

        // 只Hook蓝牙系统进程
        if (lpparam.packageName != PACKAGE_BLUETOOTH) {
            return
        }

        try {
            Logger.Hook.i(TAG, "=== BluetoothHook init START (pid=${android.os.Process.myPid()}, pkg=${lpparam.packageName}) ===")

            // 初始化SharedPreferences以读取模块配置
            // 使用XSharedPreferences可以跨进程读取（系统进程读取模块进程的配置）
            Logger.Hook.d(TAG, "Creating XSharedPreferences(pkg=$PREF_PACKAGE, name=$PREF_NAME)")
            val prefs = XSharedPreferences(PREF_PACKAGE, PREF_NAME)

            // 设置文件权限为world-readable（在模块进程中设置，这里只是尝试）
            try {
                prefs.makeWorldReadable()
                Logger.Hook.d(TAG, "XSharedPreferences.makeWorldReadable() succeeded")
            } catch (e: Exception) {
                Logger.Hook.w(TAG, "XSharedPreferences.makeWorldReadable() failed: ${e.message}")
            }

            // 始终初始化 Hook：虚拟注入由 global_enabled 控制，抓包由 capture_enabled 控制
            prefs.reload()
            val gEnabled = prefs.getBoolean("global_enabled", true)
            val cEnabled = prefs.getBoolean("capture_enabled", false)
            val devJson  = prefs.getString("devices", "[]") ?: "[]"
            Logger.Hook.i(TAG, "XSharedPreferences loaded: global=$gEnabled, capture=$cEnabled, devices=${if (devJson == "[]") "empty" else "present"}, deviceJsonLen=${devJson.length}")

            // 初始化蓝牙扫描Hook
            val bluetoothScanHook = BluetoothScanHook(lpparam.classLoader, prefs)
            bluetoothScanHook.init()

            // 写入Hook状态标记（供UI进程读取）
            writeHookStatus()

            Logger.Hook.i(TAG, "=== BluetoothHook init END ===")

        } catch (e: Throwable) {
            // 捕获所有异常，防止蓝牙服务崩溃
            Logger.Hook.e(TAG, "Fatal error during initialization", e)
        }
    }

    /**
     * Hook模块自身的Application.onCreate
     * 写入激活标记供UI读取
     */
    private fun hookModuleApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = param.thisObject
                            if (app !is android.content.Context) {
                                Logger.Hook.w(TAG, "thisObject is not a Context: ${app?.javaClass?.name}")
                                return
                            }
                            val context = app as android.content.Context
                            val prefs = context.getSharedPreferences(
                                "module_status",
                                android.content.Context.MODE_PRIVATE
                            )
                            prefs.edit()
                                .putBoolean("xposed_active", true)
                                .putLong("last_hook_time", System.currentTimeMillis())
                                .apply()

                            Logger.Hook.i(TAG, "Module activation marker written successfully")
                        } catch (e: Exception) {
                            Logger.Hook.e(TAG, "Failed to write activation marker", e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Failed to hook module application", e)
        }
    }

    /**
     * 写入Hook状态 – 完全静默（目标机 /data/system 无写权限，EACCES）。
     * 模块激活标记通过 hookModuleApplication 中的 SharedPreferences 写入。
     */
    private fun writeHookStatus() {
        // 不再尝试写入 /data/system – Android 15+ / ColorOS 上必定 EACCES。
        // 状态通过 CaptureSocket STATUS 行和模块自身的 SharedPreferences 传递。
        Logger.Hook.d(TAG, "writeHookStatus skipped (use socket STATUS instead)")
    }
}
