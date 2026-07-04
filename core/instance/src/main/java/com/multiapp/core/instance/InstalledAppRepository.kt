package com.multiapp.core.instance

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.multiapp.core.model.VirtualApp
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class InstalledAppRepository internal constructor(
    private val packageManagerProvider: () -> PackageManager,
    private val hostPackageName: String
) {
    private var cachedApps: List<VirtualApp>? = null

    @Inject
    constructor(@ApplicationContext context: android.content.Context) : this(
        packageManagerProvider = { context.packageManager },
        hostPackageName = context.packageName
    )

    fun listInstalledApps(forceRefresh: Boolean = false): List<VirtualApp> {
        if (!forceRefresh) {
            cachedApps?.let { return it }
        }
        val packageManager = packageManagerProvider()
        val apps = packageManager.getInstalledPackagesWithMetadata()
            .asSequence()
            .filter { it.packageName != hostPackageName }
            .mapNotNull { it.toVirtualApp(packageManager) }
            .sortedBy { it.appName.lowercase() }
            .toList()
        cachedApps = apps
        return apps
    }

    fun recommendedCloneTargets(forceRefresh: Boolean = false): List<VirtualApp> {
        return listInstalledApps(forceRefresh).filter { it.isCloneCandidate() }
    }

    fun clearCache() {
        cachedApps = null
    }

    private fun PackageManager.getInstalledPackagesWithMetadata(): List<PackageInfo> {
        val flags = PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= 33) {
            getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getInstalledPackages(flags)
        }
    }
}

internal fun PackageInfo.toVirtualApp(packageManager: PackageManager): VirtualApp? {
    val appInfo = applicationInfo ?: return null
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    return VirtualApp(
        packageName = packageName,
        appName = appInfo.safeLabel(packageManager, packageName),
        icon = runCatching { appInfo.loadIcon(packageManager) }.getOrNull(),
        versionName = versionName ?: "",
        versionCode = safeVersionCode(),
        apkPath = appInfo.sourceDir,
        instanceId = "",
        mainActivity = launchIntent?.component?.className,
        isSystemApp = isSystemApp,
        targetSdkVersion = appInfo.targetSdkVersion,
        minSdkVersion = appInfo.minSdkVersion,
        applicationClassName = appInfo.className
    )
}

private fun ApplicationInfo.safeLabel(packageManager: PackageManager, packageName: String): String {
    return runCatching { loadLabel(packageManager)?.toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() }
        ?: packageName.substringAfterLast(".")
}

private fun PackageInfo.safeVersionCode(): Long {
    return runCatching { longVersionCode }
        .getOrNull()
        ?.takeIf { it > 0L }
        ?: versionCode.toLong().takeIf { it > 0L }
        ?: 1L
}

fun VirtualApp.isCloneCandidate(): Boolean {
    return mainActivity != null && !isSystemApp
}
