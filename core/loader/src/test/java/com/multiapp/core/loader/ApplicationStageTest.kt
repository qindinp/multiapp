package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplicationStageTest {

    @Test
    fun `execute skips when resolver returns null`() {
        val stage = ApplicationStage(
            hostContext = null,
            applicationClassNameResolver = { _, _ -> null },
            clock = fixedClock(100L, 104L)
        )
        val input = stageInput()

        val output = stage.execute(input)

        assertEquals(RuntimeStage.APPLICATION, output.result.stage)
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals("No Application class name resolved", output.result.message)
        assertEquals(4L, output.result.durationMs)
        assertNull(output.context.guestApplication)
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute fails non terminal when host context is missing and app class exists`() {
        val stage = ApplicationStage(
            hostContext = null,
            applicationClassNameResolver = { _, _ -> TestApplication::class.java.name },
            clock = fixedClock(200L, 207L)
        )

        val output = stage.execute(stageInput())

        assertEquals(RuntimeStage.APPLICATION, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals(
            "Guest Application creation failed: hostContext is required for Application creation",
            output.result.message
        )
        assertEquals(7L, output.result.durationMs)
        assertNull(output.context.guestApplication)
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute fails non terminal when app class is not found`() {
        val stage = ApplicationStage(
            hostContext = mockk(relaxed = true),
            applicationClassNameResolver = { _, _ -> "com.example.DoesNotExist" },
            clock = fixedClock(300L, 312L)
        )

        val output = stage.execute(stageInput())

        assertEquals(RuntimeStage.APPLICATION, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertTrue(output.result.message.startsWith("Guest Application creation failed:"))
        assertEquals(12L, output.result.durationMs)
        assertNotNull(output.result.errorClass)
        assertNull(output.context.guestApplication)
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute attaches and calls onCreate when application creation succeeds`() {
        TestApplicationWithOnCreate.reset()
        val hostContext: Context = mockk(relaxed = true)
        val stage = ApplicationStage(
            hostContext = hostContext,
            applicationClassNameResolver = { _, _ -> TestApplicationWithOnCreate::class.java.name },
            clock = fixedClock(400L, 419L)
        )

        val output = stage.execute(stageInput())

        assertEquals(RuntimeStage.APPLICATION, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(19L, output.result.durationMs)
        assertTrue(TestApplicationWithOnCreate.onCreateCalled)
        assertSame(output.context.guestApplication, TestApplicationWithOnCreate.lastInstance)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(TestApplicationWithOnCreate::class.java.name, evidence["applicationClass"])
        assertEquals("true", evidence["attached"])
        assertEquals("true", evidence["onCreate"])
        assertFalse(output.isTerminalFailure)
    }

    private fun stageInput() = BootstrapStageInput(
        instanceId = "inst-001",
        instance = instanceRecord(),
        originApkPath = "/artifact/com.example.app.apk",
        nativeLibraryDir = "/data/instances/inst-001/lib",
        packageSnapshot = packageSnapshot(),
        guestClassLoader = ClassLoader.getSystemClassLoader()
    )

    private fun instanceRecord() = VirtualInstanceRecord(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = "/data/instances/inst-001",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )

    private fun packageSnapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        applicationLabel = "Example App",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/artifact/com.example.app.apk",
        dataDir = "/data/instances/inst-001"
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}

class TestApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        // no-op for JVM tests
    }
}

class TestApplicationWithOnCreate : Application() {
    override fun attachBaseContext(base: Context?) {
        lastAttachedContext = base
    }

    override fun onCreate() {
        onCreateCalled = true
        lastInstance = this
    }

    companion object {
        var onCreateCalled: Boolean = false
        var lastAttachedContext: Context? = null
        var lastInstance: TestApplicationWithOnCreate? = null

        fun reset() {
            onCreateCalled = false
            lastAttachedContext = null
            lastInstance = null
        }
    }
}
