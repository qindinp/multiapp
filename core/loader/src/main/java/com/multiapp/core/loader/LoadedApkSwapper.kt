package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import com.multiapp.core.manifest.StubConfig
import dalvik.system.PathClassLoader
import timber.log.Timber
import java.io.File
import java.net.URL
import java.util.Enumeration

/**
 * LoadedApk 替换核心 (借鉴 LSPatch)
 *
 * Android 16 策略变更: 不再调用 getPackageInfoNoCheck (CorePlatformApi, 无法绕过),
 * 而是直接修改现有 LoadedApk 的字段:
 *   - 替换 mClassLoader 为指向原始 APK 的 PathClassLoader
 *   - 修改 ApplicationInfo 中的 sourceDir/publicSourceDir/nativeLibraryDir
 *   - 清除 mClassLoader 缓存, 让系统重新初始化
 *
 * 这样做的风险比创建新 LoadedApk 稍高, 但避免了所有隐藏 API 调用。
 */
object LoadedApkSwapper {

    /**
     * 替换 LoadedApk 使系统加载原始 APK 的类和资源
     *
     * @param activityThread ActivityThread 实例
     * @param originApk 解压后的原始 APK 文件
     * @param config Stub 配置
     * @return 替换后的 ClassLoader (指向原始 APK)
     */
    fun swap(activityThread: Any, originApk: File, config: StubConfig): ClassLoader {
        Timber.d("LoadedApkSwapper: swapping to ${originApk.absolutePath}")

        // 1. 获取当前 LoadedApk
        val mBound = activityThread.javaClass
            .getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
            .get(activityThread)
            ?: throw IllegalStateException("mBoundApplication is null")

        val appInfo = mBound.javaClass
            .getDeclaredField("appInfo")
            .apply { isAccessible = true }
            .get(mBound) as? ApplicationInfo
            ?: throw IllegalStateException("appInfo is null or not ApplicationInfo")

        val infoField = mBound.javaClass.getDeclaredField("info")
            .apply { isAccessible = true }
        val loadedApk = infoField.get(mBound)
            ?: throw IllegalStateException("LoadedApk (info) is null")

        Timber.d("LoadedApkSwapper: got existing LoadedApk: ${loadedApk.javaClass.name}")

        // 2. 修改 ApplicationInfo 的路径指向原始 APK
        appInfo.sourceDir = originApk.absolutePath
        appInfo.publicSourceDir = originApk.absolutePath

        val originLibDir = File(originApk.parentFile, "lib")
        if (originLibDir.isDirectory) {
            appInfo.nativeLibraryDir = originLibDir.absolutePath
            Timber.d("LoadedApkSwapper: nativeLibraryDir updated to ${appInfo.nativeLibraryDir}")
        }

        Timber.d("LoadedApkSwapper: sourceDir updated to ${appInfo.sourceDir}")

        // 3. 创建指向原始 APK 的 PathClassLoader
        //    parent = 系统 BootClassLoader (通过 ClassLoader.getSystemClassLoader().parent)
        val bootClassLoader = ClassLoader.getSystemClassLoader().parent
        val realClassLoader = PathClassLoader(
            originApk.absolutePath,
            appInfo.nativeLibraryDir,
            bootClassLoader
        )
        Timber.d("LoadedApkSwapper: created PathClassLoader for ${originApk.absolutePath}")

        // 4. 用 StealthClassLoader 包装, 隐藏 ClassLoader 链中的 stub/multiapp 痕迹
        val stealthClassLoader = StealthClassLoader(realClassLoader, originApk.absolutePath)

        // 5. 替换 LoadedApk 的 mClassLoader
        loadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .set(loadedApk, stealthClassLoader)

        Timber.d("LoadedApkSwapper: replaced LoadedApk.mClassLoader with StealthClassLoader")

        // 6. 清除 LoadedApk 的 mAppDir / mResDir 使资源也指向原始 APK
        try {
            loadedApk.javaClass.getDeclaredField("mAppDir")
                .apply { isAccessible = true }
                .set(loadedApk, originApk.absolutePath)
            loadedApk.javaClass.getDeclaredField("mResDir")
                .apply { isAccessible = true }
                .set(loadedApk, originApk.absolutePath)
        } catch (e: Exception) {
            Timber.w(e, "LoadedApkSwapper: failed to update mAppDir/mResDir (may not exist on this Android version)")
        }

        // 7. 更新 ActivityThread.mPackages 映射
        try {
            @Suppress("UNCHECKED_CAST")
            val mPackages = activityThread.javaClass
                .getDeclaredField("mPackages")
                .apply { isAccessible = true }
                .get(activityThread) as? MutableMap<String, Any>
            if (mPackages != null) {
                // 确保 stub 和 original 包名都指向修改后的 LoadedApk
                val weakRef = java.lang.ref.WeakReference(loadedApk)
                mPackages[config.stubPackageName] = weakRef
                mPackages[config.originalPackageName] = weakRef
                Timber.d("LoadedApkSwapper: updated mPackages for ${config.stubPackageName} and ${config.originalPackageName}")
            }
        } catch (e: Exception) {
            Timber.w(e, "LoadedApkSwapper: failed to update mPackages")
        }

        Timber.d("LoadedApkSwapper: swap complete")
        return stealthClassLoader
    }
}

/**
 * 隐蔽 ClassLoader - 隐藏 stub/multiapp 痕迹
 *
 * 加固壳会遍历 ClassLoader 链检查:
 *   ClassLoader cl = context.getClassLoader();
 *   while (cl != null) {
 *       if (cl.toString().contains("clonestub")) return true;
 *       cl = cl.getParent();
 *   }
 *
 * StealthClassLoader 包装真实 ClassLoader, 使其 toString() 和 parent 链
 * 看起来像普通的 PathClassLoader, 不包含任何 stub 痕迹。
 */
class StealthClassLoader(
    private val delegate: ClassLoader,
    private val fakePath: String
) : ClassLoader(ClassLoader.getSystemClassLoader()) {

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        return delegate.loadClass(name)
    }

    override fun getResource(name: String?): URL? {
        return delegate.getResource(name)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        return delegate.getResources(name)
    }

    override fun getResourceAsStream(name: String?): java.io.InputStream? {
        return delegate.getResourceAsStream(name)
    }

    override fun toString(): String {
        return "DexPathList[[\"$fakePath\"]]\n" +
            "nativeLibraryDirectories=[\"${fakePath.substringBeforeLast("/")}/lib\"]"
    }
}
