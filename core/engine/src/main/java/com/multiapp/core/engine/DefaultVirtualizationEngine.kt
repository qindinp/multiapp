package com.multiapp.core.engine

import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.loader.VirtualActivityLaunchAllocationRequest
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EngineCapabilityReport
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.installer.toResolvedComponents
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.findActivityRuntimeComponent
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.toLegacyMetaDataMap
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultVirtualizationEngine @Inject constructor(
    engineServerRuntime: EngineServerRuntime
) : VirtualizationEngine by engineServerRuntime.virtualizationEngine {
    internal val delegatedEngine: VirtualizationEngine = engineServerRuntime.virtualizationEngine
}

internal class DefaultVirtualizationEngineCore(
    private val hostPackageName: String,
    private val instanceManager: InstanceManager,
    private val virtualInstallService: VirtualInstallService,
    private val activityLauncher: EngineActivityLauncher,
    private val processBootstrapper: EngineProcessBootstrapper = EngineProcessBootstrapper.IMMEDIATE,
    private val processTerminator: EngineProcessTerminator = EngineProcessTerminator.TEST_NO_OP,
    private val slotStore: EngineRuntimeSlotStore = InMemoryEngineRuntimeSlotStore(),
    internal val runtimeRegistry: EngineRuntimeRegistry = EngineRuntimeRegistry(),
    internal val activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry =
        EngineActivityLaunchCapabilityRegistry(),
    internal val processDeathRegistry: EngineProcessDeathRegistry = EngineProcessDeathRegistry(),
    private val profilePolicy: CompatibilityProfilePolicy = CompatibilityProfilePolicy(),
    private val hookRuntime: EngineHookRuntime = EngineHookRuntime.NO_OP,
    private val permissionGrantSeeder: EnginePermissionGrantSeeder = EnginePermissionGrantSeeder.NO_OP,
    private val ephemeralInstanceCleanup: (String) -> Unit = {},
    private val evidenceSessionFactory: () -> String = { UUID.randomUUID().toString() },
    private val runtimeEpochFactory: () -> Long = { System.currentTimeMillis().coerceAtLeast(1L) },
    systemServiceProxyRegistry: EngineSystemServiceProxyRegistry? = null,
    systemServerFactory: (EngineRuntimeRegistry) -> VirtualSystemServer = { registry ->
        DefaultVirtualSystemServer(registry)
    }
) : VirtualizationEngine {
    internal val systemServer: VirtualSystemServer = systemServerFactory(runtimeRegistry)
    internal val systemServiceProxyRegistry: EngineSystemServiceProxyRegistry =
        systemServiceProxyRegistry ?: EngineSystemServiceProxyRegistry(systemServer.runtimeService)
    private val activityLaunchAllocator = EngineActivityLaunchAllocator(
        runtimeRegistry = runtimeRegistry,
        activityService = systemServer.activityService,
        capabilities = activityLaunchCapabilities
    )
    private val runtimeEpochLock = Any()
    private val allocatedRuntimeEpochs = mutableMapOf<String, Long>()
    private val instanceOperationLocks = Array(INSTANCE_OPERATION_LOCK_COUNT) { Any() }
    private val createOperationLock = Any()

    override fun installOrRefreshPackage(originPackageName: String): EngineResult {
        if (originPackageName.isBlank()) {
            return EngineResult.fail(
                operation = OP_INSTALL,
                originPackageName = originPackageName,
                message = "originPackageName must not be blank"
            )
        }
        return if (virtualInstallService.hasInstallRecord(originPackageName)) {
            EngineResult.pass(
                operation = OP_INSTALL,
                originPackageName = originPackageName,
                message = "install record already exists"
            )
        } else {
            EngineResult.unsupported(
                operation = OP_INSTALL,
                originPackageName = originPackageName,
                message = "installOrRefreshPackage requires pre-extracted VirtualApp metadata in the current baseline"
            )
        }
    }

    override fun refreshPackage(request: EnginePackageInstallRequest): EngineResult =
        synchronized(createOperationLock) {
            refreshPackageLocked(request)
        }

    private fun refreshPackageLocked(request: EnginePackageInstallRequest): EngineResult {
        val existing = virtualInstallService.getInstallRecord(request.originPackageName)
            ?: return EngineResult.fail(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = "install_record_not_found:${request.originPackageName}"
            )
        if (request.versionCode < existing.versionCode) {
            return EngineResult.fail(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = "package_downgrade_rejected:${existing.versionCode}:${request.versionCode}"
            )
        }
        val generationMismatch = existing.generationMismatch(request)
        if (generationMismatch == null) {
            return EngineResult.pass(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = "package generation already current"
            )
        }
        if (request.versionCode == existing.versionCode) {
            if (generationMismatch in SAME_VERSION_CONTENT_MISMATCHES) {
                return EngineResult.fail(
                    operation = OP_REFRESH,
                    originPackageName = request.originPackageName,
                    message = "same_version_content_changed"
                )
            }
            if (generationMismatch in UNREADABLE_GENERATION_MISMATCHES) {
                return EngineResult.fail(
                    operation = OP_REFRESH,
                    originPackageName = request.originPackageName,
                    message = "package_refresh_preflight_failed:$generationMismatch"
                )
            }
        }

        val instances = instanceManager.getInstanceByOrigin(request.originPackageName)
            .sortedBy(VirtualInstanceRecord::instanceId)
        for (instance in instances) {
            val stop = stopInstance(instance.instanceId)
            if (!stop.success) {
                return EngineResult.fail(
                    operation = OP_REFRESH,
                    instanceId = instance.instanceId,
                    originPackageName = request.originPackageName,
                    message = "package_refresh_stop_failed:${stop.message.orEmpty()}"
                )
            }
        }

        val refreshed = virtualInstallService.refreshInstallRecord(request.toVirtualApp())
        if (refreshed.isFailure) {
            return EngineResult.fail(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = refreshed.exceptionOrNull()?.message ?: "package_refresh_failed"
            )
        }
        val current = virtualInstallService.getInstallRecord(request.originPackageName)
            ?: return EngineResult.fail(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = "install_record_missing_after_refresh"
            )
        current.generationMismatch(request)?.let { mismatch ->
            return EngineResult.fail(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = "package_refresh_verification_failed:$mismatch"
            )
        }

        val cleanupFailures = mutableListOf<String>()
        instances.forEach { instance ->
            runCatching { systemServer.instanceLifecycleService.clearInstanceState(instance.instanceId) }
                .onFailure { cleanupFailures += "${instance.instanceId}:state:${it.javaClass.simpleName}" }
            runCatching { systemServer.instanceLifecycleService.releaseInstanceSlots(instance.instanceId) }
                .onFailure { cleanupFailures += "${instance.instanceId}:slots:${it.javaClass.simpleName}" }
            slotStore.remove(instance.instanceId)
        }
        val message = "package generation refreshed:${existing.versionCode}->${current.versionCode}"
        return if (cleanupFailures.isEmpty()) {
            EngineResult.pass(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = message
            )
        } else {
            EngineResult.partial(
                operation = OP_REFRESH,
                originPackageName = request.originPackageName,
                message = "$message:cleanup_pending=${cleanupFailures.joinToString(",")}"
            )
        }
    }

    override fun createInstance(originPackageName: String): EngineResult {
        return EngineResult.unsupported(
            operation = OP_CREATE,
            originPackageName = originPackageName,
            message = "metadata_and_creation_request_id_required"
        )
    }

    override fun createInstance(request: CreateInstanceRequest): EngineResult =
        synchronized(createOperationLock) {
            createInstanceLocked(request)
        }

    private fun createInstanceLocked(request: CreateInstanceRequest): EngineResult {
        val requestFingerprint = request.creationFingerprint()
        instanceManager.getInstanceByCreationRequestId(request.creationRequestId)?.let { existing ->
            if (
                existing.originPackageName != request.originPackageName ||
                existing.displayName != request.displayName ||
                existing.compatibilityMode != request.compatibilityMode ||
                existing.creationRequestFingerprint != requestFingerprint
            ) {
                return EngineResult.fail(
                    operation = OP_CREATE,
                    instanceId = existing.instanceId,
                    originPackageName = request.originPackageName,
                    message = "creation_request_id_conflict"
                )
            }
            if (virtualInstallService.getInstallRecord(request.originPackageName) == null) {
                return EngineResult.fail(
                    operation = OP_CREATE,
                    instanceId = existing.instanceId,
                    originPackageName = request.originPackageName,
                    message = "creation_request_committed_but_install_record_missing"
                )
            }
            return EngineResult.pass(
                operation = OP_CREATE,
                instanceId = existing.instanceId,
                originPackageName = request.originPackageName,
                message = "creation request already committed"
            )
        }

        val existingInstallRecord = virtualInstallService.getInstallRecord(request.originPackageName)
        val hadInstallRecord = existingInstallRecord != null
        if (existingInstallRecord != null) {
            existingInstallRecord.generationMismatch(request.install)?.let { mismatch ->
                return EngineResult.fail(
                    operation = OP_CREATE,
                    originPackageName = request.originPackageName,
                    message = "package_generation_mismatch_refresh_required:$mismatch"
                )
            }
        } else {
            val importResult = virtualInstallService.ensureInstallRecord(request.install.toVirtualApp())
            if (importResult.isFailure) {
                val cleanup = rollbackNewInstallRecord(request.originPackageName, hadInstallRecord)
                return EngineResult.fail(
                    operation = OP_CREATE,
                    originPackageName = request.originPackageName,
                    message = buildString {
                        append(importResult.exceptionOrNull()?.message ?: "package import failed")
                        if (cleanup != null) append(":$cleanup")
                    }
                )
            }
            val importedRecord = virtualInstallService.getInstallRecord(request.originPackageName)
            val importedMismatch = if (importedRecord == null) {
                "install_record_missing_after_import"
            } else {
                importedRecord.generationMismatch(request.install)
            }
            if (importedMismatch != null) {
                val cleanup = rollbackNewInstallRecord(request.originPackageName, hadInstallRecord)
                return EngineResult.fail(
                    operation = OP_CREATE,
                    originPackageName = request.originPackageName,
                    message = buildString {
                        append("package_import_verification_failed:$importedMismatch")
                        if (cleanup != null) append(":$cleanup")
                    }
                )
            }
        }

        return instanceManager.createInstance(
            InstanceManager.CreationRequest(
                originPackageName = request.originPackageName,
                displayName = request.displayName,
                compatibilityMode = request.compatibilityMode,
                creationRequestId = request.creationRequestId,
                creationRequestFingerprint = requestFingerprint
            )
        ).fold(
            onSuccess = { instance ->
                if (
                    instance.creationRequestId != request.creationRequestId ||
                    instance.creationRequestFingerprint != requestFingerprint
                ) {
                    val removed = instanceManager.deleteInstance(instance.instanceId)
                    val installCleanup = rollbackNewInstallRecord(request.originPackageName, hadInstallRecord)
                    EngineResult.fail(
                        operation = OP_CREATE,
                        instanceId = instance.instanceId,
                        originPackageName = request.originPackageName,
                        message = "instance_manager_dropped_creation_request_identity:" +
                            "recordRemoved=$removed,installCleanup=${installCleanup ?: "not_required"}"
                    )
                } else {
                    EngineResult.pass(
                        operation = OP_CREATE,
                        instanceId = instance.instanceId,
                        originPackageName = request.originPackageName,
                        message = "package imported and instance created by engine authority"
                    )
                }
            },
            onFailure = { error ->
                val cleanup = rollbackNewInstallRecord(request.originPackageName, hadInstallRecord)
                EngineResult.fail(
                    operation = OP_CREATE,
                    originPackageName = request.originPackageName,
                    message = buildString {
                        append(error.message ?: "createInstance failed")
                        if (cleanup != null) append(":$cleanup")
                    }
                )
            }
        )
    }

    private fun rollbackNewInstallRecord(originPackageName: String, hadInstallRecord: Boolean): String? {
        if (hadInstallRecord) return null
        if (instanceManager.getInstanceByOrigin(originPackageName).isNotEmpty()) {
            return "install_record_preserved_for_sibling_instance"
        }
        if (!virtualInstallService.hasInstallRecord(originPackageName)) return "install_record_not_created"
        return if (virtualInstallService.deleteInstallRecord(originPackageName)) {
            "new_install_record_rolled_back"
        } else {
            "new_install_record_rollback_failed"
        }
    }

    private fun InstallRecord.generationMismatch(request: EnginePackageInstallRequest): String? {
        if (packageName != request.originPackageName) return "package_name"
        if (versionCode != request.versionCode) return "version_code"
        if (versionName != request.versionName) return "version_name"
        if (targetSdk != request.targetSdk) return "target_sdk"
        if (minSdk != request.minSdk) return "min_sdk"
        if (applicationClassName != request.applicationClassName) return "application_class"
        if (splitApkPaths.size != request.splitApkPaths.size) return "split_count"
        if (splitNames != request.resolvedSplitNames()) return "split_names"
        if (isolatedSplits != request.isolatedSplits) return "isolated_splits"
        val baseDigest = sha256OrNull(request.originApkPath) ?: return "source_apk_unreadable"
        if (!originApkSha256.equals(baseDigest, ignoreCase = true)) return "base_apk_digest"
        val splitDigests = request.splitApkPaths.map { path ->
            sha256OrNull(path) ?: return "split_apk_unreadable"
        }
        if (splitApkSha256s.map(String::lowercase) != splitDigests.map(String::lowercase)) {
            return "split_apk_digest"
        }
        return null
    }

    private fun EnginePackageInstallRequest.resolvedSplitNames(): List<String> =
        if (splitApkPaths.isEmpty()) {
            emptyList()
        } else {
            splitApkPaths.mapIndexed { index, path ->
                splitNames.getOrNull(index)
                    ?.takeIf { it.isNotBlank() }
                    ?: File(path).nameWithoutExtension.ifBlank { "split$index" }
            }
        }

    private fun sha256OrNull(path: String): String? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_HASH_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.getOrNull()
    }

    private fun CreateInstanceRequest.creationFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun putInt(value: Int) {
            digest.update(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte()
                )
            )
        }

        fun putString(value: String?) {
            if (value == null) {
                putInt(-1)
                return
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            putInt(bytes.size)
            digest.update(bytes)
        }

        fun putList(values: List<String>) {
            putInt(values.size)
            values.forEach(::putString)
        }

        putString(CREATE_FINGERPRINT_VERSION)
        putString(install.originPackageName)
        putString(install.originApkPath)
        putString(install.versionCode.toString())
        putString(install.versionName)
        putString(install.targetSdk.toString())
        putString(install.minSdk.toString())
        putString(install.applicationClassName)
        putString(install.packageLabel)
        putList(install.requestedPermissions)
        putList(install.activityClassNames)
        putList(install.serviceClassNames)
        putList(install.receiverClassNames)
        putList(install.providerClassNames)
        putList(install.nativeAbis)
        putList(install.splitApkPaths)
        putList(install.splitPublicSourceDirs)
        putList(install.splitNames)
        putString(install.isolatedSplits.toString())
        putString(displayName)
        putString(compatibilityMode.name)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    override fun launchInstance(request: LaunchInstanceRequest): EngineResult =
        synchronized(createOperationLock) {
            synchronized(instanceOperationLock(request.instanceId)) {
                launchInstanceLocked(request)
            }
        }

    private fun launchInstanceLocked(request: LaunchInstanceRequest): EngineResult {
        val instance = instanceManager.getInstance(request.instanceId)
            ?: return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = request.instanceId,
                message = "Instance not found: ${request.instanceId}"
            )
        val installRecord = virtualInstallService.getInstallRecord(instance.originPackageName)
            ?: return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "InstallRecord not found for ${instance.originPackageName}"
            )
        val decision = profilePolicy.evaluate(instance.originPackageName, instance.instanceId, request.profile)
        if (!decision.allowed) {
            return EngineResult.unsupported(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "Engine profile ${request.profile} rejected: ${decision.reason}"
            )
        }

        var runtime = runCatching {
            pruneRuntimeSlots()
            buildRuntime(instance, installRecord, request.profile)
        }.getOrElse { error ->
            if (error is EngineRuntimeSlotExhaustedException) {
                return EngineResult.unsupported(
                    operation = OP_LAUNCH,
                    instanceId = instance.instanceId,
                    originPackageName = instance.originPackageName,
                    message = error.message ?: "runtime slot exhausted"
                )
            }
            return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = error.message ?: "runtime slot assignment failed"
            )
        }
        runtime = systemServer.runtimeService.register(runtime)
        processDeathRegistry.removeInstance(runtime.instanceId)
        activityLaunchCapabilities.revokeInstance(runtime.instanceId)
        val permissionSeed = runCatching {
            permissionGrantSeeder.seed(runtime, systemServer.permissionService)
        }.getOrElse { error ->
            EnginePermissionSeedResult(
                verdict = EngineResultStatus.PARTIAL,
                mirroredGrantCount = 0,
                mirroredDenyCount = 0,
                preservedDecisionCount = 0,
                unresolvedCount = runtime.packageSnapshot.permissions.size,
                message = "permission_seed_failed:${error.javaClass.simpleName}:${error.message}"
            )
        }
        systemServer.runtimeService.registerOperationEvidence(
            instance.instanceId,
            EngineOperationEvidence(
                component = "permission",
                operation = "seed",
                verdict = permissionSeed.verdict,
                entries = linkedMapOf(
                    "mirroredGrantCount" to permissionSeed.mirroredGrantCount.toString(),
                    "mirroredDenyCount" to permissionSeed.mirroredDenyCount.toString(),
                    "preservedDecisionCount" to permissionSeed.preservedDecisionCount.toString(),
                    "unresolvedCount" to permissionSeed.unresolvedCount.toString(),
                    "message" to permissionSeed.message
                )
            )
        )
        systemServer.runtimeService.registerOperationEvidence(
            instance.instanceId,
            hookRuntime.profileEvidence(decision)
        )
        if (request.prewarmPolicy == com.multiapp.core.model.engine.EnginePrewarmPolicy.DISABLED) {
            val unsupported = EngineOperationEvidence(
                component = "runtime",
                operation = "process-bootstrap",
                verdict = EngineResultStatus.UNSUPPORTED,
                entries = mapOf(
                    "bootstrapState" to EngineProcessBootstrapState.UNSUPPORTED.name,
                    "reason" to "commercial_launch_requires_process_bootstrap"
                )
            )
            systemServer.runtimeService.registerOperationEvidence(instance.instanceId, unsupported)
            return EngineResult.unsupported(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "Process bootstrap cannot be disabled for an engine-owned Activity launch"
            )
        }

        val bootstrapRequest = EngineProcessBootstrapRequest(
            runtime = runtime,
            providerRoutingEnabled = decision.providerRoutingEnabled,
            legacyProviderHookEnabled = decision.providerRoutingEnabled && decision.lsplantEnabled,
            evidenceMode = request.evidenceMode
        )
        val rawBootstrap = runCatching {
            processBootstrapper.bootstrap(bootstrapRequest)
        }.getOrElse { error ->
            EngineProcessBootstrapResult(
                state = EngineProcessBootstrapState.FAILED,
                verdict = EngineResultStatus.FAIL,
                instanceId = runtime.instanceId,
                runtimeEpoch = runtime.runtimeEpoch,
                engineSessionId = runtime.engineSessionId,
                processName = runtime.processSlot,
                message = "process bootstrap threw ${error.javaClass.name}: ${error.message.orEmpty()}",
                evidence = mapOf("errorClass" to error.javaClass.name)
            )
        }
        val bootstrap = if (rawBootstrap.validates(bootstrapRequest)) {
            rawBootstrap
        } else {
            rawBootstrap.copy(
                state = EngineProcessBootstrapState.STALE,
                verdict = EngineResultStatus.FAIL,
                message = "process bootstrap response did not match the authoritative runtime",
                evidence = rawBootstrap.evidence + mapOf(
                    "expectedInstanceId" to runtime.instanceId,
                    "expectedRuntimeEpoch" to runtime.runtimeEpoch.toString(),
                    "expectedEngineSessionId" to runtime.engineSessionId,
                    "expectedProcessName" to runtime.processSlot
                )
            )
        }
        systemServer.runtimeService.registerOperationEvidence(
            instance.instanceId,
            bootstrap.toOperationEvidence()
        )
        if (!bootstrap.ready) {
            return when (bootstrap.verdict) {
                EngineResultStatus.UNSUPPORTED -> EngineResult.unsupported(
                    operation = OP_LAUNCH,
                    instanceId = instance.instanceId,
                    originPackageName = instance.originPackageName,
                    message = bootstrap.message
                )
                else -> EngineResult.fail(
                    operation = OP_LAUNCH,
                    instanceId = instance.instanceId,
                    originPackageName = instance.originPackageName,
                    message = bootstrap.message
                )
            }
        }

        runtime = runtimeRegistry.markPrewarmedIfCurrent(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processId = bootstrap.processId,
            processName = bootstrap.processName
        ) ?: return staleBootstrapResult(
            instance = instance,
            bootstrap = bootstrap,
            message = "Process bootstrap became stale before PREWARMED promotion"
        )
        if (!registerProcessDeath(runtime, bootstrap)) {
            runtimeRegistry.markDeadIfCurrent(
                instanceId = runtime.instanceId,
                runtimeEpoch = runtime.runtimeEpoch,
                engineSessionId = runtime.engineSessionId
            )
            return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "Process bootstrap client token died before foreground launch"
            )
        }
        val launcherActivityClassName = bootstrap.launcherActivityClassName
            ?: runtime.packageSnapshot.launcherActivityName
            ?: runtime.packageSnapshot.activities.firstOrNull()?.name
            ?: return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "Process bootstrap completed without a launcher Activity"
            )
        val launcherComponent = runtime.packageSnapshot.findActivityRuntimeComponent(
            launcherActivityClassName
        )
        val processId = bootstrap.processId ?: return EngineResult.fail(
            operation = OP_LAUNCH,
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            message = "Process bootstrap completed without a target process id"
        )
        if (!isForegroundLaunchGenerationCurrent(runtime, bootstrap)) {
            return staleBootstrapResult(
                instance = instance,
                bootstrap = bootstrap,
                message = "Target process died or runtime generation changed before foreground launch"
            )
        }
        val launchAllocation = activityLaunchAllocator.allocate(
            request = VirtualActivityLaunchAllocationRequest(
                instanceId = runtime.instanceId,
                originPackageName = runtime.originPackageName,
                guestActivityClassName = launcherActivityClassName,
                processSlot = runtime.processSlot,
                launchMode = launcherComponent?.launchMode,
                taskAffinity = launcherTaskAffinity(runtime, launcherComponent?.taskAffinity)
            ),
            callingPid = processId
        )
        val launchIdentity = launchAllocation.launchIdentity?.toEngineIdentity()
        val allocatedProxySlot = launchAllocation.proxyActivityClassName
        if (!launchAllocation.accepted || launchIdentity == null || allocatedProxySlot == null) {
            return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = launchAllocation.reason
            )
        }
        val launchFailure = runCatching {
            activityLauncher.launch(
                EngineLaunchSpec(
                    instanceId = instance.instanceId,
                    originPackageName = instance.originPackageName,
                    guestActivityClassName = launcherActivityClassName,
                    launchMode = launcherComponent?.launchMode,
                    taskAffinity = launcherTaskAffinity(runtime, launcherComponent?.taskAffinity),
                    profile = request.profile,
                    evidenceMode = request.evidenceMode,
                    processSlot = runtime.processSlot,
                    proxySlot = allocatedProxySlot,
                    evidenceSessionId = runtime.evidenceSessionId,
                    runtimeEpoch = runtime.runtimeEpoch,
                    engineSessionId = runtime.engineSessionId,
                    processId = processId,
                    launchCapabilityToken = launchIdentity.capabilityToken,
                    bootstrapState = bootstrap.state,
                    bootstrapVerdict = bootstrap.verdict,
                    providerRoutingEnabled = decision.providerRoutingEnabled,
                    legacyProviderHookEnabled = decision.providerRoutingEnabled && decision.lsplantEnabled
                )
            )
        }.exceptionOrNull()
        if (launchFailure != null) {
            val allocationReleased = activityLaunchAllocator.release(launchAllocation, processId)
            systemServer.runtimeService.registerOperationEvidence(
                instance.instanceId,
                EngineOperationEvidence(
                    component = "activity",
                    operation = "foreground-launch",
                    verdict = EngineResultStatus.FAIL,
                    entries = mapOf(
                        "bootstrapState" to bootstrap.state.name,
                        "guestActivityClassName" to launcherActivityClassName,
                        "errorClass" to launchFailure.javaClass.name,
                        "message" to launchFailure.message.orEmpty(),
                        "allocationReleased" to allocationReleased.toString()
                    )
                )
            )
            return EngineResult.fail(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = launchFailure.message ?: "foreground proxy Activity launch failed"
            )
        }
        if (!isForegroundLaunchGenerationCurrent(runtime, bootstrap)) {
            activityLaunchCapabilities.revoke(launchIdentity.capabilityToken)
            return staleBootstrapResult(
                instance = instance,
                bootstrap = bootstrap,
                message = "Target process died or runtime generation changed during foreground launch"
            )
        }
        runtime = runtimeRegistry.get(runtime.instanceId) ?: runtime
        instanceManager.updateLaunchState(instance.instanceId)
        val evidence = systemServer.runtimeService.evidence(instance.instanceId)
        return if (
            permissionSeed.verdict == EngineResultStatus.PASS &&
            bootstrap.verdict == EngineResultStatus.PASS
        ) {
            EngineResult.pass(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "process READY confirmed and proxy Activity launched through engine",
                runtime = runtime,
                evidence = evidence
            )
        } else {
            EngineResult.partial(
                operation = OP_LAUNCH,
                instanceId = instance.instanceId,
                originPackageName = instance.originPackageName,
                message = "proxy Activity launched with partial bootstrap or permission evidence",
                runtime = runtime,
                evidence = evidence
            )
        }
    }

    override fun stopInstance(instanceId: String): EngineResult =
        synchronized(instanceOperationLock(instanceId)) {
            stopInstanceLocked(instanceId)
        }

    private fun stopInstanceLocked(instanceId: String): EngineResult {
        val runtime = systemServer.runtimeService.get(instanceId)
        val slotAssignment = slotStore.get(instanceId)
        activityLaunchCapabilities.revokeInstance(instanceId)
        val processSlot = runtime?.processSlot ?: slotAssignment?.processSlot
        if (processSlot != null) {
            val termination = processTerminator.terminateAndAwait(
                instanceId = instanceId,
                processSlot = processSlot,
                expectedProcessId = runtime?.processId
            )
            if (!termination.confirmed) {
                return EngineResult.fail(
                    operation = OP_STOP,
                    instanceId = instanceId,
                    originPackageName = runtime?.originPackageName ?: slotAssignment?.originPackageName,
                    message = "process_termination_unconfirmed:${termination.status}:${termination.message}"
                )
            }
        }
        processDeathRegistry.removeInstance(instanceId)
        systemServiceProxyRegistry.clearInstance(instanceId)
        ephemeralInstanceCleanup(instanceId)
        val stopped = systemServer.runtimeService.stop(instanceId)
        return if (stopped) {
            EngineResult.pass(operation = OP_STOP, instanceId = instanceId, message = "runtime stopped")
        } else {
            EngineResult.partial(operation = OP_STOP, instanceId = instanceId, message = "runtime was not active")
        }
    }

    override fun deleteInstance(instanceId: String): EngineResult =
        synchronized(createOperationLock) {
            synchronized(instanceOperationLock(instanceId)) {
                deleteInstanceLocked(instanceId)
            }
        }

    override fun clearInstanceData(instanceId: String): EngineResult =
        synchronized(createOperationLock) {
            synchronized(instanceOperationLock(instanceId)) {
                clearInstanceDataLocked(instanceId)
            }
        }

    private fun clearInstanceDataLocked(instanceId: String): EngineResult {
        if (instanceId.isBlank()) {
            return EngineResult.fail(
                operation = OP_CLEAR_DATA,
                message = "instanceId must not be blank"
            )
        }
        val instance = instanceManager.getInstance(instanceId)
            ?: return EngineResult.fail(
                operation = OP_CLEAR_DATA,
                instanceId = instanceId,
                message = "instance_not_found:$instanceId"
            )
        val stopped = stopInstanceLocked(instanceId)
        if (!stopped.success) {
            return EngineResult.fail(
                operation = OP_CLEAR_DATA,
                instanceId = instanceId,
                originPackageName = instance.originPackageName,
                message = "clear_data_stop_failed:${stopped.message.orEmpty()}"
            )
        }
        val cleared = instanceManager.clearInstanceData(instanceId)
        if (cleared.isFailure) {
            return EngineResult.fail(
                operation = OP_CLEAR_DATA,
                instanceId = instanceId,
                originPackageName = instance.originPackageName,
                message = "instance_data_clear_failed:" +
                    (cleared.exceptionOrNull()?.message ?: "unknown")
            )
        }

        val cleanupFailures = mutableListOf<String>()
        runCatching { systemServer.instanceLifecycleService.clearInstanceState(instanceId) }
            .onFailure { cleanupFailures += "state:${it.javaClass.simpleName}" }
        runCatching { systemServer.instanceLifecycleService.releaseInstanceSlots(instanceId) }
            .onFailure { cleanupFailures += "proxy-slots:${it.javaClass.simpleName}" }
        runCatching { slotStore.remove(instanceId) }
            .onFailure { cleanupFailures += "runtime-slot:${it.javaClass.simpleName}" }

        return if (cleanupFailures.isEmpty()) {
            EngineResult.pass(
                operation = OP_CLEAR_DATA,
                instanceId = instanceId,
                originPackageName = instance.originPackageName,
                message = "instance data cleared and root recreated"
            )
        } else {
            EngineResult.partial(
                operation = OP_CLEAR_DATA,
                instanceId = instanceId,
                originPackageName = instance.originPackageName,
                message = "instance data cleared; cleanup_pending=${cleanupFailures.joinToString(",")}"
            )
        }
    }

    private fun deleteInstanceLocked(instanceId: String): EngineResult {
        if (instanceId.isBlank()) {
            return EngineResult.fail(operation = OP_DELETE, message = "instanceId must not be blank")
        }
        val instance = instanceManager.getInstance(instanceId)
        val runtime = systemServer.runtimeService.get(instanceId)
        val slotAssignment = slotStore.get(instanceId)
        val originPackageName = instance?.originPackageName
            ?: runtime?.originPackageName
            ?: slotAssignment?.originPackageName

        activityLaunchCapabilities.revokeInstance(instanceId)
        val processSlot = runtime?.processSlot ?: slotAssignment?.processSlot
        if (processSlot != null) {
            val termination = processTerminator.terminateAndAwait(
                instanceId = instanceId,
                processSlot = processSlot,
                expectedProcessId = runtime?.processId
            )
            if (!termination.confirmed) {
                return EngineResult.fail(
                    operation = OP_DELETE,
                    instanceId = instanceId,
                    originPackageName = originPackageName,
                    message = "process_termination_unconfirmed:${termination.status}:${termination.message}"
                )
            }
        }
        processDeathRegistry.removeInstance(instanceId)
        systemServiceProxyRegistry.clearInstance(instanceId)
        ephemeralInstanceCleanup(instanceId)

        val cleanup = runCatching {
            systemServer.instanceLifecycleService.clearInstanceState(instanceId)
        }.getOrElse { error ->
            return EngineResult.fail(
                operation = OP_DELETE,
                instanceId = instanceId,
                originPackageName = originPackageName,
                message = "instance_state_cleanup_failed:${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
        systemServer.runtimeService.stop(instanceId)
        if (instance != null && !instanceManager.deleteInstance(instanceId)) {
            return EngineResult.fail(
                operation = OP_DELETE,
                instanceId = instanceId,
                originPackageName = originPackageName,
                message = "instance_record_delete_failed"
            )
        }
        val releasedProxySlots = runCatching {
            systemServer.instanceLifecycleService.releaseInstanceSlots(instanceId)
        }.getOrElse { error ->
            return EngineResult.fail(
                operation = OP_DELETE,
                instanceId = instanceId,
                originPackageName = originPackageName,
                message = "proxy_slot_release_failed:${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
        slotStore.remove(instanceId)
        val packageGc = originPackageName
            ?.takeIf { instanceManager.getInstanceByOrigin(it).isEmpty() }
            ?.let { packageName ->
                !virtualInstallService.hasInstallRecord(packageName) ||
                    virtualInstallService.deleteInstallRecord(packageName)
            }
        val message = "instance deleted after engine cleanup " +
            "(${cleanup.totalRemoved} state records, $releasedProxySlots proxy slots removed)"
        return if (packageGc == false) {
            EngineResult.partial(
                operation = OP_DELETE,
                instanceId = instanceId,
                originPackageName = originPackageName,
                message = "$message; package_gc_pending"
            )
        } else {
            EngineResult.pass(
                operation = OP_DELETE,
                instanceId = instanceId,
                originPackageName = originPackageName,
                message = if (packageGc == true) "$message; package artifacts removed" else message
            )
        }
    }

    override fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime? =
        systemServer.runtimeService.get(instanceId)

    override fun queryCapabilities(instanceId: String?): EngineCapabilityReport {
        if (instanceId != null && instanceId.isBlank()) {
            return EngineCapabilityReport(
                instanceId = null,
                status = EngineResultStatus.FAIL,
                capabilities = emptyList(),
                generatedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
                message = "invalid_instance_id"
            )
        }
        return EngineCapabilityCatalog.report(systemServer, instanceId, systemServiceProxyRegistry)
    }

    override fun exportEvidence(instanceId: String): EngineEvidenceReport =
        systemServer.evidenceService.exportReport(instanceId)
            ?: systemServer.runtimeService.evidence(instanceId)

    private fun buildRuntime(
        instance: VirtualInstanceRecord,
        installRecord: InstallRecord,
        profile: EngineProfile
    ): VirtualInstanceRuntime {
        val snapshot = buildSnapshot(instance, installRecord)
        val launcherComponent = launcherComponent(snapshot)
        val proxyCandidates = proxySlotCandidatesForLaunchMode(launcherComponent?.launchMode)
        val slots = slotStore.assign(
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            processCandidates = processSlotCandidatesForProxySlots(proxyCandidates),
            proxyCandidates = proxyCandidates
        )
        val evidenceSessionId = evidenceSessionFactory()
        return VirtualInstanceRuntime(
            instanceId = instance.instanceId,
            hostPackageName = hostPackageName,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            dataRoot = instance.dataRoot,
            packageSnapshot = snapshot,
            profile = profile,
            processSlot = slots.processSlot,
            proxySlot = slots.proxySlot,
            evidenceSessionId = evidenceSessionId,
            runtimeEpoch = nextRuntimeEpoch(instance.instanceId),
            engineSessionId = "engine-$evidenceSessionId",
            processName = slots.processSlot,
            state = VirtualRuntimeState.CREATED
        )
    }

    private fun buildSnapshot(
        instance: VirtualInstanceRecord,
        installRecord: InstallRecord
    ): VirtualPackageSnapshot {
        return VirtualPackageSnapshot(
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            applicationLabel = installRecord.packageLabel ?: instance.displayName.ifBlank { instance.originPackageName },
            versionCode = installRecord.versionCode,
            versionName = installRecord.versionName,
            targetSdk = installRecord.targetSdk,
            minSdk = installRecord.minSdk,
            sourceDir = installRecord.originApkPath,
            sourceSha256 = installRecord.originApkSha256,
            publicSourceDir = installRecord.originApkPath,
            splitSourceDirs = installRecord.splitApkPaths,
            splitSha256s = installRecord.splitApkSha256s,
            splitPublicSourceDirs = installRecord.splitPublicSourceDirs.ifEmpty { installRecord.splitApkPaths },
            splitNames = installRecord.splitNames,
            isolatedSplits = installRecord.isolatedSplits,
            dataDir = instance.dataRoot,
            nativeLibraryDir = File(instance.dataRoot, "lib").absolutePath,
            nativeLibraries = installRecord.nativeLibraries,
            abiList = installRecord.abiList,
            applicationClassName = installRecord.applicationClassName,
            metaData = installRecord.applicationMetaData.toLegacyMetaDataMap(),
            typedMetaData = installRecord.applicationMetaData,
            launcherActivityName = installRecord.activities.firstOrNull()?.name,
            activities = installRecord.activities.toResolvedComponents(),
            services = installRecord.services.toResolvedComponents(),
            receivers = installRecord.receivers.toResolvedComponents(),
            providers = installRecord.providers.toResolvedComponents(),
            permissions = installRecord.permissions,
            originCertSha256 = installRecord.originCertSha256.takeIf { it.isNotBlank() },
            signerSha256Digests = installRecord.signerSha256Digests,
            hasMultipleSigners = installRecord.hasMultipleSigners
        )
    }

    private fun launcherComponent(snapshot: VirtualPackageSnapshot): ResolvedComponent? {
        val launcherName = snapshot.launcherActivityName?.takeIf { it.isNotBlank() }
        return snapshot.activities.firstOrNull { component ->
            component.name == launcherName || component.targetActivityName == launcherName
        } ?: snapshot.activities.firstOrNull()
    }

    private fun launcherTaskAffinity(
        runtime: VirtualInstanceRuntime,
        componentTaskAffinity: String?
    ): String {
        val guestAffinity = componentTaskAffinity?.takeIf { it.isNotBlank() }
            ?: runtime.packageSnapshot.taskAffinity?.takeIf { it.isNotBlank() }
            ?: runtime.originPackageName
        return "$guestAffinity:${runtime.instanceId}"
    }

    private fun registerProcessDeath(
        runtime: VirtualInstanceRuntime,
        bootstrap: EngineProcessBootstrapResult
    ): Boolean {
        val token = bootstrap.clientToken
        val processId = bootstrap.processId
        if (token == null || processId == null || bootstrap.processName != runtime.processSlot) {
            systemServer.runtimeService.registerOperationEvidence(
                runtime.instanceId,
                EngineOperationEvidence(
                    component = "runtime",
                    operation = "process-token",
                    verdict = EngineResultStatus.FAIL,
                    entries = mapOf(
                        "clientTokenPresent" to (token != null).toString(),
                        "processIdPresent" to (processId != null).toString(),
                        "processNameMatchesSlot" to
                            (bootstrap.processName == runtime.processSlot).toString(),
                        "reason" to "bootstrap_response_has_no_complete_live_client_authority"
                    )
                )
            )
            return false
        }
        val identity = EngineProcessClientIdentity(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processSlot = runtime.processSlot,
            processId = processId
        )
        val linked = processDeathRegistry.register(
            identity = identity,
            token = token
        ) {
            if (
                runtimeRegistry.markDeadIfCurrent(identity)
            ) {
                activityLaunchCapabilities.revokeGeneration(
                    instanceId = runtime.instanceId,
                    runtimeEpoch = runtime.runtimeEpoch,
                    engineSessionId = runtime.engineSessionId
                )
                runtimeRegistry.registerOperationEvidence(
                    runtime.instanceId,
                    EngineOperationEvidence(
                        component = "runtime",
                        operation = "process-death",
                        verdict = EngineResultStatus.FAIL,
                        entries = mapOf(
                            "runtimeEpoch" to runtime.runtimeEpoch.toString(),
                            "engineSessionId" to runtime.engineSessionId,
                            "reason" to "bootstrap_client_binder_died"
                        )
                    )
                )
            }
        }.accepted
        systemServer.runtimeService.registerOperationEvidence(
            runtime.instanceId,
            EngineOperationEvidence(
                component = "runtime",
                operation = "process-token",
                verdict = if (linked) EngineResultStatus.PASS else EngineResultStatus.PARTIAL,
                entries = mapOf(
                    "clientTokenPresent" to "true",
                    "deathRecipientLinked" to linked.toString()
                )
            )
        )
        return linked
    }

    private fun isForegroundLaunchGenerationCurrent(
        runtime: VirtualInstanceRuntime,
        bootstrap: EngineProcessBootstrapResult
    ): Boolean {
        val current = runtimeRegistry.get(runtime.instanceId) ?: return false
        return current.runtimeEpoch == runtime.runtimeEpoch &&
            current.engineSessionId == runtime.engineSessionId &&
            current.processSlot == runtime.processSlot &&
            current.processId == bootstrap.processId &&
            current.state in setOf(VirtualRuntimeState.PREWARMED, VirtualRuntimeState.RUNNING) &&
            bootstrap.clientToken?.isBinderAlive == true
    }

    private fun nextRuntimeEpoch(instanceId: String): Long = synchronized(runtimeEpochLock) {
        val durableEpoch = runtimeRegistry.get(instanceId)?.runtimeEpoch ?: 0L
        val allocatedEpoch = allocatedRuntimeEpochs[instanceId] ?: 0L
        val previousEpoch = maxOf(durableEpoch, allocatedEpoch)
        check(previousEpoch < Long.MAX_VALUE) { "runtimeEpoch exhausted for instanceId=$instanceId" }
        val requestedEpoch = runtimeEpochFactory().coerceAtLeast(1L)
        maxOf(requestedEpoch, previousEpoch + 1L).also { nextEpoch ->
            allocatedRuntimeEpochs[instanceId] = nextEpoch
        }
    }

    private fun instanceOperationLock(instanceId: String): Any {
        val index = (instanceId.hashCode() and Int.MAX_VALUE) % instanceOperationLocks.size
        return instanceOperationLocks[index]
    }

    private fun staleBootstrapResult(
        instance: VirtualInstanceRecord,
        bootstrap: EngineProcessBootstrapResult,
        message: String
    ): EngineResult {
        systemServer.runtimeService.registerOperationEvidence(
            instance.instanceId,
            bootstrap.copy(
                state = EngineProcessBootstrapState.STALE,
                verdict = EngineResultStatus.FAIL,
                message = message
            ).toOperationEvidence()
        )
        return EngineResult.fail(
            operation = OP_LAUNCH,
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            message = message
        )
    }

    private fun pruneRuntimeSlots() {
        val validInstanceIds = instanceManager.listInstances().mapTo(linkedSetOf()) { it.instanceId }
        slotStore.prune(validInstanceIds)
    }

    private fun processSlotCandidatesForProxySlots(proxyCandidates: List<String>): List<String> =
        proxyCandidates.map { proxySlot ->
            ProxyActivitySlots.processNameForClassName(hostPackageName, proxySlot)
                ?: error("No process slot mapped for proxy slot: $proxySlot")
        }

    private fun proxySlotCandidatesForLaunchMode(launchMode: String?): List<String> {
        if (!ProxyActivityRegistry.isSupportedLaunchMode(launchMode)) return emptyList()
        val normalizedLaunchMode = ProxyActivityRegistry.normalizeLaunchMode(launchMode)
        val launchModesByClassName = ProxyActivitySlots.launchModeByClassName(hostPackageName)
        return ProxyActivitySlots.classNames(hostPackageName)
            .filter { className ->
                ProxyActivityRegistry.normalizeLaunchMode(launchModesByClassName[className]) == normalizedLaunchMode
            }
    }

    companion object {
        private const val INSTANCE_OPERATION_LOCK_COUNT = 32
        private const val DEFAULT_HASH_BUFFER_SIZE = 8_192
        private const val CREATE_FINGERPRINT_VERSION = "multiapp-create-v1"
        private const val OP_INSTALL = "installOrRefreshPackage"
        private const val OP_REFRESH = "refreshPackage"
        private const val OP_CREATE = "createInstance"
        private const val OP_LAUNCH = "launchInstance"
        private const val OP_STOP = "stopInstance"
        private const val OP_DELETE = "deleteInstance"
        private const val OP_CLEAR_DATA = "clearInstanceData"
        private val SAME_VERSION_CONTENT_MISMATCHES = setOf(
            "split_count",
            "split_names",
            "base_apk_digest",
            "split_apk_digest"
        )
        private val UNREADABLE_GENERATION_MISMATCHES = setOf(
            "source_apk_unreadable",
            "split_apk_unreadable"
        )
    }
}

private fun EnginePackageInstallRequest.toVirtualApp(): VirtualApp = VirtualApp(
    packageName = originPackageName,
    appName = packageLabel,
    versionName = versionName,
    versionCode = versionCode,
    apkPath = originApkPath,
    instanceId = "",
    activities = activityClassNames,
    services = serviceClassNames,
    providers = providerClassNames,
    receivers = receiverClassNames,
    minSdkVersion = minSdk,
    targetSdkVersion = targetSdk,
    splitApkPaths = splitApkPaths,
    splitPublicSourceDirs = splitPublicSourceDirs,
    splitNames = splitNames,
    hasSplitApks = splitApkPaths.isNotEmpty(),
    isolatedSplits = isolatedSplits,
    applicationClassName = applicationClassName,
    requestedPermissions = requestedPermissions,
    nativeAbis = nativeAbis
)

data class EngineLaunchSpec(
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val launchMode: String?,
    val taskAffinity: String,
    val profile: EngineProfile,
    val evidenceMode: EngineEvidenceMode,
    val processSlot: String,
    val proxySlot: String,
    val evidenceSessionId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processId: Int,
    val launchCapabilityToken: String,
    val bootstrapState: EngineProcessBootstrapState,
    val bootstrapVerdict: EngineResultStatus,
    val providerRoutingEnabled: Boolean,
    val legacyProviderHookEnabled: Boolean
)

fun interface EngineActivityLauncher {
    fun launch(spec: EngineLaunchSpec)
}
