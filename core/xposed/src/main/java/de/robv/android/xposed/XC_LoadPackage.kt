package de.robv.android.xposed

import android.content.pm.ApplicationInfo

abstract class XC_LoadPackage {
    abstract fun handleLoadPackage(lpparam: LoadPackageParam)

    class LoadPackageParam {
        lateinit var packageName: String
        lateinit var processName: String
        lateinit var classLoader: ClassLoader
        lateinit var appInfo: ApplicationInfo
        var isFirstApplication: Boolean = false
    }
}
