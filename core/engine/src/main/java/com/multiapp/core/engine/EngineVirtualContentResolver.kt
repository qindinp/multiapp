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
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresApi
import com.multiapp.core.common.AndroidCompat
import com.multiapp.core.common.findField
import com.multiapp.core.identity.ProviderRouteToken
import com.multiapp.core.identity.ProviderRouteTokenRegistry
import com.multiapp.core.loader.VirtualContentResolverFactory
import com.multiapp.core.loader.VirtualContentResolverFactoryRequest
import com.multiapp.core.model.engine.ProviderRouteContract
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.FileNotFoundException

internal class EngineVirtualContentResolverFactory(
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
    private val resolverWrapper: (
        provider: ContentProvider,
        routingResolver: ContentResolver,
        systemResolver: ContentResolver
    ) -> ContentResolver = EngineHybridContentResolver::install,
    private val hostContextsFactory: (
        VirtualContentResolverFactoryRequest
    ) -> EngineContentResolverHostContexts = ::createEngineContentResolverHostContexts,
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
        val hostContexts = hostContextsFactory(request)
        val systemResolver = hostContexts.systemContext.contentResolver
        val provider = EngineRoutingContentProvider(
            hostContext = hostContexts.systemContext,
            config = request.config,
            dispatcher = dispatcherFactory(),
            authorityResolver = authorityResolverFactory(
                request.copy(hostContext = hostContexts.systemContext)
            ),
            hostUid = uidProvider(hostContexts.systemContext),
            processId = pidProvider(),
            systemResolver = systemResolver
        )
        providerAttacher(provider, hostContexts.systemContext, request.config)
        return resolverWrapper(provider, hostContexts.routingContext.contentResolver, systemResolver)
    }
}

internal data class EngineContentResolverHostContexts(
    val systemContext: Context,
    val routingContext: Context
)

internal fun createEngineContentResolverHostContexts(
    request: VirtualContentResolverFactoryRequest
): EngineContentResolverHostContexts {
    val hostPackageName = request.config.processSlot
        ?.substringBefore(':')
        ?.takeIf { it.isNotBlank() }
        ?: runCatching { request.hostContext.opPackageName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { request.hostContext.packageName }.getOrNull().orEmpty()
    val systemContext = request.hostContext.createHostPackageContextOrNull(hostPackageName)
        ?: request.hostContext
    val routingContext = request.hostContext.createHostPackageContextOrNull(hostPackageName)
        ?: runCatching {
            systemContext.createConfigurationContext(Configuration(systemContext.resources.configuration))
        }.getOrNull()
        ?: systemContext
    return EngineContentResolverHostContexts(systemContext, routingContext)
}

private fun Context.createHostPackageContextOrNull(hostPackageName: String): Context? {
    if (hostPackageName.isBlank()) return null
    return runCatching { createPackageContext(hostPackageName, Context.CONTEXT_IGNORE_SECURITY) }.getOrNull()
}

internal object EngineHybridContentResolver {
    fun install(
        provider: ContentProvider,
        routingResolver: ContentResolver,
        systemResolver: ContentResolver
    ): ContentResolver {
        if (routingResolver === systemResolver) return ContentResolver.wrap(provider)
        val installed = runCatching {
            AndroidCompat.bypassHiddenApis()
            val wrappedField = findField(ContentResolver::class.java, "mWrapped")
                ?: return@runCatching false
            wrappedField.isAccessible = true
            wrappedField.set(routingResolver, provider)
            wrappedField.get(routingResolver) === provider
        }.getOrDefault(false)
        return if (installed) routingResolver else ContentResolver.wrap(provider)
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
    private val systemResolver: ContentResolver? = null,
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
    },
    private val routeConsumer: (
        EngineProviderRouteTokenConsumeRequest
    ) -> EngineProviderRouteTokenAuthorityResult? =
        EngineRuntimeIpcClients::validateAndConsumeProviderRouteToken
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
            systemCall = { hostResolver().query(uri, projection, selection, selectionArgs, sortOrder) },
            blocked = { null },
            virtualCall = { proxyUri ->
                hostResolver().query(proxyUri, projection, selection, selectionArgs, sortOrder)
            }
        )
    }

    override fun getType(uri: Uri): String? {
        return routeProvider(uri, "getType", { hostResolver().getType(uri) }, { null }) { proxyUri ->
            hostResolver().getType(proxyUri)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return routeProvider(uri, "insert", { hostResolver().insert(uri, values) }, { null }) { proxyUri ->
            hostResolver().insert(proxyUri, values)
        }
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        return routeProvider(uri, "bulkInsert", { hostResolver().bulkInsert(uri, values) }, { 0 }) { proxyUri ->
            hostResolver().bulkInsert(proxyUri, values)
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
        return routeProvider(uri, "delete", { hostResolver().delete(uri, selection, selectionArgs) }, { 0 }) { proxyUri ->
            hostResolver().delete(proxyUri, selection, selectionArgs)
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
            { hostResolver().update(uri, values, selection, selectionArgs) },
            { 0 }
        ) { proxyUri -> hostResolver().update(proxyUri, values, selection, selectionArgs) }
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
            { hostResolver().call(authority, method, arg, extras) },
            { null }
        ) { proxyUri ->
            hostResolver().call(proxyUri, method, arg, extras.withProviderProxyRoute(proxyUri))
        }
    }

    override fun canonicalize(uri: Uri): Uri? {
        return routeProvider(uri, "canonicalize", { hostResolver().canonicalize(uri) }, { null }) { proxyUri ->
            hostResolver().canonicalize(proxyUri)
        }
    }

    override fun uncanonicalize(uri: Uri): Uri? {
        return routeProvider(uri, "uncanonicalize", { hostResolver().uncanonicalize(uri) }, { null }) { proxyUri ->
            hostResolver().uncanonicalize(proxyUri)
        }
    }

    override fun refresh(uri: Uri, extras: Bundle?, cancellationSignal: CancellationSignal?): Boolean {
        return routeProvider(
            uri,
            "refresh",
            { hostResolver().refresh(uri, extras, cancellationSignal) },
            { false }
        ) { proxyUri -> hostResolver().refresh(proxyUri, extras, cancellationSignal) }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        return routeProvider(
            uri,
            "openFile:$mode",
            { hostResolver().openFileDescriptor(uri, mode) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { proxyUri -> hostResolver().openFileDescriptor(proxyUri, mode) }
    }

    @Throws(FileNotFoundException::class)
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        return routeProvider(
            uri,
            "openAssetFile:$mode",
            { hostResolver().openAssetFileDescriptor(uri, mode) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { proxyUri -> hostResolver().openAssetFileDescriptor(proxyUri, mode) }
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
            { hostResolver().openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { proxyUri -> hostResolver().openTypedAssetFileDescriptor(proxyUri, mimeTypeFilter, opts) }
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
            { hostResolver().openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts, signal) },
            { throw FileNotFoundException("virtual_provider_route_blocked:${uri.authority}") }
        ) { proxyUri ->
            hostResolver().openTypedAssetFileDescriptor(proxyUri, mimeTypeFilter, opts, signal)
        }
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
            return hostResolver().applyBatch(authority, operations)
        }
        if (routes.any { !it.resolution.virtualAuthority }) {
            throw OperationApplicationException("virtual_provider_batch_route_mixed")
        }
        val targetInstanceId = routes.first().resolution.targetInstanceId
            ?: throw OperationApplicationException("virtual_provider_batch_target_missing:0")
        if (routes.any { it.resolution.targetInstanceId != targetInstanceId }) {
            throw OperationApplicationException("virtual_provider_batch_target_mismatch")
        }

        throw OperationApplicationException(
            "virtual_provider_batch_requires_target_stub_routing:$targetInstanceId"
        )
    }

    private inline fun <T> routeProvider(
        uri: Uri,
        operationName: String,
        systemCall: () -> T,
        blocked: () -> T,
        virtualCall: (Uri) -> T
    ): T {
        val operation = EngineProviderOperation.fromOperationName(operationName)
        val accessMode = operationName.substringAfter(':', "").takeIf { it.isNotBlank() }
        val resolution = authorityResolver.resolve(uri, operation, accessMode)
        if (!resolution.virtualAuthority) return systemCall()
        val targetInstanceId = resolution.targetInstanceId ?: return blocked()
        val proxyUri = proxyUriForRoute(uri, operationName, targetInstanceId) ?: return blocked()
        return virtualCall(proxyUri)
    }

    private fun proxyUriForRoute(
        uri: Uri,
        operationName: String,
        targetInstanceId: String
    ): Uri? {
        val authority = uri.authority?.takeIf { it.isNotBlank() }
            ?: return null
        if (targetInstanceId == config.instanceId && authority !in guestAuthorities()) {
            return null
        }
        val route = runCatching {
            routeIssuer(
                config.instanceId,
                targetInstanceId,
                authority,
                operationName,
                null
            ).toEngineRoute()
        }.getOrNull() ?: return null
        if (
            route.callerInstanceId != config.instanceId ||
            route.targetInstanceId != targetInstanceId ||
            route.authority != authority ||
            route.operation != normalizeProviderRouteOperation(operationName) ||
            route.processSlot.isNullOrBlank()
        ) {
            return null
        }
        return uri.toProxyUri(route)
    }

    private fun guestAuthorities(): Set<String> = config.packageSnapshot
        ?.providers
        .orEmpty()
        .flatMapTo(linkedSetOf()) { it.authorities }

    private fun hostResolver(): ContentResolver = systemResolver ?: hostContext.contentResolver

    private fun Bundle?.withProviderProxyRoute(proxyUri: Uri): Bundle =
        Bundle(this ?: Bundle()).apply {
            putString(
                ProviderRouteContract.PROXY_INSTANCE_ID,
                proxyUri.getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID)
            )
            putString(
                ProviderRouteContract.PROXY_GUEST_AUTHORITY,
                proxyUri.getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
            )
            putString(
                ProviderRouteContract.PROXY_PROCESS_SLOT,
                proxyUri.getQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT)
            )
            putString(
                ProviderRouteContract.PROXY_ROUTE_TOKEN,
                proxyUri.getQueryParameter(ProviderRouteContract.PROXY_ROUTE_TOKEN)
            )
        }

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
