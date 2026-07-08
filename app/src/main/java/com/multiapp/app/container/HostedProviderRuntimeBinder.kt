package com.multiapp.app.container

import android.content.Context
import android.net.Uri
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualProviderManager

class HostedProviderRuntimeBinder(
    private val runtime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val bootstrapRunner: (Context, String, String?) -> HostedBootstrapResult = { context, instanceId, processSlot ->
        runHostedRuntimeBootstrap(context, instanceId, processSlot = processSlot)
    }
) {
    fun ensureBound(hostContext: Context, proxyUri: Uri): HostedProviderRuntimeBindResult {
        val instanceId = proxyUri.getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return HostedProviderRuntimeBindResult.NotRequested("missingProviderProxyInstanceId")
        val guestAuthority = proxyUri.getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
            ?.takeIf { it.isNotBlank() }
            ?: return HostedProviderRuntimeBindResult.NotRequested("missingProviderProxyGuestAuthority")
        val processSlot = proxyUri.getQueryParameter(VirtualProviderManager.PROXY_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }

        runtime.reusableResult(instanceId)?.let { result ->
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
            val applicationContext = hostContext.applicationContext ?: hostContext
            val result = runtime.bindApplication(instanceId) {
                bootstrapRunner(applicationContext, instanceId, processSlot)
            }
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
        val result: HostedBootstrapResult,
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
