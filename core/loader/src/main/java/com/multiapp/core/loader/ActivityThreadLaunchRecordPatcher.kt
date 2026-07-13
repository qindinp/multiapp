package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Message
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File

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
            launchAuthorityStatus = callbackResults.firstNotNullOfOrNull { it.launchAuthorityStatus },
            launchAuthorityReason = callbackResults.firstNotNullOfOrNull { it.launchAuthorityReason }
        ).also { writeEvidence(it) }
    }

    internal fun patchLaunchRecord(
        record: Any,
        processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global
    ): ActivityThreadLaunchRecordPatchResult {
        val proxyIntent = readFirstField(record, listOf("intent", "mIntent")) as? Intent
            ?: return ActivityThreadLaunchRecordPatchResult(
                targetClassName = record.javaClass.name,
                skippedReason = "INTENT_FIELD_MISSING"
            )
        val spec = LaunchSpec.from(proxyIntent)
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
        val launchIdentity = proxyIntent.toVirtualActivityLaunchIdentity(proxyActivityClassName)
            ?: return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "ENGINE_LAUNCH_IDENTITY_MISSING",
                launchAuthorityStatus = "FAIL",
                launchAuthorityReason = "activity_launch_identity_missing"
            )
        val launchAuthorization = VirtualActivityLaunchAuthority.authorize(launchIdentity)
        if (!launchAuthorization.accepted) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "ENGINE_LAUNCH_REJECTED:${launchAuthorization.reason}",
                launchAuthorityStatus = "FAIL",
                launchAuthorityReason = launchAuthorization.reason
            )
        }

        val snapshot = VirtualPackageRegistry.global.getByInstanceId(spec.instanceId)
            ?: return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "PACKAGE_SNAPSHOT_MISSING",
                launchAuthorityStatus = "PASS",
                launchAuthorityReason = launchAuthorization.reason
            )
        if (snapshot.sourceDir.isBlank()) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "PACKAGE_SNAPSHOT_SOURCE_DIR_MISSING",
                launchAuthorityStatus = "PASS",
                launchAuthorityReason = launchAuthorization.reason
            )
        }
        val runtimeResult = processRuntime.get(spec.instanceId)?.result
        val classLoader = runtimeResult?.guestClassLoader
            ?: return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = "GUEST_CLASS_LOADER_MISSING",
                launchAuthorityStatus = "PASS",
                launchAuthorityReason = launchAuthorization.reason
            )
        val state = buildRuntimeState(spec, proxyIntent, snapshot, classLoader, runtimeResult)
        if (state.loadedApk == null) {
            return skippedPrelaunchPatch(
                record = record,
                spec = spec,
                reason = state.loadedApkSkippedReason ?: "LOADED_APK_PRELAUNCH_UNAVAILABLE",
                launchAuthorityStatus = "PASS",
                launchAuthorityReason = launchAuthorization.reason
            )
        }
        val patch = ActivityClientRecordBridge.patch(
            record = record,
            state = ActivityClientRecordRuntimeState(
                activityInfo = state.activityInfo,
                intent = state.guestIntent,
                loadedApk = state.loadedApk
            )
        )
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
            launchAuthorityStatus = "PASS",
            launchAuthorityReason = launchAuthorization.reason
        )
        safeLogInfo(
            "Prepatched launch record: target=${result.targetClassName}, instance=${spec.instanceId}, " +
                "guest=${spec.guestActivityClassName}, fields=${result.patchedFields.joinToString(",")}, " +
                "loadedApk=${result.loadedApkSource ?: result.skippedReason.orEmpty()}"
        )
        return result.also { writeEvidence(it) }
    }

    private fun buildRuntimeState(
        spec: LaunchSpec,
        proxyIntent: Intent,
        snapshot: VirtualPackageSnapshot,
        classLoader: ClassLoader,
        runtimeResult: HostedBootstrapResult?
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
            classLoader = classLoader
        )
        return LaunchRuntimeState(
            activityInfo = activityInfo,
            guestIntent = guestIntent,
            loadedApk = loadedApk?.loadedApk,
            loadedApkSource = loadedApk?.source?.name,
            loadedApkSkippedReason = loadedApk?.skippedReason
                ?: "PREWARMED_LOADED_APK_NOT_FOUND"
        )
    }

    private fun skippedPrelaunchPatch(
        record: Any,
        spec: LaunchSpec,
        reason: String,
        launchAuthorityStatus: String? = null,
        launchAuthorityReason: String? = null
    ): ActivityThreadLaunchRecordPatchResult {
        val result = ActivityThreadLaunchRecordPatchResult(
            targetClassName = record.javaClass.name,
            observedProxyLaunch = true,
            skippedReason = reason,
            instanceId = spec.instanceId,
            guestActivityClassName = spec.guestActivityClassName,
            token = spec.token,
            launchAuthorityStatus = launchAuthorityStatus,
            launchAuthorityReason = launchAuthorityReason
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
        }
    }

    private fun resolvePrewarmedLoadedApk(
        spec: LaunchSpec,
        snapshot: VirtualPackageSnapshot,
        runtimeResult: HostedBootstrapResult?,
        classLoader: ClassLoader
    ): ActivityThreadLoadedApkInstallResult? {
        val aliases = listOf(spec.originPackageName, snapshot.virtualPackageName)
        val activityThread = runCatching { ActivityThreadCompat.currentActivityThread() }
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
            val filesDir = ActivityThreadCompat.currentApplication().filesDir
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
                    "launchAuthorityStatus=${result.launchAuthorityStatus.orEmpty()}",
                    "launchAuthorityReason=${result.launchAuthorityReason.orEmpty()}",
                    "patchedRecordCount=${result.patchedRecordCount}"
                ).joinToString("\n")
            )
        }
    }

    internal fun launchRecordVerdict(result: ActivityThreadLaunchRecordPatchResult): String {
        val patchedLaunchIdentity = result.patchedFields.any { it == "intent" || it == "mIntent" } &&
            result.patchedFields.any { it == "activityInfo" || it == "mActivityInfo" || it == "info" || it == "mInfo" }
        val loadedApkReady = !result.loadedApkSource.isNullOrBlank() ||
            result.patchedFields.any { it == "packageInfo" || it == "mPackageInfo" || it == "loadedApk" || it == "mLoadedApk" }
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
    val launchAuthorityStatus: String? = null,
    val launchAuthorityReason: String? = null
)
