package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

data class VirtualAppOpsQueryRequest(
    val methodName: String,
    val opCode: Int? = null,
    val uid: Int = -1,
    val packageName: String? = null,
    val hostUid: Int = -1,
    val callingPid: Int = -1
) {
    init {
        require(methodName.isNotBlank()) { "methodName must not be blank" }
        require(opCode == null || opCode >= 0) { "opCode must not be negative" }
        require(uid >= -1) { "uid must be -1 or non-negative" }
        require(packageName == null || packageName.isNotBlank()) { "packageName must not be blank" }
        require(hostUid >= -1) { "hostUid must be -1 or non-negative" }
        require(callingPid >= -1) { "callingPid must be -1 or non-negative" }
    }
}

data class VirtualAppOpsQueryResult(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val mode: Int? = null,
    val explicitMode: Boolean = false,
    val intercept: Boolean = false,
    val blockSystemCall: Boolean = false,
    val message: String
)

data class VirtualAppOpsRuntimeState(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val records: List<EngineAppOpModeRecord> = emptyList(),
    val message: String
)

interface VirtualAppOpsService : VirtualRuntimeBoundSubsystemService {
    fun queryMode(instanceId: String, request: VirtualAppOpsQueryRequest): VirtualAppOpsQueryResult
    fun setMode(instanceId: String, opCode: Int, mode: Int): VirtualAppOpsQueryResult
    fun resetModes(instanceId: String, opCode: Int? = null): VirtualAppOpsQueryResult
    fun queryRuntimeState(instanceId: String): VirtualAppOpsRuntimeState
}

internal class RegistryBackedVirtualAppOpsService(
    private val runtimeService: VirtualRuntimeService,
    private val stateStore: EngineAppOpsStateStore = InMemoryEngineAppOpsStateStore()
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.APP_OPS,
    supportedOperations = setOf("check-operation", "check-operation-raw", "persistent-instance-mode", "note-operation", "start-operation", "finish-operation"),
    unsupportedOperations = setOf("attribution-chain")
), VirtualAppOpsService {
    override fun queryMode(
        instanceId: String,
        request: VirtualAppOpsQueryRequest
    ): VirtualAppOpsQueryResult {
        val runtime = runtimeService.get(instanceId)
            ?: return result(instanceId, request, EngineResultStatus.FAIL, "runtime_not_found:$instanceId")
        if (request.packageName != runtime.originPackageName && request.packageName != runtime.virtualPackageName) {
            return result(
                instanceId,
                request,
                EngineResultStatus.FAIL,
                "app_ops_package_mismatch:${request.packageName}",
                blockSystemCall = true
            )
        }
        if (request.hostUid < 0 || request.uid != request.hostUid) {
            return result(
                instanceId,
                request,
                EngineResultStatus.FAIL,
                "app_ops_uid_mismatch:expected=${request.hostUid}:actual=${request.uid}",
                blockSystemCall = true
            )
        }
        runtime.processId?.let { expectedPid ->
            if (request.callingPid != expectedPid) {
                return result(
                    instanceId,
                    request,
                    EngineResultStatus.FAIL,
                    "app_ops_pid_mismatch:expected=$expectedPid:actual=${request.callingPid}",
                    blockSystemCall = true
                )
            }
        }
        if (request.methodName.isMutationMethod()) {
            return result(
                instanceId,
                request,
                EngineResultStatus.FAIL,
                "guest_app_ops_mutation_blocked:${request.methodName}",
                blockSystemCall = true
            )
        }
        if (!request.methodName.isSupportedCheckMethod()) {
            return result(
                instanceId,
                request,
                EngineResultStatus.UNSUPPORTED,
                "app_ops_method_passthrough_unsupported:${request.methodName}"
            )
        }
        val opCode = request.opCode
            ?: return result(instanceId, request, EngineResultStatus.FAIL, "app_ops_code_missing")
        val record = stateStore.get(instanceId, opCode)
        return if (record == null) {
            result(
                instanceId,
                request,
                EngineResultStatus.PARTIAL,
                "app_ops_no_virtual_mode_delegate_system"
            )
        } else {
            result(
                instanceId,
                request,
                EngineResultStatus.PASS,
                "app_ops_virtual_mode_resolved",
                mode = record.mode,
                explicitMode = true,
                intercept = true
            )
        }
    }

    override fun setMode(instanceId: String, opCode: Int, mode: Int): VirtualAppOpsQueryResult {
        if (runtimeService.get(instanceId) == null) {
            return directResult(instanceId, EngineResultStatus.FAIL, "runtime_not_found:$instanceId")
        }
        if (opCode < 0 || !EngineAppOpModes.isValid(mode)) {
            return directResult(instanceId, EngineResultStatus.FAIL, "invalid_app_ops_mode:$opCode:$mode")
        }
        stateStore.set(EngineAppOpModeRecord(instanceId, opCode, mode))
        return directResult(
            instanceId,
            EngineResultStatus.PASS,
            "app_ops_virtual_mode_persisted",
            mode = mode,
            explicitMode = true,
            intercept = true
        ).also { recordEvidence(it, "set-mode", opCode) }
    }

    override fun resetModes(instanceId: String, opCode: Int?): VirtualAppOpsQueryResult {
        if (runtimeService.get(instanceId) == null) {
            return directResult(instanceId, EngineResultStatus.FAIL, "runtime_not_found:$instanceId")
        }
        val changed = stateStore.reset(instanceId, opCode)
        return directResult(
            instanceId,
            EngineResultStatus.PASS,
            "app_ops_virtual_modes_reset:count=$changed"
        ).also { recordEvidence(it, "reset-mode", opCode) }
    }

    override fun queryRuntimeState(instanceId: String): VirtualAppOpsRuntimeState {
        if (runtimeService.get(instanceId) == null) {
            return VirtualAppOpsRuntimeState(
                instanceId = instanceId.ifBlank { "invalid" },
                verdict = EngineResultStatus.FAIL,
                message = "runtime_not_found:$instanceId"
            )
        }
        val records = stateStore.list(instanceId)
        return VirtualAppOpsRuntimeState(
            instanceId = instanceId,
            verdict = EngineResultStatus.PARTIAL,
            records = records,
            message = if (records.isEmpty()) {
                "app_ops_system_delegate_without_virtual_modes"
            } else {
                "app_ops_virtual_modes_available"
            }
        )
    }

    private fun result(
        instanceId: String,
        request: VirtualAppOpsQueryRequest,
        verdict: EngineResultStatus,
        message: String,
        mode: Int? = null,
        explicitMode: Boolean = false,
        intercept: Boolean = false,
        blockSystemCall: Boolean = false
    ): VirtualAppOpsQueryResult {
        val result = directResult(
            instanceId,
            verdict,
            message,
            mode,
            explicitMode,
            intercept,
            blockSystemCall
        )
        if (
            blockSystemCall ||
            verdict == EngineResultStatus.FAIL ||
            verdict == EngineResultStatus.UNSUPPORTED
        ) {
            recordEvidence(result, request.methodName, request.opCode)
        }
        return result
    }

    private fun directResult(
        instanceId: String,
        verdict: EngineResultStatus,
        message: String,
        mode: Int? = null,
        explicitMode: Boolean = false,
        intercept: Boolean = false,
        blockSystemCall: Boolean = false
    ) = VirtualAppOpsQueryResult(
        instanceId = instanceId.ifBlank { "invalid" },
        verdict = verdict,
        mode = mode,
        explicitMode = explicitMode,
        intercept = intercept,
        blockSystemCall = blockSystemCall,
        message = message
    )

    private fun recordEvidence(result: VirtualAppOpsQueryResult, operation: String, opCode: Int?) {
        runtimeService.registerOperationEvidence(
            result.instanceId,
            EngineOperationEvidence(
                component = "app-ops",
                operation = operation,
                verdict = result.verdict,
                entries = linkedMapOf(
                    "instanceId" to result.instanceId,
                    "opCode" to (opCode?.toString() ?: ""),
                    "mode" to (result.mode?.toString() ?: ""),
                    "explicitMode" to result.explicitMode.toString(),
                    "intercept" to result.intercept.toString(),
                    "blockSystemCall" to result.blockSystemCall.toString(),
                    "message" to result.message
                )
            )
        )
    }

    private fun String.isSupportedCheckMethod(): Boolean =
        this == "checkOperation" || this == "checkOperationRaw" || this == "checkAudioOperation" || this == "noteOperation" || this == "startOperation" || this == "finishOperation"

    private fun String.isMutationMethod(): Boolean =
        (startsWith("set") || startsWith("reset")) && this !in setOf("noteOperation", "startOperation", "finishOperation")
}
