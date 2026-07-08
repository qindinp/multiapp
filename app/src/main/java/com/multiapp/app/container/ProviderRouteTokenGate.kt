package com.multiapp.app.container

import android.net.Uri
import com.multiapp.core.identity.ProviderRouteToken
import com.multiapp.core.identity.ProviderRouteTokenRegistry
import com.multiapp.core.loader.VirtualProviderDispatchResult
import com.multiapp.core.loader.VirtualProviderManager

internal object ProviderRouteTokenGate {

    fun validate(
        uri: Uri,
        operationName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ProviderRouteTokenGateResult {
        val instanceId = uri.getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID)
        val guestAuthority = uri.getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
        val expectedProcessSlot = uri.getQueryParameter(VirtualProviderManager.PROXY_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }
        val token = uri.getQueryParameter(ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN)

        if (instanceId.isNullOrBlank()) {
            return ProviderRouteTokenGateResult.Invalid(
                VirtualProviderDispatchResult.InvalidProxyUri("missing instanceId")
            )
        }
        if (guestAuthority.isNullOrBlank()) {
            return ProviderRouteTokenGateResult.Invalid(
                VirtualProviderDispatchResult.InvalidProxyUri("missing guestAuthority")
            )
        }

        val result = ProviderRouteTokenRegistry.validate(
            token = token,
            callerInstanceId = instanceId,
            targetInstanceId = instanceId,
            authority = guestAuthority,
            operation = operationName,
            expectedProcessSlot = expectedProcessSlot,
            nowMillis = nowMillis
        )
        if (!result.isValid) {
            return ProviderRouteTokenGateResult.Invalid(
                VirtualProviderDispatchResult.InvalidProxyUri("invalid route token:${result.status.name}")
            )
        }

        val route = result.route ?: return ProviderRouteTokenGateResult.Invalid(
            VirtualProviderDispatchResult.InvalidProxyUri("invalid route token:ROUTE_MISSING")
        )
        return ProviderRouteTokenGateResult.Valid(
            route = route,
            canonicalProxyUri = canonicalProxyUri(uri, route)
        )
    }

    private fun canonicalProxyUri(uri: Uri, route: ProviderRouteToken): Uri {
        return uri.buildUpon()
            .encodedQuery(canonicalEncodedQuery(uri.encodedQuery, route))
            .build()
    }

    internal fun canonicalEncodedQueryForTest(encodedQuery: String?, route: ProviderRouteToken): String {
        return canonicalEncodedQuery(encodedQuery, route)
    }

    private fun canonicalEncodedQuery(encodedQuery: String?, route: ProviderRouteToken): String {
        val preservedGuestQuery = ProviderProxyUri.rewriteEncodedQuery(encodedQuery)
        return listOfNotNull(
            preservedGuestQuery,
            "${VirtualProviderManager.PROXY_INSTANCE_ID}=${route.targetInstanceId}",
            "${VirtualProviderManager.PROXY_GUEST_AUTHORITY}=${route.authority}",
            route.processSlot?.takeIf { it.isNotBlank() }?.let {
                "${VirtualProviderManager.PROXY_PROCESS_SLOT}=$it"
            },
            "${ProviderRouteTokenRegistry.PROXY_ROUTE_TOKEN}=${route.token}"
        ).joinToString("&")
    }
}

internal sealed class ProviderRouteTokenGateResult {
    data class Valid(
        val route: ProviderRouteToken,
        val canonicalProxyUri: Uri
    ) : ProviderRouteTokenGateResult()

    data class Invalid(
        val result: VirtualProviderDispatchResult.InvalidProxyUri
    ) : ProviderRouteTokenGateResult()
}
