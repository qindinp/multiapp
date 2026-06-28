package com.multiapp.core.loader

/**
 * Installs hosted LoadedApk aliases into ActivityThread package maps.
 *
 * This is a v2 hosted-container MVP. It does not yet construct a fresh Android
 * LoadedApk object; instead it patches a LoadedApk-like target already obtained
 * from the framework path, then registers origin/virtual package aliases so
 * framework lookups can resolve the same runtime state.
 */
object ActivityThreadLoadedApkInstaller {

    fun install(
        activityThread: Any,
        loadedApk: Any,
        state: LoadedApkRuntimeState,
        packageAliases: Collection<String>,
        hostPackageName: String? = null
    ): ActivityThreadLoadedApkInstallResult {
        val aliases = packageAliases.filter { it.isNotBlank() }.distinct()
        val inspection = LoadedApkBridge.inspect(loadedApk)
        if (!hostPackageName.isNullOrBlank() && inspection.matchesPackage(hostPackageName)) {
            return ActivityThreadLoadedApkInstallResult(
                targetClassName = loadedApk.javaClass.name,
                aliases = aliases,
                patchResult = LoadedApkPatchResult(
                    targetClassName = loadedApk.javaClass.name,
                    patchedFields = emptyList(),
                    skippedFields = emptyList()
                ),
                installedAliasesByField = emptyMap(),
                skippedReason = "HOST_LOADED_APK_GUARD:${hostPackageName}"
            )
        }
        val patchResult = LoadedApkBridge.patch(loadedApk, state)
        val packageFields = mutableMapOf<String, List<String>>()

        for (fieldName in listOf("mPackages", "mResourcePackages")) {
            val installed = aliases.filter { alias ->
                ActivityThreadCompat.putLoadedApkReference(
                    fieldName = fieldName,
                    packageName = alias,
                    loadedApk = loadedApk,
                    activityThread = activityThread
                )
            }
            packageFields[fieldName] = installed
        }

        return ActivityThreadLoadedApkInstallResult(
            targetClassName = loadedApk.javaClass.name,
            aliases = aliases,
            patchResult = patchResult,
            installedAliasesByField = packageFields,
            skippedReason = null
        )
    }
}

data class ActivityThreadLoadedApkInstallResult(
    val targetClassName: String,
    val aliases: List<String>,
    val patchResult: LoadedApkPatchResult,
    val installedAliasesByField: Map<String, List<String>>,
    val skippedReason: String? = null
) {
    val installedFieldCount: Int get() = installedAliasesByField.count { it.value.isNotEmpty() }
    val installedAliasCount: Int get() = installedAliasesByField.values.sumOf { it.size }
    val skipped: Boolean get() = skippedReason != null
}
