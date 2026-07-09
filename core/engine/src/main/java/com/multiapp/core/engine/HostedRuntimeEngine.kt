package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.InstallRecordStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface HostedRuntimeEngine {
    fun reusableResult(instanceId: String): EngineHostedBootstrapResult?
    fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean = true,
        processSlot: String? = null
    ): EngineHostedBootstrapResult

    fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean = true,
        processSlot: String? = null
    ): HostedRuntimeBindOutcome
}

data class HostedRuntimeBindOutcome(
    val result: EngineHostedBootstrapResult,
    val ranBootstrapOnThisThread: Boolean
)

@Singleton
class DefaultHostedRuntimeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val instanceManager: InstanceManager,
    private val installRecordStore: InstallRecordStore
) : HostedRuntimeEngine {
    private val processRuntime: EngineHostedProcessRuntime = DefaultEngineHostedProcessRuntime()
    private val hostContext: Context
        get() = context.applicationContext ?: context
    private val engineRuntimeRegistry: EngineRuntimeRegistry =
        EngineRuntimeRegistry.global.attachStateStore(
            FileBackedEngineRuntimeStateStore(
                File(hostContext.filesDir, DefaultVirtualizationEngine.ENGINE_RUNTIME_STATE_FILE)
            )
        )

    override fun reusableResult(instanceId: String): EngineHostedBootstrapResult? =
        processRuntime.reusableResult(instanceId)

    override fun runBootstrap(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): EngineHostedBootstrapResult = EngineHostedBootstrapResult.fromLoader(
        runBootstrapLoader(instanceId, providerHookEnabled, processSlot)
    )

    private fun runBootstrapLoader(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): HostedBootstrapResult {
        val resolvedProcessSlot = processSlot
            ?.takeIf { it.isNotBlank() }
            ?: engineRuntimeRegistry.runtimeState(instanceId)?.processSlot
        return HostedRuntimeBootstrap(
            instanceManager = instanceManager,
            installRecordStore = installRecordStore,
            hostContext = hostContext,
            providerHookInstallEnabled = providerHookEnabled,
            processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
            activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
            runtimePublisher = { publishedInstanceId, result ->
                processRuntime.rememberApplication(
                    publishedInstanceId,
                    EngineHostedBootstrapResult.fromLoader(result)
                )
            }
        ).run(instanceId, resolvedProcessSlot)
    }

    override fun bindApplication(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): HostedRuntimeBindOutcome {
        return processRuntime.bindApplication(instanceId) {
            EngineHostedBootstrapResult.fromLoader(
                runBootstrapLoader(instanceId, providerHookEnabled, processSlot)
            )
        }
    }
}
