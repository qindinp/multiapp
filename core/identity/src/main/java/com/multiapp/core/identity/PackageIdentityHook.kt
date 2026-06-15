package com.multiapp.core.identity
import com.multiapp.core.model.IdentityConfig

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
        applyInternal(config)
    }

    companion object {

        private const val TAG = "PackageIdentityHook"

        fun apply(config: IdentityConfig) {
            Timber.d(
                "PackageIdentityHook: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        /**
         * 简化入口：直接传包名，不需要完整的 IdentityConfig
         */
        fun applyDirect(stubPkg: String, originPkg: String) {
            val hookEngine = HookEngine.getInstance()
            hookContextGetPackageName(hookEngine, originPkg)
            hookProcessMyPackageName(hookEngine, originPkg)
            hookAppOpsCheckPackage(hookEngine, originPkg, stubPkg)
            hookPackageManagerGetPackagesForUid(hookEngine, originPkg, stubPkg)
            Timber.tag(TAG).i("PackageIdentityHook.applyDirect: stub=%s -> original=%s", stubPkg, originPkg)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine.getInstance()
            val originalPkg = config.originalPackageName
            val stubPkg = config.stubPackageName

            hookContextGetPackageName(hookEngine, originalPkg)
            hookContextGetPackageCodePath(hookEngine, originalPkg, stubPkg)
            hookContextGetPackageResourcePath(hookEngine, originalPkg, stubPkg)
            rewriteApplicationInfoPackageName(originalPkg, stubPkg)
            hookProcessMyPackageName(hookEngine, originalPkg)

            // ★ 解决 SecurityException: Caller cannot post for pkg
            // 当系统校验 uid 与包名对应关系时，让 stub uid 也能通过 origin 包名的校验
            hookAppOpsCheckPackage(hookEngine, originalPkg, stubPkg)
            hookPackageManagerGetPackagesForUid(hookEngine, originalPkg, stubPkg)

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
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod("getPackageCodePath")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewritePackagePath(result, originalPkg, stubPkg)
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
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod("getPackageResourcePath")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewritePackagePath(result, originalPkg, stubPkg)
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
        private fun rewritePackagePath(result: Any?, originalPkg: String, stubPkg: String): Any? {
            if (result !is String) return result
            return result.replace(stubPkg, originalPkg)
        }

        /**
         * Hook AppOpsManager.checkPackage(uid, pkg) 让 stub uid 通过 origin 包名校验。
         *
         * 系统在发通知、绑定服务等操作时调用此方法校验 uid 与包名的对应关系。
         * stub 的 uid 与 origin 包名不匹配 → SecurityException。
         * 当 uid 是当前进程的 uid 且 pkg 是 origin 包名时，跳过校验。
         */
        private fun hookAppOpsCheckPackage(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val appOpsClass = Class.forName("android.app.AppOpsManager")
                val method = appOpsClass.getDeclaredMethod(
                    "checkPackage", Int::class.javaPrimitiveType, String::class.java
                )
                hookEngine.hookMethod(
                    method = method,
                    beforeCallback = { _, args ->
                        val uid = args?.get(0) as? Int ?: -1
                        val pkg = args?.get(1) as? String
                        val currentUid = android.os.Process.myUid()
                        // 只对当前进程的 uid 生效，避免影响其他进程
                        if (uid == currentUid && pkg == originalPkg) {
                            args[1] = stubPkg
                        }
                        args // 返回修改后的 args，确保 LSPlant 正确传递
                    }
                )
                Timber.tag(TAG).d("Hooked AppOpsManager.checkPackage()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook AppOpsManager.checkPackage()")
            }
        }

        /**
         * Hook PackageManager.getPackagesForUid(uid) 让返回值包含 origin 包名。
         *
         * 某些系统服务通过此方法查询 uid 对应的包名列表。
         * 如果列表中不包含 origin 包名，会导致身份校验失败。
         */
        private fun hookPackageManagerGetPackagesForUid(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val pmClass = Class.forName("android.app.ApplicationPackageManager")
                val method = pmClass.getDeclaredMethod(
                    "getPackagesForUid", Int::class.javaPrimitiveType
                )
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        val packages = result as? Array<*> ?: return@hookMethod result
                        val hasStub = packages.any { it == stubPkg }
                        val hasOrigin = packages.any { it == originalPkg }
                        if (hasStub && !hasOrigin) {
                            arrayOf(*packages, originalPkg)
                        } else {
                            result
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked PackageManager.getPackagesForUid()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook PackageManager.getPackagesForUid()")
            }
        }
    }
}
