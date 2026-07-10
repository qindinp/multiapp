package com.multiapp.core.engine

import android.net.Uri
import com.multiapp.core.identity.ProviderRouteToken
import com.multiapp.core.identity.ProviderRouteTokenRegistry
import com.multiapp.core.model.engine.ProviderRouteContract

data class EngineProviderRouteToken(
    val token: String,
    val callerInstanceId: String,
    val targetInstanceId: String,
    val authority: String,
    val operation: String,
    val expiresAtMillis: Long,
    val processSlot: String? = null
)

sealed class EngineProviderRouteTokenGateResult {
    data class Valid(
        val route: EngineProviderRouteToken,
        val canonicalProxyUri: Uri
    ) : EngineProviderRouteTokenGateResult()

    data class Invalid(
        val result: EngineProviderDispatchResult.InvalidProxyUri
    ) : EngineProviderRouteTokenGateResult()
}

object EngineProviderRouteTokenGate {
    private val proxyParameterNames = setOf(
        ProviderRouteContract.PROXY_INSTANCE_ID,
        ProviderRouteContract.PROXY_GUEST_AUTHORITY,
        ProviderRouteContract.PROXY_PROCESS_SLOT,
        ProviderRouteContract.PROXY_ROUTE_TOKEN
    )

    fun validate(
        uri: Uri,
        operationName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): EngineProviderRouteTokenGateResult {
        val instanceId = uri.getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID)
        val guestAuthority = uri.getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
        val expectedProcessSlot = uri.getQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }
        val token = uri.getQueryParameter(ProviderRouteContract.PROXY_ROUTE_TOKEN)

        if (instanceId.isNullOrBlank()) {
            return EngineProviderRouteTokenGateResult.Invalid(
                EngineProviderDispatchResult.InvalidProxyUri("missing instanceId")
            )
        }
        if (guestAuthority.isNullOrBlank()) {
            return EngineProviderRouteTokenGateResult.Invalid(
                EngineProviderDispatchResult.InvalidProxyUri("missing guestAuthority")
            )
        }
        val result = ProviderRouteTokenRegistry.validateTarget(
            token = token,
            targetInstanceId = instanceId,
            authority = guestAuthority,
            operation = operationName,
            expectedProcessSlot = expectedProcessSlot,
            nowMillis = nowMillis
        )
        if (!result.isValid) {
            return EngineProviderRouteTokenGateResult.Invalid(
                EngineProviderDispatchResult.InvalidProxyUri("invalid route token:${result.status.name}")
            )
        }
        val route = result.route?.toEngineRoute() ?: return EngineProviderRouteTokenGateResult.Invalid(
            EngineProviderDispatchResult.InvalidProxyUri("invalid route token:ROUTE_MISSING")
        )
        return EngineProviderRouteTokenGateResult.Valid(
            route = route,
            canonicalProxyUri = canonicalProxyUri(uri, route)
        )
    }

    fun routeTokenStatus(
        token: String?,
        instanceId: String?,
        guestAuthority: String?,
        operationName: String,
        expectedProcessSlot: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        return validateRouteTokenFields(
            token = token,
            instanceId = instanceId,
            guestAuthority = guestAuthority,
            operationName = operationName,
            expectedProcessSlot = expectedProcessSlot,
            nowMillis = nowMillis
        )?.reason ?: "VALID"
    }

    fun canonicalEncodedQueryForTest(
        encodedQuery: String?,
        route: EngineProviderRouteToken
    ): String = canonicalEncodedQuery(encodedQuery, route)

    fun rewriteEncodedQuery(encodedQuery: String?): String? {
        if (encodedQuery.isNullOrEmpty()) return null
        val remaining = encodedQuery
            .split("&")
            .filterNot { part -> proxyParameterNames.contains(part.substringBefore("=")) }
        return remaining.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    private fun validateRouteTokenFields(
        token: String?,
        instanceId: String?,
        guestAuthority: String?,
        operationName: String,
        expectedProcessSlot: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): EngineProviderDispatchResult.InvalidProxyUri? {
        if (instanceId.isNullOrBlank()) {
            return EngineProviderDispatchResult.InvalidProxyUri("missing instanceId")
        }
        if (guestAuthority.isNullOrBlank()) {
            return EngineProviderDispatchResult.InvalidProxyUri("missing guestAuthority")
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
        return if (result.isValid) {
            null
        } else {
            EngineProviderDispatchResult.InvalidProxyUri("invalid route token:${result.status.name}")
        }
    }

    private fun canonicalProxyUri(uri: Uri, route: EngineProviderRouteToken): Uri {
        return uri.buildUpon()
            .encodedQuery(canonicalEncodedQuery(uri.encodedQuery, route))
            .build()
    }

    private fun canonicalEncodedQuery(encodedQuery: String?, route: EngineProviderRouteToken): String {
        val preservedGuestQuery = rewriteEncodedQuery(encodedQuery)
        return listOfNotNull(
            preservedGuestQuery,
            "${ProviderRouteContract.PROXY_INSTANCE_ID}=${route.targetInstanceId}",
            "${ProviderRouteContract.PROXY_GUEST_AUTHORITY}=${route.authority}",
            route.processSlot?.takeIf { it.isNotBlank() }?.let {
                "${ProviderRouteContract.PROXY_PROCESS_SLOT}=$it"
            },
            "${ProviderRouteContract.PROXY_ROUTE_TOKEN}=${route.token}"
        ).joinToString("&")
    }

    private fun ProviderRouteToken.toEngineRoute(): EngineProviderRouteToken =
        EngineProviderRouteToken(
            token = token,
            callerInstanceId = callerInstanceId,
            targetInstanceId = targetInstanceId,
            authority = authority,
            operation = operation,
            expiresAtMillis = expiresAtMillis,
            processSlot = processSlot
        )
}
