package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.model.virtual.ResolvedComponent

/** Writes explicit hosted Provider operation evidence that is not a ContentProvider entry point. */
object ContainerProviderOperationEvidence {
    private const val TAG = "ProviderOperationEvidence"
    private const val STAGE = "PROVIDER_PROXY"

    private val capabilityOperations = listOf(
        ProviderOperationCapability(
            operationName = "openFileDescriptor",
            operationStatusKey = "providerOperationOpenFileDescriptorStatus"
        ),
        ProviderOperationCapability(
            operationName = "openAssetFileDescriptor",
            operationStatusKey = "providerOperationOpenAssetFileDescriptorStatus"
        ),
        ProviderOperationCapability(
            operationName = "openTypedAssetFileDescriptor",
            operationStatusKey = "providerOperationOpenTypedAssetFileDescriptorStatus"
        ),
        ProviderOperationCapability(
            operationName = "notifyChange",
            operationStatusKey = "providerOperationNotifyChangeStatus"
        ),
        ProviderOperationCapability(
            operationName = "registerContentObserver",
            operationStatusKey = "providerOperationRegisterContentObserverStatus"
        ),
        ProviderOperationCapability(
            operationName = "unregisterContentObserver",
            operationStatusKey = "providerOperationUnregisterContentObserverStatus"
        ),
        ProviderOperationCapability(
            operationName = "grantUriPermission",
            operationStatusKey = "providerOperationGrantUriPermissionStatus"
        ),
        ProviderOperationCapability(
            operationName = "revokeUriPermission",
            operationStatusKey = "providerOperationRevokeUriPermissionStatus"
        ),
        ProviderOperationCapability(
            operationName = "canonicalize",
            operationStatusKey = "providerOperationCanonicalizeStatus"
        ),
        ProviderOperationCapability(
            operationName = "uncanonicalize",
            operationStatusKey = "providerOperationUncanonicalizeStatus"
        )
    )

    fun writeUnsupportedOperations(context: Context, result: HostedBootstrapResult) {
        writeCapabilityOperations(context, result)
    }

    fun writeCapabilityOperations(context: Context, result: HostedBootstrapResult) {
        val snapshot = result.packageSnapshot
        val provider = snapshot?.providers?.firstOrNull()
        capabilityOperations.forEach { operation ->
            runCatching {
                ContainerRuntimeEvidenceWriter.write(
                    context = context,
                    instanceId = result.instanceId,
                    component = ProviderMethodEvidenceComponents.forOperation(operation.operationName),
                    fields = fieldsForCapabilityOperation(
                        result = result,
                        provider = provider,
                        operation = operation
                    )
                )
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Unable to write provider ${operation.operationName} capability evidence for instanceId=${result.instanceId}",
                    error
                )
            }
        }
    }

    internal fun fieldsForUnsupportedOperation(
        result: HostedBootstrapResult,
        provider: ResolvedComponent?,
        operation: UnsupportedProviderOperation
    ): Map<String, Any?> = fieldsForCapabilityOperation(
        result = result,
        provider = provider,
        operation = ProviderOperationCapability(
            operationName = operation.operationName,
            operationStatusKey = operation.operationStatusKey,
            operationReasonKey = operation.operationReasonKey,
            fallbackReason = operation.reason
        )
    )

    internal fun fieldsForCapabilityOperation(
        result: HostedBootstrapResult,
        provider: ResolvedComponent?,
        operation: ProviderOperationCapability
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
}

data class ProviderOperationCapability(
    val operationName: String,
    val operationStatusKey: String,
    val operationReasonKey: String? = null,
    val fallbackReason: String? = null
)

data class UnsupportedProviderOperation(
    val operationName: String,
    val operationStatusKey: String,
    val operationReasonKey: String,
    val reason: String
)
