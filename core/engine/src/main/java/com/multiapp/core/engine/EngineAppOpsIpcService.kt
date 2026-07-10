package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualAppOpsService(
    private val fallback: VirtualAppOpsService,
    private val remoteQuery: (String, VirtualAppOpsQueryRequest) -> VirtualAppOpsQueryResult? =
        EngineRuntimeIpcClients::queryAppOp,
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualAppOpsService {
    override val subsystem: EngineSubsystem = EngineSubsystem.APP_OPS

    override fun queryMode(
        instanceId: String,
        request: VirtualAppOpsQueryRequest
    ): VirtualAppOpsQueryResult {
        remoteQuery(instanceId, request)?.let { return it }
        if (!authorityConnected()) return fallback.queryMode(instanceId, request)
        return VirtualAppOpsQueryResult(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            blockSystemCall = true,
            message = "engine_app_ops_ipc_query_invalid"
        )
    }

    override fun setMode(instanceId: String, opCode: Int, mode: Int): VirtualAppOpsQueryResult {
        if (!authorityConnected()) return fallback.setMode(instanceId, opCode, mode)
        return unsupportedMutation(instanceId, "set-mode")
    }

    override fun resetModes(instanceId: String, opCode: Int?): VirtualAppOpsQueryResult {
        if (!authorityConnected()) return fallback.resetModes(instanceId, opCode)
        return unsupportedMutation(instanceId, "reset-mode")
    }

    override fun queryRuntimeState(instanceId: String): VirtualAppOpsRuntimeState =
        fallback.queryRuntimeState(instanceId)

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding =
        fallback.queryRuntimeBinding(instanceId)

    private fun unsupportedMutation(instanceId: String, operation: String) = VirtualAppOpsQueryResult(
        instanceId = instanceId.ifBlank { "invalid" },
        verdict = EngineResultStatus.UNSUPPORTED,
        blockSystemCall = true,
        message = "engine_app_ops_ipc_mutation_unsupported:$operation"
    )
}
