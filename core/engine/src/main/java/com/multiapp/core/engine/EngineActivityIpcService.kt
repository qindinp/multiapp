package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord

enum class EngineActivityIpcOperation(val wireName: String) {
    MARK_STATE("mark-state"),
    FINISH("finish"),
    RECORD_FINISH_RESULT("record-finish-result"),
    SET_RESULT("set-result"),
    MARK_RESULT_DISPATCH("mark-result-dispatch"),
    CONSUME_RESULT("consume-result"),
    CONSUME_RESULT_RESUME_FALLBACK("consume-result-resume-fallback"),
    CONSUME_PENDING_NEW_INTENT("consume-pending-new-intent");

    companion object {
        fun fromWireName(value: String?): EngineActivityIpcOperation? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class EngineActivityIpcMutationRequest(
    val operation: EngineActivityIpcOperation,
    val token: String,
    val state: VirtualActivityState? = null,
    val resultCode: Int = 0,
    val dataIntent: VirtualIntentSnapshot? = null,
    val requestCode: Int = -1,
    val resultWho: String? = null,
    val frameworkDispatchAttempted: Boolean = false,
    val frameworkDispatchInvoked: Boolean = false
)

data class EngineActivityIpcConsumeResponse(
    val operation: EngineActivityIpcOperation,
    val found: Boolean,
    val activityResult: VirtualActivityResult? = null,
    val pendingNewIntent: VirtualActivityPendingNewIntent? = null
)

class IpcBackedVirtualActivityService(
    private val fallback: VirtualActivityService,
    private val remotePlan: (String, VirtualActivityDispatchPlanRequest) -> VirtualActivityDispatchPlan? =
        EngineRuntimeIpcClients::planActivity,
    private val remoteRecord: (String, VirtualActivityDispatchResult) -> Boolean? =
        EngineRuntimeIpcClients::recordActivityDispatch,
    private val remoteMutation: (String, EngineActivityIpcMutationRequest) -> VirtualActivityOperationResult? =
        EngineRuntimeIpcClients::mutateActivity,
    private val remoteConsume: (String, EngineActivityIpcOperation, String) -> EngineActivityIpcConsumeResponse? =
        EngineRuntimeIpcClients::consumeActivity,
    private val remoteTaskState: (String) -> VirtualActivityTaskState? =
        EngineRuntimeIpcClients::queryActivityTaskState,
    private val remoteTaskSync: (String, String, List<VirtualTaskRecord>) -> VirtualActivityOperationResult? =
        EngineRuntimeIpcClients::syncActivityTaskState,
    private val localTaskSnapshot: (String) -> List<VirtualTaskRecord> = { instanceId ->
        fallback.queryTaskState(instanceId).tasks
    },
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualActivityService {
    override val subsystem: EngineSubsystem = EngineSubsystem.ACTIVITY

    override fun planActivity(
        instanceId: String,
        request: VirtualActivityDispatchPlanRequest
    ): VirtualActivityDispatchPlan {
        remotePlan(instanceId, request)?.let { return it }
        if (!authorityConnected()) return fallback.planActivity(instanceId, request)
        return VirtualActivityDispatchPlan(
            instanceId = instanceId,
            verdict = EngineResultStatus.FAIL,
            action = request.action,
            message = "engine_activity_ipc_plan_invalid"
        )
    }

    override fun recordActivityDispatch(instanceId: String, result: VirtualActivityDispatchResult): Boolean {
        val remote = remoteRecord(instanceId, result)
        return remote ?: if (authorityConnected()) false else fallback.recordActivityDispatch(instanceId, result)
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding =
        fallback.queryRuntimeBinding(instanceId)

    override fun syncActivityTaskState(
        instanceId: String,
        reason: String,
        tasks: List<VirtualTaskRecord>?
    ): VirtualActivityOperationResult {
        val instanceTasks = (tasks ?: localTaskSnapshot(instanceId)).mapNotNull { task ->
            val activities = task.activities.filter { it.instanceId == instanceId }
            task.copy(activities = activities).takeIf { activities.isNotEmpty() }
        }
        remoteTaskSync(instanceId, reason, instanceTasks)?.let { return it }
        if (!authorityConnected()) {
            return fallback.syncActivityTaskState(instanceId, reason, instanceTasks)
        }
        return VirtualActivityOperationResult(
            instanceId = instanceId.ifBlank { "invalid" },
            operation = "sync-task-state",
            verdict = EngineResultStatus.FAIL,
            message = "engine_activity_ipc_task_sync_invalid"
        )
    }

    override fun queryTaskState(instanceId: String): VirtualActivityTaskState {
        remoteTaskState(instanceId)?.let { return it }
        if (!authorityConnected()) return fallback.queryTaskState(instanceId)
        return VirtualActivityTaskState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = "engine_activity_ipc_task_state_invalid"
        )
    }

    override fun markActivityState(
        instanceId: String,
        token: String,
        state: VirtualActivityState
    ): VirtualActivityOperationResult = mutateOrFallback(
        instanceId = instanceId,
        request = EngineActivityIpcMutationRequest(
            operation = EngineActivityIpcOperation.MARK_STATE,
            token = token,
            state = state
        ),
        fallbackCall = { fallback.markActivityState(instanceId, token, state) }
    )

    override fun finishActivity(instanceId: String, token: String): VirtualActivityOperationResult =
        mutateOrFallback(
            instanceId = instanceId,
            request = EngineActivityIpcMutationRequest(
                operation = EngineActivityIpcOperation.FINISH,
                token = token
            ),
            fallbackCall = { fallback.finishActivity(instanceId, token) }
        )

    override fun recordActivityResultForFinish(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?
    ): VirtualActivityOperationResult = mutateOrFallback(
        instanceId = instanceId,
        request = EngineActivityIpcMutationRequest(
            operation = EngineActivityIpcOperation.RECORD_FINISH_RESULT,
            token = token,
            resultCode = resultCode,
            dataIntent = dataIntent
        ),
        fallbackCall = {
            fallback.recordActivityResultForFinish(instanceId, token, resultCode, dataIntent)
        }
    )

    override fun setActivityResult(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?,
        requestCode: Int,
        resultWho: String?,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): VirtualActivityOperationResult = mutateOrFallback(
        instanceId = instanceId,
        request = EngineActivityIpcMutationRequest(
            operation = EngineActivityIpcOperation.SET_RESULT,
            token = token,
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = requestCode,
            resultWho = resultWho,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ),
        fallbackCall = {
            fallback.setActivityResult(
                instanceId,
                token,
                resultCode,
                dataIntent,
                requestCode,
                resultWho,
                frameworkDispatchAttempted,
                frameworkDispatchInvoked
            )
        }
    )

    override fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult? =
        consumeOrFallback(
            instanceId = instanceId,
            operation = EngineActivityIpcOperation.CONSUME_RESULT,
            token = token,
            remoteValue = { it.activityResult },
            fallbackCall = { fallback.consumeActivityResult(instanceId, token) }
        )

    override fun consumeActivityResultForResumeFallback(
        instanceId: String,
        token: String
    ): VirtualActivityResult? = consumeOrFallback(
        instanceId = instanceId,
        operation = EngineActivityIpcOperation.CONSUME_RESULT_RESUME_FALLBACK,
        token = token,
        remoteValue = { it.activityResult },
        fallbackCall = { fallback.consumeActivityResultForResumeFallback(instanceId, token) }
    )

    override fun markActivityResultDispatchState(
        instanceId: String,
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): VirtualActivityOperationResult = mutateOrFallback(
        instanceId = instanceId,
        request = EngineActivityIpcMutationRequest(
            operation = EngineActivityIpcOperation.MARK_RESULT_DISPATCH,
            token = token,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ),
        fallbackCall = {
            fallback.markActivityResultDispatchState(
                instanceId,
                token,
                frameworkDispatchAttempted,
                frameworkDispatchInvoked
            )
        }
    )

    override fun consumePendingNewIntent(
        instanceId: String,
        token: String
    ): VirtualActivityPendingNewIntent? = consumeOrFallback(
        instanceId = instanceId,
        operation = EngineActivityIpcOperation.CONSUME_PENDING_NEW_INTENT,
        token = token,
        remoteValue = { it.pendingNewIntent },
        fallbackCall = { fallback.consumePendingNewIntent(instanceId, token) }
    )

    private fun mutateOrFallback(
        instanceId: String,
        request: EngineActivityIpcMutationRequest,
        fallbackCall: () -> VirtualActivityOperationResult
    ): VirtualActivityOperationResult {
        remoteMutation(instanceId, request)?.let { return it }
        if (!authorityConnected()) return fallbackCall()
        return VirtualActivityOperationResult(
            instanceId = instanceId.ifBlank { "invalid" },
            operation = request.operation.wireName,
            verdict = EngineResultStatus.FAIL,
            token = request.token.takeIf { it.isNotBlank() },
            message = "engine_activity_ipc_mutation_invalid:${request.operation.wireName}"
        )
    }

    private fun <T> consumeOrFallback(
        instanceId: String,
        operation: EngineActivityIpcOperation,
        token: String,
        remoteValue: (EngineActivityIpcConsumeResponse) -> T?,
        fallbackCall: () -> T?
    ): T? {
        val remote = remoteConsume(instanceId, operation, token)
        if (remote != null) {
            if (remote.operation != operation || !remote.found) return null
            return remoteValue(remote)
        }
        return if (authorityConnected()) null else fallbackCall()
    }
}
