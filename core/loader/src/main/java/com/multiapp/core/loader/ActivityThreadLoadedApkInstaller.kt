package com.multiapp.core.loader

import android.app.Application

/**
 * Installs hosted LoadedApk aliases into ActivityThread package maps.
 *
 * Guest sandboxes are created through ActivityThread.getPackageInfoNoCheck(),
 * then patched and registered under origin/virtual aliases. Mutations retain a
 * rollback handle until Application.onCreate completes so a failed bind cannot
 * leave ActivityThread pointing at a half-created guest runtime.
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
        val loadedApkSnapshot = LoadedApkBridge.snapshot(loadedApk)
        val packageFields = mutableMapOf<String, List<String>>()
        val skippedPackageFields = mutableMapOf<String, String>()
        val aliasSnapshots = mutableListOf<PackageMapAliasSnapshot>()
        val installedAliasSnapshots = mutableListOf<PackageMapAliasSnapshot>()

        for (fieldName in listOf("mPackages", "mResourcePackages")) {
            val mapAccess = runCatching { ActivityThreadCompat.packageMap(fieldName, activityThread) }
            val map = mapAccess.getOrNull()
            if (map == null) {
                packageFields[fieldName] = emptyList()
                skippedPackageFields[fieldName] = mapAccess.exceptionOrNull()?.let {
                    "PACKAGE_MAP_ACCESS_FAILED:${it.javaClass.simpleName}"
                } ?: "PACKAGE_MAP_UNAVAILABLE"
                continue
            }
            aliases.forEach { alias ->
                aliasSnapshots += PackageMapAliasSnapshot(
                    fieldName = fieldName,
                    packageMap = map,
                    alias = alias,
                    existed = map.containsKey(alias),
                    previousValue = map[alias]
                )
            }
        }

        val rollbackHandle = ActivityThreadLoadedApkRollbackHandle {
            val restoredFields = mutableListOf<String>()
            val failures = mutableListOf<String>()
            installedAliasSnapshots.asReversed().forEach { snapshot ->
                runCatching {
                    val current = snapshot.packageMap[snapshot.alias]
                    if (current.unwrapLoadedApkReference() !== loadedApk) {
                        failures += "${snapshot.fieldName}:${snapshot.alias}:ALIAS_OWNER_CHANGED"
                    } else {
                        if (snapshot.existed) {
                            snapshot.packageMap[snapshot.alias] = snapshot.previousValue
                        } else {
                            snapshot.packageMap.remove(snapshot.alias)
                        }
                        restoredFields += "${snapshot.fieldName}[${snapshot.alias}]"
                    }
                }.onFailure {
                    failures += "${snapshot.fieldName}:${snapshot.alias}:RESTORE_FAILED:${it.javaClass.simpleName}"
                }
            }
            val loadedApkRestore = LoadedApkBridge.restore(loadedApk, loadedApkSnapshot)
            restoredFields += loadedApkRestore.patchedFields.map { "LoadedApk.$it" }
            failures += loadedApkRestore.skippedFieldReasons.map { "LoadedApk.$it" }
            ActivityThreadLoadedApkRollbackResult(
                success = failures.isEmpty(),
                restoredFields = restoredFields,
                failureReasons = failures
            )
        }
        val patchResult = LoadedApkBridge.patch(loadedApk, state)
        val aliasInstallError = runCatching {
            aliasSnapshots.forEach { snapshot ->
                snapshot.packageMap[snapshot.alias] = java.lang.ref.WeakReference(loadedApk)
                installedAliasSnapshots += snapshot
            }
            aliasSnapshots.groupBy { it.fieldName }.forEach { (fieldName, snapshots) ->
                packageFields[fieldName] = snapshots.map { it.alias }
            }
        }.exceptionOrNull()
        if (aliasInstallError != null) {
            val rollbackResult = rollbackHandle.rollback()
            return ActivityThreadLoadedApkInstallResult(
                targetClassName = loadedApk.javaClass.name,
                aliases = aliases,
                patchResult = patchResult,
                installedAliasesByField = packageFields,
                skippedAliasInstallReasonsByField = skippedPackageFields,
                skippedReason = "LOADED_APK_ALIAS_INSTALL_FAILED:${aliasInstallError.javaClass.simpleName}",
                source = source,
                loadedApk = null,
                rollbackResult = rollbackResult
            )
        }
        val consistency = LoadedApkBridge.verify(
            target = loadedApk,
            state = state,
            requireApplication = false
        )
        if (!consistency.consistent) {
            val rollbackResult = rollbackHandle.rollback()
            return ActivityThreadLoadedApkInstallResult(
                targetClassName = loadedApk.javaClass.name,
                aliases = aliases,
                patchResult = patchResult,
                installedAliasesByField = packageFields,
                skippedAliasInstallReasonsByField = skippedPackageFields,
                skippedReason = "LOADED_APK_STATE_INCONSISTENT:${consistency.failureReasons.joinToString("|")}",
                source = source,
                loadedApk = null,
                rollbackResult = rollbackResult
            )
        }

        return ActivityThreadLoadedApkInstallResult(
            targetClassName = loadedApk.javaClass.name,
            aliases = aliases,
            patchResult = patchResult,
            installedAliasesByField = packageFields,
            skippedAliasInstallReasonsByField = skippedPackageFields,
            skippedReason = null,
            source = source,
            loadedApk = loadedApk,
            rollbackHandle = rollbackHandle
        )
    }

    fun bindApplication(
        activityThread: Any,
        installResult: ActivityThreadLoadedApkInstallResult,
        state: LoadedApkRuntimeState,
        application: Application
    ): ActivityThreadApplicationBindResult {
        val loadedApk = installResult.loadedApk
            ?: return ActivityThreadApplicationBindResult.failed(
                targetClassName = installResult.targetClassName,
                reason = "LoadedApk.mApplication:LOADED_APK_UNAVAILABLE",
                rollbackResult = installResult.rollbackResult
            )
        val threadSnapshot = captureActivityThreadBinding(activityThread)
            .getOrElse { error ->
                val rollbackResult = rollbackAfterFailedApplicationBind(
                    activityThread = activityThread,
                    application = application,
                    installRollbackHandle = installResult.rollbackHandle,
                    failurePrefix = "ActivityThread"
                )
                return ActivityThreadApplicationBindResult.failed(
                    targetClassName = installResult.targetClassName,
                    reason = error.message ?: error.javaClass.name,
                    rollbackResult = rollbackResult
                )
            }
        val rollbackHandle = createApplicationBindingRollbackHandle(
            threadSnapshot = threadSnapshot,
            application = application,
            installRollbackHandle = installResult.rollbackHandle
        )
        val boundState = state.copy(application = application)
        val loadedApkPatch = LoadedApkBridge.patch(
            target = loadedApk,
            state = boundState
        )
        val loadedApkConsistency = LoadedApkBridge.verify(
            target = loadedApk,
            state = boundState,
            requireApplication = true
        )
        if (!loadedApkConsistency.consistent) {
            return ActivityThreadApplicationBindResult.failed(
                targetClassName = installResult.targetClassName,
                reason = "LOADED_APK_BIND_INCONSISTENT:${loadedApkConsistency.failureReasons.joinToString("|")}",
                loadedApkPatchResult = loadedApkPatch,
                rollbackResult = rollbackHandle.rollback()
            )
        }

        val patchedFields = mutableListOf<String>()
        val skippedFields = mutableListOf<String>()
        setField(
            target = threadSnapshot.boundApplication,
            field = threadSnapshot.boundInfoField,
            value = loadedApk,
            displayName = "mBoundApplication.info",
            patchedFields = patchedFields,
            skippedFields = skippedFields
        )
        setField(
            target = threadSnapshot.boundApplication,
            field = threadSnapshot.boundAppInfoField,
            value = state.applicationInfo,
            displayName = "mBoundApplication.appInfo",
            patchedFields = patchedFields,
            skippedFields = skippedFields
        )
        setField(
            target = activityThread,
            field = threadSnapshot.initialApplicationField,
            value = application,
            displayName = "mInitialApplication",
            patchedFields = patchedFields,
            skippedFields = skippedFields
        )
        threadSnapshot.allApplications?.let { applications ->
            runCatching {
                if (applications.none { it === application }) {
                    applications += application
                    patchedFields += "mAllApplications"
                }
            }.onFailure {
                skippedFields += "mAllApplications:SET_FAILED:${it.javaClass.simpleName}"
            }
        } ?: run {
            skippedFields += "mAllApplications:FIELD_UNAVAILABLE"
        }

        val consistencyFailures = verifyActivityThreadBinding(
            activityThread = activityThread,
            snapshot = threadSnapshot,
            loadedApk = loadedApk,
            state = boundState,
            application = application
        ) + skippedFields.filterNot { it.startsWith("mAllApplications:") }
        if (consistencyFailures.isNotEmpty()) {
            return ActivityThreadApplicationBindResult.failed(
                targetClassName = installResult.targetClassName,
                reason = "ACTIVITY_THREAD_BIND_INCONSISTENT:${consistencyFailures.joinToString("|")}",
                loadedApkPatchResult = loadedApkPatch,
                activityThreadPatchedFields = patchedFields,
                activityThreadSkippedFields = skippedFields,
                rollbackResult = rollbackHandle.rollback()
            )
        }

        return ActivityThreadApplicationBindResult(
            status = ActivityThreadApplicationBindStatus.PASS,
            targetClassName = installResult.targetClassName,
            loadedApkPatchResult = loadedApkPatch,
            activityThreadPatchedFields = patchedFields,
            activityThreadSkippedFields = skippedFields,
            failureReasons = emptyList(),
            rollbackHandle = rollbackHandle
        )
    }

    internal fun rollbackUnboundApplication(
        activityThread: Any,
        installResult: ActivityThreadLoadedApkInstallResult,
        application: Application
    ): ActivityThreadLoadedApkRollbackResult = rollbackAfterFailedApplicationBind(
        activityThread = activityThread,
        application = application,
        installRollbackHandle = installResult.rollbackHandle,
        failurePrefix = "ActivityThread"
    )

    private fun captureActivityThreadBinding(activityThread: Any): Result<ActivityThreadBindingSnapshot> = runCatching {
        val boundApplicationField = requireField(activityThread.javaClass, "mBoundApplication")
        val boundApplication = boundApplicationField.get(activityThread)
            ?: throw IllegalStateException("ActivityThread.mBoundApplication:VALUE_NULL")
        val boundInfoField = requireField(boundApplication.javaClass, "info")
        val boundAppInfoField = requireField(boundApplication.javaClass, "appInfo")
        val initialApplicationField = requireField(activityThread.javaClass, "mInitialApplication")
        @Suppress("UNCHECKED_CAST")
        val allApplications = findFieldInHierarchy(activityThread.javaClass, "mAllApplications")
            ?.let { field -> runCatching { field.get(activityThread) as? MutableList<Application> }.getOrNull() }
        ActivityThreadBindingSnapshot(
            activityThread = activityThread,
            boundApplication = boundApplication,
            boundInfoField = boundInfoField,
            boundInfo = boundInfoField.get(boundApplication),
            boundAppInfoField = boundAppInfoField,
            boundAppInfo = boundAppInfoField.get(boundApplication),
            initialApplicationField = initialApplicationField,
            initialApplication = initialApplicationField.get(activityThread),
            allApplications = allApplications
        )
    }

    private fun createApplicationBindingRollbackHandle(
        threadSnapshot: ActivityThreadBindingSnapshot,
        application: Application,
        installRollbackHandle: ActivityThreadLoadedApkRollbackHandle?
    ): ActivityThreadLoadedApkRollbackHandle = ActivityThreadLoadedApkRollbackHandle {
        val restored = mutableListOf<String>()
        val failures = mutableListOf<String>()
        restoreField(
            threadSnapshot.boundApplication,
            threadSnapshot.boundInfoField,
            threadSnapshot.boundInfo,
            "mBoundApplication.info",
            restored,
            failures
        )
        restoreField(
            threadSnapshot.boundApplication,
            threadSnapshot.boundAppInfoField,
            threadSnapshot.boundAppInfo,
            "mBoundApplication.appInfo",
            restored,
            failures
        )
        restoreField(
            threadSnapshot.activityThread,
            threadSnapshot.initialApplicationField,
            threadSnapshot.initialApplication,
            "mInitialApplication",
            restored,
            failures
        )
        threadSnapshot.allApplications?.let { applications ->
            runCatching { applications.removeAllByIdentity(application) }
                .onSuccess { removed -> if (removed) restored += "mAllApplications" }
                .onFailure { failures += "mAllApplications:RESTORE_FAILED:${it.javaClass.simpleName}" }
        } ?: run {
            failures += "mAllApplications:ROLLBACK_FIELD_UNAVAILABLE"
        }
        installRollbackHandle?.rollback()?.let { installRollback ->
            restored += installRollback.restoredFields
            failures += installRollback.failureReasons
        } ?: run {
            failures += "LoadedApk:INSTALL_ROLLBACK_HANDLE_UNAVAILABLE"
        }
        ActivityThreadLoadedApkRollbackResult(
            success = failures.isEmpty(),
            restoredFields = restored,
            failureReasons = failures
        )
    }

    private fun rollbackAfterFailedApplicationBind(
        activityThread: Any,
        application: Application,
        installRollbackHandle: ActivityThreadLoadedApkRollbackHandle?,
        failurePrefix: String
    ): ActivityThreadLoadedApkRollbackResult {
        val restored = mutableListOf<String>()
        val failures = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val allApplications = findFieldInHierarchy(activityThread.javaClass, "mAllApplications")
            ?.let { field -> runCatching { field.get(activityThread) as? MutableList<Application> }.getOrNull() }
        if (allApplications != null) {
            runCatching { allApplications.removeAllByIdentity(application) }
                .onSuccess { removed -> if (removed) restored += "mAllApplications" }
                .onFailure { failures += "$failurePrefix.mAllApplications:RESTORE_FAILED:${it.javaClass.simpleName}" }
        } else {
            failures += "$failurePrefix.mAllApplications:ROLLBACK_FIELD_UNAVAILABLE"
        }
        installRollbackHandle?.rollback()?.let { installRollback ->
            restored += installRollback.restoredFields
            failures += installRollback.failureReasons
        } ?: run {
            failures += "LoadedApk:INSTALL_ROLLBACK_HANDLE_UNAVAILABLE"
        }
        return ActivityThreadLoadedApkRollbackResult(
            success = failures.isEmpty(),
            restoredFields = restored,
            failureReasons = failures
        )
    }

    private fun verifyActivityThreadBinding(
        activityThread: Any,
        snapshot: ActivityThreadBindingSnapshot,
        loadedApk: Any,
        state: LoadedApkRuntimeState,
        application: Application
    ): List<String> {
        val failures = mutableListOf<String>()
        val currentBound = runCatching {
            requireField(activityThread.javaClass, "mBoundApplication").get(activityThread)
        }.getOrNull()
        if (currentBound !== snapshot.boundApplication) {
            failures += "mBoundApplication:REFERENCE_CHANGED"
            return failures
        }
        if (runCatching { snapshot.boundInfoField.get(currentBound) }.getOrNull() !== loadedApk) {
            failures += "mBoundApplication.info:REFERENCE_MISMATCH"
        }
        if (runCatching { snapshot.boundAppInfoField.get(currentBound) }.getOrNull() !== state.applicationInfo) {
            failures += "mBoundApplication.appInfo:REFERENCE_MISMATCH"
        }
        if (runCatching { snapshot.initialApplicationField.get(activityThread) }.getOrNull() !== application) {
            failures += "mInitialApplication:REFERENCE_MISMATCH"
        }
        snapshot.allApplications?.let { applications ->
            if (applications.none { it === application }) {
                failures += "mAllApplications:APPLICATION_MISSING"
            }
        }
        val loadedApkConsistency = LoadedApkBridge.verify(
            target = loadedApk,
            state = state,
            requireApplication = true
        )
        failures += loadedApkConsistency.failureReasons.map { "LoadedApk.$it" }
        return failures
    }

    private fun setField(
        target: Any,
        field: java.lang.reflect.Field,
        value: Any?,
        displayName: String,
        patchedFields: MutableList<String>,
        skippedFields: MutableList<String>
    ) {
        runCatching { field.set(target, value) }
            .onSuccess { patchedFields += displayName }
            .onFailure { skippedFields += "$displayName:SET_FAILED:${it.javaClass.simpleName}" }
    }

    private fun restoreField(
        target: Any,
        field: java.lang.reflect.Field,
        value: Any?,
        displayName: String,
        restoredFields: MutableList<String>,
        failureReasons: MutableList<String>
    ) {
        runCatching { field.set(target, value) }
            .onSuccess { restoredFields += displayName }
            .onFailure { failureReasons += "$displayName:RESTORE_FAILED:${it.javaClass.simpleName}" }
    }

    private fun requireField(type: Class<*>, name: String): java.lang.reflect.Field =
        findFieldInHierarchy(type, name)
            ?: throw NoSuchFieldException("${type.name}.$name")

    private fun findFieldInHierarchy(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }
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
    val loadedApk: Any? = null,
    val rollbackHandle: ActivityThreadLoadedApkRollbackHandle? = null,
    val rollbackResult: ActivityThreadLoadedApkRollbackResult? = null
) {
    val installedFieldCount: Int get() = installedAliasesByField.count { it.value.isNotEmpty() }
    val installedAliasCount: Int get() = installedAliasesByField.values.sumOf { it.size }
    val skipped: Boolean get() = skippedReason != null
    val aliasInstallSkipped: Boolean get() = skippedAliasInstallReasonsByField.isNotEmpty()
}

class ActivityThreadLoadedApkRollbackHandle internal constructor(
    private val rollbackAction: () -> ActivityThreadLoadedApkRollbackResult
) {
    @Volatile
    private var completedResult: ActivityThreadLoadedApkRollbackResult? = null

    @Synchronized
    fun rollback(): ActivityThreadLoadedApkRollbackResult =
        completedResult ?: runCatching(rollbackAction).getOrElse { error ->
            ActivityThreadLoadedApkRollbackResult(
                success = false,
                restoredFields = emptyList(),
                failureReasons = listOf("ROLLBACK_ACTION_FAILED:${error.javaClass.name}")
            )
        }.also { completedResult = it }
}

data class ActivityThreadLoadedApkRollbackResult(
    val success: Boolean,
    val restoredFields: List<String>,
    val failureReasons: List<String>
)

enum class ActivityThreadApplicationBindStatus {
    PASS,
    FAIL
}

data class ActivityThreadApplicationBindResult(
    val status: ActivityThreadApplicationBindStatus,
    val targetClassName: String,
    val loadedApkPatchResult: LoadedApkPatchResult,
    val activityThreadPatchedFields: List<String>,
    val activityThreadSkippedFields: List<String>,
    val failureReasons: List<String>,
    val rollbackHandle: ActivityThreadLoadedApkRollbackHandle? = null,
    val rollbackResult: ActivityThreadLoadedApkRollbackResult? = null
) {
    val successful: Boolean get() = status == ActivityThreadApplicationBindStatus.PASS

    companion object {
        fun failed(
            targetClassName: String,
            reason: String,
            loadedApkPatchResult: LoadedApkPatchResult = LoadedApkPatchResult(
                targetClassName = targetClassName,
                patchedFields = emptyList(),
                skippedFields = emptyList()
            ),
            activityThreadPatchedFields: List<String> = emptyList(),
            activityThreadSkippedFields: List<String> = emptyList(),
            rollbackResult: ActivityThreadLoadedApkRollbackResult? = null
        ): ActivityThreadApplicationBindResult = ActivityThreadApplicationBindResult(
            status = ActivityThreadApplicationBindStatus.FAIL,
            targetClassName = targetClassName,
            loadedApkPatchResult = loadedApkPatchResult,
            activityThreadPatchedFields = activityThreadPatchedFields,
            activityThreadSkippedFields = activityThreadSkippedFields,
            failureReasons = listOf(reason),
            rollbackResult = rollbackResult
        )
    }
}

private data class PackageMapAliasSnapshot(
    val fieldName: String,
    val packageMap: MutableMap<Any?, Any?>,
    val alias: String,
    val existed: Boolean,
    val previousValue: Any?
)

private data class ActivityThreadBindingSnapshot(
    val activityThread: Any,
    val boundApplication: Any,
    val boundInfoField: java.lang.reflect.Field,
    val boundInfo: Any?,
    val boundAppInfoField: java.lang.reflect.Field,
    val boundAppInfo: Any?,
    val initialApplicationField: java.lang.reflect.Field,
    val initialApplication: Any?,
    val allApplications: MutableList<Application>?
)

private fun MutableList<Application>.removeAllByIdentity(application: Application): Boolean {
    var removed = false
    val iterator = iterator()
    while (iterator.hasNext()) {
        if (iterator.next() === application) {
            iterator.remove()
            removed = true
        }
    }
    return removed
}
