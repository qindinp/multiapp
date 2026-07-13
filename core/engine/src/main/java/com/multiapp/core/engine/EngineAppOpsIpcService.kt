package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualAppOpsService(
    @Suppress("UNUSED_PARAMETER") fallback: VirtualAppOpsService,
    private val remoteQuery: (String, VirtualAppOpsQueryRequest) -> VirtualAppOpsQueryResult? =
        EngineRuntimeIpcClients::queryAppOp,
    private val readOnlyRuntimeStateSnapshot: (String) -> VirtualAppOpsRuntimeState? = { null },
    private val readOnlyRuntimeBindingSnapshot: (String) -> VirtualSubsystemRuntimeBinding? = { null },
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualAppOpsService {
    override val subsystem: EngineSubsystem = EngineSubsystem.APP_OPS

    override fun queryMode(
        instanceId: String,
        request: VirtualAppOpsQueryRequest
    ): VirtualAppOpsQueryResult {
        runCatching { remoteQuery(instanceId, request) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId }
            ?.let { return it }
        return VirtualAppOpsQueryResult(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            blockSystemCall = true,
            message = authorityFailureMessage(
                invalid = "engine_app_ops_ipc_query_invalid",
                unavailable = "engine_app_ops_authority_unavailable:query"
            )
        )
    }

    override fun setMode(instanceId: String, opCode: Int, mode: Int): VirtualAppOpsQueryResult =
        unsupportedMutation(instanceId, "set-mode")

    override fun resetModes(instanceId: String, opCode: Int?): VirtualAppOpsQueryResult =
        unsupportedMutation(instanceId, "reset-mode")

    override fun queryRuntimeState(instanceId: String): VirtualAppOpsRuntimeState {
        if (!authorityConnected()) {
            val snapshot = runCatching { readOnlyRuntimeStateSnapshot(instanceId) }.getOrNull()
                ?.takeIf { state ->
                    state.instanceId == instanceId && state.records.all { it.instanceId == instanceId }
                }
            if (snapshot != null) {
                return snapshot.copy(
                    verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                    message = "engine_app_ops_read_only_runtime_snapshot:${snapshot.message}"
                )
            }
        }
        return VirtualAppOpsRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = if (authorityConnected()) {
                "engine_app_ops_remote_runtime_query_not_exposed"
            } else {
                "engine_app_ops_authority_unavailable:query-runtime-state"
            }
        )
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding {
        val snapshot = runCatching { readOnlyRuntimeBindingSnapshot(instanceId) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.subsystem == subsystem }
        if (snapshot != null) {
            return snapshot.copy(
                verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                message = "engine_app_ops_read_only_binding_snapshot:${snapshot.message}"
            )
        }
        return VirtualSubsystemRuntimeBinding(
            instanceId = instanceId.ifBlank { "invalid" },
            subsystem = subsystem,
            verdict = EngineResultStatus.FAIL,
            message = "engine_app_ops_runtime_snapshot_unavailable"
        )
    }

    private fun unsupportedMutation(instanceId: String, operation: String) = VirtualAppOpsQueryResult(
        instanceId = instanceId.ifBlank { "invalid" },
        verdict = EngineResultStatus.UNSUPPORTED,
        blockSystemCall = true,
        message = "engine_app_ops_ipc_mutation_unsupported:$operation"
    )

    private fun authorityFailureMessage(invalid: String, unavailable: String): String =
        if (authorityConnected()) invalid else unavailable

    private fun EngineResultStatus.asReadOnlySnapshotVerdict(): EngineResultStatus =
        if (this == EngineResultStatus.FAIL) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL
}
