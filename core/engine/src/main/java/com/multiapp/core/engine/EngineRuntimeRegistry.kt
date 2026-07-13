package com.multiapp.core.engine

import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import java.util.concurrent.ConcurrentHashMap

data class EngineRuntimeForegroundAckResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val state: VirtualRuntimeState?,
    val reason: String
)

data class EngineRuntimeProcessBindingResult(
    val accepted: Boolean,
    val idempotent: Boolean,
    val state: VirtualRuntimeState?,
    val reason: String
)

data class EngineRuntimeRestartGenerationResult(
    val accepted: Boolean,
    val runtime: VirtualInstanceRuntime?,
    val reason: String
)

class EngineRuntimeRegistry(
    private var stateStore: EngineRuntimeStateStore = InMemoryEngineRuntimeStateStore()
) {
    private val runtimes = ConcurrentHashMap<String, VirtualInstanceRuntime>()
    private val reports = ConcurrentHashMap<String, EngineEvidenceReport>()

    @Synchronized
    fun register(runtime: VirtualInstanceRuntime): VirtualInstanceRuntime {
        val requestedRecord = EngineRuntimeStateRecord.from(runtime)
        val authoritativeRecord = stateStore.putIfNewer(requestedRecord)
        val authoritativeRuntime = if (authoritativeRecord == requestedRecord) {
            runtime
        } else {
            authoritativeRecord.toRuntime()
        }
        cacheRuntime(
            runtime = authoritativeRuntime,
            source = if (authoritativeRecord == requestedRecord) "memory" else "durable-newer"
        )
        return authoritativeRuntime
    }

    @Synchronized
    fun get(instanceId: String): VirtualInstanceRuntime? {
        val durableRuntime = stateStore.get(instanceId)?.toRuntime()
        if (durableRuntime == null) {
            runtimes.remove(instanceId)
            reports.remove(instanceId)
            return null
        }
        val cachedRuntime = runtimes[instanceId]
        if (cachedRuntime == durableRuntime) return cachedRuntime
        cacheRuntime(
            runtime = durableRuntime,
            source = if (cachedRuntime == null) "durable" else "durable-refresh"
        )
        return durableRuntime
    }

    @Synchronized
    fun list(): List<VirtualInstanceRuntime> = stateStore.list()
        .map { it.toRuntime() }
        .sortedBy { it.instanceId }
        .onEach { runtime -> cacheRuntime(runtime, source = "durable-list") }

    @Synchronized
    fun attachStateStore(store: EngineRuntimeStateStore): EngineRuntimeRegistry {
        stateStore = store
        val durableRecords = stateStore.list()
        val durableInstanceIds = durableRecords.mapTo(linkedSetOf()) { it.instanceId }
        runtimes.keys.filterNot { it in durableInstanceIds }.forEach { instanceId ->
            runtimes.remove(instanceId)
            reports.remove(instanceId)
        }
        durableRecords.forEach { record ->
            val runtime = record.toRuntime()
            cacheRuntime(runtime, source = "durable")
        }
        return this
    }

    @Synchronized
    fun runtimeState(instanceId: String): EngineRuntimeStateRecord? =
        get(instanceId)?.let(EngineRuntimeStateRecord::from)

    private fun reportFor(runtime: VirtualInstanceRuntime, source: String): EngineEvidenceReport {
        return EngineEvidenceReport(
            instanceId = runtime.instanceId,
            evidenceSessionId = runtime.evidenceSessionId,
            status = EngineResultStatus.PASS,
            profile = runtime.profile,
            entries = mapOf(
                "hostPackageName" to runtime.hostPackageName,
                "originPackageName" to runtime.originPackageName,
                "virtualPackageName" to runtime.virtualPackageName,
                "dataRoot" to runtime.dataRoot,
                "processSlot" to runtime.processSlot,
                "proxySlot" to runtime.proxySlot,
                "processSlotBindingMode" to "proxy-activity-android-process",
                "runtimeEpoch" to runtime.runtimeEpoch.toString(),
                "engineSessionId" to runtime.engineSessionId,
                "runtimeState" to runtime.state.name,
                "processId" to (runtime.processId?.toString() ?: ""),
                "processName" to runtime.processName.orEmpty(),
                "virtualSystemServerStatus" to "PASS",
                "runtimeStateSource" to source
            ),
            subsystemVerdicts = mapOf(
                EngineSubsystem.RUNTIME to EngineResultStatus.PASS,
                EngineSubsystem.EVIDENCE to EngineResultStatus.PASS
            )
        )
    }

    @Synchronized
    fun registerOperationEvidence(instanceId: String, evidence: EngineOperationEvidence): Boolean {
        if (get(instanceId) == null) {
            return false
        }
        val report = reports[instanceId] ?: return false
        reports[instanceId] = report.withOperationEvidence(evidence.sanitizedForReport())
        return true
    }

    @Synchronized
    fun stop(instanceId: String): Boolean {
        val runtime = get(instanceId) ?: return false
        return stopIfEpoch(instanceId, runtime.runtimeEpoch)
    }

    @Synchronized
    fun stopIfEpoch(instanceId: String, runtimeEpoch: Long): Boolean {
        val durableRuntime = stateStore.get(instanceId)?.toRuntime() ?: run {
            runtimes.remove(instanceId)
            reports.remove(instanceId)
            return false
        }
        if (durableRuntime.runtimeEpoch != runtimeEpoch) {
            cacheRuntime(durableRuntime, source = "durable-refresh")
            return false
        }
        val removed = stateStore.removeIfEpoch(instanceId, runtimeEpoch)
        if (removed) {
            reports.remove(instanceId)
            runtimes.remove(instanceId)
        } else {
            get(instanceId)
        }
        return removed
    }

    @Synchronized
    fun markDeadIfCurrent(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): Boolean {
        return updateIfCurrent(instanceId, runtimeEpoch, engineSessionId) { runtime ->
            if (runtime.state == VirtualRuntimeState.DEAD) return@updateIfCurrent null
            runtime.copy(
                processId = null,
                state = VirtualRuntimeState.DEAD
            )
        } != null
    }

    @Synchronized
    fun markDeadIfCurrent(identity: EngineProcessClientIdentity): Boolean {
        return updateIfCurrent(
            instanceId = identity.instanceId,
            runtimeEpoch = identity.runtimeEpoch,
            engineSessionId = identity.engineSessionId
        ) { runtime ->
            if (
                runtime.processSlot != identity.processSlot ||
                runtime.processName != identity.processSlot ||
                runtime.processId != identity.processId ||
                runtime.state == VirtualRuntimeState.DEAD
            ) {
                return@updateIfCurrent null
            }
            runtime.copy(
                processId = null,
                processName = null,
                state = VirtualRuntimeState.DEAD
            )
        } != null
    }

    @Synchronized
    fun bindProcessClientIfCurrent(
        identity: EngineProcessClientIdentity
    ): EngineRuntimeProcessBindingResult {
        val runtime = stateStore.get(identity.instanceId)?.toRuntime()
            ?: return EngineRuntimeProcessBindingResult(
                accepted = false,
                idempotent = false,
                state = null,
                reason = "runtime_not_found"
            )
        if (
            runtime.runtimeEpoch != identity.runtimeEpoch ||
            runtime.engineSessionId != identity.engineSessionId
        ) {
            cacheRuntime(runtime, source = "durable-refresh")
            return processBindingRejected(runtime, "runtime_generation_mismatch")
        }
        if (runtime.processSlot != identity.processSlot) {
            return processBindingRejected(runtime, "process_slot_mismatch")
        }
        if (runtime.state != VirtualRuntimeState.CREATED) {
            return processBindingRejected(runtime, "runtime_not_attachable:${runtime.state.name}")
        }
        if (runtime.processId != null && runtime.processId != identity.processId) {
            return processBindingRejected(runtime, "process_id_mismatch")
        }
        if (runtime.processName != null && runtime.processName != identity.processSlot) {
            return processBindingRejected(runtime, "process_name_mismatch")
        }
        if (runtime.processId == identity.processId && runtime.processName == identity.processSlot) {
            return EngineRuntimeProcessBindingResult(
                accepted = true,
                idempotent = true,
                state = runtime.state,
                reason = "process_already_bound"
            )
        }
        val updated = runtime.copy(
            processId = identity.processId,
            processName = identity.processSlot
        )
        if (
            !stateStore.compareAndSet(
                expected = EngineRuntimeStateRecord.from(runtime),
                updated = EngineRuntimeStateRecord.from(updated)
            )
        ) {
            get(identity.instanceId)
            return EngineRuntimeProcessBindingResult(
                accepted = false,
                idempotent = false,
                state = get(identity.instanceId)?.state,
                reason = "runtime_changed_during_process_attach"
            )
        }
        cacheRuntime(updated, source = "live-client-attach")
        return EngineRuntimeProcessBindingResult(
            accepted = true,
            idempotent = false,
            state = updated.state,
            reason = "process_bound_to_live_client"
        )
    }

    @Synchronized
    fun restartDeadProcessIfCurrent(
        expectedInstanceId: String,
        expectedRuntimeEpoch: Long,
        expectedEngineSessionId: String,
        expectedProcessSlot: String,
        successorEngineSessionId: String,
        successorEvidenceSessionId: String
    ): EngineRuntimeRestartGenerationResult {
        require(successorEngineSessionId.isNotBlank()) { "successorEngineSessionId must not be blank" }
        require(successorEvidenceSessionId.isNotBlank()) { "successorEvidenceSessionId must not be blank" }
        val currentRecord = stateStore.get(expectedInstanceId)
            ?: return restartGenerationRejected("runtime_not_found")
        val current = currentRecord.toRuntime()
        if (
            current.runtimeEpoch != expectedRuntimeEpoch ||
            current.engineSessionId != expectedEngineSessionId
        ) {
            cacheRuntime(current, source = "durable-refresh")
            return restartGenerationRejected("runtime_generation_mismatch", current)
        }
        if (current.processSlot != expectedProcessSlot) {
            return restartGenerationRejected("process_slot_mismatch", current)
        }
        if (current.state != VirtualRuntimeState.DEAD) {
            return restartGenerationRejected("runtime_not_restartable:${current.state.name}", current)
        }
        if (current.runtimeEpoch == Long.MAX_VALUE) {
            return restartGenerationRejected("runtime_epoch_exhausted", current)
        }
        if (successorEngineSessionId == current.engineSessionId) {
            return restartGenerationRejected("successor_engine_session_reused", current)
        }
        val successor = current.copy(
            evidenceSessionId = successorEvidenceSessionId,
            runtimeEpoch = current.runtimeEpoch + 1L,
            engineSessionId = successorEngineSessionId,
            processId = null,
            processName = null,
            state = VirtualRuntimeState.CREATED
        )
        if (!stateStore.compareAndSet(currentRecord, EngineRuntimeStateRecord.from(successor))) {
            get(expectedInstanceId)
            return restartGenerationRejected("runtime_changed_during_restart", get(expectedInstanceId))
        }
        cacheRuntime(successor, source = "process-restart-generation")
        return EngineRuntimeRestartGenerationResult(
            accepted = true,
            runtime = successor,
            reason = "restart_generation_allocated"
        )
    }

    @Synchronized
    fun invalidateEphemeralProcessStates(reason: String): Int {
        require(reason.isNotBlank()) { "reason must not be blank" }
        var invalidated = 0
        stateStore.list().forEach { record ->
            val runtime = record.toRuntime()
            if (runtime.state !in EPHEMERAL_PROCESS_STATES) return@forEach
            val dead = runtime.copy(processId = null, state = VirtualRuntimeState.DEAD)
            if (stateStore.compareAndSet(record, EngineRuntimeStateRecord.from(dead))) {
                cacheRuntime(dead, source = "server-restart-invalidation")
                registerOperationEvidence(
                    dead.instanceId,
                    EngineOperationEvidence(
                        component = "runtime",
                        operation = "server-restart-invalidation",
                        verdict = EngineResultStatus.FAIL,
                        entries = mapOf(
                            "previousState" to runtime.state.name,
                            "previousProcessId" to runtime.processId?.toString().orEmpty(),
                            "reason" to reason
                        )
                    )
                )
                invalidated += 1
            }
        }
        return invalidated
    }

    @Synchronized
    fun markPrewarmedIfCurrent(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processId: Int?,
        processName: String?
    ): VirtualInstanceRuntime? = updateIfCurrent(
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId
    ) { runtime ->
        if (runtime.state != VirtualRuntimeState.CREATED) return@updateIfCurrent null
        runtime.copy(
            processId = processId,
            processName = processName,
            state = VirtualRuntimeState.PREWARMED
        )
    }

    @Synchronized
    fun acknowledgeActivityResumed(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        callingPid: Int
    ): EngineRuntimeForegroundAckResult {
        val runtime = stateStore.get(instanceId)?.toRuntime() ?: return EngineRuntimeForegroundAckResult(
            accepted = false,
            idempotent = false,
            state = null,
            reason = "runtime_not_found"
        )
        if (runtime.runtimeEpoch != runtimeEpoch || runtime.engineSessionId != engineSessionId) {
            cacheRuntime(runtime, source = "durable-refresh")
            return foregroundAckRejected(runtime, "runtime_identity_mismatch")
        }
        if (runtime.processSlot != processSlot || runtime.processName != processSlot) {
            return foregroundAckRejected(runtime, "process_slot_mismatch")
        }
        if (callingPid <= 0 || runtime.processId == null || runtime.processId != callingPid) {
            return foregroundAckRejected(runtime, "process_id_mismatch")
        }
        if (runtime.state == VirtualRuntimeState.RUNNING) {
            return EngineRuntimeForegroundAckResult(
                accepted = true,
                idempotent = true,
                state = runtime.state,
                reason = "already_running"
            )
        }
        if (runtime.state != VirtualRuntimeState.PREWARMED) {
            return foregroundAckRejected(runtime, "invalid_state:${runtime.state.name}")
        }
        val expectedRecord = EngineRuntimeStateRecord.from(runtime)
        val updated = runtime.copy(state = VirtualRuntimeState.RUNNING)
        if (!stateStore.compareAndSet(expectedRecord, EngineRuntimeStateRecord.from(updated))) {
            get(instanceId)
            return EngineRuntimeForegroundAckResult(
                accepted = false,
                idempotent = false,
                state = get(instanceId)?.state,
                reason = "runtime_changed_during_ack"
            )
        }
        cacheRuntime(updated, source = "foreground-ack")
        return EngineRuntimeForegroundAckResult(
            accepted = true,
            idempotent = false,
            state = updated.state,
            reason = "guest_activity_resumed"
        )
    }

    fun evidence(instanceId: String): EngineEvidenceReport {
        val runtime = get(instanceId)
        if (runtime != null) return reports[instanceId] ?: reportFor(runtime, source = "durable")
        return EngineEvidenceReport(
            instanceId = instanceId,
            evidenceSessionId = "missing",
            status = EngineResultStatus.FAIL,
            profile = EngineProfile.BASELINE,
            entries = mapOf("reason" to "runtime_not_found")
        )
    }

    @Synchronized
    fun clear() {
        runtimes.clear()
        reports.clear()
        stateStore.clear()
    }

    private fun cacheRuntime(runtime: VirtualInstanceRuntime, source: String) {
        val previousRuntime = runtimes.put(runtime.instanceId, runtime)
        val previousReport = reports[runtime.instanceId]
        val baseReport = reportFor(runtime, source)
        val preserveOperations = previousRuntime?.engineSessionId == runtime.engineSessionId &&
            previousRuntime.evidenceSessionId == runtime.evidenceSessionId
        reports[runtime.instanceId] = if (preserveOperations && previousReport != null) {
            previousReport.flattenedOperationEvidence().fold(baseReport) { current, evidence ->
                current.withOperationEvidence(evidence)
            }
        } else {
            baseReport
        }
    }

    private fun updateIfCurrent(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        update: (VirtualInstanceRuntime) -> VirtualInstanceRuntime?
    ): VirtualInstanceRuntime? {
        val runtime = stateStore.get(instanceId)?.toRuntime() ?: run {
            runtimes.remove(instanceId)
            reports.remove(instanceId)
            return null
        }
        if (runtime.runtimeEpoch != runtimeEpoch || runtime.engineSessionId != engineSessionId) {
            cacheRuntime(runtime, source = "durable-refresh")
            return null
        }
        val updated = update(runtime) ?: return null
        require(updated.instanceId == instanceId) { "runtime update changed instanceId" }
        require(updated.runtimeEpoch == runtimeEpoch) { "runtime update changed runtimeEpoch" }
        require(updated.engineSessionId == engineSessionId) { "runtime update changed engineSessionId" }
        if (
            !stateStore.compareAndSet(
                expected = EngineRuntimeStateRecord.from(runtime),
                updated = EngineRuntimeStateRecord.from(updated)
            )
        ) {
            get(instanceId)
            return null
        }
        cacheRuntime(updated, source = "identity-cas")
        return updated
    }

    private fun foregroundAckRejected(
        runtime: VirtualInstanceRuntime,
        reason: String
    ): EngineRuntimeForegroundAckResult = EngineRuntimeForegroundAckResult(
        accepted = false,
        idempotent = false,
        state = runtime.state,
        reason = reason
    )

    private fun processBindingRejected(
        runtime: VirtualInstanceRuntime,
        reason: String
    ): EngineRuntimeProcessBindingResult = EngineRuntimeProcessBindingResult(
        accepted = false,
        idempotent = false,
        state = runtime.state,
        reason = reason
    )

    private fun restartGenerationRejected(
        reason: String,
        runtime: VirtualInstanceRuntime? = null
    ) = EngineRuntimeRestartGenerationResult(
        accepted = false,
        runtime = runtime,
        reason = reason
    )

    private fun EngineOperationEvidence.sanitizedForReport(): EngineOperationEvidence {
        return copy(
            component = EvidenceSanitizer.sanitizeEvidenceLabel(component, defaultValue = "unknown"),
            operation = EvidenceSanitizer.sanitizeEvidenceLabel(operation, defaultValue = "unknown"),
            entries = EvidenceSanitizer.sanitizeEvidenceEntries(entries)
        )
    }

    companion object {
        private val EPHEMERAL_PROCESS_STATES = setOf(
            VirtualRuntimeState.CREATED,
            VirtualRuntimeState.PREWARMED,
            VirtualRuntimeState.RUNNING
        )

        val global: EngineRuntimeRegistry = EngineRuntimeRegistry()
    }
}
