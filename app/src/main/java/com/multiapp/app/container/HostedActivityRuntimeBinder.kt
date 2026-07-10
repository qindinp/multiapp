package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineRuntimeAuthorityValidator
import com.multiapp.core.engine.EngineRuntimeIpcClients
import com.multiapp.core.engine.EngineRuntimeIpcSnapshot
import com.multiapp.core.engine.HostedRuntimeEngine

class HostedActivityRuntimeBinder(
    private val runtimeEngineFactory: (Context) -> HostedRuntimeEngine = ::hostedRuntimeEngineFrom,
    private val authorityQuery: (String) -> EngineRuntimeIpcSnapshot? = EngineRuntimeIpcClients::queryRuntime
) {
    fun ensureBound(hostContext: Context, instanceId: String?): HostedActivityRuntimeBindResult {
        val requestedInstanceId = instanceId
            ?.takeIf { it.isNotBlank() }
            ?: return HostedActivityRuntimeBindResult.NotRequested("missingActivityProxyInstanceId")

        val authority = EngineRuntimeAuthorityValidator.validate(authorityQuery(requestedInstanceId))
        if (!authority.allowed) {
            return HostedActivityRuntimeBindResult.Failed(
                instanceId = requestedInstanceId,
                errorClassName = SecurityException::class.java.name,
                errorMessage = authority.reason,
                detail = "engineRuntimeAuthorityRejected"
            )
        }

        val runtimeEngine = runtimeEngineFactory(hostContext)
        runtimeEngine.reusableResult(requestedInstanceId)?.let { result ->
            return HostedActivityRuntimeBindResult.Bound(
                instanceId = requestedInstanceId,
                result = result,
                status = "CACHED",
                detail = "runtimeAlreadyReusable"
            )
        }

        return runCatching {
            val result = runtimeEngine.bindApplication(instanceId = requestedInstanceId).result
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
        val result: EngineHostedBootstrapResult,
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
