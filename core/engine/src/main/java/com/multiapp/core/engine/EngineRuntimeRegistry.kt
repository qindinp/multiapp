package com.multiapp.core.engine

import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import java.util.concurrent.ConcurrentHashMap

class EngineRuntimeRegistry {
    private val runtimes = ConcurrentHashMap<String, VirtualInstanceRuntime>()
    private val reports = ConcurrentHashMap<String, EngineEvidenceReport>()

    @Synchronized
    fun register(runtime: VirtualInstanceRuntime): VirtualInstanceRuntime {
        runtimes[runtime.instanceId] = runtime
        reports[runtime.instanceId] = EngineEvidenceReport(
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
                "proxySlot" to runtime.proxySlot
            )
        )
        return runtime
    }

    fun get(instanceId: String): VirtualInstanceRuntime? = runtimes[instanceId]

    @Synchronized
    fun registerOperationEvidence(instanceId: String, evidence: EngineOperationEvidence): Boolean {
        if (!runtimes.containsKey(instanceId)) {
            return false
        }
        val report = reports[instanceId] ?: return false
        reports[instanceId] = report.withOperationEvidence(evidence.sanitizedForReport())
        return true
    }

    @Synchronized
    fun stop(instanceId: String): Boolean {
        reports.remove(instanceId)
        return runtimes.remove(instanceId) != null
    }

    fun evidence(instanceId: String): EngineEvidenceReport {
        return reports[instanceId] ?: EngineEvidenceReport(
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
