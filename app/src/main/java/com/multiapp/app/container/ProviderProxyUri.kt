package com.multiapp.app.container

import android.net.Uri
import com.multiapp.core.model.engine.ProviderRouteContract

internal object ProviderProxyUri {

    private val proxyParameterNames = setOf(
        ProviderRouteContract.PROXY_INSTANCE_ID,
        ProviderRouteContract.PROXY_GUEST_AUTHORITY,
        ProviderRouteContract.PROXY_PROCESS_SLOT,
        ProviderRouteContract.PROXY_ROUTE_TOKEN
    )

    fun toGuestUri(uri: Uri, guestAuthority: String): Uri =
        uri.buildUpon()
            .authority(guestAuthority)
            .encodedQuery(rewriteEncodedQuery(uri.encodedQuery))
            .build()

    internal fun rewriteEncodedQuery(encodedQuery: String?): String? {
        if (encodedQuery.isNullOrEmpty()) return null
        val remaining = encodedQuery
            .split("&")
            .filterNot { part -> proxyParameterNames.contains(part.substringBefore("=")) }
        return remaining.takeIf { it.isNotEmpty() }?.joinToString("&")
    }
}
