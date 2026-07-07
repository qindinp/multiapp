package com.multiapp.app.container

import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.engine.EngineRuntimeRegistry
import com.multiapp.core.loader.VirtualStorageDiagnosticKind
import com.multiapp.core.loader.VirtualStoragePathDiagnostic
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus

/** Bridges app-container operation evidence into the engine-owned report. */
internal object ContainerEngineEvidenceBridge {
    fun recordProviderOperation(
        instanceId: String,
        operationName: String,
        fields: Map<String, Any?>,
        registry: EngineRuntimeRegistry = EngineRuntimeRegistry.global
    ): Boolean {
        if (instanceId.isBlank()) return false
        return registerOperationEvidence(
            registry = registry,
            instanceId = instanceId,
            evidence = EngineOperationEvidence(
                component = COMPONENT_PROVIDER,
                operation = operationName.toEngineOperation(),
                verdict = providerVerdict(fields),
                entries = sanitizeEntries(fields)
            )
        )
    }

    fun recordNativeStorageDiagnostic(
        diagnostic: VirtualStoragePathDiagnostic,
        fields: Map<String, Any?>,
        registry: EngineRuntimeRegistry = EngineRuntimeRegistry.global
    ): Boolean {
        if (diagnostic.kind != VirtualStorageDiagnosticKind.NATIVE_IO) return false
        if (diagnostic.instanceId.isBlank()) return false
        return registerOperationEvidence(
            registry = registry,
            instanceId = diagnostic.instanceId,
            evidence = EngineOperationEvidence(
                component = COMPONENT_NATIVE,
                operation = diagnostic.operation.toEngineOperation(defaultValue = "storage-bootstrap"),
                verdict = nativeVerdict(fields),
                entries = sanitizeEntries(fields)
            )
        )
    }

    fun recordNativeBootstrapUnsupported(
        instanceId: String,
        fields: Map<String, Any?>,
        registry: EngineRuntimeRegistry = EngineRuntimeRegistry.global
    ): Boolean {
        if (instanceId.isBlank()) return false
        return registerOperationEvidence(
            registry = registry,
            instanceId = instanceId,
            evidence = EngineOperationEvidence(
                component = COMPONENT_NATIVE,
                operation = "storage-bootstrap",
                verdict = nativeVerdict(fields),
                entries = sanitizeEntries(fields)
            )
        )
    }

    private fun registerOperationEvidence(
        registry: EngineRuntimeRegistry,
        instanceId: String,
        evidence: EngineOperationEvidence
    ): Boolean {
        val accepted = registry.registerOperationEvidence(
            instanceId = instanceId,
            evidence = evidence
        )
        if (accepted) {
            runCatching {
                ContainerEngineEvidenceReportExporter.write(registry.evidence(instanceId))
            }.onFailure { error ->
                Log.w(TAG, "Unable to export engine evidence report for instanceId=$instanceId", error)
            }
        }
        return accepted
    }

    private fun providerVerdict(fields: Map<String, Any?>): EngineResultStatus {
        when (fields["status"].orEmptyString()) {
            "PROVIDER_CREATED", "PROVIDER_CACHED", "BOUND", "CACHED" -> EngineResultStatus.PASS
            "RUNTIME_NOT_BOUND", "RUNTIME_INCOMPLETE" -> EngineResultStatus.PARTIAL
            "NOT_REQUESTED" -> EngineResultStatus.UNSUPPORTED
            "INVALID_PROXY_URI",
            "INSTANCE_NOT_FOUND",
            "PROVIDER_NOT_FOUND",
            "PROVIDER_CREATE_FAILED",
            "PROVIDER_ATTACH_FAILED",
            "FAILED" -> EngineResultStatus.FAIL
            else -> null
        }?.let { return it }

        val success = fields["evidenceSuccess"]?.toString()?.toBooleanStrictOrNull()
            ?: fields["cached"]?.toString()?.toBooleanStrictOrNull()
            ?: false
        return if (success) EngineResultStatus.PASS else EngineResultStatus.PARTIAL
    }

    private fun nativeVerdict(fields: Map<String, Any?>): EngineResultStatus {
        return when (fields["nativeIoRedirectVerdict"].orEmptyString()) {
            "PASS" -> EngineResultStatus.PASS
            "PARTIAL" -> EngineResultStatus.PARTIAL
            "FAIL" -> EngineResultStatus.FAIL
            "UNSUPPORTED" -> EngineResultStatus.UNSUPPORTED
            else -> when (fields["storageDiagnosticStatus"].orEmptyString()) {
                "REDIRECTED" -> EngineResultStatus.PASS
                "UNCHANGED" -> EngineResultStatus.FAIL
                "UNSUPPORTED" -> EngineResultStatus.UNSUPPORTED
                else -> EngineResultStatus.PARTIAL
            }
        }
    }

    private fun sanitizeEntries(fields: Map<String, Any?>): Map<String, String> {
        return EvidenceSanitizer.sanitizeEvidenceEntries(fields)
    }

    private fun String?.toEngineOperation(defaultValue: String = "unknown"): String {
        val normalized = this
            ?.substringBefore(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: defaultValue
        return normalized
    }

    private fun Any?.orEmptyString(): String = this?.toString().orEmpty()

    private const val COMPONENT_PROVIDER = "provider"
    private const val COMPONENT_NATIVE = "native"
    private const val TAG = "ContainerEngineEvidence"
}
