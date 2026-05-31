package com.multiapp.core.identity
import com.multiapp.core.model.IdentityConfig

import android.app.ActivityManager
import android.os.Process
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

/**
 * ActivityManager hook.
 *
 * Phase 4: Spoofs ActivityManager return values so the cloned app sees
 * consistent identity information when querying its own process state,
 * running tasks, and memory info.
 *
 * Hook points:
 * 1. ActivityManager.getRunningAppProcesses() - rewrite process package names and UID
 * 2. Process.myUid() - return original UID if needed for consistency
 * 3. ActivityManager.getMyMemoryState() - ensure consistent process state
 */
class ActivityManagerHook : HookPoint {

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
        Timber.d(
            "ActivityManagerHook: apply called for instance=%s, stub=%s",
            config.instanceId,
            config.stubPackageName
        )
        applyInternal(config)
    }

    companion object {

        private const val TAG = "ActivityManagerHook"

        fun apply(config: IdentityConfig) {
            Timber.d(
                "ActivityManagerHook: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine.getInstance()
            val originalPkg = config.originalPackageName
            val stubPkg = config.stubPackageName

            hookGetRunningAppProcesses(hookEngine, originalPkg, stubPkg)
            hookProcessMyUid(hookEngine)
            hookGetMyMemoryState(hookEngine, originalPkg)

            Timber.tag(TAG).i(
                "ActivityManagerHook installed for instance=%s",
                config.instanceId
            )
        }

        /**
         * Hook ActivityManager.getRunningAppProcesses() to rewrite
         * package names and process names in the returned list.
         *
         * For each RunningAppProcessInfo in the result:
         * - Replace stub package name with original package name in pkgList
         * - Replace stub process name with original process name
         * - Keep the UID as-is (system-assigned)
         */
        private fun hookGetRunningAppProcesses(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = ActivityManager::class.java
                    .getDeclaredMethod("getRunningAppProcesses")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewriteRunningProcesses(result, originalPkg, stubPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked ActivityManager.getRunningAppProcesses()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook getRunningAppProcesses()")
            }
        }

        /**
         * Hook Process.myUid() to return the real UID.
         *
         * In some cases, the cloned app's UID may differ from what the
         * target app expects. This hook ensures consistency.
         */
        private fun hookProcessMyUid(hookEngine: HookEngine) {
            try {
                val method = Process::class.java.getDeclaredMethod("myUid")
                // We don't modify the UID by default — just log for debugging
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        Timber.tag(TAG).d("Process.myUid() = %s", result)
                        result // return as-is, no modification needed
                    }
                )
                Timber.tag(TAG).d("Hooked Process.myUid() (monitoring only)")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Process.myUid()")
            }
        }

        /**
         * Hook ActivityManager.getMyMemoryState() to ensure the process
         * state is consistent with the original package identity.
         *
         * The method fills in an ActivityManager.RunningAppProcessInfo
         * object with the current process state.
         */
        private fun hookGetMyMemoryState(
            hookEngine: HookEngine,
            originalPkg: String
        ) {
            try {
                val method = ActivityManager::class.java
                    .getDeclaredMethod(
                        "getMyMemoryState",
                        ActivityManager.RunningAppProcessInfo::class.java
                    )
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, args, result ->
                        // Rewrite the process info that was filled in
                        val processInfo = args.firstOrNull()
                            as? ActivityManager.RunningAppProcessInfo
                        if (processInfo != null) {
                            rewriteProcessInfo(processInfo, originalPkg)
                        }
                        result
                    }
                )
                Timber.tag(TAG).d("Hooked ActivityManager.getMyMemoryState()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook getMyMemoryState()")
            }
        }

        /**
         * Rewrite the running process list to replace stub package names
         * with the original package name.
         */
        private fun rewriteRunningProcesses(
            result: Any?,
            originalPkg: String,
            stubPkg: String
        ): Any? {
            if (result !is List<*>) return result

            try {
                for (processInfo in result) {
                    if (processInfo !is ActivityManager.RunningAppProcessInfo) continue

                    // Rewrite pkgList entries
                    val pkgList = processInfo.pkgList
                    if (pkgList != null) {
                        for (i in pkgList.indices) {
                            if (pkgList[i] == stubPkg) {
                                pkgList[i] = originalPkg
                                Timber.tag(TAG).d(
                                    "Rewrote pkgList[%d]: %s -> %s",
                                    i, stubPkg, originalPkg
                                )
                            }
                        }
                    }

                    // Rewrite processName if it contains the stub package
                    if (processInfo.processName?.contains(stubPkg) == true) {
                        processInfo.processName = processInfo.processName!!.replace(
                            stubPkg, originalPkg
                        )
                        Timber.tag(TAG).d(
                            "Rewrote processName: %s",
                            processInfo.processName
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to rewrite running processes")
            }

            return result
        }

        /**
         * Rewrite a single RunningAppProcessInfo to use the original
         * package name.
         */
        private fun rewriteProcessInfo(
            processInfo: ActivityManager.RunningAppProcessInfo,
            originalPkg: String
        ) {
            try {
                // Rewrite pkgList
                val pkgList = processInfo.pkgList
                if (pkgList != null && pkgList.isNotEmpty()) {
                    // Replace the first entry (primary package) with the original
                    if (pkgList[0] != originalPkg) {
                        pkgList[0] = originalPkg
                        Timber.tag(TAG).d("Rewrote memory state pkgList[0] to %s", originalPkg)
                    }
                }

                // Rewrite processName
                val currentName = processInfo.processName
                if (currentName != null && !currentName.startsWith(originalPkg)) {
                    // Extract the process suffix (e.g., ":remote") if present
                    val colonIndex = currentName.indexOf(':')
                    val suffix = if (colonIndex >= 0) currentName.substring(colonIndex) else ""
                    processInfo.processName = "$originalPkg$suffix"
                    Timber.tag(TAG).d(
                        "Rewrote memory state processName: %s -> %s",
                        currentName, processInfo.processName
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to rewrite process info")
            }
        }
    }
}
