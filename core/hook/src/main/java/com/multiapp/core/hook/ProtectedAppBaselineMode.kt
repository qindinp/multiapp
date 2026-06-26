package com.multiapp.core.hook

data class ProtectedAppBaselineMode(
    val lsPlantEnabled: Boolean = false,
    val xposedModulesEnabled: Boolean = false,
    val businessNativeStubsEnabled: Boolean = false,
    val methodReplacementPatchesEnabled: Boolean = false,
    val noOpPatchesEnabled: Boolean = false,
    val nativeDiagnosticsEnabled: Boolean = false,
    val containerIdentityVirtualizationEnabled: Boolean = true,
    val packageManagerVirtualizationEnabled: Boolean = true,
    val pathVirtualizationEnabled: Boolean = true
) {
    fun allowsHookRuntime(): Boolean =
        lsPlantEnabled ||
            xposedModulesEnabled ||
            methodReplacementPatchesEnabled ||
            noOpPatchesEnabled

    fun isHookFree(): Boolean =
        !lsPlantEnabled &&
            !xposedModulesEnabled &&
            !businessNativeStubsEnabled &&
            !methodReplacementPatchesEnabled &&
            !noOpPatchesEnabled

    fun requiresContainerVirtualization(): Boolean =
        containerIdentityVirtualizationEnabled &&
            packageManagerVirtualizationEnabled &&
            pathVirtualizationEnabled

    companion object {
        fun strict(): ProtectedAppBaselineMode = ProtectedAppBaselineMode()

        fun diagnostic(): ProtectedAppBaselineMode = ProtectedAppBaselineMode(
            nativeDiagnosticsEnabled = true
        )
    }
}
