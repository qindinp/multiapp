package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import com.multiapp.core.manifest.StubConfig
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference

/**
 * LoadedApk 替换核心 (借鉴 LSPatch)
 * 替换 ActivityThread.mPackages 中的 LoadedApk
 * 使系统使用原始 APK 的 ClassLoader 和 Resources
 */
object LoadedApkSwapper {

    /**
     * 替换 LoadedApk 并返回新的 ClassLoader (原始 APK 的)
     * LoaderFactory 需要用它来初始化 LSPlant
     */
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
        Timber.d("LoadedApkSwapper: sourceDir updated to ${appInfo.sourceDir}")

        // 3. 从 mPackages 移除旧 LoadedApk（stub + original 都清理，避免冲突）
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
        val newLoadedApk = activityThread.javaClass
            .getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo::class.java)
            .invoke(activityThread, appInfo)
        // 同时注册到 stub 和 original 包名，确保两种查找路径都能命中
        mPackages[config.stubPackageName] = WeakReference(newLoadedApk)
        mPackages[config.originalPackageName] = WeakReference(newLoadedApk)
        Timber.d("LoadedApkSwapper: installed new LoadedApk for ${config.stubPackageName} and ${config.originalPackageName}")

        // 5. 提取新 ClassLoader 供 LSPlant 初始化使用
        val classLoader = newLoadedApk.javaClass
            .getDeclaredField("mClassLoader")
            .apply { isAccessible = true }
            .get(newLoadedApk) as? ClassLoader
            ?: throw IllegalStateException("mClassLoader is null or not a ClassLoader")
        Timber.d("LoadedApkSwapper: new ClassLoader = ${classLoader.javaClass.name}")

        return classLoader
    }
}
