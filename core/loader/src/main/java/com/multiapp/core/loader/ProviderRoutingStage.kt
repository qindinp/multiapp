package com.multiapp.core.loader

import com.multiapp.core.identity.ProviderRouteTokenRegistry

class ProviderRoutingStage(
    private val hostPackageName: String?,
    private val providerHookInstallEnabled: Boolean,
    private val providerHookInstaller: VirtualProviderHookInstaller,
    private val routingPlanFactory: VirtualProviderRoutingPlanFactory = VirtualProviderRoutingPlanFactory(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val packageSnapshot = input.packageSnapshot
        if (packageSnapshot == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.GUEST_CONTEXT,
                    message = "Package snapshot is required before provider routing",
                    durationMs = clock() - startMs
                )
            )
        }

        val providerRoutingPlan = routingPlanFactory.create(
            snapshot = packageSnapshot,
            hostPackageName = hostPackageName,
            processSlot = input.processSlot,
            passThroughHookAllowed = providerHookInstallEnabled
        )
        ProviderRouteTokenRegistry.rememberProcessSlot(packageSnapshot.instanceId, input.processSlot)
        val providerHookInstallResult = if (providerHookInstallEnabled) {
            providerHookInstaller.install(providerRoutingPlan)
        } else {
            VirtualProviderHookInstallResult.Skipped(providerRoutingPlan, "PROFILE_DISABLED")
        }
        val durationMs = clock() - startMs

        return BootstrapStageOutput(
            context = input.copy(providerRoutingPlan = providerRoutingPlan),
            result = BootstrapResult.success(
                stage = RuntimeStage.GUEST_CONTEXT,
                message = "Provider routing prepared: ${providerRoutingPlan.reason}",
                evidence = providerRoutingPlan.toEvidence(
                    contentResolverHookInstalled = providerHookInstallResult is VirtualProviderHookInstallResult.Installed
                ) + providerHookInstallResult.toEvidence(),
                durationMs = durationMs
            )
        )
    }
}
