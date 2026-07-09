package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence

data class EngineOperationEvidenceRecordResult(
    val accepted: Boolean,
    val report: EngineEvidenceReport?
)

interface EngineOperationEvidenceSink {
    fun record(instanceId: String, evidence: EngineOperationEvidence): EngineOperationEvidenceRecordResult
}

class DefaultEngineOperationEvidenceSink(
    private val runtimeService: VirtualRuntimeService
) : EngineOperationEvidenceSink {
    override fun record(instanceId: String, evidence: EngineOperationEvidence): EngineOperationEvidenceRecordResult {
        val accepted = runtimeService.registerOperationEvidence(
            instanceId = instanceId,
            evidence = evidence
        )
        return EngineOperationEvidenceRecordResult(
            accepted = accepted,
            report = if (accepted) runtimeService.evidence(instanceId) else null
        )
    }
}

object EngineOperationEvidenceSinks {
    val global: EngineOperationEvidenceSink by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultEngineOperationEvidenceSink(
            DefaultVirtualSystemServer(EngineRuntimeRegistry.global).runtimeService
        )
    }
}
