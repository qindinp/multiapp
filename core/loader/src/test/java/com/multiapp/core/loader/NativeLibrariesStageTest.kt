package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLibrariesStageTest {

    @Test
    fun `execute resolves instance lib dir when dataRoot lib directory exists`(
        @TempDir tempDir: File
    ) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val libDir = File(dataRoot, "lib").apply { mkdirs() }
        val instance = instanceRecord(dataRoot = dataRoot.absolutePath)
        val stage = NativeLibrariesStage(clock = fixedClock(100L, 106L))

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(RuntimeStage.NATIVE_LIBS, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(6L, output.result.durationMs)
        assertEquals(libDir.absolutePath, output.context.nativeLibraryDir)
        assertFalse(output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(libDir.absolutePath, evidence["nativeLibraryDir"])
        assertEquals("INSTANCE_DATA_ROOT_LIB", evidence["nativeLibrarySource"])
        assertEquals("DEFERRED", evidence["nativeLibrariesExtraction"])
    }

    @Test
    fun `execute skips native library dir when instance lib dir is missing`(
        @TempDir tempDir: File
    ) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val instance = instanceRecord(dataRoot = dataRoot.absolutePath)
        val stage = NativeLibrariesStage(clock = fixedClock(200L, 203L))

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(RuntimeStage.NATIVE_LIBS, output.result.stage)
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.nativeLibraryDir)
        assertFalse(output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("", evidence["nativeLibraryDir"])
        assertEquals("INSTANCE_DATA_ROOT_LIB", evidence["nativeLibrarySource"])
        assertEquals("DEFERRED", evidence["nativeLibrariesExtraction"])
        assertEquals("instance lib dir not present", evidence["reason"])
    }

    @Test
    fun `execute extracts supported native libraries from origin apk into abi dir`(
        @TempDir tempDir: File
    ) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val selectedAbi = NativeLibraryPaths.currentProcessSupportedAbis().first()
        val apkFile = File(tempDir, "origin.apk").also { apk ->
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/$selectedAbi/libapp_lib.so"))
                zip.write(byteArrayOf(1, 2, 3, 4))
                zip.closeEntry()
                if (selectedAbi != "armeabi-v7a") {
                    zip.putNextEntry(ZipEntry("lib/armeabi-v7a/libapp_lib.so"))
                    zip.write(byteArrayOf(5, 6, 7, 8))
                    zip.closeEntry()
                }
            }
        }
        val instance = instanceRecord(dataRoot = dataRoot.absolutePath)
        val stage = NativeLibrariesStage(clock = fixedClock(400L, 409L))

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                originApkPath = apkFile.absolutePath
            )
        )

        val abiDir = File(dataRoot, "lib/$selectedAbi")
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(abiDir.absolutePath, output.context.nativeLibraryDir)
        assertTrue(File(abiDir, "libapp_lib.so").isFile)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("APK_EXTRACTED_ABI_DIR", evidence["nativeLibrarySource"])
        assertEquals("EXTRACTED", evidence["nativeLibrariesExtraction"])
        assertEquals(selectedAbi, evidence["nativeLibrarySelectedAbi"])
        assertEquals("1", evidence["nativeLibraryCount"])
        assertEquals("1", evidence["nativeLibrariesCopiedCount"])
        assertEquals("libapp_lib.so", evidence["nativeLibraries"])
    }

    @Test
    fun `execute records native private path redirect evidence without changing stage status`(
        @TempDir tempDir: File
    ) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val libDir = File(dataRoot, "lib").apply { mkdirs() }
        val instance = instanceRecord(dataRoot = dataRoot.absolutePath)
        val stage = NativeLibrariesStage(
            nativePrivatePathRedirectInstaller = NativePrivatePathRedirectInstaller { _, _, _, _ ->
                NativePrivatePathRedirectInstallResult(
                    hookInstalled = true,
                    ruleCount = 2,
                    reason = "PATH_HOOK_INSTALLED_NEEDS_DEVICE_IO_PROBE"
                )
            },
            clock = fixedClock(500L, 505L)
        )

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(5L, output.result.durationMs)
        assertEquals(libDir.absolutePath, output.context.nativeLibraryDir)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("PARTIAL", evidence["nativePrivatePathRedirectVerdict"])
        assertEquals("PARTIAL", evidence["nativeIoRedirectVerdict"])
        assertEquals("2", evidence["nativePrivatePathRedirectRuleCount"])
        assertEquals("GUEST_PRIVATE_PATHS_ONLY", evidence["nativeRedirectScope"])
        assertEquals("UNSUPPORTED", evidence["nativeRealpathRedirectVerdict"])
        assertEquals("false", evidence["procMapsSpoofEnabled"])
        assertEquals("false", evidence["procStatusSpoofEnabled"])
    }

    @Test
    fun `execute fails terminally when instance is missing`() {
        val stage = NativeLibrariesStage(clock = fixedClock(300L, 302L))

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.NATIVE_LIBS, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Instance is required before resolving native library directory", output.result.message)
        assertEquals(2L, output.result.durationMs)
        assertNull(output.context.nativeLibraryDir)
        assertTrue(output.isTerminalFailure)
    }

    private fun instanceRecord(
        instanceId: String = "inst-001",
        dataRoot: String = "/data/instances/inst-001"
    ) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = dataRoot,
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}
