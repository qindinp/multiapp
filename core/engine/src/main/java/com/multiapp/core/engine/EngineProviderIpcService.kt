package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualProviderService(
    private val fallback: VirtualProviderService,
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
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualProviderService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PROVIDER

    override fun resolveProviderAuthority(
        callerInstanceId: String,
        request: VirtualProviderAuthorityResolveRequest
    ): VirtualProviderAuthorityResolveResult {
        remoteResolve(callerInstanceId, request)?.let { return it }
        if (!authorityConnected()) return fallback.resolveProviderAuthority(callerInstanceId, request)
        return VirtualProviderAuthorityResolveResult(
            callerInstanceId = callerInstanceId.ifBlank { "invalid" },
            guestAuthority = request.guestAuthority,
            verdict = EngineResultStatus.FAIL,
            virtualAuthority = true,
            message = "engine_provider_ipc_authority_resolve_invalid"
        )
    }

    override fun planProvider(
        instanceId: String,
        request: VirtualProviderDispatchPlanRequest
    ): VirtualProviderDispatchPlan {
        remotePlan(instanceId, request)?.let { return it }
        if (!authorityConnected()) return fallback.planProvider(instanceId, request)
        return VirtualProviderDispatchPlan(
            instanceId = instanceId,
            operation = request.operation,
            verdict = EngineResultStatus.FAIL,
            guestAuthority = request.guestAuthority,
            message = "engine_provider_ipc_plan_invalid"
        )
    }

    override fun recordProviderDispatch(instanceId: String, result: VirtualProviderOperationResult): Boolean {
        val remote = remoteRecord(instanceId, result)
        return remote ?: if (authorityConnected()) false else fallback.recordProviderDispatch(instanceId, result)
    }

    override fun queryProviderRuntimeState(instanceId: String): VirtualProviderRuntimeState {
        remoteState(instanceId)?.let { return it }
        if (!authorityConnected()) return fallback.queryProviderRuntimeState(instanceId)
        return VirtualProviderRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = "engine_provider_ipc_runtime_state_invalid"
        )
    }

    override fun grantUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = ownerInstanceId,
        request = request,
        remote = remoteGrant,
        fallbackCall = fallback::grantUriPermission,
        invalidMessage = "engine_provider_ipc_uri_grant_invalid"
    )

    override fun revokeUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = ownerInstanceId,
        request = request,
        remote = remoteRevoke,
        fallbackCall = fallback::revokeUriPermission,
        invalidMessage = "engine_provider_ipc_uri_revoke_invalid"
    )

    override fun checkUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = targetInstanceId,
        request = request,
        remote = remoteCheck,
        fallbackCall = fallback::checkUriPermission,
        invalidMessage = "engine_provider_ipc_uri_check_invalid"
    )

    override fun takePersistableUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = targetInstanceId,
        request = request,
        remote = remoteTakePersistable,
        fallbackCall = fallback::takePersistableUriPermission,
        invalidMessage = "engine_provider_ipc_persistable_uri_take_invalid"
    )

    override fun releasePersistableUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult = authorityOwnedUriGrantResult(
        primaryInstanceId = targetInstanceId,
        request = request,
        remote = remoteReleasePersistable,
        fallbackCall = fallback::releasePersistableUriPermission,
        invalidMessage = "engine_provider_ipc_persistable_uri_release_invalid"
    )

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding =
        fallback.queryRuntimeBinding(instanceId)

    private fun authorityOwnedUriGrantResult(
        primaryInstanceId: String,
        request: VirtualProviderUriGrantRequest,
        remote: (String, VirtualProviderUriGrantRequest) -> VirtualProviderUriGrantResult?,
        fallbackCall: (String, VirtualProviderUriGrantRequest) -> VirtualProviderUriGrantResult,
        invalidMessage: String
    ): VirtualProviderUriGrantResult {
        remote(primaryInstanceId, request)?.let { return it }
        if (!authorityConnected()) return fallbackCall(primaryInstanceId, request)
        return VirtualProviderUriGrantResult(
            ownerInstanceId = request.ownerInstanceId ?: primaryInstanceId,
            targetInstanceId = request.targetInstanceId ?: primaryInstanceId,
            guestAuthority = request.guestAuthority,
            encodedPath = request.encodedPath,
            modeFlags = request.modeFlags,
            verdict = EngineResultStatus.FAIL,
            granted = false,
            message = invalidMessage
        )
    }
}
