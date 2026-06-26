package com.multiapp.core.model

data class VirtualComponentRecord(
    val name: String,
    val processName: String? = null,
    val exported: Boolean = false,
    val permission: String? = null
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }
}

data class VirtualPackageRecord(
    val packageName: String,
    val appName: String = "",
    val appLabel: String = appName.ifBlank { packageName },
    val installManifest: InstallArtifactManifest? = null,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val sourceApkPath: String = installManifest?.originApkPath ?: "",
    val sourceApkSha256: String = installManifest?.originApkSha256 ?: "",
    val signingCertificateSha256: String? = null,
    val requestedPermissions: List<String> = installManifest?.requestedPermissions ?: emptyList(),
    val activities: List<VirtualComponentRecord> = emptyList(),
    val services: List<VirtualComponentRecord> = emptyList(),
    val providers: List<VirtualComponentRecord> = emptyList(),
    val receivers: List<VirtualComponentRecord> = emptyList(),
    val nativeAbis: List<String> = emptyList(),
    val preferredAbi: String? = null,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.DEFAULT,
    val installedAt: Long = installManifest?.installedAt ?: 0,
    val updatedAt: Long = installedAt
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(appName.isNotBlank() || appLabel.isNotBlank()) { "app label must not be blank" }
        require(minSdk >= 0) { "minSdk must be non-negative" }
        require(targetSdk >= 0) { "targetSdk must be non-negative" }
    }

    val isProtectedBaseline: Boolean
        get() = compatibilityMode.protectedAppBaseline

    val isHookFree: Boolean
        get() = compatibilityMode.isHookFree

    fun ownsInstance(instance: VirtualInstanceRecord): Boolean =
        instance.packageName == packageName

    fun declaredComponentCount(): Int =
        activities.size + services.size + providers.size + receivers.size
}
