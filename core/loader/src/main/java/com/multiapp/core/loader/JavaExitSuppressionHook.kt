package com.multiapp.core.loader

import com.multiapp.core.hook.HookEngine
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-1: Java 层 System.exit / Runtime.exit / Runtime.halt 拦截。
 *
 * 真机铁证（2026-08-05）：微信 v12/v17、WPS v13 在 guest Application.onCreate 期间
 * 主动调用 System.exit(1)（-> Runtime.exit(1) -> Shutdown.exit -> Runtime.halt）做加固自保护。
 * native 层 _exit 抑制时机太晚——ART 先提交 VM 关停，libc 层拦截时 VM 已僵尸
 * （logcat：VM exiting 早于 _exit intercepted 同毫秒）。因此必须在 Java 层、ART 提交
 * VM 关停之前吞掉非零退出。
 *
 * 语义：
 * - bootstrap 窗口内（[openWindow] 之后、[closeWindow] 之前，即 guest Application
 *   未完成/未 ACK）且 status != 0 -> swallow（不调用 original，记录调用栈到日志）；
 * - 窗口外 或 status == 0（真实用户退出）-> 一律放行（调用 original）。
 *
 * 窗口由调用方管理：
 * - [PackerRuntimeStage] preDetect 阶段调用 [install] 打开窗口（Application 创建之前）；
 * - [ApplicationStage] guest Application.onCreate 完成后调用 [closeWindow]。
 */
object JavaExitSuppressionHook {

    private const val TAG = "JavaExitSuppressionHook"

    private val windowOpen = AtomicBoolean(false)
    private val suppressedCount = AtomicInteger(0)
    private val alwaysLogStacks = AtomicBoolean(false)
    private val stackLogger = java.util.concurrent.atomic.AtomicReference<((Int, String) -> Unit)?>(null)

    /** 当前 bootstrap 窗口是否打开（true = 允许抑制非零退出）。 */
    fun isWindowOpen(): Boolean = windowOpen.get()

    /** 自上次 [openWindow] 以来被吞掉的非零退出次数（诊断/测试）。 */
    fun suppressedExitCount(): Int = suppressedCount.get()

    /** 打开 bootstrap 窗口：从此刻起窗口内非零退出会被抑制。 */
    fun openWindow() {
        windowOpen.set(true)
        suppressedCount.set(0)
    }

    /** 关闭 bootstrap 窗口：从此刻起所有退出一律放行。 */
    fun closeWindow() {
        windowOpen.set(false)
    }
    /** 诊断开关：无论窗口是否打开，所有 exit 调用都记录调用栈（用于定位加固看门狗自杀点）。 */
    fun enableAlwaysLogExitStacks() {
        alwaysLogStacks.set(true)
    }

    fun disableAlwaysLogExitStacks() {
        alwaysLogStacks.set(false)
    }

    /** 注入栈收集器（JVM 测试用；Android 上保持 null 走 Log.w）。 */
    fun setStackLogger(logger: ((Int, String) -> Unit)?) {
        stackLogger.set(logger)
    }

    private fun captureExitStack(): String =
        runCatching {
            Throwable().stackTrace.take(20).joinToString("\n    at ") { it.toString() }
        }.getOrDefault("")

    /**
     * 决策规则（纯函数，JVM 可测）：仅窗口内且 status != 0 才抑制。
     * 宁可窗口略宽（多吞几个非零退出）也不放过加固自保护 exit(1)；
     * status == 0 的真实用户退出永远放行。
     */
    fun shouldSuppress(status: Int): Boolean = windowOpen.get() && status != 0

    data class InstallResult(
        val systemExitHooked: Boolean = false,
        val runtimeExitHooked: Boolean = false,
        val runtimeHaltHooked: Boolean = false,
        val reason: String? = null
    ) {
        val anyHooked: Boolean get() = systemExitHooked || runtimeExitHooked || runtimeHaltHooked
    }

    /**
     * 安装三个 Java 层退出钩子（System.exit / Runtime.exit / Runtime.halt）。
     * 全部安装失败时窗口保持关闭（无钩子即无抑制，行为与未安装一致）。
     */
    fun install(hookEngine: HookEngine, guestClassLoader: ClassLoader? = null): InstallResult {
        return try {
            guestClassLoader?.let { cl ->
                // 防御性幂等初始化：FULL profile 已保证 LSPlant 就绪，重复调用无副作用。
                runCatching { hookEngine.initLsplant(cl) }
            }
            val systemExit = resolveExitMethod("java.lang.System", "exit")
            val runtimeExit = resolveExitMethod("java.lang.Runtime", "exit")
            val runtimeHalt = resolveExitMethod("java.lang.Runtime", "halt")
            val result = InstallResult(
                systemExitHooked = systemExit?.let { installExitHook(it, hookEngine) } ?: false,
                runtimeExitHooked = runtimeExit?.let { installExitHook(it, hookEngine) } ?: false,
                runtimeHaltHooked = runtimeHalt?.let { installExitHook(it, hookEngine) } ?: false
            )
            if (result.anyHooked) {
                openWindow()
                safeLog("installed: $result")
            } else {
                safeLog("no exit hooks installed: $result")
            }
            result
        } catch (e: Throwable) {
            safeLog("install failed: ${e.javaClass.simpleName}: ${e.message}")
            InstallResult(reason = e.javaClass.simpleName + ": " + e.message)
        }
    }

    /**
     * 拦截回调核心（JVM 可测）：窗口内非零退出 -> 吞掉并记录；其余 -> 调用 original。
     */
    fun <T> interceptExit(
        status: Int,
        callOriginal: () -> T,
        onSuppressed: (status: Int) -> Unit = { logSuppressed(it) }
    ): T? {
        return if (shouldSuppress(status)) {
            suppressedCount.incrementAndGet()
            onSuppressed(status)
            null
        } else {
            if (alwaysLogStacks.get()) {
                val stack = captureExitStack()
                stackLogger.get()?.invoke(status, stack)
                    ?: runCatching {
                        android.util.Log.w(TAG, "Unsuppressed exit($status). Stack:\n    at $stack")
                    }
            }
            callOriginal()
        }
    }

    private fun installExitHook(method: Method, hookEngine: HookEngine): Boolean {
        return try {
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                val status = args.firstOrNull() as? Int ?: 0
                interceptExit(status, callOriginal = { callOriginal(arrayOf(status)) })
                Unit
            }
        } catch (e: Throwable) {
            safeLog(
                "hook install failed on ${method.declaringClass.name}.${method.name}: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }

    private fun resolveExitMethod(className: String, methodName: String): Method? {
        return try {
            val clazz = Class.forName(className)
            clazz.getMethod(methodName, Int::class.javaPrimitiveType)
        } catch (e: Throwable) {
            safeLog("resolve $className.$methodName failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun logSuppressed(status: Int) {
        val stack = runCatching {
            Throwable().stackTrace.take(20).joinToString("\n    at ") { it.toString() }
        }.getOrDefault("")
        safeLog("Suppressed exit($status) during bootstrap window. Stack:\n    at $stack")
    }

    private fun safeLog(message: String) {
        try {
            android.util.Log.w(TAG, message)
        } catch (_: Throwable) {
            // JVM 单测里 android.util.Log 是抛异常的 stub，忽略。
        }
    }
}


