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
import com.multiapp.core.identity.IdentityConfig
import com.multiapp.core.identity.PackageIdentityHook
import com.multiapp.core.identity.ProcFsHook
import com.multiapp.core.identity.SignatureBypass
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

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        Timber.d("LoaderFactory: instantiateApplication for $className")

        // 1. 获取 ApplicationInfo (不依赖 Context)
        val activityThread = getActivityThread()
        val appInfo = getBoundAppInfo(activityThread)
        val stubApkPath = appInfo.sourceDir
        val dataDir = appInfo.dataDir

        // 2. 读取配置
        val config = readConfigFromAssets(stubApkPath)

        // 3. 解压原始 APK
        val originApk = extractOriginApk(stubApkPath, dataDir, config)

        // 3.5 解压 patched DEX (如果有) 替换原始 APK 中的 DEX
        extractPatchedDex(stubApkPath, originApk, dataDir)

        // 4. 替换 LoadedApk
        LoadedApkSwapper.swap(activityThread, originApk, config)

        // 5. 安装身份 Hook
        installIdentityHooks(config)

        // 6. 安装签名绕过
        installSignatureBypass(config)

        // 7. 重置 appComponentFactory
        appInfo.appComponentFactory = "android.app.AppComponentFactory"

        return super.instantiateApplication(cl, className)
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
            return Gson().fromJson(json, StubConfig::class.java)
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
