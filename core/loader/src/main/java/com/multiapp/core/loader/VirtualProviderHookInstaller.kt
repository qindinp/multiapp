package com.multiapp.core.loader

import com.multiapp.core.hook.HookEngine
import com.multiapp.core.identity.ContentProviderHook
import com.multiapp.core.identity.ProviderAuthorityHookConfig

/** Installs the Java provider authority rewrite hook described by a routing plan. */
class VirtualProviderHookInstaller(
    private val hookEngineProvider: () -> HookEngine = { HookEngine.getInstance() },
    private val hook: ContentProviderHook = ContentProviderHook()
) {
    fun install(plan: VirtualProviderRoutingPlan): VirtualProviderHookInstallResult {
        if (!plan.enabled) {
            return VirtualProviderHookInstallResult.Skipped(plan, plan.reason)
        }
        if (plan.primaryStrategy != ProviderRoutingStrategy.CONTENT_RESOLVER_PASS_THROUGH_HOOK) {
            return VirtualProviderHookInstallResult.Skipped(
                plan,
                "PRIMARY_STRATEGY_NOT_PASS_THROUGH:${plan.primaryStrategy.name}"
            )
        }
        if (plan.authorityMap.isEmpty()) {
            return VirtualProviderHookInstallResult.Skipped(plan, "AUTHORITY_MAP_EMPTY")
        }

        return try {
            val hookEngine = hookEngineProvider()
            hookEngine.initLsplant(ContentProviderHook::class.java.classLoader ?: ClassLoader.getSystemClassLoader())
            val stats = hook.install(
                ProviderAuthorityHookConfig(
                    instanceId = plan.instanceId,
                    originalPackageName = plan.originPackageName,
                    authorityMap = plan.authorityMap
                ),
                hookEngine
            )
            if (stats.installedMethodCount > 0) {
                VirtualProviderHookInstallResult.Installed(
                    plan = plan,
                    authorityMapSize = plan.authorityMap.size,
                    installedMethodCount = stats.installedMethodCount,
                    attemptedMethodCount = stats.attemptedMethodCount
                )
            } else {
                VirtualProviderHookInstallResult.Skipped(
                    plan,
                    "NO_CONTENT_RESOLVER_HOOK_INSTALLED:${stats.attemptedMethodCount}"
                )
            }
        } catch (error: Throwable) {
            VirtualProviderHookInstallResult.Failed(plan, error)
        }
    }
}

sealed class VirtualProviderHookInstallResult {
    abstract val plan: VirtualProviderRoutingPlan

    fun toEvidence(): List<BootstrapEvidence> = when (this) {
        is Installed -> listOf(
            BootstrapEvidence("providerHookInstallStatus", "INSTALLED", SOURCE),
            BootstrapEvidence("providerHookInstallAuthorityMapSize", authorityMapSize.toString(), SOURCE),
            BootstrapEvidence("providerHookInstallMethodCount", installedMethodCount.toString(), SOURCE),
            BootstrapEvidence("providerHookInstallAttemptedMethodCount", attemptedMethodCount.toString(), SOURCE),
            BootstrapEvidence("providerHookInstallReason", plan.reason, SOURCE)
        )
        is Skipped -> listOf(
            BootstrapEvidence("providerHookInstallStatus", "SKIPPED", SOURCE),
            BootstrapEvidence("providerHookInstallAuthorityMapSize", "0", SOURCE),
            BootstrapEvidence("providerHookInstallReason", reason, SOURCE)
        )
        is Failed -> listOf(
            BootstrapEvidence("providerHookInstallStatus", "FAILED", SOURCE),
            BootstrapEvidence("providerHookInstallAuthorityMapSize", "0", SOURCE),
            BootstrapEvidence("providerHookInstallReason", error.message ?: error.javaClass.name, SOURCE),
            BootstrapEvidence("providerHookInstallErrorClass", error.javaClass.name, SOURCE)
        )
    }

    data class Installed(
        override val plan: VirtualProviderRoutingPlan,
        val authorityMapSize: Int,
        val installedMethodCount: Int,
        val attemptedMethodCount: Int
    ) : VirtualProviderHookInstallResult()

    data class Skipped(
        override val plan: VirtualProviderRoutingPlan,
        val reason: String
    ) : VirtualProviderHookInstallResult()

    data class Failed(
        override val plan: VirtualProviderRoutingPlan,
        val error: Throwable
    ) : VirtualProviderHookInstallResult()

    companion object {
        private const val SOURCE = "VirtualProviderHookInstaller"
    }
}
