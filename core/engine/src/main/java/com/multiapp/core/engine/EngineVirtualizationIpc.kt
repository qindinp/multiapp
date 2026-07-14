package com.multiapp.core.engine

import android.content.Context
import android.os.Bundle
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EnginePrewarmPolicy
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.EngineTaskPolicy
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class EngineRuntimeIdentity(
    val instanceId: String,
    val hostPackageName: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val dataRoot: String,
    val profile: EngineProfile,
    val processSlot: String,
    val proxySlot: String,
    val evidenceSessionId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processId: Int?,
    val processName: String?,
    val state: VirtualRuntimeState
) {
    fun matches(runtime: VirtualInstanceRuntime): Boolean =
        runtime.instanceId == instanceId &&
            runtime.hostPackageName == hostPackageName &&
            runtime.originPackageName == originPackageName &&
            runtime.virtualPackageName == virtualPackageName &&
            runtime.dataRoot == dataRoot &&
            runtime.profile == profile &&
            runtime.processSlot == processSlot &&
            runtime.proxySlot == proxySlot &&
            runtime.evidenceSessionId == evidenceSessionId &&
            runtime.runtimeEpoch == runtimeEpoch &&
            runtime.engineSessionId == engineSessionId &&
            runtime.processId == processId &&
            runtime.processName == processName &&
            runtime.state == state
}

internal data class EngineRemoteResult(
    val result: EngineResult,
    val runtimeIdentity: EngineRuntimeIdentity?
)

internal fun LaunchInstanceRequest.toEngineIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE, profile.name)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, requestedLauncherActivityClass)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME, targetComponentClassName)
    putInt(EngineRuntimeIpcContract.KEY_LAUNCH_FLAGS, launchFlags)
    putString(KEY_TASK_POLICY, taskPolicy.name)
    putString(KEY_PREWARM_POLICY, prewarmPolicy.name)
    putString(KEY_EVIDENCE_MODE, evidenceMode.name)
}

internal fun Bundle.toLaunchInstanceRequestOrNull(): LaunchInstanceRequest? = runCatching {
    LaunchInstanceRequest(
        instanceId = requiredString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
        profile = requiredEnum(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE),
        requestedLauncherActivityClass = optionalNonBlankString(
            EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME
        ),
        reason = requiredString(EngineRuntimeIpcContract.KEY_REASON),
        targetComponentClassName = optionalNonBlankString(
            EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME
        ),
        launchFlags = getInt(EngineRuntimeIpcContract.KEY_LAUNCH_FLAGS),
        taskPolicy = requiredEnum(KEY_TASK_POLICY),
        prewarmPolicy = requiredEnum(KEY_PREWARM_POLICY),
        evidenceMode = requiredEnum(KEY_EVIDENCE_MODE)
    )
}.getOrNull()

internal fun CreateInstanceRequest.toEngineIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putString(KEY_CREATION_REQUEST_ID, creationRequestId)
    putString(KEY_DISPLAY_NAME, displayName)
    putString(KEY_COMPATIBILITY_MODE, compatibilityMode.name)
    putBundle(KEY_INSTALL_REQUEST, install.toEngineIpcBundle(bundleFactory))
}

internal fun Bundle.toCreateInstanceRequestOrNull(): CreateInstanceRequest? = runCatching {
    CreateInstanceRequest(
        creationRequestId = requiredString(KEY_CREATION_REQUEST_ID),
        install = getBundle(KEY_INSTALL_REQUEST)
            ?.toEnginePackageInstallRequestOrNull()
            ?: error("missing install request"),
        displayName = requiredString(KEY_DISPLAY_NAME),
        compatibilityMode = requiredEnum(KEY_COMPATIBILITY_MODE)
    )
}.getOrNull()

private fun EnginePackageInstallRequest.toEngineIpcBundle(
    bundleFactory: () -> Bundle
): Bundle = bundleFactory().apply {
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(KEY_ORIGIN_APK_PATH, originApkPath)
    putLong(KEY_VERSION_CODE, versionCode)
    putString(KEY_VERSION_NAME, versionName)
    putInt(KEY_TARGET_SDK, targetSdk)
    putInt(KEY_MIN_SDK, minSdk)
    putString(KEY_APPLICATION_CLASS_NAME, applicationClassName)
    putString(KEY_PACKAGE_LABEL, packageLabel)
    putStringArrayList(KEY_REQUESTED_PERMISSIONS, ArrayList(requestedPermissions))
    putStringArrayList(KEY_ACTIVITY_CLASS_NAMES, ArrayList(activityClassNames))
    putStringArrayList(KEY_SERVICE_CLASS_NAMES, ArrayList(serviceClassNames))
    putStringArrayList(KEY_RECEIVER_CLASS_NAMES, ArrayList(receiverClassNames))
    putStringArrayList(KEY_PROVIDER_CLASS_NAMES, ArrayList(providerClassNames))
    putStringArrayList(KEY_NATIVE_ABIS, ArrayList(nativeAbis))
    putStringArrayList(KEY_SPLIT_APK_PATHS, ArrayList(splitApkPaths))
    putStringArrayList(KEY_SPLIT_PUBLIC_SOURCE_DIRS, ArrayList(splitPublicSourceDirs))
    putStringArrayList(KEY_SPLIT_NAMES, ArrayList(splitNames))
    putBoolean(KEY_ISOLATED_SPLITS, isolatedSplits)
}

private fun Bundle.toEnginePackageInstallRequestOrNull(): EnginePackageInstallRequest? = runCatching {
    EnginePackageInstallRequest(
        originPackageName = requiredString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME),
        originApkPath = requiredString(KEY_ORIGIN_APK_PATH),
        versionCode = getLong(KEY_VERSION_CODE),
        versionName = requiredString(KEY_VERSION_NAME),
        targetSdk = getInt(KEY_TARGET_SDK),
        minSdk = getInt(KEY_MIN_SDK),
        applicationClassName = optionalNonBlankString(KEY_APPLICATION_CLASS_NAME),
        packageLabel = requiredString(KEY_PACKAGE_LABEL),
        requestedPermissions = stringList(KEY_REQUESTED_PERMISSIONS),
        activityClassNames = stringList(KEY_ACTIVITY_CLASS_NAMES),
        serviceClassNames = stringList(KEY_SERVICE_CLASS_NAMES),
        receiverClassNames = stringList(KEY_RECEIVER_CLASS_NAMES),
        providerClassNames = stringList(KEY_PROVIDER_CLASS_NAMES),
        nativeAbis = stringList(KEY_NATIVE_ABIS),
        splitApkPaths = stringList(KEY_SPLIT_APK_PATHS),
        splitPublicSourceDirs = stringList(KEY_SPLIT_PUBLIC_SOURCE_DIRS),
        splitNames = stringList(KEY_SPLIT_NAMES),
        isolatedSplits = getBoolean(KEY_ISOLATED_SPLITS)
    )
}.getOrNull()

internal fun EngineResult.toEngineIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putBoolean(EngineRuntimeIpcContract.KEY_FOUND, true)
    putString(EngineRuntimeIpcContract.KEY_OPERATION, operation)
    putString(EngineRuntimeIpcContract.KEY_STATUS, status.name)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
    putBundle(
        EngineRuntimeIpcContract.KEY_ENGINE_RUNTIME,
        runtime?.toAuthoritativeRuntimeBundle(bundleFactory)
    )
    putBundle(
        EngineRuntimeIpcContract.KEY_ENGINE_EVIDENCE,
        evidence?.toEngineEvidenceBundle(bundleFactory)
    )
}

internal fun Bundle.toEngineRemoteResultOrNull(): EngineRemoteResult? = runCatching {
    check(getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
    val operation = requiredString(EngineRuntimeIpcContract.KEY_OPERATION)
    val status = requiredEnum<EngineResultStatus>(EngineRuntimeIpcContract.KEY_STATUS)
    val runtimeBundle = getBundle(EngineRuntimeIpcContract.KEY_ENGINE_RUNTIME)
    val runtime = runtimeBundle?.toAuthoritativeRuntimeOrNull()
    check(runtimeBundle == null || runtime != null)
    val evidenceBundle = getBundle(EngineRuntimeIpcContract.KEY_ENGINE_EVIDENCE)
    val evidence = evidenceBundle?.toEngineEvidenceOrNull()
    check(evidenceBundle == null || evidence != null)
    EngineRemoteResult(
        result = EngineResult(
            operation = operation,
            status = status,
            instanceId = optionalNonBlankString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
            originPackageName = optionalNonBlankString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME),
            message = getString(EngineRuntimeIpcContract.KEY_MESSAGE),
            runtime = runtime,
            evidence = evidence
        ),
        runtimeIdentity = runtime?.toEngineRuntimeIdentity()
    )
}.getOrNull()

internal fun VirtualInstanceRuntime.toEngineRuntimeIdentityBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putBoolean(EngineRuntimeIpcContract.KEY_FOUND, true)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(KEY_HOST_PACKAGE_NAME, hostPackageName)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME, virtualPackageName)
    putString(KEY_DATA_ROOT, dataRoot)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE, profile.name)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_PROXY_SLOT, proxySlot)
    putString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID, evidenceSessionId)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
    processId?.let { putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, it) }
    putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
    putString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE, state.name)
}

internal fun Bundle.toEngineRuntimeIdentityOrNull(): EngineRuntimeIdentity? = runCatching {
    check(getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
    EngineRuntimeIdentity(
        instanceId = requiredString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
        hostPackageName = requiredString(KEY_HOST_PACKAGE_NAME),
        originPackageName = requiredString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME),
        virtualPackageName = requiredString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME),
        dataRoot = requiredString(KEY_DATA_ROOT),
        profile = requiredEnum(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE),
        processSlot = requiredString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
        proxySlot = requiredString(EngineRuntimeIpcContract.KEY_PROXY_SLOT),
        evidenceSessionId = requiredString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH).also { check(it > 0L) },
        engineSessionId = requiredString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID),
        processId = if (containsKey(EngineRuntimeIpcContract.KEY_PROCESS_ID)) {
            getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID).also { check(it > 0) }
        } else {
            null
        },
        processName = optionalNonBlankString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
        state = requiredEnum(EngineRuntimeIpcContract.KEY_RUNTIME_STATE)
    )
}.getOrNull()

private fun VirtualInstanceRuntime.toEngineRuntimeIdentity(): EngineRuntimeIdentity =
    EngineRuntimeIdentity(
        instanceId = instanceId,
        hostPackageName = hostPackageName,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        dataRoot = dataRoot,
        profile = profile,
        processSlot = processSlot,
        proxySlot = proxySlot,
        evidenceSessionId = evidenceSessionId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processId = processId,
        processName = processName,
        state = state
    )

internal fun EngineEvidenceReport.toEngineEvidenceBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putBoolean(EngineRuntimeIpcContract.KEY_FOUND, true)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID, evidenceSessionId)
    putString(EngineRuntimeIpcContract.KEY_STATUS, status.name)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE, profile.name)
    putBundle(
        EngineRuntimeIpcContract.KEY_ENTRIES,
        entries.toEngineStringMapBundle(bundleFactory)
    )
    putBundle(
        EngineRuntimeIpcContract.KEY_ENGINE_SUBSYSTEM_VERDICTS,
        subsystemVerdicts.mapKeys { it.key.name }
            .mapValues { it.value.name }
            .toEngineStringMapBundle(bundleFactory)
    )
    val operationEvidenceBundle = bundleFactory()
    val flattenedEvidence = flattenedOperationEvidence()
    operationEvidenceBundle.putInt(
        EngineRuntimeIpcContract.KEY_OPERATION_COUNT,
        flattenedEvidence.size
    )
    flattenedEvidence.forEachIndexed { index, evidence ->
        operationEvidenceBundle.putBundle(
            index.toString(),
            evidence.toEngineEvidenceEntryBundle(bundleFactory)
        )
    }
    putBundle(
        EngineRuntimeIpcContract.KEY_ENGINE_OPERATION_EVIDENCE,
        operationEvidenceBundle
    )
}

internal fun Bundle.toEngineEvidenceOrNull(): EngineEvidenceReport? = runCatching {
    check(getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
    val subsystemVerdicts = getBundle(EngineRuntimeIpcContract.KEY_ENGINE_SUBSYSTEM_VERDICTS)
        ?.toEngineStringMapOrNull()
        ?.map { (subsystem, verdict) ->
            enumValueOf<EngineSubsystem>(subsystem) to enumValueOf<EngineResultStatus>(verdict)
        }
        ?.toMap()
        ?: emptyMap()
    val operationEvidenceBundle = getBundle(
        EngineRuntimeIpcContract.KEY_ENGINE_OPERATION_EVIDENCE
    )
    val operationCount = operationEvidenceBundle
        ?.getInt(EngineRuntimeIpcContract.KEY_OPERATION_COUNT)
        ?.also { check(it >= 0) }
        ?: 0
    val operationBundles = (0 until operationCount).map { index ->
        operationEvidenceBundle?.getBundle(index.toString())
            ?: error("missing operation evidence $index")
    }
    val operationEvidence = operationBundles.map { bundle ->
        bundle.toEngineEvidenceEntryOrNull() ?: error("invalid operation evidence")
    }.groupBy { evidence -> evidence.component }
        .mapValues { (_, componentEntries) -> componentEntries.groupBy { it.operation } }
    EngineEvidenceReport(
        instanceId = requiredString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
        evidenceSessionId = requiredString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID),
        status = requiredEnum(EngineRuntimeIpcContract.KEY_STATUS),
        profile = requiredEnum(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE),
        entries = getBundle(EngineRuntimeIpcContract.KEY_ENTRIES)
            ?.toEngineStringMapOrNull()
            ?: emptyMap(),
        operationEvidence = operationEvidence,
        subsystemVerdicts = subsystemVerdicts
    )
}.getOrNull()

internal fun engineOperationUnavailableBundle(operation: String): Bundle =
    EngineResult.fail(operation = operation, message = "engine_server_owner_unavailable").toEngineIpcBundle()

internal fun engineInvalidRequestBundle(operation: String): Bundle =
    EngineResult.fail(operation = operation, message = "invalid_engine_ipc_request").toEngineIpcBundle()

internal fun engineMissingRuntimeBundle(instanceId: String): Bundle = Bundle().apply {
    putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
    putString(EngineRuntimeIpcContract.KEY_REASON, "runtime_not_found")
}

private fun EngineOperationEvidence.toEngineEvidenceEntryBundle(
    bundleFactory: () -> Bundle
): Bundle = bundleFactory().apply {
    putString(EngineRuntimeIpcContract.KEY_COMPONENT, component)
    putString(EngineRuntimeIpcContract.KEY_OPERATION, operation)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putBundle(
        EngineRuntimeIpcContract.KEY_ENTRIES,
        entries.toEngineStringMapBundle(bundleFactory)
    )
}

private fun Bundle.toEngineEvidenceEntryOrNull(): EngineOperationEvidence? = runCatching {
    EngineOperationEvidence(
        component = requiredString(EngineRuntimeIpcContract.KEY_COMPONENT),
        operation = requiredString(EngineRuntimeIpcContract.KEY_OPERATION),
        verdict = requiredEnum(EngineRuntimeIpcContract.KEY_VERDICT),
        entries = getBundle(EngineRuntimeIpcContract.KEY_ENTRIES)
            ?.toEngineStringMapOrNull()
            ?: emptyMap()
    )
}.getOrNull()

private fun Map<String, String>.toEngineStringMapBundle(
    bundleFactory: () -> Bundle
): Bundle = bundleFactory().apply {
    entries.sortedBy { it.key }.forEach { (key, value) -> putString(key, value) }
}

private fun Bundle.toEngineStringMapOrNull(): Map<String, String>? = runCatching {
    keySet().sorted().associateWith { key -> getString(key) ?: error("non-string map value") }
}.getOrNull()

private fun Bundle.requiredString(key: String): String =
    getString(key)?.takeIf { it.isNotBlank() } ?: error("missing $key")

private fun Bundle.optionalNonBlankString(key: String): String? =
    getString(key)?.also { check(it.isNotBlank()) }

private fun Bundle.stringList(key: String): List<String> =
    getStringArrayList(key)?.toList() ?: emptyList()

private inline fun <reified T : Enum<T>> Bundle.requiredEnum(key: String): T =
    enumValueOf(requiredString(key))

internal interface EngineVirtualizationRemote {
    fun installOrRefreshPackage(originPackageName: String): EngineRemoteResult?
    fun createInstance(originPackageName: String): EngineRemoteResult?
    fun createInstance(request: CreateInstanceRequest): EngineRemoteResult? =
        createInstance(request.originPackageName)
    fun launchInstance(request: LaunchInstanceRequest): EngineRemoteResult?
    fun stopInstance(instanceId: String): EngineRemoteResult?
    fun deleteInstance(instanceId: String): EngineRemoteResult?
    fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime?
    fun exportEvidence(instanceId: String): EngineEvidenceReport?
}

private object BinderEngineVirtualizationRemote : EngineVirtualizationRemote {
    override fun installOrRefreshPackage(originPackageName: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineInstallOrRefreshPackage(originPackageName)

    override fun createInstance(originPackageName: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineCreateInstance(originPackageName)

    override fun createInstance(request: CreateInstanceRequest): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineCreateInstance(request)

    override fun launchInstance(request: LaunchInstanceRequest): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineLaunchInstance(request)

    override fun stopInstance(instanceId: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineStopInstance(instanceId)

    override fun deleteInstance(instanceId: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineDeleteInstance(instanceId)

    override fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime? =
        EngineRuntimeIpcClients.engineQueryRuntimeState(instanceId)

    override fun exportEvidence(instanceId: String): EngineEvidenceReport? =
        EngineRuntimeIpcClients.engineExportEvidence(instanceId)
}

@Singleton
class IpcVirtualizationEngine @Inject constructor(
    @ApplicationContext context: Context
) : VirtualizationEngine {
    private val hostContext = context.applicationContext ?: context
    private val core = IpcVirtualizationEngineCore(remote = BinderEngineVirtualizationRemote)

    init {
        EngineRuntimeIpcClients.install(hostContext)
    }

    override fun installOrRefreshPackage(originPackageName: String): EngineResult =
        core.installOrRefreshPackage(originPackageName)

    override fun createInstance(originPackageName: String): EngineResult =
        core.createInstance(originPackageName)

    override fun createInstance(request: CreateInstanceRequest): EngineResult =
        core.createInstance(request)

    override fun launchInstance(request: LaunchInstanceRequest): EngineResult = core.launchInstance(request)

    override fun stopInstance(instanceId: String): EngineResult = core.stopInstance(instanceId)

    override fun deleteInstance(instanceId: String): EngineResult = core.deleteInstance(instanceId)

    override fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime? =
        core.queryRuntimeState(instanceId)

    override fun exportEvidence(instanceId: String): EngineEvidenceReport = core.exportEvidence(instanceId)
}

internal class IpcVirtualizationEngineCore(
    private val remote: EngineVirtualizationRemote
) : VirtualizationEngine {
    override fun installOrRefreshPackage(originPackageName: String): EngineResult =
        complete(
            operation = "installOrRefreshPackage",
            instanceId = null,
            originPackageName = originPackageName,
            remoteResult = remote.installOrRefreshPackage(originPackageName)
        )

    override fun createInstance(originPackageName: String): EngineResult =
        complete(
            operation = "createInstance",
            instanceId = null,
            originPackageName = originPackageName,
            remoteResult = remote.createInstance(originPackageName)
        )

    override fun createInstance(request: CreateInstanceRequest): EngineResult =
        complete(
            operation = "createInstance",
            instanceId = null,
            originPackageName = request.originPackageName,
            remoteResult = remote.createInstance(request)
        )

    override fun launchInstance(request: LaunchInstanceRequest): EngineResult =
        complete(
            operation = "launchInstance",
            instanceId = request.instanceId,
            originPackageName = null,
            remoteResult = remote.launchInstance(request)
        )

    override fun stopInstance(instanceId: String): EngineResult =
        complete(
            operation = "stopInstance",
            instanceId = instanceId,
            originPackageName = null,
            remoteResult = remote.stopInstance(instanceId)
        )

    override fun deleteInstance(instanceId: String): EngineResult =
        complete(
            operation = "deleteInstance",
            instanceId = instanceId,
            originPackageName = null,
            remoteResult = remote.deleteInstance(instanceId)
        )

    override fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime? {
        return remote.queryRuntimeState(instanceId)?.takeIf { runtime ->
            runtime.instanceId == instanceId
        }
    }

    override fun exportEvidence(instanceId: String): EngineEvidenceReport =
        remote.exportEvidence(instanceId) ?: EngineEvidenceReport(
            instanceId = instanceId.ifBlank { "invalid-instance" },
            evidenceSessionId = "engine-unavailable",
            status = EngineResultStatus.FAIL,
            profile = EngineProfile.BASELINE,
            entries = mapOf("reason" to "engine_authority_unavailable_or_unknown_result")
        )

    private fun complete(
        operation: String,
        instanceId: String?,
        originPackageName: String?,
        remoteResult: EngineRemoteResult?
    ): EngineResult {
        val remoteValue = remoteResult ?: return EngineResult.fail(
            operation = operation,
            instanceId = instanceId,
            originPackageName = originPackageName,
            message = "engine_authority_unavailable_or_unknown_result"
        )
        val identity = remoteValue.runtimeIdentity ?: return remoteValue.result
        val runtime = remoteValue.result.runtime?.takeIf(identity::matches)
            ?: return EngineResult.fail(
                operation = operation,
                instanceId = identity.instanceId,
                originPackageName = remoteValue.result.originPackageName ?: originPackageName,
                message = "authoritative_runtime_snapshot_mismatch"
            )
        return remoteValue.result.copy(runtime = runtime)
    }
}

private const val KEY_TASK_POLICY = "engineTaskPolicy"
private const val KEY_PREWARM_POLICY = "enginePrewarmPolicy"
private const val KEY_EVIDENCE_MODE = "engineEvidenceMode"
private const val KEY_HOST_PACKAGE_NAME = "hostPackageName"
private const val KEY_DATA_ROOT = "dataRoot"
private const val KEY_CREATION_REQUEST_ID = "creationRequestId"
private const val KEY_DISPLAY_NAME = "displayName"
private const val KEY_COMPATIBILITY_MODE = "compatibilityMode"
private const val KEY_INSTALL_REQUEST = "installRequest"
private const val KEY_ORIGIN_APK_PATH = "originApkPath"
private const val KEY_VERSION_CODE = "versionCode"
private const val KEY_VERSION_NAME = "versionName"
private const val KEY_TARGET_SDK = "targetSdk"
private const val KEY_MIN_SDK = "minSdk"
private const val KEY_APPLICATION_CLASS_NAME = "applicationClassName"
private const val KEY_PACKAGE_LABEL = "packageLabel"
private const val KEY_REQUESTED_PERMISSIONS = "requestedPermissions"
private const val KEY_ACTIVITY_CLASS_NAMES = "activityClassNames"
private const val KEY_SERVICE_CLASS_NAMES = "serviceClassNames"
private const val KEY_RECEIVER_CLASS_NAMES = "receiverClassNames"
private const val KEY_PROVIDER_CLASS_NAMES = "providerClassNames"
private const val KEY_NATIVE_ABIS = "nativeAbis"
private const val KEY_SPLIT_APK_PATHS = "splitApkPaths"
private const val KEY_SPLIT_PUBLIC_SOURCE_DIRS = "splitPublicSourceDirs"
private const val KEY_SPLIT_NAMES = "splitNames"
private const val KEY_ISOLATED_SPLITS = "isolatedSplits"
