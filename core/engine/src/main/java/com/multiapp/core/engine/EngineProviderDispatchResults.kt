package com.multiapp.core.engine

import android.content.ContentProvider
import android.net.Uri
import android.os.Bundle
import com.multiapp.core.loader.VirtualProviderDispatchResult
import com.multiapp.core.loader.VirtualProviderEvidence
import com.multiapp.core.loader.VirtualProviderPolicy
import com.multiapp.core.loader.VirtualProviderResolution

data class EngineProviderResolution(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val guestAuthority: String,
    val proxyAuthority: String,
    val providerClassName: String,
    val policy: EngineProviderPolicy
) {
    companion object {
        fun fromLoader(resolution: VirtualProviderResolution): EngineProviderResolution =
            EngineProviderResolution(
                instanceId = resolution.instanceId,
                originPackageName = resolution.originPackageName,
                virtualPackageName = resolution.virtualPackageName,
                guestAuthority = resolution.guestAuthority,
                proxyAuthority = resolution.proxyAuthority,
                providerClassName = resolution.providerClassName,
                policy = EngineProviderPolicy.fromLoader(resolution.policy)
            )
    }
}

data class EngineProviderPolicy(
    val exported: Boolean,
    val permission: String?,
    val grantUriPermissions: Boolean,
    val status: String,
    val reason: String,
    val routingScope: String,
    val processWideProviderHook: Boolean,
    val authorityRewriteEntry: String
) {
    companion object {
        fun fromLoader(policy: VirtualProviderPolicy): EngineProviderPolicy =
            EngineProviderPolicy(
                exported = policy.exported,
                permission = policy.permission,
                grantUriPermissions = policy.grantUriPermissions,
                status = policy.status,
                reason = policy.reason,
                routingScope = policy.routingScope,
                processWideProviderHook = policy.processWideProviderHook,
                authorityRewriteEntry = policy.authorityRewriteEntry
            )
    }
}

enum class EngineProviderOperation {
    ACQUIRE_PROVIDER,
    QUERY,
    GET_TYPE,
    INSERT,
    DELETE,
    UPDATE,
    CALL,
    OPEN_FILE,
    OPEN_ASSET_FILE,
    OPEN_TYPED_ASSET_FILE,
    BULK_INSERT,
    NOTIFY_CHANGE,
    CANONICALIZE,
    UNCANONICALIZE,
    UNKNOWN;

    companion object {
        fun fromOperationName(operationName: String): EngineProviderOperation =
            when (operationName.substringBefore(':')) {
                "query" -> QUERY
                "getType" -> GET_TYPE
                "insert" -> INSERT
                "delete" -> DELETE
                "update" -> UPDATE
                "call" -> CALL
                "openFile" -> OPEN_FILE
                "openAssetFile" -> OPEN_ASSET_FILE
                "openTypedAssetFile" -> OPEN_TYPED_ASSET_FILE
                "bulkInsert" -> BULK_INSERT
                "notifyChange" -> NOTIFY_CHANGE
                "canonicalize" -> CANONICALIZE
                "uncanonicalize" -> UNCANONICALIZE
                else -> UNKNOWN
            }

        fun fromLoader(operation: VirtualProviderEvidence.Operation): EngineProviderOperation =
            when (operation) {
                VirtualProviderEvidence.Operation.ACQUIRE_PROVIDER -> ACQUIRE_PROVIDER
                VirtualProviderEvidence.Operation.QUERY -> QUERY
                VirtualProviderEvidence.Operation.GET_TYPE -> GET_TYPE
                VirtualProviderEvidence.Operation.INSERT -> INSERT
                VirtualProviderEvidence.Operation.DELETE -> DELETE
                VirtualProviderEvidence.Operation.UPDATE -> UPDATE
                VirtualProviderEvidence.Operation.CALL -> CALL
                VirtualProviderEvidence.Operation.OPEN_FILE -> OPEN_FILE
                VirtualProviderEvidence.Operation.OPEN_ASSET_FILE -> OPEN_ASSET_FILE
                VirtualProviderEvidence.Operation.OPEN_TYPED_ASSET_FILE -> OPEN_TYPED_ASSET_FILE
                VirtualProviderEvidence.Operation.BULK_INSERT -> BULK_INSERT
                VirtualProviderEvidence.Operation.NOTIFY_CHANGE -> NOTIFY_CHANGE
                VirtualProviderEvidence.Operation.CANONICALIZE -> CANONICALIZE
                VirtualProviderEvidence.Operation.UNCANONICALIZE -> UNCANONICALIZE
                VirtualProviderEvidence.Operation.UNKNOWN -> UNKNOWN
            }
    }
}

data class EngineProviderEvidence(
    val instanceId: String?,
    val guestAuthority: String?,
    val proxyAuthority: String?,
    val providerClassName: String?,
    val operation: EngineProviderOperation,
    val success: Boolean,
    val reason: String? = null,
    val policy: EngineProviderPolicy? = null
) {
    companion object {
        fun fromLoader(evidence: VirtualProviderEvidence): EngineProviderEvidence =
            EngineProviderEvidence(
                instanceId = evidence.instanceId,
                guestAuthority = evidence.guestAuthority,
                proxyAuthority = evidence.proxyAuthority,
                providerClassName = evidence.providerClassName,
                operation = EngineProviderOperation.fromLoader(evidence.operation),
                success = evidence.success,
                reason = evidence.reason,
                policy = evidence.policy?.let(EngineProviderPolicy::fromLoader)
            )

        fun methodDispatch(
            result: EngineProviderDispatchResult?,
            operationName: String
        ): EngineProviderEvidence {
            val operation = EngineProviderOperation.fromOperationName(operationName)
            return when (result) {
                is EngineProviderDispatchResult.ProviderReady -> result.evidence.copy(
                    operation = operation,
                    success = true,
                    reason = if (result.cached) "PROVIDER_CACHED" else "PROVIDER_CREATED"
                )
                is EngineProviderDispatchResult.RuntimeNotBound -> result.evidence.copy(
                    operation = operation,
                    success = false,
                    reason = result.evidence.reason ?: "RUNTIME_NOT_BOUND"
                )
                is EngineProviderDispatchResult.RuntimeIncomplete -> result.evidence.copy(
                    operation = operation,
                    success = false,
                    reason = result.reason
                )
                is EngineProviderDispatchResult.ProviderCreateFailed -> result.evidence.copy(
                    operation = operation,
                    success = false,
                    reason = result.errorMessage ?: result.errorClassName
                )
                is EngineProviderDispatchResult.ProviderAttachFailed -> result.evidence.copy(
                    operation = operation,
                    success = false,
                    reason = result.errorMessage ?: result.errorClassName
                )
                is EngineProviderDispatchResult.ProviderNotFound -> result.evidence.copy(
                    operation = operation,
                    success = false,
                    reason = result.evidence.reason ?: "PROVIDER_NOT_FOUND"
                )
                is EngineProviderDispatchResult.InstanceNotFound -> EngineProviderEvidence(
                    instanceId = result.instanceId,
                    guestAuthority = null,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = operation,
                    success = false,
                    reason = "INSTANCE_NOT_FOUND"
                )
                is EngineProviderDispatchResult.InvalidProxyUri -> EngineProviderEvidence(
                    instanceId = null,
                    guestAuthority = null,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = operation,
                    success = false,
                    reason = result.reason
                )
                null -> EngineProviderEvidence(
                    instanceId = null,
                    guestAuthority = null,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = operation,
                    success = false,
                    reason = "missing uri"
                )
            }
        }
    }
}

sealed class EngineProviderDispatchResult {
    data class ProviderReady(
        val resolution: EngineProviderResolution,
        val provider: ContentProvider,
        val cached: Boolean,
        val evidence: EngineProviderEvidence
    ) : EngineProviderDispatchResult()

    data class RuntimeNotBound(
        val resolution: EngineProviderResolution,
        val evidence: EngineProviderEvidence
    ) : EngineProviderDispatchResult()

    data class RuntimeIncomplete(
        val resolution: EngineProviderResolution,
        val reason: String,
        val evidence: EngineProviderEvidence
    ) : EngineProviderDispatchResult()

    data class ProviderCreateFailed(
        val resolution: EngineProviderResolution,
        val errorClassName: String,
        val errorMessage: String?,
        val evidence: EngineProviderEvidence
    ) : EngineProviderDispatchResult()

    data class ProviderAttachFailed(
        val resolution: EngineProviderResolution,
        val errorClassName: String,
        val errorMessage: String?,
        val evidence: EngineProviderEvidence
    ) : EngineProviderDispatchResult()

    data class InvalidProxyUri(val reason: String) : EngineProviderDispatchResult()

    data class InstanceNotFound(val instanceId: String) : EngineProviderDispatchResult()

    data class ProviderNotFound(
        val instanceId: String,
        val guestAuthority: String,
        val evidence: EngineProviderEvidence
    ) : EngineProviderDispatchResult()

    companion object {
        fun fromLoader(result: VirtualProviderDispatchResult): EngineProviderDispatchResult =
            when (result) {
                is VirtualProviderDispatchResult.ProviderReady -> ProviderReady(
                    resolution = EngineProviderResolution.fromLoader(result.resolution),
                    provider = result.provider,
                    cached = result.cached,
                    evidence = EngineProviderEvidence.fromLoader(result.evidence)
                )
                is VirtualProviderDispatchResult.RuntimeNotBound -> RuntimeNotBound(
                    resolution = EngineProviderResolution.fromLoader(result.resolution),
                    evidence = EngineProviderEvidence.fromLoader(result.evidence)
                )
                is VirtualProviderDispatchResult.RuntimeIncomplete -> RuntimeIncomplete(
                    resolution = EngineProviderResolution.fromLoader(result.resolution),
                    reason = result.reason,
                    evidence = EngineProviderEvidence.fromLoader(result.evidence)
                )
                is VirtualProviderDispatchResult.ProviderCreateFailed -> ProviderCreateFailed(
                    resolution = EngineProviderResolution.fromLoader(result.resolution),
                    errorClassName = result.error.javaClass.name,
                    errorMessage = result.error.message,
                    evidence = EngineProviderEvidence.fromLoader(result.evidence)
                )
                is VirtualProviderDispatchResult.ProviderAttachFailed -> ProviderAttachFailed(
                    resolution = EngineProviderResolution.fromLoader(result.resolution),
                    errorClassName = result.error.javaClass.name,
                    errorMessage = result.error.message,
                    evidence = EngineProviderEvidence.fromLoader(result.evidence)
                )
                is VirtualProviderDispatchResult.InvalidProxyUri -> InvalidProxyUri(result.reason)
                is VirtualProviderDispatchResult.InstanceNotFound -> InstanceNotFound(result.instanceId)
                is VirtualProviderDispatchResult.ProviderNotFound -> ProviderNotFound(
                    instanceId = result.instanceId,
                    guestAuthority = result.guestAuthority,
                    evidence = EngineProviderEvidence.fromLoader(result.evidence)
                )
            }
    }
}

fun EngineProviderDispatchResult?.toEngineBundle(): Bundle = Bundle().apply {
    when (val result = this@toEngineBundle) {
        null -> {
            putString("status", "INVALID_PROXY_URI")
            putString("reason", "missing uri")
        }
        is EngineProviderDispatchResult.ProviderReady -> {
            putString("status", if (result.cached) "PROVIDER_CACHED" else "PROVIDER_CREATED")
            putString("instanceId", result.resolution.instanceId)
            putString("guestAuthority", result.resolution.guestAuthority)
            putString("providerClassName", result.resolution.providerClassName)
            putEngineProviderEvidence(result.evidence)
        }
        is EngineProviderDispatchResult.RuntimeNotBound -> {
            putString("status", "RUNTIME_NOT_BOUND")
            putString("instanceId", result.resolution.instanceId)
            putString("guestAuthority", result.resolution.guestAuthority)
            putString("providerClassName", result.resolution.providerClassName)
            putEngineProviderEvidence(result.evidence)
        }
        is EngineProviderDispatchResult.RuntimeIncomplete -> {
            putString("status", "RUNTIME_INCOMPLETE")
            putString("reason", result.reason)
            putString("instanceId", result.resolution.instanceId)
            putString("guestAuthority", result.resolution.guestAuthority)
            putString("providerClassName", result.resolution.providerClassName)
            putEngineProviderEvidence(result.evidence)
        }
        is EngineProviderDispatchResult.ProviderCreateFailed -> {
            putString("status", "PROVIDER_CREATE_FAILED")
            putString("reason", result.errorMessage)
            putString("instanceId", result.resolution.instanceId)
            putString("guestAuthority", result.resolution.guestAuthority)
            putString("providerClassName", result.resolution.providerClassName)
            putEngineProviderEvidence(result.evidence)
        }
        is EngineProviderDispatchResult.ProviderAttachFailed -> {
            putString("status", "PROVIDER_ATTACH_FAILED")
            putString("reason", result.errorMessage)
            putString("instanceId", result.resolution.instanceId)
            putString("guestAuthority", result.resolution.guestAuthority)
            putString("providerClassName", result.resolution.providerClassName)
            putEngineProviderEvidence(result.evidence)
        }
        is EngineProviderDispatchResult.InvalidProxyUri -> {
            putString("status", "INVALID_PROXY_URI")
            putString("reason", result.reason)
        }
        is EngineProviderDispatchResult.InstanceNotFound -> {
            putString("status", "INSTANCE_NOT_FOUND")
            putString("instanceId", result.instanceId)
        }
        is EngineProviderDispatchResult.ProviderNotFound -> {
            putString("status", "PROVIDER_NOT_FOUND")
            putString("instanceId", result.instanceId)
            putString("guestAuthority", result.guestAuthority)
            putEngineProviderEvidence(result.evidence)
        }
    }
}

fun EngineProviderDispatchResult?.toEngineEvidenceFields(
    operationName: String,
    uri: Uri,
    rewrittenGuestUri: String,
    processSlot: String?
): Map<String, Any?> {
    val methodEvidence = EngineProviderEvidence.methodDispatch(this, operationName)
    return linkedMapOf(
        "status" to statusName(),
        "stage" to "PROVIDER_PROXY",
        "operationName" to operationName,
        "uri" to uri.toString(),
        "proxyUri" to uri.toString(),
        "rewrittenGuestUri" to rewrittenGuestUri,
        "instanceId" to instanceIdForEvidence().orEmpty(),
        "originPackageName" to originPackageNameForEvidence().orEmpty(),
        "virtualPackageName" to virtualPackageNameForEvidence().orEmpty(),
        "guestAuthority" to guestAuthorityForEvidence().orEmpty(),
        "processSlot" to processSlot.orEmpty(),
        "providerClassName" to providerClassNameForEvidence().orEmpty(),
        "proxyAuthority" to methodEvidence.proxyAuthority.orEmpty(),
        "evidenceOperation" to methodEvidence.operation.name,
        "evidenceSuccess" to methodEvidence.success,
        "reason" to methodEvidence.reason.orEmpty(),
        "providerExported" to (methodEvidence.policy?.exported ?: false),
        "providerPermission" to methodEvidence.policy?.permission.orEmpty(),
        "providerGrantUriPermissions" to (methodEvidence.policy?.grantUriPermissions ?: false),
        "providerPolicyStatus" to methodEvidence.policy?.status.orEmpty(),
        "providerPolicyReason" to methodEvidence.policy?.reason.orEmpty(),
        "providerRoutingScope" to methodEvidence.policy?.routingScope.orEmpty(),
        "processWideProviderHook" to (methodEvidence.policy?.processWideProviderHook ?: false),
        "authorityRewriteEntry" to methodEvidence.policy?.authorityRewriteEntry.orEmpty(),
        "dispatcherStatus" to statusName(),
        "cached" to ((this as? EngineProviderDispatchResult.ProviderReady)?.cached ?: false),
        "detail" to statusForLog()
    )
}

fun EngineProviderDispatchResult?.instanceIdForEvidence(): String? = when (this) {
    is EngineProviderDispatchResult.ProviderReady -> resolution.instanceId
    is EngineProviderDispatchResult.RuntimeNotBound -> resolution.instanceId
    is EngineProviderDispatchResult.RuntimeIncomplete -> resolution.instanceId
    is EngineProviderDispatchResult.ProviderCreateFailed -> resolution.instanceId
    is EngineProviderDispatchResult.ProviderAttachFailed -> resolution.instanceId
    is EngineProviderDispatchResult.InstanceNotFound -> instanceId
    is EngineProviderDispatchResult.ProviderNotFound -> instanceId
    else -> null
}

fun EngineProviderDispatchResult?.guestAuthorityForEvidence(): String? = when (this) {
    is EngineProviderDispatchResult.ProviderReady -> resolution.guestAuthority
    is EngineProviderDispatchResult.RuntimeNotBound -> resolution.guestAuthority
    is EngineProviderDispatchResult.RuntimeIncomplete -> resolution.guestAuthority
    is EngineProviderDispatchResult.ProviderCreateFailed -> resolution.guestAuthority
    is EngineProviderDispatchResult.ProviderAttachFailed -> resolution.guestAuthority
    is EngineProviderDispatchResult.ProviderNotFound -> guestAuthority
    else -> null
}

fun EngineProviderDispatchResult?.originPackageNameForEvidence(): String? = when (this) {
    is EngineProviderDispatchResult.ProviderReady -> resolution.originPackageName
    is EngineProviderDispatchResult.RuntimeNotBound -> resolution.originPackageName
    is EngineProviderDispatchResult.RuntimeIncomplete -> resolution.originPackageName
    is EngineProviderDispatchResult.ProviderCreateFailed -> resolution.originPackageName
    is EngineProviderDispatchResult.ProviderAttachFailed -> resolution.originPackageName
    else -> null
}

fun EngineProviderDispatchResult?.virtualPackageNameForEvidence(): String? = when (this) {
    is EngineProviderDispatchResult.ProviderReady -> resolution.virtualPackageName
    is EngineProviderDispatchResult.RuntimeNotBound -> resolution.virtualPackageName
    is EngineProviderDispatchResult.RuntimeIncomplete -> resolution.virtualPackageName
    is EngineProviderDispatchResult.ProviderCreateFailed -> resolution.virtualPackageName
    is EngineProviderDispatchResult.ProviderAttachFailed -> resolution.virtualPackageName
    else -> null
}

fun EngineProviderDispatchResult?.providerClassNameForEvidence(): String? = when (this) {
    is EngineProviderDispatchResult.ProviderReady -> resolution.providerClassName
    is EngineProviderDispatchResult.RuntimeNotBound -> resolution.providerClassName
    is EngineProviderDispatchResult.RuntimeIncomplete -> resolution.providerClassName
    is EngineProviderDispatchResult.ProviderCreateFailed -> resolution.providerClassName
    is EngineProviderDispatchResult.ProviderAttachFailed -> resolution.providerClassName
    else -> null
}

fun EngineProviderDispatchResult?.statusName(): String = when (this) {
    null -> "INVALID_PROXY_URI"
    is EngineProviderDispatchResult.ProviderReady -> if (cached) "PROVIDER_CACHED" else "PROVIDER_CREATED"
    is EngineProviderDispatchResult.RuntimeNotBound -> "RUNTIME_NOT_BOUND"
    is EngineProviderDispatchResult.RuntimeIncomplete -> "RUNTIME_INCOMPLETE"
    is EngineProviderDispatchResult.ProviderCreateFailed -> "PROVIDER_CREATE_FAILED"
    is EngineProviderDispatchResult.ProviderAttachFailed -> "PROVIDER_ATTACH_FAILED"
    is EngineProviderDispatchResult.InvalidProxyUri -> "INVALID_PROXY_URI"
    is EngineProviderDispatchResult.InstanceNotFound -> "INSTANCE_NOT_FOUND"
    is EngineProviderDispatchResult.ProviderNotFound -> "PROVIDER_NOT_FOUND"
}

fun EngineProviderDispatchResult?.statusForLog(): String = when (this) {
    null -> "INVALID_PROXY_URI:missing uri"
    is EngineProviderDispatchResult.ProviderReady ->
        "PROVIDER_READY:${if (cached) "cached" else "created"}:${resolution.providerClassName}"
    is EngineProviderDispatchResult.RuntimeNotBound -> "RUNTIME_NOT_BOUND:${resolution.providerClassName}"
    is EngineProviderDispatchResult.RuntimeIncomplete -> "RUNTIME_INCOMPLETE:$reason:${resolution.providerClassName}"
    is EngineProviderDispatchResult.ProviderCreateFailed ->
        "PROVIDER_CREATE_FAILED:$errorMessage:${resolution.providerClassName}"
    is EngineProviderDispatchResult.ProviderAttachFailed ->
        "PROVIDER_ATTACH_FAILED:$errorMessage:${resolution.providerClassName}"
    is EngineProviderDispatchResult.InvalidProxyUri -> "INVALID_PROXY_URI:$reason"
    is EngineProviderDispatchResult.InstanceNotFound -> "INSTANCE_NOT_FOUND"
    is EngineProviderDispatchResult.ProviderNotFound -> "PROVIDER_NOT_FOUND"
}

private fun Bundle.putEngineProviderEvidence(evidence: EngineProviderEvidence) {
    putString("evidenceOperation", evidence.operation.name)
    putBoolean("evidenceSuccess", evidence.success)
    putString("evidenceReason", evidence.reason)
    putString("proxyAuthority", evidence.proxyAuthority)
    evidence.policy?.let { policy ->
        putBoolean("providerExported", policy.exported)
        putString("providerPermission", policy.permission.orEmpty())
        putBoolean("providerGrantUriPermissions", policy.grantUriPermissions)
        putString("providerPolicyStatus", policy.status)
        putString("providerPolicyReason", policy.reason)
        putString("providerRoutingScope", policy.routingScope)
        putBoolean("processWideProviderHook", policy.processWideProviderHook)
        putString("authorityRewriteEntry", policy.authorityRewriteEntry)
    }
}
