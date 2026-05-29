package com.multiapp.core.loader

import android.app.AppComponentFactory
import android.app.Application
import android.content.ContentProvider
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.google.gson.Gson
import com.multiapp.core.identity.ActivityManagerHook
import com.multiapp.core.identity.BuildFieldSpoof
import com.multiapp.core.identity.ContentProviderHook
import com.multiapp.core.identity.DeviceIdentityHook
import com.multiapp.core.identity.DlopenHook
import com.multiapp.core.identity.FileSystemHook
import com.multiapp.core.identity.IdentityConfig
import com.multiapp.core.identity.PackageIdentityHook
import com.multiapp.core.identity.ProcFsHook
import com.multiapp.core.identity.SignatureBypass
import com.multiapp.core.hook.HookEngine
import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.hook.antidetection.PackerDetectionBypass
import com.multiapp.core.manifest.StubConfig
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * Stub 的 AppComponentFactory 入口 (借鉴 LSPatch)
 * 在 Application.attachBaseContext() 之前执行注入
 *
 * 时序 (关键 — 所有拦截必须在壳运行前就位):
 *   T0: instantiateApplication() 被系统回调
 *   T1: 初始化 shadowhook + PLT/GOT Hook (open/fopen/read/ptrace/__system_property_get)
 *       + 配置 native 层路径重定向和 /proc/self 伪装
 *   T2: 替换 ClassLoader + LoadedApk → 安装 Java 层身份 Hook → 重置 appComponentFactory
 *   T3: 系统继续创建 Application → 360 壳执行 → 所有检测被拦截
 *
 * Stub AndroidManifest.xml 声明:
 * <application android:appComponentFactory="com.multiapp.core.loader.LoaderFactory">
 */
class LoaderFactory : AppComponentFactory() {

    companion object {
        private const val TAG = "LoaderFactory"
    }

    /** 是否已完成初始化（LoadedApk 替换 + Hook 安装）*/
    @Volatile
    private var initialized = false

    /** 初始化后持有的原始 ClassLoader */
    private var originClassLoader: ClassLoader? = null

    /**
     * 系统实例化 ContentProvider 时回调。
     *
     * 关键时序：instantiateProvider() 在 instantiateApplication() **之前**被调用！
     * Android 启动顺序：handleBindApplication → installContentProviders → instantiateProvider
     *                                                      → instantiateApplication
     * 所以 Provider 实例化时 LoadedApk 还没替换，ClassLoader 里只有 loader 的 classes.dex。
     *
     * 修复：在第一个 Provider 实例化前完成初始化，然后用原始 ClassLoader 加载真正的 Provider。
     */
    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider {
        Timber.tag(TAG).d("instantiateProvider for $className")
        return try {
            ensureInitialized(cl)
            val providerCl = originClassLoader ?: cl
            super.instantiateProvider(providerCl, className)
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "instantiateProvider failed for $className, trying default")
            super.instantiateProvider(cl, className)
        }
    }

    /**
     * 系统创建 Application 时回调。
     *
     * 时序: instantiateProvider() 已先执行，初始化已完成。
     * 此处只需确保初始化（防御性），然后用原始 ClassLoader 创建原始 Application。
     */
    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        Timber.tag(TAG).d("instantiateApplication for $className")

        return try {
            ensureInitialized(cl)
            val appCl = originClassLoader ?: cl
            super.instantiateApplication(appCl, className)
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Fatal: instantiateApplication failed, falling back to default")
            super.instantiateApplication(cl, className)
        }
    }

    /**
     * 确保初始化只执行一次（双重检查锁定）。
     * 初始化内容：读取配置 → 替换 LoadedApk → 安装 Hook。
     */
    private fun ensureInitialized(cl: ClassLoader) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            doInitialize(cl)
            initialized = true
        }
    }

    /**
     * 执行完整的初始化流程（原 doInstantiateApplication 的逻辑）。
     */
    private fun doInitialize(cl: ClassLoader) {
        Timber.tag(TAG).d("T0: initializing (from ${Thread.currentThread().stackTrace[2].methodName})...")

        // ─── T0: 获取 ApplicationInfo + 读取配置 ───
        val activityThread = getActivityThread()
        val appInfo = getBoundAppInfo(activityThread)
        val stubApkPath = appInfo.sourceDir
        val dataDir = appInfo.dataDir

        val config = readConfigFromAssets(stubApkPath)

        // 解压原始 APK
        val originApk = extractOriginApk(stubApkPath, dataDir, config)

        // 解压 patched DEX (如果有)
        extractPatchedDex(stubApkPath, originApk, dataDir)

        // ─── T1: Native 层拦截 — shadowhook PLT/GOT Hook ───
        // 必须在 ClassLoader 替换前完成!
        // 360 壳的 libsec.so 之后调用 open/fopen/read/ptrace 时
        // 会被劫持到我们的 hook 函数
        Timber.tag(TAG).d("T1: initializing native hooks via NativeHookBridge.setupForLoader...")
        val di = config.deviceIdentity
        val nativeOk = try {
            NativeHookBridge.setupForLoader(
                config.originalPackageName,
                mapOf(
                    "ro.product.model" to di.buildModel,
                    "ro.product.manufacturer" to di.buildManufacturer,
                    "ro.build.fingerprint" to di.buildFingerprint,
                    "ro.product.brand" to di.buildBrand,
                    "ro.product.device" to di.buildDevice,
                    "ro.product.name" to di.buildProduct
                )
            )
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "T1: NativeHookBridge unavailable (no .so?), skipping native hooks")
            false
        }

        if (nativeOk) {
            Timber.tag(TAG).i("T1: native hooks ready — shadowhook + /proc/self + property spoofing active")
        } else {
            Timber.tag(TAG).w("T1: native hooks not available, continuing with Java hooks only")
        }

        // ─── T2: 替换 ClassLoader + LoadedApk ───
        // 系统认为进程就是目标 app
        Timber.tag(TAG).d("T2: swapping LoadedApk...")
        val swappedClassLoader = LoadedApkSwapper.swap(activityThread, originApk, config)
        this.originClassLoader = swappedClassLoader

        // T2: 初始化 LSPlant (使用原始 APK 的 ClassLoader)
        // 必须在所有 Java Hook 之前完成
        Timber.tag(TAG).d("T2: initializing LSPlant with origin ClassLoader...")
        val hookEngine = HookEngine()
        val lsplantOk = hookEngine.initLsplant(swappedClassLoader)
        if (lsplantOk) {
            Timber.tag(TAG).i("T2: LSPlant initialized successfully")
        } else {
            Timber.tag(TAG).w("T2: LSPlant init failed — Java method hooks will not work")
        }

        // T2: 安装加固壳检测绕过 (Class.forName / ClassLoader.loadClass 拦截)
        // 必须在壳代码执行前安装，阻止壳发现 hook 框架类
        // 自动检测壳类型，不再硬编码
        Timber.tag(TAG).d("T2: installing packer detection bypass...")
        PackerDetectionBypass.apply(hookEngine, apkPath = originApk.absolutePath)

        Timber.tag(TAG).d("T2: installing Java identity hooks...")
        installIdentityHooks(config, hookEngine)

        // T2: 安装签名绕过 Hook (从 origin.apk 预读原始签名)
        Timber.tag(TAG).d("T2: installing signature bypass...")
        installSignatureBypass(config, hookEngine, originApk.absolutePath)

        // T2: 重置 appComponentFactory 为默认值
        // 加固壳在 Application.onCreate() 之后才执行检测，此时 Hook 已安装
        appInfo.appComponentFactory = "android.app.AppComponentFactory"
        Timber.tag(TAG).d("T2: appComponentFactory reset to default")

        Timber.tag(TAG).i("Initialization complete — all hooks installed, shell will run with intercepted env")
    }

    private fun getActivityThread(): Any {
        Timber.d("LoaderFactory: getActivityThread via reflection")
        val clazz = Class.forName("android.app.ActivityThread")
        val method = clazz.getDeclaredMethod("currentActivityThread")
        method.isAccessible = true
        return method.invoke(null)
            ?: throw IllegalStateException("ActivityThread.currentActivityThread() returned null")
    }

    private fun getBoundAppInfo(activityThread: Any): ApplicationInfo {
        Timber.d("LoaderFactory: getBoundAppInfo via reflection")
        val mBound = activityThread.javaClass
            .getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
            .get(activityThread)
        return mBound.javaClass
            .getDeclaredField("appInfo")
            .apply { isAccessible = true }
            .get(mBound) as ApplicationInfo
    }

    private fun readConfigFromAssets(stubApkPath: String): StubConfig {
        Timber.d("LoaderFactory: readConfigFromAssets from $stubApkPath")
        ZipFile(stubApkPath).use { zip ->
            val entry = zip.getEntry("assets/multiapp_config.json")
                ?: throw IllegalStateException("assets/multiapp_config.json not found in stub APK")
            val json = zip.getInputStream(entry).bufferedReader().readText()
            Timber.d("LoaderFactory: config JSON loaded (${json.length} chars)")
            val config = Gson().fromJson(json, StubConfig::class.java)
                ?: throw IllegalStateException("Failed to parse multiapp_config.json")
            // Validate critical fields are present
            require(config.instanceId.isNotEmpty()) { "Config missing instanceId" }
            require(config.stubPackageName.isNotEmpty()) { "Config missing stubPackageName" }
            require(config.originalPackageName.isNotEmpty()) { "Config missing originalPackageName" }
            require(config.launchActivity.isNotEmpty()) { "Config missing launchActivity" }
            return config
        }
    }

    private fun extractOriginApk(stubApkPath: String, dataDir: String, config: StubConfig): File {
        Timber.d("LoaderFactory: extractOriginApk for ${config.originalPackageName}")
        val outputDir = File(dataDir, "cache/origin")
        outputDir.mkdirs()
        val output = File(outputDir, "base.apk")
        if (output.exists()) {
            Timber.d("LoaderFactory: origin APK already extracted at ${output.absolutePath}")
            return output
        }
        ZipFile(stubApkPath).use { zip ->
            val entry = zip.getEntry("assets/origin.apk")
                ?: throw IllegalStateException("assets/origin.apk not found in stub APK")
            zip.getInputStream(entry).use { input ->
                output.outputStream().use { out -> input.copyTo(out) }
            }
        }
        Timber.d("LoaderFactory: extracted origin APK to ${output.absolutePath}")
        return output
    }

    /**
     * 从 Stub APK 解压 patched DEX 文件到原始 APK 目录
     *
     * patched DEX 是加固壳检测代码已被 dexlib2 删除的 DEX 文件。
     * 解压后替换原始 APK 中的对应 DEX，使加固壳的检测方法变成空实现。
     */
    private fun extractPatchedDex(stubApkPath: String, originApk: File, dataDir: String) {
        try {
            ZipFile(stubApkPath).use { zip ->
                val patchedEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("assets/patched/") && it.name.endsWith(".dex") }
                    .toList()

                if (patchedEntries.isEmpty()) {
                    Timber.d("LoaderFactory: no patched DEX files found, skipping")
                    return
                }

                // 解压到 origin APK 所在目录
                val originDir = originApk.parentFile ?: return
                for (entry in patchedEntries) {
                    val fileName = entry.name.removePrefix("assets/patched/")
                    val targetFile = File(originDir, fileName)
                    zip.getInputStream(entry).use { input ->
                        targetFile.outputStream().use { out -> input.copyTo(out) }
                    }
                    Timber.d("LoaderFactory: extracted patched DEX: $fileName")
                }
                Timber.d("LoaderFactory: ${patchedEntries.size} patched DEX files extracted")
            }
        } catch (e: Exception) {
            Timber.e(e, "LoaderFactory: failed to extract patched DEX, continuing with original")
        }
    }

    /**
     * 安装 Java 层身份 Hook (LSPlant)。
     *
     * 8 个 Hook 顺序执行，虽然它们之间没有数据依赖，但无法并行化:
     * - LSPlant 内部修改 ART Method 结构，非线程安全
     * - HookEngine.hookMethod() 的去重检查 (containsKey + put) 非原子操作
     * - 部分 Hook 使用反射修改静态字段 (如 ApplicationInfo.packageName)，非线程安全
     * - 此方法运行在主线程 (Application 创建前)，并行化不会减少阻塞时间
     *
     * @see <a href="https://github.com/LSPosed/LSPlant">LSPlant thread safety</a>
     */
    private fun installIdentityHooks(config: StubConfig, hookEngine: HookEngine) {
        Timber.d("LoaderFactory: installIdentityHooks for instance=${config.instanceId}")
        val identityConfig = config.toIdentityConfig()
        PackageIdentityHook.apply(identityConfig, hookEngine)
        DeviceIdentityHook(hookEngine).apply(identityConfig, hookEngine)
        BuildFieldSpoof.apply(identityConfig, hookEngine)
        FileSystemHook.apply(identityConfig, hookEngine)
        ProcFsHook.apply(identityConfig, hookEngine)
        ContentProviderHook.apply(identityConfig, hookEngine)
        ActivityManagerHook.apply(identityConfig, hookEngine)
        DlopenHook.apply(identityConfig, hookEngine)
    }

    private fun installSignatureBypass(config: StubConfig, hookEngine: HookEngine, originApkPath: String) {
        Timber.d("LoaderFactory: installSignatureBypass for instance=${config.instanceId}")
        val identityConfig = config.toIdentityConfig()
        SignatureBypass(hookEngine, originApkPath).apply(identityConfig, hookEngine)
    }

    /**
     * StubConfig -> IdentityConfig 映射
     */
    private fun StubConfig.toIdentityConfig(): IdentityConfig {
        val di = this.deviceIdentity
        return IdentityConfig(
            instanceId = this.instanceId,
            stubPackageName = this.stubPackageName,
            originalPackageName = this.originalPackageName,
            authorityMap = this.authorityMap,
            imei = di.imei,
            androidId = di.androidId,
            macAddress = di.macAddress,
            serial = di.serial,
            buildModel = di.buildModel,
            buildManufacturer = di.buildManufacturer,
            buildFingerprint = di.buildFingerprint,
            buildBrand = di.buildBrand,
            buildDevice = di.buildDevice,
            buildProduct = di.buildProduct,
            versionRelease = di.versionRelease,
            sdkInt = di.sdkInt
        )
    }
}
