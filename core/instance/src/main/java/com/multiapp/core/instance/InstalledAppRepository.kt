package com.multiapp.core.instance

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.Intent
import android.os.Build
import com.multiapp.core.model.InstalledAppCatalog
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.isCloneCandidate as isModelCloneCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class InstalledAppRepository internal constructor(
    private val packageManagerProvider: () -> PackageManager,
    private val hostPackageName: String,
    private val launcherIntentFactory: () -> Intent = {
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    }
) : InstalledAppCatalog {
    private var cachedApps: List<VirtualApp>? = null

    @Inject
    constructor(@ApplicationContext context: android.content.Context) : this(
        packageManagerProvider = { context.packageManager },
        hostPackageName = context.packageName
    )

    override fun listInstalledApps(forceRefresh: Boolean): List<VirtualApp> {
        if (!forceRefresh) {
            cachedApps?.let { return it }
        }
        val packageManager = packageManagerProvider()
        val launcherActivities = packageManager.queryLauncherActivitiesByPackage()
        val apps = packageManager.getInstalledPackagesWithMetadata()
            .asSequence()
            .filter { it.packageName != hostPackageName }
            .mapNotNull { it.toVirtualApp(packageManager, launcherActivities[it.packageName]) }
            .sortedBy { it.appName.lowercase() }
            .toList()
        // 空列表不缓存：权限未授予时 getInstalledPackages 返回空列表，
        // 若缓存会导致授权后仍返回陈旧空列表，需保留下次查询重新拉取的能力。
        if (apps.isNotEmpty()) cachedApps = apps
        return apps
    }

    fun recommendedCloneTargets(forceRefresh: Boolean = false): List<VirtualApp> {
        return listInstalledApps(forceRefresh).filter { it.isModelCloneCandidate() }
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

    private fun PackageManager.queryLauncherActivitiesByPackage(): Map<String, String> {
        val launcherIntent = launcherIntentFactory()
        return queryLauncherActivities(launcherIntent)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val activityName = activityInfo.name?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                packageName to activityName
            }
            .distinctBy { it.first }
            .toMap()
    }

    private fun PackageManager.queryLauncherActivities(intent: Intent): List<ResolveInfo> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                queryIntentActivities(intent, 0)
            }
        }.getOrElse { emptyList() }
    }
}

internal fun PackageInfo.toVirtualApp(
    packageManager: PackageManager,
    launcherActivityClassName: String?
): VirtualApp? {
    val appInfo = applicationInfo ?: return null
    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    return VirtualApp(
        packageName = packageName,
        appName = appInfo.safeLabel(packageManager, packageName),
        versionName = versionName ?: "",
        versionCode = safeVersionCode(),
        apkPath = appInfo.sourceDir,
        instanceId = "",
        mainActivity = launcherActivityClassName,
        isSystemApp = isSystemApp,
        targetSdkVersion = appInfo.targetSdkVersion,
        minSdkVersion = appInfo.minSdkVersion,
        applicationClassName = appInfo.className,
        splitApkPaths = appInfo.splitSourceDirs?.filterNotBlank().orEmpty(),
        splitPublicSourceDirs = appInfo.splitPublicSourceDirs?.filterNotBlank().orEmpty(),
        splitNames = appInfo.splitNames?.filterNotBlank().orEmpty(),
        hasSplitApks = !appInfo.splitSourceDirs.isNullOrEmpty(),
        isolatedSplits = appInfo.safeRequestsIsolatedSplitLoading(),
        isDebuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        sharedUserId = sharedUserId?.takeIf { it.isNotBlank() },
        sharedUserLabel = if (sharedUserId.isNullOrBlank()) 0 else sharedUserLabel
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

private fun Array<String>?.filterNotBlank(): List<String> =
    this?.filter { it.isNotBlank() }.orEmpty()

private fun ApplicationInfo.safeRequestsIsolatedSplitLoading(): Boolean =
    runCatching {
        javaClass.getMethod("requestsIsolatedSplitLoading").invoke(this) as? Boolean ?: false
    }.getOrDefault(false)

@Deprecated("Import com.multiapp.core.model.isCloneCandidate")
fun VirtualApp.isCloneCandidate(): Boolean = isModelCloneCandidate()
