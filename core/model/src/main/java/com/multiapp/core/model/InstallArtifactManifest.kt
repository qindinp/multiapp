package com.multiapp.core.model

enum class InstallArtifactKind {
    ORIGIN_APK,
    STUB_APK,
    CONTAINER_DEX,
    LOADER_DEX,
    NATIVE_LIBRARY,
    SPLIT_APK,
    CONFIG_JSON,
    RESOURCE_PACKAGE
}

enum class InstallArtifactType {
    BASE_APK,
    SPLIT_APK,
    NATIVE_LIBRARY,
    OPTIMIZED_DEX,
    RESOURCE,
    METADATA
}

enum class AbiPolicy {
    HOST_PREFERRED,
    ORIGIN_PREFERRED,
    ALL_SUPPORTED,
    EXPLICIT_ONLY
}

data class InstallArtifact(
    val type: InstallArtifactType = InstallArtifactType.METADATA,
    val kind: InstallArtifactKind = type.toKind(),
    val path: String,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val abi: String? = null,
    val splitName: String? = null,
    val required: Boolean = true
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(sizeBytes >= 0) { "sizeBytes must be non-negative" }
    }
}

data class InstallArtifactManifest(
    val packageName: String = "",
    val versionName: String? = null,
    val versionCode: Long = 0,
    val baseApk: InstallArtifact? = null,
    val splitApks: List<InstallArtifact> = emptyList(),
    val nativeLibraries: List<InstallArtifact> = emptyList(),
    val optimizedDexFiles: List<InstallArtifact> = emptyList(),
    val resourceFiles: List<InstallArtifact> = emptyList(),
    val metadataFiles: List<InstallArtifact> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val installedAt: Long = 0,
    val originPackageName: String = packageName,
    val stubPackageName: String = "",
    val originVersionName: String? = null,
    val originVersionCode: Long = 0,
    val originApkPath: String = baseApk?.path ?: "",
    val originApkSha256: String = baseApk?.sha256 ?: "",
    val originCertSha256: String? = null,
    val containerBuildId: String = "unknown",
    val compatibilityProfileId: String = "container-baseline",
    val loaderBuildId: String = containerBuildId,
    val patchProfileId: String = compatibilityProfileId,
    val abiPolicy: AbiPolicy = AbiPolicy.ORIGIN_PREFERRED,
    val abiList: List<String> = emptyList(),
    val createdAtMillis: Long = installedAt,
    val artifacts: List<InstallArtifact> = allConstructorArtifacts(
        baseApk,
        splitApks,
        nativeLibraries,
        optimizedDexFiles,
        resourceFiles,
        metadataFiles
    )
) {
    init {
        require(originPackageName.isNotBlank() || packageName.isNotBlank()) {
            "packageName must not be blank"
        }
        require(containerBuildId.isNotBlank()) { "containerBuildId must not be blank" }
        require(compatibilityProfileId.isNotBlank()) { "compatibilityProfileId must not be blank" }
        require(loaderBuildId.isNotBlank()) { "loaderBuildId must not be blank" }
        require(patchProfileId.isNotBlank()) { "patchProfileId must not be blank" }
        require(createdAtMillis >= 0) { "createdAtMillis must be non-negative" }
    }

    fun allArtifacts(): List<InstallArtifact> = artifacts

    fun artifactPaths(): List<String> = artifacts.map { it.path }

    fun artifactsOf(kind: InstallArtifactKind): List<InstallArtifact> =
        artifacts.filter { it.kind == kind }

    fun hasOriginNativeLibraries(): Boolean =
        artifacts.any {
            it.kind == InstallArtifactKind.NATIVE_LIBRARY ||
                it.type == InstallArtifactType.NATIVE_LIBRARY
        }

    companion object {
        private fun allConstructorArtifacts(
            baseApk: InstallArtifact?,
            splitApks: List<InstallArtifact>,
            nativeLibraries: List<InstallArtifact>,
            optimizedDexFiles: List<InstallArtifact>,
            resourceFiles: List<InstallArtifact>,
            metadataFiles: List<InstallArtifact>
        ): List<InstallArtifact> =
            listOfNotNull(baseApk) +
                splitApks +
                nativeLibraries +
                optimizedDexFiles +
                resourceFiles +
                metadataFiles
    }
}

private fun InstallArtifactType.toKind(): InstallArtifactKind =
    when (this) {
        InstallArtifactType.BASE_APK -> InstallArtifactKind.ORIGIN_APK
        InstallArtifactType.SPLIT_APK -> InstallArtifactKind.SPLIT_APK
        InstallArtifactType.NATIVE_LIBRARY -> InstallArtifactKind.NATIVE_LIBRARY
        InstallArtifactType.OPTIMIZED_DEX -> InstallArtifactKind.CONTAINER_DEX
        InstallArtifactType.RESOURCE -> InstallArtifactKind.RESOURCE_PACKAGE
        InstallArtifactType.METADATA -> InstallArtifactKind.CONFIG_JSON
    }
