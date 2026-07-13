package com.multiapp.core.loader

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualContextConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplicationStageTest {

    @Test
    fun `execute creates default Application when resolver returns null`() {
        DefaultApplicationFallback.reset()
        var createRequest: GuestApplicationCreateRequest? = null
        val stage = ApplicationStage(
            hostContext = mockk(relaxed = true),
            applicationClassNameResolver = { _, _ -> null },
            guestApplicationCreator = GuestApplicationCreator { request ->
                createRequest = request
                GuestApplicationCreateResult(
                    application = DefaultApplicationFallback(),
                    attachedContextPackageName = request.virtualContextConfig.originPackageName,
                    evidence = listOf(BootstrapEvidence("applicationCreator", "TEST_CREATOR"))
                )
            },
            clock = fixedClock(100L, 104L)
        )
        val input = stageInput()

        val output = stage.execute(input)

        assertEquals(RuntimeStage.APPLICATION, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals("Guest Application created: ${Application::class.java.name}", output.result.message)
        assertEquals(4L, output.result.durationMs)
        assertEquals(Application::class.java.name, createRequest?.applicationClassName)
        assertEquals("DEFAULT_APPLICATION", createRequest?.applicationClassSource)
        assertTrue(DefaultApplicationFallback.onCreateCalled)
        assertSame(output.context.guestApplication, DefaultApplicationFallback.lastInstance)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(Application::class.java.name, evidence["applicationClass"])
        assertEquals("DEFAULT_APPLICATION", evidence["applicationClassSource"])
        assertEquals("TEST_CREATOR", evidence["applicationCreator"])
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
            guestApplicationCreator = ReflectiveGuestApplicationCreator(),
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
        assertEquals("MANIFEST", evidence["applicationClassSource"])
        assertEquals("REFLECTIVE_ATTACH", evidence["applicationCreator"])
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
            guestApplicationCreator = ReflectiveGuestApplicationCreator(),
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
        assertEquals("com.multiapp.app:v2", result.processSlot)
        assertSame(ClassLoader.getSystemClassLoader(), result.guestClassLoader)
        assertSame(output.context.guestApplication, result.guestApplication)
        assertSame(result, TestApplicationWithOnCreate.runtimeSeenDuringOnCreate)
    }

    @Test
    fun `execute wires activity record manager into guest application context launches`(@TempDir filesDir: File) {
        TestApplicationWithOnCreate.reset()
        VirtualActivityRecordManager.global.clearAll()
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        try {
            val recordManager = VirtualActivityRecordManager()
            val snapshot = packageSnapshot(
                activities = listOf(ResolvedComponent(name = "com.example.app.DetailActivity", exported = false))
            )
            val hostContext: Context = mockk(relaxed = true) {
                every { packageName } returns "com.multiapp.app"
                every { this@mockk.filesDir } returns filesDir
            }
            val stage = ApplicationStage(
                hostContext = hostContext,
                applicationClassNameResolver = { _, _ -> TestApplicationWithOnCreate::class.java.name },
                guestApplicationCreator = ReflectiveGuestApplicationCreator(),
                activityRecordManager = recordManager,
                clock = fixedClock(610L, 633L)
            )

            val output = stage.execute(stageInput(packageSnapshot = snapshot))
            val guestContext = assertIs<VirtualContextWrapper>(TestApplicationWithOnCreate.lastAttachedContext)
            val component = mockk<ComponentName>(relaxed = true) {
                every { packageName } returns "com.example.app"
                every { className } returns "com.example.app.DetailActivity"
            }
            val intent = mockk<Intent>(relaxed = true) {
                every { this@mockk.component } returns component
                every { `package` } returns null
                every { selector } returns null
                every { flags } returns 0
                every { action } returns null
                every { categories } returns emptySet()
                every { dataString } returns null
                every { extras } returns null
            }

            assertEquals(BootstrapStatus.SUCCESS, output.result.status)
            runCatching { guestContext.startActivity(intent) }
            val record = assertNotNull(recordManager.list().singleOrNull())
            assertEquals("inst-001", record.instanceId)
            assertEquals("com.example.app.DetailActivity", record.guestActivityClassName)
            assertSame(record, recordManager.resolveByProxy(record.proxyActivityClassName))
            assertTrue(VirtualActivityRecordManager.global.list().isEmpty())
        } finally {
            VirtualActivityIntentStore.clearAll()
            VirtualActivityIntentStore.resetIntentCopierForTest()
            VirtualActivityRecordManager.global.clearAll()
            TestApplicationWithOnCreate.reset()
        }
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
            guestApplicationCreator = ReflectiveGuestApplicationCreator(),
            runtimePublisher = processRuntime::rememberApplication,
            clock = fixedClock(600L, 633L)
        )

        val output = stage.execute(stageInput(packageSnapshot = snapshot))

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertIs<VirtualProviderDispatchResult.ProviderReady>(ProviderDispatchApplication.providerResult)
    }

    @Test
    fun `same process providers are preinstalled before application onCreate`() {
        ProviderPreinstallOrderApplication.reset()
        val snapshot = packageSnapshot(
            providers = listOf(
                ResolvedComponent(
                    name = ProviderPreinstallOrderProvider::class.java.name,
                    exported = false,
                    authorities = listOf("com.example.app.preinstall")
                )
            )
        )
        val providerRuntime = VirtualProviderRuntime(
            providerFactory = ProviderFactory { _, _ -> ProviderPreinstallOrderProvider() },
            providerAttacher = ProviderAttacher { _, _, info ->
                ProviderPreinstallOrderApplication.events += "providerAttach:${info.authority}"
            }
        )
        val hostContext: Context = mockk(relaxed = true)
        val stage = ApplicationStage(
            hostContext = hostContext,
            applicationClassNameResolver = { _, _ -> ProviderPreinstallOrderApplication::class.java.name },
            guestApplicationCreator = GuestApplicationCreator {
                GuestApplicationCreateResult(
                    application = ProviderPreinstallOrderApplication(),
                    attachedContextPackageName = "com.example.app",
                    evidence = listOf(BootstrapEvidence("applicationCreator", "TEST_CREATOR"))
                )
            },
            providerPreinstaller = GuestProviderPreinstaller(providerRuntime = providerRuntime),
            applicationThreadRunner = object : ApplicationThreadRunner {
                override fun <T> run(block: () -> T): T {
                    ProviderPreinstallOrderApplication.events += "applicationThread"
                    return block()
                }
            },
            applicationOnCreateInvoker = { application ->
                ProviderPreinstallOrderApplication.events += "instrumentationOnCreate"
                application.onCreate()
            },
            runtimePublisher = { _, _ -> ProviderPreinstallOrderApplication.events += "runtimePublished" },
            clock = fixedClock(700L, 733L)
        )

        val output = stage.execute(stageInput(packageSnapshot = snapshot))

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(
            listOf(
                "applicationThread",
                "runtimePublished",
                "providerAttach:com.example.app.preinstall",
                "instrumentationOnCreate",
                "onCreate"
            ),
            ProviderPreinstallOrderApplication.events
        )
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("PASS", evidence["providerPreinstallStatus"])
        assertEquals("1", evidence["providerPreinstallInstalledCount"])
        assertEquals("com.example.app.preinstall", evidence["providerPreinstallInstalledAuthorities"])
    }

    @Test
    fun `loadedApk application creator installs sandbox and calls makeApplication`() {
        val snapshot = packageSnapshot()
        val application = TestApplication()
        val loadedApk = Any()
        var capturedState: LoadedApkRuntimeState? = null
        var capturedAliases: Collection<String>? = null
        val progressStatuses = mutableListOf<String>()
        val creator = LoadedApkGuestApplicationCreator(
            activityThreadProvider = { Any() },
            resourceBundleProvider = { _, _ ->
                VirtualResourceBundle(
                    applicationInfo = ApplicationInfo(),
                    resources = mockk<Resources>(relaxed = true),
                    source = ResourceSource.HOST_FALLBACK
                )
            },
            loadedApkInstaller = { _, state, aliases ->
                capturedState = state
                capturedAliases = aliases
                ActivityThreadLoadedApkInstallResult(
                    targetClassName = "FakeLoadedApk",
                    aliases = aliases.toList(),
                    patchResult = LoadedApkPatchResult(
                        targetClassName = "FakeLoadedApk",
                        patchedFields = listOf("mApplicationInfo", "mClassLoader"),
                        skippedFields = emptyList()
                    ),
                    installedAliasesByField = mapOf("mPackages" to aliases.toList()),
                    source = LoadedApkInstallSource.GUEST_SANDBOX,
                    loadedApk = loadedApk
                )
            },
            makeApplicationInvoker = { actualLoadedApk, instrumentation ->
                assertSame(loadedApk, actualLoadedApk)
                assertNull(instrumentation)
                application
            },
            applicationBinder = { _, _, _, actualApplication ->
                assertSame(application, actualApplication)
                successfulApplicationBindResult()
            }
        )
        val config = VirtualContextConfig(
            instanceId = "inst-001",
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = "/data/instances/inst-001/lib",
            classLoader = ClassLoader.getSystemClassLoader(),
            packageSnapshot = snapshot,
            processSlot = "com.multiapp.app:v2"
        )

        val result = creator.create(
            GuestApplicationCreateRequest(
                applicationClassName = TestApplication::class.java.name,
                applicationClassSource = "MANIFEST",
                hostContext = mockk(relaxed = true),
                virtualContextConfig = config,
                guestClassLoader = ClassLoader.getSystemClassLoader(),
                progress = { status, _, _ -> progressStatuses += status }
            )
        )

        assertSame(application, result.application)
        assertEquals(snapshot.originPackageName, capturedState?.packageName)
        assertEquals(TestApplication::class.java.name, capturedState?.applicationInfo?.className)
        assertEquals(listOf(snapshot.originPackageName, snapshot.virtualPackageName), capturedAliases?.toList())
        assertTrue("LOADED_APK_CREATE_STARTED" in progressStatuses)
        assertTrue("MAKE_APPLICATION_FINISHED" in progressStatuses)
        val evidence = result.evidence.associate { it.key to it.value }
        assertEquals("LOADED_APK_MAKE_APPLICATION", evidence["applicationCreator"])
        assertEquals("PASS", evidence["loadedApkApplicationCreatorStatus"])
        assertEquals("GUEST_SANDBOX", evidence["loadedApkApplicationCreatorSource"])
        assertEquals("PASS", evidence["activityThreadApplicationBindingStatus"])
        assertEquals("true", evidence["loadedApkApplicationOnCreateDeferred"])
    }

    @Test
    fun `loadedApk creator uses framework default Application without manifest class`() {
        val snapshot = packageSnapshot()
        val loadedApk = Any()
        var capturedState: LoadedApkRuntimeState? = null
        val defaultApplication = Application()
        val creator = LoadedApkGuestApplicationCreator(
            activityThreadProvider = { Any() },
            resourceBundleProvider = { _, _ ->
                VirtualResourceBundle(
                    applicationInfo = ApplicationInfo(),
                    resources = mockk(relaxed = true),
                    source = ResourceSource.HOST_FALLBACK
                )
            },
            loadedApkInstaller = { _, state, aliases ->
                capturedState = state
                ActivityThreadLoadedApkInstallResult(
                    targetClassName = "FakeLoadedApk",
                    aliases = aliases.toList(),
                    patchResult = LoadedApkPatchResult(
                        targetClassName = "FakeLoadedApk",
                        patchedFields = listOf("mApplicationInfo", "mResources", "mClassLoader", "mLibDir"),
                        skippedFields = emptyList()
                    ),
                    installedAliasesByField = mapOf("mPackages" to aliases.toList()),
                    source = LoadedApkInstallSource.GUEST_SANDBOX,
                    loadedApk = loadedApk
                )
            },
            makeApplicationInvoker = { _, _ -> defaultApplication },
            applicationBinder = { _, _, _, application ->
                assertSame(defaultApplication, application)
                successfulApplicationBindResult()
            }
        )

        val result = creator.create(
            guestCreateRequest(
                snapshot = snapshot,
                applicationClassName = Application::class.java.name,
                applicationClassSource = "DEFAULT_APPLICATION"
            )
        )

        assertSame(defaultApplication, result.application)
        assertNull(capturedState?.applicationInfo?.className)
        assertNull(capturedState?.applicationInfo?.name)
        val evidence = result.evidence.associate { it.key to it.value }
        assertEquals("PASS", evidence["loadedApkApplicationCreatorStatus"])
        assertEquals("DEFAULT_APPLICATION", evidence["applicationRequestedClassSource"])
    }

    @Test
    fun `loadedApk creator fails closed without reflective fallback`() {
        val progressStatuses = mutableListOf<String>()
        val creator = LoadedApkGuestApplicationCreator(
            activityThreadProvider = { throw NoSuchMethodException("ActivityThread unavailable") }
        )

        val error = assertFailsWith<LoadedApkApplicationCreationException> {
            creator.create(
                guestCreateRequest(
                    snapshot = packageSnapshot(),
                    applicationClassName = TestApplication::class.java.name,
                    applicationClassSource = "MANIFEST",
                    progress = { status, _, _ -> progressStatuses += status }
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("ActivityThread unavailable"))
        assertTrue("LOADED_APK_CREATE_FAILED" in progressStatuses)
        assertFalse(progressStatuses.any { it.contains("FALLBACK") })
    }

    @Test
    fun `application onCreate failure rolls back LoadedApk ActivityThread binding`() {
        var rollbackCalls = 0
        val rollbackHandle = ActivityThreadLoadedApkRollbackHandle {
            rollbackCalls += 1
            ActivityThreadLoadedApkRollbackResult(
                success = true,
                restoredFields = listOf("mInitialApplication", "LoadedApk.mApplication"),
                failureReasons = emptyList()
            )
        }
        val stage = ApplicationStage(
            hostContext = mockk(relaxed = true),
            applicationClassNameResolver = { _, _ -> TestApplication::class.java.name },
            guestApplicationCreator = GuestApplicationCreator {
                GuestApplicationCreateResult(
                    application = TestApplication(),
                    attachedContextPackageName = "com.example.app",
                    evidence = listOf(
                        BootstrapEvidence("loadedApkApplicationCreatorStatus", "PASS"),
                        BootstrapEvidence("activityThreadApplicationBindingStatus", "PASS")
                    ),
                    rollbackHandle = rollbackHandle
                )
            },
            applicationOnCreateInvoker = { throw IllegalStateException("onCreate failed") },
            clock = fixedClock(800L, 811L)
        )

        val output = stage.execute(stageInput())

        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertNull(output.context.guestApplication)
        assertEquals(1, rollbackCalls)
        assertEquals("Guest LoadedApk/ActivityThread binding rolled back", output.result.rollbackNote)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("PASS", evidence["applicationRuntimeRollbackStatus"])
        assertEquals("false", evidence["reflectiveApplicationFallbackEnabled"])
    }

    private fun stageInput(packageSnapshot: VirtualPackageSnapshot = packageSnapshot()) = BootstrapStageInput(
        instanceId = "inst-001",
        instance = instanceRecord(),
        originApkPath = "/artifact/com.example.app.apk",
        nativeLibraryDir = "/data/instances/inst-001/lib",
        processSlot = "com.multiapp.app:v2",
        packageSnapshot = packageSnapshot,
        guestClassLoader = ClassLoader.getSystemClassLoader()
    )

    private fun guestCreateRequest(
        snapshot: VirtualPackageSnapshot,
        applicationClassName: String,
        applicationClassSource: String,
        progress: (String, String, Map<String, String>) -> Unit = { _, _, _ -> }
    ) = GuestApplicationCreateRequest(
        applicationClassName = applicationClassName,
        applicationClassSource = applicationClassSource,
        hostContext = mockk(relaxed = true),
        virtualContextConfig = VirtualContextConfig(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = "/data/instances/inst-001/lib",
            classLoader = ClassLoader.getSystemClassLoader(),
            packageSnapshot = snapshot,
            processSlot = "com.multiapp.app:v2"
        ),
        guestClassLoader = ClassLoader.getSystemClassLoader(),
        progress = progress
    )

    private fun successfulApplicationBindResult() = ActivityThreadApplicationBindResult(
        status = ActivityThreadApplicationBindStatus.PASS,
        targetClassName = "FakeLoadedApk",
        loadedApkPatchResult = LoadedApkPatchResult(
            targetClassName = "FakeLoadedApk",
            patchedFields = listOf(
                "mApplication",
                "mApplicationInfo",
                "mResources",
                "mClassLoader",
                "mLibDir"
            ),
            skippedFields = emptyList()
        ),
        activityThreadPatchedFields = listOf(
            "mBoundApplication.info",
            "mBoundApplication.appInfo",
            "mInitialApplication"
        ),
        activityThreadSkippedFields = emptyList(),
        failureReasons = emptyList(),
        rollbackHandle = ActivityThreadLoadedApkRollbackHandle {
            ActivityThreadLoadedApkRollbackResult(
                success = true,
                restoredFields = emptyList(),
                failureReasons = emptyList()
            )
        }
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
        providers: List<ResolvedComponent> = emptyList(),
        activities: List<ResolvedComponent> = emptyList()
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
        activities = activities,
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

class ProviderPreinstallOrderApplication : Application() {
    override fun onCreate() {
        events += "onCreate"
    }

    companion object {
        val events = mutableListOf<String>()

        fun reset() {
            events.clear()
        }
    }
}

class ProviderPreinstallOrderProvider : ContentProvider() {
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

class DefaultApplicationFallback : Application() {
    override fun attachBaseContext(base: Context?) {
        // no-op for JVM tests
    }

    override fun onCreate() {
        onCreateCalled = true
        lastInstance = this
    }

    companion object {
        var onCreateCalled: Boolean = false
        var lastInstance: DefaultApplicationFallback? = null

        fun reset() {
            onCreateCalled = false
            lastInstance = null
        }
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
