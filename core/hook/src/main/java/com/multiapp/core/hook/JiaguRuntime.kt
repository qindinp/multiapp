package com.multiapp.core.hook

import android.util.Log
import java.io.File

/**
 * JiaguRuntime — 360 加固壳（libjiagu_vip.so）运行时实现
 *
 * 核心流程：
 * 1. prepareFiles: spoofProcSelf + ShadowHook + FindClass hook + GOT hook
 * 2. loadPackerLibrary: StubApp.load() → 壳 JNI_OnLoad → RegisterNatives
 * 3. installPostLoadHooks: Class.forName 触发 YWLoginManager <clinit> → interface11(59494)
 */
class JiaguRuntime : PackerRuntime {

    companion object {
        private const val TAG = "PackerRuntime.Jiagu"
        private const val JIAGU_LIB = "libjiagu_vip.so"
        private val STUB_APP_CANDIDATES = arrayOf(
            "com.stub.StubApp",
            "com.qihoo.util.StubApp",
            "com.stub.StubApplication",
            "com.secneo.apkwrapper.ApplicationWrapper"
        )
    }

    override val name: String = "Jiagu360"

    override fun detect(originLibDir: File?, originApkPath: String?): Boolean {
        if (originLibDir == null || !originLibDir.isDirectory) return false
        val jiaguFile = File(originLibDir, JIAGU_LIB)
        if (jiaguFile.exists()) {
            Log.i(TAG, "detect: $JIAGU_LIB found at ${jiaguFile.absolutePath}")
            return true
        }
        return false
    }

    override fun prepareFiles(context: PackerRuntimeContext): Boolean {
        val bridge = context.bridge

        // 伪装 /proc/self/cmdline 为原始包名
        val originalPkg = context.originalPackageName
        if (!originalPkg.isNullOrEmpty()) {
            bridge.spoofProcSelf(android.os.Process.myPid(), originalPkg)
            Log.d(TAG, "prepareFiles: /proc/self spoofed to '$originalPkg'")
        }

        // ShadowHook 初始化（必须在 dlopen 之前）
        val nativeBaseDecision = NativeHookPolicyGate.evaluate(
            policy = context.nativeHookPolicy,
            capability = NativeHookCapability.NATIVE_BASE_HOOKS,
            component = "JiaguRuntime.prepareFiles.initNativeHooks"
        )
        if (!nativeBaseDecision.allowed) {
            Log.i(TAG, "prepareFiles: native base hooks policy gate ${nativeBaseDecision.status} ${nativeBaseDecision.evidence}")
        }
        val hooksOk = if (nativeBaseDecision.allowed) {
            bridge.initNativeHooks(
                policy = context.nativeHookPolicy,
                component = "JiaguRuntime.prepareFiles.initNativeHooks"
            )
        } else {
            false
        }
        Log.d(TAG, "prepareFiles: native hooks initialized: $hooksOk")
        val stubPkg = context.stubPackageName
        if (!stubPkg.isNullOrEmpty() && !originalPkg.isNullOrEmpty()) {
            bridge.setJiaguPackageSpoof(stubPkg, originalPkg)
            Log.d(TAG, "prepareFiles: jiagu package spoof $stubPkg -> $originalPkg")
        }
        val jiaguJniDiagOk = bridge.installJiaguJniDiagHooks()
        Log.d(TAG, "prepareFiles: Jiagu JNI diag hooks installed: $jiaguJniDiagOk")

        // FindClass hook（壳的 JNI_OnLoad 需要通过 guest ClassLoader 查壳类）
        val hookReady = bridge.setupFindClassHook(context.guestClassLoader, STUB_APP_CANDIDATES)
        if (hookReady) {
            bridge.installFindClassHook()
            Log.d(TAG, "prepareFiles: FindClass hook installed")
        }

        // 完整性校验重定向（壳校验 APK 时重定向到原始 APK）
        val modifiedApkPath = context.originApkPath
        val originalApkPath = context.originalApkPath
        if (modifiedApkPath != null && originalApkPath != null) {
            bridge.setIntegrityRedirect(modifiedApkPath, originalApkPath)
            Log.d(TAG, "prepareFiles: integrity redirect set")
        }

        // GOT hook libc.so（过滤 /proc/self/maps）
        bridge.gotHookLibrary("libc.so")
        Log.d(TAG, "prepareFiles: GOT hook on libc.so installed")

        // GOT hook 壳相关库
        arrayOf("libfockrt.so", "libfock.so").forEach { lib ->
            try { bridge.gotHookLibrary(lib) } catch (_: Throwable) {}
        }

        return true
    }

    override fun loadPackerLibrary(context: PackerRuntimeContext): PackerLoadResult {
        val bridge = context.bridge
        val guestCl = context.guestClassLoader
        val diagnostics = mutableListOf<String>()
        var jiaguLoaded = false
        var stubAppLoadSucceeded = false

        val callerClass = resolveStubAppClass(guestCl)
        if (callerClass == null) {
            diagnostics.add("StubApp not found")
            return PackerLoadResult(false, false, emptyList(), diagnostics)
        }

        // 调用 StubApp.load() — 壳的 JNI_OnLoad 在此期间执行
        try {
            val loadMethod = callerClass.declaredMethods.firstOrNull { m ->
                m.name == "load" && m.parameterTypes.isEmpty() &&
                    java.lang.reflect.Modifier.isStatic(m.modifiers)
            }
            if (loadMethod != null) {
                loadMethod.isAccessible = true
                try {
                    loadMethod.invoke(null)
                    Log.d(TAG, "loadPackerLibrary: StubApp.load() OK")
                    stubAppLoadSucceeded = true
                    jiaguLoaded = true
                    try {
                        Log.w(TAG, "loadPackerLibrary: StubApp binding after load: ${bridge.getStubAppBindingReport()}")
                    } catch (e: Throwable) {
                        Log.w(TAG, "loadPackerLibrary: StubApp binding report failed: ${e.message}")
                    }
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.targetException ?: e.cause ?: e
                    diagnostics.add("StubApp.load() threw: ${cause.javaClass.simpleName}: ${cause.message}")
                    try {
                        Log.w(TAG, "loadPackerLibrary: StubApp binding after load failure: ${bridge.getStubAppBindingReport()}")
                    } catch (reportError: Throwable) {
                        Log.w(TAG, "loadPackerLibrary: StubApp binding report after failure failed: ${reportError.message}")
                    }
                }
                // 补装 GOT hook
                arrayOf(JIAGU_LIB, "libfockrt.so", "libfock.so").forEach { lib ->
                    try { bridge.gotHookLibrary(lib) } catch (_: Throwable) {}
                }
                // 加载 libfockrt.so
                try {
                    val originLibDir = context.originLibDir
                    if (originLibDir != null) {
                        val fockRt = File(originLibDir, "libfockrt.so")
                        if (fockRt.exists()) {
                            val helper = Class.forName("com.multiapp.NativeLibLoader", true, guestCl)
                            helper.getDeclaredMethod("loadLibrary", String::class.java).apply {
                                isAccessible = true
                                invoke(null, "fockrt")
                            }
                        }
                    }
                } catch (_: Throwable) {}
            } else {
                diagnostics.add("StubApp.load() not found")
            }
        } catch (e: Throwable) {
            diagnostics.add("StubApp.load() failed: ${e.message}")
        }

        // ── Collect RegisterNatives evidence after StubApp.load() ──
        val evidenceList = mutableListOf<RegisterNativesEvidence>()
        try {
            val evidence = bridge.getStubAppRegisterNativesEvidence()
            if (evidence != null) {
                evidenceList.add(evidence)
                Log.d(TAG, "loadPackerLibrary: RegisterNatives evidence collected: " +
                    "class=${evidence.className} count=${evidence.methodCount} " +
                    "originalShellPath=${evidence.originalShellPath}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "loadPackerLibrary: evidence collection failed: ${e.message}")
        }

        return PackerLoadResult(jiaguLoaded, stubAppLoadSucceeded,
            loadedLibPaths = emptyList(), diagnostics = diagnostics,
            registerNativesEvidence = evidenceList)
    }

    override fun verifyRegisterNatives(guestCl: ClassLoader): Boolean {
        return try {
            val cls = resolveStubAppClass(guestCl) ?: return false
            cls.declaredMethods.any { it.name == "interface20" && it.parameterTypes.isEmpty() }
        } catch (_: Throwable) { false }
    }

    override fun installPostLoadHooks(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        val guestCl = context.guestClassLoader
        val bridge = context.bridge
        val hookEngine = context.hookEngine
        val decisions = NativeHookPolicyGate.decisionsForComponents(
            policy = context.nativeHookPolicy,
            components = mapOf(
                NativeHookCapability.LSPLANT_METHOD_HOOKS to "JiaguRuntime.installPostLoadHooks.lsplant",
                NativeHookCapability.BUSINESS_NATIVE_STUBS to "JiaguRuntime.installPostLoadHooks.businessNativeStubs",
                NativeHookCapability.METHOD_REPLACEMENT to "JiaguRuntime.installPostLoadHooks.methodReplacement",
                NativeHookCapability.NO_OP_PATCHES to "JiaguRuntime.installPostLoadHooks.noOpPatches"
            )
        )
        decisions.forEach { decision ->
            if (!decision.allowed) {
                Log.i(TAG, "installPostLoadHooks: policy gate ${decision.status} ${decision.evidence}")
            }
        }
        val lsplantDecision = decisions.first {
            it.evidence["capability"] == NativeHookCapability.LSPLANT_METHOD_HOOKS.name
        }
        val stubsDecision = decisions.first {
            it.evidence["capability"] == NativeHookCapability.BUSINESS_NATIVE_STUBS.name
        }

        if (!lsplantDecision.allowed) {
            return
        }

        // QQ Reader 专项：壳加载后 dump 解密后的库（包含 BSS 段）
        val isQqReader = context.originalPackageName == "com.qq.reader" ||
            context.cloneProfile == "QQ_READER_SPECIAL"
        if (isQqReader && loadResult.jiaguLoaded) {
            try {
                val dumpDirs = mutableListOf(java.io.File(context.dataDir ?: "/data/local/tmp", "dump"))
                clonePackageFromDataDir(context.dataDir)?.let { clonePkg ->
                    dumpDirs.add(java.io.File("/sdcard/Android/data/$clonePkg/files/multiapp-dump"))
                }
                dumpDirs.distinctBy { it.absolutePath }.forEach { dumpDir ->
                    dumpDir.mkdirs()
                    bridge.dumpLoadedLibraries(dumpDir, "libjiagu_vip.so")
                    val ranges = bridge.dumpJiaguRuntimeRanges(dumpDir)
                    Log.d(TAG, "installPostLoadHooks: dumped libjiagu_vip.so and $ranges runtime ranges to ${dumpDir.absolutePath}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "installPostLoadHooks: dump failed: ${e.message}")
            }
        }

        // 注册缺失的 native stubs
        if (stubsDecision.allowed) {
            try { bridge.registerAllMissingNativeMethods(guestCl) } catch (_: Throwable) {}
            if (isQqReader && loadResult.jiaguLoaded) {
                Log.w(TAG, "installPostLoadHooks: QQ Reader defers YWLogin business stubs until original interface11 <clinit> attempt")
            } else {
                try { bridge.registerBusinessStubs(guestCl) } catch (_: Throwable) {}
            }
            try { bridge.registerQrencryptStubs(guestCl) } catch (_: Throwable) {}
        } else {
            Log.i(TAG, "installPostLoadHooks: business native stubs policy gate ${stubsDecision.status} ${stubsDecision.evidence}")
        }

        // LSPlant 初始化
        hookEngine.initLsplant(guestCl)

        // QQ Reader's interface20 probes ClassLoader.loadClass during shell startup.
        // Keep the Java-layer packer bypass out of this profile so LSPlant loadClass
        // frames do not perturb the shell while the token map is being populated.
        if (isQqReader) {
            Log.w(TAG, "installPostLoadHooks: skipping AntiDetectionEngine Java packer bypass for QQ Reader")
        } else {
            try {
                AntiDetectionEngine(hookEngine, bridge).apply {
                    initialize()
                    enableAntiDetection("default", DetectionLevel.MODERATE)
                }
            } catch (_: Throwable) {}
        }
    }

    override fun installStubFallback(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        val fallbackDecision = NativeHookPolicyGate.evaluate(
            policy = context.nativeHookPolicy,
            capability = NativeHookCapability.BUSINESS_NATIVE_STUBS,
            component = "JiaguRuntime.installStubFallback"
        )
        if (!fallbackDecision.allowed) {
            Log.i(TAG, "installStubFallback: policy gate ${fallbackDecision.status} ${fallbackDecision.evidence}")
            return
        }
        val guestCl = context.guestClassLoader
        val bridge = NativeHookBridge.getInstance()
        val callerClass = resolveStubAppClass(guestCl) ?: return
        val targetClass = callerClass.name
        val isQqReader = context.originalPackageName == "com.qq.reader" ||
            context.cloneProfile == "QQ_READER_SPECIAL"
        val stubFallbackMode = getSystemProperty("debug.multiapp.stubapp.fallback", "0")

        if (isQqReader && loadResult.jiaguLoaded && stubFallbackMode == "0") {
            val report = bridge.getStubAppBindingReport()
            Log.w(TAG, "installStubFallback: before fallback: $report")
            if (!report.contains("interface11=bound") || !report.contains("interface20=bound")) {
                Log.w(TAG, "installStubFallback: original Jiagu StubApp registration incomplete; installing core bootstrap fallback")
                try { bridge.registerStubCoreBootstrapMethods(guestCl, targetClass) } catch (_: Throwable) {}
                try {
                    Log.w(TAG, "installStubFallback: after core bootstrap fallback: ${bridge.getStubAppBindingReport()}")
                } catch (_: Throwable) {}
            } else if (!report.contains("originalJiaguComplete=1")) {
                Log.w(TAG, "installStubFallback: StubApp natives are bound, but original Jiagu count>=10 completion was not observed")
            }
            val app = currentApplication()
            if (app != null) {
                val interface5Ok = bridge.callOriginalStubInterface5(guestCl, targetClass, app)
                Log.w(TAG, "installStubFallback: original interface5(app) before YWLogin <clinit> ok=$interface5Ok")
                try {
                    Class.forName("com.yuewen.ywlogin.login.YWLoginManager", true, guestCl)
                    val ywReport = bridge.getYwLoginBindingReport()
                    Log.d(TAG, "installStubFallback: YWLogin after <clinit>: $ywReport")
                    if (ywReport.contains("pwdLogin=bound")) {
                        Log.i(TAG, "installStubFallback: YWLogin native methods registered!")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "installStubFallback: YWLoginManager <clinit> failed: ${e.message}")
                }
                try { bridge.registerBusinessStubs(guestCl) } catch (_: Throwable) {}
            } else {
                Log.w(TAG, "installStubFallback: original interface5/YWLogin <clinit> delayed: current application is null")
            }
            return
        }

        if (loadResult.jiaguLoaded && stubFallbackMode.equals("core", ignoreCase = true)) {
            try { bridge.registerStubCoreBootstrapMethods(guestCl, targetClass) } catch (_: Throwable) {}
        } else if (!loadResult.jiaguLoaded || stubFallbackMode.startsWith("bootstrap")) {
            try { bridge.registerStubBootstrapMethods(guestCl, targetClass) } catch (_: Throwable) {}
        }
    }

    private fun resolveStubAppClass(guestCl: ClassLoader): Class<*>? {
        for (candidate in STUB_APP_CANDIDATES) {
            try { return Class.forName(candidate, false, guestCl) } catch (_: Throwable) {}
        }
        return null
    }

    private fun clonePackageFromDataDir(dataDir: String?): String? {
        if (dataDir.isNullOrBlank()) return null
        return dataDir.trimEnd('/').substringAfterLast('/').takeIf { it.contains('.') }
    }

    private fun currentApplication(): android.app.Application? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            activityThread?.javaClass?.getDeclaredMethod("getApplication")
                ?.apply { isAccessible = true }
                ?.invoke(activityThread) as? android.app.Application
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSystemProperty(name: String, defaultValue: String = "0"): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getDeclaredMethod("get", String::class.java, String::class.java)
                .invoke(null, name, defaultValue) as String
        } catch (_: Throwable) { defaultValue }
    }
}
