package com.multiapp.feature.launcher

import androidx.lifecycle.SavedStateHandle
import com.multiapp.core.model.CloneCreateAttempt
import com.multiapp.core.model.CloneCreateFailureException
import com.multiapp.core.model.CloneCreateResult
import com.multiapp.core.model.CloneCreationCoordinator
import com.multiapp.core.model.InstalledAppCatalog
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var cloneCreationCoordinator: CloneCreationCoordinator
    private lateinit var installedAppCatalog: InstalledAppCatalog
    private lateinit var virtualizationEngine: VirtualizationEngine

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        launcherIoDispatcher = testDispatcher
        cloneCreationCoordinator = mockk(relaxed = true)
        installedAppCatalog = mockk(relaxed = true)
        virtualizationEngine = mockk(relaxed = true)
        every { virtualizationEngine.listInstances() } returns emptyList()
        every { installedAppCatalog.listInstalledApps(any()) } returns emptyList()
        every { virtualizationEngine.launchInstance(any()) } returns EngineResult.pass(operation = "launchInstance")
        every { virtualizationEngine.deleteInstance(any()) } returns EngineResult.pass(operation = "deleteInstance")
    }

    @AfterEach
    fun tearDown() {
        launcherIoDispatcher = Dispatchers.IO
        instancesLoadTimeoutMs = 15_000L
        allAppsLoadTimeoutMs = 15_000L
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `createInstance delegates to create use case and refreshes instances`() = runTest {
        val app = testApp()
        val record = testRecord(originPackageName = app.packageName, displayName = app.appName)
        val attempt = testAttempt()
        every { cloneCreationCoordinator.prepareAttempt(app, null, null) } returns attempt
        every { cloneCreationCoordinator.create(app, attempt) } returns Result.success(
            CloneCreateResult(record.instanceId, createLatencyMs = 42L, cleanupStatus = "engine_owned")
        )
        every { virtualizationEngine.listInstances() } returnsMany listOf(emptyList(), listOf(record))

        val viewModel = createViewModel()

        viewModel.createInstance(app)

        verify { cloneCreationCoordinator.prepareAttempt(app, null, null) }
        verify { cloneCreationCoordinator.create(app, attempt) }
        assertEquals(listOf(record), viewModel.uiState.value.instances)
        assertEquals(record.instanceId, viewModel.uiState.value.lastCreatedInstanceId)
        assertEquals(42L, viewModel.uiState.value.lastCreateLatencyMs)
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.creationStep)
    }

    @Test
    fun `createInstance shows friendly failure and cleanup detail`() = runTest {
        val app = testApp()
        val attempt = testAttempt(displayName = "Work")
        every { cloneCreationCoordinator.prepareAttempt(app, "Work", null) } returns attempt
        every { cloneCreationCoordinator.create(app, attempt) } returns Result.failure(
            CloneCreateFailureException(
                failureCode = "origin_apk_missing",
                userMessage = "找不到应用",
                technicalReason = "目标应用可能已卸载",
                cleanupStatus = "install_deleted",
                cause = IllegalArgumentException("missing")
            )
        )

        val viewModel = createViewModel()

        viewModel.createInstance(app, "Work")

        assertEquals("找不到应用", viewModel.uiState.value.error)
        assertEquals("目标应用可能已卸载\ncleanup=install_deleted", viewModel.uiState.value.errorDetail)
        assertNull(viewModel.uiState.value.creationStep)
    }

    @Test
    fun `unknown create result survives ViewModel recreation and reuses pending attempt`() = runTest {
        val app = testApp()
        val savedStateHandle = SavedStateHandle()
        val attempt = testAttempt()
        val pendingArguments = mutableListOf<CloneCreateAttempt?>()
        every { cloneCreationCoordinator.prepareAttempt(app, null, any()) } answers {
            val pending = arg<CloneCreateAttempt?>(2)
            pendingArguments += pending
            pending ?: attempt
        }
        every { cloneCreationCoordinator.create(app, attempt) } returnsMany listOf(
            Result.failure(
                CloneCreateFailureException(
                    failureCode = "create_failed",
                    userMessage = "创建结果未知",
                    technicalReason = "engine_authority_unavailable_or_unknown_result",
                    cleanupStatus = "engine_owned",
                    cause = IllegalStateException("unknown result"),
                    shouldRetainCreationRequestId = true
                )
            ),
            Result.success(
                CloneCreateResult("instance-1", createLatencyMs = 42L, cleanupStatus = "engine_owned")
            )
        )

        createViewModel(savedStateHandle).createInstance(app)
        val restoredViewModel = createViewModel(savedStateHandle)
        restoredViewModel.createInstance(app)

        assertEquals(listOf(null, attempt), pendingArguments)
        verify(exactly = 2) { cloneCreationCoordinator.create(app, attempt) }
        assertEquals("instance-1", restoredViewModel.uiState.value.lastCreatedInstanceId)
    }

    @Test
    fun `successful create clears pending attempt before later recreation`() = runTest {
        val app = testApp()
        val savedStateHandle = SavedStateHandle()
        val attempt = testAttempt()
        val pendingArguments = mutableListOf<CloneCreateAttempt?>()
        every { cloneCreationCoordinator.prepareAttempt(app, null, any()) } answers {
            val pending = arg<CloneCreateAttempt?>(2)
            pendingArguments += pending
            pending ?: attempt
        }
        every { cloneCreationCoordinator.create(app, attempt) } returns Result.success(
            CloneCreateResult("instance-1", createLatencyMs = 42L, cleanupStatus = "engine_owned")
        )

        createViewModel(savedStateHandle).createInstance(app)
        createViewModel(savedStateHandle).createInstance(app)

        assertEquals(listOf(null, null), pendingArguments)
    }

    @Test
    fun `deterministic create failure clears pending attempt before retry`() = runTest {
        val app = testApp()
        val savedStateHandle = SavedStateHandle()
        val attempt = testAttempt()
        val pendingArguments = mutableListOf<CloneCreateAttempt?>()
        every { cloneCreationCoordinator.prepareAttempt(app, null, any()) } answers {
            val pending = arg<CloneCreateAttempt?>(2)
            pendingArguments += pending
            pending ?: attempt
        }
        every { cloneCreationCoordinator.create(app, attempt) } returnsMany listOf(
            Result.failure(
                CloneCreateFailureException(
                    failureCode = "creation_request_id_conflict",
                    userMessage = "创建冲突",
                    technicalReason = "creation_request_id_conflict",
                    cleanupStatus = "engine_owned",
                    cause = IllegalArgumentException("conflict")
                )
            ),
            Result.success(
                CloneCreateResult("instance-1", createLatencyMs = 42L, cleanupStatus = "engine_owned")
            )
        )

        createViewModel(savedStateHandle).createInstance(app)
        createViewModel(savedStateHandle).createInstance(app)

        assertEquals(listOf(null, null), pendingArguments)
    }

    @Test
    fun `loadAllApps delegates package query to repository`() = runTest {
        val apps = listOf(testApp())
        every { installedAppCatalog.listInstalledApps(false) } returns apps
        val viewModel = createViewModel()

        viewModel.loadAllApps()

        verify { installedAppCatalog.listInstalledApps(false) }
        assertEquals(apps, viewModel.allApps.value)
        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(true, viewModel.uiState.value.allAppsLoaded)
        assertNull(viewModel.uiState.value.allAppsError)
    }

    @Test
    fun `loadAllApps marks empty result as loaded`() = runTest {
        every { installedAppCatalog.listInstalledApps(false) } returns emptyList()
        val viewModel = createViewModel()

        viewModel.loadAllApps()

        verify { installedAppCatalog.listInstalledApps(false) }
        assertEquals(emptyList<VirtualApp>(), viewModel.allApps.value)
        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(true, viewModel.uiState.value.allAppsLoaded)
        assertNull(viewModel.uiState.value.allAppsError)
    }

    @Test
    fun `loadAllApps exposes repository failure`() = runTest {
        every { installedAppCatalog.listInstalledApps(false) } throws IllegalStateException("package query failed")
        val viewModel = createViewModel()

        viewModel.loadAllApps()

        verify { installedAppCatalog.listInstalledApps(false) }
        assertEquals(emptyList<VirtualApp>(), viewModel.allApps.value)
        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(false, viewModel.uiState.value.allAppsLoaded)
        assertEquals("package query failed", viewModel.uiState.value.allAppsError)
    }

    @Test
    fun `loadAllApps times out and clears loading state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        launcherIoDispatcher = NeverDispatcher
        allAppsLoadTimeoutMs = 100L
        val viewModel = createViewModel()

        viewModel.loadAllApps()
        runCurrent()
        advanceTimeBy(101L)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(false, viewModel.uiState.value.allAppsLoaded)
        assertEquals("读取应用列表超时，请重试", viewModel.uiState.value.allAppsError)
        verify(exactly = 0) { installedAppCatalog.listInstalledApps(any()) }
    }

    @Test
    fun `loadAllApps can retry after timeout`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        launcherIoDispatcher = NeverDispatcher
        allAppsLoadTimeoutMs = 100L
        val apps = listOf(testApp())
        every { installedAppCatalog.listInstalledApps(false) } returns apps
        val viewModel = createViewModel()

        viewModel.loadAllApps()
        runCurrent()
        advanceTimeBy(101L)
        runCurrent()

        launcherIoDispatcher = StandardTestDispatcher(testScheduler)
        viewModel.loadAllApps()
        runCurrent()

        verify(exactly = 1) { installedAppCatalog.listInstalledApps(false) }
        assertEquals(apps, viewModel.allApps.value)
        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(true, viewModel.uiState.value.allAppsLoaded)
        assertNull(viewModel.uiState.value.allAppsError)
    }

    @Test
    fun `loadInstances times out and clears loading state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        launcherIoDispatcher = NeverDispatcher
        instancesLoadTimeoutMs = 100L

        val viewModel = createViewModel()
        runCurrent()
        advanceTimeBy(101L)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals("读取分身列表超时，请重试", viewModel.uiState.value.error)
        verify(exactly = 0) { virtualizationEngine.listInstances() }
    }

    @Test
    fun `launchInstance delegates to virtualization engine`() = runTest {
        val viewModel = createViewModel()

        viewModel.launchInstance("instance-1")

        verify { virtualizationEngine.launchInstance(LaunchInstanceRequest(instanceId = "instance-1")) }
    }

    @Test
    fun `deleteInstance delegates to engine authority and refreshes instances`() = runTest {
        val viewModel = createViewModel()

        viewModel.deleteInstance("instance-1")

        verify { virtualizationEngine.deleteInstance("instance-1") }
        verify(atLeast = 2) { virtualizationEngine.listInstances() }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `deleteInstance exposes engine rejection without direct model delete`() = runTest {
        every { virtualizationEngine.deleteInstance("instance-1") } returns EngineResult.fail(
            operation = "deleteInstance",
            instanceId = "instance-1",
            message = "active_runtime_requires_confirmed_process_termination"
        )
        val viewModel = createViewModel()

        viewModel.deleteInstance("instance-1")

        assertEquals("删除分身失败", viewModel.uiState.value.error)
        assertEquals(
            "active_runtime_requires_confirmed_process_termination",
            viewModel.uiState.value.errorDetail
        )
    }

    @Test
    fun `launchInstance coalesces duplicate requests before engine dispatch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        launcherIoDispatcher = dispatcher
        val viewModel = createViewModel()
        runCurrent()

        viewModel.launchInstance("instance-1")
        viewModel.launchInstance("instance-1")
        runCurrent()

        verify(exactly = 1) {
            virtualizationEngine.launchInstance(LaunchInstanceRequest(instanceId = "instance-1"))
        }
    }

    @Test
    fun `normalizeApkComponentName expands manifest component names`() {
        assertEquals(
            "com.example.app.MainActivity",
            normalizeApkComponentName("com.example.app", ".MainActivity")
        )
        assertEquals(
            "com.example.app.MainActivity",
            normalizeApkComponentName("com.example.app", "MainActivity")
        )
        assertEquals(
            "com.other.ExternalActivity",
            normalizeApkComponentName("com.example.app", "com.other.ExternalActivity")
        )
        assertNull(normalizeApkComponentName("com.example.app", " "))
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): LauncherViewModel {
        return LauncherViewModel(
            savedStateHandle = savedStateHandle,
            cloneCreationCoordinator = cloneCreationCoordinator,
            installedAppCatalog = installedAppCatalog,
            virtualizationEngine = virtualizationEngine
        )
    }

    private object NeverDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private fun testApp(): VirtualApp = VirtualApp(
        packageName = "com.example.app",
        appName = "Test App",
        versionName = "1.2.3",
        versionCode = 123L,
        apkPath = "C:/tmp/example.apk",
        instanceId = ""
    )

    private fun testAttempt(
        creationRequestId: String = "create-request-1",
        displayName: String = "Test App"
    ): CloneCreateAttempt = CloneCreateAttempt(
        creationRequestId = creationRequestId,
        payloadFingerprint = "payload-fingerprint",
        displayName = displayName
    )

    private fun testRecord(
        originPackageName: String,
        displayName: String
    ): VirtualInstanceRecord = VirtualInstanceRecord(
        instanceId = "instance-1",
        originPackageName = originPackageName,
        virtualPackageName = "com.multiapp.instance.instance1",
        displayName = displayName,
        dataRoot = "C:/tmp/instance-1",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )
}
