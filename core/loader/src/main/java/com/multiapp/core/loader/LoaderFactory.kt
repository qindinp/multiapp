package com.multiapp.core.loader

import android.app.AppComponentFactory
import android.app.Application
import android.content.ContentProvider
import android.util.Log
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
    }

    @Volatile
    private var classLoaderReady = false

    @Volatile
    private var guestClassLoader: ClassLoader? = null

    private val initLock = Any()

    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider {
        Log.d(TAG, "instantiateProvider: $className")
        ensureClassLoaderSwapped(cl)
        val realCl = guestClassLoader ?: cl
        return try {
            val clazz = realCl.loadClass(className)
            clazz.getDeclaredConstructor().newInstance() as ContentProvider
        } catch (e: Exception) {
            Log.e(TAG, "instantiateProvider failed, falling back to system", e)
            super.instantiateProvider(cl, className)
        }
    }

    override fun instantiateApplication(cl: ClassLoader, className: String): Application {
        Log.d(TAG, "instantiateApplication: $className")
        ensureClassLoaderSwapped(cl)
        val realCl = guestClassLoader ?: cl
        return try {
            val appClass = realCl.loadClass(className)
            appClass.getDeclaredConstructor().newInstance() as Application
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: cannot create Application $className", e)
            throw RuntimeException("LoaderFactory POC failed: ${e.message}", e)
        }
    }

    private fun ensureClassLoaderSwapped(cl: ClassLoader) {
        if (classLoaderReady) return
        synchronized(initLock) {
            if (classLoaderReady) return
            try {
                initializeInternal(cl)
                classLoaderReady = true
                Log.d(TAG, "ClassLoader swap complete!")
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                throw RuntimeException("LoaderFactory POC failed: ${e.message}", e)
            }
        }
    }

    private fun initializeInternal(cl: ClassLoader) {
        Log.d(TAG, "=== POC LoaderFactory starting ===")

        // 1. 获取 ActivityThread
        val activityThread = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)!!
        Log.d(TAG, "Got ActivityThread")

        // 2. 获取 ApplicationInfo
        val mBound = activityThread.javaClass
            .getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
            .get(activityThread)
        val appInfo = mBound.javaClass
            .getDeclaredField("appInfo")
            .apply { isAccessible = true }
            .get(mBound) as android.content.pm.ApplicationInfo
        val stubApkPath = appInfo.sourceDir
        val dataDir = appInfo.dataDir
        Log.d(TAG, "Stub APK: $stubApkPath, dataDir: $dataDir")

        // 3. 从 Stub APK assets 读取最小配置 (不用 Gson)
        val config = readConfig(stubApkPath)
        Log.d(TAG, "Config: originalPkg=${config.originalPkg}, stubPkg=${config.stubPkg}")

        // 4. 解压 origin.apk
        val originApk = extractOriginApk(stubApkPath, dataDir)
        Log.d(TAG, "Origin APK extracted: ${originApk.absolutePath}, size=${originApk.length()}")

        // 5. 替换 LoadedApk 的 ClassLoader (不调任何隐藏 API)
        swapClassLoader(activityThread, appInfo, originApk, config)
        Log.d(TAG, "=== POC LoaderFactory complete ===")
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
        config: PocConfig
    ) {
        // 获取 LoadedApk 对象
        val mBound = activityThread.javaClass
            .getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
            .get(activityThread)

        val loadedApk = mBound.javaClass
            .getDeclaredField("info")
            .apply { isAccessible = true }
            .get(mBound)
            ?: throw IllegalStateException("LoadedApk is null")

        Log.d(TAG, "Got LoadedApk: ${loadedApk.javaClass.name}")

        // 修改 ApplicationInfo 路径
        appInfo.sourceDir = originApk.absolutePath
        appInfo.publicSourceDir = originApk.absolutePath

        val originLibDir = File(originApk.parentFile, "lib")
        if (originLibDir.isDirectory) {
            appInfo.nativeLibraryDir = originLibDir.absolutePath
        }

        Log.d(TAG, "Updated sourceDir -> ${appInfo.sourceDir}")

        // 创建指向原始 APK 的 PathClassLoader
        val bootClassLoader = ClassLoader.getSystemClassLoader().parent
        val newClassLoader = PathClassLoader(
            originApk.absolutePath,
            appInfo.nativeLibraryDir,
            bootClassLoader
        )
        Log.d(TAG, "Created PathClassLoader for ${originApk.absolutePath}")

        // 替换 LoadedApk.mClassLoader
        loadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .set(loadedApk, newClassLoader)

        Log.d(TAG, "Replaced LoadedApk.mClassLoader")

        // 更新资源路径
        try {
            loadedApk.javaClass.getDeclaredField("mAppDir")
                .apply { isAccessible = true }
                .set(loadedApk, originApk.absolutePath)
            loadedApk.javaClass.getDeclaredField("mResDir")
                .apply { isAccessible = true }
                .set(loadedApk, originApk.absolutePath)
            Log.d(TAG, "Updated mAppDir/mResDir")
        } catch (e: Exception) {
            Log.w(TAG, "mAppDir/mResDir update failed (OK on some Android versions): ${e.message}")
        }

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
                Log.d(TAG, "Updated mPackages for ${config.stubPkg} and ${config.originalPkg}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "mPackages update failed: ${e.message}")
        }

        guestClassLoader = newClassLoader
    }

    /**
     * 从 Stub APK assets/multiapp_config.json 读取配置
     * 最小解析：只提取 originalPackageName 和 stubPackageName
     */
    private fun readConfig(stubApkPath: String): PocConfig {
        ZipFile(stubApkPath).use { zip ->
            val entry = zip.getEntry("assets/multiapp_config.json")
                ?: throw IllegalStateException("assets/multiapp_config.json not found")
            val json = zip.getInputStream(entry).bufferedReader().readText()
            Log.d(TAG, "Config JSON: ${json.take(200)}...")

            // 简单正则提取，不用 Gson
            val originalPkg = json.regexFind("\"originalPackageName\"\\s*:\\s*\"([^\"]+)\"")
                ?: throw IllegalStateException("originalPackageName not found in config")
            val stubPkg = json.regexFind("\"stubPackageName\"\\s*:\\s*\"([^\"]+)\"")
                ?: throw IllegalStateException("stubPackageName not found in config")

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
        val outputDir = File(dataDir, "cache/origin")
        outputDir.mkdirs()
        val output = File(outputDir, "base.apk")
        if (output.exists()) {
            Log.d(TAG, "Origin APK already extracted")
            return output
        }
        ZipFile(stubApkPath).use { zip ->
            val entry = zip.getEntry("assets/origin.apk")
                ?: throw IllegalStateException("assets/origin.apk not found in stub APK")
            zip.getInputStream(entry).use { input ->
                output.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return output
    }

    /**
     * 最小配置类
     */
    data class PocConfig(
        val originalPkg: String,
        val stubPkg: String
    )
}
