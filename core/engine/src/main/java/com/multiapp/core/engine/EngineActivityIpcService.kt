package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
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

internal const val PROXY_ACTIVITY_SLOT_QUERY_OPERATION = "queryProxyActivitySlot"
internal const val PROXY_ACTIVITY_SLOT_RESERVE_OPERATION = "reserveProxyActivitySlot"
internal const val PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION = "compareAndSetProxyActivitySlot"

class IpcBackedProxyActivitySlotAssignmentStore(
    private val remoteFind: (ProxyActivitySlotKey) -> VirtualProxyActivitySlotOperationResult? =
        EngineRuntimeIpcClients::queryProxyActivitySlot,
    private val remoteReserve: (
        ProxyActivitySlotKey,
        List<String>
    ) -> VirtualProxyActivitySlotOperationResult? = EngineRuntimeIpcClients::reserveProxyActivitySlot,
    private val remoteCompareAndSet: (
        ProxyActivitySlotKey,
        String?,
        String?
    ) -> VirtualProxyActivitySlotOperationResult? = EngineRuntimeIpcClients::compareAndSetProxyActivitySlot
) : ProxyActivitySlotAssignmentStore {
    override fun find(key: ProxyActivitySlotKey): String? {
        if (!key.isValidProxyActivitySlotIpcKey()) return null
        return runCatching { remoteFind(key) }.getOrNull()
            ?.takeIf {
                it.isValidProxyActivitySlotIpcResult(
                    key,
                    PROXY_ACTIVITY_SLOT_QUERY_OPERATION
                ) && it.verdict != EngineResultStatus.FAIL
            }
            ?.proxyActivityClassName
            ?.takeIf(String::isValidProxyActivitySlotClassName)
    }

    override fun reserve(
        key: ProxyActivitySlotKey,
        candidateProxyActivityClassNames: List<String>
    ): String? {
        if (!key.isValidProxyActivitySlotIpcKey() ||
            !candidateProxyActivityClassNames.isValidProxyActivitySlotCandidates()
        ) {
            return null
        }
        return runCatching { remoteReserve(key, candidateProxyActivityClassNames) }.getOrNull()
            ?.takeIf {
                it.isValidProxyActivitySlotIpcResult(
                    key,
                    PROXY_ACTIVITY_SLOT_RESERVE_OPERATION
                ) && it.verdict != EngineResultStatus.FAIL && it.matched
            }
            ?.proxyActivityClassName
            ?.takeIf { it in candidateProxyActivityClassNames }
    }

    override fun compareAndSet(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): Boolean {
        if (!key.isValidProxyActivitySlotIpcKey() ||
            !expectedProxyActivityClassName.isValidNullableProxyActivitySlotClassName() ||
            !newProxyActivityClassName.isValidNullableProxyActivitySlotClassName()
        ) {
            return false
        }
        return runCatching {
            remoteCompareAndSet(
                key,
                expectedProxyActivityClassName,
                newProxyActivityClassName
            )
        }.getOrNull()?.let { result ->
            result.isValidProxyActivitySlotIpcResult(
                key,
                PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION
            ) && result.verdict != EngineResultStatus.FAIL && result.matched &&
                result.proxyActivityClassName == newProxyActivityClassName
        } ?: false
    }

    override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String): Nothing {
        throw UnsupportedOperationException(
            "IPC proxy Activity slot store does not permit client-authoritative save"
        )
    }

    override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? = null

    override fun removeInstance(instanceId: String): Int = 0

    override fun pruneStaleAssignments(
        validInstanceIds: Set<String>,
        liveProxyActivityClassNames: Set<String>,
        knownProxyActivityClassNames: Set<String>
    ): Int = 0
}

internal fun ProxyActivitySlotKey.isValidProxyActivitySlotIpcKey(): Boolean =
    instanceId.isValidProxyActivitySlotIdentityPart() &&
        launchMode.let { it == null || it.isValidProxyActivitySlotIdentityPart() } &&
        taskKey.isValidProxyActivitySlotTaskKey()

internal fun List<String>.isValidProxyActivitySlotCandidates(): Boolean =
    isNotEmpty() &&
        size <= EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_COUNT &&
        distinct().size == size &&
        all(String::isValidProxyActivitySlotClassName) &&
        sumOf(String::length) <= EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_TOTAL_LENGTH

private fun String.isValidProxyActivitySlotIdentityPart(): Boolean =
    isNotBlank() && length <= EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_IDENTITY_LENGTH

private fun String.isValidProxyActivitySlotTaskKey(): Boolean =
    isNotBlank() && length <= EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_TASK_KEY_LENGTH

internal fun String.isValidProxyActivitySlotClassName(): Boolean =
    isNotBlank() && length <= EngineRuntimeIpcContract.MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_LENGTH

internal fun String?.isValidNullableProxyActivitySlotClassName(): Boolean =
    this == null || isValidProxyActivitySlotClassName()

internal fun VirtualProxyActivitySlotOperationResult.isValidProxyActivitySlotIpcResult(
    expectedKey: ProxyActivitySlotKey,
    expectedOperation: String
): Boolean =
    instanceId == expectedKey.instanceId &&
        key == expectedKey &&
        operation == expectedOperation &&
        proxyActivityClassName.isValidNullableProxyActivitySlotClassName()

class IpcBackedVirtualActivityService(
    @Suppress("UNUSED_PARAMETER") fallback: VirtualActivityService,
    private val remoteProxySlotQuery: (
        String,
        ProxyActivitySlotKey
    ) -> VirtualProxyActivitySlotOperationResult? = { _, key ->
        EngineRuntimeIpcClients.queryProxyActivitySlot(key)
    },
    private val remoteProxySlotReserve: (
        String,
        ProxyActivitySlotKey,
        List<String>
    ) -> VirtualProxyActivitySlotOperationResult? = { _, key, candidates ->
        EngineRuntimeIpcClients.reserveProxyActivitySlot(key, candidates)
    },
    private val remoteProxySlotCompareAndSet: (
        String,
        ProxyActivitySlotKey,
        String?,
        String?
    ) -> VirtualProxyActivitySlotOperationResult? = { _, key, expected, new ->
        EngineRuntimeIpcClients.compareAndSetProxyActivitySlot(key, expected, new)
    },
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
    private val localTaskSnapshot: (String) -> List<VirtualTaskRecord> = { emptyList() },
    private val readOnlyTaskStateSnapshot: (String) -> VirtualActivityTaskState? = { null },
    private val readOnlyRuntimeBindingSnapshot: (String) -> VirtualSubsystemRuntimeBinding? = { null },
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualActivityService {
    override val subsystem: EngineSubsystem = EngineSubsystem.ACTIVITY

    override fun queryProxyActivitySlot(
        instanceId: String,
        key: ProxyActivitySlotKey
    ): VirtualProxyActivitySlotOperationResult = proxySlotAuthority(
        instanceId = instanceId,
        key = key,
        operation = PROXY_ACTIVITY_SLOT_QUERY_OPERATION
    ) { remoteProxySlotQuery(instanceId, key) }

    override fun reserveProxyActivitySlot(
        instanceId: String,
        key: ProxyActivitySlotKey,
        candidates: List<String>
    ): VirtualProxyActivitySlotOperationResult {
        if (!candidates.isValidProxyActivitySlotCandidates()) {
            return proxySlotFailure(instanceId, key, PROXY_ACTIVITY_SLOT_RESERVE_OPERATION)
        }
        return proxySlotAuthority(
            instanceId = instanceId,
            key = key,
            operation = PROXY_ACTIVITY_SLOT_RESERVE_OPERATION
        ) { remoteProxySlotReserve(instanceId, key, candidates) }
    }

    override fun compareAndSetProxyActivitySlot(
        instanceId: String,
        key: ProxyActivitySlotKey,
        expected: String?,
        new: String?
    ): VirtualProxyActivitySlotOperationResult {
        if (!expected.isValidNullableProxyActivitySlotClassName() ||
            !new.isValidNullableProxyActivitySlotClassName()
        ) {
            return proxySlotFailure(instanceId, key, PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION)
        }
        return proxySlotAuthority(
            instanceId = instanceId,
            key = key,
            operation = PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION
        ) { remoteProxySlotCompareAndSet(instanceId, key, expected, new) }
    }

    override fun planActivity(
        instanceId: String,
        request: VirtualActivityDispatchPlanRequest
    ): VirtualActivityDispatchPlan {
        runCatching { remotePlan(instanceId, request) }.getOrNull()
            ?.takeIf { response ->
                response.instanceId == instanceId &&
                    response.targets.all { it.instanceId == instanceId }
            }
            ?.let { return it }
        return VirtualActivityDispatchPlan(
            instanceId = instanceId,
            verdict = EngineResultStatus.FAIL,
            action = request.action,
            message = authorityFailureMessage(
                invalid = "engine_activity_ipc_plan_invalid",
                unavailable = "engine_activity_authority_unavailable"
            )
        )
    }

    override fun recordActivityDispatch(instanceId: String, result: VirtualActivityDispatchResult): Boolean {
        if (result.instanceId != instanceId) return false
        return runCatching { remoteRecord(instanceId, result) }.getOrNull() ?: false
    }

    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding {
        val snapshot = runCatching { readOnlyRuntimeBindingSnapshot(instanceId) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.subsystem == subsystem }
        if (snapshot != null) {
            return snapshot.copy(
                verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                message = "engine_activity_read_only_runtime_snapshot:${snapshot.message}"
            )
        }
        return VirtualSubsystemRuntimeBinding(
            instanceId = instanceId.ifBlank { "invalid" },
            subsystem = subsystem,
            verdict = EngineResultStatus.FAIL,
            message = "engine_activity_runtime_snapshot_unavailable"
        )
    }

    override fun syncActivityTaskState(
        instanceId: String,
        reason: String,
        tasks: List<VirtualTaskRecord>?
    ): VirtualActivityOperationResult {
        val snapshotTasks = tasks ?: runCatching { localTaskSnapshot(instanceId) }.getOrDefault(emptyList())
        val instanceTasks = snapshotTasks.mapNotNull { task ->
            val activities = task.activities.filter { it.instanceId == instanceId }
            task.copy(activities = activities).takeIf { activities.isNotEmpty() }
        }
        runCatching { remoteTaskSync(instanceId, reason, instanceTasks) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId && it.operation == "sync-task-state" }
            ?.let { return it }
        return VirtualActivityOperationResult(
            instanceId = instanceId.ifBlank { "invalid" },
            operation = "sync-task-state",
            verdict = EngineResultStatus.FAIL,
            message = authorityFailureMessage(
                invalid = "engine_activity_ipc_task_sync_invalid",
                unavailable = "engine_activity_authority_unavailable:sync-task-state"
            )
        )
    }

    override fun queryTaskState(instanceId: String): VirtualActivityTaskState {
        runCatching { remoteTaskState(instanceId) }.getOrNull()
            ?.takeIf { it.instanceId == instanceId }
            ?.let { return it }
        if (!authorityConnected()) {
            val snapshot = runCatching { readOnlyTaskStateSnapshot(instanceId) }.getOrNull()
                ?.takeIf { it.instanceId == instanceId }
            if (snapshot != null) {
                return snapshot.copy(
                    verdict = snapshot.verdict.asReadOnlySnapshotVerdict(),
                    message = "engine_activity_read_only_task_snapshot:${snapshot.message}"
                )
            }
        }
        return VirtualActivityTaskState(
            instanceId = instanceId.ifBlank { "invalid" },
            verdict = EngineResultStatus.FAIL,
            message = authorityFailureMessage(
                invalid = "engine_activity_ipc_task_state_invalid",
                unavailable = "engine_activity_authority_unavailable:query-task-state"
            )
        )
    }

    override fun markActivityState(
        instanceId: String,
        token: String,
        state: VirtualActivityState
    ): VirtualActivityOperationResult = mutateAuthority(
        instanceId = instanceId,
        request = EngineActivityIpcMutationRequest(
            operation = EngineActivityIpcOperation.MARK_STATE,
            token = token,
            state = state
        )
    )

    override fun finishActivity(instanceId: String, token: String): VirtualActivityOperationResult =
        mutateAuthority(
            instanceId = instanceId,
            request = EngineActivityIpcMutationRequest(
                operation = EngineActivityIpcOperation.FINISH,
                token = token
            )
        )

    override fun recordActivityResultForFinish(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?
    ): VirtualActivityOperationResult = mutateAuthority(
        instanceId = instanceId,
        request = EngineActivityIpcMutationRequest(
            operation = EngineActivityIpcOperation.RECORD_FINISH_RESULT,
            token = token,
            resultCode = resultCode,
            dataIntent = dataIntent
        )
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
    ): VirtualActivityOperationResult = mutateAuthority(
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
        )
    )

    override fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult? =
        consumeAuthority(
            instanceId = instanceId,
            operation = EngineActivityIpcOperation.CONSUME_RESULT,
            token = token,
            remoteValue = { it.activityResult }
        )

    override fun consumeActivityResultForResumeFallback(
        instanceId: String,
        token: String
    ): VirtualActivityResult? = consumeAuthority(
        instanceId = instanceId,
        operation = EngineActivityIpcOperation.CONSUME_RESULT_RESUME_FALLBACK,
        token = token,
        remoteValue = { it.activityResult }
    )

    override fun markActivityResultDispatchState(
        instanceId: String,
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): VirtualActivityOperationResult = mutateAuthority(
        instanceId = instanceId,
        request = EngineActivityIpcMutationRequest(
            operation = EngineActivityIpcOperation.MARK_RESULT_DISPATCH,
            token = token,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        )
    )

    override fun consumePendingNewIntent(
        instanceId: String,
        token: String
    ): VirtualActivityPendingNewIntent? = consumeAuthority(
        instanceId = instanceId,
        operation = EngineActivityIpcOperation.CONSUME_PENDING_NEW_INTENT,
        token = token,
        remoteValue = { it.pendingNewIntent }
    )

    private fun mutateAuthority(
        instanceId: String,
        request: EngineActivityIpcMutationRequest
    ): VirtualActivityOperationResult {
        runCatching { remoteMutation(instanceId, request) }.getOrNull()
            ?.takeIf {
                it.instanceId == instanceId &&
                    it.operation == request.operation.wireName &&
                    it.token == request.token
            }
            ?.let { return it }
        return VirtualActivityOperationResult(
            instanceId = instanceId.ifBlank { "invalid" },
            operation = request.operation.wireName,
            verdict = EngineResultStatus.FAIL,
            token = request.token.takeIf { it.isNotBlank() },
            message = authorityFailureMessage(
                invalid = "engine_activity_ipc_mutation_invalid:${request.operation.wireName}",
                unavailable = "engine_activity_authority_unavailable:${request.operation.wireName}"
            )
        )
    }

    private fun <T> consumeAuthority(
        instanceId: String,
        operation: EngineActivityIpcOperation,
        token: String,
        remoteValue: (EngineActivityIpcConsumeResponse) -> T?
    ): T? {
        val remote = runCatching { remoteConsume(instanceId, operation, token) }.getOrNull()
        if (remote != null) {
            if (remote.operation != operation || !remote.found) return null
            return remoteValue(remote)
        }
        return null
    }

    private fun authorityFailureMessage(invalid: String, unavailable: String): String =
        if (authorityConnected()) invalid else unavailable

    private fun proxySlotAuthority(
        instanceId: String,
        key: ProxyActivitySlotKey,
        operation: String,
        remote: () -> VirtualProxyActivitySlotOperationResult?
    ): VirtualProxyActivitySlotOperationResult {
        if (instanceId == key.instanceId && key.isValidProxyActivitySlotIpcKey()) {
            runCatching(remote).getOrNull()
                ?.takeIf { it.isValidProxyActivitySlotIpcResult(key, operation) }
                ?.let { return it }
        }
        return proxySlotFailure(instanceId, key, operation)
    }

    private fun proxySlotFailure(
        instanceId: String,
        key: ProxyActivitySlotKey,
        operation: String
    ) = VirtualProxyActivitySlotOperationResult(
        instanceId = instanceId.ifBlank { "invalid" },
        operation = operation,
        verdict = EngineResultStatus.FAIL,
        key = key,
        proxyActivityClassName = null,
        matched = false,
        removedCount = 0,
        message = authorityFailureMessage(
            invalid = "engine_activity_ipc_proxy_slot_invalid:$operation",
            unavailable = "engine_activity_authority_unavailable:$operation"
        )
    )

    private fun EngineResultStatus.asReadOnlySnapshotVerdict(): EngineResultStatus =
        if (this == EngineResultStatus.FAIL) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL
}
