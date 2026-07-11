package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

class IpcBackedVirtualPermissionService(
    private val fallback: VirtualPermissionService,
    private val remoteCheck: (String, String) -> VirtualPermissionCheckResult? =
        EngineRuntimeIpcClients::checkPermission,
    private val remoteState: (String) -> VirtualPermissionRuntimeState? =
        EngineRuntimeIpcClients::queryPermissionRuntimeState,
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualPermissionService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PERMISSION

    override fun checkPermission(
        instanceId: String,
        permissionName: String
    ): VirtualPermissionCheckResult {
        remoteCheck(instanceId, permissionName)?.let { return it }
        if (!authorityConnected()) return fallback.checkPermission(instanceId, permissionName)
        return invalidResult(instanceId, permissionName, "engine_permission_ipc_check_invalid")
    }

    override fun setPermissionGrant(
        instanceId: String,
        permissionName: String,
        granted: Boolean,
        source: EnginePermissionGrantSource
    ): VirtualPermissionCheckResult {
        if (!authorityConnected()) {
            return fallback.setPermissionGrant(instanceId, permissionName, granted, source)
        }
        return invalidResult(
            instanceId,
            permissionName,
            "engine_permission_remote_mutation_not_exposed"
        )
    }

    override fun clearPermissionGrant(instanceId: String, permissionName: String?): Int =
        if (authorityConnected()) 0 else fallback.clearPermissionGrant(instanceId, permissionName)

    override fun queryRuntimeState(instanceId: String): VirtualPermissionRuntimeState {
        remoteState(instanceId)?.let { return it }
        if (!authorityConnected()) return fallback.queryRuntimeState(instanceId)
        return VirtualPermissionRuntimeState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = "engine_permission_ipc_runtime_state_invalid"
        )
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding =
        fallback.queryRuntimeBinding(instanceId)

    private fun invalidResult(
        instanceId: String,
        permissionName: String,
        message: String
    ) = VirtualPermissionCheckResult(
        instanceId = instanceId.ifBlank { "invalid" },
        permissionName = permissionName.ifBlank { "invalid" },
        verdict = EngineResultStatus.FAIL,
        requested = false,
        granted = false,
        explicit = false,
        message = message
    )
}
