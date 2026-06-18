package com.multiapp.core.hook

import android.util.Log
import java.io.File

/**
 * JiaguRuntime — 360 加固壳（libjiagu_vip.so）运行时实现
 *
 * 从 LoaderFactory.preloadPackerLibViaGuestClassLoader() 和 native-hook.cpp
 * 迁移已验证的 QQ Reader 兼容代码，包括：
 * - libjiagu_vip.so 加载（dlopen + ART nativeLoad + StubApp.load()）
 * - RegisterNatives probe（JNI 函数表 hook）
 * - self-kill 修复（BL/BLR → NOP patch）
 * - /proc/self/maps 过滤（GOT hook on libc.so）
 * - FindClass hook（JNI_OnLoad 期间用 guest ClassLoader 查找壳类）
 */
class JiaguRuntime : PackerRuntime {

    companion object {
        private const val TAG = "PackerRuntime.Jiagu"

        /** 壳特征库名 */
        private const val JIAGU_LIB = "libjiagu_vip.so"

        /** StubApp 候选类名 */
        private val STUB_APP_CANDIDATES = arrayOf(
            "com.stub.StubApp",
            "com.qihoo.util.StubApp",
            "com.stub.StubApplication",
            "com.secneo.apkwrapper.ApplicationWrapper"
        )
    }

    override val name: String = "Jiagu360"

    // ──────────────────────────────────────────────────────────
    //  detect: 检测 libjiagu_vip.so 是否存在
    // ──────────────────────────────────────────────────────────

    override fun detect(originLibDir: File?, originApkPath: String?): Boolean {
        if (originLibDir == null || !originLibDir.isDirectory) return false
        val jiaguFile = File(originLibDir, JIAGU_LIB)
        if (jiaguFile.exists()) {
            Log.i(TAG, "detect: $JIAGU_LIB found at ${jiaguFile.absolutePath}")
            return true
        }
        return false
    }

    // ──────────────────────────────────────────────────────────
    //  prepareFiles: ShadowHook 初始化 + FindClass hook + GOT hook
    // ──────────────────────────────────────────────────────────

    override fun prepareFiles(context: PackerRuntimeContext): Boolean {
        val bridge = context.bridge

        // Step 0: 初始化 ShadowHook native hooks
        // 必须在 dlopen 之前！壳的 JNI_OnLoad 会读 /proc/self/maps 检测 hook 框架。
        Log.d(TAG, "prepareFiles: initializing native hooks (ShadowHook)")
        val hooksOk = bridge.initNativeHooks()
        Log.d(TAG, "prepareFiles: native hooks initialized: $hooksOk")

        // Step 1: 安装 FindClass hook
        // preloadNativeLibraries 内部会 dlopen + 手动调用 JNI_OnLoad。
        // hook 必须在此之前生效，否则 JNI_OnLoad 的 FindClass 用默认 boot namespace → 失败。
        Log.d(TAG, "prepareFiles: setting up FindClass hook for ${STUB_APP_CANDIDATES.joinToString()}")
        val hookReady = bridge.setupFindClassHook(context.guestClassLoader, STUB_APP_CANDIDATES)
        if (hookReady) {
            bridge.installFindClassHook()
            Log.d(TAG, "prepareFiles: FindClass hook installed")
        } else {
            Log.w(TAG, "prepareFiles: FindClass hook setup failed")
        }

        // Step 2: 完整性校验重定向
        // 壳的 JNI_OnLoad 会校验 APK 的 DEX 完整性，需要重定向到原始 APK
        val modifiedApkPath = context.originApkPath
        val originalApkPath = context.originalApkPath
        if (modifiedApkPath != null && originalApkPath != null) {
            bridge.setIntegrityRedirect(modifiedApkPath, originalApkPath)
            Log.d(TAG, "prepareFiles: integrity redirect: $modifiedApkPath -> $originalApkPath")
        }

        // Step 3: 全局 GOT hook — 对 libc.so 做 hook 过滤 /proc/self/maps
        bridge.gotHookLibrary("libc.so")
        Log.d(TAG, "prepareFiles: global GOT hook on libc.so installed")

        // Step 4: 预装壳相关库的 GOT hook
        arrayOf("libfockrt.so", "libfock.so").forEach { targetLib ->
            try {
                bridge.gotHookLibrary(targetLib)
                Log.d(TAG, "prepareFiles: GOT hook on $targetLib installed")
            } catch (e: Throwable) {
                Log.d(TAG, "prepareFiles: GOT hook on $targetLib failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        return true
    }

    // ──────────────────────────────────────────────────────────
    //  loadPackerLibrary: 完整的壳库加载流程
    // ──────────────────────────────────────────────────────────

    override fun loadPackerLibrary(context: PackerRuntimeContext): PackerLoadResult {
        val bridge = context.bridge
        val guestCl = context.guestClassLoader
        val diagnostics = mutableListOf<String>()
        val loadedLibPaths = mutableListOf<String>()
        var jiaguLoaded = false
        var stubAppLoadSucceeded = false

        // 查找 StubApp 类
        val callerClass = resolveStubAppClass(guestCl)
        if (callerClass == null) {
            diagnostics.add("StubApp not found in guest ClassLoader")
            Log.w(TAG, "loadPackerLibrary: StubApp not found in guest ClassLoader")
            return PackerLoadResult(false, false, emptyList(), diagnostics)
        }
        val targetClass = callerClass.name
        diagnostics.add("StubApp resolved: $targetClass")

        // ── 可选：显式 dlopen 加载（debug.multiapp.jiagu.explicit_load=1）──
        val originLibDir = context.originLibDir
        if (originLibDir != null) {
            val jiaguFile = File(originLibDir, JIAGU_LIB)
            val processName = currentProcessName()
            val explicitLoadRequested = isTruthyProperty("debug.multiapp.jiagu.explicit_load", false)
            val explicitLoadAllowed = explicitLoadRequested && !processName.contains(":")

            if (explicitLoadAllowed && jiaguFile.exists()) {
                Log.d(TAG, "loadPackerLibrary: explicit load enabled for $JIAGU_LIB")
                val dlopenOnlyOk = bridge.dlopenOnly(jiaguFile.absolutePath)
                diagnostics.add("explicit dlopenOnly: $dlopenOnlyOk")
                if (dlopenOnlyOk) {
                    bridge.gotHookLibrary(JIAGU_LIB)
                    loadedLibPaths.add(jiaguFile.absolutePath)
                }
                jiaguLoaded = bridge.loadLibraryForGuest(
                    jiaguFile.absolutePath, guestCl, callerClass
                )
                diagnostics.add("explicit nativeLoad guest: $jiaguLoaded")
            } else {
                val prehookDlopen = isTruthyProperty("debug.multiapp.jiagu.prehook_dlopen", false)
                if (prehookDlopen && jiaguFile.exists() && !processName.contains(":")) {
                    val dlopenOnlyOk = bridge.dlopenOnly(jiaguFile.absolutePath)
                    diagnostics.add("prehook dlopenOnly: $dlopenOnlyOk")
                    if (dlopenOnlyOk) {
                        bridge.gotHookLibrary(JIAGU_LIB)
                    }
                }
            }
        }

        // ── 调用 StubApp.load() ──
        // 如果 dlopen + FindClass hook 成功，JNI_OnLoad 已通过 RegisterNatives 注册了
        // native 方法（interface20 等）。StubApp.load() 调用 native 方法 → 壳解密 DEX。
        try {
            val loadMethod = callerClass.declaredMethods.firstOrNull { m ->
                m.name == "load" && m.parameterTypes.isEmpty() &&
                    java.lang.reflect.Modifier.isStatic(m.modifiers)
            }
            if (loadMethod != null) {
                loadMethod.isAccessible = true
                try {
                    loadMethod.invoke(null)
                    Log.d(TAG, "loadPackerLibrary: StubApp.load() invoked OK")
                    stubAppLoadSucceeded = true
                    jiaguLoaded = true
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val realCause = e.targetException ?: e.cause ?: e
                    diagnostics.add("StubApp.load() threw: ${realCause.javaClass.simpleName}: ${realCause.message}")
                    Log.w(TAG, "loadPackerLibrary: StubApp.load() threw: ${realCause.javaClass.simpleName}: ${realCause.message}")
                    var cause = realCause.cause
                    var depth = 0
                    while (cause != null && depth < 5) {
                        diagnostics.add("  cause[$depth]: ${cause.javaClass.simpleName}: ${cause.message}")
                        cause = cause.cause
                        depth++
                    }
                }

                // StubApp.load() 后补装 GOT hook
                arrayOf(JIAGU_LIB, "libfockrt.so", "libfock.so").forEach { targetLib ->
                    try {
                        bridge.gotHookLibrary(targetLib)
                    } catch (_: Throwable) {}
                }

                // 加载 libfockrt.so（如果存在）
                try {
                    if (originLibDir != null) {
                        val fockRtFile = File(originLibDir, "libfockrt.so")
                        if (fockRtFile.exists()) {
                            val helperClass = Class.forName(
                                "com.multiapp.NativeLibLoader", true, guestCl
                            )
                            val helperMethod = helperClass.getDeclaredMethod(
                                "loadLibrary", String::class.java
                            )
                            helperMethod.isAccessible = true
                            helperMethod.invoke(null, "fockrt")
                            loadedLibPaths.add(fockRtFile.absolutePath)
                            diagnostics.add("libfockrt.so loaded via NativeLibLoader")
                        }
                    }
                } catch (e: Throwable) {
                    diagnostics.add("libfockrt.so load failed: ${e.message}")
                }
            } else {
                diagnostics.add("StubApp.load() not found (no-arg static)")
                Log.d(TAG, "loadPackerLibrary: StubApp.load() not found")
            }
        } catch (e: Throwable) {
            diagnostics.add("StubApp.load() failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.d(TAG, "loadPackerLibrary: StubApp.load() failed: ${e.message}")
        }

        return PackerLoadResult(
            jiaguLoaded = jiaguLoaded,
            stubAppLoadSucceeded = stubAppLoadSucceeded,
            loadedLibPaths = loadedLibPaths,
            diagnostics = diagnostics
        )
    }

    // ──────────────────────────────────────────────────────────
    //  verifyRegisterNatives: 检查 StubApp native 方法是否已注册
    // ──────────────────────────────────────────────────────────

    override fun verifyRegisterNatives(guestCl: ClassLoader): Boolean {
        return try {
            val stubAppClass = resolveStubAppClass(guestCl) ?: return false
            // interface20 是壳的核心 native 方法，签名: ()Z
            val interface20 = stubAppClass.declaredMethods.firstOrNull { m ->
                m.name == "interface20" && m.parameterTypes.isEmpty()
            }
            val registered = interface20 != null
            Log.d(TAG, "verifyRegisterNatives: StubApp.interface20 registered=$registered")
            registered
        } catch (e: Throwable) {
            Log.w(TAG, "verifyRegisterNatives failed: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────
    //  installPostLoadHooks: 加载后的业务兼容 hook
    // ──────────────────────────────────────────────────────────

    override fun installPostLoadHooks(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        val guestCl = context.guestClassLoader
        val bridge = context.bridge
        val hookEngine = context.hookEngine

        // 批量注册所有已知 native 类的缺失方法
        Log.d(TAG, "installPostLoadHooks: registering all missing native methods")
        try {
            val allRegistered = bridge.registerAllMissingNativeMethods(guestCl)
            Log.d(TAG, "installPostLoadHooks: all missing native methods registered: $allRegistered")
        } catch (e: Throwable) {
            Log.w(TAG, "installPostLoadHooks: registerAllMissingNativeMethods failed: ${e.message}")
        }

        // 注册关键业务 stub（覆盖批量注册中的 null 返回 stub）
        try {
            val businessRegistered = bridge.registerBusinessStubs(guestCl)
            Log.d(TAG, "installPostLoadHooks: business stubs registered: $businessRegistered")
        } catch (e: Throwable) {
            Log.w(TAG, "installPostLoadHooks: registerBusinessStubs failed: ${e.message}")
        }

        // 注册 qrencrypt native stubs
        try {
            val qrencryptRegistered = bridge.registerQrencryptStubs(guestCl)
            Log.d(TAG, "installPostLoadHooks: qrencrypt stubs registered: $qrencryptRegistered")
        } catch (e: Throwable) {
            Log.w(TAG, "installPostLoadHooks: registerQrencryptStubs failed: ${e.message}")
        }

        // 初始化 LSPlant
        val lsplantOk = hookEngine.initLsplant(guestCl)
        Log.d(TAG, "installPostLoadHooks: LSPlant initialized: $lsplantOk")

        // AntiDetectionEngine 初始化
        try {
            val antiDetect = AntiDetectionEngine(hookEngine, bridge)
            antiDetect.initialize()
            antiDetect.enableAntiDetection("default", DetectionLevel.MODERATE)
            Log.d(TAG, "installPostLoadHooks: AntiDetectionEngine initialized (MODERATE)")
        } catch (e: Throwable) {
            Log.w(TAG, "installPostLoadHooks: AntiDetectionEngine init failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────
    //  installStubFallback: StubApp native 方法 stub fallback
    // ──────────────────────────────────────────────────────────

    override fun installStubFallback(context: PackerRuntimeContext, loadResult: PackerLoadResult) {
        val guestCl = context.guestClassLoader
        val bridge = NativeHookBridge.getInstance()
        val callerClass = resolveStubAppClass(guestCl) ?: return
        val targetClass = callerClass.name

        val stubFallbackMode = getSystemProperty("debug.multiapp.stubapp.fallback", "0")
        val isQqReader = context.originalPackageName == "com.qq.reader" ||
            context.cloneProfile == "QQ_READER_SPECIAL"

        if (isQqReader && loadResult.jiaguLoaded && stubFallbackMode == "0") {
            val report = bridge.getStubAppBindingReport()
            val hasOriginalCore = report.contains("interface11=bound") &&
                report.contains("interface20=bound")
            Log.d(TAG, "installStubFallback: QQ Reader StubApp native binding report: $report")
            if (hasOriginalCore) {
                Log.d(TAG, "installStubFallback: QQ Reader preserves original StubApp core natives")
                return
            }
            Log.w(TAG, "installStubFallback: QQ Reader original StubApp core natives missing; registering core fallback")
            try {
                bridge.registerStubCoreBootstrapMethods(guestCl, targetClass)
            } catch (e: Throwable) {
                Log.w(TAG, "installStubFallback: registerStubCoreBootstrapMethods failed: ${e.message}")
            }
            return
        }

        if (loadResult.jiaguLoaded && stubFallbackMode.equals("core", ignoreCase = true)) {
            Log.d(TAG, "installStubFallback: registering StubApp core bootstrap methods")
            try {
                bridge.registerStubCoreBootstrapMethods(guestCl, targetClass)
            } catch (e: Throwable) {
                Log.w(TAG, "installStubFallback: registerStubCoreBootstrapMethods failed: ${e.message}")
            }
        } else if (stubFallbackMode.equals("bootstrap", ignoreCase = true) ||
            stubFallbackMode.equals("bootstrap_only", ignoreCase = true) ||
            !loadResult.jiaguLoaded
        ) {
            Log.d(TAG, "installStubFallback: registering StubApp bootstrap methods only")
            try {
                bridge.registerStubBootstrapMethods(guestCl, targetClass)
            } catch (e: Throwable) {
                Log.w(TAG, "installStubFallback: registerStubBootstrapMethods failed: ${e.message}")
            }
        } else if (stubFallbackMode.equals("interface20", ignoreCase = true) ||
            stubFallbackMode.equals("interface20_only", ignoreCase = true)
        ) {
            Log.d(TAG, "installStubFallback: registering StubApp interface20 only")
            try {
                bridge.registerStubInterface20Only(guestCl, targetClass)
            } catch (e: Throwable) {
                Log.w(TAG, "installStubFallback: registerStubInterface20Only failed: ${e.message}")
            }
        } else if (isTruthyProperty("debug.multiapp.stubapp.fallback", false)) {
            Log.d(TAG, "installStubFallback: registering full stub methods")
            try {
                bridge.registerStubMethods(guestCl, targetClass)
            } catch (e: Throwable) {
                Log.w(TAG, "installStubFallback: registerStubMethods failed: ${e.message}")
            }
        } else {
            Log.d(TAG, "installStubFallback: StubApp native fallback disabled")
        }
    }

    // ──────────────────────────────────────────────────────────
    //  内部工具方法
    // ──────────────────────────────────────────────────────────

    private fun resolveStubAppClass(guestCl: ClassLoader): Class<*>? {
        for (candidate in STUB_APP_CANDIDATES) {
            try {
                return Class.forName(candidate, false, guestCl)
            } catch (_: Throwable) { /* try next */ }
        }
        return null
    }

    private fun currentProcessName(): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName() ?: ""
            } else {
                File("/proc/self/cmdline").readText().trimEnd('\u0000')
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isTruthyProperty(name: String, defaultValue: Boolean = false): Boolean {
        val value = getSystemProperty(name, if (defaultValue) "1" else "0")
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    private fun getSystemProperty(name: String, defaultValue: String = "0"): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            get.invoke(null, name, defaultValue) as String
        } catch (_: Throwable) {
            defaultValue
        }
    }
}
