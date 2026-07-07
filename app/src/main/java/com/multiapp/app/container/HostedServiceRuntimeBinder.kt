package com.multiapp.app.container

import android.content.Context
import android.content.Intent
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualServiceManager
import com.multiapp.core.loader.VirtualServiceStartRequest
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore

class HostedServiceRuntimeBinder(
    private val runtime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val serviceManagerFactory: (String) -> VirtualServiceManager = { hostPackageName ->
        VirtualServiceManager(hostPackageName)
    },
    private val requestDecoder: (String, Intent) -> VirtualServiceStartRequest? = { hostPackageName, intent ->
        serviceManagerFactory(hostPackageName).requestFromProxyIntent(intent)
    },
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
    fun ensureBound(hostContext: Context, proxyIntent: Intent?): HostedServiceRuntimeBindResult {
        val request = proxyIntent
            ?.let { requestDecoder(hostContext.packageName, it) }
            ?: return HostedServiceRuntimeBindResult.NotRequested("missingServiceProxyRequest")

        return ensureBound(hostContext, request)
    }

    fun ensureBound(hostContext: Context, request: VirtualServiceStartRequest): HostedServiceRuntimeBindResult {
        runtime.reusableResult(request.instanceId)?.let { result ->
            return HostedServiceRuntimeBindResult.Bound(
                instanceId = request.instanceId,
                result = result,
                status = "CACHED",
                detail = "runtimeAlreadyReusable"
            )
        }

        return runCatching {
            val applicationContext = hostContext.applicationContext ?: hostContext
            val result = runtime.bindApplication(request.instanceId) {
                bootstrapRunner(applicationContext, request.instanceId)
            }
            HostedServiceRuntimeBindResult.Bound(
                instanceId = request.instanceId,
                result = result,
                status = "BOUND",
                detail = "runtimeBoundForServiceProxy"
            )
        }.getOrElse { error ->
            HostedServiceRuntimeBindResult.Failed(
                instanceId = request.instanceId,
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
        val result: HostedBootstrapResult,
        override val status: String,
        override val detail: String
    ) : HostedServiceRuntimeBindResult()

    data class Failed(
        val instanceId: String,
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
