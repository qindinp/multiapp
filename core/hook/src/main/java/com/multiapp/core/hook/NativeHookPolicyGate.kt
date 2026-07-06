package com.multiapp.core.hook

/**
 * Central startup gate for protected-app baseline policy decisions.
 *
 * The strict baseline path allows container virtualization only. It records an
 * explicit SKIPPED decision for hook, Xposed, business-native-stub, method
 * replacement, and no-op capabilities instead of silently falling back.
 */
object NativeHookPolicyGate {

    fun evaluate(
        policy: NativeHookPolicy,
        capability: NativeHookCapability,
        component: String
    ): NativeHookPolicyDecision {
        val allowed = policy.isEnabled(capability)
        val status = if (allowed) "ALLOWED" else "SKIPPED"
        val reason = if (allowed) {
            "enabled by NativeHookPolicy"
        } else {
            "disabled by NativeHookPolicy"
        }
        return NativeHookPolicyDecision(
            allowed = allowed,
            status = status,
            evidence = baselineEvidence(policy) + mapOf(
                "capability" to capability.name,
                "component" to component,
                "reason" to reason
            )
        )
    }

    fun decisionsForComponents(
        policy: NativeHookPolicy,
        components: Map<NativeHookCapability, String>
    ): List<NativeHookPolicyDecision> = components.map { (capability, component) ->
        evaluate(policy, capability, component)
    }

    fun baselineEvidence(policy: NativeHookPolicy): Map<String, String> = mapOf(
        "policyMode" to policy.mode.name,
        "lsPlantEnabled" to policy.lsPlantMethodHooks.toString(),
        "xposedModulesEnabled" to policy.xposedModules.toString(),
        "businessNativeStubsEnabled" to policy.businessNativeStubs.toString(),
        "businessNativeWrappersEnabled" to policy.businessNativeWrappers.toString(),
        "nativeBaseHooksEnabled" to policy.nativeBaseHooks.toString(),
        "mapsFilterEnabled" to policy.mapsFilter.toString(),
        "cmdlineSpoofEnabled" to policy.cmdlineSpoof.toString(),
        "statusTracerPidSpoofEnabled" to policy.statusTracerPidSpoof.toString(),
        "apkOpenRedirectEnabled" to policy.apkOpenRedirect.toString(),
        "selfKillInterceptionEnabled" to policy.selfKillInterception.toString(),
        "registerNativesLoggerEnabled" to policy.registerNativesLogger.toString(),
        "findClassLoggerEnabled" to policy.findClassLogger.toString(),
        "methodReplacementEnabled" to policy.methodReplacement.toString(),
        "noOpPatchesEnabled" to policy.noOpPatches.toString(),
        "invasiveNativeHooksEnabled" to policy.isInvasive().toString(),
        "hookFreeBaselineCompatible" to policy.isHookFreeBaselineCompatible().toString(),
        "containerIdentityVirtualizationEnabled" to policy.containerIdentityVirtualization.toString(),
        "packageManagerVirtualizationEnabled" to policy.packageManagerVirtualization.toString(),
        "pathVirtualizationEnabled" to policy.pathVirtualization.toString()
    )
}

data class NativeHookPolicyDecision(
    val allowed: Boolean,
    val status: String,
    val evidence: Map<String, String>
)
