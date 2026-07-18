package com.multiapp.app.container

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Log
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.engine.DefaultEngineProviderDispatcher
import com.multiapp.core.engine.EngineProviderDispatchRequest
import com.multiapp.core.engine.EngineProviderDispatchResult
import com.multiapp.core.engine.EngineProviderRouteTokenGate
import com.multiapp.core.engine.EngineProviderRouteTokenGateResult
import com.multiapp.core.engine.EngineProviderRouteSlots
import com.multiapp.core.engine.guestAuthorityForEvidence
import com.multiapp.core.engine.instanceIdForEvidence
import com.multiapp.core.engine.statusForLog
import com.multiapp.core.engine.toEngineBundle
import com.multiapp.core.engine.toEngineEvidenceFields
import com.multiapp.core.model.engine.ProviderRouteContract
import java.io.FileNotFoundException

/**
 * Host-declared provider slot for v2 hosted container provider virtualization.
 *
 * System-visible provider slot for hosted guest providers. Android can only
 * resolve manifest-declared provider authorities, so guest authorities are
 * rewritten to this stub authority and then dispatched to the process-local
 * virtual provider runtime.
 */
open class StubContentProvider : ContentProvider() {

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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.query(
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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.getType(
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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.insert(
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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.bulkInsert(
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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.delete(
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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.update(
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
        val bundle = result.toEngineBundle()
        if (uri != null && result is EngineProviderDispatchResult.ProviderReady) {
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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.canonicalize(
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
            is EngineProviderDispatchResult.ProviderReady -> result.provider.uncanonicalize(
                uri.toGuestUri(result.resolution.guestAuthority)
            )
            else -> null
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val result = dispatch(uri, "openFile:$mode")
        writeProviderEvidence("openFile:$mode", uri, result)
        Log.w(TAG, "openFile dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is EngineProviderDispatchResult.ProviderReady -> result.provider.openFile(
                uri.toGuestUri(result.resolution.guestAuthority),
                mode
            )
            else -> throw FileNotFoundException(result.statusForLog())
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val result = dispatch(uri, "openAssetFile:$mode")
        writeProviderEvidence("openAssetFile:$mode", uri, result)
        Log.w(TAG, "openAssetFile dispatch result=${result.statusForLog()} uri=${uri.redactForLog()}")
        return when (result) {
            is EngineProviderDispatchResult.ProviderReady -> result.provider.openAssetFile(
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
            is EngineProviderDispatchResult.ProviderReady -> {
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

    private fun dispatch(uri: Uri, operationName: String): EngineProviderDispatchResult {
        val providerCallingUid = Binder.getCallingUid()
        val providerCallingPid = Binder.getCallingPid()
        val route = when (
            val routeResult = validateRouteToken(
                uri,
                operationName,
                providerCallingUid,
                providerCallingPid
            )
        ) {
            is EngineProviderRouteTokenGateResult.Valid -> routeResult
            is EngineProviderRouteTokenGateResult.Invalid -> return routeResult.result
        }
        val hostPackageName = context?.packageName ?: return EngineProviderDispatchResult.InvalidProxyUri("missing host context")
        val hostContext = context ?: return EngineProviderDispatchResult.InvalidProxyUri("missing host context")
        val runtimeBindResult = HostedProviderRuntimeBinder().ensureBound(hostContext, route.canonicalProxyUri)
        writeProviderRuntimeBindEvidence(route.canonicalProxyUri, runtimeBindResult)
        return DefaultEngineProviderDispatcher().dispatch(
            EngineProviderDispatchRequest(
                hostPackageName = hostPackageName,
                hostContext = hostContext,
                proxyUri = route.canonicalProxyUri,
                operationName = operationName,
                verifiedRoute = route.route,
                providerCallingUid = providerCallingUid,
                providerCallingPid = providerCallingPid,
                hostUid = hostContext.applicationInfo.uid,
                callerProcessSlot = route.route.callerProcessSlot,
                accessMode = operationName.substringAfter(':', "").takeIf { it.isNotBlank() }
            )
        )
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
        val instanceId = extras?.getString(ProviderRouteContract.PROXY_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val guestAuthority = extras.getString(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val routeToken = extras.getString(ProviderRouteContract.PROXY_ROUTE_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val processSlot = extras.getString(ProviderRouteContract.PROXY_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }
        val hostPackageName = context?.packageName ?: return null
        val proxyUri = Uri.Builder()
            .scheme("content")
            .authority(stubAuthority(hostPackageName, processSlot))
            .appendQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID, instanceId)
            .appendQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY, guestAuthority)
            .apply {
                if (!processSlot.isNullOrBlank()) {
                    appendQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT, processSlot)
                }
            }
            .appendQueryParameter(ProviderRouteContract.PROXY_ROUTE_TOKEN, routeToken)
            .build()
        return ProviderCallRoute(proxyUri, arg)
    }

    internal fun routeTokenStatusForTest(
        token: String?,
        instanceId: String?,
        guestAuthority: String?,
        operationName: String,
        expectedProcessSlot: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): String = validateRouteTokenFields(
        token = token,
        instanceId = instanceId,
        guestAuthority = guestAuthority,
        operationName = operationName,
        expectedProcessSlot = expectedProcessSlot,
        nowMillis = nowMillis
    )?.reason
        ?: "VALID"

    private fun validateRouteToken(
        uri: Uri,
        operationName: String,
        providerCallingUid: Int,
        providerCallingPid: Int
    ): EngineProviderRouteTokenGateResult {
        return EngineProviderRouteTokenGate.validate(
            uri = uri,
            operationName = operationName,
            providerCallingUid = providerCallingUid,
            providerCallingPid = providerCallingPid
        )
    }

    private fun validateRouteTokenFields(
        token: String?,
        instanceId: String?,
        guestAuthority: String?,
        operationName: String,
        expectedProcessSlot: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): EngineProviderDispatchResult.InvalidProxyUri? {
        val status = EngineProviderRouteTokenGate.routeTokenStatus(
            token = token,
            instanceId = instanceId,
            guestAuthority = guestAuthority,
            operationName = operationName,
            expectedProcessSlot = expectedProcessSlot,
            nowMillis = nowMillis
        )
        return if (status == "VALID") null else EngineProviderDispatchResult.InvalidProxyUri(status)
    }

    private fun Uri.toGuestUri(guestAuthority: String): Uri {
        return ProviderProxyUri.toGuestUri(this, guestAuthority).withoutRouteToken()
    }

    private fun Bundle?.withoutProxyRoute(): Bundle? {
        if (this == null) return null
        return Bundle(this).apply {
            remove(ProviderRouteContract.PROXY_INSTANCE_ID)
            remove(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
            remove(ProviderRouteContract.PROXY_PROCESS_SLOT)
            remove(ProviderRouteContract.PROXY_ROUTE_TOKEN)
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
        result: EngineProviderDispatchResult?
    ) {
        val instanceId = result.instanceIdForEvidence()
            ?: uri.getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID)
            ?: return
        runCatching {
            val rewrittenGuestUri = result.guestAuthorityForEvidence()
                ?.takeIf { it.isNotBlank() }
                ?.let { guestAuthority -> uri.toGuestUri(guestAuthority).toString() }
                .orEmpty()
            val fields = result.toEngineEvidenceFields(
                operationName = operationName,
                uri = uri,
                rewrittenGuestUri = rewrittenGuestUri,
                processSlot = uri.getQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT)
            )
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
            ContainerEngineEvidenceBridge.recordProviderOperation(
                instanceId = instanceId,
                operationName = operationName,
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
            ?: uri.getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID)
            ?: return
        runCatching {
            val fields = linkedMapOf(
                "status" to result.status,
                "stage" to "PROVIDER_RUNTIME_BIND",
                "providerRuntimeBindStatus" to result.status,
                "providerRuntimeBindDetail" to result.detail,
                "providerRuntimeBindErrorClassName" to result.errorClassNameForEvidence(),
                "providerRuntimeBindErrorMessage" to result.errorMessageForEvidence(),
                "instanceId" to instanceId,
                "guestAuthority" to (
                    result.guestAuthorityForEvidence()
                        ?: uri.getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
                        ?: ""
                    ),
                "providerRuntimeBindProcessSlot" to result.processSlotForEvidence().orEmpty(),
                "detail" to result.detail
            )
            ContainerRuntimeEvidenceWriter.write(
                context = requireNotNull(context),
                instanceId = instanceId,
                component = "provider-runtime-bind",
                fields = fields
            )
            ContainerEngineEvidenceBridge.recordProviderOperation(
                instanceId = instanceId,
                operationName = "runtime-bind",
                fields = fields
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write provider runtime bind evidence for instanceId=$instanceId", error)
        }
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

    private fun HostedProviderRuntimeBindResult.processSlotForEvidence(): String? = when (this) {
        is HostedProviderRuntimeBindResult.Bound -> processSlot
        is HostedProviderRuntimeBindResult.Failed -> processSlot
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

        private fun stubAuthority(hostPackageName: String, processSlot: String?): String {
            return EngineProviderRouteSlots.stubAuthority(hostPackageName, processSlot)
        }

        private fun Uri.withoutRouteToken(): Uri {
            return buildUpon()
                .encodedQuery(removeRouteTokenFromEncodedQuery(encodedQuery))
                .build()
        }

        private fun removeRouteTokenFromEncodedQuery(encodedQuery: String?): String? {
            if (encodedQuery.isNullOrEmpty()) return null
            val remaining = encodedQuery
                .split("&")
                .filterNot { part -> part.substringBefore("=") == ProviderRouteContract.PROXY_ROUTE_TOKEN }
            return remaining.takeIf { it.isNotEmpty() }?.joinToString("&")
        }
    }
}

class StubContentProviderV0 : StubContentProvider()
class StubContentProviderV1 : StubContentProvider()
class StubContentProviderV2 : StubContentProvider()
class StubContentProviderV3 : StubContentProvider()
class StubContentProviderV4 : StubContentProvider()
class StubContentProviderV5 : StubContentProvider()
class StubContentProviderV6 : StubContentProvider()
class StubContentProviderV7 : StubContentProvider()
