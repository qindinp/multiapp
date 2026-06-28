package com.multiapp.core.loader

import android.net.Uri
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

/**
 * Rewrites guest provider authorities to the host-declared stub provider.
 *
 * Android's public ContentResolver operations are final on modern SDK stubs,
 * so the production call-site must be a hook/proxy layer around provider
 * acquisition rather than a ContentResolver subclass. This class keeps the
 * authority mapping deterministic and testable for that runtime layer.
 */
class VirtualProviderUriRewriter(
    private val hostPackageName: String,
    private val providerManagerFactory: (String) -> VirtualProviderManager = { VirtualProviderManager(it) }
) {
    private val providerManager by lazy(LazyThreadSafetyMode.NONE) {
        providerManagerFactory(hostPackageName)
    }

    fun rewrite(snapshot: VirtualPackageSnapshot, uri: Uri): VirtualProviderUriRewrite? =
        providerManager.rewriteUri(snapshot, uri)

    fun rewriteAuthority(snapshot: VirtualPackageSnapshot, authority: String): VirtualProviderAuthorityRewrite? {
        val resolution = providerManager.resolve(snapshot, authority) ?: return null
        return VirtualProviderAuthorityRewrite(
            originalAuthority = authority,
            proxyAuthority = resolution.proxyAuthority,
            resolution = resolution
        )
    }
}

data class VirtualProviderAuthorityRewrite(
    val originalAuthority: String,
    val proxyAuthority: String,
    val resolution: VirtualProviderResolution
)

class VirtualProviderAuthorityMapFactory(
    private val hostPackageName: String,
    private val rewriter: VirtualProviderUriRewriter = VirtualProviderUriRewriter(hostPackageName)
) {
    fun create(snapshot: VirtualPackageSnapshot): Map<String, String> {
        val mappings = linkedMapOf<String, String>()
        snapshot.providers
            .flatMap { it.authorities }
            .distinct()
            .forEach { authority ->
                val rewrite = rewriter.rewriteAuthority(snapshot, authority)
                if (rewrite != null) {
                    mappings[authority] = rewrite.proxyAuthority
                }
            }
        return mappings
    }
}
