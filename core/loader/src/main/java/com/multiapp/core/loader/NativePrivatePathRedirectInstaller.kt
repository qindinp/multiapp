package com.multiapp.core.loader

import android.content.Context
import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.hook.NativeHookPolicy
import java.io.File

internal fun interface NativePrivatePathRedirectInstaller {
    fun install(
        instanceId: String,
        originPackageName: String,
        dataRoot: String,
        processSlot: String,
        hostContext: Context?
    ): NativePrivatePathRedirectInstallResult
}

internal data class NativePrivatePathRedirectConfig(
    val instanceId: String,
    val originPackageName: String,
    val dataRoot: String,
    val processSlot: String,
    val privatePathPrefixes: List<String>
) {
    fun evidence(): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("nativeRedirectInstanceId", instanceId),
        BootstrapEvidence("nativeRedirectOriginPackageName", originPackageName),
        BootstrapEvidence("nativeRedirectDataRoot", dataRoot),
        BootstrapEvidence("nativeRedirectProcessSlot", processSlot),
        BootstrapEvidence("nativeRedirectProcessSlotBound", processSlot.isNotBlank().toString()),
        BootstrapEvidence("nativeRedirectBindingScope", "processSlot+instanceId+dataRoot"),
        BootstrapEvidence("nativeRedirectRulePrefixes", privatePathPrefixes.joinToString(","))
    )

    companion object {
        fun create(
            instanceId: String,
            originPackageName: String,
            dataRoot: String,
            processSlot: String = VirtualStoragePathDiagnostics.nativeProcessSlot(instanceId)
        ): NativePrivatePathRedirectConfigDecision {
            val normalizedProcessSlot = processSlot.ifBlank {
                VirtualStoragePathDiagnostics.nativeProcessSlot(instanceId)
            }
            if (instanceId.isBlank() || originPackageName.isBlank() || dataRoot.isBlank() || normalizedProcessSlot.isBlank()) {
                return NativePrivatePathRedirectConfigDecision.invalid("PRIVATE_PATH_REDIRECT_INPUT_INCOMPLETE")
            }
            if (originPackageName.contains('/') ||
                originPackageName.contains('\\') ||
                instanceId.contains('/') ||
                instanceId.contains('\\') ||
                normalizedProcessSlot.contains('/') ||
                normalizedProcessSlot.contains('\\')
            ) {
                return NativePrivatePathRedirectConfigDecision.invalid("PRIVATE_PATH_REDIRECT_UNSAFE_IDENTITY")
            }
            if (listOf(instanceId, originPackageName, dataRoot, normalizedProcessSlot).any {
                    VirtualStoragePathDiagnostics.hasUnsafePathCharacter(it)
                }
            ) {
                return NativePrivatePathRedirectConfigDecision.invalid("PRIVATE_PATH_REDIRECT_UNSAFE_NUL")
            }
            if (listOf(instanceId, originPackageName, dataRoot, normalizedProcessSlot).any {
                    VirtualStoragePathDiagnostics.hasParentTraversalSegment(it)
                }
            ) {
                return NativePrivatePathRedirectConfigDecision.invalid("PRIVATE_PATH_REDIRECT_UNSAFE_TRAVERSAL")
            }

            val canonicalDataRoot = try {
                File(dataRoot).canonicalFile.absolutePath
            } catch (_: Exception) {
                return NativePrivatePathRedirectConfigDecision.invalid("PRIVATE_PATH_REDIRECT_DATA_ROOT_UNRESOLVED")
            }

            return NativePrivatePathRedirectConfigDecision.valid(
                NativePrivatePathRedirectConfig(
                    instanceId = instanceId,
                    originPackageName = originPackageName,
                    dataRoot = canonicalDataRoot,
                    processSlot = normalizedProcessSlot,
                    privatePathPrefixes = listOf(
                        "/data/data/$originPackageName/",
                        "/data/user/0/$originPackageName/"
                    )
                )
            )
        }
    }
}

internal data class NativePrivatePathRedirectConfigDecision(
    val config: NativePrivatePathRedirectConfig?,
    val reason: String
) {
    companion object {
        fun valid(config: NativePrivatePathRedirectConfig): NativePrivatePathRedirectConfigDecision =
            NativePrivatePathRedirectConfigDecision(config = config, reason = "OK")

        fun invalid(reason: String): NativePrivatePathRedirectConfigDecision =
            NativePrivatePathRedirectConfigDecision(config = null, reason = reason)
    }
}

internal data class NativePrivatePathRedirectInstallResult(
    val hookInstalled: Boolean,
    val ruleCount: Int,
    val reason: String,
    val config: NativePrivatePathRedirectConfig? = null
) {
    val verdict: String
        get() = when {
            hookInstalled && ruleCount == EXPECTED_RULE_COUNT -> "PARTIAL"
            ruleCount != EXPECTED_RULE_COUNT -> "FAIL"
            else -> "UNSUPPORTED"
        }

    fun evidence(): List<BootstrapEvidence> {
        return listOf(
            BootstrapEvidence("nativePrivatePathRedirectVerdict", verdict),
            BootstrapEvidence("nativePrivatePathRedirectReason", reason),
            BootstrapEvidence("nativePrivatePathRedirectHookInstalled", hookInstalled.toString()),
            BootstrapEvidence("nativePrivatePathRedirectRuleCount", ruleCount.toString()),
            BootstrapEvidence("nativePrivatePathRedirectExpectedRuleCount", EXPECTED_RULE_COUNT.toString()),
            BootstrapEvidence("nativeRedirectScope", "GUEST_PRIVATE_PATHS_ONLY"),
            BootstrapEvidence("nativePrivatePathRedirectOperations", PATH_REDIRECT_OPERATIONS.joinToString(",")),
            BootstrapEvidence("nativeRealpathRedirectVerdict", verdict),
            BootstrapEvidence("nativeRealpathRedirectVerdictReason", nativeIoReason()),
            BootstrapEvidence("procMapsSpoofEnabled", "false"),
            BootstrapEvidence("procStatusSpoofEnabled", "false"),
            BootstrapEvidence("nativeIoRedirectVerdict", verdict),
            BootstrapEvidence("nativeIoRedirectVerdictReason", nativeIoReason())
        ) + (config?.evidence() ?: emptyRedirectConfigEvidence())
    }

    private fun emptyRedirectConfigEvidence(): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("nativeRedirectInstanceId", ""),
        BootstrapEvidence("nativeRedirectOriginPackageName", ""),
        BootstrapEvidence("nativeRedirectDataRoot", ""),
        BootstrapEvidence("nativeRedirectProcessSlot", ""),
        BootstrapEvidence("nativeRedirectProcessSlotBound", "false"),
        BootstrapEvidence("nativeRedirectBindingScope", "processSlot+instanceId+dataRoot"),
        BootstrapEvidence("nativeRedirectRulePrefixes", "")
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
            "realpath",
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
    ): NativePrivatePathRedirectInstaller = NativePrivatePathRedirectInstaller { instanceId, originPackageName, dataRoot, processSlot, hostContext ->
        val configDecision = NativePrivatePathRedirectConfig.create(
            instanceId = instanceId,
            originPackageName = originPackageName,
            dataRoot = dataRoot,
            processSlot = processSlot
        )
        val config = configDecision.config
        if (config == null) {
            return@NativePrivatePathRedirectInstaller NativePrivatePathRedirectInstallResult.failed(
                ruleCount = 0,
                reason = configDecision.reason
            )
        }

        val bridge = bridgeProvider()
        val hookInstalled = bridge.initNativePathRedirectHooks(
            policy = policy,
            context = hostContext,
            component = "NativeLibrariesStage.nativePrivatePathRedirect"
        )
        val ruleCount = bridge.setupGuestPrivatePathRedirections(
            guestPackageName = config.originPackageName,
            processSlot = config.processSlot,
            instanceId = config.instanceId,
            dataRoot = config.dataRoot
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
            reason = reason,
            config = config
        )
    }
}
