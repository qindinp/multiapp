package com.multiapp.core.loader

data class HostedRuntimeBindingFingerprint(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val processSlot: String,
    val dataRoot: String,
    val versionCode: Long,
    val baseApkPath: String,
    val baseApkSha256: String,
    val splitApkPaths: List<String>,
    val splitApkSha256s: List<String>,
    val applicationClassName: String?,
    val engineProfile: String,
    val providerHookEnabled: Boolean
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(dataRoot.isNotBlank()) { "dataRoot must not be blank" }
        require(versionCode >= 0L) { "versionCode must not be negative" }
        require(baseApkPath.isNotBlank()) { "baseApkPath must not be blank" }
        require(baseApkSha256.isNotBlank()) { "baseApkSha256 must not be blank" }
        require(splitApkPaths.none { it.isBlank() }) { "splitApkPaths must not contain blanks" }
        require(splitApkSha256s.none { it.isBlank() }) { "splitApkSha256s must not contain blanks" }
        require(engineProfile.isNotBlank()) { "engineProfile must not be blank" }
    }
}
