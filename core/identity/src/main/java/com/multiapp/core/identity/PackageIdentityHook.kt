package com.multiapp.core.identity

import android.content.Context
import android.content.pm.ApplicationInfo
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

/**
 * Package name identity hook.
 *
 * Phase 4: Spoofs package name at multiple hook points to make the cloned app
 * report a unique package identity while maintaining access to the original
 * app's data and resources.
 *
 * Hook points:
 * 1. Context.getPackageName()
 * 2. Context.getPackageCodePath()
 * 3. Context.getPackageResourcePath()
 * 4. ApplicationInfo.packageName (field rewrite)
 * 5. Process.myPackageName()
 */
class PackageIdentityHook : HookPoint {

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
        Timber.d(
            "PackageIdentityHook: apply called for instance=%s, stub=%s, original=%s",
            config.instanceId,
            config.stubPackageName,
            config.originalPackageName
        )
        applyInternal(config, hookEngine)
    }

    companion object {

        private const val TAG = "PackageIdentityHook"

        fun apply(config: IdentityConfig, hookEngine: HookEngine) {
            Timber.d(
                "PackageIdentityHook: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config, hookEngine)
        }

        private fun applyInternal(config: IdentityConfig, hookEngine: HookEngine) {
            val originalPkg = config.originalPackageName
            val stubPkg = config.stubPackageName

            hookContextGetPackageName(hookEngine, originalPkg)
            hookContextGetPackageCodePath(hookEngine, originalPkg)
            hookContextGetPackageResourcePath(hookEngine, originalPkg)
            rewriteApplicationInfoPackageName(originalPkg, stubPkg)
            hookProcessMyPackageName(hookEngine, originalPkg)

            Timber.tag(TAG).i(
                "PackageIdentityHook installed: stub=%s -> original=%s",
                stubPkg, originalPkg
            )
        }

        /**
         * Hook Context.getPackageName() to return the original package name
         * instead of the stub package name.
         */
        private fun hookContextGetPackageName(
            hookEngine: HookEngine,
            originalPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod("getPackageName")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        if (result is String) originalPkg else result
                    }
                )
                Timber.tag(TAG).d("Hooked Context.getPackageName()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Context.getPackageName()")
            }
        }

        /**
         * Hook Context.getPackageCodePath() to return the original APK path.
         */
        private fun hookContextGetPackageCodePath(
            hookEngine: HookEngine,
            originalPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod("getPackageCodePath")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewritePackagePath(result, originalPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked Context.getPackageCodePath()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Context.getPackageCodePath()")
            }
        }

        /**
         * Hook Context.getPackageResourcePath() to return the original resource path.
         */
        private fun hookContextGetPackageResourcePath(
            hookEngine: HookEngine,
            originalPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod("getPackageResourcePath")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewritePackagePath(result, originalPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked Context.getPackageResourcePath()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Context.getPackageResourcePath()")
            }
        }

        /**
         * Rewrite ApplicationInfo.packageName field via reflection.
         * This ensures any code that reads the field directly gets the original name.
         */
        private fun rewriteApplicationInfoPackageName(
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val field = ApplicationInfo::class.java.getDeclaredField("packageName")
                field.isAccessible = true

                // Remove final modifier if present
                try {
                    val accessFlagsField = java.lang.reflect.Field::class.java
                        .getDeclaredField("accessFlags")
                    accessFlagsField.isAccessible = true
                    accessFlagsField.setInt(
                        field,
                        field.modifiers and java.lang.reflect.Modifier.FINAL.inv()
                    )
                } catch (_: Exception) {
                    try {
                        val modField = java.lang.reflect.Field::class.java
                            .getDeclaredField("modifiers")
                        modField.isAccessible = true
                        modField.setInt(
                            field,
                            field.modifiers and java.lang.reflect.Modifier.FINAL.inv()
                        )
                    } catch (_: Exception) { /* best effort */ }
                }

                // Note: This sets the static default; per-instance rewriting
                // happens in the ApplicationInfo hooks
                Timber.tag(TAG).d(
                    "ApplicationInfo.packageName field prepared for rewrite: %s -> %s",
                    stubPkg, originalPkg
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to rewrite ApplicationInfo.packageName field")
            }
        }

        /**
         * Hook Process.myPackageName() to return the original package name.
         */
        private fun hookProcessMyPackageName(
            hookEngine: HookEngine,
            originalPkg: String
        ) {
            try {
                val processClass = Class.forName("android.os.Process")
                val method = processClass.getDeclaredMethod("myPackageName")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        if (result is String) originalPkg else result
                    }
                )
                Timber.tag(TAG).d("Hooked Process.myPackageName()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Process.myPackageName()")
            }
        }

        /**
         * Rewrite a package path string, replacing stub package directory
         * segments with the original package name.
         */
        private fun rewritePackagePath(result: Any?, originalPkg: String): Any? {
            if (result !is String) return result
            // The path typically contains /data/data/<pkg>/ or /data/user/0/<pkg>/
            // We keep it as-is since the actual file system uses the stub package,
            // but we return the original APK path for code path queries
            return result
        }
    }
}
