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

    fun skippedInstallResult(
        targetClassName: String,
        packageAliases: Collection<String>,
        skippedReason: String,
        source: LoadedApkInstallSource = LoadedApkInstallSource.EXISTING_PATCH
    ): ActivityThreadLoadedApkInstallResult {
        val aliases = packageAliases.filter { it.isNotBlank() }.distinct()
        return ActivityThreadLoadedApkInstallResult(
            targetClassName = targetClassName,
            aliases = aliases,
            patchResult = LoadedApkPatchResult(
                targetClassName = targetClassName,
                patchedFields = emptyList(),
                skippedFields = emptyList(),
                skippedFieldReasons = emptyList()
            ),
            installedAliasesByField = emptyMap(),
            skippedAliasInstallReasonsByField = emptyMap(),
            skippedReason = skippedReason,
            source = source,
            loadedApk = null
        )
    }

    fun installGuestSandbox(
        activityThread: Any,
        state: LoadedApkRuntimeState,
        packageAliases: Collection<String>
    ): ActivityThreadLoadedApkInstallResult {
        val loadedApk = ActivityThreadCompat.getPackageInfoNoCheck(
            applicationInfo = state.applicationInfo,
            activityThread = activityThread
        )
        return install(
            activityThread = activityThread,
            loadedApk = loadedApk,
            state = state,
            packageAliases = packageAliases,
            hostPackageName = null,
            source = LoadedApkInstallSource.GUEST_SANDBOX
        )
    }

    fun install(
        activityThread: Any,
        loadedApk: Any,
        state: LoadedApkRuntimeState,
        packageAliases: Collection<String>,
        hostPackageName: String? = null,
        source: LoadedApkInstallSource = LoadedApkInstallSource.EXISTING_PATCH
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
                    skippedFields = emptyList(),
                    skippedFieldReasons = emptyList()
                ),
                installedAliasesByField = emptyMap(),
                skippedAliasInstallReasonsByField = emptyMap(),
                skippedReason = "HOST_LOADED_APK_GUARD:${hostPackageName}",
                source = source,
                loadedApk = null
            )
        }
        val patchResult = LoadedApkBridge.patch(loadedApk, state)
        val packageFields = mutableMapOf<String, List<String>>()
        val skippedPackageFields = mutableMapOf<String, String>()

        for (fieldName in listOf("mPackages", "mResourcePackages")) {
            val map = ActivityThreadCompat.packageMap(fieldName, activityThread)
            if (map == null) {
                packageFields[fieldName] = emptyList()
                skippedPackageFields[fieldName] = "PACKAGE_MAP_UNAVAILABLE"
                continue
            }
            val installed = aliases.onEach { alias ->
                map[alias] = java.lang.ref.WeakReference(loadedApk)
            }
            packageFields[fieldName] = installed
        }

        return ActivityThreadLoadedApkInstallResult(
            targetClassName = loadedApk.javaClass.name,
            aliases = aliases,
            patchResult = patchResult,
            installedAliasesByField = packageFields,
            skippedAliasInstallReasonsByField = skippedPackageFields,
            skippedReason = null,
            source = source,
            loadedApk = loadedApk
        )
    }
}

enum class LoadedApkInstallSource {
    GUEST_SANDBOX,
    EXISTING_PATCH
}

data class ActivityThreadLoadedApkInstallResult(
    val targetClassName: String,
    val aliases: List<String>,
    val patchResult: LoadedApkPatchResult,
    val installedAliasesByField: Map<String, List<String>>,
    val skippedAliasInstallReasonsByField: Map<String, String> = emptyMap(),
    val skippedReason: String? = null,
    val source: LoadedApkInstallSource = LoadedApkInstallSource.EXISTING_PATCH,
    val loadedApk: Any? = null
) {
    val installedFieldCount: Int get() = installedAliasesByField.count { it.value.isNotEmpty() }
    val installedAliasCount: Int get() = installedAliasesByField.values.sumOf { it.size }
    val skipped: Boolean get() = skippedReason != null
    val aliasInstallSkipped: Boolean get() = skippedAliasInstallReasonsByField.isNotEmpty()
}
