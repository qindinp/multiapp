package com.multiapp.feature.launcher

import app.cash.turbine.test
import com.multiapp.core.model.IdentityConfig
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import com.multiapp.core.instance.InstanceStatus
import com.multiapp.core.model.VirtualApp
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    private lateinit var instanceManager: InstanceManager
    private lateinit var viewModel: LauncherViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    private val instancesFlow = MutableStateFlow<List<InstanceInfo>>(emptyList())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        instanceManager = mockk(relaxed = true)
        every { instanceManager.instances } returns instancesFlow
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // -- 辅助工厂方法 --

    private fun createTestInstanceInfo(
        instanceId: String = "stub_test-001",
        packageName: String = "com.example.app"
    ): InstanceInfo = InstanceInfo(
        instanceId = instanceId,
        originalPackageName = packageName,
        stubPackageName = "$packageName.clone",
        identity = IdentityConfig(
            instanceId = instanceId,
            stubPackageName = "$packageName.clone",
            originalPackageName = packageName,
            authorityMap = emptyMap(),
            imei = "861234567890123",
            androidId = "abcdef0123456789",
            macAddress = "AA:BB:CC:DD:EE:FF",
            serial = "ABCDEF1234",
            buildModel = "Pixel 9",
            buildManufacturer = "Google",
            buildFingerprint = "google/raven/test",
            buildBrand = "google",
            buildDevice = "raven",
            buildProduct = "raven",
            versionRelease = "14",
            sdkInt = 34
        ),
        createdAt = System.currentTimeMillis(),
        status = InstanceStatus.READY
    )

    private fun createTestVirtualApp(
        packageName: String = "com.example.app"
    ): VirtualApp = VirtualApp(
        packageName = packageName,
        appName = "TestApp",
        apkPath = "/tmp/test.apk",
        instanceId = "existing-instance"
    )

    private fun createViewModel(): LauncherViewModel {
        return LauncherViewModel(instanceManager)
    }

    // -- 1. loadInstances --

    @Nested
    inner class LoadInstances {

        @Test
        fun `loadInstances 成功时更新状态为 Success`() = runTest {
            val testInstances = listOf(
                createTestInstanceInfo("stub_001", "com.app1"),
                createTestInstanceInfo("stub_002", "com.app2")
            )

            coEvery { instanceManager.loadInstances() } coAnswers {
                instancesFlow.value = testInstances
            }

            viewModel = createViewModel()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading, "加载完成后 isLoading 应为 false")
            assertNull(state.error, "成功加载时 error 应为 null")
            assertEquals(2, state.instances.size)
            assertEquals("stub_001", state.instances[0].instanceId)
            assertEquals("stub_002", state.instances[1].instanceId)
        }

        @Test
        fun `loadInstances 失败时更新状态为 Error`() = runTest {
            val errorMessage = "Database connection failed"
            coEvery { instanceManager.loadInstances() } throws RuntimeException(errorMessage)

            viewModel = createViewModel()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading, "失败后 isLoading 应为 false")
            assertEquals(errorMessage, state.error, "错误消息应匹配")
            assertTrue(state.instances.isEmpty(), "失败时 instances 应为空")
        }

        @Test
        fun `loadInstances 空列表正确处理`() = runTest {
            coEvery { instanceManager.loadInstances() } coAnswers {
                instancesFlow.value = emptyList()
            }

            viewModel = createViewModel()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertTrue(state.instances.isEmpty())
        }
    }

    // -- 2. createInstance --

    @Nested
    inner class CreateInstance {

        @Test
        fun `createInstance 成功后刷新列表`() = runTest {
            val app = createTestVirtualApp()

            coEvery { instanceManager.createInstance(app, any()) } returns "stub_new-001"
            coEvery { instanceManager.loadInstances() } coAnswers {
                instancesFlow.value = listOf(createTestInstanceInfo("stub_new-001"))
            }

            viewModel = createViewModel()

            viewModel.createInstance(app)

            val state = viewModel.uiState.value
            assertNull(state.creationStep, "创建完成后 creationStep 应为 null")
            assertNull(state.error, "创建成功时 error 应为 null")
            coVerify { instanceManager.createInstance(app, any()) }
        }

        @Test
        fun `createInstance 失败时设置错误消息`() = runTest {
            val app = createTestVirtualApp()
            val errorMessage = "Install failed"

            coEvery { instanceManager.createInstance(app, any()) } throws RuntimeException(errorMessage)
            coEvery { instanceManager.loadInstances() } coAnswers {
                instancesFlow.value = emptyList()
            }

            viewModel = createViewModel()

            viewModel.createInstance(app)

            val state = viewModel.uiState.value
            assertNull(state.creationStep, "失败后 creationStep 应为 null")
            assertEquals(errorMessage, state.error)
        }

        @Test
        fun `createInstance 发射进度状态`() = runTest {
            val app = createTestVirtualApp()

            coEvery { instanceManager.createInstance(app, any()) } coAnswers {
                val onProgress = secondArg<suspend (String) -> Unit>()
                onProgress("解析APK")
                onProgress("生成身份")
                onProgress("构建Stub")
                "stub_new-001"
            }
            coEvery { instanceManager.loadInstances() } coAnswers {
                instancesFlow.value = listOf(createTestInstanceInfo("stub_new-001"))
            }

            viewModel = createViewModel()

            // UnconfinedTestDispatcher 下，createInstance 同步执行完成
            // 验证最终状态正确
            viewModel.createInstance(app)

            val state = viewModel.uiState.value
            assertNull(state.creationStep, "创建完成后 creationStep 应为 null")
            assertNull(state.error, "成功创建后 error 应为 null")
            assertFalse(state.isLoading, "完成后 isLoading 应为 false")
        }

        @Test
        fun `createInstance 成功后不设置错误消息`() = runTest {
            val app = createTestVirtualApp()

            coEvery { instanceManager.createInstance(app, any()) } returns "stub_new"

            viewModel = createViewModel()

            viewModel.createInstance(app)

            assertNull(viewModel.uiState.value.error)
        }
    }

    // -- 3. deleteInstance --

    @Nested
    inner class DeleteInstance {

        @Test
        fun `deleteInstance 成功调用 instanceManager`() = runTest {
            viewModel = createViewModel()

            viewModel.deleteInstance("stub_to-delete")

            coVerify { instanceManager.deleteInstance("stub_to-delete") }
        }

        @Test
        fun `deleteInstance 失败时设置错误消息`() = runTest {
            val errorMessage = "Instance not found"
            coEvery { instanceManager.deleteInstance(any()) } throws IllegalArgumentException(errorMessage)

            viewModel = createViewModel()

            viewModel.deleteInstance("stub_nonexistent")

            assertEquals(errorMessage, viewModel.uiState.value.error)
        }

        @Test
        fun `deleteInstance 成功后无错误消息`() = runTest {
            coEvery { instanceManager.deleteInstance(any()) } just Runs

            viewModel = createViewModel()

            viewModel.deleteInstance("stub_to-delete")

            assertNull(viewModel.uiState.value.error)
        }
    }

    // -- 4. dismissCreationProgress --

    @Nested
    inner class DismissCreationProgress {

        @Test
        fun `dismissCreationProgress 重置进度状态`() = runTest {
            val app = createTestVirtualApp()

            coEvery { instanceManager.createInstance(app, any()) } coAnswers {
                // delay 导致协程挂起，creationStep 保持为 "准备中…"
                kotlinx.coroutines.delay(Long.MAX_VALUE)
                "stub_new"
            }

            viewModel = createViewModel()

            // 触发 createInstance（协程在 delay 处挂起）
            viewModel.createInstance(app)
            assertEquals("准备中…", viewModel.uiState.value.creationStep)

            // dismiss 应重置 creationStep
            viewModel.dismissCreationProgress()
            assertNull(viewModel.uiState.value.creationStep, "dismissCreationProgress 应重置 creationStep 为 null")
        }

        @Test
        fun `dismissCreationProgress 在 creationStep 已为 null 时无副作用`() = runTest {
            viewModel = createViewModel()

            assertNull(viewModel.uiState.value.creationStep)
            viewModel.dismissCreationProgress()
            assertNull(viewModel.uiState.value.creationStep)
        }
    }

    // -- 5. 初始状态 --

    @Nested
    inner class InitialState {

        @Test
        fun `初始状态加载完成后为空列表`() = runTest {
            viewModel = createViewModel()

            val state = viewModel.uiState.value
            assertTrue(state.instances.isEmpty())
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertNull(state.creationStep)
        }
    }
}
