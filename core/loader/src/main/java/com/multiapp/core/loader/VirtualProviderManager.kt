package com.multiapp.core.loader

import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Process
import com.multiapp.core.identity.ProviderRouteTokenRegistry
import com.multiapp.core.model.engine.ProviderRouteContract
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Minimal hosted-container provider registry.
 *
 * VirtualApp/DroidPlugin style runtimes route guest ContentProvider access
 * through host-declared stub providers. This manager establishes the authority
 * mapping used by the dispatcher/runtime so guest provider access does not
 * silently fall through to host/system providers.
 */
class VirtualProviderManager(
    private val hostPackageName: String,
    private val stubAuthorityPrefix: String = "$hostPackageName.multiapp.provider.stub",
    private val processSlot: String? = null,
    private val runtimeUidProvider: () -> Int = { RuntimeUidCompat.resolve() }
) {
    fun resolve(snapshot: VirtualPackageSnapshot, authority: String): VirtualProviderResolution? {
        val provider = snapshot.providers.firstOrNull { authority in it.authorities } ?: return null
        return VirtualProviderResolution(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            guestAuthority = authority,
            proxyAuthority = proxyAuthority(snapshot, authority),
            providerClassName = provider.name,
            providerInfo = providerInfo(snapshot, provider, authority),
            policy = VirtualProviderPolicy.fromComponent(provider)
        )
    }

    fun rewriteUri(
        snapshot: VirtualPackageSnapshot,
        uri: Uri,
        operation: String = "query"
    ): VirtualProviderUriRewrite? {
        val authority = uri.authority ?: return null
        val resolution = resolve(snapshot, authority) ?: return null
        val resolvedProcessSlot = processSlotForSnapshot(snapshot)
        val routeToken = ProviderRouteTokenRegistry.issue(
            callerInstanceId = snapshot.instanceId,
            targetInstanceId = snapshot.instanceId,
            authority = authority,
            operation = operation,
            processSlot = resolvedProcessSlot
        ).token
        val rewritten = uri.buildUpon()
            .authority(resolution.proxyAuthority)
            .appendQueryParameter(PROXY_INSTANCE_ID, snapshot.instanceId)
            .appendQueryParameter(PROXY_GUEST_AUTHORITY, authority)
            .apply {
                if (!resolvedProcessSlot.isNullOrBlank()) {
                    appendQueryParameter(PROXY_PROCESS_SLOT, resolvedProcessSlot)
                }
            }
            .appendQueryParameter(PROXY_ROUTE_TOKEN, routeToken)
            .build()
        return VirtualProviderUriRewrite(
            originalUri = uri,
            rewrittenUri = rewritten,
            resolution = resolution
        )
    }

    fun openProvider(snapshot: VirtualPackageSnapshot, authority: String): VirtualProviderOpenResult {
        val resolution = resolve(snapshot, authority)
            ?: return VirtualProviderOpenResult.NotFound(authority)
        return VirtualProviderOpenResult.Resolved(resolution)
    }

    private fun proxyAuthority(snapshot: VirtualPackageSnapshot, guestAuthority: String): String =
        proxyAuthorityForProcessSlot(processSlotForSnapshot(snapshot))

    private fun processSlotForSnapshot(snapshot: VirtualPackageSnapshot): String? =
        processSlot?.takeIf { it.isNotBlank() }
            ?: ProviderRouteTokenRegistry.processSlotForInstance(snapshot.instanceId)

    private fun proxyAuthorityForProcessSlot(processSlot: String?): String {
        val index = ProxyActivitySlots.processSlotIndex(hostPackageName, processSlot) ?: return stubAuthorityPrefix
        return "$stubAuthorityPrefix.v$index"
    }

    companion object {
        const val PROXY_INSTANCE_ID = ProviderRouteContract.PROXY_INSTANCE_ID
        const val PROXY_GUEST_AUTHORITY = ProviderRouteContract.PROXY_GUEST_AUTHORITY
        const val PROXY_PROCESS_SLOT = ProviderRouteContract.PROXY_PROCESS_SLOT
        const val PROXY_ROUTE_TOKEN = ProviderRouteContract.PROXY_ROUTE_TOKEN
    }

    private fun providerInfo(
        snapshot: VirtualPackageSnapshot,
        provider: ResolvedComponent,
        authority: String
    ): ProviderInfo {
        val runtimeUid = runtimeUidProvider()
        require(runtimeUid > 0) { "runtimeUid must be a positive Android application UID" }
        return VirtualPackageInfoFactory.providerInfo(
            snapshot,
            provider,
            runtimeUid,
            VirtualPackageQueryFlags.INTERNAL_FULL
        )?.apply {
        this.packageName = snapshot.originPackageName
        this.name = provider.name
        this.applicationInfo = VirtualPackageInfoFactory.applicationInfo(
            snapshot,
            runtimeUid,
            VirtualPackageQueryFlags.INTERNAL_FULL
        )
    } ?: ProviderInfo().apply {
        this.packageName = snapshot.originPackageName
        this.name = provider.name
        this.authority = provider.authorities.filter { it.isNotBlank() }.joinToString(";")
            .ifBlank { authority }
        this.exported = provider.exported
        this.readPermission = provider.readPermission ?: provider.permission
        this.writePermission = provider.writePermission ?: provider.permission
        this.grantUriPermissions = provider.grantUriPermissions || provider.uriPermissionPatterns.isNotEmpty()
        this.applicationInfo = VirtualPackageInfoFactory.applicationInfo(
            snapshot,
            runtimeUid,
            VirtualPackageQueryFlags.INTERNAL_FULL
        )
    }
    }
}

data class VirtualProviderResolution(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val guestAuthority: String,
    val proxyAuthority: String,
    val providerClassName: String,
    val providerInfo: ProviderInfo,
    val policy: VirtualProviderPolicy = VirtualProviderPolicy.fromProviderInfo(providerInfo)
)

data class VirtualProviderUriRewrite(
    val originalUri: Uri,
    val rewrittenUri: Uri,
    val resolution: VirtualProviderResolution
)

sealed class VirtualProviderOpenResult {
    data class Resolved(
        val resolution: VirtualProviderResolution
    ) : VirtualProviderOpenResult()

    data class NotFound(
        val authority: String
    ) : VirtualProviderOpenResult()
}
