package com.multiapp.core.loader

import android.util.Log
import com.multiapp.core.hook.HookEngine
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通用 guest 进程名还原（对齐 VirtualApp 运行期进程名还原思路）。
 *
 * 组件进程/主进程在 attach 拿到 effectiveGuestProcessName 后统一安装：
 *  - Application.getProcessName()
 *  - ActivityThread.currentProcessName()
 *  - Process.myProcessName()
 * 全部返回 guest 进程名（如 com.tencent.mm:sandbox / cn.wps.moffice_eng），
 * 让微信看门狗、WPS RePlugin 等按进程名分流的框架看到「自己人」的名字。
 */
object GuestProcessNameCompat {

    private const val TAG = "GuestProcessNameCompat"

    /** 每进程拦截日志限频：高频进程名读取（微信实测每毫秒数十次）会刷爆 logcat 并拖慢 bootstrap。 */
    private const val MAX_INTERCEPT_LOGS = 20

    private val interceptedLogBudget = AtomicInteger(MAX_INTERCEPT_LOGS)

    data class HookResult(
        val applicationGetProcessNameHooked: Boolean = false,
        val activityThreadCurrentProcessNameHooked: Boolean = false,
        val processMyProcessNameHooked: Boolean = false
    ) {
        val anyHooked: Boolean
            get() = applicationGetProcessNameHooked ||
                activityThreadCurrentProcessNameHooked ||
                processMyProcessNameHooked
    }

    /** 纯决策：effective 优先，否则退回 origin 包名。JVM 可测。 */
    fun resolveGuestProcessName(
        originPackageName: String,
        effectiveGuestProcessName: String?
    ): String = effectiveGuestProcessName
        ?.takeIf { it.isNotBlank() }
        ?: originPackageName

    fun resolveApplicationGetProcessName(): Method? =
        runCatching {
            android.app.Application::class.java.getMethod("getProcessName")
        }.getOrNull()

    fun resolveProcessMyProcessName(): Method? =
        runCatching {
            android.os.Process::class.java.getMethod("myProcessName")
        }.getOrNull()

    fun resolveActivityThreadCurrentProcessName(): Method? =
        runCatching {
            val clazz = Class.forName("android.app.ActivityThread")
            clazz.declaredMethods.firstOrNull {
                it.name == "currentProcessName" &&
                    it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }?.also { it.isAccessible = true }
        }.getOrNull()

    fun install(
        guestProcessName: String,
        hookEngine: HookEngine
    ): HookResult {
        fun installReturnHook(method: Method?): Boolean {
            if (method == null) return false
            return runCatching {
                hookEngine.hookMethod(
                    method,
                    afterCallback = { _, _, _ ->
                        if (interceptedLogBudget.getAndDecrement() > 0) {
                            safeLog("process name getter intercepted - $guestProcessName")
                        }
                        guestProcessName
                    }
                )
            }.getOrElse { e ->
                safeLog(
                    "hook install failed on ${method.declaringClass.name}.${method.name}: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                false
            }
        }
        val result = HookResult(
            applicationGetProcessNameHooked = installReturnHook(resolveApplicationGetProcessName()),
            activityThreadCurrentProcessNameHooked =
                installReturnHook(resolveActivityThreadCurrentProcessName()),
            processMyProcessNameHooked = installReturnHook(resolveProcessMyProcessName())
        )
        safeLog("Guest process-name hooks: $result")
        return result
    }

    private fun safeLog(message: String) {
        runCatching { Log.d(TAG, message) }
    }
}