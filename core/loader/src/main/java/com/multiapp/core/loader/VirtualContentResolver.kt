package com.multiapp.core.loader

import android.net.Uri
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Named provider-resolution entry for v2 ContentResolver virtualization.
 *
 * Android's public ContentResolver operations are final on modern SDK stubs,
 * so this is not an Android ContentResolver subclass. It is the deterministic
 * authority/URI rewrite entry used by provider acquisition and routing evidence.
 */
class VirtualContentResolver(
    private val hostPackageName: String,
    private val providerManagerFactory: (String) -> VirtualProviderManager = { VirtualProviderManager(it) }
) {
    private val providerManager by lazy(LazyThreadSafetyMode.NONE) {
        providerManagerFactory(hostPackageName)
    }

    fun rewriteProviderUri(snapshot: VirtualPackageSnapshot, uri: Uri): VirtualProviderUriRewrite? =
        providerManager.rewriteUri(snapshot, uri)

    fun rewriteProviderAuthority(snapshot: VirtualPackageSnapshot, authority: String): VirtualProviderAuthorityRewrite? {
        val resolution = providerManager.resolve(snapshot, authority) ?: return null
        return VirtualProviderAuthorityRewrite(
            originalAuthority = authority,
            proxyAuthority = resolution.proxyAuthority,
            resolution = resolution
        )
    }
}

class VirtualProviderUriRewriter(
    private val virtualContentResolver: VirtualContentResolver
) {
    constructor(
        hostPackageName: String,
        providerManagerFactory: (String) -> VirtualProviderManager = { VirtualProviderManager(it) }
    ) : this(VirtualContentResolver(hostPackageName, providerManagerFactory))

    fun rewrite(snapshot: VirtualPackageSnapshot, uri: Uri): VirtualProviderUriRewrite? =
        virtualContentResolver.rewriteProviderUri(snapshot, uri)

    fun rewriteAuthority(snapshot: VirtualPackageSnapshot, authority: String): VirtualProviderAuthorityRewrite? =
        virtualContentResolver.rewriteProviderAuthority(snapshot, authority)
}

data class VirtualProviderAuthorityRewrite(
    val originalAuthority: String,
    val proxyAuthority: String,
    val resolution: VirtualProviderResolution
)

class VirtualProviderAuthorityMapFactory(
    private val hostPackageName: String,
    private val virtualContentResolver: VirtualContentResolver = VirtualContentResolver(hostPackageName)
) {
    fun create(snapshot: VirtualPackageSnapshot): Map<String, String> {
        val mappings = linkedMapOf<String, String>()
        snapshot.providers
            .flatMap { it.authorities }
            .distinct()
            .forEach { authority ->
                val rewrite = virtualContentResolver.rewriteProviderAuthority(snapshot, authority)
                if (rewrite != null) {
                    mappings[authority] = rewrite.proxyAuthority
                }
            }
        return mappings
    }
}
