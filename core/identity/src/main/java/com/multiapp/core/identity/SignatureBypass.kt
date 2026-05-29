package com.multiapp.core.identity

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.multiapp.core.hook.HookEngine
import timber.log.Timber
import java.util.jar.JarFile

/**
 * Signature bypass hook.
 *
 * Bypasses APK signature verification so the cloned app (repackaged
 * with a different signing key) can pass PackageManager signature checks.
 *
 * - Hook PackageManager.getPackageInfo() to restore original signatures
 * - Read original signatures from the embedded origin.apk at init time
 * - Hook SigningInfo fields for API 28+
 */
class SignatureBypass(
    private val hookEngine: HookEngine,
    private val originApkPath: String? = null
) : HookPoint {

    // 缓存从 origin.apk 读取的原始签名
    private var cachedSignatures: Array<Signature>? = null

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
        Timber.d(
            "SignatureBypass: apply called for instance=%s, pkg=%s",
            config.instanceId,
            config.originalPackageName
        )

        // 从 origin.apk 预读签名
        if (originApkPath != null) {
            cachedSignatures = readSignaturesFromApk(originApkPath)
            Timber.tag(TAG).i(
                "Pre-cached %d signatures from origin.apk",
                cachedSignatures?.size ?: 0
            )
        }

        hookGetPackageInfoSignatures(hookEngine, config.originalPackageName)
        hookGetPackageInfoWithFlags(hookEngine, config.originalPackageName)

        Timber.tag(TAG).i("SignatureBypass installed for pkg=%s", config.originalPackageName)
    }

    companion object {
        private const val TAG = "SignatureBypass"
    }

    private fun hookGetPackageInfoSignatures(
        hookEngine: HookEngine,
        originalPkg: String
    ) {
        try {
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

    private fun interceptPackageInfo(
        result: Any?,
        args: Array<Any?>,
        originalPkg: String
    ): Any? {
        if (result !is PackageInfo) return result
        val queriedPkg = args.firstOrNull() as? String ?: return result
        if (queriedPkg != originalPkg) return result

        val sigs = cachedSignatures ?: return result

        try {
            result.signatures = sigs
            Timber.tag(TAG).d(
                "Replaced signatures for package %s (%d signatures)",
                queriedPkg, sigs.size
            )

            try {
                val signingInfoField = PackageInfo::class.java
                    .getDeclaredField("signingInfo")
                signingInfoField.isAccessible = true
                val signingInfo = signingInfoField.get(result)
                if (signingInfo != null) {
                    val apkSignaturesField = signingInfo::class.java
                        .getDeclaredField("mApkContentsSigners")
                    apkSignaturesField.isAccessible = true
                    apkSignaturesField.set(signingInfo, sigs)
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
     * 从 APK 文件直接读取签名证书
     *
     * 通过 JarFile 读取 AndroidManifest.xml 的签名证书。
     * APK 本质上是 JAR/ZIP，签名证书嵌入在 META-INF/ 中。
     */
    private fun readSignaturesFromApk(apkPath: String): Array<Signature>? {
        return try {
            JarFile(apkPath).use { jar ->
                val manifestEntry = jar.getJarEntry("AndroidManifest.xml")
                    ?: return null
                val certs = manifestEntry.certificates ?: return null
                if (certs.isNullOrEmpty()) return null

                val signatures = Array(certs.size) { i ->
                    Signature(certs[i].encoded)
                }
                Timber.tag(TAG).d("Read ${signatures.size} signatures from $apkPath")
                signatures
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read signatures from $apkPath")
            null
        }
    }
}
