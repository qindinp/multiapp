package com.multiapp.app.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContainerRuntimePathsTest {

    @Test
    @DisplayName("runtime stores use the shared v2 filesystem layout")
    fun runtimeStoresUseSharedLayout(@TempDir filesDir: File) {
        assertEquals(File(filesDir, "instances"), ContainerRuntimePaths.instanceStoreDir(filesDir))
        assertEquals(File(filesDir, "installs"), ContainerRuntimePaths.installStoreDir(filesDir))
        assertEquals(File(filesDir, "artifacts"), ContainerRuntimePaths.artifactDir(filesDir))
        assertEquals(File(filesDir, "instance_data"), ContainerRuntimePaths.instanceDataRootBase(filesDir))
        assertEquals(File(filesDir, "hosted_launch_evidence"), ContainerRuntimePaths.hostedLaunchEvidenceDir(filesDir))
        assertEquals(
            File(filesDir, "engine_activity_task_state.properties"),
            ContainerRuntimePaths.engineActivityTaskStateFile(filesDir)
        )

        assertTrue(ContainerRuntimePaths.instanceStoreDir(filesDir).isDirectory)
        assertTrue(ContainerRuntimePaths.installStoreDir(filesDir).isDirectory)
        assertTrue(ContainerRuntimePaths.artifactDir(filesDir).isDirectory)
        assertTrue(ContainerRuntimePaths.instanceDataRootBase(filesDir).isDirectory)
        assertTrue(ContainerRuntimePaths.hostedLaunchEvidenceDir(filesDir).isDirectory)
    }

    @Test
    @DisplayName("instance data root stays under instance_data")
    fun instanceDataRootStaysUnderSharedBase(@TempDir filesDir: File) {
        val dataRoot = ContainerRuntimePaths.instanceDataRoot(filesDir, "inst-001")

        assertEquals(File(filesDir, "instance_data/inst-001"), dataRoot)
        assertTrue(dataRoot.isDirectory)
    }

    @Test
    @DisplayName("native library dir resolves only when dataRoot lib exists")
    fun nativeLibraryDirResolvesOnlyWhenLibExists(@TempDir filesDir: File) {
        val dataRoot = ContainerRuntimePaths.instanceDataRoot(filesDir, "inst-001")

        assertNull(ContainerRuntimePaths.nativeLibraryDirOrNull(dataRoot.absolutePath))

        val libDir = ContainerRuntimePaths.nativeLibraryDir(dataRoot).apply { mkdirs() }
        assertEquals(libDir.absolutePath, ContainerRuntimePaths.nativeLibraryDirOrNull(dataRoot.absolutePath))
    }

    @Test
    @DisplayName("hosted launch evidence file stays under hosted_launch_evidence")
    fun hostedLaunchEvidenceFileStaysUnderSharedDir(@TempDir filesDir: File) {
        val evidenceFile = ContainerRuntimePaths.hostedLaunchEvidenceFile(filesDir, "inst-001")

        assertEquals(File(filesDir, "hosted_launch_evidence/inst-001.properties").canonicalFile, evidenceFile)
    }

    @Test
    @DisplayName("component evidence files are scoped by instance and component")
    fun componentEvidenceFileStaysUnderSharedDir(@TempDir filesDir: File) {
        val evidenceFile = ContainerRuntimePaths.hostedRuntimeEvidenceFile(
            filesDir = filesDir,
            instanceId = "inst-001",
            component = "provider-proxy"
        )

        assertEquals(File(filesDir, "hosted_launch_evidence/inst-001.provider-proxy.properties").canonicalFile, evidenceFile)
    }

    @Test
    @DisplayName("component evidence files reject unsafe instance and component segments")
    fun componentEvidenceFileRejectsUnsafeSegments(@TempDir filesDir: File) {
        val unsafeSegments = listOf(
            "../inst",
            "inst/001",
            "inst\\001",
            ".",
            "..",
            "",
            " ",
            " inst-001",
            "inst-001 ",
            "C:temp",
            "inst:001",
            "inst\u0000evil",
            "%2e%2e",
            "..%2fsecret",
            "%2fabsolute",
            "inst%5c001"
        )

        unsafeSegments.forEach { segment ->
            assertThrows(IllegalArgumentException::class.java) {
                ContainerRuntimePaths.hostedRuntimeEvidenceFile(filesDir, segment, "provider-proxy")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ContainerRuntimePaths.hostedRuntimeEvidenceFile(filesDir, "inst-001", segment)
            }
        }
    }

    @Test
    @DisplayName("hosted launch evidence file rejects unsafe instance ids")
    fun hostedLaunchEvidenceFileRejectsUnsafeInstanceIds(@TempDir filesDir: File) {
        listOf("../inst", "inst/001", "inst\\001", ".", "..", "", " ", "C:temp").forEach { instanceId ->
            assertThrows(IllegalArgumentException::class.java) {
                ContainerRuntimePaths.hostedLaunchEvidenceFile(filesDir, instanceId)
            }
        }
    }

    @Test
    @DisplayName("runtime evidence writer sanitizes multiline values")
    fun runtimeEvidenceWriterSanitizesMultilineValues(@TempDir filesDir: File) {
        val file = ContainerRuntimeEvidenceWriter.write(
            filesDir = filesDir,
            instanceId = "inst-001",
            component = "service-proxy",
            fields = linkedMapOf(
                "status" to "STARTED",
                "detail" to "line1\nline2\rline3"
            )
        )

        assertEquals(File(filesDir, "hosted_launch_evidence/inst-001.service-proxy.properties").canonicalFile, file)
        assertEquals("status=STARTED\ndetail=line1 line2 line3", file.readText())
    }

    @Test
    @DisplayName("runtime evidence writer redacts uri fields")
    fun runtimeEvidenceWriterRedactsUriFields(@TempDir filesDir: File) {
        val file = ContainerRuntimeEvidenceWriter.write(
            filesDir = filesDir,
            instanceId = "inst-001",
            component = "provider-proxy",
            fields = linkedMapOf(
                "uri" to "content://proxy/items/1?token=secret&instanceId=inst-001#frag",
                "intentData" to "https://user:pass@example.com/path?password=secret#fragment",
                "intentDataUri" to "content://contacts/people/1/private?auth=secret",
                "pendingDataUri" to "file:///data/data/com.test/secret.txt",
                "dataUri" to "mailto:private@example.com",
                "detail" to "keep?query=visible"
            )
        )
        val text = file.readText()

        assertTrue(text.contains("uri=content://proxy/<redacted>"))
        assertTrue(text.contains("intentData=https://example.com/<redacted>"))
        assertTrue(text.contains("intentDataUri=content://contacts/<redacted>"))
        assertTrue(text.contains("pendingDataUri=file:///<redacted>"))
        assertTrue(text.contains("dataUri=mailto:<redacted>"))
        assertTrue(text.contains("detail=keep?query=visible"))
        listOf("token=", "password=", "secret", "instanceId=", "fragment", "private@example.com", "user:pass").forEach { leaked ->
            assertFalse(text.contains(leaked), "evidence leaked $leaked in $text")
        }
    }

    @Test
    @DisplayName("runtime evidence writer redacts activity token fields")
    fun runtimeEvidenceWriterRedactsActivityTokenFields(@TempDir filesDir: File) {
        val rawToken = "raw-activity-token-super-secret"
        val file = ContainerRuntimeEvidenceWriter.write(
            filesDir = filesDir,
            instanceId = "inst-001",
            component = "activity-proxy",
            fields = linkedMapOf(
                "status" to "PROXY_ACTIVITY_BASE_ONCREATE",
                "token" to rawToken,
                "sourceToken" to rawToken,
                "detail" to "INVALID_PROXY_URI:invalid route token:EXPIRED"
            )
        )
        val text = file.readText()

        assertTrue(text.contains("token=<redacted>"))
        assertTrue(text.contains("sourceToken=<redacted>"))
        assertTrue(text.contains("detail=INVALID_PROXY_URI:invalid route token:EXPIRED"))
        assertFalse(text.contains(rawToken), "evidence leaked raw token in $text")
    }

    @Test
    @DisplayName("runtime evidence writer rejects unsafe evidence path inputs")
    fun runtimeEvidenceWriterRejectsUnsafeEvidencePathInputs(@TempDir filesDir: File) {
        assertThrows(IllegalArgumentException::class.java) {
            ContainerRuntimeEvidenceWriter.write(
                filesDir = filesDir,
                instanceId = "../inst",
                component = "service-proxy",
                fields = mapOf("status" to "STARTED")
            )
        }

        assertFalse(File(filesDir.parentFile, "inst.service-proxy.properties").exists())
    }
}
