package com.multiapp.app.container

import android.content.Context
import android.net.Uri
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineRuntimeAuthorityValidator
import com.multiapp.core.engine.EngineRuntimeIpcClients
import com.multiapp.core.engine.EngineRuntimeIpcSnapshot
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.model.engine.ProviderRouteContract

class HostedProviderRuntimeBinder(
    private val runtimeEngineFactory: (Context) -> HostedRuntimeEngine = ::hostedRuntimeEngineFrom,
    private val authorityQuery: (String) -> EngineRuntimeIpcSnapshot? = EngineRuntimeIpcClients::queryRuntime
) {
    fun ensureBound(hostContext: Context, proxyUri: Uri): HostedProviderRuntimeBindResult {
        val instanceId = proxyUri.getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return HostedProviderRuntimeBindResult.NotRequested("missingProviderProxyInstanceId")
        val guestAuthority = proxyUri.getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
            ?.takeIf { it.isNotBlank() }
            ?: return HostedProviderRuntimeBindResult.NotRequested("missingProviderProxyGuestAuthority")
        val processSlot = proxyUri.getQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }

        val authority = EngineRuntimeAuthorityValidator.validate(
            snapshot = authorityQuery(instanceId),
            expectedProcessSlot = processSlot
        )
        if (!authority.allowed) {
            return HostedProviderRuntimeBindResult.Failed(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                processSlot = processSlot,
                errorClassName = SecurityException::class.java.name,
                errorMessage = authority.reason,
                detail = "engineRuntimeAuthorityRejected"
            )
        }

        val runtimeEngine = runtimeEngineFactory(hostContext)
        runtimeEngine.reusableResult(instanceId)?.let { result ->
            if (!processSlot.isNullOrBlank() && result.processSlot != processSlot) {
                return HostedProviderRuntimeBindResult.Failed(
                    instanceId = instanceId,
                    guestAuthority = guestAuthority,
                    processSlot = processSlot,
                    errorClassName = IllegalStateException::class.java.name,
                    errorMessage = "cached runtime processSlot mismatch: expected=$processSlot actual=${result.processSlot}",
                    detail = "runtimeProcessSlotMismatch"
                )
            }
            return HostedProviderRuntimeBindResult.Bound(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                processSlot = processSlot,
                result = result,
                status = "CACHED",
                detail = "runtimeAlreadyReusable"
            )
        }

        return runCatching {
            val result = runtimeEngine.bindApplication(
                instanceId = instanceId,
                processSlot = processSlot
            ).result
            HostedProviderRuntimeBindResult.Bound(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                processSlot = processSlot,
                result = result,
                status = "BOUND",
                detail = "runtimeBoundForProviderProxy"
            )
        }.getOrElse { error ->
            HostedProviderRuntimeBindResult.Failed(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                processSlot = processSlot,
                errorClassName = error.javaClass.name,
                errorMessage = error.message,
                detail = "runtimeBindFailed"
            )
        }
    }
}

sealed class HostedProviderRuntimeBindResult {
    abstract val status: String
    abstract val detail: String

    data class Bound(
        val instanceId: String,
        val guestAuthority: String,
        val processSlot: String?,
        val result: EngineHostedBootstrapResult,
        override val status: String,
        override val detail: String
    ) : HostedProviderRuntimeBindResult()

    data class Failed(
        val instanceId: String,
        val guestAuthority: String,
        val processSlot: String?,
        val errorClassName: String,
        val errorMessage: String?,
        override val detail: String
    ) : HostedProviderRuntimeBindResult() {
        override val status: String = "FAILED"
    }

    data class NotRequested(
        override val detail: String
    ) : HostedProviderRuntimeBindResult() {
        override val status: String = "NOT_REQUESTED"
    }
}
