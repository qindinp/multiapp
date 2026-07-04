package com.multiapp.core.loader

import android.content.pm.ProviderInfo
import android.net.Uri
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
    private val stubAuthorityPrefix: String = "$hostPackageName.multiapp.provider.stub"
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
            providerInfo = providerInfo(snapshot, provider, authority)
        )
    }

    fun rewriteUri(snapshot: VirtualPackageSnapshot, uri: Uri): VirtualProviderUriRewrite? {
        val authority = uri.authority ?: return null
        val resolution = resolve(snapshot, authority) ?: return null
        val rewritten = uri.buildUpon()
            .authority(resolution.proxyAuthority)
            .appendQueryParameter(PROXY_INSTANCE_ID, snapshot.instanceId)
            .appendQueryParameter(PROXY_GUEST_AUTHORITY, authority)
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

    private fun proxyAuthority(snapshot: VirtualPackageSnapshot, guestAuthority: String): String {
        return stubAuthorityPrefix
    }

    companion object {
        const val PROXY_INSTANCE_ID = "multiapp_instanceId"
        const val PROXY_GUEST_AUTHORITY = "multiapp_guestAuthority"
    }

    private fun providerInfo(
        snapshot: VirtualPackageSnapshot,
        provider: ResolvedComponent,
        authority: String
    ): ProviderInfo = VirtualPackageInfoFactory.providerInfo(snapshot, provider)?.apply {
        this.authority = authority
        this.packageName = snapshot.originPackageName
        this.name = provider.name
        this.applicationInfo = VirtualPackageInfoFactory.applicationInfo(snapshot)
    } ?: ProviderInfo().apply {
        this.packageName = snapshot.originPackageName
        this.name = provider.name
        this.authority = authority
        this.exported = provider.exported
        this.readPermission = provider.permission
        this.writePermission = provider.permission
        this.grantUriPermissions = provider.grantUriPermissions
        this.applicationInfo = VirtualPackageInfoFactory.applicationInfo(snapshot)
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
