package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile

data class EngineProfileDecision(
    val profile: EngineProfile,
    val allowed: Boolean,
    val reason: String,
    val providerRoutingEnabled: Boolean,
    val lsplantEnabled: Boolean,
    val xposedEnabled: Boolean,
    val procMapsSpoofEnabled: Boolean,
    val signatureFakeEnabled: Boolean,
    val businessNativeWrappersEnabled: Boolean,
    val noOpPatchesEnabled: Boolean,
    val nativeHookEnhancementEnabled: Boolean,
    val diagnosticsObserveOnlyEnabled: Boolean
)

data class EngineProfileAllowKey(
    val originPackageName: String,
    val instanceId: String,
    val profile: EngineProfile
)

class CompatibilityProfilePolicy(
    private val allowList: Set<EngineProfileAllowKey> = emptySet()
) {
    fun evaluate(
        originPackageName: String,
        instanceId: String,
        profile: EngineProfile
    ): EngineProfileDecision {
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        return when (profile) {
            EngineProfile.BASELINE -> EngineProfileDecision(
                profile = profile,
                allowed = true,
                reason = "baseline",
                providerRoutingEnabled = true,
                lsplantEnabled = false,
                xposedEnabled = false,
                procMapsSpoofEnabled = false,
                signatureFakeEnabled = false,
                businessNativeWrappersEnabled = false,
                noOpPatchesEnabled = false,
                nativeHookEnhancementEnabled = false,
                diagnosticsObserveOnlyEnabled = false
            )
            EngineProfile.DIAGNOSTICS_ONLY -> EngineProfileDecision(
                profile = profile,
                allowed = true,
                reason = "observe_only",
                providerRoutingEnabled = true,
                lsplantEnabled = false,
                xposedEnabled = false,
                procMapsSpoofEnabled = false,
                signatureFakeEnabled = false,
                businessNativeWrappersEnabled = false,
                noOpPatchesEnabled = false,
                nativeHookEnhancementEnabled = false,
                diagnosticsObserveOnlyEnabled = true
            )
            EngineProfile.COMPAT_HOOK -> {
                val allowed = EngineProfileAllowKey(originPackageName, instanceId, profile) in allowList
                EngineProfileDecision(
                    profile = profile,
                    allowed = allowed,
                    reason = if (allowed) "allow_listed" else "missing_profile_allow_list",
                    providerRoutingEnabled = true,
                    lsplantEnabled = allowed,
                    xposedEnabled = allowed,
                    procMapsSpoofEnabled = false,
                    signatureFakeEnabled = false,
                    businessNativeWrappersEnabled = false,
                    noOpPatchesEnabled = false,
                    nativeHookEnhancementEnabled = allowed,
                    diagnosticsObserveOnlyEnabled = false
                )
            }
            EngineProfile.EXPERIMENTAL_COMPAT -> EngineProfileDecision(
                profile = profile,
                allowed = false,
                reason = "experimental_profile_disabled",
                providerRoutingEnabled = true,
                lsplantEnabled = false,
                xposedEnabled = false,
                procMapsSpoofEnabled = false,
                signatureFakeEnabled = false,
                businessNativeWrappersEnabled = false,
                noOpPatchesEnabled = false,
                nativeHookEnhancementEnabled = false,
                diagnosticsObserveOnlyEnabled = false
            )
        }
    }
}
