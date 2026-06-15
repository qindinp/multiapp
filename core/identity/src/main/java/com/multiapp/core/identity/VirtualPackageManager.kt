package com.multiapp.core.identity

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

/**
 * Unified PackageManager proxy for MultiApp identity virtualization.
 *
 * Intercepts PackageManager queries so that:
 * - System-facing calls (UID check, package resolution) use stub package name
 * - Guest-facing queries (app code reading own identity) use original package name
 * - Installed package lists filter out stub entries
 *
 * Uses [HookEngine.hookMethodPassThrough] to intercept [ApplicationPackageManager]
 * methods, calling through to the original implementation and patching results.
 */
class VirtualPackageManager private constructor(
    private val stubPackageName: String,
    private val originalPackageName: String
) {

    companion object {
        private const val TAG = "VirtualPackageManager"

        @Volatile
        private var instance: VirtualPackageManager? = null

        /**
         * Initialize and install VirtualPackageManager hooks.
         *
         * Call after identity hooks ([PackageIdentityHook]) are installed.
         * Idempotent — repeated calls are no-ops.
         */
        fun install(stubPackageName: String, originalPackageName: String) {
            if (instance != null) {
                Timber.tag(TAG).d("Already installed, skipping")
                return
            }
            val vpm = VirtualPackageManager(stubPackageName, originalPackageName)
            vpm.installHooks()
            instance = vpm
            Timber.tag(TAG).i(
                "VirtualPackageManager installed: stub=%s, original=%s",
                stubPackageName, originalPackageName
            )
        }

        fun getInstance(): VirtualPackageManager? = instance

        fun reset() {
            instance = null
        }
    }

    private fun installHooks() {
        val hookEngine = HookEngine.getInstance()
        hookGetPackageInfo(hookEngine)
        hookGetApplicationInfo(hookEngine)
        hookGetInstalledPackages(hookEngine)
    }

    /**
     * Hook [PackageManager.getPackageInfo] to return original package info
     * when the guest queries its own package.
     */
    private fun hookGetPackageInfo(hookEngine: HookEngine) {
        try {
            val pmClass = Class.forName("android.app.ApplicationPackageManager")
            val method = pmClass.getDeclaredMethod(
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            hookEngine.hookMethodPassThrough(
                method = method,
                afterCallback = { _, args, result ->
                    val queriedPkg = args.getOrNull(0) as? String
                    if (result is PackageInfo &&
                        (queriedPkg == stubPackageName || queriedPkg == originalPackageName)
                    ) {
                        result.packageName = originalPackageName
                        result.applicationInfo?.packageName = originalPackageName
                    }
                    result
                }
            )
            Timber.tag(TAG).d("Hooked getPackageInfo(String, int)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook getPackageInfo")
        }
    }

    /**
     * Hook [PackageManager.getApplicationInfo] to return original application info
     * when the guest queries its own package.
     */
    private fun hookGetApplicationInfo(hookEngine: HookEngine) {
        try {
            val pmClass = Class.forName("android.app.ApplicationPackageManager")
            val method = pmClass.getDeclaredMethod(
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            hookEngine.hookMethodPassThrough(
                method = method,
                afterCallback = { _, args, result ->
                    val queriedPkg = args.getOrNull(0) as? String
                    if (result is ApplicationInfo &&
                        (queriedPkg == stubPackageName || queriedPkg == originalPackageName)
                    ) {
                        result.packageName = originalPackageName
                    }
                    result
                }
            )
            Timber.tag(TAG).d("Hooked getApplicationInfo(String, int)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook getApplicationInfo")
        }
    }

    /**
     * Hook [PackageManager.getInstalledPackages] to filter out stub package entries.
     *
     * The system returns packages including the stub identity. Guest code should
     * only see the original package name in the installed list.
     */
    private fun hookGetInstalledPackages(hookEngine: HookEngine) {
        try {
            val pmClass = Class.forName("android.app.ApplicationPackageManager")
            val method = pmClass.getDeclaredMethod(
                "getInstalledPackages",
                Int::class.javaPrimitiveType
            )
            hookEngine.hookMethodPassThrough(
                method = method,
                afterCallback = { _, _, result ->
                    filterStubPackages(result)
                }
            )
            Timber.tag(TAG).d("Hooked getInstalledPackages(int)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook getInstalledPackages")
        }
    }

    /**
     * Remove stub package entries from a package list result.
     *
     * Handles both [List] (public API) and ParceledListSlice (internal AIDL)
     * return types from different Android versions.
     */
    private fun filterStubPackages(result: Any?): Any? {
        if (result == null) return result

        when (result) {
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                val packages = result as List<PackageInfo>
                val filtered = packages.filter { it.packageName != stubPackageName }
                if (filtered.size != packages.size) {
                    Timber.tag(TAG).d(
                        "Filtered %d stub entries from installed packages list",
                        packages.size - filtered.size
                    )
                }
                return filtered
            }
            else -> {
                // ParceledListSlice — attempt to filter via reflection
                try {
                    val listField = result.javaClass.getDeclaredField("mList")
                    listField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val packages = listField.get(result) as? MutableList<PackageInfo>
                    if (packages != null) {
                        val removed = packages.removeAll { it.packageName == stubPackageName }
                        if (removed) {
                            Timber.tag(TAG).d("Filtered stub entries from ParceledListSlice")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).d("ParceledListSlice filter skipped: %s", e.message)
                }
                return result
            }
        }
    }
}
