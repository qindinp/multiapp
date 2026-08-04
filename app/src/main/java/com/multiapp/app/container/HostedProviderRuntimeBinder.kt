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

        // B 类 self-provider 兼容（2026-08-03）：guest Application.onCreate 期间的工作线程
        // 查询自营 provider 时，bootstrap 仍在 BINDING 阶段。此时不得调用 bindApplication()
        // 等待 bootstrap 完成——bootstrap 完成依赖 onCreate 返回，而 onCreate 正在等待本
        // 查询返回，会互相等待死锁（微博实测 30s READY 超时）。provisional 记录中的
        // guestClassLoader 与 package snapshot 在 Application 创建前已就绪，可直接分发。
        runtimeEngine.providerDispatchResult(instanceId)?.let { result ->
            return HostedProviderRuntimeBindResult.Bound(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                processSlot = processSlot,
                result = result,
                status = "PROVISIONAL",
                detail = "runtimeBoundForProviderProxyWhileBinding"
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
