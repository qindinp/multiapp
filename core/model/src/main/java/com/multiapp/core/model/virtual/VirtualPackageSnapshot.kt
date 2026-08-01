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
    val sourceSha256: String? = null,
    val publicSourceDir: String = sourceDir,
    val splitSourceDirs: List<String> = emptyList(),
    val splitSha256s: List<String> = emptyList(),
    val splitPublicSourceDirs: List<String> = splitSourceDirs,
    val splitNames: List<String> = emptyList(),
    val isolatedSplits: Boolean = false,
    val dataDir: String,
    val nativeLibraryDir: String? = null,
    val nativeLibraries: List<String> = emptyList(),
    val abiList: List<String> = emptyList(),
    val applicationClassName: String? = null,
    val processName: String? = null,
    val taskAffinity: String? = null,
    val themeId: Int = 0,
    val metaData: Map<String, String> = emptyMap(),
    val typedMetaData: Map<String, VirtualMetaDataValue> = emptyMap(),
    val launcherActivityName: String? = null,
    val activities: List<ResolvedComponent> = emptyList(),
    val services: List<ResolvedComponent> = emptyList(),
    val receivers: List<ResolvedComponent> = emptyList(),
    val providers: List<ResolvedComponent> = emptyList(),
    val permissions: List<String> = emptyList(),
    val debuggable: Boolean = false,
    val sharedUserId: String? = null,
    val sharedUserLabel: Int = 0,
    val originCertSha256: String? = null,
    val signerSha256Digests: List<String> = emptyList(),
    val hasMultipleSigners: Boolean = false
) {
    /** Code paths for class loading: base APK first, then split APKs. */
    val codeSourceDirs: List<String>
        get() = listOf(sourceDir) + splitSourceDirs

    /** Public/resource paths for Resources/AssetManager: base APK first, then split resource paths. */
    val publicResourceDirs: List<String>
        get() = listOf(publicSourceDir) + splitPublicSourceDirs

    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(sourceDir.isNotBlank()) { "sourceDir must not be blank" }
        require(publicSourceDir.isNotBlank()) { "publicSourceDir must not be blank" }
        require(sourceSha256 == null || sourceSha256.isNotBlank()) {
            "sourceSha256 must not be blank"
        }
        require(splitSourceDirs.none { it.isBlank() }) { "splitSourceDirs must not contain blank entries" }
        require(splitSha256s.none { it.isBlank() }) { "splitSha256s must not contain blank entries" }
        require(splitPublicSourceDirs.none { it.isBlank() }) {
            "splitPublicSourceDirs must not contain blank entries"
        }
        require(splitNames.none { it.isBlank() }) { "splitNames must not contain blank entries" }
        require(nativeLibraries.none { it.isBlank() }) {
            "nativeLibraries must not contain blank entries"
        }
        require(abiList.none { it.isBlank() }) { "abiList must not contain blank entries" }
        require(signerSha256Digests.none { it.isBlank() }) {
            "signerSha256Digests must not contain blank entries"
        }
        require(splitPublicSourceDirs.isEmpty() || splitPublicSourceDirs.size == splitSourceDirs.size) {
            "splitPublicSourceDirs size must match splitSourceDirs size"
        }
        require(splitSha256s.isEmpty() || splitSha256s.size == splitSourceDirs.size) {
            "splitSha256s size must match splitSourceDirs size"
        }
        require(splitNames.isEmpty() || splitNames.size == splitSourceDirs.size) {
            "splitNames size must match splitSourceDirs size"
        }
        require(dataDir.isNotBlank()) { "dataDir must not be blank" }
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(targetSdk > 0) { "targetSdk must be positive" }
        require(minSdk > 0) { "minSdk must be positive" }
    }

    fun matchesPackageName(packageName: String?): Boolean =
        packageName == originPackageName || packageName == virtualPackageName
}
