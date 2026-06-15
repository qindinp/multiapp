package com.multiapp.core.identity

import android.app.ActivityManager
import android.app.ActivityManager.RecentTaskInfo
import android.app.ActivityManager.RunningServiceInfo
import android.app.ActivityManager.RunningAppProcessInfo
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

/**
 * Unified ActivityManager proxy for MultiApp identity virtualization.
 *
 * Intercepts ActivityManager queries so that:
 * - [getRunningAppProcesses] rewrites stub package names to original package name
 * - [getRunningServices] rewrites stub package names to original package name
 * - [getRecentTasks] filters out entries whose base intent targets the stub package
 *
 * Uses [HookEngine.hookMethodPassThrough] to intercept [ActivityManager] methods,
 * calling through to the original implementation and patching results.
 */
class VirtualActivityManager private constructor(
    private val stubPackageName: String,
    private val originalPackageName: String
) {

    companion object {
        private const val TAG = "VirtualActivityManager"

        @Volatile
        private var instance: VirtualActivityManager? = null

        /**
         * Initialize and install VirtualActivityManager hooks.
         *
         * Call after identity hooks ([PackageIdentityHook]) and
         * [VirtualPackageManager] are installed. Idempotent.
         */
        fun install(stubPackageName: String, originalPackageName: String) {
            if (instance != null) {
                Timber.tag(TAG).d("Already installed, skipping")
                return
            }
            val vam = VirtualActivityManager(stubPackageName, originalPackageName)
            vam.installHooks()
            instance = vam
            Timber.tag(TAG).i(
                "VirtualActivityManager installed: stub=%s, original=%s",
                stubPackageName, originalPackageName
            )
        }

        fun getInstance(): VirtualActivityManager? = instance

        fun reset() {
            instance = null
        }
    }

    private fun installHooks() {
        val hookEngine = HookEngine.getInstance()
        hookGetRunningAppProcesses(hookEngine)
        hookGetRunningServices(hookEngine)
        hookGetRecentTasks(hookEngine)
    }

    /**
     * Hook [ActivityManager.getRunningAppProcesses] to rewrite stub package
     * names and process names to original values in the returned list.
     */
    private fun hookGetRunningAppProcesses(hookEngine: HookEngine) {
        try {
            val method = ActivityManager::class.java
                .getDeclaredMethod("getRunningAppProcesses")
            hookEngine.hookMethodPassThrough(
                method = method,
                afterCallback = { _, _, result ->
                    rewriteRunningProcesses(result)
                }
            )
            Timber.tag(TAG).d("Hooked getRunningAppProcesses()")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook getRunningAppProcesses")
        }
    }

    /**
     * Hook [ActivityManager.getRunningServices] to rewrite stub package
     * names to original values in the returned list.
     */
    private fun hookGetRunningServices(hookEngine: HookEngine) {
        try {
            val method = ActivityManager::class.java.getDeclaredMethod(
                "getRunningServices",
                Int::class.javaPrimitiveType
            )
            hookEngine.hookMethodPassThrough(
                method = method,
                afterCallback = { _, _, result ->
                    rewriteRunningServices(result)
                }
            )
            Timber.tag(TAG).d("Hooked getRunningServices(int)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook getRunningServices")
        }
    }

    /**
     * Hook [ActivityManager.getRecentTasks] to filter out entries whose
     * base intent targets the stub package.
     */
    private fun hookGetRecentTasks(hookEngine: HookEngine) {
        try {
            val method = ActivityManager::class.java.getDeclaredMethod(
                "getRecentTasks",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            hookEngine.hookMethodPassThrough(
                method = method,
                afterCallback = { _, _, result ->
                    filterStubRecentTasks(result)
                }
            )
            Timber.tag(TAG).d("Hooked getRecentTasks(int, int)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook getRecentTasks")
        }
    }

    private fun rewriteRunningProcesses(result: Any?): Any? {
        if (result !is List<*>) return result
        try {
            for (processInfo in result) {
                if (processInfo !is RunningAppProcessInfo) continue
                val pkgList = processInfo.pkgList
                if (pkgList != null) {
                    for (i in pkgList.indices) {
                        if (pkgList[i] == stubPackageName) {
                            pkgList[i] = originalPackageName
                        }
                    }
                }
                if (processInfo.processName?.contains(stubPackageName) == true) {
                    processInfo.processName = processInfo.processName!!.replace(
                        stubPackageName, originalPackageName
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to rewrite running processes")
        }
        return result
    }

    private fun rewriteRunningServices(result: Any?): Any? {
        if (result !is List<*>) return result
        try {
            for (serviceInfo in result) {
                if (serviceInfo !is RunningServiceInfo) continue
                val svc = serviceInfo.service
                if (svc?.packageName == stubPackageName) {
                    serviceInfo.service = android.content.ComponentName(
                        originalPackageName, svc.className
                    )
                }
                if (serviceInfo.process?.contains(stubPackageName) == true) {
                    serviceInfo.process = serviceInfo.process!!.replace(
                        stubPackageName, originalPackageName
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to rewrite running services")
        }
        return result
    }

    private fun filterStubRecentTasks(result: Any?): Any? {
        if (result !is List<*>) return result
        try {
            @Suppress("UNCHECKED_CAST")
            val tasks = result as List<RecentTaskInfo>
            val filtered = tasks.filter { task ->
                val intent = task.baseIntent
                intent?.component?.packageName != stubPackageName
            }
            if (filtered.size != tasks.size) {
                Timber.tag(TAG).d(
                    "Filtered %d stub entries from recent tasks",
                    tasks.size - filtered.size
                )
            }
            return filtered
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to filter recent tasks")
            return result
        }
    }
}
