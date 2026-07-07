package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime

class HostedActivityRuntimeBinder(
    private val runtime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val bootstrapRunner: (Context, String) -> HostedBootstrapResult = ::runHostedRuntimeBootstrap
) {
    fun ensureBound(hostContext: Context, instanceId: String?): HostedActivityRuntimeBindResult {
        val requestedInstanceId = instanceId
            ?.takeIf { it.isNotBlank() }
            ?: return HostedActivityRuntimeBindResult.NotRequested("missingActivityProxyInstanceId")

        runtime.reusableResult(requestedInstanceId)?.let { result ->
            return HostedActivityRuntimeBindResult.Bound(
                instanceId = requestedInstanceId,
                result = result,
                status = "CACHED",
                detail = "runtimeAlreadyReusable"
            )
        }

        return runCatching {
            val applicationContext = hostContext.applicationContext ?: hostContext
            val result = runtime.bindApplication(requestedInstanceId) {
                bootstrapRunner(applicationContext, requestedInstanceId)
            }
            HostedActivityRuntimeBindResult.Bound(
                instanceId = requestedInstanceId,
                result = result,
                status = "BOUND",
                detail = "runtimeBoundForActivityProxy"
            )
        }.getOrElse { error ->
            HostedActivityRuntimeBindResult.Failed(
                instanceId = requestedInstanceId,
                errorClassName = error.javaClass.name,
                errorMessage = error.message,
                detail = "runtimeBindFailed"
            )
        }
    }
}

sealed class HostedActivityRuntimeBindResult {
    abstract val status: String
    abstract val detail: String

    data class Bound(
        val instanceId: String,
        val result: HostedBootstrapResult,
        override val status: String,
        override val detail: String
    ) : HostedActivityRuntimeBindResult()

    data class Failed(
        val instanceId: String,
        val errorClassName: String,
        val errorMessage: String?,
        override val detail: String
    ) : HostedActivityRuntimeBindResult() {
        override val status: String = "FAILED"
    }

    data class NotRequested(
        override val detail: String
    ) : HostedActivityRuntimeBindResult() {
        override val status: String = "NOT_REQUESTED"
    }
}
