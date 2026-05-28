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

    fun swap(activityThread: Any, originApk: File, config: StubConfig) {
        Timber.d("LoadedApkSwapper: swapping to ${originApk.absolutePath}")

        // 1. 获取 mBoundApplication.appInfo
        val mBound = activityThread.javaClass
            .getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
            .get(activityThread)
        val appInfo = mBound.javaClass
            .getDeclaredField("appInfo")
            .apply { isAccessible = true }
            .get(mBound) as ApplicationInfo

        // 2. 修改 sourceDir 指向原始 APK
        appInfo.sourceDir = originApk.absolutePath
        appInfo.publicSourceDir = originApk.absolutePath
        Timber.d("LoadedApkSwapper: sourceDir updated to ${appInfo.sourceDir}")

        // 3. 从 mPackages 移除旧 LoadedApk
        @Suppress("UNCHECKED_CAST")
        val mPackages = activityThread.javaClass
            .getDeclaredField("mPackages")
            .apply { isAccessible = true }
            .get(activityThread) as MutableMap<String, Any>
        mPackages.remove(config.stubPackageName)
        Timber.d("LoadedApkSwapper: removed old LoadedApk for ${config.stubPackageName}")

        // 4. 创建新 LoadedApk
        val newLoadedApk = activityThread.javaClass
            .getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo::class.java)
            .invoke(activityThread, appInfo)
        mPackages[config.stubPackageName] = WeakReference(newLoadedApk)
        Timber.d("LoadedApkSwapper: installed new LoadedApk for ${config.stubPackageName}")
    }
}
