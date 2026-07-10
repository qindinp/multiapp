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

    @Test
    fun `native runtime evidence resolves findLibrary and keeps nativeLoad partial until probed`() {
        val libDir = java.nio.file.Files.createTempDirectory("multiapp-native-lib").toFile()
        val libFile = java.io.File(libDir, "libdemo.so").apply {
            writeText("demo")
        }
        val result = hostedResult(
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = libDir.parentFile?.absolutePath ?: libDir.absolutePath,
            nativePrivatePathRedirectVerdict = "FAIL",
            nativePrivatePathRedirectReason = "PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE",
            nativeLibraryDir = libDir.absolutePath,
            nativeLibrarySearchPath = libDir.absolutePath,
            nativeLibraries = "libdemo.so",
            nativeLibraryCount = "1",
            guestClassLoader = NativeLibraryClassLoader(mapOf("demo" to libFile.absolutePath))
        )

        val nativeDiagnostic = assertNotNull(
            EngineStorageDiagnosticsFacade.diagnosticsFromBootstrapResult(result)
                .diagnostics
                .firstOrNull { diagnostic ->
                    diagnostic.kind == EngineStorageDiagnosticKind.NATIVE_IO &&
                        diagnostic.operation == "open"
                }
        )
        val fields = EngineStorageDiagnosticsFacade.fieldsForDiagnostic(nativeDiagnostic)

        assertEquals("PASS", fields["findLibraryVerdict"])
        assertEquals("", fields["findLibraryVerdictReason"])
        assertEquals("demo", fields["findLibraryName"])
        assertEquals(libFile.absolutePath, fields["findLibraryResolvedPath"])
        assertEquals("PARTIAL", fields["namespaceVerdict"])
        assertEquals("PARTIAL", fields["nativeLoadVerdict"])
        assertEquals("NATIVE_LOAD_NOT_EXECUTED_BY_STORAGE_DIAGNOSTIC", fields["nativeLoadVerdictReason"])
    }

    @Test
    fun `native runtime evidence marks packages without native libraries unsupported`() {
        val result = hostedResult(
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            nativePrivatePathRedirectVerdict = "FAIL",
            nativePrivatePathRedirectReason = "PRIVATE_PATH_REDIRECT_RULES_INCOMPLETE",
            nativeLibraryCount = "0"
        )

        val nativeDiagnostic = assertNotNull(
            EngineStorageDiagnosticsFacade.diagnosticsFromBootstrapResult(result)
                .diagnostics
                .firstOrNull { diagnostic ->
                    diagnostic.kind == EngineStorageDiagnosticKind.NATIVE_IO &&
                        diagnostic.operation == "open"
                }
        )
        val fields = EngineStorageDiagnosticsFacade.fieldsForDiagnostic(nativeDiagnostic)

        assertEquals("UNSUPPORTED", fields["findLibraryVerdict"])
        assertEquals("NO_GUEST_NATIVE_LIBRARIES", fields["findLibraryVerdictReason"])
        assertEquals("UNSUPPORTED", fields["namespaceVerdict"])
        assertEquals("UNSUPPORTED", fields["nativeLoadVerdict"])
    }

    private fun hostedResult(
        originPackageName: String,
        virtualPackageName: String,
        dataRoot: String,
        nativePrivatePathRedirectVerdict: String? = null,
        nativePrivatePathRedirectReason: String? = null,
        nativeLibraryDir: String? = null,
        nativeLibrarySearchPath: String? = null,
        nativeLibraries: String? = null,
        nativeLibraryCount: String? = null,
        guestClassLoader: ClassLoader? = null
    ): HostedBootstrapResult {
        val nativeEvidence = buildList {
            nativePrivatePathRedirectVerdict?.let { add(BootstrapEvidence("nativePrivatePathRedirectVerdict", it)) }
            nativePrivatePathRedirectReason?.let { add(BootstrapEvidence("nativePrivatePathRedirectReason", it)) }
            nativeLibraryDir?.let { add(BootstrapEvidence("nativeLibraryDir", it)) }
            nativeLibraries?.let { add(BootstrapEvidence("nativeLibraries", it)) }
            nativeLibraryCount?.let { add(BootstrapEvidence("nativeLibraryCount", it)) }
        }
        val classLoaderEvidence = buildList {
            nativeLibrarySearchPath?.let { add(BootstrapEvidence("nativeLibrarySearchPath", it)) }
        }
        return HostedBootstrapResult(
            instanceId = "inst-001",
            installId = originPackageName.ifBlank { "com.example.app" },
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            applicationLabel = "Example",
            originApkPath = "/data/app/example.apk",
            dataRoot = dataRoot,
            guestClassLoader = guestClassLoader,
            guestApplication = null,
            installRecord = null,
            packageSnapshot = null,
            launcherActivityClassName = null,
            stageResults = listOf(
                BootstrapResult(
                    stage = RuntimeStage.NATIVE_LIBS,
                    status = BootstrapStatus.SUCCESS,
                    message = "native redirect checked",
                    evidence = nativeEvidence
                ),
                BootstrapResult(
                    stage = RuntimeStage.CLASS_LOADER,
                    status = BootstrapStatus.SUCCESS,
                    message = "classloader checked",
                    evidence = classLoaderEvidence
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

    private class NativeLibraryClassLoader(
        private val libraries: Map<String, String>
    ) : ClassLoader() {
        override fun findLibrary(libname: String): String? = libraries[libname]
    }
}
