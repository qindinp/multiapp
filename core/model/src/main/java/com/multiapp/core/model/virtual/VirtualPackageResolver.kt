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
    val launcherActivityName: String? = null,
    val activities: List<ResolvedComponent> = emptyList(),
    val services: List<ResolvedComponent> = emptyList(),
    val receivers: List<ResolvedComponent> = emptyList(),
    val providers: List<ResolvedComponent> = emptyList(),
    val permissions: List<String> = emptyList(),
    val nativeLibDir: String? = null
)

/**
 * A single component (activity, service, receiver, provider) declared in the manifest.
 */
data class ResolvedComponent(
    val name: String,
    val exported: Boolean = false,
    val intentFilters: List<String> = emptyList()
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
