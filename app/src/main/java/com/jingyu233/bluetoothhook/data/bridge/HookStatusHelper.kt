package com.jingyu233.bluetoothhook.data.bridge

/**
 * Hook 状态解析（UI 与 ConfigBridge 共用）
 */
object HookStatusHelper {

    enum class Activation { Active, Inactive, Unknown }

    data class Status(
        val activation: Activation,
        /** 简短摘要，用于折叠态 */
        val summary: String,
        /** Socket 推送的详细诊断信息 */
        val detail: CaptureBridge.HookStatus?
    )

    fun resolve(detail: CaptureBridge.HookStatus?, moduleActive: Boolean): Status {
        if (detail != null) {
            val classOk = detail.classFound != "NONE"
            val methodOk = detail.methodFound != "NONE"
            val activation = if (classOk && methodOk) Activation.Active else Activation.Inactive
            val summary = when (activation) {
                Activation.Active -> "Hook 已激活"
                Activation.Inactive -> "Hook 未完全就绪"
                Activation.Unknown -> "Hook 状态未知"
            }
            return Status(activation, summary, detail)
        }
        return if (moduleActive) {
            Status(Activation.Unknown, "模块已加载，等待蓝牙扫描…", null)
        } else {
            Status(Activation.Unknown, "Hook 状态未知", null)
        }
    }

    fun isModuleActive(context: android.content.Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("module_status", android.content.Context.MODE_PRIVATE)
            prefs.getBoolean("xposed_active", false)
        } catch (_: Exception) {
            false
        }
    }
}
