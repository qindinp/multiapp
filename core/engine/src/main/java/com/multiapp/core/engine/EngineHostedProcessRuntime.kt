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

    /**
     * 非阻塞获取 provider 分发可用的运行时记录（READY 记录或 BINDING 阶段的 provisional）。
     *
     * provider 分发在 guest Application.onCreate 期间就可能发生（如微博的工作线程
     * 查询自营 provider）。此时 bootstrap 仍在进行（BINDING），[reusableResult] 对
     * 非初始化线程返回 null，若分发因此去 [bindApplication] 会递归等待 bootstrap
     * 完成，而 bootstrap 又卡在 onCreate 等待该查询返回 → 死锁。
     *
     * 本方法只消费 provisional 中的 guestClassLoader 与 package snapshot，二者在
     * Application 创建前已就绪，无需等待 bootstrap 完成（B 类 self-provider 兼容，
     * 2026-08-03）。
     */
    fun providerDispatchResult(instanceId: String): EngineHostedBootstrapResult?
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

    override fun providerDispatchResult(instanceId: String): EngineHostedBootstrapResult? =
        runtime.recordForProviderDispatch(instanceId)?.result?.let(EngineHostedBootstrapResult::fromLoader)

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
