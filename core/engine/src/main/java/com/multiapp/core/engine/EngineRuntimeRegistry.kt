package com.multiapp.core.engine

import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import java.util.concurrent.ConcurrentHashMap

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

    private fun EngineOperationEvidence.sanitizedForReport(): EngineOperationEvidence {
        return copy(
            component = EvidenceSanitizer.sanitizeEvidenceLabel(component, defaultValue = "unknown"),
            operation = EvidenceSanitizer.sanitizeEvidenceLabel(operation, defaultValue = "unknown"),
            entries = EvidenceSanitizer.sanitizeEvidenceEntries(entries)
        )
    }

    companion object {
        val global: EngineRuntimeRegistry = EngineRuntimeRegistry()
    }
}
