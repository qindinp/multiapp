package com.multiapp.app.container

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.loader.VirtualProviderEvidence
import com.multiapp.core.loader.VirtualProviderDispatchResult
import com.multiapp.core.loader.VirtualProviderDispatcher
import com.multiapp.core.loader.VirtualProviderManager

/**
 * Host-declared provider slot for v2 hosted container provider virtualization.
 *
 * System-visible provider slot for hosted guest providers. Android can only
 * resolve manifest-declared provider authorities, so guest authorities are
 * rewritten to this stub authority and then dispatched to the process-local
 * virtual provider runtime.
 */
class StubContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Log.i(TAG, "StubContentProvider created: authority=${context?.packageName}.multiapp.provider.stub")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val result = dispatch(uri)
        writeProviderEvidence("query", uri, result)
        Log.w(TAG, "query dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.query(
                uri.toGuestUri(result.resolution.guestAuthority),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            else -> null
        }
    }

    override fun getType(uri: Uri): String? {
        val result = dispatch(uri)
        writeProviderEvidence("getType", uri, result)
        Log.w(TAG, "getType dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.getType(
                uri.toGuestUri(result.resolution.guestAuthority)
            )
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val result = dispatch(uri)
        writeProviderEvidence("insert", uri, result)
        Log.w(TAG, "insert dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.insert(
                uri.toGuestUri(result.resolution.guestAuthority),
                values
            )
            else -> null
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val result = dispatch(uri)
        writeProviderEvidence("delete", uri, result)
        Log.w(TAG, "delete dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.delete(
                uri.toGuestUri(result.resolution.guestAuthority),
                selection,
                selectionArgs
            )
            else -> 0
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val result = dispatch(uri)
        writeProviderEvidence("update", uri, result)
        Log.w(TAG, "update dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.update(
                uri.toGuestUri(result.resolution.guestAuthority),
                values,
                selection,
                selectionArgs
            )
            else -> 0
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uri = arg?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val result = uri?.let { dispatch(it) }
        if (uri != null) writeProviderEvidence("call:$method", uri, result)
        Log.w(TAG, "call dispatch result=${result.statusForLog()} method=$method arg=${arg.redactUriStringForLog()}")
        return result.toBundle()
    }

    private fun dispatch(uri: Uri): VirtualProviderDispatchResult {
        val hostPackageName = context?.packageName ?: return VirtualProviderDispatchResult.InvalidProxyUri("missing host context")
        return VirtualProviderDispatcher(
            hostPackageName = hostPackageName,
            hostContext = context
        ).dispatch(uri)
    }

    private fun Uri.toGuestUri(guestAuthority: String): Uri = buildUpon()
        .authority(guestAuthority)
        .clearQuery()
        .build()

    private fun Uri.redactForLog(): String = EvidenceSanitizer.redactUriForEvidence(toString())

    private fun String?.redactUriStringForLog(): String = this
        ?.let { value ->
            val parsed = runCatching { Uri.parse(value) }.getOrNull()
            parsed?.takeIf { !it.scheme.isNullOrBlank() }?.redactForLog() ?: "<non-uri>"
        }
        .orEmpty()

    private fun writeProviderEvidence(
        operationName: String,
        uri: Uri,
        result: VirtualProviderDispatchResult?
    ) {
        val instanceId = result.instanceIdForEvidence()
            ?: uri.getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID)
            ?: return
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = requireNotNull(context),
                instanceId = instanceId,
                component = "provider-proxy",
                fields = result.toEvidenceFields(operationName, uri)
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write provider evidence for instanceId=$instanceId", error)
        }
    }

    private fun VirtualProviderDispatchResult?.toBundle(): Bundle = Bundle().apply {
        when (val result = this@toBundle) {
            null -> {
                putString("status", "INVALID_PROXY_URI")
                putString("reason", "missing uri")
            }
            is VirtualProviderDispatchResult.ProviderReady -> {
                putString("status", if (result.cached) "PROVIDER_CACHED" else "PROVIDER_CREATED")
                putString("instanceId", result.resolution.instanceId)
                putString("guestAuthority", result.resolution.guestAuthority)
                putString("providerClassName", result.resolution.providerClassName)
                putProviderEvidence(result.evidence)
            }
            is VirtualProviderDispatchResult.RuntimeNotBound -> {
                putString("status", "RUNTIME_NOT_BOUND")
                putString("instanceId", result.resolution.instanceId)
                putString("guestAuthority", result.resolution.guestAuthority)
                putString("providerClassName", result.resolution.providerClassName)
                putProviderEvidence(result.evidence)
            }
            is VirtualProviderDispatchResult.RuntimeIncomplete -> {
                putString("status", "RUNTIME_INCOMPLETE")
                putString("reason", result.reason)
                putString("instanceId", result.resolution.instanceId)
                putString("guestAuthority", result.resolution.guestAuthority)
                putString("providerClassName", result.resolution.providerClassName)
                putProviderEvidence(result.evidence)
            }
            is VirtualProviderDispatchResult.ProviderCreateFailed -> {
                putString("status", "PROVIDER_CREATE_FAILED")
                putString("reason", result.error.message)
                putString("instanceId", result.resolution.instanceId)
                putString("guestAuthority", result.resolution.guestAuthority)
                putString("providerClassName", result.resolution.providerClassName)
                putProviderEvidence(result.evidence)
            }
            is VirtualProviderDispatchResult.ProviderAttachFailed -> {
                putString("status", "PROVIDER_ATTACH_FAILED")
                putString("reason", result.error.message)
                putString("instanceId", result.resolution.instanceId)
                putString("guestAuthority", result.resolution.guestAuthority)
                putString("providerClassName", result.resolution.providerClassName)
                putProviderEvidence(result.evidence)
            }
            is VirtualProviderDispatchResult.InvalidProxyUri -> {
                putString("status", "INVALID_PROXY_URI")
                putString("reason", result.reason)
            }
            is VirtualProviderDispatchResult.InstanceNotFound -> {
                putString("status", "INSTANCE_NOT_FOUND")
                putString("instanceId", result.instanceId)
            }
            is VirtualProviderDispatchResult.ProviderNotFound -> {
                putString("status", "PROVIDER_NOT_FOUND")
                putString("instanceId", result.instanceId)
                putString("guestAuthority", result.guestAuthority)
                putProviderEvidence(result.evidence)
            }
        }
    }

    private fun Bundle.putProviderEvidence(evidence: VirtualProviderEvidence) {
        putString("evidenceOperation", evidence.operation.name)
        putBoolean("evidenceSuccess", evidence.success)
        putString("evidenceReason", evidence.reason)
        putString("proxyAuthority", evidence.proxyAuthority)
    }

    private fun VirtualProviderDispatchResult?.toEvidenceFields(
        operationName: String,
        uri: Uri
    ): Map<String, Any?> = linkedMapOf(
        "status" to statusName(),
        "stage" to "PROVIDER_PROXY",
        "operationName" to operationName,
        "uri" to uri.toString(),
        "instanceId" to instanceIdForEvidence().orEmpty(),
        "guestAuthority" to guestAuthorityForEvidence().orEmpty(),
        "providerClassName" to providerClassNameForEvidence().orEmpty(),
        "proxyAuthority" to evidenceOrNull()?.proxyAuthority.orEmpty(),
        "evidenceOperation" to evidenceOrNull()?.operation?.name.orEmpty(),
        "evidenceSuccess" to (evidenceOrNull()?.success ?: false),
        "reason" to reasonForEvidence().orEmpty(),
        "cached" to ((this as? VirtualProviderDispatchResult.ProviderReady)?.cached ?: false),
        "detail" to statusForLog()
    )

    private fun VirtualProviderDispatchResult?.evidenceOrNull(): VirtualProviderEvidence? = when (this) {
        is VirtualProviderDispatchResult.ProviderReady -> evidence
        is VirtualProviderDispatchResult.RuntimeNotBound -> evidence
        is VirtualProviderDispatchResult.RuntimeIncomplete -> evidence
        is VirtualProviderDispatchResult.ProviderCreateFailed -> evidence
        is VirtualProviderDispatchResult.ProviderAttachFailed -> evidence
        is VirtualProviderDispatchResult.ProviderNotFound -> evidence
        else -> null
    }

    private fun VirtualProviderDispatchResult?.instanceIdForEvidence(): String? = when (this) {
        is VirtualProviderDispatchResult.ProviderReady -> resolution.instanceId
        is VirtualProviderDispatchResult.RuntimeNotBound -> resolution.instanceId
        is VirtualProviderDispatchResult.RuntimeIncomplete -> resolution.instanceId
        is VirtualProviderDispatchResult.ProviderCreateFailed -> resolution.instanceId
        is VirtualProviderDispatchResult.ProviderAttachFailed -> resolution.instanceId
        is VirtualProviderDispatchResult.InstanceNotFound -> instanceId
        is VirtualProviderDispatchResult.ProviderNotFound -> instanceId
        else -> null
    }

    private fun VirtualProviderDispatchResult?.guestAuthorityForEvidence(): String? = when (this) {
        is VirtualProviderDispatchResult.ProviderReady -> resolution.guestAuthority
        is VirtualProviderDispatchResult.RuntimeNotBound -> resolution.guestAuthority
        is VirtualProviderDispatchResult.RuntimeIncomplete -> resolution.guestAuthority
        is VirtualProviderDispatchResult.ProviderCreateFailed -> resolution.guestAuthority
        is VirtualProviderDispatchResult.ProviderAttachFailed -> resolution.guestAuthority
        is VirtualProviderDispatchResult.ProviderNotFound -> guestAuthority
        else -> null
    }

    private fun VirtualProviderDispatchResult?.providerClassNameForEvidence(): String? = when (this) {
        is VirtualProviderDispatchResult.ProviderReady -> resolution.providerClassName
        is VirtualProviderDispatchResult.RuntimeNotBound -> resolution.providerClassName
        is VirtualProviderDispatchResult.RuntimeIncomplete -> resolution.providerClassName
        is VirtualProviderDispatchResult.ProviderCreateFailed -> resolution.providerClassName
        is VirtualProviderDispatchResult.ProviderAttachFailed -> resolution.providerClassName
        else -> null
    }

    private fun VirtualProviderDispatchResult?.reasonForEvidence(): String? = when (this) {
        null -> "missing uri"
        is VirtualProviderDispatchResult.RuntimeIncomplete -> reason
        is VirtualProviderDispatchResult.ProviderCreateFailed -> error.message ?: error.javaClass.name
        is VirtualProviderDispatchResult.ProviderAttachFailed -> error.message ?: error.javaClass.name
        is VirtualProviderDispatchResult.InvalidProxyUri -> reason
        is VirtualProviderDispatchResult.InstanceNotFound -> "INSTANCE_NOT_FOUND"
        else -> evidenceOrNull()?.reason
    }

    private fun VirtualProviderDispatchResult?.statusName(): String = when (this) {
        null -> "INVALID_PROXY_URI"
        is VirtualProviderDispatchResult.ProviderReady -> if (cached) "PROVIDER_CACHED" else "PROVIDER_CREATED"
        is VirtualProviderDispatchResult.RuntimeNotBound -> "RUNTIME_NOT_BOUND"
        is VirtualProviderDispatchResult.RuntimeIncomplete -> "RUNTIME_INCOMPLETE"
        is VirtualProviderDispatchResult.ProviderCreateFailed -> "PROVIDER_CREATE_FAILED"
        is VirtualProviderDispatchResult.ProviderAttachFailed -> "PROVIDER_ATTACH_FAILED"
        is VirtualProviderDispatchResult.InvalidProxyUri -> "INVALID_PROXY_URI"
        is VirtualProviderDispatchResult.InstanceNotFound -> "INSTANCE_NOT_FOUND"
        is VirtualProviderDispatchResult.ProviderNotFound -> "PROVIDER_NOT_FOUND"
    }

    private fun VirtualProviderDispatchResult?.statusForLog(): String = when (this) {
        null -> "INVALID_PROXY_URI:missing uri"
        is VirtualProviderDispatchResult.ProviderReady -> "PROVIDER_READY:${if (cached) "cached" else "created"}:${resolution.providerClassName}"
        is VirtualProviderDispatchResult.RuntimeNotBound -> "RUNTIME_NOT_BOUND:${resolution.providerClassName}"
        is VirtualProviderDispatchResult.RuntimeIncomplete -> "RUNTIME_INCOMPLETE:$reason:${resolution.providerClassName}"
        is VirtualProviderDispatchResult.ProviderCreateFailed -> "PROVIDER_CREATE_FAILED:${error.message}:${resolution.providerClassName}"
        is VirtualProviderDispatchResult.ProviderAttachFailed -> "PROVIDER_ATTACH_FAILED:${error.message}:${resolution.providerClassName}"
        is VirtualProviderDispatchResult.InvalidProxyUri -> "INVALID_PROXY_URI:$reason"
        is VirtualProviderDispatchResult.InstanceNotFound -> "INSTANCE_NOT_FOUND"
        is VirtualProviderDispatchResult.ProviderNotFound -> "PROVIDER_NOT_FOUND"
    }

    companion object {
        private const val TAG = "StubContentProvider"
    }
}
