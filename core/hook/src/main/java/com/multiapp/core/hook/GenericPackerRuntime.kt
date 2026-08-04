package com.multiapp.core.hook

import android.util.Log
import com.multiapp.core.hook.antidetection.PackerDetector
import java.io.File

/**
 * Generic packed-shell (加固) runtime adapter.
 *
 * Uses [PackerDetector] to identify the shell family and then runs the
 * universal shell bootstrap path shared by most Android packers:
 * FindClass hook -> StubApp/ApplicationWrapper load -> RegisterNatives ->
 * missing-native fallback.
 *
 * This is intentionally a best-effort fallback: it is registered after the
 * dedicated runtimes (e.g. [JiaguRuntime]) and only fires when no dedicated
 * adapter matched. It converts "NO_PACKER_DETECTED" into a detected-shell
 * adaptation with evidence, which is exactly the missing coverage for
 * WeChat / Qidian / WPS / Weibo in the hosted path.
 */
class GenericPackerRuntime : PackerRuntime {

    override val name: String = "GenericPacker"

    override fun detect(originLibDir: File?, originApkPath: String?): Boolean {
        if (originApkPath == null) return false
        val type = PackerDetector.detect(originApkPath)
        val detected = type != "unknown"
        if (detected) {
            Log.i(TAG, "detect: packer family='" + type + "' from " + originApkPath)
        }
        return detected
    }

    override fun prepareFiles(context: PackerRuntimeContext): Boolean {
        val bridge = context.bridge
        val policy = context.nativeHookPolicy
        val originalPkg = context.originalPackageName

        if (!originalPkg.isNullOrEmpty() && policy.cmdlineSpoof) {
            bridge.spoofProcSelf(android.os.Process.myPid(), originalPkg)
        }

        val nativeBaseDecision = NativeHookPolicyGate.evaluate(
            policy = policy,
            capability = NativeHookCapability.NATIVE_BASE_HOOKS,
            component = "GenericPackerRuntime.prepareFiles.initNativeHooks"
        )
        if (nativeBaseDecision.allowed) {
            bridge.initNativeHooks(
                policy = policy,
                component = "GenericPackerRuntime.prepareFiles.initNativeHooks"
            )
        } else {
            Log.i(TAG, "prepareFiles: native base hooks policy gate " + nativeBaseDecision.status)
        }

        val stubPkg = context.stubPackageName
        if (!stubPkg.isNullOrEmpty() && !originalPkg.isNullOrEmpty() && policy.cmdlineSpoof) {
            bridge.setJiaguPackageSpoof(stubPkg, originalPkg)
        }

        val classLoadDecision = NativeHookPolicyGate.evaluate(
            policy = policy,
            capability = NativeHookCapability.CLASS_LOAD_LOGGING,
            component = "GenericPackerRuntime.prepareFiles.classLoadDiagnostics"
        )
        if (classLoadDecision.allowed) {
            bridge.setupFindClassHook(context.guestClassLoader, SHELL_CLASS_CANDIDATES)
            bridge.installFindClassHook()
        }
        return true
    }

    override fun loadPackerLibrary(context: PackerRuntimeContext): PackerLoadResult {
        val bridge = context.bridge
        val guestCl = context.guestClassLoader
        val diagnostics = mutableListOf<String>()
        val detectedType = context.originApkPath?.let { PackerDetector.detect(it) } ?: "unknown"
        diagnostics += "detectedShellFamily=" + detectedType

        // Resolve the shell's Application entry (StubApp / StubApplication /
        // ApplicationWrapper / SuperApplication) via the guest ClassLoader.
        val shellClass = resolveShellClass(guestCl)
        if (shellClass == null) {
            diagnostics += "shellClass=NOT_FOUND candidates=" + SHELL_CLASS_CANDIDATES.joinToString(",")
            return PackerLoadResult(
                jiaguLoaded = false,
                stubAppLoadSucceeded = false,
                diagnostics = diagnostics
            )
        }
        diagnostics += "shellClass=" + shellClass.name

        // Try the well-known static shell bootstrap entry first.
        val loaded = try {
            val load = shellClass.getDeclaredMethod("load")
            load.isAccessible = true
            load.invoke(null)
            true
        } catch (e: Throwable) {
            diagnostics += "StubApp.load failed: " + e.javaClass.simpleName + ": " + e.message?.take(160)
            false
        }

        // Whatever happened, register every currently-missing native method in
        // the guest loader. This is the shell-independent safety net that lets
        // packed apps reach Application.onCreate even when the shell's own
        // RegisterNatives path is not directly observable.
        val missingRegistered = try {
            bridge.registerAllMissingNativeMethods(guestCl)
        } catch (e: Throwable) {
            Log.w(TAG, "registerAllMissingNativeMethods failed: " + e.message)
            0
        }
        diagnostics += "missingNativeRegistered=" + missingRegistered

        val verified = try {
            bridge.getStubAppRegisterNativesEvidence() != null
        } catch (e: Throwable) {
            Log.w(TAG, "verifyRegisterNatives failed: " + e.message)
            false
        }
        diagnostics += "stubNativesVerified=" + verified

        return PackerLoadResult(
            jiaguLoaded = loaded,
            stubAppLoadSucceeded = loaded,
            stubNativesVerified = verified,
            loadedLibPaths = emptyList(),
            diagnostics = diagnostics
        )
    }

    override fun verifyRegisterNatives(guestCl: ClassLoader): Boolean =
        try {
            NativeHookBridge.getInstance().getStubAppRegisterNativesEvidence() != null
        } catch (e: Throwable) {
            Log.w(TAG, "verifyRegisterNatives failed: " + e.message)
            false
        }

    override fun installPostLoadHooks(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        // 微博 SLib 定向 stub：按类注册空实现（保守策略，非 blanket 白名单）。
        // 微博 APK 含 libslib.so → 家族聚合为 QIHOO_360，本兜底路径亦需覆盖。
        try {
            val slibRegistered = context.bridge.registerSlibStubNatives(context.guestClassLoader)
            Log.i(TAG, "installPostLoadHooks: SLib stub natives registered=$slibRegistered")
        } catch (e: Throwable) {
            Log.w(TAG, "installPostLoadHooks: SLib stub registration failed: " + e.message)
        }
    }

    override fun installStubFallback(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        try {
            context.bridge.registerAllMissingNativeMethods(context.guestClassLoader)
        } catch (e: Throwable) {
            Log.w(TAG, "installStubFallback failed: " + e.message)
        }
        // SLib 二次尝试：post-load 时 SLib 可能尚未加载，fallback 阶段再注册一次。
        try {
            val slibRegistered = context.bridge.registerSlibStubNatives(context.guestClassLoader)
            Log.i(TAG, "installStubFallback: SLib stub natives registered=$slibRegistered")
        } catch (e: Throwable) {
            Log.w(TAG, "installStubFallback: SLib stub registration failed: " + e.message)
        }
    }

    private fun resolveShellClass(guestCl: ClassLoader): Class<*>? {
        for (candidate in SHELL_CLASS_CANDIDATES) {
            try {
                val cls = Class.forName(candidate, false, guestCl)
                if (cls != null) return cls
            } catch (_: Throwable) {
                // try next candidate
            }
        }
        return null
    }

    companion object {
        private const val TAG = "PackerRuntime.Generic"

        /** Shell entry classes used by common families (360/腾讯乐固/爱加密/梆梆/阿里/娜迦). */
        private val SHELL_CLASS_CANDIDATES = arrayOf(
            "com.stub.StubApp",
            "com.qihoo.util.StubApp",
            "com.stub.StubApplication",
            "com.secneo.apkwrapper.ApplicationWrapper",
            "com.shell.SuperApplication",
            "com.tencent.StubShell.TxAppEntry"
        )
    }
}
