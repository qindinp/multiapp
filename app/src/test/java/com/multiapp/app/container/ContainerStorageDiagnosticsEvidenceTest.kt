package com.multiapp.app.container

import com.multiapp.core.loader.VirtualStorageDiagnosticKind
import com.multiapp.core.loader.VirtualStorageDiagnosticStatus
import com.multiapp.core.loader.VirtualStoragePathDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContainerStorageDiagnosticsEvidenceTest {

    @Test
    fun `java diagnostic fields include PR10 path identity and marker evidence`(@TempDir tempDir: File) {
        val marker = File(tempDir, "files/pr10.txt").apply {
            parentFile?.mkdirs()
            writeText("instanceId=inst-001")
        }
        val diagnostic = VirtualStoragePathDiagnostic(
            kind = VirtualStorageDiagnosticKind.JAVA_ABSOLUTE_PATH,
            status = VirtualStorageDiagnosticStatus.REDIRECTED,
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = tempDir.absolutePath,
            probeName = "data-data",
            operation = null,
            originalPath = "/data/data/com.example.app/files/pr10.txt",
            redirectedPath = marker.absolutePath,
            candidateRedirectedPath = null,
            caller = "test",
            reason = null,
            withinDataRoot = true,
            candidateWithinDataRoot = null
        )

        val fields = ContainerStorageDiagnosticsEvidence.fieldsForDiagnostic(diagnostic, marker)

        assertEquals("STORAGE_PATH_DIAGNOSTIC", fields["stage"])
        assertEquals("inst-001", fields["instanceId"])
        assertEquals("com.example.app", fields["originPackageName"])
        assertEquals("com.multiapp.instance.001", fields["virtualPackageName"])
        assertEquals("JAVA_ABSOLUTE_PATH", fields["storageDiagnosticKind"])
        assertEquals("REDIRECTED", fields["storageDiagnosticStatus"])
        assertEquals("data-data", fields["probeName"])
        assertEquals(marker.absolutePath, fields["redirectedPath"])
        assertEquals(marker.absolutePath, fields["isolationMarkerPath"])
        assertEquals("instanceId=inst-001", fields["isolationMarkerContent"])
        assertEquals(true, fields["withinDataRoot"])
    }

    @Test
    fun `native unsupported fields make the unsupported hook gap explicit`() {
        val diagnostic = VirtualStoragePathDiagnostic(
            kind = VirtualStorageDiagnosticKind.NATIVE_IO,
            status = VirtualStorageDiagnosticStatus.UNSUPPORTED,
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            probeName = null,
            operation = "openat",
            originalPath = "/data/data/com.example.app/files/pr10-native-openat.txt",
            redirectedPath = "",
            candidateRedirectedPath = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/files/pr10-native-openat.txt",
            caller = "test",
            reason = "NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE",
            withinDataRoot = false,
            candidateWithinDataRoot = true
        )

        val fields = ContainerStorageDiagnosticsEvidence.fieldsForDiagnostic(diagnostic)

        assertEquals("NATIVE_IO", fields["storageDiagnosticKind"])
        assertEquals("UNSUPPORTED", fields["storageDiagnosticStatus"])
        assertEquals("UNSUPPORTED", fields["nativeIoDiagnosticStatus"])
        assertEquals("openat", fields["nativeIoOperation"])
        assertEquals("NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE", fields["reason"])
        assertEquals("UNSUPPORTED", fields["nativeIoRedirectVerdict"])
        assertEquals("NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE", fields["nativeIoRedirectVerdictReason"])
        assertEquals("GUEST_PRIVATE_PATHS_ONLY", fields["nativeRedirectScope"])
        assertEquals(false, fields["nativeIoRedirectEnabled"])
        assertEquals(true, fields["nativeIoCandidateWithinDataRoot"])
        assertEquals("UNKNOWN", fields["namespaceVerdict"])
        assertEquals("NAMESPACE_COLLECTOR_NOT_IMPLEMENTED", fields["namespaceVerdictReason"])
        assertEquals("UNKNOWN", fields["findLibraryVerdict"])
        assertEquals("FIND_LIBRARY_COLLECTOR_NOT_IMPLEMENTED", fields["findLibraryVerdictReason"])
        assertEquals("UNKNOWN", fields["nativeLoadVerdict"])
        assertEquals("NATIVE_LOAD_COLLECTOR_NOT_IMPLEMENTED", fields["nativeLoadVerdictReason"])
        assertEquals(false, fields["procMapsSpoofEnabled"])
        assertEquals(false, fields["procStatusSpoofEnabled"])
        assertEquals(true, fields["candidateWithinDataRoot"])
        assertTrue(fields["candidateRedirectedPath"].toString().contains("pr10-native-openat.txt"))
    }

    @Test
    fun `native redirected fields make verified path redirect explicit`() {
        val diagnostic = VirtualStoragePathDiagnostic(
            kind = VirtualStorageDiagnosticKind.NATIVE_IO,
            status = VirtualStorageDiagnosticStatus.REDIRECTED,
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            probeName = null,
            operation = "open",
            originalPath = "/data/data/com.example.app/files/pr10-native-open.txt",
            redirectedPath = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/files/pr10-native-open.txt",
            candidateRedirectedPath = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/files/pr10-native-open.txt",
            caller = "test",
            reason = null,
            withinDataRoot = true,
            candidateWithinDataRoot = true,
            nativeProbeResultCode = 0,
            nativeProbeErrno = 0,
            nativeProbeCandidateExists = true,
            nativeProbeResolvedPath = ""
        )

        val fields = ContainerStorageDiagnosticsEvidence.fieldsForDiagnostic(diagnostic)

        assertEquals("REDIRECTED", fields["storageDiagnosticStatus"])
        assertEquals("REDIRECTED", fields["nativeIoDiagnosticStatus"])
        assertEquals("PASS", fields["nativeIoRedirectVerdict"])
        assertEquals("", fields["nativeIoRedirectVerdictReason"])
        assertEquals(true, fields["nativeIoRedirectEnabled"])
        assertEquals(true, fields["withinDataRoot"])
        assertEquals(true, fields["nativeIoCandidateWithinDataRoot"])
        assertEquals(0, fields["nativeProbeResultCode"])
        assertEquals(0, fields["nativeProbeErrno"])
        assertEquals(true, fields["nativeProbeCandidateExists"])
        assertEquals("GUEST_PRIVATE_PATHS_ONLY", fields["nativeRedirectScope"])
        assertEquals(false, fields["procMapsSpoofEnabled"])
        assertEquals(false, fields["procStatusSpoofEnabled"])
    }

    @Test
    fun `native unchanged fields fail closed when probe misses redirected path`() {
        val diagnostic = VirtualStoragePathDiagnostic(
            kind = VirtualStorageDiagnosticKind.NATIVE_IO,
            status = VirtualStorageDiagnosticStatus.UNCHANGED,
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            probeName = null,
            operation = "fopen",
            originalPath = "/data/data/com.example.app/files/pr10-native-fopen.txt",
            redirectedPath = "",
            candidateRedirectedPath = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/files/pr10-native-fopen.txt",
            caller = "test",
            reason = "NATIVE_IO_CANDIDATE_NOT_OBSERVED",
            withinDataRoot = false,
            candidateWithinDataRoot = true,
            nativeProbeResultCode = 0,
            nativeProbeErrno = 0,
            nativeProbeCandidateExists = false,
            nativeProbeResolvedPath = ""
        )

        val fields = ContainerStorageDiagnosticsEvidence.fieldsForDiagnostic(diagnostic)

        assertEquals("UNCHANGED", fields["storageDiagnosticStatus"])
        assertEquals("UNCHANGED", fields["nativeIoDiagnosticStatus"])
        assertEquals("FAIL", fields["nativeIoRedirectVerdict"])
        assertEquals("NATIVE_IO_CANDIDATE_NOT_OBSERVED", fields["nativeIoRedirectVerdictReason"])
        assertEquals(false, fields["nativeIoRedirectEnabled"])
        assertEquals(false, fields["withinDataRoot"])
        assertEquals(true, fields["nativeIoCandidateWithinDataRoot"])
        assertEquals(0, fields["nativeProbeResultCode"])
        assertEquals(0, fields["nativeProbeErrno"])
        assertEquals(false, fields["nativeProbeCandidateExists"])
        assertEquals(false, fields["procMapsSpoofEnabled"])
        assertEquals(false, fields["procStatusSpoofEnabled"])
    }
}
