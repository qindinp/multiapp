package com.multiapp.core.identity

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.multiapp.core.hook.HookEngine
import timber.log.Timber

/**
 * Signature bypass hook.
 *
 * Phase 4: Bypasses APK signature verification so the cloned app (repackaged
 * with a different signing key) can pass PackageManager signature checks.
 *
 * Inspired by LSPatch SigBypass approach:
 * - Hook PackageManager.getPackageInfo() to restore original signatures
 * - Hook PackageInfo.signatures field access
 * - Proxy PackageInfo.CREATOR to intercept Parcelable deserialization
 */
class SignatureBypass : HookPoint {

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
        Timber.d(
            "SignatureBypass: apply called for instance=%s, pkg=%s",
            config.instanceId,
            config.originalPackageName
        )
        applyInternal(config)
    }

    companion object {

        private const val TAG = "SignatureBypass"

        fun apply(config: IdentityConfig) {
            Timber.d(
                "SignatureBypass: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine.getInstance()
            val originalPkg = config.originalPackageName

            hookGetPackageInfoSignatures(hookEngine, originalPkg)
            hookGetPackageInfoWithFlags(hookEngine, originalPkg)

            Timber.tag(TAG).i("SignatureBypass installed for pkg=%s", originalPkg)
        }

        /**
         * Hook PackageManager.getPackageInfo(String, int) to intercept
         * signature retrieval and replace with original APK signatures.
         *
         * When the target app calls getPackageInfo with GET_SIGNATURES or
         * GET_SIGNING_CERTIFICATES, we intercept the result and replace
         * the signatures with the original app's signatures.
         */
        private fun hookGetPackageInfoSignatures(
            hookEngine: HookEngine,
            originalPkg: String
        ) {
            try {
                // Hook getPackageInfo(String, int) — pre-API 28
                val method = PackageManager::class.java.getDeclaredMethod(
                    "getPackageInfo",
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, args, result ->
                        interceptPackageInfo(result, args, originalPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked PackageManager.getPackageInfo(String, int)")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook getPackageInfo(String, int)")
            }
        }

        /**
         * Hook PackageManager.getPackageInfo(String, PackageManager.PackageInfoFlags)
         * for API 33+ where flags parameter changed to PackageInfoFlags.
         */
        private fun hookGetPackageInfoWithFlags(
            hookEngine: HookEngine,
            originalPkg: String
        ) {
            try {
                val flagsClass = Class.forName(
                    "android.content.pm.PackageManager\$PackageInfoFlags"
                )
                val method = PackageManager::class.java.getDeclaredMethod(
                    "getPackageInfo",
                    String::class.java,
                    flagsClass
                )
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, args, result ->
                        interceptPackageInfo(result, args, originalPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked PackageManager.getPackageInfo(String, PackageInfoFlags)")
            } catch (e: ClassNotFoundException) {
                Timber.tag(TAG).d("PackageInfoFlags class not found (pre-API 33), skipping")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook getPackageInfo with PackageInfoFlags")
            }
        }

        /**
         * Intercept a PackageInfo result and rewrite its signatures.
         *
         * If the query was for the original package, we read the real signatures
         * from the original APK and inject them into the returned PackageInfo.
         */
        private fun interceptPackageInfo(
            result: Any?,
            args: Array<Any?>,
            originalPkg: String
        ): Any? {
            if (result !is PackageInfo) return result

            val queriedPkg = args.firstOrNull() as? String ?: return result

            // Only intercept queries for the original package or our stub
            if (queriedPkg != originalPkg) return result

            try {
                // Read the original APK signatures from the actual installed package
                val originalSignatures = readOriginalSignatures(originalPkg)
                if (originalSignatures != null) {
                    // Replace signatures in the returned PackageInfo
                    result.signatures = originalSignatures
                    Timber.tag(TAG).d(
                        "Replaced signatures for package %s (%d signatures)",
                        queriedPkg, originalSignatures.size
                    )
                }

                // Also patch signingInfo for API 28+
                try {
                    val signingInfoField = PackageInfo::class.java
                        .getDeclaredField("signingInfo")
                    signingInfoField.isAccessible = true
                    val signingInfo = signingInfoField.get(result)
                    if (signingInfo != null) {
                        val apkSignaturesField = signingInfo::class.java
                            .getDeclaredField("mApkContentsSigners")
                        apkSignaturesField.isAccessible = true
                        if (originalSignatures != null) {
                            apkSignaturesField.set(signingInfo, originalSignatures)
                        }
                    }
                } catch (_: Exception) {
                    // signingInfo field may not exist on older APIs
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to intercept signatures for %s", queriedPkg)
            }

            return result
        }

        /**
         * 读取原始 APK 签名（绕过自身 hook 避免递归）
         *
         * 使用 getPackageArchiveInfo 直接读文件，
         * 不调用 getPackageInfo（避免命中已安装的 hook 导致递归）。
         */
        private fun readOriginalSignatures(originalPkg: String): Array<Signature>? {
            return try {
                val atClass = Class.forName("android.app.ActivityThread")
                val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
                val ctx = atClass.getDeclaredMethod("getSystemContext").invoke(at) as android.content.Context
                val pm = ctx.packageManager

                // 方法1: 从 Stub assets 中读取的 origin.apk（最可靠）
                val originApkPath = findOriginApkPath(ctx)
                if (originApkPath != null) {
                    val pkgInfo = pm.getPackageArchiveInfo(originApkPath, PackageManager.GET_SIGNATURES)
                    if (pkgInfo?.signatures != null) {
                        Timber.tag(TAG).d("Read signatures from origin.apk: %s", originApkPath)
                        return pkgInfo.signatures
                    }
                }

                // 方法2: 通过 ApplicationInfo.sourceDir 获取（系统安装的原始 APK）
                try {
                    val appInfo = pm.getApplicationInfo(originalPkg, 0)
                    val sourceDir = appInfo.sourceDir
                    if (sourceDir != null && java.io.File(sourceDir).exists()) {
                        val pkgInfo = pm.getPackageArchiveInfo(sourceDir, PackageManager.GET_SIGNATURES)
                        if (pkgInfo?.signatures != null) {
                            Timber.tag(TAG).d("Read signatures from sourceDir: %s", sourceDir)
                            return pkgInfo.signatures
                        }
                    }
                } catch (_: Exception) { /* original app may not be installed */ }

                // 方法3: 扫描 /data/app/ 目录（兜底）
                val dataAppDir = java.io.File("/data/app")
                if (dataAppDir.isDirectory) {
                    dataAppDir.listFiles()?.forEach { dir ->
                        if (dir.isDirectory && dir.name.contains(originalPkg)) {
                            val apk = java.io.File(dir, "base.apk")
                            if (apk.exists()) {
                                val pkgInfo = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                                if (pkgInfo?.signatures != null) {
                                    Timber.tag(TAG).d("Read signatures from scan: %s", apk.absolutePath)
                                    return pkgInfo.signatures
                                }
                            }
                        }
                    }
                }

                Timber.tag(TAG).w("No signatures found for %s", originalPkg)
                null
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to read original signatures for %s", originalPkg)
                null
            }
        }

        /**
         * 查找 Stub assets 中解压的 origin.apk 路径
         */
        private fun findOriginApkPath(ctx: android.content.Context): String? {
            val candidates = listOf(
                "${ctx.dataDir}/cache/origin/base.apk",
                "${ctx.cacheDir}/origin/base.apk"
            )
            for (path in candidates) {
                if (java.io.File(path).exists()) return path
            }
            return null
        }
    }
}
