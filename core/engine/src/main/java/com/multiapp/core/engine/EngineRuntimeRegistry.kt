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
        runtimes[runtime.instanceId] = runtime
        stateStore.put(EngineRuntimeStateRecord.from(runtime))
        reports[runtime.instanceId] = reportFor(runtime, source = "memory")
        return runtime
    }

    fun get(instanceId: String): VirtualInstanceRuntime? =
        runtimes[instanceId] ?: restore(instanceId)

    @Synchronized
    fun attachStateStore(store: EngineRuntimeStateStore): EngineRuntimeRegistry {
        stateStore = store
        stateStore.list().forEach { record ->
            val runtime = record.toRuntime()
            runtimes.putIfAbsent(runtime.instanceId, runtime)
            reports.putIfAbsent(runtime.instanceId, reportFor(runtime, source = "durable"))
        }
        return this
    }

    @Synchronized
    fun runtimeState(instanceId: String): EngineRuntimeStateRecord? =
        runtimes[instanceId]?.let(EngineRuntimeStateRecord::from) ?: stateStore.get(instanceId)

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
        reports.remove(instanceId)
        val removedRuntime = runtimes.remove(instanceId) != null
        val removedState = stateStore.remove(instanceId)
        return removedRuntime || removedState
    }

    fun evidence(instanceId: String): EngineEvidenceReport {
        val report = reports[instanceId]
        if (report != null) return report
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

    @Synchronized
    private fun restore(instanceId: String): VirtualInstanceRuntime? {
        val runtime = stateStore.get(instanceId)?.toRuntime() ?: return null
        runtimes[runtime.instanceId] = runtime
        reports.putIfAbsent(runtime.instanceId, reportFor(runtime, source = "durable"))
        return runtime
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
