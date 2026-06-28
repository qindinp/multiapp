package com.multiapp.feature.launcher

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.installer.ImportResult
import com.multiapp.core.model.installer.VirtualInstallService
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var instanceManager: InstanceManager
    private lateinit var installService: VirtualInstallService

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        instanceManager = mockk(relaxed = true)
        installService = mockk(relaxed = true)
        every { instanceManager.listInstances() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `createInstance ensures install record before creating instance`() = runTest {
        val app = testApp()
        val record = testRecord(originPackageName = app.packageName, displayName = app.appName)
        every { installService.ensureInstallRecord(app) } returns Result.success(mockk<ImportResult>())
        every {
            instanceManager.createInstance(app.packageName, app.appName, CompatibilityMode.DEFAULT)
        } returns Result.success(record)
        every { instanceManager.listInstances() } returnsMany listOf(emptyList(), listOf(record))

        val viewModel = LauncherViewModel(instanceManager, installService)

        viewModel.createInstance(app)

        verifyOrder {
            installService.ensureInstallRecord(app)
            instanceManager.createInstance(app.packageName, app.appName, CompatibilityMode.DEFAULT)
        }
        assertEquals(listOf(record), viewModel.uiState.value.instances)
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.creationStep)
    }

    @Test
    fun `createInstance does not create instance when install import fails`() = runTest {
        val app = testApp()
        every { installService.ensureInstallRecord(app) } returns
            Result.failure(IllegalArgumentException("APK file not found: ${app.apkPath}"))

        val viewModel = LauncherViewModel(instanceManager, installService)

        viewModel.createInstance(app)

        verify(exactly = 0) {
            instanceManager.createInstance(any(), any(), any())
        }
        assertNotNull(viewModel.uiState.value.error)
        assertEquals("APK file not found: ${app.apkPath}", viewModel.uiState.value.errorDetail)
        assertNull(viewModel.uiState.value.creationStep)
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
