package com.multiapp.app.container

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.identity.ProviderRouteTokenRegistry
import com.multiapp.core.loader.VirtualProviderEvidence
import com.multiapp.core.loader.VirtualProviderDispatchResult
import com.multiapp.core.loader.VirtualProviderDispatcher
import com.multiapp.core.loader.VirtualProviderManager
import java.io.FileNotFoundException

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
        val result = dispatch(uri, "query")
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
        val result = dispatch(uri, "getType")
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
        val result = dispatch(uri, "insert")
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

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        val result = dispatch(uri, "bulkInsert")
        writeProviderEvidence("bulkInsert", uri, result)
        Log.w(TAG, "bulkInsert dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.bulkInsert(
                uri.toGuestUri(result.resolution.guestAuthority),
                values
            )
            else -> 0
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val result = dispatch(uri, "delete")
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
        val result = dispatch(uri, "update")
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
        val route = callRoute(arg, extras)
        val uri = route?.proxyUri
        val result = uri?.let { dispatch(it, "call") }
        if (uri != null) writeProviderEvidence("call:$method", uri, result)
        Log.w(TAG, "call dispatch result=${result.statusForLog()} method=$method arg=${arg.redactUriStringForLog()}")
        val bundle = result.toBundle()
        if (uri != null && result is VirtualProviderDispatchResult.ProviderReady) {
            val guestResult = result.provider.call(
                method,
                route.guestArg(result.resolution.guestAuthority),
                extras.withoutProxyRoute()
            )
            if (guestResult != null) {
                bundle.putAll(guestResult)
                bundle.putString("providerMethodResult", "RETURNED")
            } else {
                bundle.putString("providerMethodResult", "NULL")
            }
        }
        return bundle
    }

    override fun canonicalize(uri: Uri): Uri? {
        val result = dispatch(uri, "canonicalize")
        writeProviderEvidence("canonicalize", uri, result)
        Log.w(TAG, "canonicalize dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.canonicalize(
                uri.toGuestUri(result.resolution.guestAuthority)
            )
            else -> null
        }
    }

    override fun uncanonicalize(uri: Uri): Uri? {
        val result = dispatch(uri, "uncanonicalize")
        writeProviderEvidence("uncanonicalize", uri, result)
        Log.w(TAG, "uncanonicalize dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.uncanonicalize(
                uri.toGuestUri(result.resolution.guestAuthority)
            )
            else -> null
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val result = dispatch(uri, "openFile")
        writeProviderEvidence("openFile:$mode", uri, result)
        Log.w(TAG, "openFile dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.openFile(
                uri.toGuestUri(result.resolution.guestAuthority),
                mode
            )
            else -> throw FileNotFoundException(result.statusForLog())
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val result = dispatch(uri, "openAssetFile")
        writeProviderEvidence("openAssetFile:$mode", uri, result)
        Log.w(TAG, "openAssetFile dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> result.provider.openAssetFile(
                uri.toGuestUri(result.resolution.guestAuthority),
                mode
            )
            else -> throw FileNotFoundException(result.statusForLog())
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?
    ): AssetFileDescriptor? = openTypedAssetFileInternal(
        uri = uri,
        mimeTypeFilter = mimeTypeFilter,
        opts = opts,
        signal = null
    )

    @Throws(FileNotFoundException::class)
    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? = openTypedAssetFileInternal(
        uri = uri,
        mimeTypeFilter = mimeTypeFilter,
        opts = opts,
        signal = signal
    )

    @Throws(FileNotFoundException::class)
    private fun openTypedAssetFileInternal(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        val result = dispatch(uri, "openTypedAssetFile")
        writeProviderEvidence("openTypedAssetFile:$mimeTypeFilter", uri, result)
        Log.w(TAG, "openTypedAssetFile dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is VirtualProviderDispatchResult.ProviderReady -> {
                val guestUri = uri.toGuestUri(result.resolution.guestAuthority)
                if (signal != null) {
                    result.provider.openTypedAssetFile(guestUri, mimeTypeFilter, opts, signal)
                } else {
                    result.provider.openTypedAssetFile(guestUri, mimeTypeFilter, opts)
                }
            }
            else -> throw FileNotFoundException(result.statusForLog())
        }
    }

    private fun dispatch(uri: Uri, operationName: String): VirtualProviderDispatchResult {
        validateRouteToken(uri, operationName)?.let { return it }
        val hostPackageName = context?.packageName ?: return VirtualProviderDispatchResult.InvalidProxyUri("missing host context")
        val hostContext = context ?: return VirtualProviderDispatchResult.InvalidProxyUri("missing host context")
        val runtimeBindResult = HostedProviderRuntimeBinder().ensureBound(hostContext, uri)
        writeProviderRuntimeBindEvidence(uri, runtimeBindResult)
        return VirtualProviderDispatcher(
            hostPackageName = hostPackageName,
            hostContext = hostContext
        ).dispatch(uri)
    }

    private fun callRoute(arg: String?, extras: Bundle?): ProviderCallRoute? {
        routeFromExtras(arg, extras)?.let { return it }
        arg?.let { value ->
            val parsed = runCatching { Uri.parse(value) }.getOrNull()
            if (parsed?.scheme == "content" && !parsed.authority.isNullOrBlank()) {
                return ProviderCallRoute(parsed, value)
            }
        }
        return null
    }

    private fun routeFromExtras(arg: String?, extras: Bundle?): ProviderCallRoute? {
        val instanceId = extras?.getString(VirtualProviderManager.PROXY_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val guestAuthority = extras.getString(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val routeToken = extras.getString(ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val hostPackageName = context?.packageName ?: return null
        val proxyUri = Uri.Builder()
            .scheme("content")
            .authority("$hostPackageName.multiapp.provider.stub")
            .appendQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID, instanceId)
            .appendQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY, guestAuthority)
            .appendQueryParameter(ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN, routeToken)
            .build()
        return ProviderCallRoute(proxyUri, arg)
    }

    internal fun routeTokenStatusForTest(
        token: String?,
        instanceId: String?,
        guestAuthority: String?,
        operationName: String
    ): String = validateRouteTokenFields(token, instanceId, guestAuthority, operationName)?.reason
        ?: "VALID"

    private fun validateRouteToken(uri: Uri, operationName: String): VirtualProviderDispatchResult.InvalidProxyUri? {
        val instanceId = uri.getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID)
        val guestAuthority = uri.getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
        val token = uri.getQueryParameter(ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN)
        return validateRouteTokenFields(token, instanceId, guestAuthority, operationName)
    }

    private fun validateRouteTokenFields(
        token: String?,
        instanceId: String?,
        guestAuthority: String?,
        operationName: String
    ): VirtualProviderDispatchResult.InvalidProxyUri? {
        if (instanceId.isNullOrBlank()) {
            return VirtualProviderDispatchResult.InvalidProxyUri("missing instanceId")
        }
        if (guestAuthority.isNullOrBlank()) {
            return VirtualProviderDispatchResult.InvalidProxyUri("missing guestAuthority")
        }
        val result = ProviderRouteTokenRegistry.validate(
            token = token,
            callerInstanceId = instanceId,
            targetInstanceId = instanceId,
            authority = guestAuthority,
            operation = operationName
        )
        return if (result.isValid) {
            null
        } else {
            VirtualProviderDispatchResult.InvalidProxyUri("invalid route token:${result.status.name}")
        }
    }

    private fun Uri.toGuestUri(guestAuthority: String): Uri {
        return ProviderProxyUri.toGuestUri(this, guestAuthority).withoutRouteToken()
    }

    private fun Bundle?.withoutProxyRoute(): Bundle? {
        if (this == null) return null
        return Bundle(this).apply {
            remove(VirtualProviderManager.PROXY_INSTANCE_ID)
            remove(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
            remove(ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN)
        }
    }

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
            val fields = result.toEvidenceFields(operationName, uri)
            ContainerRuntimeEvidenceWriter.write(
                context = requireNotNull(context),
                instanceId = instanceId,
                component = "provider-proxy",
                fields = fields
            )
            ContainerRuntimeEvidenceWriter.write(
                context = requireNotNull(context),
                instanceId = instanceId,
                component = ProviderMethodEvidenceComponents.forOperation(operationName),
                fields = fields
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write provider evidence for instanceId=$instanceId", error)
        }
    }

    private fun writeProviderRuntimeBindEvidence(
        uri: Uri,
        result: HostedProviderRuntimeBindResult
    ) {
        val instanceId = result.instanceIdForEvidence()
            ?: uri.getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID)
            ?: return
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = requireNotNull(context),
                instanceId = instanceId,
                component = "provider-runtime-bind",
                fields = linkedMapOf(
                    "status" to result.status,
                    "stage" to "PROVIDER_RUNTIME_BIND",
                    "providerRuntimeBindStatus" to result.status,
                    "providerRuntimeBindDetail" to result.detail,
                    "providerRuntimeBindErrorClassName" to result.errorClassNameForEvidence(),
                    "providerRuntimeBindErrorMessage" to result.errorMessageForEvidence(),
                    "instanceId" to instanceId,
                    "guestAuthority" to (
                        result.guestAuthorityForEvidence()
                            ?: uri.getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
                            ?: ""
                        ),
                    "detail" to result.detail
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write provider runtime bind evidence for instanceId=$instanceId", error)
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

    private fun VirtualProviderDispatchResult?.toEvidenceFields(
        operationName: String,
        uri: Uri
    ): Map<String, Any?> {
        val methodEvidence = VirtualProviderEvidence.methodDispatch(this, operationName)
        val rewrittenGuestUri = guestAuthorityForEvidence()
            ?.takeIf { it.isNotBlank() }
            ?.let { guestAuthority -> uri.toGuestUri(guestAuthority).toString() }
        return linkedMapOf(
            "status" to statusName(),
            "stage" to "PROVIDER_PROXY",
            "operationName" to operationName,
            "uri" to uri.toString(),
            "proxyUri" to uri.toString(),
            "rewrittenGuestUri" to rewrittenGuestUri.orEmpty(),
            "instanceId" to instanceIdForEvidence().orEmpty(),
            "originPackageName" to originPackageNameForEvidence().orEmpty(),
            "virtualPackageName" to virtualPackageNameForEvidence().orEmpty(),
            "guestAuthority" to guestAuthorityForEvidence().orEmpty(),
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
            "cached" to ((this as? VirtualProviderDispatchResult.ProviderReady)?.cached ?: false),
            "detail" to statusForLog()
        )
    }

    private data class ProviderCallRoute(
        val proxyUri: Uri,
        private val originalArg: String?
    ) {
        fun guestArg(guestAuthority: String): String? {
            val parsed = originalArg?.let { runCatching { Uri.parse(it) }.getOrNull() }
            return if (parsed?.scheme == "content" && parsed.authority == proxyUri.authority) {
                ProviderProxyUri.toGuestUri(parsed, guestAuthority).withoutRouteToken().toString()
            } else {
                originalArg
            }
        }
    }

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

    private fun VirtualProviderDispatchResult?.originPackageNameForEvidence(): String? = when (this) {
        is VirtualProviderDispatchResult.ProviderReady -> resolution.originPackageName
        is VirtualProviderDispatchResult.RuntimeNotBound -> resolution.originPackageName
        is VirtualProviderDispatchResult.RuntimeIncomplete -> resolution.originPackageName
        is VirtualProviderDispatchResult.ProviderCreateFailed -> resolution.originPackageName
        is VirtualProviderDispatchResult.ProviderAttachFailed -> resolution.originPackageName
        else -> null
    }

    private fun VirtualProviderDispatchResult?.virtualPackageNameForEvidence(): String? = when (this) {
        is VirtualProviderDispatchResult.ProviderReady -> resolution.virtualPackageName
        is VirtualProviderDispatchResult.RuntimeNotBound -> resolution.virtualPackageName
        is VirtualProviderDispatchResult.RuntimeIncomplete -> resolution.virtualPackageName
        is VirtualProviderDispatchResult.ProviderCreateFailed -> resolution.virtualPackageName
        is VirtualProviderDispatchResult.ProviderAttachFailed -> resolution.virtualPackageName
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

    private fun HostedProviderRuntimeBindResult.instanceIdForEvidence(): String? = when (this) {
        is HostedProviderRuntimeBindResult.Bound -> instanceId
        is HostedProviderRuntimeBindResult.Failed -> instanceId
        is HostedProviderRuntimeBindResult.NotRequested -> null
    }

    private fun HostedProviderRuntimeBindResult.guestAuthorityForEvidence(): String? = when (this) {
        is HostedProviderRuntimeBindResult.Bound -> guestAuthority
        is HostedProviderRuntimeBindResult.Failed -> guestAuthority
        is HostedProviderRuntimeBindResult.NotRequested -> null
    }

    private fun HostedProviderRuntimeBindResult.errorClassNameForEvidence(): String? = when (this) {
        is HostedProviderRuntimeBindResult.Failed -> errorClassName
        else -> null
    }

    private fun HostedProviderRuntimeBindResult.errorMessageForEvidence(): String? = when (this) {
        is HostedProviderRuntimeBindResult.Failed -> errorMessage
        else -> null
    }

    companion object {
        private const val TAG = "StubContentProvider"

        private fun Uri.withoutRouteToken(): Uri {
            return buildUpon()
                .encodedQuery(removeRouteTokenFromEncodedQuery(encodedQuery))
                .build()
        }

        private fun removeRouteTokenFromEncodedQuery(encodedQuery: String?): String? {
            if (encodedQuery.isNullOrEmpty()) return null
            val remaining = encodedQuery
                .split("&")
                .filterNot { part -> part.substringBefore("=") == ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN }
            return remaining.takeIf { it.isNotEmpty() }?.joinToString("&")
        }
    }
}
