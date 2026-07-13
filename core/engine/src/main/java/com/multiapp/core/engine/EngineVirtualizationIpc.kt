package com.multiapp.core.engine

import android.content.Context
import android.os.Bundle
import com.multiapp.core.model.engine.EngineEvidenceMode
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
        runtime?.toEngineRuntimeIdentityBundle(bundleFactory)
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
    val runtimeIdentity = runtimeBundle?.toEngineRuntimeIdentityOrNull()
    check(runtimeBundle == null || runtimeIdentity != null)
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
            runtime = null,
            evidence = evidence
        ),
        runtimeIdentity = runtimeIdentity
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

private inline fun <reified T : Enum<T>> Bundle.requiredEnum(key: String): T =
    enumValueOf(requiredString(key))

internal interface EngineVirtualizationRemote {
    fun installOrRefreshPackage(originPackageName: String): EngineRemoteResult?
    fun createInstance(originPackageName: String): EngineRemoteResult?
    fun launchInstance(request: LaunchInstanceRequest): EngineRemoteResult?
    fun stopInstance(instanceId: String): EngineRemoteResult?
    fun deleteInstance(instanceId: String): EngineRemoteResult?
    fun queryRuntimeState(instanceId: String): EngineRuntimeIdentity?
    fun exportEvidence(instanceId: String): EngineEvidenceReport?
}

private object BinderEngineVirtualizationRemote : EngineVirtualizationRemote {
    override fun installOrRefreshPackage(originPackageName: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineInstallOrRefreshPackage(originPackageName)

    override fun createInstance(originPackageName: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineCreateInstance(originPackageName)

    override fun launchInstance(request: LaunchInstanceRequest): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineLaunchInstance(request)

    override fun stopInstance(instanceId: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineStopInstance(instanceId)

    override fun deleteInstance(instanceId: String): EngineRemoteResult? =
        EngineRuntimeIpcClients.engineDeleteInstance(instanceId)

    override fun queryRuntimeState(instanceId: String): EngineRuntimeIdentity? =
        EngineRuntimeIpcClients.engineQueryRuntimeState(instanceId)

    override fun exportEvidence(instanceId: String): EngineEvidenceReport? =
        EngineRuntimeIpcClients.engineExportEvidence(instanceId)
}

@Singleton
class IpcVirtualizationEngine @Inject constructor(
    @ApplicationContext context: Context
) : VirtualizationEngine {
    private val hostContext = context.applicationContext ?: context
    private val core = IpcVirtualizationEngineCore(
        remote = BinderEngineVirtualizationRemote,
        runtimeReader = { instanceId ->
            FileBackedEngineRuntimeStateStore(
                File(hostContext.filesDir, EngineRuntimeStateFiles.DEFAULT_FILE_NAME)
            ).get(instanceId)?.toRuntime()
        }
    )

    init {
        EngineRuntimeIpcClients.install(hostContext)
    }

    override fun installOrRefreshPackage(originPackageName: String): EngineResult =
        core.installOrRefreshPackage(originPackageName)

    override fun createInstance(originPackageName: String): EngineResult =
        core.createInstance(originPackageName)

    override fun launchInstance(request: LaunchInstanceRequest): EngineResult = core.launchInstance(request)

    override fun stopInstance(instanceId: String): EngineResult = core.stopInstance(instanceId)

    override fun deleteInstance(instanceId: String): EngineResult = core.deleteInstance(instanceId)

    override fun queryRuntimeState(instanceId: String): VirtualInstanceRuntime? =
        core.queryRuntimeState(instanceId)

    override fun exportEvidence(instanceId: String): EngineEvidenceReport = core.exportEvidence(instanceId)
}

internal class IpcVirtualizationEngineCore(
    private val remote: EngineVirtualizationRemote,
    private val runtimeReader: (String) -> VirtualInstanceRuntime?
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
        val identity = remote.queryRuntimeState(instanceId) ?: return null
        return runtimeReader(instanceId)?.takeIf(identity::matches)
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
        val runtime = runtimeReader(identity.instanceId)?.takeIf(identity::matches)
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
