package com.multiapp.app.container

import android.content.Context
import android.net.Uri
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualProviderManager
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore

class HostedProviderRuntimeBinder(
    private val runtime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val bootstrapRunner: (Context, String) -> HostedBootstrapResult = { hostContext, instanceId ->
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(hostContext))
        val instanceManager: InstanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(hostContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(hostContext),
            installRecordStore = installStore
        )
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = installStore,
            hostContext = hostContext,
            providerHookInstallEnabled = true
        )
        bootstrap.run(instanceId)
    }
) {
    fun ensureBound(hostContext: Context, proxyUri: Uri): HostedProviderRuntimeBindResult {
        val instanceId = proxyUri.getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return HostedProviderRuntimeBindResult.NotRequested("missingProviderProxyInstanceId")
        val guestAuthority = proxyUri.getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY)
            ?.takeIf { it.isNotBlank() }
            ?: return HostedProviderRuntimeBindResult.NotRequested("missingProviderProxyGuestAuthority")

        runtime.reusableResult(instanceId)?.let { result ->
            return HostedProviderRuntimeBindResult.Bound(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                result = result,
                status = "CACHED",
                detail = "runtimeAlreadyReusable"
            )
        }

        return runCatching {
            val applicationContext = hostContext.applicationContext ?: hostContext
            val result = runtime.bindApplication(instanceId) {
                bootstrapRunner(applicationContext, instanceId)
            }
            HostedProviderRuntimeBindResult.Bound(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
                result = result,
                status = "BOUND",
                detail = "runtimeBoundForProviderProxy"
            )
        }.getOrElse { error ->
            HostedProviderRuntimeBindResult.Failed(
                instanceId = instanceId,
                guestAuthority = guestAuthority,
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
        val result: HostedBootstrapResult,
        override val status: String,
        override val detail: String
    ) : HostedProviderRuntimeBindResult()

    data class Failed(
        val instanceId: String,
        val guestAuthority: String,
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
