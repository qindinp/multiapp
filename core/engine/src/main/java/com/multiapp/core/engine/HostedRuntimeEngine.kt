package com.multiapp.core.engine

import android.content.Context
import com.multiapp.core.loader.ActivityThreadCompat
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.HostedRuntimeBootstrap
import com.multiapp.core.loader.HostedRuntimeBindingFingerprint
import com.multiapp.core.loader.MainLooperApplicationThreadRunner
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.installer.InstallRecordStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface HostedRuntimeEngine {
    fun reusableResult(instanceId: String): EngineHostedBootstrapResult?
    fun reusableResult(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): EngineHostedBootstrapResult? = reusableResult(instanceId)
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
                File(hostContext.filesDir, EngineRuntimeStateFiles.DEFAULT_FILE_NAME)
            )
        )

    override fun reusableResult(instanceId: String): EngineHostedBootstrapResult? {
        val fingerprint = bindingFingerprint(
            instanceId = instanceId,
            providerHookEnabled = false,
            processSlot = null
        ) ?: return null
        return processRuntime.reusableResult(instanceId, fingerprint)
    }

    override fun reusableResult(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): EngineHostedBootstrapResult? {
        val fingerprint = bindingFingerprint(instanceId, providerHookEnabled, processSlot) ?: return null
        return processRuntime.reusableResult(instanceId, fingerprint)
    }

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
            applicationThreadRunner = MainLooperApplicationThreadRunner(),
            applicationOnCreateInvoker = { application ->
                val activityThread = ActivityThreadCompat.currentActivityThread()
                ActivityThreadCompat.getInstrumentation(activityThread)
                    .callApplicationOnCreate(application)
            },
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
        val fingerprint = requireNotNull(
            bindingFingerprint(instanceId, providerHookEnabled, processSlot)
        ) {
            "Unable to build hosted runtime binding fingerprint: instanceId=$instanceId"
        }
        return processRuntime.bindApplication(instanceId, fingerprint) {
            EngineHostedBootstrapResult.fromLoader(
                runBootstrapLoader(instanceId, providerHookEnabled, processSlot)
            )
        }
    }

    private fun bindingFingerprint(
        instanceId: String,
        providerHookEnabled: Boolean,
        processSlot: String?
    ): HostedRuntimeBindingFingerprint? {
        val instance = instanceManager.getInstance(instanceId) ?: return null
        val installRecord = installRecordStore.load(instance.originPackageName) ?: return null
        val engineRuntime = engineRuntimeRegistry.get(instanceId)
        val resolvedProcessSlot = processSlot?.takeIf { it.isNotBlank() }
            ?: engineRuntime?.processSlot?.takeIf { it.isNotBlank() }
            ?: return null
        return HostedRuntimeBindingFingerprint(
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            processSlot = resolvedProcessSlot,
            dataRoot = File(instance.dataRoot).absoluteFile.normalize().path,
            versionCode = installRecord.versionCode,
            baseApkPath = File(installRecord.originApkPath).absoluteFile.normalize().path,
            baseApkSha256 = installRecord.originApkSha256,
            splitApkPaths = installRecord.splitApkPaths.map { path ->
                File(path).absoluteFile.normalize().path
            },
            splitApkSha256s = installRecord.splitApkSha256s.toList(),
            applicationClassName = installRecord.applicationClassName,
            engineProfile = engineRuntime?.profile?.name ?: "UNKNOWN",
            providerHookEnabled = providerHookEnabled
        )
    }
}
