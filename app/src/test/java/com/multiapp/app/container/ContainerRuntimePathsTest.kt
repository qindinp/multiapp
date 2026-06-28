package com.multiapp.app.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

        assertEquals(File(filesDir, "hosted_launch_evidence/inst-001.properties"), evidenceFile)
    }

    @Test
    @DisplayName("component evidence files are scoped by instance and component")
    fun componentEvidenceFileStaysUnderSharedDir(@TempDir filesDir: File) {
        val evidenceFile = ContainerRuntimePaths.hostedRuntimeEvidenceFile(
            filesDir = filesDir,
            instanceId = "inst-001",
            component = "provider-proxy"
        )

        assertEquals(File(filesDir, "hosted_launch_evidence/inst-001.provider-proxy.properties"), evidenceFile)
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

        assertEquals(File(filesDir, "hosted_launch_evidence/inst-001.service-proxy.properties"), file)
        assertEquals("status=STARTED\ndetail=line1 line2 line3", file.readText())
    }
}
