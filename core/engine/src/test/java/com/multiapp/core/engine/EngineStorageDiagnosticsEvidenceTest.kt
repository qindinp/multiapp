package com.multiapp.core.engine

import com.multiapp.core.loader.BootstrapEvidence
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.BootstrapStatus
import com.multiapp.core.loader.BootstrapSummary
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.RuntimeStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineStorageDiagnosticsEvidenceTest {

    @Test
    fun `plan returns bootstrap unsupported evidence when storage identity is incomplete`() {
        val result = hostedResult(
            originPackageName = "",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = "/data/user/0/com.multiapp.app/files/instance_data/inst-001"
        )

        val plan = EngineStorageDiagnosticsFacade.diagnosticsFromBootstrapResult(result)
        val entry = assertNotNull(plan.bootstrapUnsupportedEntry)

        assertEquals("inst-001", plan.instanceId)
        assertEquals("storage-bootstrap", entry.component)
        assertEquals("UNSUPPORTED", entry.fields["nativeIoRedirectVerdict"])
        assertEquals("BOOTSTRAP_STORAGE_IDENTITY_INCOMPLETE", entry.fields["nativeIoRedirectVerdictReason"])
        assertTrue(plan.diagnostics.isEmpty())
    }

    @Test
    fun `plan creates java and native diagnostics from hosted bootstrap evidence`() {
        val result = hostedResult(
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            nativePrivatePathRedirectVerdict = "FAIL",
            nativePrivatePathRedirectReason = "PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE"
        )

        val plan = EngineStorageDiagnosticsFacade.diagnosticsFromBootstrapResult(result)
        val javaDiagnostic = assertNotNull(
            plan.diagnostics.firstOrNull { diagnostic ->
                diagnostic.kind == EngineStorageDiagnosticKind.JAVA_ABSOLUTE_PATH &&
                    diagnostic.probeName == "data-data"
            }
        )
        val nativeDiagnostic = assertNotNull(
            plan.diagnostics.firstOrNull { diagnostic ->
                diagnostic.kind == EngineStorageDiagnosticKind.NATIVE_IO &&
                    diagnostic.operation == "openat"
            }
        )
        val nativeFields = EngineStorageDiagnosticsFacade.fieldsForDiagnostic(nativeDiagnostic)

        assertEquals("storage-java-data-data", EngineStorageDiagnosticsFacade.componentName(javaDiagnostic))
        assertEquals("storage-native-openat", EngineStorageDiagnosticsFacade.componentName(nativeDiagnostic))
        assertEquals("PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE", nativeDiagnostic.reason)
        assertEquals("UNSUPPORTED", nativeFields["nativeIoRedirectVerdict"])
        assertEquals("PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE", nativeFields["nativeIoRedirectVerdictReason"])
        assertEquals(false, nativeFields["procMapsSpoofEnabled"])
    }

    private fun hostedResult(
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        nativePrivatePathRedirectVerdict: String? = null,
        nativePrivatePathRedirectReason: String? = null
    ): HostedBootstrapResult {
        val evidence = buildList {
            nativePrivatePathRedirectVerdict?.let { add(BootstrapEvidence("nativePrivatePathRedirectVerdict", it)) }
            nativePrivatePathRedirectReason?.let { add(BootstrapEvidence("nativePrivatePathRedirectReason", it)) }
        }
        return HostedBootstrapResult(
            instanceId = "inst-001",
            installId = originPackageName.ifBlank { "com.example.app" },
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            applicationLabel = "Example",
            originApkPath = "/data/app/example.apk",
            dataRoot = dataRoot,
            guestClassLoader = null,
            guestApplication = null,
            installRecord = null,
            packageSnapshot = null,
            launcherActivityClassName = null,
            stageResults = listOf(
                BootstrapResult(
                    stage = RuntimeStage.NATIVE_LIBS,
                    status = BootstrapStatus.SUCCESS,
                    message = "native redirect checked",
                    evidence = evidence
                )
            ),
            summary = BootstrapSummary(
                totalTimeMs = 0L,
                stageResults = emptyList(),
                overallStatus = BootstrapStatus.SUCCESS,
                failedStage = null,
                failureReason = null
            ),
            success = true
        )
    }
}
