package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import com.multiapp.core.manifest.StubConfig
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.net.URL
import java.util.Enumeration

/**
 * LoadedApk 替换核心 (借鉴 LSPatch)
 * 替换 ActivityThread.mPackages 中的 LoadedApk
 * 使系统使用原始 APK 的 ClassLoader 和 Resources
 */
object LoadedApkSwapper {

    // 强引用持有新 LoadedApk, 防止 GC 导致 WeakReference.get() 返回 null
    @Volatile
    @JvmStatic
    private var strongRefToNewLoadedApk: Any? = null

    fun swap(activityThread: Any, originApk: File, config: StubConfig): ClassLoader {
        Timber.d("LoadedApkSwapper: swapping to ${originApk.absolutePath}")

        // 1. 获取 mBoundApplication.appInfo
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

        // 2. 修改 sourceDir 指向原始 APK
        appInfo.sourceDir = originApk.absolutePath
        appInfo.publicSourceDir = originApk.absolutePath

        val originLibDir = File(originApk.parentFile, "lib")
        if (originLibDir.isDirectory) {
            appInfo.nativeLibraryDir = originLibDir.absolutePath
            Timber.d("LoadedApkSwapper: nativeLibraryDir updated to ${appInfo.nativeLibraryDir}")
        }

        Timber.d("LoadedApkSwapper: sourceDir updated to ${appInfo.sourceDir}")

        // 3. 从 mPackages 移除旧 LoadedApk
        @Suppress("UNCHECKED_CAST")
        val mPackages = activityThread.javaClass
            .getDeclaredField("mPackages")
            .apply { isAccessible = true }
            .get(activityThread) as? MutableMap<String, Any>
            ?: throw IllegalStateException("mPackages is null or not a Map")
        mPackages.remove(config.stubPackageName)
        mPackages.remove(config.originalPackageName)
        Timber.d("LoadedApkSwapper: removed old LoadedApk for ${config.stubPackageName} and ${config.originalPackageName}")

        // 4. 创建新 LoadedApk
        // Android 16 (API 36) 把 getPackageInfoNoCheck 改为 2 参数版本:
        //   getPackageInfoNoCheck(ApplicationInfo, CompatibilityInfo)
        val newLoadedApk = try {
            activityThread.javaClass
                .getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo::class.java)
                .invoke(activityThread, appInfo)
        } catch (_: NoSuchMethodException) {
            Timber.d("LoadedApkSwapper: 1-arg getPackageInfoNoCheck not found, trying 2-arg (Android 16+)")
            try {
                val compatInfoClass = Class.forName("android.content.res.CompatibilityInfo")
                val defaultCompat = compatInfoClass.getField("DEFAULT_COMPATIBILITY_INFO").get(null)
                activityThread.javaClass
                    .getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo::class.java, compatInfoClass)
                    .invoke(activityThread, appInfo, defaultCompat)
            } catch (e2: Exception) {
                // Fallback: try getPackageInfo(ApplicationInfo) which some custom ROMs use
                Timber.d("LoadedApkSwapper: 2-arg also failed, trying getPackageInfo fallback")
                try {
                    activityThread.javaClass
                        .getDeclaredMethod("getPackageInfo", ApplicationInfo::class.java, Int::class.javaPrimitiveType)
                        .invoke(activityThread, appInfo, 0)
                } catch (e3: Exception) {
                    throw RuntimeException(
                        "getPackageInfoNoCheck not found. Tried: " +
                        "(ApplicationInfo), (ApplicationInfo, CompatibilityInfo), " +
                        "(ApplicationInfo, int). Last error: ${e3.message}", e3
                    )
                }
            }
        }
        mPackages[config.stubPackageName] = WeakReference(newLoadedApk)
        mPackages[config.originalPackageName] = WeakReference(newLoadedApk)
        strongRefToNewLoadedApk = newLoadedApk
        Timber.d("LoadedApkSwapper: installed new LoadedApk for ${config.stubPackageName} and ${config.originalPackageName}")

        // 5. 提取真实 ClassLoader
        val realClassLoader = newLoadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .get(newLoadedApk) as? ClassLoader
            ?: throw IllegalStateException("mClassLoader is null or not a ClassLoader")
        Timber.d("LoadedApkSwapper: real ClassLoader = ${realClassLoader.javaClass.name}")

        // 6. 用 StealthClassLoader 包装, 隐藏 ClassLoader 链中的 stub/multiapp 痕迹
        val stealthClassLoader = StealthClassLoader(realClassLoader, originApk.absolutePath)

        // 7. 替换 mClassLoader
        newLoadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .set(newLoadedApk, stealthClassLoader)
        strongRefToNewLoadedApk = newLoadedApk

        Timber.d("LoadedApkSwapper: ClassLoader wrapped with StealthClassLoader")
        return stealthClassLoader
    }
}

/**
 * 隐蔽 ClassLoader — 隐藏 stub/multiapp 痕迹
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
