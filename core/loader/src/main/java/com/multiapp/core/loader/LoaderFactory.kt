package com.multiapp.core.loader

import android.app.AppComponentFactory
import android.app.Application
import android.content.ContentProvider
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.multiapp.core.hook.NativeHookBridge
import dalvik.system.PathClassLoader
import java.io.File
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

        private fun logW(msg: String) {
            val ts = System.currentTimeMillis()
            val line = "[$ts] W $msg"
            synchronized(debugLog) { debugLog.add(line) }
            Log.w(TAG, msg)
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

    /** 原始 APK 路径 */
    @Volatile
    private var originApkPath: String? = null

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

    /** 原始包名 */
    @Volatile
    private var guestPackageName: String? = null

    /** Stub package name currently running in this process. */
    @Volatile
    private var stubPackageName: String? = null

    private val initLock = Any()

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): android.app.Activity {
        logD("instantiateActivity: $className")
        ensureClassLoaderSwapped(cl)
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

        return try {
            val clazz = realCl.loadClass(className)
            logD("  loaded: ${clazz.name}, creating instance...")
            val provider = clazz.getDeclaredConstructor().newInstance() as ContentProvider
            logD("  provider created OK: ${provider.javaClass.name}")
            // 包装 provider，在 onCreate 失败时优雅降级
            SafeProviderWrapper(provider)
        } catch (e: Exception) {
            logE("instantiateProvider FAILED for $className, falling back to system", e)
            try {
                val fallback = super.instantiateProvider(cl, className)
                logD("  fallback OK: ${fallback.javaClass.name}")
                SafeProviderWrapper(fallback)
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
        logD("=== POC LoaderFactory starting ===")
        logD("  Thread: ${Thread.currentThread().name}")
        logD("  ClassLoader: ${cl.javaClass.name}")
        logD("  ClassLoader parent: ${cl.parent?.javaClass?.name}")

        // 保底：安装 UncaughtExceptionHandler，确保崩溃前写日志到文件
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logE("UncaughtException on ${thread.name}", throwable)
            try { writeDebugLogToFile(cl) } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            // Step 0: Hidden API bypass — 必须在任何反射调用之前
            logD("Step 0: Hidden API bypass...")
            bypassHiddenApis()

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
            logD("  originalPkg=${config.originalPkg}, stubPkg=${config.stubPkg}")

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
            logD("Step 5: Installing nativeLoad hook...")
            // 标记 native 库已加载（libmultiapp-native.so 在 stub APK 的 lib/ 中，
            // 被 stub ClassLoader 加载，但 NativeHookBridge 的 init 块用 boot ClassLoader 检测不到）
            NativeHookBridge.markNativeLibLoaded()
            installNativeLoadHookIfAvailable()

            // 6. 替换 ClassLoader
            logD("Step 6: Swapping ClassLoader...")
            swapClassLoader(activityThread, appInfo, originApk, config, originalApk)
            logD("=== POC LoaderFactory complete ===")

        } catch (e: Exception) {
            logE("=== POC LoaderFactory FAILED ===", e)
            try { writeDebugLogToFile(cl) } catch (_: Exception) {}
            throw e
        }
    }

    private fun installNativeLoadHookIfAvailable() {
        try {
            val candidates = arrayOf(
                "com.stub.StubApp",
                "com.qihoo.util.StubApp",
                "com.stub.StubApplication",
                originApplicationClass
            ).filterNotNull().distinct().toTypedArray()
            val installed = NativeHookBridge.getInstance().hookRuntimeNativeLoad(candidates)
            logD("  Runtime.nativeLoad hook installed=$installed, fallbackCallers=${candidates.joinToString()}")
        } catch (e: Throwable) {
            logW("  Runtime.nativeLoad hook unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
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
        logD("swapClassLoader: originApk=${originApk.absolutePath}")

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
            val originInfo = pm.getPackageArchiveInfo(originApk.absolutePath,
                android.content.pm.PackageManager.GET_META_DATA or android.content.pm.PackageManager.GET_ACTIVITIES)
            if (originInfo != null) {
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
                originInfo.activities?.forEach { activityInfo ->
                    originActivityThemes[activityInfo.name] = activityInfo.theme
                }
                logD(
                    "  Origin themes: app=0x${Integer.toHexString(originApplicationThemeId)}, " +
                        "activities=${originActivityThemes.filterValues { it != 0 }.mapValues { "0x${Integer.toHexString(it.value)}" }}"
                )
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
        appInfo.publicSourceDir = originApk.absolutePath

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
        guestPackageName = config.originalPkg
        stubPackageName = config.stubPkg

        // 创建指向原始 APK 的 PathClassLoader
        // 使用原始 ClassLoader 的 parent 保留系统设置的中间 ClassLoader 层级
        val parentClassLoader = try {
            loadedApk.javaClass.getDeclaredField("mClassLoader")
                .apply { isAccessible = true }
                .get(loadedApk) as? ClassLoader
        } catch (_: Exception) { null }
            ?.parent ?: ClassLoader.getSystemClassLoader().parent
        logD("  parentClassLoader: ${parentClassLoader.javaClass.name}")
        val realGuestClassLoader = PathClassLoader(
            originApk.absolutePath,
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

        // 不预加载加固壳 native 库！
        // Android 禁止同一个 .so 被两个 ClassLoader 重复加载。
        // 让加固壳自己的 StubApp.load() 通过 System.loadLibrary("jiagu_vip") 加载。
        logD("  Skipping packer native preload (let packer load via System.loadLibrary)")

        // 但加固壳的 StubApp.load() 可能不调用 System.loadLibrary，直接调 JNI 方法。
        // 所以我们需要主动通过 guest ClassLoader 预加载加固库。
        // 关键：必须通过 guest ClassLoader 的 System 类调用，使库加载到 guest 命名空间。
        preloadPackerLibViaGuestClassLoader(realGuestClassLoader, originalApk?.absolutePath)

        // Stage 2: after the packer bootstrap, load business SDK libraries
        // through ART nativeLoad with guest ClassLoader ownership. This keeps
        // RegisterNatives bindings such as YWLoginManager.getInstance attached
        // to the real guest classes instead of the loader/stub namespace.
        preloadGuestRuntimeNativeLibraries(realGuestClassLoader)

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
                ai.publicSourceDir = originApk.absolutePath
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
                .set(loadedApk, originApk.absolutePath)
            logD("  Updated mAppDir/mResDir")
        } catch (e: Exception) {
            logW("  mAppDir/mResDir update failed (OK on some Android versions): ${e.message}")
        }

        rebuildLoadedApkResources(loadedApk, originApk)

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
    private fun preloadPackerLibViaGuestClassLoader(guestCl: ClassLoader, originalApkPath: String? = null) {
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

        // ── Step 2: dlopen + GOT hook + 手动 JNI_OnLoad ──
        // Android 16 上 ShadowHook inline hook 失败（errno=12），改用 GOT hook。
        // nativePreloadLibraries 内部: dlopen → GOT hook（过滤 /proc/self/maps）→ JNI_OnLoad。
        try {
            // 设置完整性校验重定向
            val modifiedApkPath = originApkPath
            if (modifiedApkPath != null && originalApkPath != null) {
                bridge.setIntegrityRedirect(modifiedApkPath, originalApkPath)
                logD("  preloadPackerLib: integrity redirect: $modifiedApkPath -> $originalApkPath")
            }

            // dlopen + GOT hook + JNI_OnLoad（GOT hook 在 nativePreloadLibraries 内部调用）
            val count = bridge.preloadNativeLibraries(listOf(jiaguFile.absolutePath))
            bridge.clearIntegrityRedirect()

            if (count > 0) {
                logD("  preloadPackerLib: dlopen + JNI_OnLoad OK")
            } else {
                logW("  preloadPackerLib: dlopen + JNI_OnLoad failed")
            }
        } catch (e: Throwable) {
            bridge.clearIntegrityRedirect()
            logD("  preloadPackerLib: GOT hook + dlopen failed: ${e.javaClass.simpleName}: ${e.message}")
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
                loadMethod.invoke(null)
                logD("  preloadPackerLib: StubApp.load() invoked — DEX should be decrypted")
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

        // ── Step 4: 兜底 — 手动注册 native 方法 stub ──
        // 如果 FindClass hook + dlopen 成功，RegisterNatives 已注册了真实实现，
        // registerStubMethods 不会覆盖已注册的方法。
        logD("  preloadPackerLib: registering stub methods as fallback")
        try {
            val registered = bridge.registerStubMethods(guestCl, targetClass)
            logD("  preloadPackerLib: stub methods registered: $registered")
        } catch (e: Throwable) {
            logW("  preloadPackerLib: registerStubMethods exception: ${e.message}")
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

    private fun preloadGuestRuntimeNativeLibraries(realGuestClassLoader: ClassLoader) {
        val libDirPath = originNativeLibDir
        if (libDirPath == null) {
            logD("  Stage2 native preload skipped: no origin lib dir")
            return
        }
        val libDir = File(libDirPath)
        if (!libDir.isDirectory) {
            logD("  Stage2 native preload skipped: not a directory: $libDirPath")
            return
        }

        val bridge = NativeHookBridge.getInstance()
        preloadNativeForClass(
            bridge = bridge,
            classLoader = realGuestClassLoader,
            className = "com.yuewen.ywlogin.login.YWLoginManager",
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
    }

    private fun preloadNativeForClass(
        bridge: NativeHookBridge,
        classLoader: ClassLoader,
        className: String,
        libDir: File,
        preferredLibraries: List<String>
    ) {
        val callerClass = try {
            Class.forName(className, false, classLoader)
        } catch (e: Throwable) {
            logW("  Stage2 native preload: caller class not found: $className (${e.message})")
            return
        }

        val allLibs = libDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?: emptyList()
        if (allLibs.isEmpty()) {
            logD("  Stage2 native preload: no .so files in ${libDir.absolutePath}")
            return
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
                methodName = "getInstance",
                libs = candidates
            )
        ) {
            return
        }

        val fallbackLibs = allLibs
            .filterNot { lib -> candidates.any { it.absolutePath == lib.absolutePath } }
            .sortedWith(compareBy<File> { nativeLoadPriority(it.name) }.thenBy { it.name.lowercase() })
        if (fallbackLibs.isEmpty()) {
            logW("  Stage2 native preload failed: no fallback libs for $className.getInstance")
            return
        }

        logW("  Stage2 native preload: named candidates did not bind $className.getInstance; trying ${fallbackLibs.size} fallback libs")
        if (!tryBindNativeMethod(
                bridge = bridge,
                classLoader = classLoader,
                callerClass = callerClass,
                className = className,
                methodName = "getInstance",
                libs = fallbackLibs
            )
        ) {
            logW("  Stage2 native preload failed: $className.getInstance remains unbound after ${allLibs.size} libs")
        }
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
            if (ok && isNativeMethodBound(callerClass, methodName)) {
                logD("  Stage2 native method bound after ${lib.name}: $className.$methodName")
                return true
            }
        }
        return false
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
            val oldResources = loadedApk.javaClass
                .getDeclaredField("mResources")
                .apply { isAccessible = true }
                .get(loadedApk) as? Resources

            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
            val result = addAssetPath.invoke(assets, originApk.absolutePath) as Int
            logD("  New AssetManager.addAssetPath result: $result for ${originApk.absolutePath}")

            val baseResources = oldResources ?: Resources.getSystem()
            val newResources = Resources(
                assets,
                baseResources.displayMetrics,
                baseResources.configuration
            )
            originResources = newResources

            loadedApk.javaClass
                .getDeclaredField("mResources")
                .apply { isAccessible = true }
                .set(loadedApk, newResources)

            logD("  Replaced LoadedApk.mResources with origin Resources")
            logD("  Origin resources mounted before activity creation")
        } catch (e: Exception) {
            logW("  Rebuilding origin Resources failed: ${e.message}")
        }
    }

    private fun applyActivityThemeIfKnown(activity: android.app.Activity, className: String) {
        val resources = originResources ?: return
        try {
            val themeId = resolveActivityTheme(className)
            if (themeId != 0) {
                activity.setTheme(themeId)
                logD("  Activity theme applied: $className -> 0x${Integer.toHexString(themeId)}")
                probeOriginTheme(resources, themeId)
            } else {
                logW("  Activity theme is 0 for $className")
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
        if (output.exists()) {
            logD("Origin APK already extracted")
            ensureReadOnly(output)
            return output
        }
        ZipFile(stubApkPath).use { zip ->
            val entry = zip.getEntry("assets/origin.apk")
                ?: throw IllegalStateException("assets/origin.apk not found in stub APK")
            zip.getInputStream(entry).use { input ->
                output.outputStream().use { out -> input.copyTo(out) }
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
            }

            val existingSoCount = outputDir.listFiles()?.count { it.isFile && it.extension == "so" } ?: 0
            if (marker.exists() && marker.readText() == markerText && existingSoCount >= entries.size) {
                logD("Origin native libs already extracted for $abi")
                ensureReadOnlyTree(outputDir)
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
                ensureReadOnly(outFile)
                extracted++
                logD("  Extracted origin native lib: ${outFile.name}")
            }

            marker.writeText(markerText)
            ensureReadOnly(marker)
            ensureReadOnlyTree(outputDir)
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
    private class SafeProviderWrapper(private val delegate: ContentProvider) : ContentProvider() {

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
                delegate.attachInfo(context, info)
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
}

