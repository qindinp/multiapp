package com.multiapp.core.engine

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresApi
import com.multiapp.core.identity.ProviderRouteToken
import com.multiapp.core.identity.ProviderRouteTokenRegistry
import com.multiapp.core.loader.VirtualContentResolverFactory
import com.multiapp.core.loader.VirtualContentResolverFactoryRequest
import com.multiapp.core.model.engine.ProviderRouteContract
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.FileNotFoundException

class EngineVirtualContentResolverFactory(
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
    private val resolverWrapper: (ContentProvider) -> ContentResolver = ContentResolver::wrap,
    private val dispatcherFactory: () -> EngineProviderDispatcher = { DefaultEngineProviderDispatcher() },
    private val authorityResolverFactory: (
        VirtualContentResolverFactoryRequest
    ) -> EngineProviderAuthorityResolver = { request ->
        DefaultEngineProviderAuthorityResolver(request.hostContext, request.config)
    },
    private val uidProvider: (Context) -> Int = { it.applicationInfo.uid },
    private val pidProvider: () -> Int = Process::myPid,
    private val providerAttacher: (ContentProvider, Context, VirtualContextConfig) -> Unit =
        { provider, context, config ->
            provider.attachInfo(
                context,
                ProviderInfo().apply {
                    name = provider.javaClass.name
                    authority = EngineProviderRouteSlots.stubAuthority(context.packageName, config.processSlot)
                    applicationInfo = context.applicationInfo
                    processName = config.processSlot
                    exported = false
                    grantUriPermissions = false
                }
            )
        }
) : VirtualContentResolverFactory {
    override fun create(request: VirtualContentResolverFactoryRequest): ContentResolver? {
        if (sdkInt() < Build.VERSION_CODES.Q || request.config.packageSnapshot == null) return null
        return createWrappedResolver(request)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createWrappedResolver(request: VirtualContentResolverFactoryRequest): ContentResolver {
        val provider = EngineRoutingContentProvider(
            hostContext = request.hostContext,
            config = request.config,
            dispatcher = dispatcherFactory(),
            authorityResolver = authorityResolverFactory(request),
            hostUid = uidProvider(request.hostContext),
            processId = pidProvider()
        )
        providerAttacher(provider, request.hostContext, request.config)
        return resolverWrapper(provider)
    }
}

fun interface EngineProviderAuthorityResolver {
    fun resolve(
        uri: Uri,
        operation: EngineProviderOperation,
        accessMode: String?
    ): VirtualProviderAuthorityResolveResult
}

internal class DefaultEngineProviderAuthorityResolver(
    private val hostContext: Context,
    private val config: VirtualContextConfig,
    private val providerServiceFactory: (Context) -> VirtualProviderService = { context ->
        @Suppress("UNUSED_VARIABLE")
        val ignored = context
        IpcBackedVirtualProviderService()
    }
) : EngineProviderAuthorityResolver {
    private val providerService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        providerServiceFactory(hostContext)
    }

    override fun resolve(
        uri: Uri,
        operation: EngineProviderOperation,
        accessMode: String?
    ): VirtualProviderAuthorityResolveResult {
        val authority = uri.authority?.takeIf { it.isNotBlank() }
            ?: return nonVirtualResult("uri_authority_missing")
        if (authority in config.packageSnapshot?.providers.orEmpty().flatMap { it.authorities }) {
            return VirtualProviderAuthorityResolveResult(
                callerInstanceId = config.instanceId,
                guestAuthority = authority,
                verdict = com.multiapp.core.model.engine.EngineResultStatus.PASS,
                virtualAuthority = true,
                targetInstanceId = config.instanceId,
                message = "provider_authority_resolved_self_snapshot"
            )
        }
        val currentService = runCatching { providerService }.getOrNull()
            ?: return nonVirtualResult("provider_authority_index_unavailable")
        return currentService.resolveProviderAuthority(
            config.instanceId,
            VirtualProviderAuthorityResolveRequest(
                guestAuthority = authority,
                operation = operation,
                encodedPath = normalizeProviderGrantPath(uri.encodedPath),
                accessMode = accessMode
            )
        )
    }

    private fun nonVirtualResult(
        message: String,
        authority: String = "unknown"
    ) = VirtualProviderAuthorityResolveResult(
        callerInstanceId = config.instanceId,
        guestAuthority = authority,
        verdict = com.multiapp.core.model.engine.EngineResultStatus.FAIL,
        virtualAuthority = false,
        message = message
    )

}

/**
 * Process-local provider bridge used by ContentResolver.wrap on baseline profiles.
 * It keeps data-plane calls out of LSPlant while preserving the engine route gate.
 */
class EngineRoutingContentProvider internal constructor(
    private val hostContext: Context,
    private val config: VirtualContextConfig,
    private val dispatcher: EngineProviderDispatcher,
    private val authorityResolver: EngineProviderAuthorityResolver = EngineProviderAuthorityResolver { uri, _, _ ->
        val authority = uri.authority.orEmpty()
        val isSelf = authority in config.packageSnapshot?.providers.orEmpty().flatMap { it.authorities }
        VirtualProviderAuthorityResolveResult(
            callerInstanceId = config.instanceId,
            guestAuthority = authority.ifBlank { "unknown" },
            verdict = if (isSelf) {
                com.multiapp.core.model.engine.EngineResultStatus.PASS
            } else {
                com.multiapp.core.model.engine.EngineResultStatus.FAIL
            },
            virtualAuthority = isSelf,
            targetInstanceId = config.instanceId.takeIf { isSelf },
            message = if (isSelf) "provider_authority_resolved_self_snapshot" else "provider_authority_not_virtual"
        )
    },
    private val hostUid: Int,
    private val processId: Int,
    private val routeIssuer: (
        callerInstanceId: String,
        targetInstanceId: String,
        authority: String,
        operation: String,
        processSlot: String?
    ) -> ProviderRouteToken = { caller, target, authority, operation, processSlot ->
        ProviderRouteTokenRegistry.issue(
            callerInstanceId = caller,
            targetInstanceId = target,
            authority = authority,
            operation = operation,
            processSlot = processSlot
        )
    }
) : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return routeProvider(
            uri = uri,
            operationName = "query",
            systemCall = { hostContext.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder) },
            blocked = { null },
            guestCall = { provider -> provider.query(uri, projection, selection, selectionArgs, sortOrder) }
        )
    }

    override fun getType(uri: Uri): String? {
        return routeProvider(uri, "getType", { hostContext.contentResolver.getType(uri) }, { null }) {
            it.getType(uri)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return routeProvider(uri, "insert", { hostContext.contentResolver.insert(uri, values) }, { null }) {
            it.insert(uri, values)
        }
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        return routeProvider(uri, "bulkInsert", { hostContext.contentResolver.bulkInsert(uri, values) }, { 0 }) {
            it.bulkInsert(uri, values)
        }
    }

    override fun applyBatch(
        authority: String,
        operations: ArrayList<ContentProviderOperation>
    ): Array<ContentProviderResult> = routeBatch(authority, operations)

    override fun applyBatch(
        operations: ArrayList<ContentProviderOperation>
    ): Array<ContentProviderResult> {
        val authority = operations.firstOrNull()?.uri?.authority
            ?: guestAuthorities().singleOrNull()
            ?: throw OperationApplicationException("virtual_provider_batch_authority_missing")
        return routeBatch(authority, operations)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return routeProvider(uri, "delete", { hostContext.contentResolver.delete(uri, selection, selectionArgs) }, { 0 }) {
            it.delete(uri, selection, selectionArgs)
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        return routeProvider(
            uri,
            "update",
            { hostContext.contentResolver.update(uri, values, selection, selectionArgs) },
            { 0 }
        ) { provider -> provider.update(uri, values, selection, selectionArgs) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val authority = guestAuthorities().singleOrNull() ?: return null
        return call(authority, method, arg, extras)
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).build()
        return routeProvider(
            uri,
            "call",
            { hostContext.contentResolver.call(authority, method, arg, extras) },
            { null }
        ) { provider -> provider.call(authority, method, arg, extras) }
    }

    override fun canonicalize(uri: Uri): Uri? {
        return routeProvider(uri, "canonicalize", { hostContext.contentResolver.canonicalize(uri) }, { null }) {
            it.canonicalize(uri)
        }
    }

    override fun uncanonicalize(uri: Uri): Uri? {
        return routeProvider(uri, "uncanonicalize", { hostContext.contentResolver.uncanonicalize(uri) }, { null }) {
            it.uncanonicalize(uri)
        }
    }

    override fun refresh(uri: Uri, extras: Bundle?, cancellationSignal: CancellationSignal?): Boolean {
        return routeProvider(
            uri,
            "refresh",
            { hostContext.contentResolver.refresh(uri, extras, cancellationSignal) },
            { false }
        ) { provider -> provider.refresh(uri, extras, cancellationSignal) }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        return routeProvider(
            uri,
            "openFile:$mode",
            { hostContext.contentResolver.openFileDescriptor(uri, mode) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { it.openFile(uri, mode) }
    }

    @Throws(FileNotFoundException::class)
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        return routeProvider(
            uri,
            "openAssetFile:$mode",
            { hostContext.contentResolver.openAssetFileDescriptor(uri, mode) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { it.openAssetFile(uri, mode) }
    }

    @Throws(FileNotFoundException::class)
    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?
    ): AssetFileDescriptor? {
        return routeProvider(
            uri,
            "openTypedAssetFile",
            { hostContext.contentResolver.openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { it.openTypedAssetFile(uri, mimeTypeFilter, opts) }
    }

    @Throws(FileNotFoundException::class)
    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        return routeProvider(
            uri,
            "openTypedAssetFile",
            { hostContext.contentResolver.openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts, signal) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { it.openTypedAssetFile(uri, mimeTypeFilter, opts, signal) }
    }

    private fun routeBatch(
        authority: String,
        operations: ArrayList<ContentProviderOperation>
    ): Array<ContentProviderResult> {
        if (authority.isBlank()) {
            throw OperationApplicationException("virtual_provider_batch_authority_missing")
        }
        if (operations.isEmpty()) return emptyArray()

        val routes = operations.mapIndexed { index, operation ->
            val uri = operation.uri
            if (uri.authority != authority) {
                throw OperationApplicationException(
                    "virtual_provider_batch_authority_mismatch:$index:${uri.authority}:$authority"
                )
            }
            val accessMode = when {
                operation.isWriteOperation -> "w"
                operation.isReadOperation -> "r"
                else -> throw OperationApplicationException(
                    "virtual_provider_batch_operation_unsupported:$index"
                )
            }
            BatchRoute(
                index = index,
                uri = uri,
                accessMode = accessMode,
                resolution = authorityResolver.resolve(
                    uri,
                    EngineProviderOperation.APPLY_BATCH,
                    accessMode
                )
            )
        }

        if (routes.all { !it.resolution.virtualAuthority }) {
            return hostContext.contentResolver.applyBatch(authority, operations)
        }
        if (routes.any { !it.resolution.virtualAuthority }) {
            throw OperationApplicationException("virtual_provider_batch_route_mixed")
        }
        val targetInstanceId = routes.first().resolution.targetInstanceId
            ?: throw OperationApplicationException("virtual_provider_batch_target_missing:0")
        if (routes.any { it.resolution.targetInstanceId != targetInstanceId }) {
            throw OperationApplicationException("virtual_provider_batch_target_mismatch")
        }

        var targetProvider: ContentProvider? = null
        routes.forEach { route ->
            when (val result = dispatch(route.uri, "applyBatch:${route.accessMode}", targetInstanceId)) {
                is EngineProviderDispatchResult.ProviderReady -> {
                    val existing = targetProvider
                    if (existing != null && existing !== result.provider) {
                        throw OperationApplicationException("virtual_provider_batch_provider_mismatch:${route.index}")
                    }
                    targetProvider = result.provider
                }
                else -> throw OperationApplicationException(
                    "virtual_provider_batch_route_blocked:${route.index}:${result.statusName()}"
                )
            }
        }
        return checkNotNull(targetProvider).applyBatch(authority, operations)
    }

    private inline fun <T> routeProvider(
        uri: Uri,
        operationName: String,
        systemCall: () -> T,
        blocked: () -> T,
        guestCall: (ContentProvider) -> T
    ): T {
        val operation = EngineProviderOperation.fromOperationName(operationName)
        val accessMode = operationName.substringAfter(':', "").takeIf { it.isNotBlank() }
        val resolution = authorityResolver.resolve(uri, operation, accessMode)
        if (!resolution.virtualAuthority) return systemCall()
        val targetInstanceId = resolution.targetInstanceId ?: return blocked()
        return when (val result = dispatch(uri, operationName, targetInstanceId)) {
            is EngineProviderDispatchResult.ProviderReady -> guestCall(result.provider)
            else -> blocked()
        }
    }

    private fun dispatch(
        uri: Uri,
        operationName: String,
        targetInstanceId: String
    ): EngineProviderDispatchResult {
        val authority = uri.authority?.takeIf { it.isNotBlank() }
            ?: return EngineProviderDispatchResult.InvalidProxyUri("missing guest authority")
        if (targetInstanceId == config.instanceId && authority !in guestAuthorities()) {
            return EngineProviderDispatchResult.InvalidProxyUri("guest authority not in package snapshot")
        }
        val route = routeIssuer(
            config.instanceId,
            targetInstanceId,
            authority,
            operationName,
            config.processSlot
        ).toEngineRoute()
        return dispatcher.dispatch(
            EngineProviderDispatchRequest(
                hostPackageName = hostContext.packageName,
                hostContext = hostContext,
                proxyUri = uri.toProxyUri(route),
                operationName = operationName,
                verifiedRoute = route,
                providerCallingUid = hostUid,
                providerCallingPid = processId,
                hostUid = hostUid,
                callerProcessSlot = config.processSlot,
                accessMode = operationName.substringAfter(':', "").takeIf { it.isNotBlank() }
            )
        )
    }

    private fun guestAuthorities(): Set<String> = config.packageSnapshot
        ?.providers
        .orEmpty()
        .flatMapTo(linkedSetOf()) { it.authorities }

    private fun Uri.toProxyUri(route: EngineProviderRouteToken): Uri {
        val builder = buildUpon()
            .encodedAuthority(EngineProviderRouteSlots.stubAuthority(hostContext.packageName, route.processSlot))
            .encodedQuery(EngineProviderRouteTokenGate.rewriteEncodedQuery(encodedQuery))
            .appendQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID, route.targetInstanceId)
            .appendQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY, route.authority)
        route.processSlot?.let {
            builder.appendQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT, it)
        }
        return builder
            .appendQueryParameter(ProviderRouteContract.PROXY_ROUTE_TOKEN, route.token)
            .build()
    }

    private fun ProviderRouteToken.toEngineRoute(): EngineProviderRouteToken = EngineProviderRouteToken(
        token = token,
        callerInstanceId = callerInstanceId,
        targetInstanceId = targetInstanceId,
        authority = authority,
        operation = operation,
        expiresAtMillis = expiresAtMillis,
        processSlot = processSlot
    )

    private data class BatchRoute(
        val index: Int,
        val uri: Uri,
        val accessMode: String,
        val resolution: VirtualProviderAuthorityResolveResult
    )
}
