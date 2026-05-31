package com.multiapp.core.loader

import android.app.AppComponentFactory
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.google.gson.Gson
import com.multiapp.core.identity.ActivityManagerHook
import com.multiapp.core.identity.BuildFieldSpoof
import com.multiapp.core.identity.ContentProviderHook
import com.multiapp.core.identity.DeviceIdentityHook
import com.multiapp.core.identity.DlopenHook
import com.multiapp.core.identity.FileSystemHook
import com.multiapp.core.model.IdentityConfig
import com.multiapp.core.identity.PackageIdentityHook
import com.multiapp.core.identity.ProcFsHook
import com.multiapp.core.identity.SignatureBypass
import com.multiapp.core.hook.AntiDetectionEngine
import com.multiapp.core.hook.DetectionLevel
import com.multiapp.core.hook.HookEngine
import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.common.ConfigEncryptor
import com.multiapp.core.manifest.StubConfig
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * Stub 的 AppComponentFactory 入口 (借鉴 LSPatch)
 * 在 Application.attachBaseContext() 之前执行注入
 *
 * Stub AndroidManifest.xml 声明:
 * <application android:appComponentFactory="com.multiapp.loader.LoaderFactory">
 */
class LoaderFactory : AppComponentFactory() {

    // 标记: 是否已完成 ClassLoader 替换 (ContentProvider 先于 Application 实例化)
    @Volatile
    private var classLoaderReady = false
    // 保存替换后的 ClassLoader, 供 instantiateProvider 使用
    @Volatile
    private var guestClassLoader: ClassLoader? = null

    // 一次性初始化锁
    private val initLock = Any()

    /**
     * ContentProvider 先于 Application 实例化。
     * 必须在第一个 Provider 创建前完成 ClassLoader 替换，
     * 否则 Provider 加载原始 app 的类时会 ClassNotFoundException。
     */
    override fun instantiateProvider(cl: ClassLoader, className: String): android.content.ContentProvider {
        Timber.d("LoaderFactory: instantiateProvider for $className")
        ensureClassLoaderSwapped(cl)
        // 用替换后的 ClassLoader 加载 Provider 类
        val realCl = guestClassLoader ?: cl
        return try {
            val clazz = realCl.loadClass(className)
            clazz.getDeclaredConstructor().newInstance() as android.content.ContentProvider
        } catch (e: Exception) {
            Timber.e(e, "LoaderFactory: failed to instantiate provider $className with real ClassLoader, falling back")
            super.instantiateProvider(cl, className)
        }
    }

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        Timber.d("LoaderFactory: instantiateApplication for $className")
        ensureClassLoaderSwapped(cl)

        return try {
            // 用 guestClassLoader 加载 Application 类
            val realCl = guestClassLoader ?: cl
            val appClass = realCl.loadClass(className)
            appClass.getDeclaredConstructor().newInstance() as Application
        } catch (e: Exception) {
            Timber.e(e, "LoaderFactory: FATAL — cannot initialize, stub will crash")
            throw RuntimeException("LoaderFactory initialization failed: ${e.message}", e)
        }
    }

    /**
     * 确保 ClassLoader 替换只执行一次 (由 instantiateProvider 或 instantiateApplication 触发)
     */
    private fun ensureClassLoaderSwapped(cl: ClassLoader) {
        if (classLoaderReady) return
        synchronized(initLock) {
            if (classLoaderReady) return
            try {
                initializeInternal(cl)
                classLoaderReady = true
            } catch (e: Exception) {
                Timber.e(e, "LoaderFactory: initialization failed")
                throw RuntimeException("LoaderFactory initialization failed: ${e.message}", e)
            }
        }
    }

    private fun initializeInternal(cl: ClassLoader) {
        // 1. 获取 ApplicationInfo (不依赖 Context)
        val activityThread = getActivityThread()
        val appInfo = getBoundAppInfo(activityThread)
        val stubApkPath = appInfo.sourceDir
        val dataDir = appInfo.dataDir

        // 2. 读取配置
        val config = readConfigFromAssets(stubApkPath)
        Timber.d("LoaderFactory: config loaded, original=${config.originalPackageName}, stub=${config.stubPackageName}")

        // 3. 初始化 NativeHookBridge（native 层 hook 必须最早就位）
        val nativeBridge = NativeHookBridge.getInstance()
        val nativeOk = nativeBridge.initNativeHooks(hostDataDir = dataDir)
        if (!nativeOk) {
            Timber.e("LoaderFactory: Native hooks FAILED to init — anti-detection will be incomplete")
        }
        // 立即 spoof, 消除 initNativeHooks 和 spoofProcSelf 之间的时序窗口
        // 在此窗口期间如果有代码读取 /proc/self/cmdline 会暴露 stub 包名
        nativeBridge.spoofProcSelf(android.os.Process.myPid(), config.originalPackageName)
        // 设置 native 层路径重定向基准
        nativeBridge.setupAppRedirections(
            config.originalPackageName,
            config.instanceId,
            "$dataDir/data/${config.stubPackageName}"
        )

        // 4. 解压原始 APK
        val originApk = extractOriginApk(stubApkPath, dataDir, config)
        require(originApk.exists() && originApk.length() > 0) {
            "Extracted origin APK is empty or missing: ${originApk.absolutePath}"
        }

        // 4.5 解压 patched DEX (如果有) 替换原始 APK 中的 DEX
        extractPatchedDex(stubApkPath, originApk, dataDir)

        // 5. 替换 LoadedApk（返回原始 APK 的 ClassLoader）
        guestClassLoader = LoadedApkSwapper.swap(activityThread, originApk, config)
        Timber.d("LoaderFactory: LoadedApk swapped, guestClassLoader=${guestClassLoader!!.javaClass.name}")

        // 5.5 初始化 LSPlant（用原始 APK 的 ClassLoader，确保 hook 目标类已加载）
        val hookEngine = HookEngine.getInstance()
        hookEngine.initLsplant(guestClassLoader!!)

        // 6. 安装身份 Hook
        installIdentityHooks(config)

        // 6.5 启用 AntiDetectionEngine（Root/模拟器/Xposed 检测绕过）
        val antiDetect = AntiDetectionEngine(hookEngine, nativeBridge)
        antiDetect.initialize()
        antiDetect.enableAntiDetection(config.instanceId, DetectionLevel.MODERATE)

        // 7. 安装签名绕过
        installSignatureBypass(config)

        // 8. 重置 appComponentFactory（防壳检测异常）
        appInfo.appComponentFactory = "android.app.AppComponentFactory"

        Timber.d("LoaderFactory: injection complete for ${config.originalPackageName}")
    }

    private fun getActivityThread(): Any {
        Timber.d("LoaderFactory: getActivityThread via reflection")
        val clazz = Class.forName("android.app.ActivityThread")
        val method = clazz.getDeclaredMethod("currentActivityThread")
        method.isAccessible = true
        return method.invoke(null)!!
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

            // 先解析为 Map, 检查是否有加密字段
            @Suppress("UNCHECKED_CAST")
            val configMap = Gson().fromJson(json, Map::class.java) as Map<String, Any?>

            // 解密敏感字段
            val decryptedMap = if (ConfigEncryptor.hasEncryptedFields(configMap)) {
                val stubPkg = configMap["stubPackageName"] as? String ?: ""
                val instanceId = configMap["instanceId"] as? String ?: ""
                ConfigEncryptor.decryptSensitiveFields(configMap, stubPkg, instanceId)
            } else {
                configMap
            }

            return Gson().fromJson(Gson().toJson(decryptedMap), StubConfig::class.java)
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

    private fun installIdentityHooks(config: StubConfig) {
        Timber.d("LoaderFactory: installIdentityHooks for instance=${config.instanceId}")
        val identityConfig = config.toIdentityConfig()
        PackageIdentityHook.apply(identityConfig)
        DeviceIdentityHook.apply(identityConfig)
        BuildFieldSpoof.apply(identityConfig)
        FileSystemHook.apply(identityConfig)
        ProcFsHook.apply(identityConfig)
        ContentProviderHook.apply(identityConfig)
        ActivityManagerHook.apply(identityConfig)
        DlopenHook.apply(identityConfig)
    }

    private fun installSignatureBypass(config: StubConfig) {
        Timber.d("LoaderFactory: installSignatureBypass for instance=${config.instanceId}")
        val identityConfig = config.toIdentityConfig()
        SignatureBypass.apply(identityConfig)
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
