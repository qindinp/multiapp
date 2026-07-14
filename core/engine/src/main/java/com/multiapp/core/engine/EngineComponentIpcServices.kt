package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualServiceService(
    @Suppress("UNUSED_PARAMETER") fallback: VirtualServiceService? = null,
    private val remotePlan: (String, VirtualServiceDispatchPlanRequest) -> VirtualServiceDispatchPlan? =
        EngineRuntimeIpcClients::planService,
    private val remoteRecord: (String, VirtualServiceOperationResult) -> Boolean? =
        EngineRuntimeIpcClients::recordServiceDispatch,
    private val remoteState: (String) -> VirtualServiceRuntimeState? =
        EngineRuntimeIpcClients::queryServiceRuntimeState,
    private val readOnlyRuntimeStateSnapshot: (String) -> VirtualServiceRuntimeState? = { null },
    private val readOnlyRuntimeBindingSnapshot: (String) -> VirtualSubsystemRuntimeBinding? = { null },
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualServiceService {
    override val subsystem: EngineSubsystem = EngineSubsystem.SERVICE

    override fun planService(
        instanceId: String,
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceDispatchPlan {
        runCatching { remotePlan(instanceId, request) }.getOrNull()
            ?.takeIf {
                it.instanceId == instanceId &&
                    it.operation == request.operation &&
                    it.targets.all { target -> target.instanceId == instanceId } &&
                    when (it.verdict) {
                        EngineResultStatus.PASS,
                        EngineResultStatus.PARTIAL -> !request.operationLeaseRequested ||
                            it.targets.size == 1 && it.targets.single().operationLease != null
                        EngineResultStatus.FAIL,
                        EngineResultStatus.UNSUPPORTED -> true
                    }
            }
            ?.let { return it }
        return VirtualServiceDispatchPlan(
            instanceId = instanceId,
            operation = request.operation,
            verdict = EngineResultStatus.FAIL,
            action = request.action,
            message = authorityFailureMessage(
                invalid = "engine_service_ipc_plan_invalid",
                unavailable = "engine_service_authority_unavailable:plan"
            )
        )
    }

    override fun recordServiceDispatch(instanceId: String, result: VirtualServiceOperationResult): Boolean {
        if (result.instanceId != instanceId) return false
        return runCatching { remoteRecord(instanceId, result) }.getOrNull() ?: false
    }

    override fun queryServiceRuntimeState(instanceId: String): VirtualServiceRuntimeState {
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
                    message = "engine_service_read_only_runtime_snapshot:${snapshot.message}"
                )
            }
        }
        return VirtualServiceRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = authorityFailureMessage(
                invalid = "engine_service_ipc_runtime_state_invalid",
                unavailable = "engine_service_authority_unavailable:query-runtime-state"
            )
        )
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding {
        val snapshot = runCatching { readOnlyRuntimeBindingSnapshot(instanceId) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.subsystem == subsystem }
        if (snapshot != null) {
            return snapshot.copy(
                verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                message = "engine_service_read_only_binding_snapshot:${snapshot.message}"
            )
        }
        return VirtualSubsystemRuntimeBinding(
            instanceId = instanceId.ifBlank { "invalid" },
            subsystem = subsystem,
            verdict = EngineResultStatus.FAIL,
            message = "engine_service_runtime_snapshot_unavailable"
        )
    }

    private fun authorityFailureMessage(invalid: String, unavailable: String): String =
        if (authorityConnected()) invalid else unavailable

    private fun EngineResultStatus.asReadOnlySnapshotVerdict(): EngineResultStatus =
        if (this == EngineResultStatus.FAIL) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL
}

class IpcBackedVirtualBroadcastService(
    @Suppress("UNUSED_PARAMETER") fallback: VirtualBroadcastService? = null,
    private val remotePlan: (String, VirtualBroadcastDispatchPlanRequest) -> VirtualBroadcastDispatchPlan? =
        EngineRuntimeIpcClients::planBroadcast,
    private val remoteRecord: (String, VirtualBroadcastOperationResult) -> Boolean? =
        EngineRuntimeIpcClients::recordBroadcastDispatch,
    private val remoteState: (String) -> VirtualBroadcastRuntimeState? =
        EngineRuntimeIpcClients::queryBroadcastRuntimeState,
    private val readOnlyRuntimeStateSnapshot: (String) -> VirtualBroadcastRuntimeState? = { null },
    private val readOnlyRuntimeBindingSnapshot: (String) -> VirtualSubsystemRuntimeBinding? = { null },
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualBroadcastService {
    override val subsystem: EngineSubsystem = EngineSubsystem.BROADCAST

    override fun planBroadcast(
        instanceId: String,
        request: VirtualBroadcastDispatchPlanRequest
    ): VirtualBroadcastDispatchPlan {
        runCatching { remotePlan(instanceId, request) }.getOrNull()
            ?.takeIf {
                it.instanceId == instanceId && it.targets.all { target -> target.instanceId == instanceId }
            }
            ?.let { return it }
        return VirtualBroadcastDispatchPlan(
            instanceId = instanceId,
            verdict = EngineResultStatus.FAIL,
            action = request.action,
            message = authorityFailureMessage(
                invalid = "engine_broadcast_ipc_plan_invalid",
                unavailable = "engine_broadcast_authority_unavailable:plan"
            )
        )
    }

    override fun recordBroadcastDispatch(instanceId: String, result: VirtualBroadcastOperationResult): Boolean {
        if (result.instanceId != instanceId) return false
        return runCatching { remoteRecord(instanceId, result) }.getOrNull() ?: false
    }

    override fun queryBroadcastRuntimeState(instanceId: String): VirtualBroadcastRuntimeState {
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
                    message = "engine_broadcast_read_only_runtime_snapshot:${snapshot.message}"
                )
            }
        }
        return VirtualBroadcastRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = authorityFailureMessage(
                invalid = "engine_broadcast_ipc_runtime_state_invalid",
                unavailable = "engine_broadcast_authority_unavailable:query-runtime-state"
            )
        )
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding {
        val snapshot = runCatching { readOnlyRuntimeBindingSnapshot(instanceId) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.subsystem == subsystem }
        if (snapshot != null) {
            return snapshot.copy(
                verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                message = "engine_broadcast_read_only_binding_snapshot:${snapshot.message}"
            )
        }
        return VirtualSubsystemRuntimeBinding(
            instanceId = instanceId.ifBlank { "invalid" },
            subsystem = subsystem,
            verdict = EngineResultStatus.FAIL,
            message = "engine_broadcast_runtime_snapshot_unavailable"
        )
    }

    private fun authorityFailureMessage(invalid: String, unavailable: String): String =
        if (authorityConnected()) invalid else unavailable

    private fun EngineResultStatus.asReadOnlySnapshotVerdict(): EngineResultStatus =
        if (this == EngineResultStatus.FAIL) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL
}
