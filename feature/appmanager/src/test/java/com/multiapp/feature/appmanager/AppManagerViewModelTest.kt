package com.multiapp.feature.appmanager

import app.cash.turbine.test
import com.google.gson.Gson
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import com.multiapp.core.instance.InstanceStatus
import com.multiapp.core.model.IdentityConfig
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
class AppManagerViewModelTest {

    private lateinit var instanceManager: InstanceManager
    private lateinit var viewModel: AppManagerViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    private val instancesFlow = MutableStateFlow<List<InstanceInfo>>(emptyList())
    private val gson = Gson()

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

    private fun createViewModel(): AppManagerViewModel {
        return AppManagerViewModel(instanceManager)
    }

    // -- 1. 初始加载实例列表 --

    @Nested
    inner class LoadInstances {

        @Test
        fun `loadInstances 成功时更新实例列表`() = runTest {
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
        fun `loadInstances 失败时设置错误消息`() = runTest {
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

        @Test
        fun `初始状态 isLoading 为 false`() = runTest {
            viewModel = createViewModel()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertNull(state.expandedInstanceId)
            assertTrue(state.dataSizeMap.isEmpty())
        }
    }

    // -- 2. 展开/折叠详情 --

    @Nested
    inner class ToggleExpand {

        @Test
        fun `toggleExpand 展开指定实例`() = runTest {
            viewModel = createViewModel()

            viewModel.onEvent(AppManagerEvent.ToggleExpand("stub_001"))

            assertEquals("stub_001", viewModel.uiState.value.expandedInstanceId)
        }

        @Test
        fun `toggleExpand 再次点击同一实例则折叠`() = runTest {
            viewModel = createViewModel()

            viewModel.onEvent(AppManagerEvent.ToggleExpand("stub_001"))
            assertEquals("stub_001", viewModel.uiState.value.expandedInstanceId)

            viewModel.onEvent(AppManagerEvent.ToggleExpand("stub_001"))
            assertNull(viewModel.uiState.value.expandedInstanceId)
        }

        @Test
        fun `toggleExpand 点击不同实例切换展开`() = runTest {
            viewModel = createViewModel()

            viewModel.onEvent(AppManagerEvent.ToggleExpand("stub_001"))
            assertEquals("stub_001", viewModel.uiState.value.expandedInstanceId)

            viewModel.onEvent(AppManagerEvent.ToggleExpand("stub_002"))
            assertEquals("stub_002", viewModel.uiState.value.expandedInstanceId)
        }
    }

    // -- 3. 删除实例（带撤销） --

    @Nested
    inner class DeleteInstance {

        @Test
        fun `deleteInstance 成功后发送 UndoDelete 事件`() = runTest {
            val instance = createTestInstanceInfo("stub_to-delete", "com.example.app")
            instancesFlow.value = listOf(instance)

            viewModel = createViewModel()

            viewModel.events.test {
                viewModel.onEvent(AppManagerEvent.DeleteInstance("stub_to-delete"))

                val event = awaitItem()
                assertTrue(event is AppManagerEvent.UndoDelete, "应发送 UndoDelete 事件")
                val undoEvent = event as AppManagerEvent.UndoDelete
                assertEquals("stub_to-delete", undoEvent.instanceId)
                val identity = gson.fromJson(undoEvent.identityJson, IdentityConfig::class.java)
                assertEquals("com.example.app", identity.originalPackageName)

                coVerify { instanceManager.deleteInstance("stub_to-delete") }
            }
        }

        @Test
        fun `deleteInstance 失败时设置错误消息`() = runTest {
            val errorMessage = "Instance not found"
            coEvery { instanceManager.deleteInstance(any()) } throws IllegalArgumentException(errorMessage)

            viewModel = createViewModel()

            viewModel.onEvent(AppManagerEvent.DeleteInstance("stub_nonexistent"))

            assertEquals(errorMessage, viewModel.uiState.value.error)
        }

        @Test
        fun `deleteInstance 失败时不发送 UndoDelete 事件`() = runTest {
            coEvery { instanceManager.deleteInstance(any()) } throws RuntimeException("fail")

            viewModel = createViewModel()

            viewModel.events.test {
                viewModel.onEvent(AppManagerEvent.DeleteInstance("stub_fail"))

                expectNoEvents()
            }
        }

        @Test
        fun `deleteInstance 成功后 error 为 null`() = runTest {
            instancesFlow.value = listOf(createTestInstanceInfo("stub_ok"))

            viewModel = createViewModel()

            viewModel.onEvent(AppManagerEvent.DeleteInstance("stub_ok"))

            assertNull(viewModel.uiState.value.error)
        }
    }

    // -- 4. UndoDelete --

    @Nested
    inner class UndoDelete {

        @Test
        fun `undoDelete 成功恢复实例`() = runTest {
            viewModel = createViewModel()

            viewModel.undoDelete("stub_restored", "{}")

            coVerify { instanceManager.undoDelete("stub_restored", "{}") }
            assertNull(viewModel.uiState.value.error)
        }

        @Test
        fun `undoDelete 失败时设置错误消息`() = runTest {
            val errorMessage = "Restore failed"
            coEvery { instanceManager.undoDelete(any(), any()) } throws RuntimeException(errorMessage)

            viewModel = createViewModel()

            viewModel.undoDelete("stub_fail", "{}")

            assertEquals(errorMessage, viewModel.uiState.value.error)
        }
    }

    // -- 5. Refresh --

    @Nested
    inner class Refresh {

        @Test
        fun `Refresh 事件触发重新加载`() = runTest {
            coEvery { instanceManager.loadInstances() } coAnswers {
                instancesFlow.value = listOf(createTestInstanceInfo("stub_001"))
            }

            viewModel = createViewModel()

            coEvery { instanceManager.loadInstances() } coAnswers {
                instancesFlow.value = listOf(
                    createTestInstanceInfo("stub_001"),
                    createTestInstanceInfo("stub_002")
                )
            }

            viewModel.onEvent(AppManagerEvent.Refresh)

            assertEquals(2, viewModel.uiState.value.instances.size)
            coVerify(exactly = 2) { instanceManager.loadInstances() }
        }
    }

    // -- 6. 错误处理 --

    @Nested
    inner class ErrorHandling {

        @Test
        fun `deleteInstance 失败后 error 正确显示`() = runTest {
            coEvery { instanceManager.deleteInstance(any()) } throws RuntimeException("delete error")

            viewModel = createViewModel()

            viewModel.onEvent(AppManagerEvent.DeleteInstance("stub_err"))

            assertEquals("delete error", viewModel.uiState.value.error)
        }

        @Test
        fun `undoDelete 失败后 error 正确显示`() = runTest {
            coEvery { instanceManager.undoDelete(any(), any()) } throws RuntimeException("undo error")

            viewModel = createViewModel()

            viewModel.undoDelete("stub_err", "{}")

            assertEquals("undo error", viewModel.uiState.value.error)
        }
    }
}
