package com.multiapp.app.container

import android.content.Context
import android.content.Intent
import com.multiapp.core.engine.DefaultEngineServiceRouter
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineServiceRouter
import com.multiapp.core.engine.EngineServiceStartRoute
import com.multiapp.core.engine.HostedRuntimeEngine

class HostedServiceRuntimeBinder(
    private val runtimeEngineFactory: (Context) -> HostedRuntimeEngine = ::hostedRuntimeEngineFrom,
    private val serviceRouter: EngineServiceRouter = DefaultEngineServiceRouter(),
    private val requestDecoder: (String, Intent) -> EngineServiceStartRoute? = { hostPackageName, intent ->
        serviceRouter.routeFromProxyIntent(hostPackageName, intent)
    }
) {
    fun ensureBound(hostContext: Context, proxyIntent: Intent?): HostedServiceRuntimeBindResult {
        val request = proxyIntent
            ?.let { requestDecoder(hostContext.packageName, it) }
            ?: return HostedServiceRuntimeBindResult.NotRequested("missingServiceProxyRequest")

        return ensureBound(hostContext, request)
    }

    fun ensureBound(hostContext: Context, route: EngineServiceStartRoute): HostedServiceRuntimeBindResult {
        val runtimeEngine = runtimeEngineFactory(hostContext)
        runtimeEngine.reusableResult(route.instanceId)?.let { result ->
            if (!route.processSlot.isNullOrBlank() && result.processSlot != route.processSlot) {
                return HostedServiceRuntimeBindResult.Failed(
                    instanceId = route.instanceId,
                    processSlot = route.processSlot,
                    errorClassName = IllegalStateException::class.java.name,
                    errorMessage = "cached runtime processSlot mismatch: expected=${route.processSlot} actual=${result.processSlot}",
                    detail = "runtimeProcessSlotMismatch"
                )
            }
            return HostedServiceRuntimeBindResult.Bound(
                instanceId = route.instanceId,
                processSlot = route.processSlot,
                result = result,
                status = "CACHED",
                detail = "runtimeAlreadyReusable"
            )
        }

        return runCatching {
            val result = runtimeEngine.bindApplication(
                instanceId = route.instanceId,
                processSlot = route.processSlot
            ).result
            HostedServiceRuntimeBindResult.Bound(
                instanceId = route.instanceId,
                processSlot = route.processSlot,
                result = result,
                status = "BOUND",
                detail = "runtimeBoundForServiceProxy"
            )
        }.getOrElse { error ->
            HostedServiceRuntimeBindResult.Failed(
                instanceId = route.instanceId,
                processSlot = route.processSlot,
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
        val result: EngineHostedBootstrapResult,
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
