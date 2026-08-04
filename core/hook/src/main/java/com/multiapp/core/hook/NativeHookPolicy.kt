package com.multiapp.core.hook

enum class NativeHookPolicyMode {
    OFF,
    BASELINE,
    DIAGNOSTIC,
    COMPATIBILITY
}

data class NativeHookPolicy(
    val mode: NativeHookPolicyMode,
    val containerIdentityVirtualization: Boolean = true,
    val packageManagerVirtualization: Boolean = true,
    val pathVirtualization: Boolean = true,
    val mapsFilter: Boolean = false,
    val cmdlineSpoof: Boolean = false,
    val statusTracerPidSpoof: Boolean = false,
    val apkOpenRedirect: Boolean = false,
    val registerNativesLogger: Boolean = false,
    val findClassLogger: Boolean = false,
    val selfKillInterception: Boolean = false,
    val lsPlantMethodHooks: Boolean = false,
    val xposedModules: Boolean = false,
    val businessNativeStubs: Boolean = false,
    val businessNativeWrappers: Boolean = false,
    val nativeBaseHooks: Boolean = false,
    val methodReplacement: Boolean = false,
    val noOpPatches: Boolean = false
) {
    init {
        // Only COMPATIBILITY mode may enable business native stubs/wrappers;
        // strict baseline and hook-free diagnostic policies must stay clean.
        require(mode == NativeHookPolicyMode.COMPATIBILITY || !businessNativeStubs) {
            "Protected app policy must not enable business native stubs outside COMPATIBILITY"
        }
        require(mode == NativeHookPolicyMode.COMPATIBILITY || !businessNativeWrappers) {
            "Protected app policy must not enable business native wrappers outside COMPATIBILITY"
        }
        require(mode != NativeHookPolicyMode.BASELINE || isStrictBaseline()) {
            "Baseline policy must be hook-free and diagnostic-free"
        }
        require(mode != NativeHookPolicyMode.DIAGNOSTIC || isHookFree()) {
            "Diagnostic policy must remain hook-free"
        }
    }

    fun isInvasive(): Boolean =
        mapsFilter ||
            cmdlineSpoof ||
            statusTracerPidSpoof ||
            apkOpenRedirect ||
            selfKillInterception ||
            businessNativeStubs ||
            businessNativeWrappers ||
            nativeBaseHooks ||
            lsPlantMethodHooks ||
            xposedModules ||
            methodReplacement ||
            noOpPatches

    fun isHookFree(): Boolean =
            !lsPlantMethodHooks &&
            !xposedModules &&
            !businessNativeStubs &&
            !businessNativeWrappers &&
            !nativeBaseHooks &&
            !methodReplacement &&
            !noOpPatches

    fun keepsContainerVirtualization(): Boolean =
        containerIdentityVirtualization &&
            packageManagerVirtualization &&
            pathVirtualization

    fun isHookFreeBaselineCompatible(): Boolean =
        mode == NativeHookPolicyMode.OFF ||
            (mode == NativeHookPolicyMode.BASELINE && isStrictBaseline())

    fun isEnabled(capability: NativeHookCapability): Boolean =
        when (capability) {
            NativeHookCapability.CONTAINER_IDENTITY_VIRTUALIZATION -> containerIdentityVirtualization
            NativeHookCapability.PACKAGE_MANAGER_VIRTUALIZATION -> packageManagerVirtualization
            NativeHookCapability.PATH_VIRTUALIZATION -> pathVirtualization
            NativeHookCapability.REGISTER_NATIVES_LOGGING -> registerNativesLogger
            NativeHookCapability.CLASS_LOAD_LOGGING -> findClassLogger
            NativeHookCapability.DIAGNOSTIC_LOGGING -> registerNativesLogger || findClassLogger
            NativeHookCapability.REGISTER_NATIVES_OBSERVE_ONLY -> registerNativesLogger
            NativeHookCapability.NATIVE_BASE_HOOKS -> nativeBaseHooks
            NativeHookCapability.LSPLANT_METHOD_HOOKS -> lsPlantMethodHooks
            NativeHookCapability.XPOSED_MODULES -> xposedModules
            NativeHookCapability.BUSINESS_NATIVE_STUBS -> businessNativeStubs
            NativeHookCapability.BUSINESS_NATIVE_WRAPPERS -> businessNativeWrappers
            NativeHookCapability.METHOD_REPLACEMENT -> methodReplacement
            NativeHookCapability.NO_OP_PATCHES -> noOpPatches
        }

    private fun isStrictBaseline(): Boolean =
        keepsContainerVirtualization() &&
            isHookFree() &&
            !isInvasive() &&
            !registerNativesLogger &&
            !findClassLogger

    companion object {
        val strictBaseline: NativeHookPolicy = baseline()

        fun off(): NativeHookPolicy = NativeHookPolicy(
            mode = NativeHookPolicyMode.OFF,
            containerIdentityVirtualization = false,
            packageManagerVirtualization = false,
            pathVirtualization = false
        )

        fun baseline(): NativeHookPolicy = NativeHookPolicy(
            mode = NativeHookPolicyMode.BASELINE
        )

        fun diagnostic(): NativeHookPolicy = NativeHookPolicy(
            mode = NativeHookPolicyMode.DIAGNOSTIC,
            registerNativesLogger = true,
            findClassLogger = true
        )

        fun registerNativesDiagnostic(): NativeHookPolicy = NativeHookPolicy(
            mode = NativeHookPolicyMode.DIAGNOSTIC,
            registerNativesLogger = true,
            findClassLogger = false
        )

        fun compatibility(): NativeHookPolicy = NativeHookPolicy(
            mode = NativeHookPolicyMode.COMPATIBILITY,
            mapsFilter = true,
            cmdlineSpoof = true,
            statusTracerPidSpoof = true,
            apkOpenRedirect = true,
            selfKillInterception = true
        )

        fun fromCapabilities(
            mode: NativeHookPolicyMode,
            enabledCapabilities: Set<NativeHookCapability>
        ): NativeHookPolicy = NativeHookPolicy(
            mode = mode,
            containerIdentityVirtualization =
                NativeHookCapability.CONTAINER_IDENTITY_VIRTUALIZATION in enabledCapabilities,
            packageManagerVirtualization =
                NativeHookCapability.PACKAGE_MANAGER_VIRTUALIZATION in enabledCapabilities,
            pathVirtualization =
                NativeHookCapability.PATH_VIRTUALIZATION in enabledCapabilities,
            registerNativesLogger =
                NativeHookCapability.REGISTER_NATIVES_LOGGING in enabledCapabilities ||
                    NativeHookCapability.REGISTER_NATIVES_OBSERVE_ONLY in enabledCapabilities,
            findClassLogger =
                NativeHookCapability.CLASS_LOAD_LOGGING in enabledCapabilities,
            nativeBaseHooks =
                NativeHookCapability.NATIVE_BASE_HOOKS in enabledCapabilities,
            lsPlantMethodHooks =
                NativeHookCapability.LSPLANT_METHOD_HOOKS in enabledCapabilities,
            xposedModules =
                NativeHookCapability.XPOSED_MODULES in enabledCapabilities,
            businessNativeStubs =
                NativeHookCapability.BUSINESS_NATIVE_STUBS in enabledCapabilities,
            businessNativeWrappers =
                NativeHookCapability.BUSINESS_NATIVE_WRAPPERS in enabledCapabilities,
            methodReplacement =
                NativeHookCapability.METHOD_REPLACEMENT in enabledCapabilities,
            noOpPatches =
                NativeHookCapability.NO_OP_PATCHES in enabledCapabilities
        )
    }
}

enum class NativeHookCapability {
    CONTAINER_IDENTITY_VIRTUALIZATION,
    PACKAGE_MANAGER_VIRTUALIZATION,
    PATH_VIRTUALIZATION,
    DIAGNOSTIC_LOGGING,
    CLASS_LOAD_LOGGING,
    REGISTER_NATIVES_LOGGING,
    REGISTER_NATIVES_OBSERVE_ONLY,
    NATIVE_BASE_HOOKS,
    LSPLANT_METHOD_HOOKS,
    XPOSED_MODULES,
    BUSINESS_NATIVE_STUBS,
    BUSINESS_NATIVE_WRAPPERS,
    METHOD_REPLACEMENT,
    NO_OP_PATCHES
}
