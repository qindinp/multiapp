package com.multiapp.core.model.installer

data class ComponentInfo(
    val name: String,
    val exported: Boolean = false,
    val permission: String? = null,
    val grantUriPermissions: Boolean = false,
    val launchMode: String? = null,
    val processName: String? = null,
    val taskAffinity: String? = null,
    val themeId: Int = 0,
    val targetActivityName: String? = null
) {
    init {
        require(name.isNotBlank()) { "component name must not be blank" }
    }
}

data class InstallMetadata(
    val permissions: List<String> = emptyList(),
    val activities: List<ComponentInfo> = emptyList(),
    val services: List<ComponentInfo> = emptyList(),
    val receivers: List<ComponentInfo> = emptyList(),
    val providers: List<ComponentInfo> = emptyList(),
    val nativeLibraries: List<String> = emptyList(),
    val abiList: List<String> = emptyList(),
    val splitApkPaths: List<String> = emptyList(),
    val splitPublicSourceDirs: List<String> = emptyList(),
    val splitNames: List<String> = emptyList(),
    val isolatedSplits: Boolean = false
)

fun interface InstallMetadataResolver {
    fun resolve(packageName: String, originApkPath: String): InstallMetadata
}

internal fun requireSafeInstallPackageName(packageName: String) {
    require(packageName.isNotBlank()) { "packageName must not be blank" }
    require(!packageName.contains("..") && !packageName.contains("/") && !packageName.contains("\\")) {
        "Invalid packageName: $packageName"
    }
}

data class InstallRecord(
    val schemaVersion: Int = 1,
    val packageName: String,
    val originApkPath: String,
    val originApkSha256: String,
    val originCertSha256: String,
    val splitApkPaths: List<String> = emptyList(),
    val splitPublicSourceDirs: List<String> = emptyList(),
    val splitNames: List<String> = emptyList(),
    val splitApkSha256s: List<String> = emptyList(),
    val isolatedSplits: Boolean = false,
    val versionCode: Long,
    val versionName: String,
    val targetSdk: Int,
    val minSdk: Int,
    val nativeLibraries: List<String> = emptyList(),
    val abiList: List<String> = emptyList(),
    val applicationClassName: String? = null,
    val packageLabel: String? = null,
    val permissions: List<String> = emptyList(),
    val activities: List<ComponentInfo> = emptyList(),
    val services: List<ComponentInfo> = emptyList(),
    val receivers: List<ComponentInfo> = emptyList(),
    val providers: List<ComponentInfo> = emptyList(),
    val installTimeMs: Long,
    val updatedAtMs: Long = installTimeMs
) {
    /** Code paths for runtime class loading: base APK first, then split APKs. */
    val codeSourceDirs: List<String>
        get() = listOf(originApkPath) + splitApkPaths

    /** Public/resource paths for runtime resource loading: base APK first, then split public paths. */
    val publicResourceDirs: List<String>
        get() = listOf(originApkPath) + splitPublicSourceDirs.ifEmpty { splitApkPaths }

    init {
        requireSafeInstallPackageName(packageName)
        require(originApkPath.isNotBlank()) { "originApkPath must not be blank" }
        require(originApkSha256.isNotBlank()) { "originApkSha256 must not be blank" }
        require(splitApkPaths.none { it.isBlank() }) { "splitApkPaths must not contain blank entries" }
        require(splitPublicSourceDirs.none { it.isBlank() }) {
            "splitPublicSourceDirs must not contain blank entries"
        }
        require(splitNames.none { it.isBlank() }) { "splitNames must not contain blank entries" }
        require(splitApkSha256s.none { it.isBlank() }) { "splitApkSha256s must not contain blank entries" }
        require(splitPublicSourceDirs.isEmpty() || splitPublicSourceDirs.size == splitApkPaths.size) {
            "splitPublicSourceDirs size must match splitApkPaths size"
        }
        require(splitNames.isEmpty() || splitNames.size == splitApkPaths.size) {
            "splitNames size must match splitApkPaths size"
        }
        require(splitApkSha256s.isEmpty() || splitApkSha256s.size == splitApkPaths.size) {
            "splitApkSha256s size must match splitApkPaths size"
        }
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(targetSdk > 0) { "targetSdk must be positive" }
        require(minSdk > 0) { "minSdk must be positive" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(nativeLibraries.none { it.isBlank() }) { "nativeLibraries must not contain blank entries" }
        require(abiList.none { it.isBlank() }) { "abiList must not contain blank entries" }
    }
}
