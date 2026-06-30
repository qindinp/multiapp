package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClassLoaderStageTest {

    @Test
    fun `execute creates classloader and stores it in context`() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> classLoader },
            clock = fixedClock(100L, 107L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk",
                nativeLibraryDir = "/data/instances/inst-001/lib"
            )
        )

        assertEquals(RuntimeStage.CLASS_LOADER, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(7L, output.result.durationMs)
        assertSame(classLoader, output.context.guestClassLoader)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(classLoader.javaClass.name, evidence["classLoaderClass"])
        assertEquals("/data/instances/inst-001/lib", evidence["nativeLibraryDir"])
    }

    @Test
    fun `execute passes origin apk path and native library dir to factory`() {
        var capturedApkPath: String? = null
        var capturedNativeLibraryDir: String? = null
        val stage = ClassLoaderStage(
            classLoaderFactory = { apkPath, nativeLibraryDir ->
                capturedApkPath = apkPath
                capturedNativeLibraryDir = nativeLibraryDir
                ClassLoader.getSystemClassLoader()
            },
            clock = fixedClock(200L, 203L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk",
                nativeLibraryDir = "/data/instances/inst-001/lib"
            )
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("/artifact/app.apk", capturedApkPath)
        assertEquals("/data/instances/inst-001/lib", capturedNativeLibraryDir)
    }

    @Test
    fun `execute appends additional evidence to classloader result`() {
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            clock = fixedClock(300L, 305L)
        )

        val output = stage.execute(
            input = BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk"
            ),
            additionalEvidence = listOf(
                BootstrapEvidence("providerRoutingEnabled", "true"),
                BootstrapEvidence("providerHookInstallStatus", "SKIPPED")
            )
        )

        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("true", evidence["providerRoutingEnabled"])
        assertEquals("SKIPPED", evidence["providerHookInstallStatus"])
    }

    @Test
    fun `execute fails terminally when factory throws`() {
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> throw RuntimeException("dex load failed") },
            clock = fixedClock(400L, 411L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = "inst-001",
                originApkPath = "/artifact/app.apk"
            )
        )

        assertEquals(RuntimeStage.CLASS_LOADER, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("ClassLoader creation failed: dex load failed", output.result.message)
        assertEquals(11L, output.result.durationMs)
        assertNull(output.context.guestClassLoader)
        assertTrue(output.isTerminalFailure)
    }

    @Test
    fun `execute fails terminally when origin apk path is missing`() {
        val stage = ClassLoaderStage(
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            clock = fixedClock(500L, 502L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.CLASS_LOADER, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Origin APK path is required before ClassLoader creation", output.result.message)
        assertEquals(2L, output.result.durationMs)
        assertTrue(output.isTerminalFailure)
    }

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}
