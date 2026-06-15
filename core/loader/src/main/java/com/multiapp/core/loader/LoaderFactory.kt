package com.multiapp.core.loader

import android.app.AppComponentFactory
import android.app.Application
import android.content.ContentProvider
import android.content.ComponentName
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import android.view.LayoutInflater
import java.util.Vector
import com.multiapp.core.hook.NativeHookBridge
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Field
import java.util.zip.ZipFile

/**
 * 【最小 POC 骨架】LoaderFactory
 *
 * 只验证核心链路：Stub 启动 → 替换 ClassLoader → 原始 Application 创建成功
 *
 * 剥离所有非必要依赖：NativeHook、HookEngine、AntiDetection、IdentityHook、
 * SignatureBypass、ConfigEncryptor、StealthClassLoader 等。
 *
 * 日志用 android.util.Log 而非 Timber（避免依赖）。
 * 配置用简单正则提取（避免 Gson 依赖）。
 */
class LoaderFactory : AppComponentFactory() {

    companion object {
        private const val TAG = "MultiApp.POC"

        /**
         * 内存日志缓冲 — 无需 ADB 也能看到 LoaderFactory 内部发生了什么。
         * 通过 adb shell 或任何方式读取 "MultiApp.POC" tag 即可。
         * 同时写入文件 /data/data/<stubPkg>/cache/loader_debug.log
         */
        @JvmStatic
        val debugLog = mutableListOf<String>()

        private fun logD(msg: String) {
            val ts = System.currentTimeMillis()
            val line = "[$ts] D $msg"
            synchronized(debugLog) { debugLog.add(line) }
            Log.d(TAG, msg)
        }

        private fun logE(msg: String, t: Throwable? = null) {
            val ts = System.currentTimeMillis()
            val line = "[$ts] E $msg${t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""}"
            synchronized(debugLog) { debugLog.add(line) }
            Log.e(TAG, msg, t)
        }

        private fun logSignal(msg: String) {
            val line = "DIAG-SIGNAL $msg"
            logE(line)
            System.err.println("$TAG: $line")
        }

        private fun md5Hex(value: String): String {
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun logW(msg: String) {
            val ts = System.currentTimeMillis()
            val line = "[$ts] W $msg"
            synchronized(debugLog) { debugLog.add(line) }
            Log.w(TAG, msg)
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

        private fun isTruthyProperty(name: String, defaultValue: Boolean = false): Boolean {
            val value = getSystemProperty(name, if (defaultValue) "1" else "0")
            return value == "1" || value.equals("true", ignoreCase = true)
        }

        private fun currentProcessName(): String {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    Application.getProcessName() ?: ""
                } else {
                    File("/proc/self/cmdline").readText().trimEnd('\u0000')
                }
            } catch (_: Throwable) {
                ""
            }
        }
    }

    @Volatile
    private var classLoaderReady = false

    @Volatile
    private var guestClassLoader: ClassLoader? = null

    /**
     * 原始 APK 的 Application 类名（从 manifest 中解析）。
     * 当系统传入默认 "android.app.Application" 时，使用此值替代。
     */
    @Volatile
    private var originApplicationClass: String? = null

    /** 原始 APK 的 ApplicationInfo（用于 GuestContextWrapper） */
    @Volatile
    private var originAppInfo: android.content.pm.ApplicationInfo? = null

    /** 原始 APK 的 PackageInfo（用于 PackageManager 虚拟化返回真实版本信息） */
    @Volatile
    private var originPackageInfo: android.content.pm.PackageInfo? = null

    /** 原始 APK 路径 */
    @Volatile
    private var originApkPath: String? = null

    /** 完整资源 APK 路径。离线 patched APK 可能破坏 resources.arsc，资源优先走原始 APK。 */
    @Volatile
    private var originResourceApkPath: String? = null

    /** Origin APK resources used for resolving 0x7f theme IDs. */
    @Volatile
    private var originResources: Resources? = null

    /** Origin APK application theme ID. */
    @Volatile
    private var originApplicationThemeId: Int = 0

    /** Origin APK activity theme IDs keyed by activity class name. */
    private val originActivityThemes = mutableMapOf<String, Int>()

    /** 原始 APK 的 native lib 目录 */
    @Volatile
    private var originNativeLibDir: String? = null

    /** 原始 APK 的 meta-data Bundle */
    @Volatile
    private var originMetaData: android.os.Bundle? = null

    /** Origin APK provider metadata keyed by provider class name. */
    private val originProviderMetaData = mutableMapOf<String, android.os.Bundle>()

    /** 原始包名 */
    @Volatile
    private var guestPackageName: String? = null

    /** Stub package name currently running in this process. */
    @Volatile
    private var stubPackageName: String? = null

    @Volatile
    private var currentConfig: PocConfig? = null

    @Volatile
    private var packageManagerProxyInstalled = false

    @Volatile
    private var originSignatures: Array<android.content.pm.Signature>? = null

    @Volatile
    private var activityTaskManagerProxyInstalled = false

    @Volatile
    private var notificationManagerProxyInstalled = false

    private val initLock = Any()

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): android.app.Activity {
        logD("instantiateActivity: $className")
        ensureClassLoaderSwapped(cl)
        installActivityStartProxiesIfReady("instantiateActivity")
        val realCl = guestClassLoader ?: cl
        return try {
            val clazz = realCl.loadClass(className)
            val activity = clazz.getDeclaredConstructor().newInstance() as android.app.Activity
            applyActivityThemeIfKnown(activity, className)
            activity
        } catch (e: Exception) {
            logE("instantiateActivity FAILED for $className", e)
            throw e
        }
    }

    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider {
        logD("instantiateProvider: $className")
        logD("  classLoaderReady=$classLoaderReady, guestClassLoader=${guestClassLoader != null}")
        logD("  cl=${cl.javaClass.name}")
        ensureClassLoaderSwapped(cl)
        val realCl = guestClassLoader ?: cl
        logD("  loading from: ${realCl.javaClass.name}")

        // ★ 在第一个 Provider 初始化时执行延迟包装（此时 attachBaseContext 已完成）
        // instantiateApplication 时 mBase 为 null，无法包装
        // instantiateProvider 在 attachBaseContext 之后、onCreate 之前调用
        if (!contextWrapped) {
            tryWrapApplicationContextDeferred()
            // 注册 Activity Context 包装回调
            try {
                val at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread")
                    .apply { isAccessible = true }
                    .invoke(null)
                val app = at?.javaClass?.getDeclaredMethod("getApplication")
                    ?.apply { isAccessible = true }?.invoke(at) as? android.app.Application
                if (app != null) {
                    registerActivityContextWrapper(app)
                    logD("  Activity context wrapper registered from instantiateProvider")

                    // ★ 启用 PackageIdentityHook，解决 SecurityException: Caller cannot post for pkg
                    try {
                        val config = currentConfig
                        val stubPkg = config?.stubPkg ?: stubPackageName ?: app.applicationInfo.packageName
                        val originPkg = config?.originalPkg ?: guestPackageName ?: originAppInfo?.packageName ?: stubPkg
                        com.multiapp.core.identity.PackageIdentityHook.applyDirect(stubPkg, originPkg)
                        logD("  PackageIdentityHook installed: $stubPkg -> $originPkg")
                    } catch (e: Throwable) {
                        logW("  PackageIdentityHook failed: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                logW("  registerActivityContextWrapper from instantiateProvider failed: ${e.message}")
            }
        }

        return try {
            val clazz = realCl.loadClass(className)
            logD("  loaded: ${clazz.name}, creating instance...")
            val provider = clazz.getDeclaredConstructor().newInstance() as ContentProvider
            logD("  provider created OK: ${provider.javaClass.name}")
            // 包装 provider，在 onCreate 失败时优雅降级
            SafeProviderWrapper(provider, originProviderMetaData[className])
        } catch (e: Exception) {
            logE("instantiateProvider FAILED for $className, falling back to system", e)
            try {
                val fallback = super.instantiateProvider(cl, className)
                logD("  fallback OK: ${fallback.javaClass.name}")
                SafeProviderWrapper(fallback, originProviderMetaData[className])
            } catch (e2: Exception) {
                logE("instantiateProvider FALLBACK also failed", e2)
                throw e2
            }
        }
    }

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        logD("instantiateApplication: $className")
        logD("  classLoaderReady=$classLoaderReady, guestClassLoader=${guestClassLoader != null}")
        logD("  cl=${cl.javaClass.name}")
        ensureClassLoaderSwapped(cl)
        val realCl = guestClassLoader ?: cl
        logD("  loading Application from: ${realCl.javaClass.name}")

        // ★ 设置全局异常处理器，catch 通知相关的 SecurityException
        // 分身包名和原始包名不同，系统不允许为其他包发送/取消通知
        // 异常可能被 RuntimeException 包装，需要检查 cause 链
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 检查 throwable 本身和 cause 链中是否有 SecurityException
            var cause: Throwable? = throwable
            while (cause != null) {
                if (cause is SecurityException &&
                    cause.message?.contains("cannot post for pkg") == true
                ) {
                    android.util.Log.w("MultiApp.UncaughtHandler",
                        "Suppressed notification SecurityException on ${thread.name}", throwable)
                    return@setDefaultUncaughtExceptionHandler
                }
                cause = cause.cause
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        logD("  Global UncaughtExceptionHandler installed (notification SecurityException only)")

        // 如果系统传入的是默认 "android.app.Application"，尝试使用原始 APK 的 Application class
        val effectiveClassName = if (className == "android.app.Application" && originApplicationClass != null) {
            logD("  Overriding default Application class -> $originApplicationClass")
            originApplicationClass!!
        } else {
            className
        }

        return try {
            val appClass = realCl.loadClass(effectiveClassName)
            logD("  loaded: ${appClass.name}, creating instance...")
            val app = appClass.getDeclaredConstructor().newInstance() as Application
            logD("  Application created OK: ${app.javaClass.name}")

            app
        } catch (e: Exception) {
            logE("FATAL: cannot create Application $effectiveClassName (original: $className)", e)
            // 如果使用原始 Application class 失败，回退到默认
            if (effectiveClassName != className) {
                logW("  Falling back to default Application class: $className")
                val fallbackClass = realCl.loadClass(className)
                val fallbackApp = fallbackClass.getDeclaredConstructor().newInstance() as Application
                logD("  Fallback Application created OK: ${fallbackApp.javaClass.name}")
                fallbackApp
            } else {
                try { writeDebugLogToFile(cl) } catch (_: Exception) {}
                throw RuntimeException("LoaderFactory POC failed: ${e.message}", e)
            }
        }
    }

    private fun ensureClassLoaderSwapped(cl: ClassLoader) {
        if (classLoaderReady) return
        synchronized(initLock) {
            if (classLoaderReady) return
            try {
                initializeInternal(cl)
                classLoaderReady = true
                logD("ClassLoader swap complete!")
            } catch (e: Exception) {
                logE("Initialization failed", e)
                throw RuntimeException("LoaderFactory POC failed: ${e.message}", e)
            }
        }
    }

    private fun initializeInternal(cl: ClassLoader) {
        logSignal("LoaderFactory.initializeInternal entered")
        logD("=== POC LoaderFactory starting ===")
        logD("  Thread: ${Thread.currentThread().name}")
        logD("  ClassLoader: ${cl.javaClass.name}")
        logD("  ClassLoader parent: ${cl.parent?.javaClass?.name}")

        // 保底：安装 UncaughtExceptionHandler，确保崩溃前写日志到文件
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logSignal("UncaughtException thread=${thread.name} type=${throwable.javaClass.name} msg=${throwable.message}")
            logE("UncaughtException on ${thread.name}", throwable)

            // ★ 诊断：Resources$NotFoundException 时 dump 资源状态
            // 检查异常链（顶层是 RuntimeException，cause 才是 NotFoundException）
            var isResourceError = throwable is android.content.res.Resources.NotFoundException
            if (!isResourceError) {
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    if (cause is android.content.res.Resources.NotFoundException) {
                        isResourceError = true
                        break
                    }
                    cause = cause.cause
                    depth++
                }
            }
            if (isResourceError) {
                try {
                    // 1. 提取异常中的 resource ID
                    logE("DIAG-RESOURCE: === CRASH RESOURCE DIAGNOSTIC ===")
                    logE("DIAG-RESOURCE: exception message: ${throwable.message}")

                    // 2. 获取 Application 的 Resources
                    val at = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                    val app = at?.javaClass?.getDeclaredMethod("getApplication")?.apply { isAccessible = true }?.invoke(at)
                    if (app != null) {
                        val ctx = app as android.content.Context
                        val res = ctx.resources
                        logE("DIAG-RESOURCE: app.resources class=${res.javaClass.name}")
                        logE("DIAG-RESOURCE: app.packageName=${ctx.packageName}")
                        logE("DIAG-RESOURCE: originPackageName=$guestPackageName")

                        // 3. 用 origin 包名查资源
                        val originPkg = guestPackageName ?: ctx.packageName
                        val testId = res.getIdentifier("app_name", "string", originPkg)
                        logE("DIAG-RESOURCE: getIdentifier(app_name, string, $originPkg) = 0x${Integer.toHexString(testId)}")

                        // 4. 检查 originResources
                        logE("DIAG-RESOURCE: originResources=${originResources != null}")
                        if (originResources != null) {
                            val testId2 = originResources!!.getIdentifier("app_name", "string", originPkg)
                            logE("DIAG-RESOURCE: originResources.getIdentifier(app_name) = 0x${Integer.toHexString(testId2)}")
                        }
                    }

                    // 5. dump ApplicationInfo
                    val loadedApkField = at?.javaClass?.getDeclaredField("mBoundApplication")
                    loadedApkField?.isAccessible = true
                    val data = loadedApkField?.get(at)
                    val appInfoField = data?.javaClass?.getDeclaredField("appInfo")
                    appInfoField?.isAccessible = true
                    val ai = appInfoField?.get(data) as? android.content.pm.ApplicationInfo
                    logE("DIAG-RESOURCE: appInfo.sourceDir=${ai?.sourceDir}")
                    logE("DIAG-RESOURCE: appInfo.theme=0x${Integer.toHexString(ai?.theme ?: 0)}")

                    logE("DIAG-RESOURCE: === END DIAGNOSTIC ===")
                } catch (diagEx: Throwable) {
                    logE("DIAG-RESOURCE failed: ${diagEx.message}")
                }
            }

            try { writeDebugLogToFile(cl) } catch (_: Exception) {}
            dumpDebugLogToLogcat("uncaught")
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            // Step 0: Hidden API bypass — 必须在任何反射调用之前
            logD("Step 0: Hidden API bypass...")
            bypassHiddenApis()

            // Step 0.5: NativeHookBridge 初始化 — 必须在 identity hooks 之前
            // 安装 open/fopen/readlink 等 libc hook，过滤 /proc/self/maps 中的 multiapp/shadowhook
            logD("Step 0.5: NativeHookBridge early init...")
            try {
                val hooksOk = NativeHookBridge.getInstance().initNativeHooks()
                logD("  NativeHookBridge.initNativeHooks: $hooksOk")
            } catch (e: Throwable) {
                logW("  NativeHookBridge.initNativeHooks failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // 1. 获取 ActivityThread
            logD("Step 1: Getting ActivityThread...")
            val activityThread = try {
                Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread")
                    .apply { isAccessible = true }
                    .invoke(null)
            } catch (e: Exception) {
                logE("Step 1 FAILED: ActivityThread.currentActivityThread()", e)
                throw e
            }
            if (activityThread == null) {
                logE("FATAL: ActivityThread.currentActivityThread() returned null!")
                throw IllegalStateException("ActivityThread is null")
            }
            logD("  ActivityThread: ${activityThread.javaClass.name}")

            // 2. 获取 ApplicationInfo
            logD("Step 2: Getting ApplicationInfo via mBoundApplication...")
            val mBoundField = try {
                activityThread.javaClass.getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
            } catch (e: Exception) {
                logE("Step 2 FAILED: mBoundApplication field access", e)
                throw e
            }
            val mBound = try {
                mBoundField.get(activityThread)
            } catch (e: Exception) {
                logE("Step 2 FAILED: mBoundApplication get()", e)
                throw e
            }
            if (mBound == null) {
                logE("FATAL: mBoundApplication is null!")
                throw IllegalStateException("mBoundApplication is null")
            }
            logD("  mBoundApplication: ${mBound.javaClass.name}")

            val appInfo = try {
                val appInfoField = mBound.javaClass
                    .getDeclaredField("appInfo")
                    .apply { isAccessible = true }
                appInfoField.get(mBound) as? android.content.pm.ApplicationInfo
            } catch (e: Exception) {
                logE("Step 2 FAILED: appInfo field access", e)
                throw e
            }
            if (appInfo == null) {
                logE("FATAL: appInfo is null!")
                throw IllegalStateException("appInfo is null")
            }
            // Use a real framework icon. IconCompat.createWithResource crashes
            // when callers receive 0 or a guest-only resource id under stub resources.
            appInfo.icon = android.R.drawable.sym_def_app_icon
            val stubApkPath = appInfo.sourceDir
            val dataDir = appInfo.dataDir
            logD("  Stub APK: $stubApkPath")
            logD("  dataDir: $dataDir")
            logD("  packageName: ${appInfo.packageName}")
            logD("  appComponentFactory: ${appInfo.appComponentFactory}")
            logD("  className: ${appInfo.className}")

            // 3. 从 Stub APK assets 读取配置
            logD("Step 3: Reading config...")
            val config = readConfig(stubApkPath)
            currentConfig = config
            guestPackageName = config.originalPkg
            stubPackageName = config.stubPkg
            logD("  originalPkg=${config.originalPkg}, stubPkg=${config.stubPkg}")
            installActivityStartProxiesIfReady("after-readConfig", activityThread)
            installNotificationManagerPackageProxy(config)

            // 4. 解压 origin.apk
            logD("Step 4: Extracting origin.apk...")
            val originApk = extractOriginApk(stubApkPath, dataDir)
            logD("  Origin APK: ${originApk.absolutePath}, size=${originApk.length()}")
            if (!originApk.exists()) {
                logE("FATAL: origin.apk does not exist after extraction!")
                throw IllegalStateException("origin.apk missing")
            }

            // 4.5 解压原始 APK（未修改，用于完整性校验重定向）
            val originalApk = extractOriginalApk(stubApkPath, dataDir)
            if (originalApk != null) {
                logD("  Original APK: ${originalApk.absolutePath}, size=${originalApk.length()}")
            }

            // 5. 安装 nativeLoad hook，确保加固壳的 JNI_OnLoad/RegisterNatives 能完整执行
            logSignal("before native hook install")
            logD("Step 5: Installing nativeLoad hook...")
            // 标记 native 库已加载（libmultiapp-native.so 在 stub APK 的 lib/ 中，
            // 被 stub ClassLoader 加载，但 NativeHookBridge 的 init 块用 boot ClassLoader 检测不到）
            NativeHookBridge.markNativeLibLoaded()
            installNativeLoadHookIfAvailable()
            logSignal("after native hook install")

            // 6. 替换 ClassLoader
            logD("Step 6: Swapping ClassLoader...")
            swapClassLoader(activityThread, appInfo, originApk, config, originalApk)
            logSignal("after swapClassLoader")
            logD("=== POC LoaderFactory complete ===")

        } catch (e: Exception) {
            logSignal("initializeInternal failed ${e.javaClass.name}: ${e.message}")
            logE("=== POC LoaderFactory FAILED ===", e)
            try { writeDebugLogToFile(cl) } catch (_: Exception) {}
            dumpDebugLogToLogcat("init-failed")
            throw e
        }
    }

    private fun installNativeLoadHookIfAvailable() {
        try {
            logSignal("installNativeLoadHookIfAvailable entered")
            val candidates = arrayOf(
                "com.stub.StubApp",
                "com.qihoo.util.StubApp",
                "com.stub.StubApplication",
                originApplicationClass
            ).filterNotNull().distinct().toTypedArray()
            val installed = NativeHookBridge.getInstance().hookRuntimeNativeLoad(candidates)
            logD("  Runtime.nativeLoad hook installed=$installed, fallbackCallers=${candidates.joinToString()}")
            logSignal("Runtime.nativeLoad hook installed=$installed")
            val registerLoggerInstalled = NativeHookBridge.getInstance().installRegisterNativesLogger()
            logD("  RegisterNatives logger installed=$registerLoggerInstalled")
            logSignal("RegisterNatives logger installed=$registerLoggerInstalled")
        } catch (e: Throwable) {
            logSignal("installNativeLoadHookIfAvailable failed ${e.javaClass.name}: ${e.message}")
            logW("  Runtime.nativeLoad hook unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Volatile
    private var intentRemappingInstrumentationInstalled = false

    private fun installActivityStartProxiesIfReady(label: String, activityThread: Any? = null) {
        val config = currentConfig
        if (config == null) {
            logW("  Activity start proxy install skipped[$label]: config is null")
            return
        }
        val at = activityThread ?: try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
        } catch (e: Throwable) {
            logW("  Activity start proxy install skipped[$label]: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        logSignal("installActivityStartProxies[$label] begin")
        if (at != null) {
            installIntentRemappingInstrumentation(at, config)
        }
        installActivityTaskManagerIntentProxy(config)
        logSignal("installActivityStartProxies[$label] end")
    }

    private fun installIntentRemappingInstrumentation(activityThread: Any, config: PocConfig) {
        if (intentRemappingInstrumentationInstalled) return
        try {
            val field = activityThread.javaClass
                .getDeclaredField("mInstrumentation")
                .apply { isAccessible = true }
            val current = field.get(activityThread) as? android.app.Instrumentation
            if (current == null) {
                logW("  Intent remap skipped: ActivityThread.mInstrumentation is null")
                return
            }
            if (current is IntentRemappingInstrumentation) {
                intentRemappingInstrumentationInstalled = true
                logD("  Intent remap instrumentation already installed")
                return
            }
            field.set(
                activityThread,
                IntentRemappingInstrumentation(
                    base = current,
                    originalPackageName = config.originalPkg,
                    stubPackageName = config.stubPkg,
                    beforeActivityLifecycle = { activity, reason ->
                        val pkg = guestPackageName ?: config.originalPkg
                        val apkPath = originApkPath
                        if (apkPath != null) {
                            syncActivityResourceContext(activity, pkg, apkPath, reason)
                        }
                    },
                    afterActivityLifecycle = { activity, reason ->
                        restoreActivityBaseContextAfterLifecycle(activity, reason)
                    }
                )
            )
            intentRemappingInstrumentationInstalled = true
            logD("  Intent remap instrumentation installed: ${config.originalPkg} -> ${config.stubPkg}")
        } catch (e: Throwable) {
            logW("  Intent remap instrumentation install failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun installActivityTaskManagerIntentProxy(config: PocConfig) {
        if (activityTaskManagerProxyInstalled) return
        var installed = false
        installed = installActivityManagerSingletonProxy(
            ownerClassName = "android.app.ActivityTaskManager",
            singletonFieldName = "IActivityTaskManagerSingleton",
            interfaceClassName = "android.app.IActivityTaskManager",
            config = config
        ) || installed

        // Older platform fallback. Harmless when the singleton/interface is absent.
        installed = installActivityManagerSingletonProxy(
            ownerClassName = "android.app.ActivityManager",
            singletonFieldName = "IActivityManagerSingleton",
            interfaceClassName = "android.app.IActivityManager",
            config = config
        ) || installed

        activityTaskManagerProxyInstalled = installed
        if (installed) {
            logD("  Activity start intent proxy installed: ${config.originalPkg} -> ${config.stubPkg}")
        } else {
            logW("  Activity start intent proxy not installed")
        }
    }

    private fun installActivityManagerSingletonProxy(
        ownerClassName: String,
        singletonFieldName: String,
        interfaceClassName: String,
        config: PocConfig
    ): Boolean {
        return try {
            val ownerClass = Class.forName(ownerClassName)
            val singletonField = ownerClass.getDeclaredField(singletonFieldName).apply {
                isAccessible = true
            }
            val singleton = singletonField.get(null) ?: return false
            val singletonClass = Class.forName("android.util.Singleton")
            val instanceField = singletonClass.getDeclaredField("mInstance").apply {
                isAccessible = true
            }
            var base = instanceField.get(singleton)
            if (base == null) {
                base = singleton.javaClass.getDeclaredMethod("get").apply {
                    isAccessible = true
                }.invoke(singleton)
            }
            if (base == null) {
                logW("  $ownerClassName.$singletonFieldName proxy skipped: base is null")
                return false
            }

            val iface = Class.forName(interfaceClassName)
            if (java.lang.reflect.Proxy.isProxyClass(base.javaClass)) {
                logD("  $interfaceClassName already proxied")
                return true
            }

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface)
            ) { _, method, args ->
                if (args != null && method.name.startsWith("startActiv")) {
                    remapStartActivityArgs(method.name, args, config)
                }
                try {
                    method.invoke(base, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
            instanceField.set(singleton, proxy)
            logD("  Installed $interfaceClassName proxy via $ownerClassName.$singletonFieldName")
            true
        } catch (e: Throwable) {
            logW("  $ownerClassName.$singletonFieldName proxy failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun installNotificationManagerPackageProxy(config: PocConfig) {
        if (notificationManagerProxyInstalled) return
        try {
            val notificationManagerClass = Class.forName("android.app.NotificationManager")
            val serviceField = notificationManagerClass.getDeclaredField("sService").apply {
                isAccessible = true
            }
            var base = serviceField.get(null)
            if (base == null) {
                base = notificationManagerClass.getDeclaredMethod("getService").apply {
                    isAccessible = true
                }.invoke(null)
            }
            if (base == null) {
                logW("  NotificationManager package proxy skipped: service is null")
                return
            }
            if (java.lang.reflect.Proxy.isProxyClass(base.javaClass)) {
                notificationManagerProxyInstalled = true
                logD("  NotificationManager package proxy already installed")
                return
            }

            val iface = Class.forName("android.app.INotificationManager")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface)
            ) { _, method, args ->
                val patchedArgs = args?.let { remapNotificationPackageArgs(method.name, it, config) }
                try {
                    method.invoke(base, *(patchedArgs ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            }
            serviceField.set(null, proxy)
            notificationManagerProxyInstalled = true
            logD("  NotificationManager package proxy installed: ${config.originalPkg} -> ${config.stubPkg}")
        } catch (e: Throwable) {
            logW("  NotificationManager package proxy failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun remapNotificationPackageArgs(
        methodName: String,
        args: Array<Any?>,
        config: PocConfig
    ): Array<Any?> {
        var changed = false
        val patched = args.copyOf()
        for (index in patched.indices) {
            if (patched[index] == config.originalPkg) {
                patched[index] = config.stubPkg
                changed = true
            }
        }
        if (changed) {
            logD("  NotificationManager.$methodName package remap: ${config.originalPkg} -> ${config.stubPkg}")
        }
        return patched
    }

    private fun remapStartActivityArgs(methodName: String, args: Array<Any?>, config: PocConfig) {
        for (index in args.indices) {
            val arg = args[index]
            when (arg) {
                is Intent -> {
                    val remapped = remapActivityIntent(arg, config)
                    if (remapped !== arg) {
                        args[index] = remapped
                        logD("  ActivityTaskManager.$methodName remapped Intent argument #$index")
                    }
                }
                is Array<*> -> {
                    if (arg.any { it is Intent }) {
                        @Suppress("UNCHECKED_CAST")
                        val intents = arg as Array<Any?>
                        var changed = false
                        for (intentIndex in intents.indices) {
                            val intent = intents[intentIndex] as? Intent ?: continue
                            val remapped = remapActivityIntent(intent, config)
                            if (remapped !== intent) {
                                intents[intentIndex] = remapped
                                changed = true
                            }
                        }
                        if (changed) {
                            logD("  ActivityTaskManager.$methodName remapped Intent[] argument #$index")
                        }
                    }
                }
            }
        }
    }

    private fun remapActivityIntent(intent: Intent, config: PocConfig): Intent {
        var changed = false
        val copy = Intent(intent)

        fun rewriteOne(target: Intent) {
            val component = target.component
            if (component?.packageName == config.originalPkg) {
                val rewritten = ComponentName(config.stubPkg, component.className)
                target.component = rewritten
                changed = true
                logD("  Activity intent component remap: $component -> $rewritten")
            }
            if (target.`package` == config.originalPkg) {
                target.setPackage(config.stubPkg)
                changed = true
                logD("  Activity intent package remap: ${config.originalPkg} -> ${config.stubPkg}")
            }
        }

        rewriteOne(copy)
        copy.selector?.let { selector ->
            val selectorCopy = Intent(selector)
            rewriteOne(selectorCopy)
            if (selectorCopy != selector) {
                copy.selector = selectorCopy
            }
        }
        return if (changed) copy else intent
    }

    /**
     * 核心方法：直接修改现有 LoadedApk 的字段
     *
     * 策略：不调 getPackageInfoNoCheck（Android 16 CorePlatformApi），
     * 不调 setHiddenApiExemptions（同样被拦截），
     * 直接修改现有 LoadedApk 的 mClassLoader 和路径字段。
     */
    private fun swapClassLoader(
        activityThread: Any,
        appInfo: android.content.pm.ApplicationInfo,
        originApk: File,
        config: PocConfig,
        originalApk: File? = null
    ) {
        val resourceApk = originalApk ?: originApk
        logD("swapClassLoader: originApk=${originApk.absolutePath}")
        logD("swapClassLoader: resourceApk=${resourceApk.absolutePath}")

        // 从 origin APK 的 manifest 解析原始 Application class name 和 meta-data
        try {
            val at = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            val getSystemContext = at.javaClass.getDeclaredMethod("getSystemContext")
            getSystemContext.isAccessible = true
            val systemContext = getSystemContext.invoke(at) as android.content.Context
            val pm = systemContext.packageManager
            @Suppress("DEPRECATION")
            val originInfo = pm.getPackageArchiveInfo(resourceApk.absolutePath,
                android.content.pm.PackageManager.GET_META_DATA or
                    android.content.pm.PackageManager.GET_ACTIVITIES or
                    android.content.pm.PackageManager.GET_PROVIDERS)
            if (originInfo != null) {
                originPackageInfo = originInfo
                logD(
                    "  Origin APK packageInfo: package=${originInfo.packageName}, " +
                        "versionName=${originInfo.versionName}, versionCode=${originInfo.versionCode}, " +
                        "longVersionCode=${originInfo.longVersionCode}"
                )
                val originAI = originInfo.applicationInfo
                val originClassName = originAI?.className as? String
                logD("  Origin APK applicationInfo.className: $originClassName")
                if (!originClassName.isNullOrEmpty()) {
                    originApplicationClass = originClassName
                    logD("  Stored origin Application class: $originClassName")
                }
                // 提取 meta-data（GMS/Firebase 等 SDK 需要）
                originMetaData = originAI?.metaData
                logD("  Origin APK metaData keys: ${originMetaData?.keySet()?.toList()}")
                originAppInfo = originAI
                originApplicationThemeId = originAI?.theme ?: 0
                originActivityThemes.clear()
                originProviderMetaData.clear()
                originInfo.activities?.forEach { activityInfo ->
                    originActivityThemes[activityInfo.name] = activityInfo.theme
                }
                originInfo.providers?.forEach { providerInfo ->
                    val metaData = providerInfo.metaData
                    if (providerInfo.name != null && metaData != null && !metaData.isEmpty) {
                        originProviderMetaData[providerInfo.name] = android.os.Bundle(metaData)
                    }
                }
                logD(
                    "  Origin themes: app=0x${Integer.toHexString(originApplicationThemeId)}, " +
                        "activities=${originActivityThemes.filterValues { it != 0 }.mapValues { "0x${Integer.toHexString(it.value)}" }}"
                )
                logD("  Origin provider metaData entries: ${originProviderMetaData.keys}")
            }
        } catch (e: Exception) {
            logW("  Failed to parse origin APK manifest: ${e.message}")
        }

        // 保存 stub APK 路径和系统安装后的 native lib 路径（替换 sourceDir 前）
        val stubApkPath = appInfo.sourceDir
        val stubNativeLibraryDir = appInfo.nativeLibraryDir
        val stubSecondaryNativeLibraryDir = try {
            appInfo.javaClass
                .getDeclaredField("secondaryNativeLibraryDir")
                .apply { isAccessible = true }
                .get(appInfo) as? String
        } catch (_: Exception) {
            null
        }
        logD("  Stub nativeLibraryDir: $stubNativeLibraryDir")
        logD("  Stub secondaryNativeLibraryDir: $stubSecondaryNativeLibraryDir")

        // 获取 LoadedApk 对象
        val mBound = activityThread.javaClass
            .getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
            .get(activityThread)

        val loadedApk = mBound.javaClass
            .getDeclaredField("info")
            .apply { isAccessible = true }
            .get(mBound)
        if (loadedApk == null) {
            logE("FATAL: LoadedApk (mBound.info) is null!")
            throw IllegalStateException("LoadedApk is null")
        }
        logD("  LoadedApk: ${loadedApk.javaClass.name}")

        // 修改 ApplicationInfo 路径和 Application 类名
        // 注意：不改 appInfo.packageName！保持 stub 包名，避免 AppOpsManager.checkPackage(uid, pkg) 失败
        // 包名伪装由 GuestContextWrapper 在应用层处理
        appInfo.sourceDir = originApk.absolutePath
        appInfo.publicSourceDir = resourceApk.absolutePath
        applyOriginApplicationInfoFields(appInfo, originApk)
        appInfo.sourceDir = originApk.absolutePath
        appInfo.publicSourceDir = resourceApk.absolutePath

        // Origin icon ids belong to the guest resource table. Keep a framework
        // icon here; IconCompat.createWithResource() crashes on 0 or guest-only
        // resource ids when the current process still resolves stub resources.
        appInfo.icon = android.R.drawable.sym_def_app_icon

        // 添加 native 文件 I/O 重定向：jiagu shell 通过 /proc/self/maps 或硬编码路径找 APK，
        // 会尝试打开 stub APK 路径。重定向到 origin APK 让壳能找到正确的加密 DEX。
        try {
            val bridge = NativeHookBridge.getInstance()
            if (stubApkPath != originApk.absolutePath) {
                bridge.addPathRedirection(stubApkPath, originApk.absolutePath)
                logD("  APK path redirect: $stubApkPath -> ${originApk.absolutePath}")
            }
        } catch (e: Throwable) {
            logD("  APK path redirect failed: ${e.message}")
        }
        // 设置 ApplicationInfo.name（android:name 属性）— 原始 app 代码会读取此字段
        if (originApplicationClass != null) {
            appInfo.name = originApplicationClass
            logD("  Updated appInfo.name -> $originApplicationClass")
        }
        logD("  Updated sourceDir -> ${appInfo.sourceDir}")
        logD("  Kept packageName as stub: ${appInfo.packageName}")

        val originLibDir = extractOriginNativeLibs(originApk)
        val nativeSearchPaths = mutableListOf<String>()
        if (originLibDir != null && originLibDir.isDirectory) {
            nativeSearchPaths += originLibDir.absolutePath
            logD("  Found extracted origin lib dir: ${originLibDir.absolutePath}")
        } else {
            logD("  No extracted origin lib dir available")
        }
        listOfNotNull(stubNativeLibraryDir, stubSecondaryNativeLibraryDir)
            .filter { it.isNotBlank() }
            .forEach { nativeSearchPaths += it }
        currentProcessSupportedAbis()
            .filter { it.isNotBlank() }
            .forEach { abi -> nativeSearchPaths += "$stubApkPath!/lib/$abi" }

        val nativeLibraryPath = nativeSearchPaths
            .distinct()
            .joinToString(File.pathSeparator)
        if (nativeLibraryPath.isNotBlank()) {
            val exposedNativeLibDir = originLibDir?.absolutePath ?: nativeSearchPaths.first()
            appInfo.nativeLibraryDir = exposedNativeLibDir
            originNativeLibDir = exposedNativeLibDir
            logD("  Updated nativeLibraryDir -> ${appInfo.nativeLibraryDir}")
            logD("  Native library search path -> $nativeLibraryPath")
        } else {
            logW("  Native library search path is empty")
        }

        // 保存成员变量供 GuestContextWrapper 使用
        originApkPath = originApk.absolutePath
        originResourceApkPath = resourceApk.absolutePath
        guestPackageName = config.originalPkg
        stubPackageName = config.stubPkg
        currentConfig = config
        logSignal("installing identity and activity start proxies")
        installApplicationInfoPackageManagerProxy(originApk, config)
        installActivityStartProxiesIfReady("swapClassLoader", activityThread)
        installNotificationManagerPackageProxy(config)
        logSignal("installed identity and activity start proxies")

        // 创建指向原始 APK 的 PathClassLoader
        // 使用原始 ClassLoader 的 parent 保留系统设置的中间 ClassLoader 层级
        val parentClassLoader = try {
            loadedApk.javaClass.getDeclaredField("mClassLoader")
                .apply { isAccessible = true }
                .get(loadedApk) as? ClassLoader
        } catch (_: Exception) { null }
            ?.parent ?: ClassLoader.getSystemClassLoader().parent
        logD("  parentClassLoader: ${parentClassLoader.javaClass.name}")

        // 提取 APK 中的 multidex DEX 文件（classes2.dex ~ classes13.dex）
        // 360 加固的壳不会自动加载这些 DEX，需要我们手动提取并加入 ClassLoader
        val extractedDexDir = java.io.File(appInfo.dataDir, "extracted_dex")
        val additionalDexFiles = extractAdditionalDex(originApk, extractedDexDir)
        logD("  Extracted ${additionalDexFiles.size} additional DEX files")

        // 构建 dex 路径：origin APK + 所有提取的 DEX 文件
        val dexPath = buildString {
            append(originApk.absolutePath)
            for (dex in additionalDexFiles) {
                append(File.pathSeparator)
                append(dex.absolutePath)
            }
        }
        logD("  DexPath entries: ${1 + additionalDexFiles.size}")

        val realGuestClassLoader = PathClassLoader(
            dexPath,
            nativeLibraryPath,
            parentClassLoader
        )
        val newClassLoader = StealthClassLoader(realGuestClassLoader, originApk.absolutePath)
        logD("  Created PathClassLoader: ${realGuestClassLoader.javaClass.name}")
        logD("  Exposed StealthClassLoader: ${newClassLoader.javaClass.name}")
        logD("  Path: ${originApk.absolutePath}")

        // 替换 LoadedApk.mClassLoader
        loadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .set(loadedApk, newClassLoader)
        logD("  Replaced LoadedApk.mClassLoader")

        // 先通过 guest ClassLoader 预加载加固壳 native 库，便于后续 JNI 注册和诊断。
        // 如果壳自己再次调用 System.loadLibrary，同一个命名空间内可能会复用已有句柄。
        logD("  Preloading packer native libs via guest ClassLoader")
        preloadPackerLibViaGuestClassLoader(realGuestClassLoader, originalApk?.absolutePath, appInfo.dataDir)

        // Stage 2: after the packer bootstrap, load business SDK libraries
        // through ART nativeLoad with guest ClassLoader ownership. This keeps
        // RegisterNatives bindings such as YWLoginManager.getInstance attached
        // to the real guest classes instead of the loader/stub namespace.
        val onlineChapterNativeBound = preloadGuestRuntimeNativeLibraries(realGuestClassLoader)
        if (!onlineChapterNativeBound) {
            logW("  Stage2 OnlineChapterDownloadTask.run not bound")
        }
        if (isTruthyProperty("debug.multiapp.online.state_fallback", true)) {
            logW("  Installing OnlineChapterDownloadTask background-state fallback stubs")
            try {
                val fallbackRegistered =
                    NativeHookBridge.getInstance().registerOnlineChapterDownloadFallbackStubs(realGuestClassLoader)
                logD("  Stage2 online chapter fallback stubs registered: $fallbackRegistered")
            } catch (e: Throwable) {
                logW("  Stage2 online chapter fallback registration failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            logD("  Stage2 online chapter fallback stubs disabled; preserving shell native bindings")
        }

        // 验证 LoadedApk.mApplicationInfo 与 mBound.appInfo 是同一引用
        // 如果不是，需要同步修改
        try {
            val loadedApkAppInfoField = loadedApk.javaClass
                .getDeclaredField("mApplicationInfo")
                .apply { isAccessible = true }
            val loadedApkAppInfo = loadedApkAppInfoField.get(loadedApk)
            if (loadedApkAppInfo != null && loadedApkAppInfo !== appInfo) {
                val ai = loadedApkAppInfo as android.content.pm.ApplicationInfo
                ai.sourceDir = originApk.absolutePath
                ai.publicSourceDir = resourceApk.absolutePath
                applyOriginApplicationInfoFields(ai, originApk)
                ai.sourceDir = originApk.absolutePath
                ai.publicSourceDir = resourceApk.absolutePath
                ai.icon = android.R.drawable.sym_def_app_icon
                // 不改 ai.packageName — 保持 stub 身份
                if (originApplicationClass != null) {
                    ai.name = originApplicationClass
                }
                if (originNativeLibDir != null) {
                    ai.nativeLibraryDir = originNativeLibDir
                }
                logD("  Also updated LoadedApk.mApplicationInfo (separate reference)")
            } else {
                logD("  LoadedApk.mApplicationInfo is same reference as appInfo")
            }
        } catch (e: Exception) {
            logW("  mApplicationInfo check failed: ${e.message}")
        }

        // 不替换 LoadedApk.mPackageName — 保持 stub 包名
        // AppOpsManager.checkPackage(uid, pkg) 会检查 UID 与包名匹配
        // 包名伪装完全由 GuestContextWrapper 在应用层处理
        logD("  Kept LoadedApk.mPackageName as stub identity")

        // 更新资源路径
        try {
            loadedApk.javaClass.getDeclaredField("mAppDir")
                .apply { isAccessible = true }
                .set(loadedApk, originApk.absolutePath)
            loadedApk.javaClass.getDeclaredField("mResDir")
                .apply { isAccessible = true }
                .set(loadedApk, resourceApk.absolutePath)
            logD("  Updated mAppDir/mResDir: code=${originApk.absolutePath}, res=${resourceApk.absolutePath}")
        } catch (e: Exception) {
            logW("  mAppDir/mResDir update failed (OK on some Android versions): ${e.message}")
        }

        rebuildLoadedApkResources(loadedApk, resourceApk)

        // 更新 mPackages 映射
        try {
            @Suppress("UNCHECKED_CAST")
            val mPackages = activityThread.javaClass
                .getDeclaredField("mPackages")
                .apply { isAccessible = true }
                .get(activityThread) as? MutableMap<String, Any>
            if (mPackages != null) {
                val weakRef = java.lang.ref.WeakReference(loadedApk)
                mPackages[config.stubPkg] = weakRef
                mPackages[config.originalPkg] = weakRef
                logD("  Updated mPackages for ${config.stubPkg} and ${config.originalPkg}")
            } else {
                logW("  mPackages is null, skipped update")
            }
        } catch (e: Exception) {
            logW("  mPackages update failed: ${e.message}")
        }

        guestClassLoader = newClassLoader
        logD("  swapClassLoader complete")
    }

    private fun applyOriginApplicationInfoFields(
        appInfo: android.content.pm.ApplicationInfo,
        originApk: File
    ) {
        originMetaData?.let { metadata ->
            appInfo.metaData = android.os.Bundle(metadata)
            logD("  Applied origin metaData to ApplicationInfo: ${metadata.keySet().size} keys")
        } ?: logW("  Origin metaData unavailable; ApplicationInfo.metaData=${appInfo.metaData}")

        originApplicationClass?.let { appInfo.name = it }
        if (originApplicationThemeId != 0) {
            appInfo.theme = originApplicationThemeId
        }

        appInfo.sourceDir = originApk.absolutePath
        appInfo.publicSourceDir = originApk.absolutePath

        // Origin icon ids belong to the guest resource table. Expose a framework
        // icon so shortcut creation gets a valid resource in every process.
        appInfo.icon = android.R.drawable.sym_def_app_icon
        try {
            val roundIconField = appInfo.javaClass.getDeclaredField("roundIcon")
            roundIconField.isAccessible = true
            roundIconField.setInt(appInfo, android.R.drawable.sym_def_app_icon)
        } catch (_: Throwable) {
            // roundIcon is version dependent.
        }
    }

    private fun installApplicationInfoPackageManagerProxy(originApk: File, config: PocConfig) {
        if (packageManagerProxyInstalled) return
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val sPackageManagerField = activityThreadClass
                .getDeclaredField("sPackageManager")
                .apply { isAccessible = true }
            val originalPm = sPackageManagerField.get(null)
            if (originalPm == null) {
                logW("  PackageManager proxy skipped: ActivityThread.sPackageManager is null")
                return
            }

            val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                iPackageManagerClass.classLoader,
                arrayOf(iPackageManagerClass)
            ) { _, method, args ->
                try {
                    val result = invokePackageManagerWithFallback(
                        originalPm,
                        method,
                        args,
                        config
                    )
                    patchPackageManagerResult(method.name, args, result, originApk, config)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException ?: e
                }
            }

            sPackageManagerField.set(null, proxy)
            patchApplicationPackageManagerInstance(proxy)
            packageManagerProxyInstalled = true
            logD("  PackageManager ApplicationInfo proxy installed for ${config.stubPkg}/${config.originalPkg}")
        } catch (e: Throwable) {
            logW("  PackageManager ApplicationInfo proxy install failed: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun invokePackageManagerWithFallback(
        originalPm: Any,
        method: java.lang.reflect.Method,
        args: Array<Any?>?,
        config: PocConfig
    ): Any? {
        val callArgs = args ?: emptyArray()
        return try {
            method.invoke(originalPm, *callArgs)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val queriedPackage = queriedPackageName(callArgs)
            if (isPackageManagerIdentityMethod(method.name) && queriedPackage == config.originalPkg) {
                val retryArgs = callArgs.copyOf()
                retryArgs[0] = rewritePackageQueryArg(retryArgs[0], config.stubPkg)
                logD("  PM proxy retry ${method.name}: ${config.originalPkg} -> ${config.stubPkg}")
                method.invoke(originalPm, *retryArgs)
            } else {
                throw e
            }
        }
    }

    private fun rewritePackageQueryArg(arg: Any?, packageName: String): Any? {
        return when (arg) {
            is String -> packageName
            is ComponentName -> ComponentName(packageName, arg.className)
            else -> arg
        }
    }

    private fun patchApplicationPackageManagerInstance(proxy: Any) {
        try {
            val at = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            val systemContext = at?.javaClass
                ?.getDeclaredMethod("getSystemContext")
                ?.apply { isAccessible = true }
                ?.invoke(at) as? android.content.Context
            val pm = systemContext?.packageManager ?: return
            val mPmField = pm.javaClass.getDeclaredField("mPM").apply { isAccessible = true }
            mPmField.set(pm, proxy)
            logD("  Patched existing ApplicationPackageManager.mPM")
        } catch (e: Throwable) {
            logW("  Existing PackageManager instance patch failed: ${e.message}")
        }
    }

    private fun patchPackageManagerResult(
        methodName: String,
        args: Array<Any?>?,
        result: Any?,
        originApk: File,
        config: PocConfig
    ): Any? {
        if (result == null) return result
        if (!isPackageManagerIdentityMethod(methodName)) return result
        val queriedPackage = queriedPackageName(args) ?: return result
        if (queriedPackage != config.stubPkg && queriedPackage != config.originalPkg) return result

        when (result) {
            is android.content.pm.ApplicationInfo -> {
                applyOriginApplicationInfoFields(result, originApk)
                originResourceApkPath?.let { result.publicSourceDir = it }
                if (queriedPackage == config.originalPkg) {
                    result.packageName = config.originalPkg
                }
                logD("  PM proxy patched ApplicationInfo for $queriedPackage, metaData=${result.metaData?.keySet()?.size}")
            }
            is android.content.pm.PackageInfo -> {
                applyOriginPackageInfoFields(result, config, queriedPackage)
                if (queriedPackage == config.originalPkg) {
                    result.packageName = config.originalPkg
                }
                result.applicationInfo?.let { appInfo ->
                    applyOriginApplicationInfoFields(appInfo, originApk)
                    originResourceApkPath?.let { appInfo.publicSourceDir = it }
                    if (queriedPackage == config.originalPkg) {
                        appInfo.packageName = config.originalPkg
                    }
                    logD("  PM proxy patched PackageInfo.applicationInfo for $queriedPackage")
                }
                applyOriginSignaturesToPackageInfo(result, originApk, config, queriedPackage)
            }
            is android.content.pm.ComponentInfo -> {
                patchComponentInfo(result, originApk, config, queriedPackage)
                logD("  PM proxy patched ComponentInfo for $queriedPackage/${result.name}")
            }
        }
        return result
    }

    private fun applyOriginPackageInfoFields(
        packageInfo: android.content.pm.PackageInfo,
        config: PocConfig,
        queriedPackage: String
    ) {
        val originInfo = originPackageInfo ?: return
        try {
            packageInfo.versionName = originInfo.versionName
            @Suppress("DEPRECATION")
            packageInfo.versionCode = originInfo.versionCode
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode = originInfo.longVersionCode
            }
            packageInfo.sharedUserId = originInfo.sharedUserId
            packageInfo.firstInstallTime = originInfo.firstInstallTime
            packageInfo.lastUpdateTime = originInfo.lastUpdateTime
            packageInfo.gids = originInfo.gids
            packageInfo.requestedPermissions = originInfo.requestedPermissions
            packageInfo.requestedPermissionsFlags = originInfo.requestedPermissionsFlags
            if (queriedPackage == config.originalPkg) {
                packageInfo.packageName = config.originalPkg
            }
            logD(
                "  PM proxy patched PackageInfo fields for $queriedPackage: " +
                    "versionName=${packageInfo.versionName}, versionCode=${packageInfo.versionCode}, " +
                    "longVersionCode=${packageInfo.longVersionCode}"
            )
        } catch (e: Throwable) {
            logW("  PM proxy PackageInfo field patch failed for $queriedPackage: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun isPackageManagerIdentityMethod(methodName: String): Boolean {
        return methodName == "getApplicationInfo" ||
            methodName == "getPackageInfo" ||
            methodName == "getActivityInfo" ||
            methodName == "getServiceInfo" ||
            methodName == "getReceiverInfo" ||
            methodName == "getProviderInfo"
    }

    private fun queriedPackageName(args: Array<Any?>?): String? {
        val first = args?.firstOrNull() ?: return null
        return when (first) {
            is String -> first
            is ComponentName -> first.packageName
            else -> null
        }
    }

    private fun patchComponentInfo(
        componentInfo: android.content.pm.ComponentInfo,
        originApk: File,
        config: PocConfig,
        queriedPackage: String
    ) {
        if (queriedPackage == config.originalPkg) {
            componentInfo.packageName = config.originalPkg
        }
        componentInfo.applicationInfo?.let { appInfo ->
            applyOriginApplicationInfoFields(appInfo, originApk)
            originResourceApkPath?.let { appInfo.publicSourceDir = it }
            if (queriedPackage == config.originalPkg) {
                appInfo.packageName = config.originalPkg
            }
        }
    }

    private fun applyOriginSignaturesToPackageInfo(
        packageInfo: android.content.pm.PackageInfo,
        originApk: File,
        config: PocConfig,
        queriedPackage: String
    ) {
        val signatures = loadOriginSignatures(originApk, config) ?: return
        try {
            packageInfo.signatures = signatures
            patchSigningInfo(packageInfo, signatures)
            logD("  PM proxy spoofed signatures for $queriedPackage count=${signatures.size}")
        } catch (e: Throwable) {
            logW("  PM proxy signature spoof failed for $queriedPackage: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun loadOriginSignatures(
        originApk: File,
        config: PocConfig
    ): Array<android.content.pm.Signature>? {
        originSignatures?.let { return it }
        return try {
            readOriginSignaturesFromPreservedCert(originApk)?.let { signatures ->
                originSignatures = signatures
                logD("  PM proxy loaded preserved origin cert for ${config.originalPkg} count=${signatures.size}")
                return signatures
            }
            val at = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
            val systemContext = at?.javaClass
                ?.getDeclaredMethod("getSystemContext")
                ?.apply { isAccessible = true }
                ?.invoke(at) as? android.content.Context
            val pm = systemContext?.packageManager
            if (pm == null) {
                logW("  PM proxy cannot read origin signatures: PackageManager unavailable")
                return null
            }
            val flags = android.content.pm.PackageManager.GET_SIGNATURES or
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    0
                }
            val info = pm.getPackageArchiveInfo(originApk.absolutePath, flags)
            val signatures = info?.signatures
                ?: readSignaturesFromSigningInfo(info)
            if (signatures.isNullOrEmpty()) {
                logW("  PM proxy origin signatures empty: ${originApk.absolutePath}")
                null
            } else {
                originSignatures = signatures
                logD("  PM proxy loaded origin signatures for ${config.originalPkg} count=${signatures.size}")
                signatures
            }
        } catch (e: Throwable) {
            logW("  PM proxy read origin signatures failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun readOriginSignaturesFromPreservedCert(
        originApk: File
    ): Array<android.content.pm.Signature>? {
        return try {
            ZipFile(originApk).use { zip ->
                val entry = zip.getEntry("assets/multiapp_origin_cert.RSA") ?: return null
                zip.getInputStream(entry).use { input ->
                    val factory = java.security.cert.CertificateFactory.getInstance("X.509")
                    val certificates = factory.generateCertificates(input)
                    if (certificates.isEmpty()) {
                        logW("  PM proxy preserved cert block has no certificates")
                        null
                    } else {
                        certificates.map { cert ->
                            android.content.pm.Signature(cert.encoded)
                        }.toTypedArray()
                    }
                }
            }
        } catch (e: Throwable) {
            logW("  PM proxy preserved cert read failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun readSignaturesFromSigningInfo(
        packageInfo: android.content.pm.PackageInfo?
    ): Array<android.content.pm.Signature>? {
        if (packageInfo == null || android.os.Build.VERSION.SDK_INT < 28) return null
        return try {
            val signingInfo = packageInfo.signingInfo ?: return null
            when {
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.signingCertificateHistory
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun patchSigningInfo(
        packageInfo: android.content.pm.PackageInfo,
        signatures: Array<android.content.pm.Signature>
    ) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        try {
            val signingInfo = packageInfo.signingInfo ?: return
            listOf("mApkContentsSigners", "mPastSigningCertificates").forEach { fieldName ->
                try {
                    val field = signingInfo.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(signingInfo, signatures)
                } catch (_: Throwable) {
                    // SigningInfo internals differ by Android release.
                }
            }
        } catch (_: Throwable) {
            // Best effort only; PackageInfo.signatures is still patched.
        }
    }

    /**
     * 通过 JNI 调用 Runtime.nativeLoad 加载加固壳 native 库到 guest ClassLoader 命名空间。
     *
     * 为什么不能用 System.loadLibrary / System.load：
     * - System 是 boot class，Class.forName("java.lang.System", false, guestCl) 返回的
     *   仍是 boot ClassLoader 的 System.class
     * - System.loadLibrary 用 caller 的 ClassLoader (boot) 查找库 → "not found"
     * - System.load(path) 用 boot namespace → libstdc++.so 依赖解析失败
     *
     * JNI 的 Runtime.nativeLoad(path, classLoader, callerClass) 直接指定 ClassLoader，
     * 绕过 Java 层 hidden API 限制和 namespace 问题。
     */
    /**
     * Fallback: 通过 JNI Runtime.nativeLoad 预加载加固壳 native 库。
     * 主要方案已在 StubBuilder.build() 中通过 DEX 注入完成。
     */
    private fun preloadPackerLibViaGuestClassLoader(guestCl: ClassLoader, originalApkPath: String? = null, dataDir: String? = null) {
        val libDirPath = originNativeLibDir
        if (libDirPath == null) {
            logD("  preloadPackerLib: no origin lib dir, skip")
            return
        }
        val originLibDir = java.io.File(libDirPath)
        if (!originLibDir.isDirectory) return

        val jiaguFile = java.io.File(originLibDir, "libjiagu_vip.so")
        if (!jiaguFile.exists()) {
            logD("  preloadPackerLib: libjiagu_vip.so not found at ${originLibDir.absolutePath}")
            return
        }

        val callerClass = try {
            Class.forName("com.stub.StubApp", false, guestCl)
        } catch (_: Throwable) {
            try { Class.forName("com.qihoo.util.StubApp", false, guestCl) } catch (_: Throwable) { null }
        }
        if (callerClass == null) {
            logD("  preloadPackerLib: StubApp not found in guest ClassLoader")
            return
        }

        val bridge = NativeHookBridge.getInstance()
        val targetClass = callerClass.name

        // ── Step 0: 初始化 ShadowHook native hooks ──
        // 必须在 dlopen 之前！壳的 JNI_OnLoad 会读 /proc/self/maps 检测 hook 框架。
        // initNativeHooks 安装 open/fopen/readlink 等 libc hook，过滤 maps 中的 multiapp/shadowhook。
        logD("  preloadPackerLib: initializing native hooks (ShadowHook)")
        val hooksOk = bridge.initNativeHooks()
        logD("  preloadPackerLib: native hooks initialized: $hooksOk")

        // ── Step 1: 先装 FindClass hook ──
        // preloadNativeLibraries 内部会 dlopen + 手动调用 JNI_OnLoad。
        // hook 必须在此之前生效，否则 JNI_OnLoad 的 FindClass 用默认 boot namespace → 失败。
        // 传入所有候选类名，JNI_OnLoad 可能查找其中任意一个。
        val candidateClasses = arrayOf(
            "com.stub.StubApp",
            "com.qihoo.util.StubApp",
            "com.stub.StubApplication",
            "com.secneo.apkwrapper.ApplicationWrapper"
        )
        logD("  preloadPackerLib: setting up FindClass hook for ${candidateClasses.joinToString()}")
        val hookReady = bridge.setupFindClassHook(guestCl, candidateClasses)
        if (hookReady) {
            bridge.installFindClassHook()
            logD("  preloadPackerLib: FindClass hook installed")
        } else {
            logW("  preloadPackerLib: FindClass hook setup failed")
        }

        // ── Step 2: 全局 GOT hook + 让 StubApp.load() 自行加载 ──
        // 不用 dlopen，不用 loadLibraryForGuest。
        // 提前对 libc.so 做 GOT hook（过滤 /proc/self/maps），
        // 然后让 StubApp.load() 中注入的 System.loadLibrary("jiagu_vip") 成为唯一加载入口。
        // 这样 ART 会做 ClassLoader 绑定 + 调用 JNI_OnLoad，壳的 RegisterNatives 能正常执行。
        var jiaguLoadedWithGuestClassLoader = false
        try {
            // 设置完整性校验重定向
            val modifiedApkPath = originApkPath
            if (modifiedApkPath != null && originalApkPath != null) {
                bridge.setIntegrityRedirect(modifiedApkPath, originalApkPath)
                logD("  preloadPackerLib: integrity redirect: $modifiedApkPath -> $originalApkPath")
            }

            // 全局 GOT hook：对 libc.so 的 open/openat/fopen/readlink 做 hook
            // 这样壳加载时读 /proc/self/maps 会被拦截
            bridge.gotHookLibrary("libc.so")
            logD("  preloadPackerLib: global GOT hook on libc.so installed")

            // Default: do not explicitly load libjiagu_vip.so from originNativeLibDir.
            // The shell usually expects StubApp.load() to be the first loader
            // entry. For QQ Reader diagnostics, debug.multiapp.jiagu.explicit_load=1
            // forces an early guest load so we can trigger the original
            // StubApp RegisterNatives path before fallback wrappers are installed.
            try {
                val jiaguFile = java.io.File(originNativeLibDir, "libjiagu_vip.so")
                val processName = currentProcessName()
                val explicitLoadRequested = isTruthyProperty("debug.multiapp.jiagu.explicit_load", false)
                val explicitLoadAllowed = explicitLoadRequested && !processName.contains(":")
                if (explicitLoadAllowed) {
                    logD("  preloadPackerLib: jiagu_vip.so exists=${jiaguFile.exists()} at ${jiaguFile.absolutePath}; explicit load enabled process=$processName")
                    val dlopenOnlyOk = bridge.dlopenOnly(jiaguFile.absolutePath)
                    logD("  preloadPackerLib: explicit jiagu_vip dlopenOnly result=$dlopenOnlyOk")
                    if (dlopenOnlyOk) {
                        bridge.gotHookLibrary("libjiagu_vip.so")
                        logD("  preloadPackerLib: explicit jiagu_vip GOT hook installed after dlopenOnly")
                    }
                    jiaguLoadedWithGuestClassLoader = bridge.loadLibraryForGuest(
                        jiaguFile.absolutePath,
                        guestCl,
                        callerClass
                    )
                    logD("  preloadPackerLib: explicit jiagu_vip nativeLoad guest result=$jiaguLoadedWithGuestClassLoader")
                    if (!jiaguLoadedWithGuestClassLoader) {
                        jiaguLoadedWithGuestClassLoader = loadGuestLibraryViaInjectedHelper(guestCl, "jiagu_vip")
                        logD("  preloadPackerLib: explicit jiagu_vip helper fallback result=$jiaguLoadedWithGuestClassLoader")
                    }
                } else if (explicitLoadRequested) {
                    logD("  preloadPackerLib: jiagu_vip.so exists=${jiaguFile.exists()} at ${jiaguFile.absolutePath}; explicit load skipped for process=$processName")
                } else {
                    logD("  preloadPackerLib: jiagu_vip.so exists=${jiaguFile.exists()} at ${jiaguFile.absolutePath}; explicit load skipped process=$processName")
                    val prehookDlopen = isTruthyProperty("debug.multiapp.jiagu.prehook_dlopen", false)
                    if (prehookDlopen && jiaguFile.exists() && !processName.contains(":")) {
                        val dlopenOnlyOk = bridge.dlopenOnly(jiaguFile.absolutePath)
                        logD("  preloadPackerLib: prehook jiagu_vip dlopenOnly result=$dlopenOnlyOk")
                        if (dlopenOnlyOk) {
                            bridge.gotHookLibrary("libjiagu_vip.so")
                            logD("  preloadPackerLib: prehook jiagu_vip GOT hook installed before StubApp.load")
                        }
                    } else {
                        logD("  preloadPackerLib: prehook jiagu_vip dlopenOnly skipped prehook=$prehookDlopen process=$processName")
                    }
                }
                arrayOf("libc.so", "libfockrt.so", "libfock.so").forEach { targetLib ->
                    try {
                        bridge.gotHookLibrary(targetLib)
                        logD("  preloadPackerLib: GOT hook on $targetLib installed before StubApp.load")
                    } catch (e: Throwable) {
                        logD("  preloadPackerLib: GOT hook on $targetLib failed before StubApp.load: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                logD("  preloadPackerLib: pre-load hook setup failed: ${e.javaClass.simpleName}: ${e.message}")
            }

        } catch (e: Throwable) {
            bridge.clearIntegrityRedirect()
            logD("  preloadPackerLib: GOT hook setup failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        // ── Step 3: 调用 StubApp.load() ──
        // 如果 dlopen + FindClass hook 成功，JNI_OnLoad 已通过 RegisterNatives 注册了
        // native 方法（interface20 等）。StubApp.load() 调用 native 方法 → 壳解密 DEX。
        try {
            val loadMethod = callerClass.declaredMethods.firstOrNull { m ->
                m.name == "load" && m.parameterTypes.isEmpty() && java.lang.reflect.Modifier.isStatic(m.modifiers)
            }
            if (loadMethod != null) {
                loadMethod.isAccessible = true
                try {
                    loadMethod.invoke(null)
                    logD("  preloadPackerLib: StubApp.load() invoked OK")
                    jiaguLoadedWithGuestClassLoader = true
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val realCause = e.targetException ?: e.cause ?: e
                    logW("  preloadPackerLib: StubApp.load() threw: ${realCause.javaClass.simpleName}: ${realCause.message}")
                    var cause = realCause.cause
                    var depth = 0
                    while (cause != null && depth < 5) {
                        logW("    cause[$depth]: ${cause.javaClass.simpleName}: ${cause.message}")
                        cause = cause.cause
                        depth++
                    }
                }
                arrayOf("libjiagu_vip.so", "libfockrt.so", "libfock.so").forEach { targetLib ->
                    try {
                        bridge.gotHookLibrary(targetLib)
                        logD("  preloadPackerLib: GOT hook on $targetLib installed after StubApp.load")
                    } catch (e: Throwable) {
                        logD("  preloadPackerLib: GOT hook on $targetLib failed after StubApp.load: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }

                try {
                    val fockRtFile = java.io.File(originNativeLibDir, "libfockrt.so")
                    if (fockRtFile.exists()) {
                        val helperClass = Class.forName("com.multiapp.NativeLibLoader", true, guestCl)
                        val helperMethod = helperClass.getDeclaredMethod("loadLibrary", String::class.java)
                        helperMethod.isAccessible = true
                        helperMethod.invoke(null, "fockrt")
                        logD("  preloadPackerLib: libfockrt.so loaded via NativeLibLoader")
                    } else {
                        logD("  preloadPackerLib: libfockrt.so not found at ${fockRtFile.absolutePath}")
                    }
                } catch (e: Throwable) {
                    logD("  preloadPackerLib: libfockrt.so guest load failed: ${e.javaClass.simpleName}: ${e.message}")
                }

                // ★ 批量注册所有已知 native 类的缺失方法（先注册通用 stub）
                logD("  preloadPackerLib: registering all missing native methods")
                try {
                    val allRegistered = bridge.registerAllMissingNativeMethods(guestCl)
                    logD("  preloadPackerLib: all missing native methods registered: $allRegistered")
                } catch (e: Throwable) {
                    logW("  preloadPackerLib: registerAllMissingNativeMethods exception: ${e.message}")
                }

                // ★ 再注册关键业务 stub（覆盖批量注册中的 null 返回 stub）
                logD("  preloadPackerLib: registering business native stubs (after StubApp.load)")
                try {
                    val businessRegistered = bridge.registerBusinessStubs(guestCl)
                    logD("  preloadPackerLib: business stubs registered: $businessRegistered")
                } catch (e: Throwable) {
                    logW("  preloadPackerLib: registerBusinessStubs exception: ${e.message}")
                }

                logD("  preloadPackerLib: registering qrencrypt native stubs")
                try {
                    val qrencryptRegistered = bridge.registerQrencryptStubs(guestCl)
                    logD("  preloadPackerLib: qrencrypt stubs registered: $qrencryptRegistered")
                } catch (e: Throwable) {
                    logW("  preloadPackerLib: registerQrencryptStubs exception: ${e.message}")
                }

                // ── 初始化 LSPlant 并 hook 有问题的 SDK 初始化 ──
                val hookEngine = com.multiapp.core.hook.HookEngine.getInstance()
                val lsplantOk = hookEngine.initLsplant(guestCl)
                logD("  preloadPackerLib: LSPlant initialized: $lsplantOk")

                // AntiDetectionEngine 初始化 — 反检测引擎接入启动流程
                try {
                    val antiDetect = com.multiapp.core.hook.AntiDetectionEngine(hookEngine, bridge)
                    antiDetect.initialize()
                    antiDetect.enableAntiDetection("default", com.multiapp.core.hook.DetectionLevel.MODERATE)
                    logD("  preloadPackerLib: AntiDetectionEngine initialized and enabled (MODERATE)")
                } catch (e: Throwable) {
                    logW("  preloadPackerLib: AntiDetectionEngine init failed: ${e.javaClass.simpleName}: ${e.message}")
                }

                // 跳过 Pangle 广告 SDK 初始化（Zeus.init 方法不存在）
                try {
                    val zeusUtilsClass = Class.forName(
                        "com.bytedance.android.dy.sdk.pangle.ZeusPlatformUtils", false, guestCl)
                    val initZeusMethod = zeusUtilsClass.declaredMethods.firstOrNull { it.name == "initZeus" }
                    if (initZeusMethod != null && lsplantOk) {
                        hookEngine.hookMethod(initZeusMethod,
                            beforeCallback = { _, _ ->
                                logD("  Hooked ZeusPlatformUtils.initZeus — skipping Pangle init")
                                null // null = skip original method
                            }
                        )
                        logD("  preloadPackerLib: Pangle initZeus hooked")
                    }
                } catch (e: Throwable) {
                    logD("  preloadPackerLib: Pangle hook skipped: ${e.javaClass.simpleName}: ${e.message}")
                }

                logD("  preloadPackerLib: keep ReaderApplication.initLoginSDK and Fock.sign original")
                try {
                    if (lsplantOk) {
                        val fileDiagOk = com.multiapp.core.hook.QqReaderFileJavaDiag.install(hookEngine)
                        logD("  preloadPackerLib: QQReader java file diag installed: $fileDiagOk")
                        val providerDiagOk = com.multiapp.core.hook.QqReaderProviderDiag.install(hookEngine, guestCl)
                        logD("  preloadPackerLib: QQReader provider diag installed: $providerDiagOk")
                        val protocolDiagOk = com.multiapp.core.hook.QqReaderProtocolDiag.install(hookEngine, guestCl)
                        logD("  preloadPackerLib: QQReader protocol diag installed: $protocolDiagOk")
                        val eqctCompatOk = com.multiapp.core.hook.QqReaderEqctPlaintextCompat.install(hookEngine, guestCl)
                        logD("  preloadPackerLib: QQReader eqct plaintext compat installed: $eqctCompatOk")
                    }
                } catch (e: Throwable) {
                    logD("  preloadPackerLib: QQReader java file diag skipped: ${e.javaClass.simpleName}: ${e.message}")
                }

                // ★ Hook ShortcutManager.cihai() 跳过快捷方式创建（icon 资源找不到会崩溃）
                try {
                    val shortcutClass = Class.forName("com.qq.reader.shortcut.ShortcutManager", false, guestCl)
                    val cihaiMethod = shortcutClass.declaredMethods.firstOrNull { it.name == "cihai" }
                    if (cihaiMethod != null && lsplantOk) {
                        hookEngine.hookMethod(cihaiMethod,
                            beforeCallback = { _, _ ->
                                logD("  Hooked ShortcutManager.cihai — skipping shortcut creation")
                                null
                            }
                        )
                        logD("  preloadPackerLib: ShortcutManager.cihai hooked")
                    }
                } catch (e: Throwable) {
                    logD("  preloadPackerLib: ShortcutManager hook skipped: ${e.javaClass.simpleName}: ${e.message}")
                }

                // ★ Hook ReaderApplication.initPushSDK() 跳过推送初始化（YWPushSDK Bundle NPE）
                try {
                    val readerAppClass2 = Class.forName("com.qq.reader.ReaderApplication", false, guestCl)
                    val initPushMethod = readerAppClass2.declaredMethods.firstOrNull { it.name == "initPushSDK" }
                    if (initPushMethod != null && lsplantOk) {
                        hookEngine.hookMethod(initPushMethod,
                            beforeCallback = { _, _ ->
                                logD("  Hooked ReaderApplication.initPushSDK — skipping push init")
                                null
                            }
                        )
                        logD("  preloadPackerLib: initPushSDK hooked")
                    }
                } catch (e: Throwable) {
                    logD("  preloadPackerLib: initPushSDK hook skipped: ${e.javaClass.simpleName}: ${e.message}")
                }

                // ── P0: Dump 解密产物（仅 debug 开关开启时执行）──
                try {
                    if (
                        java.lang.Boolean.getBoolean("multiapp.dump.enabled") ||
                        isTruthyProperty("debug.multiapp.dump", false)
                    ) {
                        val dumpBase = java.io.File(dataDir ?: "/data/local/tmp", "dump_output")
                        dumpBase.mkdirs()
                        val dexDumpDir = java.io.File(dumpBase, "dex"); dexDumpDir.mkdirs()
                        val soDumpDir = java.io.File(dumpBase, "lib"); soDumpDir.mkdirs()
                        logD("  P0 DUMP: dir=${dumpBase.absolutePath}, exists=${dumpBase.exists()}")

                        val dexCount = bridge.dumpDexFromClassLoader(guestCl, dexDumpDir)
                        val soCount = bridge.dumpLoadedLibraries(soDumpDir)

                        // 兜底：复制壳提取的 extracted_dex/
                        var fbCount = 0
                        val extDir = java.io.File(dataDir ?: "/data/local/tmp", "extracted_dex")
                        if (extDir.exists()) {
                            extDir.listFiles()?.filter { it.name.endsWith(".dex") }?.forEach { f ->
                                if (!java.io.File(dexDumpDir, f.name).exists()) {
                                    f.copyTo(java.io.File(dexDumpDir, f.name)); fbCount++
                                }
                            }
                        }
                        // 复制 origin APK
                        val origin = java.io.File(dataDir ?: "/data/local/tmp", "base.apk")
                        if (origin.exists()) origin.copyTo(java.io.File(dumpBase, "origin.apk"), overwrite = true)

                        val total = dexCount + fbCount
                        java.io.File(dumpBase, "dump_meta.txt").writeText(
                            "native_dex=$dexCount\nfallback_dex=$fbCount\ntotal_dex=$total\nso=$soCount\ntime=${System.currentTimeMillis()}\n"
                        )
                        // 尝试复制到 /sdcard/Download/
                        try {
                            val sd = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), "MultiApp_dump")
                            sd.mkdirs()
                            if (sd.exists()) { dumpBase.copyRecursively(sd, overwrite = true); logD("  P0 DUMP: copied to ${sd.absolutePath}") }
                        } catch (_: Throwable) {}
                        logD("  P0 DUMP COMPLETE: total_dex=$total, so=$soCount")
                    } else {
                        logD("  P0 DUMP disabled (set -Dmultiapp.dump.enabled=true to enable)")
                    }
                } catch (e: Throwable) {
                    logD("  P0 DUMP FAILED: ${e.javaClass.simpleName}: ${e.message}")
                }

                // 注意：stub 重新注册已由 native stub_interface_app 处理
                // 当壳调用 interface5(Application) 时，会自动重新注册 YWLoginManager 和 Fock.sign 的 stub

            } else {
                logD("  preloadPackerLib: StubApp.load() not found (no-arg static)")
            }
        } catch (e: Throwable) {
            logD("  preloadPackerLib: StubApp.load() failed: ${e.javaClass.simpleName}: ${e.message}")
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 3) {
                logD("    cause[$depth]: ${cause.javaClass.simpleName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
        } finally {
            bridge.clearIntegrityRedirect()
        }

        // 诊断：检查 guest ClassLoader 实际加载了哪些 DEX
        try {
            val dexPathField = guestCl.javaClass.superclass?.getDeclaredField("pathList")
            dexPathField?.isAccessible = true
            val pathList = dexPathField?.get(guestCl)
            val dexElementsField = pathList?.javaClass?.getDeclaredField("dexElements")
            dexElementsField?.isAccessible = true
            val dexElements = dexElementsField?.get(pathList) as? Array<*>
            val dexPaths = dexElements?.map { elem ->
                val f = elem?.javaClass?.getDeclaredField("dexFile")
                f?.isAccessible = true
                val dexFile = f?.get(elem)
                dexFile?.toString() ?: "null"
            } ?: emptyList()
            logD("  DEX loaded: ${dexPaths.size} files: $dexPaths")
        } catch (e: Throwable) {
            logD("  DEX diagnostic failed: ${e.message}")
        }

        // ── Step 4: StubApp native fallback ──
        // Default off for QQ Reader: broad stubs replace shell entry points and
        // can prevent the protected
        // runtime from registering business natives like OnlineChapterDownloadTask.
        val stubFallbackMode = getSystemProperty("debug.multiapp.stubapp.fallback", "0")
        if (jiaguLoadedWithGuestClassLoader &&
            stubFallbackMode.equals("core", ignoreCase = true)
        ) {
            logD("  preloadPackerLib: registering StubApp core bootstrap methods (interface5/interface11/interface20/interface21)")
            try {
                val registered = bridge.registerStubCoreBootstrapMethods(guestCl, targetClass)
                logD("  preloadPackerLib: StubApp core bootstrap methods registered: $registered")
            } catch (e: Throwable) {
                logW("  preloadPackerLib: registerStubCoreBootstrapMethods exception: ${e.message}")
            }
        } else if (stubFallbackMode.equals("bootstrap", ignoreCase = true) ||
            stubFallbackMode.equals("bootstrap_only", ignoreCase = true) ||
            !jiaguLoadedWithGuestClassLoader
        ) {
            val reason = if (!jiaguLoadedWithGuestClassLoader) {
                "jiagu native registration unavailable"
            } else {
                "explicit property"
            }
            logD("  preloadPackerLib: registering StubApp bootstrap methods only (jiaguLoaded=$jiaguLoadedWithGuestClassLoader, reason=$reason)")
            try {
                val registered = bridge.registerStubBootstrapMethods(guestCl, targetClass)
                logD("  preloadPackerLib: StubApp bootstrap methods registered: $registered")
            } catch (e: Throwable) {
                logW("  preloadPackerLib: registerStubBootstrapMethods exception: ${e.message}")
            }
        } else if (stubFallbackMode.equals("interface20", ignoreCase = true) ||
            stubFallbackMode.equals("interface20_only", ignoreCase = true)
        ) {
            logD("  preloadPackerLib: registering StubApp interface20 only (jiaguLoaded=$jiaguLoadedWithGuestClassLoader)")
            try {
                val registered = bridge.registerStubInterface20Only(guestCl, targetClass)
                logD("  preloadPackerLib: StubApp interface20-only registered: $registered")
            } catch (e: Throwable) {
                logW("  preloadPackerLib: registerStubInterface20Only exception: ${e.message}")
            }
        } else if (isTruthyProperty("debug.multiapp.stubapp.fallback", false)) {
            logD("  preloadPackerLib: registering full stub methods (jiaguLoaded=$jiaguLoadedWithGuestClassLoader)")
            try {
                val registered = bridge.registerStubMethods(guestCl, targetClass)
                logD("  preloadPackerLib: full stub methods registered: $registered")
            } catch (e: Throwable) {
                logW("  preloadPackerLib: registerStubMethods exception: ${e.message}")
            }
        } else {
            logD("  preloadPackerLib: StubApp native fallback disabled; preserving shell registrations")
        }
    }

    /**
     * Some protected apps register native methods such as StubApp.interface20
     * from JNI_OnLoad. Loading these libs through the loader/stub caller binds
     * them to the wrong ClassLoader, so JNI_OnLoad cannot find the protected
     * StubApp class and RegisterNatives never completes.
     */
    private fun preloadPackerNativeLibraries(
        originLibDir: File?,
        realGuestClassLoader: ClassLoader
    ) {
        if (originLibDir == null || !originLibDir.isDirectory) {
            logD("  Packer native preload skipped: no origin lib dir")
            return
        }

        val candidates = originLibDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.endsWith(".so") &&
                    (file.name.startsWith("libjiagu") || file.name.contains("jiagu", ignoreCase = true))
            }
            ?.sortedBy { file ->
                when {
                    file.name.contains("vip", ignoreCase = true) -> 0
                    file.name.startsWith("libjiagu", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            ?: emptyList()

        if (candidates.isEmpty()) {
            logD("  Packer native preload skipped: no jiagu libs")
            return
        }

        val paths = candidates.map { it.absolutePath }
        logD("  Packer native preload: ${paths.size} libs found")

        // 策略 1: 通过 JNI 调用 Runtime.nativeLoad（绕过 hidden API，使用 guest ClassLoader）
        val callerClass = try {
            Class.forName("com.stub.StubApp", false, realGuestClassLoader)
        } catch (_: Throwable) { null }

        if (callerClass != null) {
            val bridge = NativeHookBridge.getInstance()
            for (lib in candidates) {
                try {
                    val ok = bridge.loadLibraryForGuest(lib.absolutePath, realGuestClassLoader, callerClass)
                    if (ok) {
                        logD("  Preloaded OK (JNI nativeLoad): ${lib.name}")
                        return // 成功加载一个就够了
                    }
                } catch (e: Throwable) {
                    logW("  JNI nativeLoad failed for ${lib.name}: ${e.message}")
                }
            }
        }

        // 策略 2: dlopen 直接加载
        try {
            val loaded = NativeHookBridge.getInstance().preloadNativeLibraries(paths)
            if (loaded > 0) {
                logD("  Packer native preload OK via dlopen: $loaded/${paths.size}")
                return
            }
        } catch (e: Throwable) {
            logW("  dlopen preload failed: ${e.message}")
        }

        // 策略 3: 反射 Runtime.nativeLoad
        val nativeLoad = try {
            Runtime::class.java.getDeclaredMethod(
                "nativeLoad",
                String::class.java,
                ClassLoader::class.java,
                Class::class.java
            ).apply { isAccessible = true }
        } catch (_: Throwable) { null }

        if (nativeLoad != null && callerClass != null) {
            for (lib in candidates) {
                try {
                    val error = nativeLoad.invoke(null, lib.absolutePath, realGuestClassLoader, callerClass) as? String
                    if (error == null) { logD("  Preloaded OK (reflection): ${lib.name}"); return }
                    else logW("  Preload failed for ${lib.name}: $error")
                } catch (e: Throwable) {
                    logW("  Preload exception for ${lib.name}: ${e.cause?.message ?: e.message}")
                }
            }
        }

        // 策略 4: System.load() 最终回退
        logW("  All preload strategies exhausted, trying System.load()")
        for (lib in candidates) {
            try {
                System.load(lib.absolutePath)
                logD("  Preloaded OK (System.load): ${lib.name}")
            } catch (e: Throwable) {
                logW("  System.load failed for ${lib.name}: ${e.message}")
            }
        }
    }

    private fun preloadGuestRuntimeNativeLibraries(realGuestClassLoader: ClassLoader): Boolean {
        val libDirPath = originNativeLibDir
        if (libDirPath == null) {
            logD("  Stage2 native preload skipped: no origin lib dir")
            return false
        }
        val libDir = File(libDirPath)
        if (!libDir.isDirectory) {
            logD("  Stage2 native preload skipped: not a directory: $libDirPath")
            return false
        }

        val bridge = NativeHookBridge.getInstance()
        preloadNativeForClass(
            bridge = bridge,
            classLoader = realGuestClassLoader,
            className = "com.yuewen.ywlogin.login.YWLoginManager",
            methodName = "getInstance",
            libDir = libDir,
            preferredLibraries = listOf(
                "libywlogin.so",
                "libYWLogin.so",
                "libyuewenlogin.so",
                "libyuewen.so",
                "libreader.so",
                "libaccount.so",
                "liblogin.so",
                "libsdk.so",
                "libywad-own.so",
                "libnativekey.so",
                "libapp.so",
                "libentryexpro.so",
                "libQmt.so"
            )
        )
        val onlineRunBound = preloadNativeForClass(
            bridge = bridge,
            classLoader = realGuestClassLoader,
            className = "com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask",
            methodName = "run",
            libDir = libDir,
            preferredLibraries = listOf(
                "libapp.so",
                "libentryexpro.so",
                "libQmt.so",
                "libywad-own.so",
                "libnativekey.so",
                "libnib.so",
                "librelax.so"
            )
        )
        return onlineRunBound
    }

    private fun preloadNativeForClass(
        bridge: NativeHookBridge,
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        libDir: File,
        preferredLibraries: List<String>
    ): Boolean {
        val callerClass = try {
            Class.forName(className, false, classLoader)
        } catch (e: Throwable) {
            logW("  Stage2 native preload: caller class not found: $className (${e.message})")
            return false
        }

        val allLibs = libDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?: emptyList()
        if (allLibs.isEmpty()) {
            logD("  Stage2 native preload: no .so files in ${libDir.absolutePath}")
            return false
        }
        logD("  Stage2 native preload: ${allLibs.size} libs available: ${allLibs.joinToString { it.name }}")

        val preferred = preferredLibraries.mapNotNull { name ->
            allLibs.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        val keywordLibs = allLibs
            .filter { file ->
                val n = file.name.lowercase()
                n.contains("yw") ||
                    n.contains("login") ||
                    n.contains("yuewen") ||
                    n.contains("reader") ||
                    n.contains("account") ||
                    n.contains("sdk")
            }
            .sortedBy { it.name.lowercase() }
        val candidates = (preferred + keywordLibs)
            .distinctBy { it.absolutePath }

        if (candidates.isEmpty()) {
            logW("  Stage2 native preload: no named candidates for $className, trying all libs")
        } else {
            logD("  Stage2 native preload for $className: ${candidates.joinToString { it.name }}")
        }

        if (tryBindNativeMethod(
                bridge = bridge,
                classLoader = classLoader,
                callerClass = callerClass,
                className = className,
                methodName = methodName,
                libs = candidates
            )
        ) {
            return true
        }

        // Do not brute-force every extracted .so here. Several third-party
        // SDK libraries run constructors on load and can crash outside their
        // expected initialization order (for example libmsaoaidsec.so).
        logW("  Stage2 native preload failed: $className.$methodName not bound by named candidates")
        return false
    }

    private fun tryBindNativeMethod(
        bridge: NativeHookBridge,
        classLoader: ClassLoader,
        callerClass: Class<*>,
        className: String,
        methodName: String,
        libs: List<File>
    ): Boolean {
        for (lib in libs) {
            val ok = bridge.loadLibraryForGuest(lib.absolutePath, classLoader, callerClass)
            logD("  Stage2 nativeLoad ${lib.name}: $ok")
            if (ok && isNativeMethodBoundAfterSuccessfulLoad(callerClass, methodName, className, lib.name)) {
                logD("  Stage2 native method bound after ${lib.name}: $className.$methodName")
                return true
            }

            val helperOk = loadGuestLibraryViaInjectedHelper(classLoader, lib.name.removePrefix("lib").removeSuffix(".so"))
            logD("  Stage2 NativeLibLoader ${lib.name}: $helperOk")
            if (helperOk && isNativeMethodBound(callerClass, methodName)) {
                logD("  Stage2 native method bound via NativeLibLoader after ${lib.name}: $className.$methodName")
                return true
            }
        }
        return false
    }

    private fun isNativeMethodBoundAfterSuccessfulLoad(
        clazz: Class<*>,
        methodName: String,
        className: String,
        libName: String
    ): Boolean {
        val method = clazz.declaredMethods.firstOrNull { it.name == methodName } ?: return false
        if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) {
            logD("  Stage2 native method $className.$methodName is instance; cannot prove binding after $libName")
            return false
        }
        return isNativeMethodBound(clazz, methodName)
    }

    private fun nativeLoadPriority(libName: String): Int {
        val n = libName.lowercase()
        return when {
            n.contains("yw") || n.contains("login") || n.contains("yuewen") -> 0
            n.contains("reader") || n.contains("account") || n.contains("sdk") -> 1
            n.contains("native") || n.contains("jni") -> 2
            n.contains("app") || n.contains("entry") -> 3
            else -> 4
        }
    }

    private fun isNativeMethodBound(clazz: Class<*>, methodName: String): Boolean {
        val method = clazz.declaredMethods.firstOrNull { it.name == methodName } ?: return false
        return try {
            if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) return false
            method.isAccessible = true
            method.invoke(null)
            true
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is UnsatisfiedLinkError) {
                false
            } else {
                logD("  Stage2 native method $methodName invoked with non-link error: ${cause?.javaClass?.name}: ${cause?.message}")
                true
            }
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: Throwable) {
            logD("  Stage2 native method $methodName probe result: ${e.javaClass.name}: ${e.message}")
            true
        }
    }

    private fun rebuildLoadedApkResources(loadedApk: Any, originApk: File) {
        try {
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }

            // ★ Priority 1 修复：创建干净的 AssetManager，只包含 origin APK
            // 不合并 stub 的 resources.arsc，避免资源 ID 冲突导致 getIdentifier() 返回 0
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val result = addAssetPath.invoke(assets, originApk.absolutePath) as Int
            logD("  Clean AssetManager.addAssetPath result: $result for ${originApk.absolutePath}")

            if (result == 0) {
                logW("  addAssetPath returned 0 — resources may not load correctly")
            }

            val oldResources = loadedApk.javaClass
                .getDeclaredField("mResources")
                .apply { isAccessible = true }
                .get(loadedApk) as? Resources

            val displayMetrics = oldResources?.displayMetrics
                ?: android.util.DisplayMetrics()
            val configuration = oldResources?.configuration
                ?: android.content.res.Configuration()

            val newResources = Resources(assets, displayMetrics, configuration)
            originResources = newResources

            // 替换 LoadedApk.mResources
            loadedApk.javaClass
                .getDeclaredField("mResources")
                .apply { isAccessible = true }
                .set(loadedApk, newResources)

            logD("  Replaced LoadedApk.mResources with clean origin Resources")
        } catch (e: Exception) {
            logW("  Rebuilding origin Resources failed: ${e.message}")
        }
    }

    private fun applyActivityThemeIfKnown(activity: android.app.Activity, className: String) {
        try {
            val themeId = resolveActivityTheme(className)
            if (themeId != 0) {
                // 设置 ApplicationInfo.theme，让框架在 attach 后自动应用
                activity.setTheme(themeId)
                activity.applicationInfo?.let { appInfo ->
                    appInfo.theme = themeId
                }
                replaceFieldIfPresent(activity, "mThemeResource", themeId)
                logD("  Activity theme set early: $className -> 0x${Integer.toHexString(themeId)}")
            } else {
                // ★ 兜底：用系统主题，避免 theme=0 导致 Resources$NotFoundException
                val appInfo = activity.applicationInfo
                if (appInfo != null && appInfo.theme == 0) {
                    appInfo.theme = android.R.style.Theme_Material_Light_NoActionBar
                    logD("  Activity theme fallback: $className -> Theme_Material_Light_NoActionBar")
                } else {
                    logW("  Activity theme is 0 for $className")
                }
            }
        } catch (e: Exception) {
            logW("  applyActivityThemeIfKnown failed for $className: ${e.message}")
        }
    }

    private fun resolveActivityTheme(className: String): Int {
        val originTheme = originActivityThemes[className] ?: 0
        if (originTheme != 0) return originTheme
        if (originApplicationThemeId != 0) return originApplicationThemeId

        val at = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
        val systemContext = at.javaClass
            .getDeclaredMethod("getSystemContext")
            .apply { isAccessible = true }
            .invoke(at) as android.content.Context
        val candidates = listOfNotNull(stubPackageName, guestPackageName)
        for (pkg in candidates) {
            try {
                val info = systemContext.packageManager.getActivityInfo(
                    android.content.ComponentName(pkg, className),
                    0
                )
                if (info.theme != 0) return info.theme
            } catch (_: Exception) {
                // Try the next package identity.
            }
        }
        return 0
    }

    private fun probeOriginTheme(resources: Resources, themeId: Int) {
        try {
            val theme = resources.newTheme()
            theme.applyStyle(themeId, true)
            val typedValue = android.util.TypedValue()
            val resolved = theme.resolveAttribute(android.R.attr.windowNoTitle, typedValue, true)
            logD("  Origin theme probe: windowNoTitle resolved=$resolved value=0x${Integer.toHexString(typedValue.data)}")
        } catch (e: Exception) {
            logW("  Origin theme probe failed for 0x${Integer.toHexString(themeId)}: ${e.message}")
        }
    }

    /**
     * 包装 Application 的 Context，让原始 app 代码看到正确的包名和 ApplicationInfo。
     * 通过反射获取 mBase（ContextImpl），用 GuestContextWrapper 包装后替换回去。
     */
    private fun wrapApplicationContext(app: Application) {
        val pkg = guestPackageName ?: return
        val apkPath = originApkPath ?: return
        if (isHotfixProxyApplication(app)) {
            contextWrapped = true
            logD("  Skip Context wrapping for hotfix Application: ${app.javaClass.name}")
            return
        }
        try {
            val mBaseField = android.content.ContextWrapper::class.java
                .getDeclaredField("mBase")
                .apply { isAccessible = true }
            val originalContext = mBaseField.get(app) as? android.content.Context
            if (originalContext != null) {
                val wrappedContext = GuestContextWrapper(
                    base = originalContext,
                    guestPackageName = pkg,
                    guestSourceDir = apkPath,
                    guestResourceDir = originResourceApkPath ?: apkPath,
                    guestNativeLibDir = originNativeLibDir,
                    guestMetaData = originMetaData,
                    guestResources = originResources
                )
                wrappedContext.mOuterContext = app
                mBaseField.set(app, wrappedContext)
                logD("  Wrapped Context with guest package: $pkg")
            } else {
                logW("  mBase is null, cannot wrap Context")
            }
        } catch (e: Exception) {
            logW("  Context wrapping failed: ${e.message}")
        }
    }

    @Volatile
    private var contextWrapped = false

    /**
     * 延迟包装 Context — 在 instantiateProvider 阶段调用。
     * 此时 Application.attachBaseContext() 已完成，mBase 不再为 null。
     */
    private fun tryWrapApplicationContextDeferred() {
        if (contextWrapped) return
        val pkg = guestPackageName ?: return
        val apkPath = originApkPath ?: return
        try {
            val at = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null) ?: return
            val mBound = at.javaClass.getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }
                .get(at) ?: return
            val loadedApk = mBound.javaClass.getDeclaredField("info")
                .apply { isAccessible = true }
                .get(mBound) ?: return
            val app = loadedApk.javaClass.getDeclaredField("mApplication")
                .apply { isAccessible = true }
                .get(loadedApk) as? Application ?: return
            if (isHotfixProxyApplication(app)) {
                contextWrapped = true
                logD("  Skip deferred Context wrapping for hotfix Application: ${app.javaClass.name}")
                return
            }

            val mBaseField = android.content.ContextWrapper::class.java
                .getDeclaredField("mBase")
                .apply { isAccessible = true }
            val originalContext = mBaseField.get(app) as? android.content.Context
            if (originalContext != null) {
                val wrappedContext = GuestContextWrapper(
                    base = originalContext,
                    guestPackageName = pkg,
                    guestSourceDir = apkPath,
                    guestResourceDir = originResourceApkPath ?: apkPath,
                    guestNativeLibDir = originNativeLibDir,
                    guestMetaData = originMetaData,
                    guestResources = originResources
                )
                wrappedContext.mOuterContext = app
                mBaseField.set(app, wrappedContext)
                contextWrapped = true
                logD("  Deferred wrapped Context with guest package: $pkg")
            }
        } catch (e: Exception) {
            logW("  Deferred context wrapping failed: ${e.message}")
        }
    }

    /**
     * 注册 ActivityLifecycleCallbacks，在 Activity.onCreate 前包装 Activity 的 Context。
     * 解决 Activity.getResources() 绕过 GuestContextWrapper 的问题。
     *
     * onActivityPreCreated (API 29+) 在 Activity.attach() 之后、onCreate() 之前调用。
     * 此时 Activity 的 mBase (ContextImpl) 已设置，可以替换为 GuestContextWrapper。
     */
    private fun registerActivityContextWrapper(app: Application) {
        val pkg = guestPackageName ?: return
        val apkPath = originApkPath ?: return

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                syncActivityResourceContext(activity, pkg, apkPath, "preCreated")
            }
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, o: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    private fun syncActivityResourceContext(
        activity: android.app.Activity,
        pkg: String,
        apkPath: String,
        reason: String
    ) {
        val resources = originResources
        if (resources == null) {
            logW("  Activity resource sync skipped, originResources is null: ${activity.javaClass.name}")
            return
        }

        try {
            val wrappedContext = if (shouldWrapActivityBase(activity, reason)) {
                wrapContextForGuest(activity, pkg, apkPath)
            } else {
                logD("  Skip Activity base context wrapping[$reason]: ${activity.javaClass.name}")
                null
            }
            replaceFieldIfPresent(activity, "mResources", resources)
            patchResourceObjectGraph(activity.resources, resources, "activity.resources")
            patchResourceObjectGraph(activity.application?.resources, resources, "application.resources")
            if (wrappedContext != null) {
                patchResourceObjectGraph(wrappedContext.resources, resources, "wrappedContext.resources")
            }

            val themeId = originActivityThemes[activity.javaClass.name]
                ?: originApplicationThemeId
            applyOriginActivityTheme(activity, wrappedContext, themeId)

            val inflaterContext = if (shouldUseActivityAsInflaterContext(activity)) {
                activity
            } else {
                wrappedContext ?: activity
            }
            syncActivityInflater(activity, inflaterContext)

            logD(
                "  Synced Activity resources[$reason]: ${activity.javaClass.name}, " +
                    "theme=0x${Integer.toHexString(themeId)}, res=${resources.javaClass.name}"
            )
        } catch (e: Throwable) {
            logW("  Activity resource sync failed: ${activity.javaClass.name}: ${e.message}")
        }
    }

    private fun shouldUseActivityAsInflaterContext(activity: android.app.Activity): Boolean {
        return isReaderPageActivity(activity)
    }

    private fun isReaderPageActivity(activity: android.app.Activity): Boolean {
        return activity.javaClass.name == "com.qq.reader.activity.ReaderPageActivity"
    }

    private fun shouldWrapActivityBase(activity: android.app.Activity, reason: String): Boolean {
        return reason == "preCreated" ||
            reason == "callActivityOnCreate" ||
            reason == "callActivityOnCreatePersistable"
    }

    private fun restoreActivityBaseContextAfterLifecycle(activity: android.app.Activity, reason: String) {
        val className = activity.javaClass.name
        if (reason != "callActivityOnCreate" && reason != "callActivityOnCreatePersistable") return
        try {
            val mBaseField = findFieldInHierarchy(android.content.ContextWrapper::class.java, "mBase")
                ?: return
            val current = mBaseField.get(activity)
            if (current is GuestContextWrapper) {
                mBaseField.set(activity, current.baseContext)
                logD("  Restored Activity base context after $reason: $className")
            }
        } catch (e: Throwable) {
            logW("  Restore Activity base context failed after $reason: $className: ${e.message}")
        }
    }

    private fun applyOriginActivityTheme(
        activity: android.app.Activity,
        wrappedContext: android.content.Context?,
        themeId: Int
    ) {
        val resources = originResources ?: return
        try {
            (wrappedContext as? GuestContextWrapper)?.setTheme(themeId)

            val theme = resources.newTheme()
            try {
                theme.setTo(activity.application?.theme)
            } catch (_: Throwable) {
                // Application theme can belong to the stub table; apply the origin style below.
            }
            if (themeId != 0) {
                theme.applyStyle(themeId, true)
            }

            replaceFieldIfPresent(activity, "mResources", resources)
            replaceFieldIfPresent(activity, "mTheme", theme)
            if (themeId != 0) {
                replaceFieldIfPresent(activity, "mThemeResource", themeId)
            }
            logD(
                "  Applied origin Activity theme: ${activity.javaClass.name}, " +
                    "theme=0x${Integer.toHexString(themeId)}"
            )
        } catch (e: Throwable) {
            logW("  Apply origin Activity theme failed: ${activity.javaClass.name}: ${e.message}")
        }
    }

    private fun wrapContextForGuest(
        target: android.content.ContextWrapper,
        pkg: String,
        apkPath: String
    ): android.content.Context? {
        val mBaseField = findFieldInHierarchy(android.content.ContextWrapper::class.java, "mBase")
            ?: return null
        val originalContext = mBaseField.get(target) as? android.content.Context ?: return null
        if (originalContext is GuestContextWrapper) {
            return originalContext
        }
        val wrappedContext = GuestContextWrapper(
            base = originalContext,
            guestPackageName = pkg,
            guestSourceDir = apkPath,
            guestResourceDir = originResourceApkPath ?: apkPath,
            guestNativeLibDir = originNativeLibDir,
            guestMetaData = originMetaData,
            guestResources = originResources
        )
        wrappedContext.mOuterContext = target
        mBaseField.set(target, wrappedContext)
        return wrappedContext
    }

    private fun syncActivityInflater(activity: android.app.Activity, context: android.content.Context) {
        val field = findFieldInHierarchy(activity.javaClass, "mInflater")
        if (field == null) {
            return
        }

        val current = try {
            field.get(activity)
        } catch (_: Throwable) {
            null
        }
        val cloned = try {
            (current as? LayoutInflater)?.cloneInContext(context)
                ?: LayoutInflater.from(context).cloneInContext(context)
        } catch (_: Throwable) {
            null
        }

        if (cloned != null && field.type.isInstance(cloned)) {
            try {
                patchLayoutInflaterContext(cloned, context, activity.javaClass.name)
                field.set(activity, cloned)
                return
            } catch (e: Throwable) {
                logW("  Reflect set ${activity.javaClass.name}.mInflater failed: ${e.message}")
            }
        }

        if (current != null && cloned == null) {
            logD("  Kept existing Activity inflater without reflection patch: ${activity.javaClass.name}, inflater=${current.javaClass.name}")
        } else if (cloned != null && field.type.isAssignableFrom(cloned.javaClass)) {
            patchLayoutInflaterContext(cloned, context, activity.javaClass.name)
            field.set(activity, cloned)
        }
    }

    private fun patchLayoutInflaterContext(
        inflater: LayoutInflater,
        context: android.content.Context,
        label: String
    ) {
        try {
            replaceFieldIfPresent(inflater, "mContext", context)
            val constructorArgsField = findFieldInHierarchy(LayoutInflater::class.java, "mConstructorArgs")
            val args = constructorArgsField?.get(inflater) as? Array<Any?>
            if (args != null && args.isNotEmpty()) {
                args[0] = context
            }
            logD("  Patched LayoutInflater context for $label -> ${context.javaClass.name}")
        } catch (e: Throwable) {
            logW("  Patch LayoutInflater context failed for $label: ${e.message}")
        }
    }

    private fun patchResourceObjectGraph(
        target: Any?,
        originResources: Resources,
        label: String,
        seen: MutableSet<Int> = mutableSetOf()
    ) {
        if (target == null) return
        if (!seen.add(System.identityHashCode(target))) return

        val originImpl = try {
            findFieldInHierarchy(Resources::class.java, "mResourcesImpl")?.get(originResources)
        } catch (_: Throwable) {
            null
        }
        val originAssets = originResources.assets
        val originDisplayMetrics = originResources.displayMetrics
        val originConfiguration = originResources.configuration

        var patchedAny = false
        var current: Class<*>? = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val fieldType = field.type
                    when {
                        Resources::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originResources) {
                                field.set(target, originResources)
                                patchedAny = true
                                logD("  Patched $label.${field.name} -> originResources")
                            }
                        }
                        fieldType.name.contains("ResourcesImpl") && originImpl != null -> {
                            if (field.get(target) !== originImpl) {
                                field.set(target, originImpl)
                                patchedAny = true
                                logD("  Patched $label.${field.name} -> originResourcesImpl")
                            }
                        }
                        AssetManager::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originAssets) {
                                field.set(target, originAssets)
                                patchedAny = true
                                logD("  Patched $label.${field.name} -> originAssets")
                            }
                        }
                        android.util.DisplayMetrics::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originDisplayMetrics) {
                                field.set(target, originDisplayMetrics)
                                patchedAny = true
                            }
                        }
                        android.content.res.Configuration::class.java.isAssignableFrom(fieldType) -> {
                            if (field.get(target) !== originConfiguration) {
                                field.set(target, originConfiguration)
                                patchedAny = true
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore fields we cannot touch on this platform.
                }
            }
            current = current.superclass
        }

        if (!patchedAny && target is Resources) {
            logD("  Resource graph already aligned for $label: ${target.javaClass.name}")
        }

        // Follow obvious nested resource holders once to catch wrapper stacks.
        current = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(target) ?: continue
                    when {
                        value is Resources && value !== originResources -> {
                            patchResourceObjectGraph(value, originResources, "$label.${field.name}", seen)
                        }
                        value.javaClass.name.startsWith("com.qq.reader.") -> {
                            patchResourceObjectGraph(value, originResources, "$label.${field.name}", seen)
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore nested traversal failures.
                }
            }
            current = current.superclass
        }
    }

    private fun replaceFieldIfPresent(target: Any, name: String, value: Any?): Boolean {
        val field = findFieldInHierarchy(target.javaClass, name) ?: return false
        return try {
            field.set(target, value)
            true
        } catch (e: Throwable) {
            logW("  Reflect set ${target.javaClass.name}.$name failed: ${e.message}")
            false
        }
    }

    private fun findFieldInHierarchy(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    /**
     * 从 Stub APK assets/multiapp_config.json 读取配置
     * 最小解析：只提取 originalPackageName 和 stubPackageName
     */
    private fun readConfig(stubApkPath: String): PocConfig {
        logD("readConfig from: $stubApkPath")
        ZipFile(stubApkPath).use { zip ->
            logD("  ZIP entries: ${zip.entries().toList().map { it.name }}")
            val entry = zip.getEntry("assets/multiapp_config.json")
                ?: throw IllegalStateException("assets/multiapp_config.json not found in stub APK")
            val json = zip.getInputStream(entry).bufferedReader().readText()
            logD("  Config JSON length: ${json.length}")
            logD("  Config JSON preview: ${json.take(300)}...")

            // 简单正则提取，不用 Gson
            val originalPkg = json.regexFind("\"originalPackageName\"\\s*:\\s*\"([^\"]+)\"")
            if (originalPkg == null) {
                logE("originalPackageName not found in config JSON!")
                logE("  Full JSON: ${json.take(500)}")
                throw IllegalStateException("originalPackageName not found in config")
            }
            val stubPkg = json.regexFind("\"stubPackageName\"\\s*:\\s*\"([^\"]+)\"")
            if (stubPkg == null) {
                logE("stubPackageName not found in config JSON!")
                throw IllegalStateException("stubPackageName not found in config")
            }
            logD("  parsed: originalPkg=$originalPkg, stubPkg=$stubPkg")
            return PocConfig(originalPkg = originalPkg, stubPkg = stubPkg)
        }
    }

    private fun String.regexFind(pattern: String): String? {
        val regex = Regex(pattern)
        return regex.find(this)?.groupValues?.getOrNull(1)
    }

    /**
     * 从 Stub APK 解压 origin.apk
     */
    private fun extractOriginApk(stubApkPath: String, dataDir: String): File {
        // 提取到 dataDir/base.apk — jiagu shell 通过 /proc/self/maps 或硬编码路径找 APK，
        // 期望在 /data/data/<pkg>/base.apk 位置找到它。
        val outputDir = File(dataDir)
        outputDir.mkdirs()
        val output = File(outputDir, "base.apk")
        val marker = File(outputDir, "origin_apk.marker")
        ZipFile(stubApkPath).use { zip ->
            val entry = zip.getEntry("assets/origin.apk")
                ?: throw IllegalStateException("assets/origin.apk not found in stub APK")
            val markerText = buildString {
                append("stubLength=").append(File(stubApkPath).length()).append('\n')
                append("stubLastModified=").append(File(stubApkPath).lastModified()).append('\n')
                append("originSize=").append(entry.size).append('\n')
                append("originCrc=").append(entry.crc).append('\n')
            }
            if (output.exists() && output.length() == entry.size && marker.exists() && runCatching { marker.readText() }.getOrNull() == markerText) {
                logD("Origin APK already extracted and marker matches")
                ensureReadOnly(output)
                return output
            }

            logD("Origin APK cache stale or missing, refreshing base.apk")
            ensureWritableFile(output)
            if (output.exists() && !output.delete()) {
                throw IllegalStateException("delete stale origin.apk failed: ${output.absolutePath}")
            }
            File(outputDir, "extracted_dex").deleteRecursively()
            zip.getInputStream(entry).use { input ->
                output.outputStream().use { out -> input.copyTo(out) }
            }
            runCatching {
                marker.writeText(markerText)
                ensureReadOnly(marker)
            }.onFailure {
                logW("Origin APK marker write skipped: ${it.message}")
            }
        }
        ensureReadOnly(output)
        return output
    }

    /**
     * 解压原始 APK（未修改，用于完整性校验重定向）
     * 壳的 JNI_OnLoad 读 APK 校验 DEX 完整性时，重定向到此文件。
     */
    private fun extractOriginalApk(stubApkPath: String, dataDir: String): File? {
        val output = File(dataDir, "origin_original.apk")
        if (output.exists()) return output
        try {
            ZipFile(stubApkPath).use { zip ->
                val entry = zip.getEntry("assets/origin_original.apk") ?: return null
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { out -> input.copyTo(out) }
                }
            }
            ensureReadOnly(output)
            return output
        } catch (e: Exception) {
            logD("  extractOriginalApk failed: ${e.message}")
            return null
        }
    }

    /**
     * 从 APK 中提取 classes2.dex ~ classesN.dex（multidex DEX 文件）
     * 360 加固的壳不会自动加载这些 DEX，需要手动提取并加入 ClassLoader
     */
    private fun extractAdditionalDex(apk: File, outputDir: File): List<File> {
        val result = mutableListOf<File>()
        try {
            if (!outputDir.exists()) outputDir.mkdirs()
            ZipFile(apk).use { zip ->
                val entries = zip.entries().toList()
                    .filter {
                        val name = it.name
                        // 匹配 classes2.dex ~ classes999.dex（根目录或 assets/patched/ 下）
                        (name.matches(Regex(".*/classes[2-9]\\d*\\.dex")) ||
                         name.matches(Regex("classes[2-9]\\d*\\.dex"))) &&
                        !it.isDirectory
                    }
                    .sortedBy { it.name }
                for (entry in entries) {
                    val outFile = java.io.File(outputDir, java.io.File(entry.name).name)
                    if (outFile.exists() && outFile.length() == entry.size) {
                        result.add(outFile)
                        continue
                    }
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Android 禁止加载可写的 DEX 文件
                    outFile.setReadOnly()
                    result.add(outFile)
                    logD("  Extracted DEX: ${entry.name} -> ${outFile.name} (${entry.size} bytes)")
                }
            }
        } catch (e: Throwable) {
            logW("  extractAdditionalDex failed: ${e.message}")
        }
        return result
    }

    /**
     * Extract origin APK native libraries for the current process ABI.
     *
     * Loading protected apps directly from "base.apk!/lib/<abi>" is fragile: some
     * packers expect a real filesystem path during early Application init.
     */
    private fun extractOriginNativeLibs(originApk: File): File? {
        ZipFile(originApk).use { zip ->
            val abi = findOriginNativeAbi(zip)
            if (abi == null) {
                logW("No origin native libs found for current process ABIs")
                return null
            }

            val prefix = "lib/$abi/"
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".so") }
                .toList()

            val outputDir = File(originApk.parentFile, "lib/${nativeDirNameForAbi(abi)}")
            val marker = File(outputDir, ".complete")
            val markerText = buildString {
                append("abi=").append(abi).append('\n')
                append("apkLength=").append(originApk.length()).append('\n')
                append("apkLastModified=").append(originApk.lastModified()).append('\n')
                append("count=").append(entries.size).append('\n')
                append("patchJiagu=").append(shouldPatchJiaguSo()).append('\n')
                append("patchJiaguMode=").append(getSystemProperty("debug.multiapp.patch_jiagu_mode", "legacy")).append('\n')
                append("nativeLibsWritable=").append(shouldKeepOriginNativeLibsWritable()).append('\n')
            }

            val existingSoCount = outputDir.listFiles()?.count { it.isFile && it.extension == "so" } ?: 0
            if (marker.exists() && marker.readText() == markerText && existingSoCount >= entries.size) {
                logD("Origin native libs already extracted for $abi")
                patchJiaguSoIfEnabled(outputDir)
                finalizeOriginNativeLibPermissions(outputDir)
                return outputDir
            }

            outputDir.mkdirs()
            ensureWritableDir(outputDir)
            outputDir.listFiles()
                ?.filter { it.isFile && (it.extension == "so" || it.name == marker.name) }
                ?.forEach { file ->
                    if (!file.delete()) {
                        logW("  Failed to delete stale native lib cache file: ${file.name}")
                    }
                }

            var extracted = 0
            entries.forEach { entry ->
                val outFile = File(outputDir, entry.name.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
                logD("  Extracted origin native lib: ${outFile.name}")
            }

            marker.writeText(markerText)
            patchJiaguSoIfEnabled(outputDir)
            finalizeOriginNativeLibPermissions(outputDir)
            logD("Extracted $extracted origin native libs for $abi to ${outputDir.absolutePath}")
            return outputDir
        }
    }

    private fun ensureWritableDir(dir: File) {
        try {
            dir.setWritable(true, true)
            Runtime.getRuntime().exec(arrayOf("chmod", "755", dir.absolutePath)).waitFor()
        } catch (e: Exception) {
            logW("ensureWritableDir failed: ${e.message}")
        }
    }

    private fun shouldPatchJiaguSo(): Boolean {
        if (java.lang.Boolean.getBoolean("multiapp.patch.jiagu.enabled")) {
            return true
        }
        return isTruthyProperty("debug.multiapp.patch_jiagu", false)
    }

    private fun shouldKeepOriginNativeLibsWritable(): Boolean {
        return isTruthyProperty("debug.multiapp.origin_libs.writable", false)
    }

    private fun finalizeOriginNativeLibPermissions(dir: File) {
        if (shouldKeepOriginNativeLibsWritable()) {
            ensureWritableTree(dir)
            logD("Origin native libs kept writable: ${dir.absolutePath}")
        } else {
            ensureReadOnlyTree(dir)
        }
    }

    private fun patchJiaguSoIfEnabled(libDir: File) {
        if (!shouldPatchJiaguSo()) {
            logD("  patchJiaguSo: disabled; preserving original libjiagu_vip.so")
            return
        }
        patchJiaguSoIfPresent(libDir)
    }

    /**
     * Patch libjiagu_vip.so only when explicitly enabled for diagnostics.
     * Default runtime keeps the original shell library intact because protected
     * QQ Reader methods depend on the shell's real JNI registration path.
     */
    private fun patchJiaguSoIfPresent(libDir: File) {
        val jiaguSo = File(libDir, "libjiagu_vip.so")
        if (!jiaguSo.exists()) {
            logW("  patchJiaguSo: libjiagu_vip.so not found in ${libDir.absolutePath}")
            return
        }

        try {
            val data = jiaguSo.readBytes()
            logW("  patchJiaguSo: read ${data.size} bytes from ${jiaguSo.absolutePath}")
            val patched = patchJiaguLoad(data)
            if (patched !== data) {
                jiaguSo.setWritable(true, false)
                Runtime.getRuntime().exec(arrayOf("chmod", "666", jiaguSo.absolutePath)).waitFor()
                jiaguSo.writeBytes(patched)
                logW("  patchJiaguSo: PATCHED libjiagu_vip.so (${data.size} bytes)")
            } else {
                logW("  patchJiaguSo: pattern not found, no patch applied")
            }
        } catch (e: Exception) {
            logW("  patchJiaguSo: FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Patch libjiagu_vip.so: 在 JNI_OnLoad 函数体中找 MOV W0, #-1 后跟 B，
     * 改成 MOV W0, #0（返回成功）。
     */
    private fun patchJiaguLoad(data: ByteArray): ByteArray {
        val patched = data.copyOf()

        // ELF64 header
        val ePhoff = readLongLE(patched, 32).toInt()
        val ePhentsize = readShortLE(patched, 54)
        val ePhnum = readShortLE(patched, 56)
        logW("  patchJiaguLoad: ELF64 phoff=$ePhoff phentsize=$ePhentsize phnum=$ePhnum")

        // 找 PT_DYNAMIC 段
        var dynOffset = -1
        for (i in 0 until ePhnum) {
            val phOff = ePhoff + i * ePhentsize
            if (readIntLE(patched, phOff) == 2) { // PT_DYNAMIC
                dynOffset = readLongLE(patched, phOff + 8).toInt()
                break
            }
        }
        if (dynOffset < 0) {
            logW("  patchJiaguLoad: PT_DYNAMIC not found")
            return data
        }
        logW("  patchJiaguLoad: PT_DYNAMIC at offset $dynOffset")

        // 找 PT_LOAD 段用于 vaddr → file offset 映射
        data class LoadSeg(val vaddr: Int, val offset: Int, val filesz: Int)
        val loads = mutableListOf<LoadSeg>()
        for (i in 0 until ePhnum) {
            val phOff = ePhoff + i * ePhentsize
            if (readIntLE(patched, phOff) == 1) {
                loads.add(LoadSeg(
                    readLongLE(patched, phOff + 16).toInt(),
                    readLongLE(patched, phOff + 8).toInt(),
                    readLongLE(patched, phOff + 32).toInt()
                ))
            }
        }

        fun vaddrToFile(vaddr: Int): Int {
            for (seg in loads) {
                if (vaddr >= seg.vaddr && vaddr < seg.vaddr + seg.filesz) {
                    return seg.offset + (vaddr - seg.vaddr)
                }
            }
            return -1
        }

        // 解析 dynamic entries
        var symtabVaddr = -1
        var strtabVaddr = -1
        var dynI = dynOffset
        while (dynI + 16 <= patched.size) {
            val dTag = readLongLE(patched, dynI)
            val dVal = readLongLE(patched, dynI + 8).toInt()
            if (dTag == 0L) break
            when (dTag) {
                6L -> symtabVaddr = dVal   // DT_SYMTAB
                5L -> strtabVaddr = dVal   // DT_STRTAB
            }
            dynI += 16
        }

        if (symtabVaddr < 0 || strtabVaddr < 0) {
            logW("  patchJiaguLoad: symtab=$symtabVaddr strtab=$strtabVaddr - missing")
            return data
        }
        val symtabFile = vaddrToFile(symtabVaddr)
        val strtabFile = vaddrToFile(strtabVaddr)
        if (symtabFile < 0 || strtabFile < 0) {
            logW("  patchJiaguLoad: symtabFile=$symtabFile strtabFile=$strtabFile - invalid")
            return data
        }
        logW("  patchJiaguLoad: symtab=0x${Integer.toHexString(symtabFile)} strtab=0x${Integer.toHexString(strtabFile)}")

        // 在 .dynstr 中找 "JNI_OnLoad"
        val jniStrPos = findBytes(patched, "JNI_OnLoad".toByteArray(), strtabFile)
        if (jniStrPos < 0) {
            logW("  patchJiaguLoad: JNI_OnLoad not found in .dynstr")
            return data
        }
        val jniNameIdx = jniStrPos - strtabFile
        logW("  patchJiaguLoad: JNI_OnLoad at strtab[$jniNameIdx]")

        // 在 .dynsym 中找 JNI_OnLoad 符号
        var jniVaddr = -1
        var jniSize = -1
        for (i in 0 until 2000) {
            val entryOff = symtabFile + i * 24
            if (entryOff + 24 > patched.size) break
            if (readIntLE(patched, entryOff) == jniNameIdx) {
                jniVaddr = readLongLE(patched, entryOff + 8).toInt()
                jniSize = readLongLE(patched, entryOff + 16).toInt()
                break
            }
        }

        if (jniVaddr < 0 || jniSize <= 0) {
            logW("  patchJiaguLoad: JNI_OnLoad symbol not found (vaddr=$jniVaddr size=$jniSize)")
            return data
        }
        val jniFileOff = vaddrToFile(jniVaddr)
        if (jniFileOff < 0) {
            logW("  patchJiaguLoad: JNI_OnLoad file offset invalid (vaddr=0x${Integer.toHexString(jniVaddr)})")
            return data
        }
        logW("  patchJiaguLoad: JNI_OnLoad at vaddr=0x${Integer.toHexString(jniVaddr)}, file=0x${Integer.toHexString(jniFileOff)}, size=$jniSize")
        val endOff = jniFileOff + jniSize - 4
        var patchCount = 0
        var off = jniFileOff

        val patchMode = getSystemProperty("debug.multiapp.patch_jiagu_mode", "legacy")
        logW("  patchJiaguLoad: mode=$patchMode")

        fun nopInstruction(targetOff: Int, reason: String): Boolean {
            if (targetOff < jniFileOff || targetOff > endOff) {
                logW("  patchJiaguLoad: skip NOP for $reason; offset 0x${Integer.toHexString(targetOff)} outside JNI_OnLoad")
                return false
            }
            patched[targetOff] = 0x1F.toByte()
            patched[targetOff + 1] = 0x20.toByte()
            patched[targetOff + 2] = 0x03.toByte()
            patched[targetOff + 3] = 0xD5.toByte()
            patchCount++
            logW("  patchJiaguLoad: NOP'd $reason at offset 0x${Integer.toHexString(targetOff)}")
            return true
        }

        when (patchMode.lowercase()) {
            "skip_stage1", "preserve_stage2" -> {
                // JNI_OnLoad+0x54: cbz w2, sub_2586d4. The unpatched stage-1 path
                // registers StubApp natives but then hangs inside Runtime.nativeLoad on
                // this clone. Keep stage-2 callbacks reachable so original native
                // registration still has a chance to run.
                nopInstruction(jniFileOff + 0x54, "stage1 cbz -> sub_2586d4")
            }
            "legacy" -> {
                // Legacy diagnostic patch: NOP every CBZ/CBNZ in JNI_OnLoad. This
                // makes JNI_OnLoad return but skips original StubApp registrations.
                val scanEnd = minOf(jniFileOff + jniSize, endOff)
                while (off < scanEnd) {
                    val insn = readIntLE(patched, off)
                    val isCBZ = (insn and 0xFF000000.toInt()) == 0x34000000
                    val isCBNZ = (insn and 0xFF000000.toInt()) == 0x35000000
                    if (isCBZ || isCBNZ) {
                        nopInstruction(off, if (isCBZ) "CBZ" else "CBNZ")
                    }
                    off += 4
                }
            }
            else -> {
                logW("  patchJiaguLoad: unknown mode=$patchMode; using MOVN return patch only")
            }
        }

        // Patch 2: MOV W0, #-1 → MOV W0, #0
        // 诊断：直接检查 0xceaf0 处的字节
        val expectedMovnOffset = vaddrToFile(0x258af0)  // JNI_OnLoad+0xb8 = MOV W0, #-1
        if (expectedMovnOffset >= 0 && expectedMovnOffset + 8 <= patched.size) {
            val b0 = readIntLE(patched, expectedMovnOffset)
            val b1 = readIntLE(patched, expectedMovnOffset + 4)
            logW("  patchJiaguLoad: DIAG vaddr 0x258af0 -> file 0x${Integer.toHexString(expectedMovnOffset)}: " +
                "0x${Integer.toHexString(b0)} 0x${Integer.toHexString(b1)} " +
                "(expect MOVN=0x12800000, got ${if (b0 == 0x12800000) "MATCH!" else "MISMATCH"})")
        } else {
            logW("  patchJiaguLoad: DIAG vaddr 0x258af0 -> file offset $expectedMovnOffset (out of bounds)")
        }

        off = jniFileOff
        var patch2Checked = 0
        while (off <= endOff) {
            val insn = readIntLE(patched, off)
            if (insn == 0x12800000) { // MOVN W0, #0 = MOV W0, #-1
                val nextInsn = readIntLE(patched, off + 4)
                val isBranch = (nextInsn ushr 26) == 5 // B instruction: 0b000101
                logW("  patchJiaguLoad: found MOVN at 0x${Integer.toHexString(off)}, " +
                    "next=0x${Integer.toHexString(nextInsn)}, isBranch=$isBranch")
                if (isBranch) {
                    patched[off] = 0x00
                    patched[off + 1] = 0x00
                    patched[off + 2] = 0x80.toByte()
                    patched[off + 3] = 0x52
                    patchCount++
                    logW("  patchJiaguLoad: patched MOV W0,#-1 at offset 0x${Integer.toHexString(off)}")
                }
            }
            patch2Checked++
            off += 4
        }
        logW("  patchJiaguLoad: Patch2 checked $patch2Checked instructions (0x${Integer.toHexString(jniFileOff)}..0x${Integer.toHexString(endOff)})")

        return if (patchCount > 0) patched else data
    }

    private fun findBytes(haystack: ByteArray, needle: ByteArray, startOffset: Int = 0): Int {
        for (i in startOffset..(haystack.size - needle.size)) {
            if (haystack[i] == needle[0]) {
                var match = true
                for (j in 1 until needle.size) {
                    if (haystack[i + j] != needle[j]) { match = false; break }
                }
                if (match) return i
            }
        }
        return -1
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readLongLE(data: ByteArray, offset: Int): Long {
        return (readIntLE(data, offset).toLong() and 0xFFFFFFFFL) or
            ((readIntLE(data, offset + 4).toLong() and 0xFFFFFFFFL) shl 32)
    }

    private fun readShortLE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun findOriginNativeAbi(zip: ZipFile): String? {
        val availableAbis = zip.entries().asSequence()
            .mapNotNull { entry ->
                val name = entry.name
                if (!entry.isDirectory && name.startsWith("lib/") && name.endsWith(".so")) {
                    name.removePrefix("lib/").substringBefore('/')
                } else {
                    null
                }
            }
            .toSet()
        return currentProcessSupportedAbis().firstOrNull { it in availableAbis }
    }

    private fun currentProcessSupportedAbis(): Array<String> {
        val processAbis = if (android.os.Process.is64Bit()) {
            android.os.Build.SUPPORTED_64_BIT_ABIS
        } else {
            android.os.Build.SUPPORTED_32_BIT_ABIS
        }
        return if (processAbis.isNotEmpty()) processAbis else android.os.Build.SUPPORTED_ABIS
    }

    private fun nativeDirNameForAbi(abi: String): String {
        return when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "arm"
            else -> abi
        }
    }

    private fun ensureReadOnlyTree(dir: File) {
        try {
            dir.walkTopDown().forEach { file ->
                if (file.isFile) ensureReadOnly(file)
            }
            Runtime.getRuntime().exec(arrayOf("chmod", "555", dir.absolutePath)).waitFor()
        } catch (e: Exception) {
            logW("ensureReadOnlyTree failed: ${e.message}")
        }
    }

    private fun ensureWritableTree(dir: File) {
        try {
            dir.setWritable(true, true)
            dir.walkTopDown().forEach { file ->
                if (file.isFile) ensureWritableFile(file)
            }
            Runtime.getRuntime().exec(arrayOf("chmod", "755", dir.absolutePath)).waitFor()
        } catch (e: Exception) {
            logW("ensureWritableTree failed: ${e.message}")
        }
    }

    /**
     * 确保文件为只读 — Android 要求 dex 文件必须位于只读路径
     */
    private fun ensureReadOnly(file: File) {
        try {
            file.setReadOnly()
            // setReadOnly() 只设 user 位，用 Runtime.exec 确保 group/other 也不可写
            Runtime.getRuntime().exec(arrayOf("chmod", "444", file.absolutePath)).waitFor()
            logD("Set read-only: ${file.absolutePath}")
        } catch (e: Exception) {
            logW("ensureReadOnly failed: ${e.message}")
        }
    }

    private fun ensureWritableFile(file: File) {
        try {
            file.setWritable(true, true)
            Runtime.getRuntime().exec(arrayOf("chmod", "644", file.absolutePath)).waitFor()
        } catch (e: Exception) {
            logW("ensureWritableFile failed: ${e.message}")
        }
    }

    /**
     * Hidden API bypass — 必须在任何反射调用之前执行
     * 使用 VMRuntime.setHiddenApiExemptions 豁免所有 hidden API
     */
    private fun bypassHiddenApis() {
        try {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntime.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null)
            val exempt = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            exempt.isAccessible = true
            exempt.invoke(runtime, arrayOf("L") as Any)
            logD("Hidden API bypass via VMRuntime succeeded")
        } catch (e: Exception) {
            logW("Hidden API bypass failed: ${e.message}")
        }
    }

    /**
     * 将内存日志写入文件，方便无 ADB 时查看
     */
    private fun writeDebugLogToFile(cl: ClassLoader) {
        try {
            // 尝试从 ClassLoader 获取 dataDir
            val ctx = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null) ?: return

            val mBound = ctx.javaClass
                .getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }
                .get(ctx) ?: return

            val appInfo = mBound.javaClass
                .getDeclaredField("appInfo")
                .apply { isAccessible = true }
                .get(mBound) as? android.content.pm.ApplicationInfo ?: return

            val logFile = File(appInfo.dataDir, "cache/loader_debug.log")
            logFile.parentFile?.mkdirs()
            synchronized(debugLog) {
                logFile.writeText(debugLog.joinToString("\n"))
            }
            logD("Debug log written to: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log", e)
        }
    }

    private fun dumpDebugLogToLogcat(reason: String) {
        val snapshot = synchronized(debugLog) { debugLog.toList() }
        Log.e(TAG, "DIAG-SIGNAL dumpDebugLogToLogcat reason=$reason size=${snapshot.size}")
        snapshot.takeLast(120).forEachIndexed { index, line ->
            Log.e(TAG, "DIAG-DUMP[$index] $line")
        }
    }

    /**
     * 最小配置类
     */
    data class PocConfig(
        val originalPkg: String,
        val stubPkg: String
    )

    // ========================================================================
    // TODO(B): 签名伪装升级位 — 需要用户提供自有签名文件方可启用
    // ========================================================================
    // 接入点：在 ClassLoader 替换后，hook PackageManager 对 guest 包返回用户的真实签名。
    // 前置条件：
    //   1. 用户必须提供自有签名文件（keystore / PEM / DER），本机签名文件缺失时不可启用。
    //   2. StubConfig 需拆分：新增 originApkPath（原 APK 路径），把 originalSignatures
    //      还原为真正的"原始签名证书列表"（A 阶段留空，不改结构避免大面积调用点变更）。
    // 实现思路：
    //   - 通过 Proxy.newProxyInstance(IPackageManager) 包装 ActivityThread.sPackageManager
    //   - 拦截 getPackageInfo(GET_SIGNING_CERTIFICATES) 对 guest 包返回用户提供的签名
    //   - 需配合 Hidden API bypass（Android 16 上 setHiddenApiExemptions 已失败，
    //     需改用更底层的 bypass 技术，如 META 字段修改或 JNI 直接调用）
    // 状态：未实现，等待签名文件就位后独立推进。
    // ========================================================================

    /**
     * ContentProvider 包装器 — 在 onCreate 失败时优雅降级
     *
     * FileProvider 等 provider 的 onCreate() 会读取 meta-data 中的资源 ID，
     * 但 stub 的资源表中没有原始 APK 的资源，导致 Resources$NotFoundException。
     * 此包装器捕获 onCreate 异常，让 provider 以"空实现"方式存活，不阻塞 app 启动。
     */
    private class SafeProviderWrapper(
        private val delegate: ContentProvider,
        private val originMetaData: android.os.Bundle?
    ) : ContentProvider() {

        @Volatile
        private var initFailed = false

        override fun onCreate(): Boolean {
            return try {
                delegate.onCreate()
            } catch (e: Throwable) {
                Log.e(TAG, "Provider ${delegate.javaClass.name} onCreate failed, degrading", e)
                initFailed = true
                true // 返回 true 让系统认为初始化成功
            }
        }

        override fun query(
            uri: android.net.Uri, projection: Array<out String>?,
            selection: String?, selectionArgs: Array<out String>?, sortOrder: String?
        ): android.database.Cursor? {
            if (initFailed) return null
            return runProviderCall("query") {
                delegate.query(uri, projection, selection, selectionArgs, sortOrder)
            }
        }

        override fun getType(uri: android.net.Uri): String? {
            if (initFailed) return null
            return runProviderCall("getType") {
                delegate.getType(uri)
            }
        }

        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? {
            if (initFailed) return null
            return runProviderCall("insert") {
                delegate.insert(uri, values)
            }
        }

        override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (initFailed) return 0
            return runProviderCall("delete") {
                delegate.delete(uri, selection, selectionArgs)
            } ?: 0
        }

        override fun update(
            uri: android.net.Uri, values: android.content.ContentValues?,
            selection: String?, selectionArgs: Array<out String>?
        ): Int {
            if (initFailed) return 0
            return runProviderCall("update") {
                delegate.update(uri, values, selection, selectionArgs)
            } ?: 0
        }

        override fun attachInfo(context: android.content.Context, info: android.content.pm.ProviderInfo) {
            try {
                val patchedInfo = if (info.metaData == null && originMetaData != null) {
                    android.content.pm.ProviderInfo(info).apply {
                        metaData = android.os.Bundle(originMetaData)
                    }
                } else {
                    info
                }
                delegate.attachInfo(context, patchedInfo)
            } catch (e: Throwable) {
                Log.e(TAG, "Provider ${delegate.javaClass.name} attachInfo failed", e)
                initFailed = true
            }
        }

        private fun <T> runProviderCall(operation: String, block: () -> T): T? {
            return try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "Provider ${delegate.javaClass.name} $operation failed, degrading", e)
                initFailed = true
                null
            }
        }
    }

    private fun isHotfixProxyApplication(app: Application): Boolean {
        val name = app.javaClass.name
        return name.contains("tinker", ignoreCase = true) ||
            name.contains("rfix", ignoreCase = true) ||
            name.contains("ProxyApplication", ignoreCase = true) ||
            hasSuperclassNamed(app.javaClass, "com.tencent.rfix.loader.app.RFixProxyApplication")
    }

    private fun hasSuperclassNamed(clazz: Class<*>, targetName: String): Boolean {
        var current: Class<*>? = clazz
        while (current != null) {
            if (current.name == targetName) return true
            current = current.superclass
        }
        return false
    }

    // ========================================================================
    // 诊断工具：nativeLibraries 缓存分析
    // ========================================================================

    /**
     * 诊断 ClassLoader 的 nativeLibraries 缓存状态。
     *
     * 检查指定库是否已在缓存中，以及 /proc/self/maps 中是否有对应内存映射。
     * 如果"缓存中有但 maps 中没有"，说明 Android 16 缓存污染假说成立。
     */
    private fun diagnoseNativeLibCache(loader: ClassLoader, libKeyword: String, label: String) {
        // 1. 检查 nativeLibraries 缓存
        var cacheEntries = listOf<String>()
        var cacheSize = -1
        var foundInCache = false
        try {
            val field = ClassLoader::class.java.getDeclaredField("nativeLibraries")
            field.isAccessible = true
            val nativeLibraries = field.get(loader)
            if (nativeLibraries is Vector<*>) {
                cacheSize = nativeLibraries.size
                cacheEntries = nativeLibraries.mapNotNull { lib ->
                    try {
                        val nameField = lib?.javaClass?.getDeclaredField("name")
                        nameField?.isAccessible = true
                        nameField?.get(lib) as? String
                    } catch (_: Exception) { null }
                }
                foundInCache = cacheEntries.any { it.contains(libKeyword) }
            }
        } catch (e: Throwable) {
            logD("  DIAG[$label]: nativeLibraries reflection failed: ${e.message}")
        }

        // 2. 检查 /proc/self/maps
        var mapsHasLib = false
        var mapsLines = listOf<String>()
        try {
            val maps = java.io.File("/proc/self/maps").readLines()
            mapsLines = maps.filter { it.contains(libKeyword) }
            mapsHasLib = mapsLines.isNotEmpty()
        } catch (_: Exception) {}

        // 3. 诊断结论
        val verdict = when {
            foundInCache && mapsHasLib -> "LOADED_NORMALLY"
            foundInCache && !mapsHasLib -> "CACHE_POLLUTED (缓存有但内存无映射 ← 假说成立!)"
            !foundInCache && mapsHasLib -> "MAPS_ONLY (内存有映射但缓存无条目 ← 异常)"
            !foundInCache && !mapsHasLib -> "NOT_LOADED (未加载 ← 预期状态)"
            else -> "UNKNOWN"
        }

        logD("  DIAG[$label]: classLoader=${loader.javaClass.name}")
        logD("  DIAG[$label]: nativeLibraries cacheSize=$cacheSize, foundInCache=$foundInCache")
        if (cacheEntries.isNotEmpty()) {
            logD("  DIAG[$label]: cache entries: ${cacheEntries.take(20)}")
        }
        logD("  DIAG[$label]: /proc/self/maps has $libKeyword: $mapsHasLib (${mapsLines.size} lines)")
        if (mapsLines.isNotEmpty()) {
            mapsLines.take(5).forEach { logD("  DIAG[$label]:   maps: $it") }
        }
        logD("  DIAG[$label]: *** VERDICT: $verdict ***")

        if (foundInCache && !mapsHasLib) {
            logE("  DIAG[$label]: CACHE_POLLUTED — 缓存污染假说确认！" +
                " 库 '${libKeyword}' 在 nativeLibraries 中有条目，" +
                "但 /proc/self/maps 中无对应映射。" +
                " 后续 System.load/loadLibrary 将静默返回而不实际加载。")
        }
    }

    /**
     * 清除 ClassLoader 的 nativeLibraries 缓存中指定库的条目。
     * 如果缓存污染假说成立，清除后重新调用 System.load 即可正常加载。
     */
    private fun clearNativeLibraryCache(loader: ClassLoader, libKeyword: String): Int {
        var removed = 0
        try {
            // Android 16 可能改了字段名，尝试多个候选
            val candidateFields = listOf("nativeLibraries", "mNativeLibraries", "nativeLibraryCache", "loadedLibraries")
            var field: java.lang.reflect.Field? = null
            for (name in candidateFields) {
                try {
                    field = ClassLoader::class.java.getDeclaredField(name)
                    field.isAccessible = true
                    logD("  clearNativeLibCache: found field '$name'")
                    break
                } catch (_: NoSuchFieldException) {
                    if (envExceptionCheck()) envExceptionClear()
                }
            }

            if (field == null) {
                // 枚举所有字段
                logW("  clearNativeLibCache: no known field found, enumerating ClassLoader fields:")
                for (f in ClassLoader::class.java.declaredFields) {
                    logW("    ${f.name} : ${f.type.name}")
                }
                return 0
            }

            val nativeLibraries = field.get(loader)
            if (nativeLibraries is Vector<*>) {
                for (i in nativeLibraries.size - 1 downTo 0) {
                    val lib = nativeLibraries[i]
                    val name = try {
                        val nameField = lib?.javaClass?.getDeclaredField("name")
                        nameField?.isAccessible = true
                        nameField?.get(lib) as? String
                    } catch (_: Exception) { null }
                    if (name != null && name.contains(libKeyword)) {
                        nativeLibraries.removeAt(i)
                        removed++
                        logD("  clearNativeLibCache: removed '$name' at index $i")
                    }
                }
            }
            logD("  clearNativeLibCache: removed $removed entries matching '$libKeyword'")
        } catch (e: Throwable) {
            logW("  clearNativeLibCache failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return removed
    }

    private fun envExceptionCheck(): Boolean = false // placeholder
    private fun envExceptionClear() {} // placeholder

    private fun loadGuestLibraryViaInjectedHelper(loader: ClassLoader, libName: String): Boolean {
        try {
            val helperClass = Class.forName("com.multiapp.NativeLibLoader", true, loader)
            val helperMethod = helperClass.getDeclaredMethod("loadLibrary", String::class.java)
            helperMethod.isAccessible = true
            helperMethod.invoke(null, libName)
            logD("  preloadPackerLib: $libName loaded via NativeLibLoader")
            return true
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            logD("  preloadPackerLib: NativeLibLoader.loadLibrary($libName) failed: ${cause.javaClass.simpleName}: ${cause.message}")
        }

        try {
            val helperClass = Class.forName("com.multiapp.JiaguLoader", true, loader)
            val helperMethod = helperClass.getDeclaredMethod("loadLibrary")
            helperMethod.isAccessible = true
            helperMethod.invoke(null)
            logD("  preloadPackerLib: $libName loaded via JiaguLoader")
            return true
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            logD("  preloadPackerLib: JiaguLoader.loadLibrary($libName) failed: ${cause.javaClass.simpleName}: ${cause.message}")
        }

        return false
    }

    /**
     * 组合操作：诊断 → 清除缓存 → 重新加载 → 最终诊断。
     * 用于验证缓存清除是否能修复 System.loadLibrary 静默失败问题。
     */
    private fun diagnoseAndRetryLibLoad(loader: ClassLoader, libPath: String, libKeyword: String) {
        logD("  === diagnoseAndRetryLibLoad: $libKeyword ===")

        diagnoseNativeLibCache(loader, libKeyword, "PRE-CLEAR")

        val removed = clearNativeLibraryCache(loader, libKeyword)
        if (removed > 0) {
            logD("  diagnoseAndRetryLibLoad: cleared $removed stale cache entries, retrying...")
        }

        diagnoseNativeLibCache(loader, libKeyword, "POST-CLEAR")

        val t0 = System.currentTimeMillis()
        try {
            System.load(libPath)
            val elapsed = System.currentTimeMillis() - t0
            logD("  diagnoseAndRetryLibLoad: System.load OK in ${elapsed}ms")
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - t0
            logW("  diagnoseAndRetryLibLoad: System.load FAILED in ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message}")
        }

        diagnoseNativeLibCache(loader, libKeyword, "FINAL")
    }
}

