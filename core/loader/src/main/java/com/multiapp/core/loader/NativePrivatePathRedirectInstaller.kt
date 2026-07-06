package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.hook.NativeHookPolicy

internal fun interface NativePrivatePathRedirectInstaller {
    fun install(
        instanceId: String,
        originPackageName: String,
        dataRoot: String,
        hostContext: Context?
    ): NativePrivatePathRedirectInstallResult
}

internal data class NativePrivatePathRedirectInstallResult(
    val hookInstalled: Boolean,
    val ruleCount: Int,
    val reason: String
) {
    val verdict: String
        get() = when {
            hookInstalled && ruleCount == EXPECTED_RULE_COUNT -> "PARTIAL"
            ruleCount != EXPECTED_RULE_COUNT -> "FAIL"
            else -> "UNSUPPORTED"
        }

    fun evidence(): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("nativePrivatePathRedirectVerdict", verdict),
        BootstrapEvidence("nativePrivatePathRedirectReason", reason),
        BootstrapEvidence("nativePrivatePathRedirectHookInstalled", hookInstalled.toString()),
        BootstrapEvidence("nativePrivatePathRedirectRuleCount", ruleCount.toString()),
        BootstrapEvidence("nativePrivatePathRedirectExpectedRuleCount", EXPECTED_RULE_COUNT.toString()),
        BootstrapEvidence("nativeRedirectScope", "GUEST_PRIVATE_PATHS_ONLY"),
        BootstrapEvidence("nativePrivatePathRedirectOperations", PATH_REDIRECT_OPERATIONS.joinToString(",")),
        BootstrapEvidence("nativeRealpathRedirectVerdict", "UNSUPPORTED"),
        BootstrapEvidence("nativeRealpathRedirectVerdictReason", "REALPATH_HOOK_NOT_IMPLEMENTED"),
        BootstrapEvidence("procMapsSpoofEnabled", "false"),
        BootstrapEvidence("procStatusSpoofEnabled", "false"),
        BootstrapEvidence("nativeIoRedirectVerdict", verdict),
        BootstrapEvidence("nativeIoRedirectVerdictReason", nativeIoReason())
    )

    private fun nativeIoReason(): String = when (verdict) {
        "PARTIAL" -> "PATH_HOOK_INSTALLED_NEEDS_DEVICE_IO_PROBE"
        "FAIL" -> reason
        else -> "NATIVE_PATH_REDIRECT_HOOK_NOT_INSTALLED"
    }

    companion object {
        const val EXPECTED_RULE_COUNT = 2
        val PATH_REDIRECT_OPERATIONS: List<String> = listOf(
            "open",
            "openat",
            "access",
            "stat",
            "lstat",
            "readlink",
            "fopen",
            "mkdir",
            "unlink",
            "rename"
        )

        fun unsupported(reason: String = "NATIVE_LIBRARY_NOT_AVAILABLE"): NativePrivatePathRedirectInstallResult =
            NativePrivatePathRedirectInstallResult(
                hookInstalled = false,
                ruleCount = EXPECTED_RULE_COUNT,
                reason = reason
            )

        fun failed(ruleCount: Int, reason: String): NativePrivatePathRedirectInstallResult =
            NativePrivatePathRedirectInstallResult(
                hookInstalled = false,
                ruleCount = ruleCount,
                reason = reason
            )
    }
}

internal object NativePrivatePathRedirectInstallers {
    fun bridge(
        bridgeProvider: () -> NativeHookBridge = { NativeHookBridge.getInstance() },
        policy: NativeHookPolicy = NativeHookPolicy.baseline()
    ): NativePrivatePathRedirectInstaller = NativePrivatePathRedirectInstaller { instanceId, originPackageName, dataRoot, hostContext ->
        if (originPackageName.isBlank() || dataRoot.isBlank()) {
            return@NativePrivatePathRedirectInstaller NativePrivatePathRedirectInstallResult.failed(
                ruleCount = 0,
                reason = "PRIVATE_PATH_REDIRECT_INPUT_INCOMPLETE"
            )
        }

        val bridge = bridgeProvider()
        val hookInstalled = bridge.initNativePathRedirectHooks(
            policy = policy,
            context = hostContext,
            component = "NativeLibrariesStage.nativePrivatePathRedirect"
        )
        val ruleCount = bridge.setupGuestPrivatePathRedirections(
            guestPackageName = originPackageName,
            instanceId = instanceId,
            dataRoot = dataRoot
        )
        val reason = when {
            hookInstalled && ruleCount == NativePrivatePathRedirectInstallResult.EXPECTED_RULE_COUNT ->
                "PATH_HOOK_INSTALLED_NEEDS_DEVICE_IO_PROBE"
            ruleCount != NativePrivatePathRedirectInstallResult.EXPECTED_RULE_COUNT ->
                "PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE"
            else -> "NATIVE_PATH_REDIRECT_HOOK_NOT_INSTALLED"
        }
        NativePrivatePathRedirectInstallResult(
            hookInstalled = hookInstalled,
            ruleCount = ruleCount,
            reason = reason
        )
    }
}
