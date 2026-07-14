package com.multiapp.core.loader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import com.multiapp.core.model.virtual.VirtualContextConfig

/**
 * Builds the guest resource view for hosted container runtime.
 *
 * VirtualApp/BlackBox-style runtimes keep guest APK resources separate from
 * the host package. This manager centralizes that responsibility for v2 so
 * Context, Activity injection, and the future LoadedApk bridge do not each
 * invent their own resource loading path.
 */
class VirtualResourcesManager(
    private val hostContext: Context
) {
    fun create(config: VirtualContextConfig): VirtualResourceBundle {
        val appInfo = createApplicationInfo(config)
        val packageManagerResources = runCatching {
            hostContext.packageManager.getResourcesForApplication(appInfo)
        }.getOrNull()
        if (packageManagerResources != null) {
            return VirtualResourceBundle(appInfo, packageManagerResources, ResourceSource.PACKAGE_MANAGER)
        }

        val archiveResources = runCatching {
            createArchiveResources(config.resourceAssetPaths())
        }.getOrNull()
        if (archiveResources != null) {
            return VirtualResourceBundle(appInfo, archiveResources, ResourceSource.ASSET_MANAGER)
        }

        return VirtualResourceBundle(appInfo, hostContext.resources, ResourceSource.HOST_FALLBACK)
    }

    internal fun createApplicationInfo(config: VirtualContextConfig): ApplicationInfo {
        val runtimeUid = RuntimeUidCompat.resolve(
            runCatching { hostContext.applicationInfo.uid }.getOrNull()
        )
        return config.packageSnapshot?.let {
            VirtualPackageInfoFactory.applicationInfo(
                snapshot = it,
                runtimeUid = runtimeUid,
                flags = VirtualPackageQueryFlags.INTERNAL_FULL
            )
        }
            ?: ApplicationInfo(hostContext.applicationInfo).apply {
                uid = runtimeUid
                packageName = config.originPackageName
                className = null
                name = null
                sourceDir = config.sourceDir
                publicSourceDir = config.publicSourceDir
                applySplitPaths(config)
                dataDir = config.dataDir
                ApplicationInfoNativePathCompat.applyTo(this, config.dataDir, config.nativeLibraryDir)
                nonLocalizedLabel = config.applicationLabel ?: config.originPackageName
                enabled = true
            }
    }

    @Suppress("DEPRECATION")
    private fun VirtualContextConfig.resourceAssetPaths(): List<String> =
        publicResourceDirs

    private fun ApplicationInfo.applySplitPaths(config: VirtualContextConfig) {
        if (config.splitSourceDirs.isNotEmpty()) {
            splitSourceDirs = config.splitSourceDirs.toTypedArray()
        }
        val publicDirs = config.splitPublicSourceDirs.ifEmpty { config.splitSourceDirs }
        if (publicDirs.isNotEmpty()) {
            splitPublicSourceDirs = publicDirs.toTypedArray()
        }
        if (config.splitNames.isNotEmpty()) {
            splitNames = config.splitNames.toTypedArray()
        }
    }

    @Suppress("DEPRECATION")
    private fun createArchiveResources(assetPaths: List<String>): Resources? {
        val hostResources = hostContext.resources
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
        var added = 0
        assetPaths.distinct().forEach { path ->
            val cookie = addAssetPath.invoke(assetManager, path) as? Int ?: 0
            if (cookie != 0) added += 1
        }
        if (added == 0) return null
        return Resources(assetManager, hostResources.displayMetrics, hostResources.configuration)
    }
}

data class VirtualResourceBundle(
    val applicationInfo: ApplicationInfo,
    val resources: Resources,
    val source: ResourceSource
)

enum class ResourceSource {
    PACKAGE_MANAGER,
    ASSET_MANAGER,
    HOST_FALLBACK
}
