package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualPermissionService(
    @Suppress("UNUSED_PARAMETER") fallback: VirtualPermissionService? = null,
    private val remoteCheck: (String, String) -> VirtualPermissionCheckResult? =
        EngineRuntimeIpcClients::checkPermission,
    private val remoteState: (String) -> VirtualPermissionRuntimeState? =
        EngineRuntimeIpcClients::queryPermissionRuntimeState,
    private val readOnlyRuntimeStateSnapshot: (String) -> VirtualPermissionRuntimeState? = { null },
    private val readOnlyRuntimeBindingSnapshot: (String) -> VirtualSubsystemRuntimeBinding? = { null },
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualPermissionService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PERMISSION

    override fun checkPermission(
        instanceId: String,
        permissionName: String
    ): VirtualPermissionCheckResult {
        runCatching { remoteCheck(instanceId, permissionName) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.permissionName == permissionName }
            ?.let { return it }
        return invalidResult(
            instanceId,
            permissionName,
            authorityFailureMessage(
                invalid = "engine_permission_ipc_check_invalid",
                unavailable = "engine_permission_authority_unavailable:check"
            )
        )
    }

    override fun setPermissionGrant(
        instanceId: String,
        permissionName: String,
        granted: Boolean,
        source: EnginePermissionGrantSource
    ): VirtualPermissionCheckResult = invalidResult(
        instanceId,
        permissionName,
        "engine_permission_remote_mutation_unsupported:set-grant",
        verdict = EngineResultStatus.UNSUPPORTED
    )

    override fun clearPermissionGrant(instanceId: String, permissionName: String?): Int = 0

    override fun queryRuntimeState(instanceId: String): VirtualPermissionRuntimeState {
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
                    message = "engine_permission_read_only_runtime_snapshot:${snapshot.message}"
                )
            }
        }
        return VirtualPermissionRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = authorityFailureMessage(
                invalid = "engine_permission_ipc_runtime_state_invalid",
                unavailable = "engine_permission_authority_unavailable:query-runtime-state"
            )
        )
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding {
        val snapshot = runCatching { readOnlyRuntimeBindingSnapshot(instanceId) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.subsystem == subsystem }
        if (snapshot != null) {
            return snapshot.copy(
                verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                message = "engine_permission_read_only_binding_snapshot:${snapshot.message}"
            )
        }
        return VirtualSubsystemRuntimeBinding(
            instanceId = instanceId.ifBlank { "invalid" },
            subsystem = subsystem,
            verdict = EngineResultStatus.FAIL,
            message = "engine_permission_runtime_snapshot_unavailable"
        )
    }

    private fun invalidResult(
        instanceId: String,
        permissionName: String,
        message: String,
        verdict: EngineResultStatus = EngineResultStatus.FAIL
    ) = VirtualPermissionCheckResult(
        instanceId = instanceId.ifBlank { "invalid" },
        permissionName = permissionName.ifBlank { "invalid" },
        verdict = verdict,
        requested = false,
        granted = false,
        explicit = false,
        message = message
    )

    private fun authorityFailureMessage(invalid: String, unavailable: String): String =
        if (authorityConnected()) invalid else unavailable

    private fun EngineResultStatus.asReadOnlySnapshotVerdict(): EngineResultStatus =
        if (this == EngineResultStatus.FAIL) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL
}
