package com.multiapp.core.model.installer

data class ComponentInfo(
    val name: String,
    val exported: Boolean = false
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
    val providers: List<ComponentInfo> = emptyList()
)

fun interface InstallMetadataResolver {
    fun resolve(packageName: String, originApkPath: String): InstallMetadata
}

data class InstallRecord(
    val schemaVersion: Int = 1,
    val packageName: String,
    val originApkPath: String,
    val originApkSha256: String,
    val originCertSha256: String,
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
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(originApkPath.isNotBlank()) { "originApkPath must not be blank" }
        require(originApkSha256.isNotBlank()) { "originApkSha256 must not be blank" }
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(targetSdk > 0) { "targetSdk must be positive" }
        require(minSdk > 0) { "minSdk must be positive" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }
}
