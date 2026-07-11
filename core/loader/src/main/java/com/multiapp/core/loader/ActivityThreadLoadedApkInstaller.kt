package com.multiapp.core.loader

import android.app.Application

/**
 * Installs hosted LoadedApk aliases into ActivityThread package maps.
 *
 * This is a v2 hosted-container MVP. It does not yet construct a fresh Android
 * LoadedApk object; instead it patches a LoadedApk-like target already obtained
 * from the framework path, then registers origin/virtual package aliases so
 * framework lookups can resolve the same runtime state.
 */
object ActivityThreadLoadedApkInstaller {

    fun findInstalledGuest(
        activityThread: Any,
        packageAliases: Collection<String>
    ): ActivityThreadLoadedApkInstallResult? {
        val aliases = packageAliases.filter { it.isNotBlank() }.distinct()
        val resolvedByField = linkedMapOf<String, MutableList<String>>()
        val skippedFields = linkedMapOf<String, String>()
        val candidates = mutableListOf<Any>()
        for (fieldName in listOf("mPackages", "mResourcePackages")) {
            val map = ActivityThreadCompat.packageMap(fieldName, activityThread)
            if (map == null) {
                skippedFields[fieldName] = "PACKAGE_MAP_UNAVAILABLE"
                continue
            }
            for (alias in aliases) {
                val loadedApk = map[alias].unwrapLoadedApkReference() ?: continue
                resolvedByField.getOrPut(fieldName) { mutableListOf() } += alias
                candidates += loadedApk
            }
        }
        val loadedApk = candidates.firstOrNull() ?: return null
        if (candidates.any { it !== loadedApk }) {
            return skippedInstallResult(
                targetClassName = loadedApk.javaClass.name,
                packageAliases = aliases,
                skippedReason = "PREWARMED_LOADED_APK_ALIAS_TARGET_MISMATCH",
                source = LoadedApkInstallSource.PREWARMED_GUEST
            )
        }
        return ActivityThreadLoadedApkInstallResult(
            targetClassName = loadedApk.javaClass.name,
            aliases = aliases,
            patchResult = LoadedApkPatchResult(
                targetClassName = loadedApk.javaClass.name,
                patchedFields = emptyList(),
                skippedFields = emptyList(),
                skippedFieldReasons = emptyList()
            ),
            installedAliasesByField = resolvedByField.mapValues { it.value.toList() },
            skippedAliasInstallReasonsByField = skippedFields,
            skippedReason = null,
            source = LoadedApkInstallSource.PREWARMED_GUEST,
            loadedApk = loadedApk
        )
    }

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

    fun bindApplication(
        installResult: ActivityThreadLoadedApkInstallResult,
        state: LoadedApkRuntimeState,
        application: Application
    ): LoadedApkPatchResult {
        val loadedApk = installResult.loadedApk
            ?: return LoadedApkPatchResult(
                targetClassName = installResult.targetClassName,
                patchedFields = emptyList(),
                skippedFields = listOf("mApplication"),
                skippedFieldReasons = listOf("mApplication:LOADED_APK_UNAVAILABLE")
            )
        return bindApplication(
            loadedApk = loadedApk,
            state = state,
            application = application
        )
    }

    fun bindApplication(
        loadedApk: Any,
        state: LoadedApkRuntimeState,
        application: Application
    ): LoadedApkPatchResult =
        LoadedApkBridge.patch(
            target = loadedApk,
            state = state.copy(application = application)
        )
}

enum class LoadedApkInstallSource {
    GUEST_SANDBOX,
    PREWARMED_GUEST,
    EXISTING_PATCH
}

private fun Any?.unwrapLoadedApkReference(): Any? = when (this) {
    is java.lang.ref.Reference<*> -> get()
    else -> this
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
