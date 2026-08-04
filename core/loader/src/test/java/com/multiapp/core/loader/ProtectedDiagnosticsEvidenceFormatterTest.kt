package com.multiapp.core.loader

import com.multiapp.core.hook.NativeDiagnosticsConfig
import com.multiapp.core.hook.NativeDiagnosticsEvidence
import com.multiapp.core.hook.NativeDiagnosticsProfile
import com.multiapp.core.hook.NativeHookPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedDiagnosticsEvidenceFormatterTest {

    @Test
    fun `protected verdict records PR11 fields and keeps compatibility claim false`() {
        val result = hostedResult()
        val files = ProtectedDiagnosticsEvidenceFormatter.files(
            result = result,
            policy = NativeHookPolicy.registerNativesDiagnostic()
        )

        val verdict = files.single { it.name == "inst-001.protected-verdict.properties" }
            .lines.joinToString("\n")

        assertTrue(verdict.contains("mode=hosted-register-natives-only-diagnostics"), verdict)
        assertTrue(verdict.contains("policyMode=DIAGNOSTIC"), verdict)
        assertTrue(verdict.contains("registerNativesObserveOnlyEnabled=true"), verdict)
        assertTrue(verdict.contains("classLoadLoggingEnabled=false"), verdict)
        assertTrue(verdict.contains("lsplantEnabled=false"), verdict)
        assertTrue(verdict.contains("xposedEnabled=false"), verdict)
        assertTrue(verdict.contains("businessNativeStubsEnabled=false"), verdict)
        assertTrue(verdict.contains("businessNativeWrappersEnabled=false"), verdict)
        assertTrue(verdict.contains("nativeBaseHooksEnabled=false"), verdict)
        assertTrue(verdict.contains("methodReplacementEnabled=false"), verdict)
        assertTrue(verdict.contains("noOpPatchesEnabled=false"), verdict)
        assertTrue(verdict.contains("nativeLoadVerdict=UNKNOWN"), verdict)
        assertTrue(verdict.contains("jniOnLoadVerdict=PASS"), verdict)
        assertTrue(verdict.contains("findClassVerdict=UNKNOWN"), verdict)
        assertTrue(verdict.contains("registerNativesVerdict=PASS"), verdict)
        assertTrue(verdict.contains("interface20Verdict=ORIGINAL_SHELL_REGISTERED"), verdict)
        assertTrue(verdict.contains("namespaceVerdict=UNKNOWN"), verdict)
        assertTrue(verdict.contains("classLoaderVerdict=PASS"), verdict)
        assertTrue(verdict.contains("compatibilityClaim=false"), verdict)
        assertTrue(verdict.contains("packerStageStatus=SKIPPED"), verdict)
    }

    @Test
    fun `missing JNI onload evidence remains unknown instead of synthetic failure`() {
        val files = ProtectedDiagnosticsEvidenceFormatter.files(
            result = hostedResult(includeJniEvidence = false),
            policy = NativeHookPolicy.registerNativesDiagnostic()
        )

        val verdict = files.single { it.name == "inst-001.protected-verdict.properties" }
            .lines.joinToString("\n")
        val nativeLoad = files.single { it.name == "inst-001.native-load.properties" }
            .lines.joinToString("\n")

        assertTrue(verdict.contains("interface20Verdict=JNI_ONLOAD_NOT_EXECUTED"), verdict)
        assertTrue(verdict.contains("jniOnLoadVerdict=UNKNOWN"), verdict)
        assertTrue(nativeLoad.contains("jniOnLoadVerdictReason=JNI_OnLoad evidence not collected"), nativeLoad)
    }

    @Test
    fun `writer creates all protected diagnostics files`(@TempDir tempDir: File) {
        ProtectedDiagnosticsEvidenceWriter.write(
            filesDir = tempDir,
            result = hostedResult(),
            policy = NativeHookPolicy.registerNativesDiagnostic()
        )

        val evidenceDir = File(tempDir, "hosted_launch_evidence")
        assertTrue(File(evidenceDir, "inst-001.protected-diagnostics.properties").isFile)
        assertTrue(File(evidenceDir, "inst-001.native-load.properties").isFile)
        assertTrue(File(evidenceDir, "inst-001.register-natives.properties").isFile)
        assertTrue(File(evidenceDir, "inst-001.protected-verdict.properties").isFile)
        assertEquals(
            true,
            File(evidenceDir, "inst-001.protected-verdict.properties")
                .readText()
                .contains("compatibilityClaim=false")
        )
    }

    @Test
    fun `writer skips normal hosted app under baseline policy`(@TempDir tempDir: File) {
        val wrote = ProtectedDiagnosticsEvidenceWriter.writeIfAllowed(
            filesDir = tempDir,
            result = hostedResult(originPackageName = "com.example.app"),
            policy = NativeHookPolicy.baseline()
        )

        val evidenceDir = File(tempDir, "hosted_launch_evidence")
        assertFalse(wrote)
        assertFalse(File(evidenceDir, "inst-001.protected-diagnostics.properties").exists())
        assertFalse(File(evidenceDir, "inst-001.native-load.properties").exists())
        assertFalse(File(evidenceDir, "inst-001.register-natives.properties").exists())
        assertFalse(File(evidenceDir, "inst-001.protected-verdict.properties").exists())
    }

    @Test
    fun `writer creates diagnostics for normal app when register natives diagnostics is explicit`(@TempDir tempDir: File) {
        val wrote = ProtectedDiagnosticsEvidenceWriter.writeIfAllowed(
            filesDir = tempDir,
            result = hostedResult(originPackageName = "com.example.app"),
            policy = NativeHookPolicy.registerNativesDiagnostic()
        )

        val verdict = File(tempDir, "hosted_launch_evidence/inst-001.protected-verdict.properties")
        assertTrue(wrote)
        assertTrue(verdict.isFile)
        val text = verdict.readText()
        assertTrue(text.contains("mode=hosted-register-natives-only-diagnostics"), text)
        assertTrue(text.contains("diagnosticsEnabled=true"), text)
    }

    @Test
    fun `writer creates baseline policy evidence for qq reader under baseline`(@TempDir tempDir: File) {
        val wrote = ProtectedDiagnosticsEvidenceWriter.writeIfAllowed(
            filesDir = tempDir,
            result = hostedResult(originPackageName = "com.qq.reader"),
            policy = NativeHookPolicy.baseline()
        )

        val verdict = File(tempDir, "hosted_launch_evidence/inst-001.protected-verdict.properties")
        assertTrue(wrote)
        assertTrue(verdict.isFile)
        val text = verdict.readText()
        assertTrue(text.contains("status=PROTECTED_BASELINE_POLICY"), text)
        assertTrue(text.contains("mode=hosted-protected-baseline"), text)
        assertTrue(text.contains("diagnosticsEnabled=false"), text)
    }

    private fun hostedResult(
        originPackageName: String = "com.qq.reader",
        includeJniEvidence: Boolean = true
    ): HostedBootstrapResult {
        val stageResults = listOf(
            BootstrapResult.success(RuntimeStage.NATIVE_LIBS),
            BootstrapResult.success(RuntimeStage.CLASS_LOADER),
            BootstrapResult.skipped(
                stage = RuntimeStage.PACKER_RUNTIME,
                message = "packer adaptation skipped",
                evidence = listOf(BootstrapEvidence("packerSkipReason", "NO_PACKER_DETECTED"))
            ),
            BootstrapResult.failed(RuntimeStage.APPLICATION, "interface20 missing")
        )
        val diagnosticsEvidence = listOfNotNull(
            NativeDiagnosticsEvidence("classloader_created", "true"),
            if (includeJniEvidence) NativeDiagnosticsEvidence("jni_onload_executed", "true") else null,
            if (includeJniEvidence) NativeDiagnosticsEvidence("register_natives_executed", "true") else null,
            if (includeJniEvidence) NativeDiagnosticsEvidence("register_natives_class", "com.stub.StubApp") else null,
            if (includeJniEvidence) NativeDiagnosticsEvidence("original_shell_path", "true") else null
        )
        val diagnostics = NativeDiagnosticsProfile.analyze(
            NativeDiagnosticsConfig(),
            diagnosticsEvidence
        )
        return HostedBootstrapResult(
            instanceId = "inst-001",
            installId = originPackageName,
            originPackageName = originPackageName,
            virtualPackageName = "com.multiapp.virtual.${originPackageName.substringAfterLast('.')}",
            originApkPath = "/data/app/com.qq.reader/base.apk",
            guestClassLoader = null,
            guestApplication = null,
            stageResults = stageResults,
            summary = stageResults.toSummary(),
            success = false,
            diagnostics = diagnostics
        )
    }
}
