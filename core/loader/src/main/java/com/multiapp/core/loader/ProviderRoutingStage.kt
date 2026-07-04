package com.multiapp.core.loader

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
            passThroughHookAllowed = providerHookInstallEnabled
        )
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
                evidence = providerRoutingPlan.toEvidence() + providerHookInstallResult.toEvidence(),
                durationMs = durationMs
            )
        )
    }
}
