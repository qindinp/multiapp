package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualServiceService(
    private val fallback: VirtualServiceService,
    private val remotePlan: (String, VirtualServiceDispatchPlanRequest) -> VirtualServiceDispatchPlan? =
        EngineRuntimeIpcClients::planService,
    private val remoteRecord: (String, VirtualServiceOperationResult) -> Boolean? =
        EngineRuntimeIpcClients::recordServiceDispatch,
    private val remoteState: (String) -> VirtualServiceRuntimeState? =
        EngineRuntimeIpcClients::queryServiceRuntimeState,
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualServiceService {
    override val subsystem: EngineSubsystem = EngineSubsystem.SERVICE

    override fun planService(
        instanceId: String,
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceDispatchPlan {
        remotePlan(instanceId, request)?.let { return it }
        if (!authorityConnected()) return fallback.planService(instanceId, request)
        return VirtualServiceDispatchPlan(
            instanceId = instanceId,
            operation = request.operation,
            verdict = EngineResultStatus.FAIL,
            action = request.action,
            message = "engine_service_ipc_plan_invalid"
        )
    }

    override fun recordServiceDispatch(instanceId: String, result: VirtualServiceOperationResult): Boolean {
        val remote = remoteRecord(instanceId, result)
        return remote ?: if (authorityConnected()) false else fallback.recordServiceDispatch(instanceId, result)
    }

    override fun queryServiceRuntimeState(instanceId: String): VirtualServiceRuntimeState {
        remoteState(instanceId)?.let { return it }
        if (!authorityConnected()) return fallback.queryServiceRuntimeState(instanceId)
        return VirtualServiceRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = "engine_service_ipc_runtime_state_invalid"
        )
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding =
        fallback.queryRuntimeBinding(instanceId)
}

class IpcBackedVirtualBroadcastService(
    private val fallback: VirtualBroadcastService,
    private val remotePlan: (String, VirtualBroadcastDispatchPlanRequest) -> VirtualBroadcastDispatchPlan? =
        EngineRuntimeIpcClients::planBroadcast,
    private val remoteRecord: (String, VirtualBroadcastOperationResult) -> Boolean? =
        EngineRuntimeIpcClients::recordBroadcastDispatch,
    private val remoteState: (String) -> VirtualBroadcastRuntimeState? =
        EngineRuntimeIpcClients::queryBroadcastRuntimeState,
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualBroadcastService {
    override val subsystem: EngineSubsystem = EngineSubsystem.BROADCAST

    override fun planBroadcast(
        instanceId: String,
        request: VirtualBroadcastDispatchPlanRequest
    ): VirtualBroadcastDispatchPlan {
        remotePlan(instanceId, request)?.let { return it }
        if (!authorityConnected()) return fallback.planBroadcast(instanceId, request)
        return VirtualBroadcastDispatchPlan(
            instanceId = instanceId,
            verdict = EngineResultStatus.FAIL,
            action = request.action,
            message = "engine_broadcast_ipc_plan_invalid"
        )
    }

    override fun recordBroadcastDispatch(instanceId: String, result: VirtualBroadcastOperationResult): Boolean {
        val remote = remoteRecord(instanceId, result)
        return remote ?: if (authorityConnected()) false else fallback.recordBroadcastDispatch(instanceId, result)
    }

    override fun queryBroadcastRuntimeState(instanceId: String): VirtualBroadcastRuntimeState {
        remoteState(instanceId)?.let { return it }
        if (!authorityConnected()) return fallback.queryBroadcastRuntimeState(instanceId)
        return VirtualBroadcastRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = "engine_broadcast_ipc_runtime_state_invalid"
        )
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding =
        fallback.queryRuntimeBinding(instanceId)
}
