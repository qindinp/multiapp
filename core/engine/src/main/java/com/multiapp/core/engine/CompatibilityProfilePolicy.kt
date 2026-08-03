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
                // B 类修复验证（2026-08-03）：COMPUT_HOOK 启用 lsplant provider hook 时，
                // 微博 WeiboApplication 可推进到 onCreate（hook 生效，不再 attachBaseContext UID 拒绝）。
                // 正式方案待 allowList 配置入口（按需放行加固应用）。
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
