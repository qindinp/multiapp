package com.multiapp.feature.launcher

import com.multiapp.core.instance.CloneCreateFailureException
import com.multiapp.core.instance.CloneCreateResult
import com.multiapp.core.instance.CloneCreateUseCase
import com.multiapp.core.instance.InstalledAppRepository
import com.multiapp.core.instance.InstanceLaunchUseCase
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var instanceManager: InstanceManager
    private lateinit var cloneCreateUseCase: CloneCreateUseCase
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var launchUseCase: InstanceLaunchUseCase

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        launcherIoDispatcher = testDispatcher
        instanceManager = mockk(relaxed = true)
        cloneCreateUseCase = mockk(relaxed = true)
        installedAppRepository = mockk(relaxed = true)
        launchUseCase = mockk(relaxed = true)
        every { instanceManager.listInstances() } returns emptyList()
        every { installedAppRepository.listInstalledApps(any()) } returns emptyList()
        every { launchUseCase.launch(any()) } returns Result.success(Unit)
    }

    @AfterEach
    fun tearDown() {
        launcherIoDispatcher = Dispatchers.IO
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `createInstance delegates to create use case and refreshes instances`() = runTest {
        val app = testApp()
        val record = testRecord(originPackageName = app.packageName, displayName = app.appName)
        every { cloneCreateUseCase.create(app, null) } returns Result.success(
            CloneCreateResult(record, createLatencyMs = 42L, cleanupStatus = "not_required")
        )
        every { instanceManager.listInstances() } returnsMany listOf(emptyList(), listOf(record))

        val viewModel = createViewModel()

        viewModel.createInstance(app)

        verify { cloneCreateUseCase.create(app, null) }
        assertEquals(listOf(record), viewModel.uiState.value.instances)
        assertEquals(record.instanceId, viewModel.uiState.value.lastCreatedInstanceId)
        assertEquals(42L, viewModel.uiState.value.lastCreateLatencyMs)
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.creationStep)
    }

    @Test
    fun `createInstance shows friendly failure and cleanup detail`() = runTest {
        val app = testApp()
        every { cloneCreateUseCase.create(app, "Work") } returns Result.failure(
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
    fun `loadAllApps delegates package query to repository`() = runTest {
        val apps = listOf(testApp())
        every { installedAppRepository.listInstalledApps(false) } returns apps
        val viewModel = createViewModel()

        viewModel.loadAllApps()

        verify { installedAppRepository.listInstalledApps(false) }
        assertEquals(apps, viewModel.allApps.value)
        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(true, viewModel.uiState.value.allAppsLoaded)
        assertNull(viewModel.uiState.value.allAppsError)
    }

    @Test
    fun `loadAllApps marks empty result as loaded`() = runTest {
        every { installedAppRepository.listInstalledApps(false) } returns emptyList()
        val viewModel = createViewModel()

        viewModel.loadAllApps()

        verify { installedAppRepository.listInstalledApps(false) }
        assertEquals(emptyList<VirtualApp>(), viewModel.allApps.value)
        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(true, viewModel.uiState.value.allAppsLoaded)
        assertNull(viewModel.uiState.value.allAppsError)
    }

    @Test
    fun `loadAllApps exposes repository failure`() = runTest {
        every { installedAppRepository.listInstalledApps(false) } throws IllegalStateException("package query failed")
        val viewModel = createViewModel()

        viewModel.loadAllApps()

        verify { installedAppRepository.listInstalledApps(false) }
        assertEquals(emptyList<VirtualApp>(), viewModel.allApps.value)
        assertEquals(false, viewModel.uiState.value.allAppsLoading)
        assertEquals(false, viewModel.uiState.value.allAppsLoaded)
        assertEquals("package query failed", viewModel.uiState.value.allAppsError)
    }

    @Test
    fun `launchInstance delegates to launch use case`() = runTest {
        val viewModel = createViewModel()

        viewModel.launchInstance("instance-1")

        verify { launchUseCase.launch("instance-1") }
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

    private fun createViewModel(): LauncherViewModel {
        return LauncherViewModel(
            instanceManager = instanceManager,
            cloneCreateUseCase = cloneCreateUseCase,
            installedAppRepository = installedAppRepository,
            instanceLaunchUseCase = launchUseCase
        )
    }

    private fun testApp(): VirtualApp = VirtualApp(
        packageName = "com.example.app",
        appName = "Test App",
        versionName = "1.2.3",
        versionCode = 123L,
        apkPath = "C:/tmp/example.apk",
        instanceId = ""
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
