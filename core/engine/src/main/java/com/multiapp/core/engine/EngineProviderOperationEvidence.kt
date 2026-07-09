package com.multiapp.core.engine

import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.model.virtual.ResolvedComponent

data class EngineProviderOperationEvidenceBatch(
    val instanceId: String,
    val entries: List<EngineProviderOperationEvidenceEntry>
)

data class EngineProviderOperationEvidenceEntry(
    val component: String,
    val operationName: String,
    val fields: Map<String, Any?>
)

data class EngineProviderOperationCapability(
    val operationName: String,
    val operationStatusKey: String,
    val operationReasonKey: String? = null,
    val fallbackReason: String? = null
)

data class EngineUnsupportedProviderOperation(
    val operationName: String,
    val operationStatusKey: String,
    val operationReasonKey: String,
    val reason: String
)

object EngineProviderOperationEvidenceFacade {
    private const val STAGE = "PROVIDER_PROXY"

    private val capabilityOperations = listOf(
        EngineProviderOperationCapability(
            operationName = "openFileDescriptor",
            operationStatusKey = "providerOperationOpenFileDescriptorStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "openAssetFileDescriptor",
            operationStatusKey = "providerOperationOpenAssetFileDescriptorStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "openTypedAssetFileDescriptor",
            operationStatusKey = "providerOperationOpenTypedAssetFileDescriptorStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "notifyChange",
            operationStatusKey = "providerOperationNotifyChangeStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "registerContentObserver",
            operationStatusKey = "providerOperationRegisterContentObserverStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "unregisterContentObserver",
            operationStatusKey = "providerOperationUnregisterContentObserverStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "grantUriPermission",
            operationStatusKey = "providerOperationGrantUriPermissionStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "revokeUriPermission",
            operationStatusKey = "providerOperationRevokeUriPermissionStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "canonicalize",
            operationStatusKey = "providerOperationCanonicalizeStatus"
        ),
        EngineProviderOperationCapability(
            operationName = "uncanonicalize",
            operationStatusKey = "providerOperationUncanonicalizeStatus"
        )
    )

    fun capabilityEvidenceFromBootstrapResult(result: Any): EngineProviderOperationEvidenceBatch {
        val loaderResult = EngineHostedBootstrapResult.unwrap(result) ?: throw IllegalArgumentException(
            "Expected HostedBootstrapResult, got ${result::class.java.name}"
        )
        return capabilityEvidenceFromBootstrapResult(loaderResult)
    }

    fun capabilityEvidenceFromBootstrapResult(
        result: HostedBootstrapResult
    ): EngineProviderOperationEvidenceBatch {
        val provider = result.packageSnapshot?.providers?.firstOrNull()
        return EngineProviderOperationEvidenceBatch(
            instanceId = result.instanceId,
            entries = capabilityOperations.map { operation ->
                EngineProviderOperationEvidenceEntry(
                    component = componentForOperation(operation.operationName),
                    operationName = operation.operationName,
                    fields = fieldsForCapabilityOperation(
                        result = result,
                        provider = provider,
                        operation = operation
                    )
                )
            }
        )
    }

    fun fieldsForUnsupportedOperation(
        result: HostedBootstrapResult,
        provider: ResolvedComponent?,
        operation: EngineUnsupportedProviderOperation
    ): Map<String, Any?> = fieldsForCapabilityOperation(
        result = result,
        provider = provider,
        operation = EngineProviderOperationCapability(
            operationName = operation.operationName,
            operationStatusKey = operation.operationStatusKey,
            operationReasonKey = operation.operationReasonKey,
            fallbackReason = operation.reason
        )
    )

    fun fieldsForCapabilityOperation(
        result: HostedBootstrapResult,
        provider: ResolvedComponent?,
        operation: EngineProviderOperationCapability
    ): Map<String, Any?> {
        val bootstrapEvidence = result.stageResults
            .flatMap { it.evidence }
            .associate { it.key to it.value }
        val guestAuthority = provider?.authorities?.firstOrNull().orEmpty()
        val status = bootstrapEvidence[operation.operationStatusKey] ?: "UNKNOWN"
        val reason = operation.operationReasonKey
            ?.let { bootstrapEvidence[it] }
            ?: operation.fallbackReason
            ?: status
        return linkedMapOf(
            "status" to status,
            "stage" to STAGE,
            "operationName" to operation.operationName,
            "instanceId" to result.instanceId,
            "originPackageName" to result.originPackageName.orEmpty(),
            "virtualPackageName" to result.virtualPackageName.orEmpty(),
            "guestAuthority" to guestAuthority,
            "providerClassName" to provider?.name.orEmpty(),
            "providerRoutingEnabled" to bootstrapEvidence["providerRoutingEnabled"].orEmpty(),
            "providerRoutingScope" to bootstrapEvidence["providerRoutingScope"].orEmpty(),
            "providerRoutingPrimary" to bootstrapEvidence["providerRoutingPrimary"].orEmpty(),
            "providerRoutingFallback" to bootstrapEvidence["providerRoutingFallback"].orEmpty(),
            "evidenceOperation" to operation.operationName,
            "evidenceSuccess" to (status.startsWith("ROUTED_BY") || status == "NO_URI_REWRITE_REQUIRED"),
            "reason" to reason,
            operation.operationStatusKey to status,
            "hostFallback" to false,
            "capabilityVerdict" to status
        ).apply {
            operation.operationReasonKey?.let { key -> put(key, reason) }
        }
    }

    private fun componentForOperation(operationName: String): String {
        return operationComponents[operationName.substringBefore(':')] ?: "provider-method-unknown"
    }

    private val operationComponents = mapOf(
        "query" to "provider-query",
        "insert" to "provider-insert",
        "update" to "provider-update",
        "delete" to "provider-delete",
        "call" to "provider-call",
        "openFile" to "provider-open-file",
        "openAssetFile" to "provider-open-asset-file",
        "openTypedAssetFile" to "provider-open-typed-asset-file",
        "bulkInsert" to "provider-bulk-insert",
        "getType" to "provider-get-type",
        "openFileDescriptor" to "provider-open-file-descriptor",
        "openAssetFileDescriptor" to "provider-open-asset-file-descriptor",
        "openTypedAssetFileDescriptor" to "provider-open-typed-asset-file-descriptor",
        "notifyChange" to "provider-notify-change",
        "registerContentObserver" to "provider-register-content-observer",
        "unregisterContentObserver" to "provider-unregister-content-observer",
        "ContentObserver" to "provider-register-content-observer",
        "grantUriPermission" to "provider-grant-uri-permission",
        "revokeUriPermission" to "provider-revoke-uri-permission",
        "canonicalize" to "provider-canonicalize",
        "uncanonicalize" to "provider-uncanonicalize"
    )
}
