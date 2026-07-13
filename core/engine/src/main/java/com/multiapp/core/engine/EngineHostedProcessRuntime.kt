package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.HostedRuntimeBindingFingerprint

internal object EngineHostedProcessRuntimeDefaults {
    val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global
    val loaderRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global
}

interface EngineHostedProcessRuntime {
    fun reusableResult(instanceId: String): EngineHostedBootstrapResult?
    fun reusableResult(
        instanceId: String,
        bindingFingerprint: HostedRuntimeBindingFingerprint
    ): EngineHostedBootstrapResult? = reusableResult(instanceId)

    fun bindApplication(
        instanceId: String,
        bootstrap: () -> EngineHostedBootstrapResult
    ): HostedRuntimeBindOutcome

    fun bindApplication(
        instanceId: String,
        bindingFingerprint: HostedRuntimeBindingFingerprint,
        bootstrap: () -> EngineHostedBootstrapResult
    ): HostedRuntimeBindOutcome = bindApplication(instanceId, bootstrap)

    fun rememberApplication(instanceId: String, result: EngineHostedBootstrapResult)
}

class DefaultEngineHostedProcessRuntime(
    private val runtime: VirtualProcessRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime
) : EngineHostedProcessRuntime {

    override fun reusableResult(instanceId: String): EngineHostedBootstrapResult? =
        runtime.reusableResult(instanceId)?.let(EngineHostedBootstrapResult::fromLoader)

    override fun reusableResult(
        instanceId: String,
        bindingFingerprint: HostedRuntimeBindingFingerprint
    ): EngineHostedBootstrapResult? =
        runtime.reusableResult(instanceId, bindingFingerprint)?.let(EngineHostedBootstrapResult::fromLoader)

    override fun bindApplication(
        instanceId: String,
        bootstrap: () -> EngineHostedBootstrapResult
    ): HostedRuntimeBindOutcome {
        var ranBootstrapOnThisThread = false
        val result = runtime.bindApplication(instanceId) {
            ranBootstrapOnThisThread = true
            bootstrap().loaderResult
        }
        return HostedRuntimeBindOutcome(
            result = EngineHostedBootstrapResult.fromLoader(result),
            ranBootstrapOnThisThread = ranBootstrapOnThisThread
        )
    }

    override fun bindApplication(
        instanceId: String,
        bindingFingerprint: HostedRuntimeBindingFingerprint,
        bootstrap: () -> EngineHostedBootstrapResult
    ): HostedRuntimeBindOutcome {
        var ranBootstrapOnThisThread = false
        val result = runtime.bindApplication(instanceId, bindingFingerprint) {
            ranBootstrapOnThisThread = true
            bootstrap().loaderResult
        }
        return HostedRuntimeBindOutcome(
            result = EngineHostedBootstrapResult.fromLoader(result),
            ranBootstrapOnThisThread = ranBootstrapOnThisThread
        )
    }

    override fun rememberApplication(instanceId: String, result: EngineHostedBootstrapResult) {
        runtime.rememberApplication(instanceId, result.loaderResult)
    }
}
