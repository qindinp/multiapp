package com.multiapp.core.model.virtual

/**
 * Runtime package snapshot for one hosted virtual instance.
 *
 * This is the v2 hosted-container equivalent of the package settings kept by
 * VirtualApp/BlackBox style engines: every PackageManager/ActivityManager query
 * for a guest self package should be answered from the same immutable snapshot.
 */
data class VirtualPackageSnapshot(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val applicationLabel: String,
    val versionCode: Long,
    val versionName: String,
    val targetSdk: Int,
    val minSdk: Int,
    val sourceDir: String,
    val publicSourceDir: String = sourceDir,
    val dataDir: String,
    val nativeLibraryDir: String? = null,
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
    val originCertSha256: String? = null
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(sourceDir.isNotBlank()) { "sourceDir must not be blank" }
        require(dataDir.isNotBlank()) { "dataDir must not be blank" }
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(targetSdk > 0) { "targetSdk must be positive" }
        require(minSdk > 0) { "minSdk must be positive" }
    }

    fun matchesPackageName(packageName: String?): Boolean =
        packageName == originPackageName || packageName == virtualPackageName
}
