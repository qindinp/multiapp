package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

data class VirtualPermissionCheckResult(
    val instanceId: String,
    val permissionName: String,
    val verdict: EngineResultStatus,
    val requested: Boolean,
    val granted: Boolean,
    val explicit: Boolean,
    val source: EnginePermissionGrantSource? = null,
    val message: String
)

data class VirtualPermissionRuntimeState(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val records: List<EnginePermissionGrantRecord> = emptyList(),
    val message: String
)

object EnginePermissionFlags {
    const val FLAG_USER_SET = 1
    const val FLAG_USER_FIXED = 2
    const val FLAG_GRANTED_BY_DEFAULT = 4
    const val FLAG_ONE_TIME = 8
    const val FLAG_AUTO_RESET = 16
}

interface VirtualPermissionService : VirtualRuntimeBoundSubsystemService {
    fun checkPermission(instanceId: String, permissionName: String): VirtualPermissionCheckResult
    fun setPermissionGrant(
        instanceId: String,
        permissionName: String,
        granted: Boolean,
        source: EnginePermissionGrantSource
    ): VirtualPermissionCheckResult

    fun clearPermissionGrant(instanceId: String, permissionName: String? = null): Int
    fun queryRuntimeState(instanceId: String): VirtualPermissionRuntimeState
    fun setPermissionGrantWithFlags(
        instanceId: String,
        permissionName: String,
        granted: Boolean,
        source: EnginePermissionGrantSource,
        flags: Int,
        oneTime: Boolean = false
    ): VirtualPermissionCheckResult = setPermissionGrant(instanceId, permissionName, granted, source)
    fun consumeOneTimePermission(
        instanceId: String,
        permissionName: String
    ): VirtualPermissionCheckResult = checkPermission(instanceId, permissionName)
}

internal class RegistryBackedVirtualPermissionService(
    private val runtimeService: VirtualRuntimeService,
    private val stateStore: EnginePermissionGrantStore = InMemoryEnginePermissionGrantStore()
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.PERMISSION,
    supportedOperations = setOf(
        "check-permission",
        "persistent-instance-grant",
        "explicit-grant",
        "explicit-revoke",
        "permission-flags",
        "one-time-permission"
    ),
    unsupportedOperations = setOf(
        "runtime-permission-dialog",
        "auto-reset",
        "shared-uid-permission"
    )
), VirtualPermissionService {
    override fun checkPermission(
        instanceId: String,
        permissionName: String
    ): VirtualPermissionCheckResult {
        val runtime = runtimeService.get(instanceId)
            ?: return result(
                instanceId,
                permissionName,
                EngineResultStatus.FAIL,
                requested = false,
                granted = false,
                explicit = false,
                message = "runtime_not_found:$instanceId"
            )
        if (permissionName.isBlank()) {
            return result(
                runtime.instanceId,
                "invalid",
                EngineResultStatus.FAIL,
                requested = false,
                granted = false,
                explicit = false,
                message = "permission_name_blank"
            )
        }
        val requested = permissionName in runtime.packageSnapshot.permissions
        if (!requested) {
            return result(
                runtime.instanceId,
                permissionName,
                EngineResultStatus.FAIL,
                requested = false,
                granted = false,
                explicit = false,
                message = "permission_not_requested:$permissionName"
            )
        }
        val record = stateStore.get(runtime.instanceId, permissionName)
            ?: return result(
                runtime.instanceId,
                permissionName,
                EngineResultStatus.UNSUPPORTED,
                requested = true,
                granted = false,
                explicit = false,
                message = "permission_grant_state_unknown:$permissionName"
            )
        return result(
            runtime.instanceId,
            permissionName,
            verdict = if (record.granted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
            requested = true,
            granted = record.granted,
            explicit = true,
            source = record.source,
            message = if (record.granted) {
                "permission_granted:$permissionName"
            } else {
                "permission_denied:$permissionName"
            }
        )
    }

    override fun setPermissionGrant(
        instanceId: String,
        permissionName: String,
        granted: Boolean,
        source: EnginePermissionGrantSource
    ): VirtualPermissionCheckResult {
        val runtime = runtimeService.get(instanceId)
            ?: return result(
                instanceId,
                permissionName.ifBlank { "invalid" },
                EngineResultStatus.FAIL,
                requested = false,
                granted = false,
                explicit = false,
                message = "runtime_not_found:$instanceId"
            )
        if (permissionName.isBlank() || permissionName !in runtime.packageSnapshot.permissions) {
            return checkPermission(runtime.instanceId, permissionName)
        }
        val record = stateStore.set(
            EnginePermissionGrantRecord(
                instanceId = runtime.instanceId,
                permissionName = permissionName,
                granted = granted,
                source = source
            )
        )
        return result(
            runtime.instanceId,
            permissionName,
            verdict = if (record.granted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
            requested = true,
            granted = record.granted,
            explicit = true,
            source = record.source,
            message = if (record.granted) {
                "permission_grant_persisted:$permissionName"
            } else {
                "permission_revoke_persisted:$permissionName"
            }
        ).also {
            if (source != EnginePermissionGrantSource.SOURCE_APP_MIRROR) {
                recordEvidence(it, if (granted) "grant" else "revoke")
            }
        }
    }

    override fun setPermissionGrantWithFlags(
        instanceId: String,
        permissionName: String,
        granted: Boolean,
        source: EnginePermissionGrantSource,
        flags: Int,
        oneTime: Boolean
    ): VirtualPermissionCheckResult {
        val runtime = runtimeService.get(instanceId)
            ?: return result(
                instanceId,
                permissionName.ifBlank { "invalid" },
                EngineResultStatus.FAIL,
                requested = false,
                granted = false,
                explicit = false,
                message = "runtime_not_found:$instanceId"
            )
        if (permissionName.isBlank() || permissionName !in runtime.packageSnapshot.permissions) {
            return checkPermission(runtime.instanceId, permissionName)
        }
        val record = stateStore.set(
            EnginePermissionGrantRecord(
                instanceId = runtime.instanceId,
                permissionName = permissionName,
                granted = granted,
                source = source,
                flags = flags,
                oneTime = oneTime
            )
        )
        return result(
            runtime.instanceId,
            permissionName,
            verdict = if (record.granted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
            requested = true,
            granted = record.granted,
            explicit = true,
            source = record.source,
            message = if (record.granted) {
                "permission_grant_with_flags_persisted:$permissionName:flags=$flags:oneTime=$oneTime"
            } else {
                "permission_revoke_with_flags_persisted:$permissionName:flags=$flags"
            }
        ).also {
            if (source != EnginePermissionGrantSource.SOURCE_APP_MIRROR) {
                recordEvidence(it, if (granted) "grant-with-flags" else "revoke-with-flags")
            }
        }
    }

    override fun consumeOneTimePermission(
        instanceId: String,
        permissionName: String
    ): VirtualPermissionCheckResult {
        val runtime = runtimeService.get(instanceId)
            ?: return result(
                instanceId,
                permissionName,
                EngineResultStatus.FAIL,
                requested = false,
                granted = false,
                explicit = false,
                message = "runtime_not_found:$instanceId"
            )
        val record = stateStore.get(runtime.instanceId, permissionName)
            ?: return result(
                runtime.instanceId,
                permissionName,
                EngineResultStatus.UNSUPPORTED,
                requested = true,
                granted = false,
                explicit = false,
                message = "permission_grant_state_unknown:$permissionName"
            )
        if (!record.oneTime || !record.granted) {
            return result(
                runtime.instanceId,
                permissionName,
                verdict = if (record.granted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
                requested = true,
                granted = record.granted,
                explicit = true,
                source = record.source,
                message = "permission_not_one_time_or_not_granted:$permissionName"
            )
        }
        // Revoke the one-time permission after consumption
        stateStore.set(
            EnginePermissionGrantRecord(
                instanceId = runtime.instanceId,
                permissionName = permissionName,
                granted = false,
                source = record.source,
                flags = record.flags,
                oneTime = false,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        return result(
            runtime.instanceId,
            permissionName,
            verdict = EngineResultStatus.PASS,
            requested = true,
            granted = true,
            explicit = true,
            source = record.source,
            message = "one_time_permission_consumed:$permissionName"
        ).also { recordEvidence(it, "consume-one-time") }
    }

    override fun clearPermissionGrant(instanceId: String, permissionName: String?): Int {
        if (runtimeService.get(instanceId) == null) return 0
        val changed = stateStore.clear(instanceId, permissionName)
        runtimeService.registerOperationEvidence(
            instanceId,
            EngineOperationEvidence(
                component = "permission",
                operation = "clear",
                verdict = EngineResultStatus.PASS,
                entries = linkedMapOf(
                    "instanceId" to instanceId,
                    "permissionName" to permissionName.orEmpty(),
                    "changedCount" to changed.toString()
                )
            )
        )
        return changed
    }

    override fun queryRuntimeState(instanceId: String): VirtualPermissionRuntimeState {
        if (runtimeService.get(instanceId) == null) {
            return VirtualPermissionRuntimeState(
                instanceId = instanceId.ifBlank { "invalid" },
                verdict = EngineResultStatus.FAIL,
                message = "runtime_not_found:$instanceId"
            )
        }
        val records = stateStore.list(instanceId)
        return VirtualPermissionRuntimeState(
            instanceId = instanceId,
            verdict = EngineResultStatus.PARTIAL,
            records = records,
            message = if (records.isEmpty()) {
                "permission_explicit_grants_unavailable"
            } else {
                "permission_explicit_grants_available"
            }
        )
    }

    private fun result(
        instanceId: String,
        permissionName: String,
        verdict: EngineResultStatus,
        requested: Boolean,
        granted: Boolean,
        explicit: Boolean,
        source: EnginePermissionGrantSource? = null,
        message: String
    ) = VirtualPermissionCheckResult(
        instanceId = instanceId.ifBlank { "invalid" },
        permissionName = permissionName.ifBlank { "invalid" },
        verdict = verdict,
        requested = requested,
        granted = granted,
        explicit = explicit,
        source = source,
        message = message
    )

    private fun recordEvidence(result: VirtualPermissionCheckResult, operation: String) {
        runtimeService.registerOperationEvidence(
            result.instanceId,
            EngineOperationEvidence(
                component = "permission",
                operation = operation,
                verdict = result.verdict,
                entries = linkedMapOf(
                    "instanceId" to result.instanceId,
                    "permissionName" to result.permissionName,
                    "requested" to result.requested.toString(),
                    "granted" to result.granted.toString(),
                    "explicit" to result.explicit.toString(),
                    "source" to (result.source?.name ?: ""),
                    "message" to result.message
                )
            )
        )
    }
}
