package com.multiapp.core.loader

import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.hook.Interface20Verdict
import com.multiapp.core.hook.NativeDiagnosticsEvidence
import com.multiapp.core.hook.NativeDiagnosticsResult
import com.multiapp.core.hook.NativeHookPolicy
import com.multiapp.core.hook.NativeHookPolicyGate
import java.io.File

internal object ProtectedDiagnosticsEvidenceWriter {
    private const val EVIDENCE_DIR = "hosted_launch_evidence"

    fun writeIfAllowed(
        filesDir: File,
        result: HostedBootstrapResult,
        policy: NativeHookPolicy
    ): Boolean {
        if (!shouldWrite(result, policy)) return false
        write(filesDir, result, policy)
        return true
    }

    fun write(
        filesDir: File,
        result: HostedBootstrapResult,
        policy: NativeHookPolicy
    ) {
        val evidenceDir = File(filesDir, EVIDENCE_DIR).apply { mkdirs() }.canonicalFile
        val files = ProtectedDiagnosticsEvidenceFormatter.files(result, policy)
        files.forEach { file ->
            val outputFile = File(evidenceDir, file.name).canonicalFile
            require(outputFile.parentFile == evidenceDir) { "Protected diagnostics evidence path escapes evidence dir" }
            outputFile.writeText(file.lines.joinToString("\n") { EvidenceSanitizer.sanitizeEvidenceLine(it) })
        }
    }

    private fun shouldWrite(result: HostedBootstrapResult, policy: NativeHookPolicy): Boolean =
        policy.registerNativesLogger || QqReaderProfile.isQqReaderPackage(result.originPackageName)
}

internal data class ProtectedDiagnosticsEvidenceFile(
    val name: String,
    val lines: List<String>
)

internal object ProtectedDiagnosticsEvidenceFormatter {

    fun files(
        result: HostedBootstrapResult,
        policy: NativeHookPolicy
    ): List<ProtectedDiagnosticsEvidenceFile> {
        val diagnostics = result.diagnostics
        val verdict = buildVerdict(result, diagnostics)
        val policyLines = policyEvidenceLines(policy)
        val commonLines = commonLines(result, policy) + policyLines + packerEvidenceLines(result)

        return listOf(
            ProtectedDiagnosticsEvidenceFile(
                HostedActivityEvidenceFiles.protectedDiagnostics(result.instanceId),
                commonLines + listOf(
                    "diagnosticsEvidenceCount=${diagnostics?.evidence?.size ?: 0}",
                    "stageCount=${result.stageResults.size}",
                    "compatibilityClaim=false"
                )
            ),
            ProtectedDiagnosticsEvidenceFile(
                HostedActivityEvidenceFiles.nativeLoad(result.instanceId),
                commonLines + listOf(
                    "nativeLoadVerdict=${verdict.nativeLoadVerdict}",
                    "nativeLoadVerdictReason=${verdict.nativeLoadVerdictReason}",
                    "jniOnLoadVerdict=${verdict.jniOnLoadVerdict}",
                    "jniOnLoadVerdictReason=${verdict.jniOnLoadVerdictReason}",
                    "namespaceVerdict=${verdict.namespaceVerdict}",
                    "namespaceVerdictReason=${verdict.namespaceVerdictReason}",
                    "classLoaderVerdict=${verdict.classLoaderVerdict}",
                    "classLoaderVerdictReason=${verdict.classLoaderVerdictReason}"
                )
            ),
            ProtectedDiagnosticsEvidenceFile(
                HostedActivityEvidenceFiles.registerNatives(result.instanceId),
                commonLines + listOf(
                    "registerNativesVerdict=${verdict.registerNativesVerdict}",
                    "registerNativesVerdictReason=${verdict.registerNativesVerdictReason}",
                    "findClassVerdict=${verdict.findClassVerdict}",
                    "findClassVerdictReason=${verdict.findClassVerdictReason}",
                    "interface20Verdict=${verdict.interface20Verdict}",
                    "interface20VerdictReason=${propertyValue(verdict.interface20VerdictReason)}"
                )
            ),
            ProtectedDiagnosticsEvidenceFile(
                HostedActivityEvidenceFiles.protectedVerdict(result.instanceId),
                commonLines + listOf(
                    "nativeLoadVerdict=${verdict.nativeLoadVerdict}",
                    "jniOnLoadVerdict=${verdict.jniOnLoadVerdict}",
                    "findClassVerdict=${verdict.findClassVerdict}",
                    "registerNativesVerdict=${verdict.registerNativesVerdict}",
                    "interface20Verdict=${verdict.interface20Verdict}",
                    "interface20VerdictReason=${propertyValue(verdict.interface20VerdictReason)}",
                    "namespaceVerdict=${verdict.namespaceVerdict}",
                    "classLoaderVerdict=${verdict.classLoaderVerdict}",
                    "compatibilityClaim=false"
                )
            )
        )
    }

    private fun commonLines(
        result: HostedBootstrapResult,
        policy: NativeHookPolicy
    ): List<String> = listOf(
        "status=${diagnosticsStatus(policy)}",
        "mode=${diagnosticsMode(policy)}",
        "diagnosticsEnabled=${policy.registerNativesLogger}",
        "instanceId=${propertyValue(result.instanceId)}",
        "installId=${propertyValue(result.installId.orEmpty())}",
        "originPackageName=${propertyValue(result.originPackageName.orEmpty())}",
        "virtualPackageName=${propertyValue(result.virtualPackageName.orEmpty())}",
        "originApkPath=${propertyValue(result.originApkPath.orEmpty())}",
        "bootstrapSuccess=${result.success}",
        "policyMode=${policy.mode.name}",
        "registerNativesObserveOnlyEnabled=${policy.registerNativesLogger}",
        "classLoadLoggingEnabled=${policy.findClassLogger}"
    )

    private fun diagnosticsStatus(policy: NativeHookPolicy): String =
        if (policy.registerNativesLogger) "PROTECTED_DIAGNOSTICS" else "PROTECTED_BASELINE_POLICY"

    private fun diagnosticsMode(policy: NativeHookPolicy): String =
        if (policy.registerNativesLogger) "hosted-register-natives-only-diagnostics" else "hosted-protected-baseline"

    private fun packerEvidenceLines(result: HostedBootstrapResult): List<String> {
        val stage = result.stageResults.firstOrNull { it.stage == RuntimeStage.PACKER_RUNTIME }
            ?: return emptyList()
        val evidence = stage.evidence.associate { it.key to it.value }
        return listOf(
            "packerStageStatus=" + stage.status.name,
            "packerName=" + propertyValue(evidence["packerName"].orEmpty()),
            "packerSkipReason=" + propertyValue(evidence["packerSkipReason"].orEmpty()),
            "packerJiaguLoaded=" + propertyValue(evidence["jiaguLoaded"].orEmpty()),
            "packerStubVerified=" + propertyValue(evidence["stubNativesVerified"].orEmpty()),
            "packerMessage=" + propertyValue(stage.message)
        )
    }

    private fun policyEvidenceLines(policy: NativeHookPolicy): List<String> {
        val evidence = NativeHookPolicyGate.baselineEvidence(policy)
        return listOf(
            "lsplantEnabled=${policy.lsPlantMethodHooks}",
            "xposedEnabled=${policy.xposedModules}",
            "businessNativeStubsEnabled=${policy.businessNativeStubs}",
            "businessNativeWrappersEnabled=${policy.businessNativeWrappers}",
            "nativeBaseHooksEnabled=${policy.nativeBaseHooks}",
            "methodReplacementEnabled=${policy.methodReplacement}",
            "noOpPatchesEnabled=${policy.noOpPatches}",
            "containerIdentityVirtualizationEnabled=${policy.containerIdentityVirtualization}",
            "packageManagerVirtualizationEnabled=${policy.packageManagerVirtualization}",
            "pathVirtualizationEnabled=${policy.pathVirtualization}"
        ) + evidence.entries.map { (key, value) -> "policyEvidence.$key=$value" }
    }

    private fun buildVerdict(
        result: HostedBootstrapResult,
        diagnostics: NativeDiagnosticsResult?
    ): ProtectedDiagnosticsVerdict {
        val evidence = diagnostics?.evidence.orEmpty()
        val classLoaderCreated = evidence.firstValue("classloader_created")
        val jniOnLoadExecuted = evidence.firstValue("jni_onload_executed")
        val registerNativesExecuted = evidence.firstValue("register_natives_executed")
        val nativeStage = result.stageResults.firstOrNull { it.stage == RuntimeStage.NATIVE_LIBS }

        val nativeLoadVerdict = when {
            evidence.hasTrue("native_load_succeeded") -> "PASS"
            evidence.hasTrue("native_load_failed") -> "FAIL"
            nativeStage?.status == BootstrapStatus.FAILED -> "FAIL"
            else -> "UNKNOWN"
        }
        val nativeLoadReason = when (nativeLoadVerdict) {
            "PASS" -> "native load success evidence observed"
            "FAIL" -> nativeStage?.message ?: "native load failure evidence observed"
            else -> "native load is not directly collected by hosted bootstrap"
        }

        val jniVerdict = when (jniOnLoadExecuted) {
            "true" -> "PASS"
            "false" -> "FAIL"
            else -> "UNKNOWN"
        }
        val jniReason = when (jniVerdict) {
            "PASS" -> "JNI_OnLoad execution evidence observed"
            "FAIL" -> diagnostics?.verdictReason ?: "JNI_OnLoad execution evidence explicitly false"
            else -> "JNI_OnLoad evidence not collected"
        }

        val registerVerdict = when {
            registerNativesExecuted == "true" -> "PASS"
            registerNativesExecuted == "false" -> "FAIL"
            diagnostics?.verdict == Interface20Verdict.ORIGINAL_SHELL_REGISTERED -> "PASS"
            diagnostics?.verdict in setOf(
                Interface20Verdict.REGISTER_NATIVES_NOT_EXECUTED,
                Interface20Verdict.REGISTER_NATIVES_WRONG_CLASS,
                Interface20Verdict.FALLBACK_REGISTERED
            ) -> "FAIL"
            else -> "UNKNOWN"
        }
        val registerReason = when (registerVerdict) {
            "PASS" -> "RegisterNatives evidence observed"
            "FAIL" -> diagnostics?.verdictReason ?: "RegisterNatives evidence indicates failure"
            else -> "RegisterNatives evidence not collected"
        }

        val classLoaderVerdict = when (classLoaderCreated) {
            "true" -> "PASS"
            "false" -> "FAIL"
            else -> "UNKNOWN"
        }
        val classLoaderReason = when (classLoaderVerdict) {
            "PASS" -> "guest ClassLoader stage succeeded"
            "FAIL" -> "guest ClassLoader stage did not succeed"
            else -> "guest ClassLoader evidence not collected"
        }

        return ProtectedDiagnosticsVerdict(
            nativeLoadVerdict = nativeLoadVerdict,
            nativeLoadVerdictReason = nativeLoadReason,
            jniOnLoadVerdict = jniVerdict,
            jniOnLoadVerdictReason = jniReason,
            findClassVerdict = "UNKNOWN",
            findClassVerdictReason = "not collected in register-natives-only diagnostics",
            registerNativesVerdict = registerVerdict,
            registerNativesVerdictReason = registerReason,
            interface20Verdict = diagnostics?.verdict?.name ?: Interface20Verdict.INSUFFICIENT_EVIDENCE.name,
            interface20VerdictReason = diagnostics?.verdictReason ?: "diagnostics result missing",
            namespaceVerdict = "UNKNOWN",
            namespaceVerdictReason = "native namespace evidence requires a separate collector",
            classLoaderVerdict = classLoaderVerdict,
            classLoaderVerdictReason = classLoaderReason
        )
    }

    private fun List<NativeDiagnosticsEvidence>.firstValue(key: String): String? =
        firstOrNull { it.key == key }?.value

    private fun List<NativeDiagnosticsEvidence>.hasTrue(key: String): Boolean =
        any { it.key == key && it.value == "true" }

    private fun propertyValue(value: String): String =
        EvidenceSanitizer.sanitizeEvidenceLine(value)
}

private data class ProtectedDiagnosticsVerdict(
    val nativeLoadVerdict: String,
    val nativeLoadVerdictReason: String,
    val jniOnLoadVerdict: String,
    val jniOnLoadVerdictReason: String,
    val findClassVerdict: String,
    val findClassVerdictReason: String,
    val registerNativesVerdict: String,
    val registerNativesVerdictReason: String,
    val interface20Verdict: String,
    val interface20VerdictReason: String,
    val namespaceVerdict: String,
    val namespaceVerdictReason: String,
    val classLoaderVerdict: String,
    val classLoaderVerdictReason: String
)
