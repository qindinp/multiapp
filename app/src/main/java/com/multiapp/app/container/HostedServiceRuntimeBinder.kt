package com.multiapp.app.container

import android.content.Context
import android.content.Intent
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualServiceManager
import com.multiapp.core.loader.VirtualServiceStartRequest

class HostedServiceRuntimeBinder(
    private val runtime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val serviceManagerFactory: (String) -> VirtualServiceManager = { hostPackageName ->
        VirtualServiceManager(hostPackageName)
    },
    private val requestDecoder: (String, Intent) -> VirtualServiceStartRequest? = { hostPackageName, intent ->
        serviceManagerFactory(hostPackageName).requestFromProxyIntent(intent)
    },
    private val bootstrapRunner: (Context, String, String?) -> HostedBootstrapResult = { context, instanceId, processSlot ->
        runHostedRuntimeBootstrap(context, instanceId, processSlot = processSlot)
    }
) {
    fun ensureBound(hostContext: Context, proxyIntent: Intent?): HostedServiceRuntimeBindResult {
        val request = proxyIntent
            ?.let { requestDecoder(hostContext.packageName, it) }
            ?: return HostedServiceRuntimeBindResult.NotRequested("missingServiceProxyRequest")

        return ensureBound(hostContext, request)
    }

    fun ensureBound(hostContext: Context, request: VirtualServiceStartRequest): HostedServiceRuntimeBindResult {
        runtime.reusableResult(request.instanceId)?.let { result ->
            if (!request.processSlot.isNullOrBlank() && result.processSlot != request.processSlot) {
                return HostedServiceRuntimeBindResult.Failed(
                    instanceId = request.instanceId,
                    processSlot = request.processSlot,
                    errorClassName = IllegalStateException::class.java.name,
                    errorMessage = "cached runtime processSlot mismatch: expected=${request.processSlot} actual=${result.processSlot}",
                    detail = "runtimeProcessSlotMismatch"
                )
            }
            return HostedServiceRuntimeBindResult.Bound(
                instanceId = request.instanceId,
                processSlot = request.processSlot,
                result = result,
                status = "CACHED",
                detail = "runtimeAlreadyReusable"
            )
        }

        return runCatching {
            val applicationContext = hostContext.applicationContext ?: hostContext
            val result = runtime.bindApplication(request.instanceId) {
                bootstrapRunner(applicationContext, request.instanceId, request.processSlot)
            }
            HostedServiceRuntimeBindResult.Bound(
                instanceId = request.instanceId,
                processSlot = request.processSlot,
                result = result,
                status = "BOUND",
                detail = "runtimeBoundForServiceProxy"
            )
        }.getOrElse { error ->
            HostedServiceRuntimeBindResult.Failed(
                instanceId = request.instanceId,
                processSlot = request.processSlot,
                errorClassName = error.javaClass.name,
                errorMessage = error.message,
                detail = "runtimeBindFailed"
            )
        }
    }
}

sealed class HostedServiceRuntimeBindResult {
    abstract val status: String
    abstract val detail: String

    data class Bound(
        val instanceId: String,
        val processSlot: String?,
        val result: HostedBootstrapResult,
        override val status: String,
        override val detail: String
    ) : HostedServiceRuntimeBindResult()

    data class Failed(
        val instanceId: String,
        val processSlot: String?,
        val errorClassName: String,
        val errorMessage: String?,
        override val detail: String
    ) : HostedServiceRuntimeBindResult() {
        override val status: String = "FAILED"
    }

    data class NotRequested(
        override val detail: String
    ) : HostedServiceRuntimeBindResult() {
        override val status: String = "NOT_REQUESTED"
    }
}
