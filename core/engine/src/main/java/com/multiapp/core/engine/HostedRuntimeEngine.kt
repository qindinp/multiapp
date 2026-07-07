package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.InstallRecordStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface HostedRuntimeEngine {
    fun reusableResult(instanceId: String): HostedBootstrapResult?
    fun runBootstrap(instanceId: String, providerHookEnabled: Boolean = true): HostedBootstrapResult
    fun bindApplication(instanceId: String, providerHookEnabled: Boolean = true): HostedRuntimeBindOutcome
}

data class HostedRuntimeBindOutcome(
    val result: HostedBootstrapResult,
    val ranBootstrapOnThisThread: Boolean
)

@Singleton
class DefaultHostedRuntimeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val instanceManager: InstanceManager,
    private val installRecordStore: InstallRecordStore
) : HostedRuntimeEngine {
    private val runtime: VirtualProcessRuntime = VirtualProcessRuntime.global
    private val hostContext: Context
        get() = context.applicationContext ?: context

    override fun reusableResult(instanceId: String): HostedBootstrapResult? =
        runtime.reusableResult(instanceId)

    override fun runBootstrap(instanceId: String, providerHookEnabled: Boolean): HostedBootstrapResult {
        return HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = installRecordStore,
            hostContext = hostContext,
            providerHookInstallEnabled = providerHookEnabled
        ).run(instanceId)
    }

    override fun bindApplication(instanceId: String, providerHookEnabled: Boolean): HostedRuntimeBindOutcome {
        var ranBootstrapOnThisThread = false
        val result = runtime.bindApplication(instanceId) {
            ranBootstrapOnThisThread = true
            runBootstrap(instanceId, providerHookEnabled)
        }
        return HostedRuntimeBindOutcome(
            result = result,
            ranBootstrapOnThisThread = ranBootstrapOnThisThread
        )
    }
}
