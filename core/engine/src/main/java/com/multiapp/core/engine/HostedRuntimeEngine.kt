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
    fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean = true,
        processSlot: String? = null
    ): HostedBootstrapResult

    fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean = true,
        processSlot: String? = null
    ): HostedRuntimeBindOutcome
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

    override fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): HostedBootstrapResult {
        val resolvedProcessSlot = processSlot
            ?.takeIf { it.isNotBlank() }
            ?: EngineRuntimeRegistry.global.get(instanceId)?.processSlot
        return HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = installRecordStore,
            hostContext = hostContext,
            providerHookInstallEnabled = providerHookEnabled
        ).run(instanceId, resolvedProcessSlot)
    }

    override fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): HostedRuntimeBindOutcome {
        var ranBootstrapOnThisThread = false
        val result = runtime.bindApplication(instanceId) {
            ranBootstrapOnThisThread = true
            runBootstrap(instanceId, providerHookEnabled, processSlot)
        }
        return HostedRuntimeBindOutcome(
            result = result,
            ranBootstrapOnThisThread = ranBootstrapOnThisThread
        )
    }
}
