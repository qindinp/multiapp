package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Message
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Patches framework launch records before ActivityThread creates and attaches an Activity.
 *
 * DroidPlugin and VirtualApp both hook ActivityThread.H for this reason: Instrumentation.newActivity()
 * can substitute the Java Activity class, but Activity.attach() has already consumed
 * ActivityClientRecord/LaunchActivityItem identity by the time callActivityOnCreate() runs.
 */
object ActivityThreadLaunchRecordPatcher {
    private const val TAG = "ActivityLaunchPatcher"
    private const val EVIDENCE_DIR = "hosted_launch_evidence"
    private const val RECORD_PATCH_CAPABILITY_OWNER = "ACTIVITY_THREAD_RECORD_PATCHER"
    private const val INSTRUMENTATION_FALLBACK_CAPABILITY_OWNER = "VIRTUAL_INSTRUMENTATION_FALLBACK"
    private const val LOADED_APK_BINDING_RECORD_PATCHED = "RECORD_PATCHED"
    private const val LOADED_APK_BINDING_FRAMEWORK_DERIVED = "FRAMEWORK_DERIVED_FROM_ACTIVITY_INFO"
    private val ACTIVITY_INFO_FIELDS = listOf("activityInfo", "mActivityInfo", "info", "mInfo")
    private val INTENT_FIELDS = listOf("intent", "mIntent")
    private val LOADED_APK_FIELDS = listOf("packageInfo", "mPackageInfo", "loadedApk", "mLoadedApk")
    private val prepatchedLaunchIdentities = ConcurrentHashMap<String, VirtualActivityLaunchIdentity>()

    fun patchMessage(
        message: Message,
        processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global
    ): ActivityThreadLaunchRecordPatchResult =
        patchMessageObject(message.obj, processRuntime)

    internal fun patchMessageObject(
        messageObject: Any?,
        processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global
    ): ActivityThreadLaunchRecordPatchResult {
        if (messageObject == null) {
            return ActivityThreadLaunchRecordPatchResult(skippedReason = "MESSAGE_OBJECT_MISSING")
        }

        val direct = patchLaunchRecord(messageObject, processRuntime)
        if (direct.observedProxyLaunch) return direct

        val callbackResults = activityCallbacks(messageObject).map { callback ->
            patchLaunchRecord(callback, processRuntime)
        }.filter { it.observedProxyLaunch }

        if (callbackResults.isEmpty()) {
            return ActivityThreadLaunchRecordPatchResult(skippedReason = "PROXY_LAUNCH_RECORD_NOT_FOUND")
        }

        val patchedFields = callbackResults.flatMap { it.patchedFields }.distinct()
        val skippedFields = callbackResults.flatMap { it.skippedFields }.distinct()
        val skippedReasons = callbackResults.mapNotNull { it.skippedReason }.distinct()
        return ActivityThreadLaunchRecordPatchResult(
            targetClassName = messageObject.javaClass.name,
            observedProxyLaunch = true,
            patchedFields = patchedFields,
            skippedFields = skippedFields,
            patchedRecordCount = callbackResults.count { it.patchedFields.isNotEmpty() },
            skippedReason = skippedReasons.takeIf { it.isNotEmpty() }?.joinToString(","),
            instanceId = callbackResults.firstNotNullOfOrNull { it.instanceId },
            guestActivityClassName = callbackResults.firstNotNullOfOrNull { it.guestActivityClassName },
            token = callbackResults.firstNotNullOfOrNull { it.token },
            loadedApkSource = callbackResults.firstNotNullOfOrNull { it.loadedApkSource },
            loadedApkBindingMode = callbackResults.firstNotNullOfOrNull { it.loadedApkBindingMode },
            launchAuthorityStatus = callbackResults.firstNotNullOfOrNull { it.launchAuthorityStatus },
            launchAuthorityReason = callbackResults.firstNotNullOfOrNull { it.launchAuthorityReason },
            launchRecoveryStatus = callbackResults.firstNotNullOfOrNull { it.launchRecoveryStatus },
            launchRecoveryReason = callbackResults.firstNotNullOfOrNull { it.launchRecoveryReason },
            rolledBackFields = callbackResults.flatMap { it.rolledBackFields }.distinct(),
            launchCapabilityOwner = callbackResults.firstNotNullOfOrNull { it.launchCapabilityOwner }
        ).also { writeEvidence(it) }
    }

    internal fun patchLaunchRecord(
        record: Any,
        processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
        activityThreadProvider: () -> Any = ActivityThreadCompat::currentActivityThread
    ): ActivityThreadLaunchRecordPatchResult {
        val proxyIntent = readFirstField(record, listOf("intent", "mIntent")) as? Intent
            ?: return ActivityThreadLaunchRecordPatchResult(
                targetClassName = record.javaClass.name,
                skippedReason = "INTENT_FIELD_MISSING"
            )
        var spec = LaunchSpec.from(proxyIntent)
            ?: return ActivityThreadLaunchRecordPatchResult(
                targetClassName = record.javaClass.name,
                skippedReason = "NOT_MULTIAPP_PROXY_LAUNCH"
            )

        val proxyActivityClassName = resolveProxyActivityClassName(record, proxyIntent)
            ?: return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "ENGINE_PROXY_ACTIVITY_CLASS_MISSING",
                launchAuthorityStatus = "FAIL",
                launchAuthorityReason = "proxy_activity_class_missing"
            )
        if (!proxyActivityClassName.contains(".container.ProxyActivity")) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "ENGINE_PROXY_ACTIVITY_CLASS_INVALID",
                launchAuthorityStatus = "FAIL",
                launchAuthorityReason = "proxy_activity_class_invalid"
            )
        }
        var launchIdentity = proxyIntent.toVirtualActivityLaunchIdentity(proxyActivityClassName)
        var recoveryStatus: String? = null
        var recoveryReason: String? = null
        if (launchIdentity == null) {
            val recoveryRequest = recoveryRequest(proxyIntent, spec, proxyActivityClassName)
            val recovery = recoveryRequest?.let(VirtualActivityLaunchRecovery::recover)
            val recoveredIdentity = recovery?.identity?.takeIf { identity ->
                identity.instanceId == spec.instanceId &&
                    identity.processSlot == recoveryRequest.processSlot &&
                    identity.proxyActivityClassName == proxyActivityClassName
            }
            if (recovery?.recovered == true && recoveredIdentity != null) {
                applyRecoveredIdentity(proxyIntent, recoveredIdentity)
                spec = LaunchSpec.from(proxyIntent) ?: spec
                launchIdentity = recoveredIdentity
                recoveryStatus = "PENDING"
                recoveryReason = recovery.reason
            } else {
                recoveryStatus = "FAIL"
                recoveryReason = recovery?.reason ?: "activity_launch_recovery_request_invalid"
            }
            if (launchIdentity == null) {
                return skippedPrelaunchPatch(
                    record = record,
                    spec = spec,
                    reason = "ENGINE_LAUNCH_IDENTITY_MISSING",
                    launchAuthorityStatus = "FAIL",
                    launchAuthorityReason = "activity_launch_identity_missing",
                    launchRecoveryStatus = recoveryStatus,
                    launchRecoveryReason = recoveryReason
                )
            }
        }

        val snapshot = VirtualPackageRegistry.global.getByInstanceId(spec.instanceId)
            ?: return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "PACKAGE_SNAPSHOT_MISSING",
                launchAuthorityStatus = "NOT_CONSUMED",
                launchAuthorityReason = "runtime_not_ready:package_snapshot_missing",
                launchRecoveryStatus = recoveryStatus,
                launchRecoveryReason = recoveryReason
            )
        if (snapshot.originPackageName != spec.originPackageName) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "ENGINE_ORIGIN_PACKAGE_MISMATCH",
                launchAuthorityStatus = "FAIL",
                launchAuthorityReason = "snapshot_origin_package_mismatch",
                launchRecoveryStatus = recoveryStatus,
                launchRecoveryReason = recoveryReason
            )
        }
        if (snapshot.sourceDir.isBlank()) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "PACKAGE_SNAPSHOT_SOURCE_DIR_MISSING",
                launchAuthorityStatus = "NOT_CONSUMED",
                launchAuthorityReason = "runtime_not_ready:package_snapshot_source_dir_missing",
                launchRecoveryStatus = recoveryStatus,
                launchRecoveryReason = recoveryReason
            )
        }
        val runtimeResult = processRuntime.get(spec.instanceId)?.result
        val classLoader = runtimeResult?.guestClassLoader
            ?: return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "GUEST_CLASS_LOADER_MISSING",
                launchAuthorityStatus = "NOT_CONSUMED",
                launchAuthorityReason = "runtime_not_ready:guest_class_loader_missing",
                launchRecoveryStatus = recoveryStatus,
                launchRecoveryReason = recoveryReason
            )
        var state = buildRuntimeState(
            spec,
            proxyIntent,
            snapshot,
            classLoader,
            runtimeResult,
            activityThreadProvider
        )
        if (state.loadedApk == null) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = state.loadedApkSkippedReason ?: "LOADED_APK_PRELAUNCH_UNAVAILABLE",
                launchAuthorityStatus = "NOT_CONSUMED",
                launchAuthorityReason = "runtime_not_ready:${state.loadedApkSkippedReason.orEmpty()}",
                launchRecoveryStatus = recoveryStatus,
                launchRecoveryReason = recoveryReason
            )
        }
        var patchAttempt = patchLaunchRecordTransaction(record, state)
        if (!patchAttempt.complete) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = patchAttempt.failureReason ?: "LAUNCH_RECORD_PATCH_INCOMPLETE",
                launchAuthorityStatus = "NOT_CONSUMED",
                launchAuthorityReason = "record_patch_not_committed",
                launchRecoveryStatus = recoveryStatus,
                launchRecoveryReason = recoveryReason,
                skippedFields = patchAttempt.patch.skippedFields,
                rolledBackFields = patchAttempt.rolledBackFields,
                loadedApkSource = state.loadedApkSource,
                launchCapabilityOwner = INSTRUMENTATION_FALLBACK_CAPABILITY_OWNER
            )
        }

        var activePatchApplied = true
        var authorizedLaunch = VirtualActivityLaunchAuthority.authorize(requireNotNull(launchIdentity))
        if (!authorizedLaunch.accepted) {
            var rolledBackFields = rollbackLaunchRecord(patchAttempt)
            activePatchApplied = false
            val originalFailureReason = authorizedLaunch.reason
            val recoveryRequest = recoveryRequest(proxyIntent, spec, proxyActivityClassName)
            val recovery = recoveryRequest?.let(VirtualActivityLaunchRecovery::recover)
            val recoveredIdentity = recovery?.identity?.takeIf { identity ->
                identity.instanceId == spec.instanceId &&
                    identity.processSlot == recoveryRequest.processSlot &&
                    identity.proxyActivityClassName == proxyActivityClassName
            }
            if (recovery?.recovered == true && recoveredIdentity != null) {
                applyRecoveredIdentity(proxyIntent, recoveredIdentity)
                spec = LaunchSpec.from(proxyIntent) ?: spec
                launchIdentity = recoveredIdentity
                state = buildRuntimeState(
                    spec,
                    proxyIntent,
                    snapshot,
                    classLoader,
                    runtimeResult,
                    activityThreadProvider
                )
                if (state.loadedApk == null) {
                    return skippedPrelaunchPatch(
                        record = record,
                        spec = spec,
                        reason = state.loadedApkSkippedReason ?: "LOADED_APK_PRELAUNCH_UNAVAILABLE",
                        launchAuthorityStatus = "NOT_CONSUMED",
                        launchAuthorityReason = "runtime_not_ready_after_recovery:${state.loadedApkSkippedReason.orEmpty()}",
                        launchRecoveryStatus = "FAIL",
                        launchRecoveryReason = recovery.reason,
                        rolledBackFields = rolledBackFields,
                        launchCapabilityOwner = INSTRUMENTATION_FALLBACK_CAPABILITY_OWNER
                    )
                }
                patchAttempt = patchLaunchRecordTransaction(record, state)
                activePatchApplied = patchAttempt.complete
                if (!patchAttempt.complete) {
                    return skippedPrelaunchPatch(
                        record = record,
                        spec = spec,
                        reason = patchAttempt.failureReason ?: "LAUNCH_RECORD_PATCH_INCOMPLETE_AFTER_RECOVERY",
                        launchAuthorityStatus = "NOT_CONSUMED",
                        launchAuthorityReason = "record_patch_not_committed_after_recovery",
                        launchRecoveryStatus = "PARTIAL",
                        launchRecoveryReason = recovery.reason,
                        skippedFields = patchAttempt.patch.skippedFields,
                        rolledBackFields = (rolledBackFields + patchAttempt.rolledBackFields).distinct(),
                        loadedApkSource = state.loadedApkSource,
                        launchCapabilityOwner = INSTRUMENTATION_FALLBACK_CAPABILITY_OWNER
                    )
                }
                authorizedLaunch = VirtualActivityLaunchAuthority.authorize(recoveredIdentity)
                recoveryStatus = if (authorizedLaunch.accepted) "PASS" else "FAIL"
                recoveryReason = if (authorizedLaunch.accepted) {
                    recovery.reason
                } else {
                    "${recovery.reason}:${authorizedLaunch.reason}"
                }
            } else {
                recoveryStatus = "FAIL"
                recoveryReason = recovery?.reason ?: "activity_launch_recovery_request_invalid"
            }
            if (!authorizedLaunch.accepted) {
                if (activePatchApplied) {
                    rolledBackFields = (rolledBackFields + rollbackLaunchRecord(patchAttempt)).distinct()
                    activePatchApplied = false
                }
                return skippedPrelaunchPatch(
                    record = record,
                    spec = spec,
                    reason = "ENGINE_LAUNCH_REJECTED:${authorizedLaunch.reason}",
                    launchAuthorityStatus = "FAIL",
                    launchAuthorityReason = authorizedLaunch.reason.ifBlank { originalFailureReason },
                    launchRecoveryStatus = recoveryStatus,
                    launchRecoveryReason = recoveryReason,
                    rolledBackFields = rolledBackFields
                )
            }
        } else if (recoveryStatus == "PENDING") {
            recoveryStatus = "PASS"
        }
        val activityRecordRegistration = registerPatchedActivityRecord(
            spec = spec,
            proxyIntent = proxyIntent,
            guestIntent = state.guestIntent,
            proxyActivityClassName = proxyActivityClassName
        )
        if (!activityRecordRegistration.accepted) {
            val rolledBackFields = rollbackLaunchRecord(patchAttempt)
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "ACTIVITY_RECORD_REGISTRATION_FAILED:${activityRecordRegistration.reason}",
                launchAuthorityStatus = "FAIL",
                launchAuthorityReason = "activity_record_registration_failed",
                launchRecoveryStatus = recoveryStatus,
                launchRecoveryReason = recoveryReason,
                rolledBackFields = rolledBackFields,
                loadedApkSource = state.loadedApkSource,
                launchCapabilityOwner = RECORD_PATCH_CAPABILITY_OWNER
            )
        }
        prepatchedLaunchIdentities[activityRecordRegistration.token] = launchIdentity
        val patch = patchAttempt.patch
        val result = ActivityThreadLaunchRecordPatchResult(
            targetClassName = record.javaClass.name,
            observedProxyLaunch = true,
            patchedFields = patch.patchedFields,
            skippedFields = patch.skippedFields,
            skippedReason = patch.skippedReason ?: state.loadedApkSkippedReason,
            instanceId = spec.instanceId,
            guestActivityClassName = spec.guestActivityClassName,
            token = spec.token,
            loadedApkSource = state.loadedApkSource,
            loadedApkBindingMode = patchAttempt.loadedApkBindingMode,
            launchAuthorityStatus = "PASS",
            launchAuthorityReason = authorizedLaunch.reason,
            launchRecoveryStatus = recoveryStatus,
            launchRecoveryReason = recoveryReason,
            launchCapabilityOwner = RECORD_PATCH_CAPABILITY_OWNER
        )
        safeLogInfo(
            "Prepatched launch record: target=${result.targetClassName}, instance=${spec.instanceId}, " +
                "guest=${spec.guestActivityClassName}, fields=${result.patchedFields.joinToString(",")}, " +
                "loadedApk=${result.loadedApkSource ?: result.skippedReason.orEmpty()}"
        )
        return result.also { writeEvidence(it) }
    }

    internal fun consumePrepatchedLaunchIdentity(
        token: String,
        identity: VirtualActivityLaunchIdentity
    ): Boolean = prepatchedLaunchIdentities.remove(token, identity)

    internal fun clearPrepatchedLaunchIdentitiesForTests() {
        prepatchedLaunchIdentities.clear()
    }

    private fun registerPatchedActivityRecord(
        spec: LaunchSpec,
        proxyIntent: Intent,
        guestIntent: Intent,
        proxyActivityClassName: String
    ): ActivityRecordRegistration {
        val token = spec.token?.takeIf { it.isNotBlank() }
            ?: return ActivityRecordRegistration(reason = "TOKEN_MISSING")
        val manager = VirtualActivityRecordManager.global
        val existing = manager.resolve(token)
        if (existing != null) {
            val matches = existing.instanceId == spec.instanceId &&
                existing.originPackageName == spec.originPackageName &&
                existing.guestActivityClassName == spec.guestActivityClassName &&
                existing.proxyActivityClassName == proxyActivityClassName
            return if (matches) {
                ActivityRecordRegistration(accepted = true, token = token)
            } else {
                ActivityRecordRegistration(reason = "TOKEN_OWNER_MISMATCH")
            }
        }
        val resultToToken = proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_RESULT_TO_TOKEN)
            ?.takeIf { it.isNotBlank() }
        val record = VirtualActivityRecord(
            token = token,
            instanceId = spec.instanceId,
            originPackageName = spec.originPackageName,
            guestActivityClassName = spec.guestActivityClassName,
            proxyActivityClassName = proxyActivityClassName,
            launchMode = spec.launchMode,
            taskAffinity = spec.taskAffinity,
            resultToToken = resultToToken,
            resultRequestCode = if (resultToToken == null) {
                -1
            } else {
                proxyIntent.getIntExtra(VirtualActivityManager.EXTRA_RESULT_REQUEST_CODE, -1)
            },
            state = VirtualActivityState.RESUMED
        )
        if (manager.conflictingProxyOwner(record) != null) {
            return ActivityRecordRegistration(reason = "PROXY_SLOT_ALREADY_OWNED")
        }
        val sourceIntent = VirtualActivityIntentStore.find(token) ?: guestIntent
        val launched = manager.registerLaunch(
            record = record,
            intentFlags = runCatching { sourceIntent.flags }.getOrDefault(0),
            dataIntent = sourceIntent.toVirtualIntentSnapshot()
        ).activity
        return if (launched.token == token) {
            ActivityRecordRegistration(accepted = true, token = token)
        } else {
            ActivityRecordRegistration(reason = "PROXY_SLOT_ALREADY_OWNED")
        }
    }

    private fun Intent.toVirtualIntentSnapshot(): VirtualIntentSnapshot {
        val sourceExtras = runCatching { extras }.getOrNull()
        return VirtualIntentSnapshot(
            flags = runCatching { flags }.getOrDefault(0),
            action = runCatching { action }.getOrNull(),
            dataUri = runCatching { dataString?.let(EvidenceSanitizer::redactUriForEvidence) }.getOrNull(),
            categories = runCatching { categories.orEmpty().toSet() }.getOrDefault(emptySet()),
            extras = sourceExtras?.keySet()?.associateWith { "<present>" }.orEmpty()
        )
    }

    private fun patchLaunchRecordTransaction(
        record: Any,
        state: LaunchRuntimeState
    ): LaunchRecordPatchAttempt {
        val loadedApkField = LOADED_APK_FIELDS.firstNotNullOfOrNull { name ->
            findFieldInHierarchy(record.javaClass, name)
        }
        val frameworkDerivesLoadedApk = loadedApkField == null && isFrameworkLaunchTransactionItem(record)
        if (loadedApkField == null && !frameworkDerivesLoadedApk) {
            return LaunchRecordPatchAttempt(
                patch = ActivityClientRecordPatchResult(
                    targetClassName = record.javaClass.name,
                    skippedFields = listOf("packageInfo"),
                    skippedReason = "REQUIRED_FIELD_MISSING:packageInfo"
                ),
                failureReason = "LAUNCH_RECORD_PATCH_INCOMPLETE:REQUIRED_FIELD_MISSING:packageInfo"
            )
        }
        val requiredFields = mutableListOf(
            RequiredRecordField("activityInfo", ACTIVITY_INFO_FIELDS, state.activityInfo),
            RequiredRecordField("intent", INTENT_FIELDS, state.guestIntent)
        )
        if (loadedApkField != null) {
            requiredFields += RequiredRecordField(
                "packageInfo",
                listOf(loadedApkField.name),
                requireNotNull(state.loadedApk)
            )
        }
        val snapshots = mutableListOf<RecordFieldSnapshot>()
        for (required in requiredFields) {
            val field = required.aliases.firstNotNullOfOrNull { name ->
                findFieldInHierarchy(record.javaClass, name)
            } ?: return LaunchRecordPatchAttempt(
                patch = ActivityClientRecordPatchResult(
                    targetClassName = record.javaClass.name,
                    skippedFields = listOf(required.canonicalName),
                    skippedReason = "REQUIRED_FIELD_MISSING:${required.canonicalName}"
                ),
                failureReason = "LAUNCH_RECORD_PATCH_INCOMPLETE:REQUIRED_FIELD_MISSING:${required.canonicalName}"
            )
            val originalValue = runCatching { field.get(record) }.getOrElse { error ->
                return LaunchRecordPatchAttempt(
                    patch = ActivityClientRecordPatchResult(
                        targetClassName = record.javaClass.name,
                        skippedFields = listOf(field.name),
                        skippedReason = "REQUIRED_FIELD_READ_FAILED:${field.name}:${error.javaClass.name}"
                    ),
                    failureReason = "LAUNCH_RECORD_PATCH_INCOMPLETE:REQUIRED_FIELD_READ_FAILED:${field.name}"
                )
            }
            snapshots += RecordFieldSnapshot(
                canonicalName = required.canonicalName,
                owner = record,
                field = field,
                originalValue = originalValue,
                expectedValue = required.value
            )
        }

        val rawPatch = ActivityClientRecordBridge.patch(
            record = record,
            state = ActivityClientRecordRuntimeState(
                activityInfo = state.activityInfo,
                intent = state.guestIntent,
                loadedApk = state.loadedApk
            )
        )
        val patch = if (frameworkDerivesLoadedApk) {
            rawPatch.copy(skippedFields = rawPatch.skippedFields - "packageInfo")
        } else {
            rawPatch
        }
        val patchedNames = patch.patchedFields.toSet()
        val failedFields = snapshots.filter { snapshot ->
            snapshot.field.name !in patchedNames ||
                runCatching { snapshot.field.get(record) === snapshot.expectedValue }.getOrDefault(false).not()
        }
        if (failedFields.isEmpty()) {
            return LaunchRecordPatchAttempt(
                patch = patch,
                snapshots = snapshots,
                complete = true,
                loadedApkBindingMode = if (frameworkDerivesLoadedApk) {
                    LOADED_APK_BINDING_FRAMEWORK_DERIVED
                } else {
                    LOADED_APK_BINDING_RECORD_PATCHED
                }
            )
        }

        val rolledBackFields = rollbackLaunchRecordSnapshots(snapshots)
        val failedNames = failedFields.joinToString(",") { it.canonicalName }
        return LaunchRecordPatchAttempt(
            patch = patch.copy(
                patchedFields = emptyList(),
                skippedFields = (patch.skippedFields + failedFields.map { it.field.name }).distinct(),
                skippedReason = "REQUIRED_FIELD_PATCH_FAILED:$failedNames"
            ),
            failureReason = "LAUNCH_RECORD_PATCH_INCOMPLETE:REQUIRED_FIELD_PATCH_FAILED:$failedNames",
            rolledBackFields = rolledBackFields
        )
    }

    private fun rollbackLaunchRecord(attempt: LaunchRecordPatchAttempt): List<String> {
        check(attempt.complete && attempt.snapshots.isNotEmpty()) {
            "Cannot rollback an incomplete launch record transaction"
        }
        return rollbackLaunchRecordSnapshots(attempt.snapshots)
    }

    private fun rollbackLaunchRecordSnapshots(snapshots: List<RecordFieldSnapshot>): List<String> {
        val failures = mutableListOf<String>()
        snapshots.asReversed().forEach { snapshot ->
            runCatching { snapshot.field.set(snapshot.owner, snapshot.originalValue) }
                .onFailure { failures += "${snapshot.field.name}:write" }
        }
        snapshots.forEach { snapshot ->
            val restored = runCatching {
                snapshot.field.get(snapshot.owner) === snapshot.originalValue
            }.getOrDefault(false)
            if (!restored) failures += "${snapshot.field.name}:verify"
        }
        check(failures.isEmpty()) {
            "LAUNCH_RECORD_ROLLBACK_FAILED:${failures.distinct().joinToString(",")}"
        }
        return snapshots.map { it.field.name }
    }

    private fun isFrameworkLaunchTransactionItem(record: Any): Boolean {
        val className = record.javaClass.name
        return className == "android.app.servertransaction.LaunchActivityItem" ||
            record.javaClass.simpleName.endsWith("LaunchActivityItem")
    }

    private fun buildRuntimeState(
        spec: LaunchSpec,
        proxyIntent: Intent,
        snapshot: VirtualPackageSnapshot,
        classLoader: ClassLoader,
        runtimeResult: HostedBootstrapResult?,
        activityThreadProvider: () -> Any
    ): LaunchRuntimeState {
        val hostApplication = runCatching { ActivityThreadCompat.currentApplication() }.getOrNull()
        val processSlot = runtimeResult?.processSlot
        val config = VirtualContextConfig(
            instanceId = spec.instanceId,
            originPackageName = spec.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = snapshot.nativeLibraryDir,
            classLoader = classLoader,
            applicationLabel = snapshot.applicationLabel,
            packageSnapshot = snapshot,
            splitSourceDirs = snapshot.splitSourceDirs,
            splitPublicSourceDirs = snapshot.splitPublicSourceDirs,
            splitNames = snapshot.splitNames,
            isolatedSplits = snapshot.isolatedSplits,
            processSlot = processSlot
        )
        val resourceBundle = hostApplication?.let { VirtualResourcesManager(it).create(config) }
        val applicationInfo = resourceBundle
            ?.let { HostedActivityIdentity.applicationInfoForRuntime(config, it.applicationInfo) }
            ?: android.content.pm.ApplicationInfo().apply {
                packageName = config.originPackageName
                sourceDir = config.sourceDir
                publicSourceDir = config.publicSourceDir
                if (config.splitSourceDirs.isNotEmpty()) {
                    splitSourceDirs = config.splitSourceDirs.toTypedArray()
                }
                val publicDirs = config.splitPublicSourceDirs.ifEmpty { config.splitSourceDirs }
                if (publicDirs.isNotEmpty()) {
                    splitPublicSourceDirs = publicDirs.toTypedArray()
                }
                if (config.splitNames.isNotEmpty()) {
                    splitNames = config.splitNames.toTypedArray()
                }
                dataDir = config.dataDir
                ApplicationInfoNativePathCompat.applyTo(this, config.dataDir, config.nativeLibraryDir)
                enabled = true
            }
        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = spec.guestActivityClassName,
            applicationInfo = applicationInfo
        ).apply {
            launchMode = launchModeFrom(spec.launchMode)
            if (!spec.taskAffinity.isNullOrBlank()) taskAffinity = spec.taskAffinity
        }
        val guestIntent = buildGuestIntent(spec, proxyIntent)
        val loadedApk = resolvePrewarmedLoadedApk(
            spec = spec,
            snapshot = snapshot,
            runtimeResult = runtimeResult,
            classLoader = classLoader,
            activityThreadProvider = activityThreadProvider
        )
        return LaunchRuntimeState(
            activityInfo = activityInfo,
            guestIntent = guestIntent,
            loadedApk = loadedApk?.loadedApk,
            loadedApkSource = loadedApk?.source?.name,
            loadedApkSkippedReason = loadedApk?.skippedReason
                ?: if (loadedApk == null) "PREWARMED_LOADED_APK_NOT_FOUND" else null
        )
    }

    private fun skippedPrelaunchPatch(
        record: Any,
        spec: LaunchSpec,
        reason: String,
        launchAuthorityStatus: String? = null,
        launchAuthorityReason: String? = null,
        launchRecoveryStatus: String? = null,
        launchRecoveryReason: String? = null,
        skippedFields: List<String> = emptyList(),
        rolledBackFields: List<String> = emptyList(),
        loadedApkSource: String? = null,
        launchCapabilityOwner: String? = when (launchAuthorityStatus) {
            "NOT_CONSUMED" -> INSTRUMENTATION_FALLBACK_CAPABILITY_OWNER
            else -> null
        }
    ): ActivityThreadLaunchRecordPatchResult {
        val result = ActivityThreadLaunchRecordPatchResult(
            targetClassName = record.javaClass.name,
            observedProxyLaunch = true,
            skippedFields = skippedFields,
            skippedReason = reason,
            instanceId = spec.instanceId,
            guestActivityClassName = spec.guestActivityClassName,
            token = spec.token,
            loadedApkSource = loadedApkSource,
            launchAuthorityStatus = launchAuthorityStatus,
            launchAuthorityReason = launchAuthorityReason,
            launchRecoveryStatus = launchRecoveryStatus,
            launchRecoveryReason = launchRecoveryReason,
            rolledBackFields = rolledBackFields,
            launchCapabilityOwner = launchCapabilityOwner
        )
        safeLogWarning(
            "Skipped prelaunch guest record patch: instance=${spec.instanceId}, " +
                "guest=${spec.guestActivityClassName}, reason=$reason"
        )
        return result.also { writeEvidence(it) }
    }

    private fun resolveProxyActivityClassName(record: Any, proxyIntent: Intent): String? {
        val recordActivityInfo = readFirstField(
            record,
            listOf("activityInfo", "mActivityInfo", "info", "mInfo")
        ) as? ActivityInfo
        return recordActivityInfo?.name?.takeIf { it.isNotBlank() }
            ?: proxyIntent.component?.className?.takeIf { it.isNotBlank() }
            ?: proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME)
                ?.takeIf { it.isNotBlank() }
    }

    private fun recoveryRequest(
        proxyIntent: Intent,
        spec: LaunchSpec,
        proxyActivityClassName: String
    ): VirtualActivityLaunchRecoveryRequest? {
        val restoreActivityId = spec.token?.takeIf { it.isNotBlank() } ?: return null
        val processSlot = proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            VirtualActivityLaunchRecoveryRequest(
                instanceId = spec.instanceId,
                previousRuntimeEpoch = proxyIntent.getLongExtra(
                    VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH,
                    0L
                ).coerceAtLeast(0L),
                previousEngineSessionId = proxyIntent.getStringExtra(
                    VirtualActivityManager.EXTRA_ENGINE_SESSION_ID
                )?.takeIf { it.isNotBlank() },
                processSlot = processSlot,
                proxyActivityClassName = proxyActivityClassName,
                guestActivityClassName = spec.guestActivityClassName,
                restoreActivityId = restoreActivityId
            )
        }.getOrNull()
    }

    private fun applyRecoveredIdentity(
        proxyIntent: Intent,
        identity: VirtualActivityLaunchIdentity
    ) {
        proxyIntent.putExtra(VirtualActivityManager.EXTRA_INSTANCE_ID, identity.instanceId)
        proxyIntent.putExtra(
            VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME,
            identity.guestActivityClassName
        )
        proxyIntent.putExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, identity.runtimeEpoch)
        proxyIntent.putExtra(VirtualActivityManager.EXTRA_ENGINE_SESSION_ID, identity.engineSessionId)
        proxyIntent.putExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT, identity.processSlot)
        proxyIntent.putExtra(
            VirtualActivityManager.EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME,
            identity.proxyActivityClassName
        )
        proxyIntent.putExtra(
            VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY,
            identity.capabilityToken
        )
    }

    private fun buildGuestIntent(spec: LaunchSpec, proxyIntent: Intent): Intent {
        val guestIntent = VirtualActivityIntentStore.find(spec.token)
            ?: legacyOriginalGuestIntent(proxyIntent)
            ?: Intent(proxyIntent)
        return guestIntent.apply {
            runCatching { component = ComponentName(spec.originPackageName, spec.guestActivityClassName) }
            runCatching { setPackage(spec.originPackageName) }
            runCatching { putExtra(VirtualActivityManager.EXTRA_INSTANCE_ID, spec.instanceId) }
            runCatching { putExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME, spec.originPackageName) }
            runCatching { putExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME, spec.guestActivityClassName) }
            spec.token?.takeIf { it.isNotBlank() }?.let {
                runCatching { putExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN, it) }
            }
            spec.hostPackageName?.takeIf { it.isNotBlank() }?.let {
                runCatching { putExtra(VirtualActivityManager.EXTRA_HOST_PACKAGE_NAME, it) }
            }
            runCatching {
                putExtra(
                    VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH,
                    proxyIntent.getLongExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, 0L)
                )
            }
            listOf(
                VirtualActivityManager.EXTRA_ENGINE_SESSION_ID,
                VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT,
                VirtualActivityManager.EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME,
                VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY
            ).forEach { key ->
                proxyIntent.getStringExtra(key)?.let { value ->
                    runCatching { putExtra(key, value) }
                }
            }
        }
    }

    private fun resolvePrewarmedLoadedApk(
        spec: LaunchSpec,
        snapshot: VirtualPackageSnapshot,
        runtimeResult: HostedBootstrapResult?,
        classLoader: ClassLoader,
        activityThreadProvider: () -> Any
    ): ActivityThreadLoadedApkInstallResult? {
        val aliases = listOf(spec.originPackageName, snapshot.virtualPackageName)
        val activityThread = runCatching(activityThreadProvider)
            .onFailure { error ->
                safeLogWarning("Unable to inspect prewarmed LoadedApk for ${spec.guestActivityClassName}", error)
            }
            .getOrNull() ?: return null
        val resolved = ActivityThreadLoadedApkInstaller.findInstalledGuest(activityThread, aliases)
            ?: return ActivityThreadLoadedApkInstaller.skippedInstallResult(
                targetClassName = "",
                packageAliases = aliases,
                skippedReason = "PREWARMED_LOADED_APK_NOT_FOUND",
                source = LoadedApkInstallSource.PREWARMED_GUEST
            )
        val loadedApk = resolved.loadedApk ?: return resolved
        val expectedApplication = runtimeResult?.guestApplication
            ?: return ActivityThreadLoadedApkInstaller.skippedInstallResult(
                targetClassName = loadedApk.javaClass.name,
                packageAliases = aliases,
                skippedReason = "PREWARMED_GUEST_APPLICATION_MISSING",
                source = LoadedApkInstallSource.PREWARMED_GUEST
            )
        if (LoadedApkBridge.application(loadedApk) !== expectedApplication) {
            return ActivityThreadLoadedApkInstaller.skippedInstallResult(
                targetClassName = loadedApk.javaClass.name,
                packageAliases = aliases,
                skippedReason = "PREWARMED_LOADED_APK_APPLICATION_MISMATCH",
                source = LoadedApkInstallSource.PREWARMED_GUEST
            )
        }
        if (LoadedApkBridge.classLoader(loadedApk) !== classLoader) {
            return ActivityThreadLoadedApkInstaller.skippedInstallResult(
                targetClassName = loadedApk.javaClass.name,
                packageAliases = aliases,
                skippedReason = "PREWARMED_LOADED_APK_CLASS_LOADER_MISMATCH",
                source = LoadedApkInstallSource.PREWARMED_GUEST
            )
        }
        val loadedApkPackageName = LoadedApkBridge.inspect(loadedApk).packageName
        if (loadedApkPackageName != spec.originPackageName) {
            return ActivityThreadLoadedApkInstaller.skippedInstallResult(
                targetClassName = loadedApk.javaClass.name,
                packageAliases = aliases,
                skippedReason =
                    "PREWARMED_LOADED_APK_PACKAGE_MISMATCH:" +
                        "${loadedApkPackageName.orEmpty()}!=${spec.originPackageName}",
                source = LoadedApkInstallSource.PREWARMED_GUEST
            )
        }
        return resolved
    }

    @Suppress("DEPRECATION")
    private fun legacyOriginalGuestIntent(proxyIntent: Intent): Intent? =
        runCatching {
            proxyIntent.getParcelableExtra<Intent>(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT)
        }.onFailure { error ->
            safeLogWarning("Ignoring legacy original guest Activity intent extra: ${error.javaClass.simpleName}")
        }.getOrNull()

    private fun safeLogInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun safeLogWarning(message: String, error: Throwable? = null) {
        runCatching {
            if (error == null) {
                Log.w(TAG, message)
            } else {
                Log.w(TAG, message, error)
            }
        }
    }

    private fun activityCallbacks(transaction: Any): List<Any> {
        val callbackContainers = listOfNotNull(
            readFirstField(
                transaction,
                listOf("mActivityCallbacks", "activityCallbacks", "callbacks", "mTransactionItems", "transactionItems")
            ),
            runCatching {
                transaction.javaClass.getMethod("getTransactionItems").invoke(transaction)
            }.getOrNull()
        )
        return callbackContainers
            .flatMap { container ->
                when (container) {
                    is Iterable<*> -> container.filterNotNull()
                    is Array<*> -> container.filterNotNull()
                    else -> listOf(container)
                }
            }
            .distinctBy { System.identityHashCode(it) }
    }

    private fun readFirstField(target: Any, names: List<String>): Any? {
        for (name in names) {
            val field = findFieldInHierarchy(target.javaClass, name) ?: continue
            return runCatching { field.get(target) }.getOrNull()
        }
        return null
    }

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

    private fun launchModeFrom(launchMode: String?): Int = when (launchMode) {
        "singleTop" -> ActivityInfo.LAUNCH_SINGLE_TOP
        "singleTask" -> ActivityInfo.LAUNCH_SINGLE_TASK
        "singleInstance" -> ActivityInfo.LAUNCH_SINGLE_INSTANCE
        "singleInstancePerTask" -> 4
        else -> ActivityInfo.LAUNCH_MULTIPLE
    }

    private fun writeEvidence(result: ActivityThreadLaunchRecordPatchResult) {
        val instanceId = result.instanceId?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val filesDir = hostFilesDirFor(instanceId) ?: ActivityThreadCompat.currentApplication().filesDir
            val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }.canonicalFile
            val file = File(evidenceDir, HostedActivityEvidenceFiles.launchRecord(instanceId)).canonicalFile
            require(file.parentFile == evidenceDir) { "Launch record evidence path escapes evidence dir" }
            val verdict = launchRecordVerdict(result)
            file.writeText(
                listOf(
                    "status=$verdict",
                    "stage=ACTIVITY_THREAD_LAUNCH_RECORD",
                    "targetClassName=${result.targetClassName.orEmpty()}",
                    "instanceId=$instanceId",
                    "token=${EvidenceSanitizer.redactTokenForEvidence(result.token)}",
                    "guestActivityClassName=${result.guestActivityClassName.orEmpty()}",
                    "preLaunchPatchVerdict=$verdict",
                    "patchedFields=${result.patchedFields.joinToString(",")}",
                    "skippedFields=${result.skippedFields.joinToString(",")}",
                    "skippedReason=${result.skippedReason.orEmpty()}",
                    "loadedApkSource=${result.loadedApkSource.orEmpty()}",
                    "loadedApkBindingMode=${result.loadedApkBindingMode.orEmpty()}",
                    "launchAuthorityStatus=${result.launchAuthorityStatus.orEmpty()}",
                    "launchAuthorityReason=${result.launchAuthorityReason.orEmpty()}",
                    "launchRecoveryStatus=${result.launchRecoveryStatus.orEmpty()}",
                    "launchRecoveryReason=${result.launchRecoveryReason.orEmpty()}",
                    "rolledBackFields=${result.rolledBackFields.joinToString(",")}",
                    "launchCapabilityOwner=${result.launchCapabilityOwner.orEmpty()}",
                    "patchedRecordCount=${result.patchedRecordCount}"
                ).joinToString("\n")
            )
        }
    }

    private fun hostFilesDirFor(instanceId: String): File? {
        val dataRoot = VirtualPackageRegistry.global.getByInstanceId(instanceId)
            ?.dataDir
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val instanceRoot = runCatching { File(dataRoot).canonicalFile }.getOrNull() ?: return null
        val instanceDataDir = instanceRoot.parentFile ?: return null
        if (instanceDataDir.name != "instance_data") return null
        return instanceDataDir.parentFile?.canonicalFile
    }

    internal fun launchRecordVerdict(result: ActivityThreadLaunchRecordPatchResult): String {
        val patchedLaunchIdentity = result.patchedFields.any { it == "intent" || it == "mIntent" } &&
            result.patchedFields.any { it == "activityInfo" || it == "mActivityInfo" || it == "info" || it == "mInfo" }
        val loadedApkPatched = result.patchedFields.any {
            it == "packageInfo" || it == "mPackageInfo" || it == "loadedApk" || it == "mLoadedApk"
        }
        val loadedApkReady = !result.loadedApkSource.isNullOrBlank() &&
            (loadedApkPatched || result.loadedApkBindingMode == LOADED_APK_BINDING_FRAMEWORK_DERIVED)
        return if (patchedLaunchIdentity && loadedApkReady && result.launchAuthorityStatus == "PASS") {
            "PASS"
        } else {
            "PARTIAL"
        }
    }

    private data class LaunchSpec(
        val token: String?,
        val instanceId: String,
        val originPackageName: String,
        val guestActivityClassName: String,
        val hostPackageName: String?,
        val launchMode: String?,
        val taskAffinity: String?
    ) {
        companion object {
            fun from(intent: Intent): LaunchSpec? {
                val instanceId = intent.getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                val originPackageName = intent.getStringExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                val guestActivityClassName = intent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                return LaunchSpec(
                    token = intent.getStringExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN),
                    instanceId = instanceId,
                    originPackageName = originPackageName,
                    guestActivityClassName = guestActivityClassName,
                    hostPackageName = intent.getStringExtra(VirtualActivityManager.EXTRA_HOST_PACKAGE_NAME),
                    launchMode = intent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_LAUNCH_MODE),
                    taskAffinity = intent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_TASK_AFFINITY)
                )
            }
        }
    }

    private data class LaunchRuntimeState(
        val activityInfo: ActivityInfo,
        val guestIntent: Intent,
        val loadedApk: Any?,
        val loadedApkSource: String?,
        val loadedApkSkippedReason: String?
    )

    private data class RequiredRecordField(
        val canonicalName: String,
        val aliases: List<String>,
        val value: Any
    )

    private data class RecordFieldSnapshot(
        val canonicalName: String,
        val owner: Any,
        val field: java.lang.reflect.Field,
        val originalValue: Any?,
        val expectedValue: Any
    )

    private data class LaunchRecordPatchAttempt(
        val patch: ActivityClientRecordPatchResult,
        val snapshots: List<RecordFieldSnapshot> = emptyList(),
        val complete: Boolean = false,
        val failureReason: String? = null,
        val rolledBackFields: List<String> = emptyList(),
        val loadedApkBindingMode: String? = null
    )

    private data class ActivityRecordRegistration(
        val accepted: Boolean = false,
        val token: String = "",
        val reason: String = ""
    )
}

data class ActivityThreadLaunchRecordPatchResult(
    val targetClassName: String? = null,
    val observedProxyLaunch: Boolean = false,
    val patchedFields: List<String> = emptyList(),
    val skippedFields: List<String> = emptyList(),
    val skippedReason: String? = null,
    val patchedRecordCount: Int = if (patchedFields.isEmpty()) 0 else 1,
    val instanceId: String? = null,
    val guestActivityClassName: String? = null,
    val token: String? = null,
    val loadedApkSource: String? = null,
    val loadedApkBindingMode: String? = null,
    val launchAuthorityStatus: String? = null,
    val launchAuthorityReason: String? = null,
    val launchRecoveryStatus: String? = null,
    val launchRecoveryReason: String? = null,
    val rolledBackFields: List<String> = emptyList(),
    val launchCapabilityOwner: String? = null
)
