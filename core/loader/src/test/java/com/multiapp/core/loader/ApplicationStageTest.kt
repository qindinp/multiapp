package com.multiapp.core.loader

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        assertEquals("true", evidence["runtimePublishedBeforeOnCreate"])
        assertEquals("com.example.app", evidence["contextPackageName"])
        assertEquals("com.example.app", evidence["originPackageName"])
        assertEquals("com.multiapp.instance.abc123", evidence["virtualPackageName"])
        assertFalse(output.isTerminalFailure)
    }

    @Test
    fun `execute publishes reusable runtime after attach and before onCreate`() {
        TestApplicationWithOnCreate.reset()
        val hostContext: Context = mockk(relaxed = true)
        var publishedInstanceId: String? = null
        var publishedResult: HostedBootstrapResult? = null
        val stage = ApplicationStage(
            hostContext = hostContext,
            applicationClassNameResolver = { _, _ -> TestApplicationWithOnCreate::class.java.name },
            runtimePublisher = { instanceId, result ->
                publishedInstanceId = instanceId
                publishedResult = result
                TestApplicationWithOnCreate.publishedRuntime = result
            },
            clock = fixedClock(500L, 523L)
        )

        val output = stage.execute(stageInput())

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("inst-001", publishedInstanceId)
        val result = assertNotNull(publishedResult)
        assertEquals("inst-001", result.instanceId)
        assertEquals("com.example.app", result.originPackageName)
        assertEquals("com.multiapp.instance.abc123", result.virtualPackageName)
        assertSame(ClassLoader.getSystemClassLoader(), result.guestClassLoader)
        assertSame(output.context.guestApplication, result.guestApplication)
        assertSame(result, TestApplicationWithOnCreate.runtimeSeenDuringOnCreate)
    }

    @Test
    fun `provider dispatch inside onCreate sees published runtime`() {
        ProviderDispatchApplication.reset()
        val snapshot = packageSnapshot(
            providers = listOf(
                ResolvedComponent(
                    name = StageProbeProvider::class.java.name,
                    exported = false,
                    authorities = listOf("com.example.app.probe")
                )
            )
        )
        val registry = VirtualPackageRegistry().apply { register(snapshot) }
        val processRuntime = VirtualProcessRuntime()
        val providerRuntime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ -> StageProbeProvider() },
            providerAttacher = ProviderAttacher { _, _, _ -> }
        )
        val hostContext: Context = mockk(relaxed = true)
        ProviderDispatchApplication.dispatcher = VirtualProviderDispatcher(
            hostPackageName = "com.multiapp.app",
            packageRegistry = registry,
            processRuntime = processRuntime,
            providerRuntime = providerRuntime,
            hostContext = hostContext
        )
        val stage = ApplicationStage(
            hostContext = hostContext,
            applicationClassNameResolver = { _, _ -> ProviderDispatchApplication::class.java.name },
            runtimePublisher = processRuntime::rememberApplication,
            clock = fixedClock(600L, 633L)
        )

        val output = stage.execute(stageInput(packageSnapshot = snapshot))

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertIs<VirtualProviderDispatchResult.ProviderReady>(ProviderDispatchApplication.providerResult)
    }

    private fun stageInput(packageSnapshot: VirtualPackageSnapshot = packageSnapshot()) = BootstrapStageInput(
        instanceId = "inst-001",
        instance = instanceRecord(),
        originApkPath = "/artifact/com.example.app.apk",
        nativeLibraryDir = "/data/instances/inst-001/lib",
        packageSnapshot = packageSnapshot,
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

    private fun packageSnapshot(
        providers: List<ResolvedComponent> = emptyList()
    ) = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        applicationLabel = "Example App",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/artifact/com.example.app.apk",
        dataDir = "/data/instances/inst-001",
        providers = providers
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}

class ProviderDispatchApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        // no-op for JVM tests
    }

    override fun onCreate() {
        providerResult = dispatcher?.dispatch("inst-001", "com.example.app.probe")
    }

    companion object {
        var dispatcher: VirtualProviderDispatcher? = null
        var providerResult: VirtualProviderDispatchResult? = null

        fun reset() {
            dispatcher = null
            providerResult = null
        }
    }
}

class StageProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
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
        runtimeSeenDuringOnCreate = publishedRuntime
        onCreateCalled = true
        lastInstance = this
    }

    companion object {
        var onCreateCalled: Boolean = false
        var lastAttachedContext: Context? = null
        var lastInstance: TestApplicationWithOnCreate? = null
        var publishedRuntime: HostedBootstrapResult? = null
        var runtimeSeenDuringOnCreate: HostedBootstrapResult? = null

        fun reset() {
            onCreateCalled = false
            lastAttachedContext = null
            lastInstance = null
            publishedRuntime = null
            runtimeSeenDuringOnCreate = null
        }
    }
}
