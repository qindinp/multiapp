package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualProviderService(
    @Suppress("UNUSED_PARAMETER") fallback: VirtualProviderService,
    private val remotePlan: (String, VirtualProviderDispatchPlanRequest) -> VirtualProviderDispatchPlan? =
        EngineRuntimeIpcClients::planProvider,
    private val remoteResolve: (
        String,
        VirtualProviderAuthorityResolveRequest
    ) -> VirtualProviderAuthorityResolveResult? = EngineRuntimeIpcClients::resolveProviderAuthority,
    private val remoteRecord: (String, VirtualProviderOperationResult) -> Boolean? =
        EngineRuntimeIpcClients::recordProviderDispatch,
    private val remoteState: (String) -> VirtualProviderRuntimeState? =
        EngineRuntimeIpcClients::queryProviderRuntimeState,
    private val remoteGrant: (String, VirtualProviderUriGrantRequest) -> VirtualProviderUriGrantResult? =
        EngineRuntimeIpcClients::grantProviderUriPermission,
    private val remoteRevoke: (String, VirtualProviderUriGrantRequest) -> VirtualProviderUriGrantResult? =
        EngineRuntimeIpcClients::revokeProviderUriPermission,
    private val remoteCheck: (String, VirtualProviderUriGrantRequest) -> VirtualProviderUriGrantResult? =
        EngineRuntimeIpcClients::checkProviderUriPermission,
    private val remoteTakePersistable: (
        String,
        VirtualProviderUriGrantRequest
    ) -> VirtualProviderUriGrantResult? = EngineRuntimeIpcClients::takePersistableProviderUriPermission,
    private val remoteReleasePersistable: (
        String,
        VirtualProviderUriGrantRequest
    ) -> VirtualProviderUriGrantResult? = EngineRuntimeIpcClients::releasePersistableProviderUriPermission,
    private val readOnlyRuntimeStateSnapshot: (String) -> VirtualProviderRuntimeState? = { null },
    private val readOnlyRuntimeBindingSnapshot: (String) -> VirtualSubsystemRuntimeBinding? = { null },
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualProviderService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PROVIDER

    override fun resolveProviderAuthority(
        callerInstanceId: String,
        request: VirtualProviderAuthorityResolveRequest
    ): VirtualProviderAuthorityResolveResult {
        runCatching { remoteResolve(callerInstanceId, request) }.getOrNull()
            ?.takeIf {
                it.callerInstanceId == callerInstanceId &&
                    it.guestAuthority == request.guestAuthority &&
                    (it.targetInstanceId == null || it.targetInstanceId.isNotBlank())
            }
            ?.let { return it }
        return VirtualProviderAuthorityResolveResult(
            callerInstanceId = callerInstanceId.ifBlank { "invalid" },
            guestAuthority = request.guestAuthority,
            verdict = EngineResultStatus.FAIL,
            virtualAuthority = true,
            message = authorityFailureMessage(
                invalid = "engine_provider_ipc_authority_resolve_invalid",
                unavailable = "engine_provider_authority_unavailable:resolve"
            )
        )
    }

    override fun planProvider(
        instanceId: String,
        request: VirtualProviderDispatchPlanRequest
    ): VirtualProviderDispatchPlan {
        runCatching { remotePlan(instanceId, request) }.getOrNull()
            ?.takeIf {
                it.instanceId == instanceId &&
                    it.operation == request.operation &&
                    it.guestAuthority == request.guestAuthority &&
                    it.targets.all { target -> target.instanceId == instanceId }
            }
            ?.let { return it }
        return VirtualProviderDispatchPlan(
            instanceId = instanceId,
            operation = request.operation,
            verdict = EngineResultStatus.FAIL,
            guestAuthority = request.guestAuthority,
            message = authorityFailureMessage(
                invalid = "engine_provider_ipc_plan_invalid",
                unavailable = "engine_provider_authority_unavailable:plan"
            )
        )
    }

    override fun recordProviderDispatch(instanceId: String, result: VirtualProviderOperationResult): Boolean {
        if (result.instanceId != instanceId) return false
        return runCatching { remoteRecord(instanceId, result) }.getOrNull() ?: false
    }

    override fun queryProviderRuntimeState(instanceId: String): VirtualProviderRuntimeState {
        runCatching { remoteState(instanceId) }.getOrNull()
            ?.takeIf { state ->
                state.instanceId == instanceId && state.records.all { it.instanceId == instanceId }
            }
            ?.let { return it }
        if (!authorityConnected()) {
            val snapshot = runCatching { readOnlyRuntimeStateSnapshot(instanceId) }.getOrNull()
                ?.takeIf { state ->
                    state.instanceId == instanceId && state.records.all { it.instanceId == instanceId }
                }
            if (snapshot != null) {
                return snapshot.copy(
                    verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                    message = "engine_provider_read_only_runtime_snapshot:${snapshot.message}"
                )
            }
        }
        return VirtualProviderRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = authorityFailureMessage(
                invalid = "engine_provider_ipc_runtime_state_invalid",
                unavailable = "engine_provider_authority_unavailable:query-runtime-state"
            )
        )
    }

    override fun grantUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = ownerInstanceId,
        request = request,
        remote = remoteGrant,
        invalidMessage = "engine_provider_ipc_uri_grant_invalid"
    )

    override fun revokeUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = ownerInstanceId,
        request = request,
        remote = remoteRevoke,
        invalidMessage = "engine_provider_ipc_uri_revoke_invalid"
    )

    override fun checkUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = targetInstanceId,
        request = request,
        remote = remoteCheck,
        invalidMessage = "engine_provider_ipc_uri_check_invalid"
    )

    override fun takePersistableUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = targetInstanceId,
        request = request,
        remote = remoteTakePersistable,
        invalidMessage = "engine_provider_ipc_persistable_uri_take_invalid"
    )

    override fun releasePersistableUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = targetInstanceId,
        request = request,
        remote = remoteReleasePersistable,
        invalidMessage = "engine_provider_ipc_persistable_uri_release_invalid"
    )

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding {
        val snapshot = runCatching { readOnlyRuntimeBindingSnapshot(instanceId) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.subsystem == subsystem }
        if (snapshot != null) {
            return snapshot.copy(
                verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                message = "engine_provider_read_only_binding_snapshot:${snapshot.message}"
            )
        }
        return VirtualSubsystemRuntimeBinding(
            instanceId = instanceId.ifBlank { "invalid" },
            subsystem = subsystem,
            verdict = EngineResultStatus.FAIL,
            message = "engine_provider_runtime_snapshot_unavailable"
        )
    }

    private fun authorityOwnedUriGrantResult(
        primaryInstanceId: String,
        request: VirtualProviderUriGrantRequest,
        remote: (String, VirtualProviderUriGrantRequest) -> VirtualProviderUriGrantResult?,
        invalidMessage: String
    ): VirtualProviderUriGrantResult {
        val expectedOwner = request.ownerInstanceId ?: primaryInstanceId
        val expectedTarget = request.targetInstanceId ?: primaryInstanceId
        runCatching { remote(primaryInstanceId, request) }.getOrNull()
            ?.takeIf {
                it.ownerInstanceId == expectedOwner &&
                    it.targetInstanceId == expectedTarget &&
                    it.guestAuthority == request.guestAuthority &&
                    it.encodedPath == request.encodedPath &&
                    it.modeFlags == request.modeFlags &&
                    it.affectedGrantCount >= 0 &&
                    it.persistedModeFlags >= 0
            }
            ?.let { return it }
        return VirtualProviderUriGrantResult(
            ownerInstanceId = expectedOwner,
            targetInstanceId = expectedTarget,
            guestAuthority = request.guestAuthority,
            encodedPath = request.encodedPath,
            modeFlags = request.modeFlags,
            verdict = EngineResultStatus.FAIL,
            granted = false,
            message = authorityFailureMessage(
                invalid = invalidMessage,
                unavailable = "engine_provider_authority_unavailable:${uriGrantOperation(invalidMessage)}"
            )
        )
    }

    private fun authorityFailureMessage(invalid: String, unavailable: String): String =
        if (authorityConnected()) invalid else unavailable

    private fun uriGrantOperation(invalidMessage: String): String =
        invalidMessage.removePrefix("engine_provider_ipc_").removeSuffix("_invalid")

    private fun EngineResultStatus.asReadOnlySnapshotVerdict(): EngineResultStatus =
        if (this == EngineResultStatus.FAIL) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL
}
