package com.multiapp.core.model.virtual

/**
 * Resolved metadata for an installed APK.
 * Used by the container runtime to bootstrap a guest app without querying the system PackageManager.
 */
data class ResolvedPackage(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val targetSdk: Int,
    val minSdk: Int,
    val applicationClassName: String? = null,
    val processName: String? = null,
    val taskAffinity: String? = null,
    val themeId: Int = 0,
    val metaData: Map<String, String> = emptyMap(),
    val launcherActivityName: String? = null,
    val activities: List<ResolvedComponent> = emptyList(),
    val services: List<ResolvedComponent> = emptyList(),
    val receivers: List<ResolvedComponent> = emptyList(),
    val providers: List<ResolvedComponent> = emptyList(),
    val permissions: List<String> = emptyList(),
    val nativeLibDir: String? = null,
    val applicationLabel: String? = null,
    val splitSourceDirs: List<String> = emptyList(),
    val splitPublicSourceDirs: List<String> = emptyList(),
    val splitNames: List<String> = emptyList(),
    val isolatedSplits: Boolean = false
)

/**
 * Structured intent-filter metadata used by the hosted container resolver.
 *
 * Only the subset needed by the in-process VPMS model is represented here:
 * action, category, and data scheme. MIME type, authority, host, path, and
 * permission matching remain intentionally out of scope.
 */
data class ResolvedIntentFilter(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dataSchemes: List<String> = emptyList()
)

/**
 * A single component (activity, service, receiver, provider) declared in the manifest.
 */
data class ResolvedComponent(
    val name: String,
    val exported: Boolean = false,
    val intentFilters: List<String> = emptyList(),
    val resolvedIntentFilters: List<ResolvedIntentFilter> = emptyList(),
    val authorities: List<String> = emptyList(),
    val launchMode: String? = null,
    val processName: String? = null,
    val taskAffinity: String? = null,
    val themeId: Int = 0,
    val screenOrientation: String? = null,
    val configChanges: String? = null,
    val permission: String? = null,
    val grantUriPermissions: Boolean = false,
    val metaData: Map<String, String> = emptyMap(),
    val targetActivityName: String? = null
)

/**
 * Resolves package metadata from an APK path.
 *
 * Implementations may use aapt2 dumps, PackageManager APIs, or cached records
 * to produce a [ResolvedPackage] for container startup.
 */
interface VirtualPackageResolver {
    /**
     * Resolve package metadata for the given APK file.
     *
     * @param apkPath absolute path to the APK on disk
     * @return resolved metadata, or null if the APK cannot be parsed
     */
    fun resolve(apkPath: String): ResolvedPackage?
}
