package com.multiapp.feature.appmanager

import com.multiapp.core.model.engine.EngineResult
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppManagerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var instanceManager: InstanceManager
    private lateinit var virtualizationEngine: VirtualizationEngine

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appManagerIoDispatcher = testDispatcher
        instanceManager = mockk(relaxed = true)
        virtualizationEngine = mockk(relaxed = true)
        every { instanceManager.listInstances() } returns emptyList()
        every { virtualizationEngine.launchInstance(any()) } returns EngineResult.pass(operation = "launchInstance")
        every { virtualizationEngine.deleteInstance(any()) } returns EngineResult.pass(operation = "deleteInstance")
    }

    @AfterEach
    fun tearDown() {
        appManagerIoDispatcher = Dispatchers.IO
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `init loads instances from model manager`() = runTest {
        val records = listOf(testRecord("instance-1"), testRecord("instance-2"))
        every { instanceManager.listInstances() } returns records

        val viewModel = AppManagerViewModel(instanceManager, virtualizationEngine)

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        assertEquals(records, viewModel.uiState.value.instances)
    }

    @Test
    fun `refresh reloads instances`() = runTest {
        val first = listOf(testRecord("instance-1"))
        val second = listOf(testRecord("instance-1"), testRecord("instance-2"))
        every { instanceManager.listInstances() } returnsMany listOf(first, second)
        val viewModel = AppManagerViewModel(instanceManager, virtualizationEngine)

        viewModel.onEvent(AppManagerEvent.Refresh)

        assertEquals(second, viewModel.uiState.value.instances)
        verify(exactly = 2) { instanceManager.listInstances() }
    }

    @Test
    fun `delete removes instance and reloads list`() = runTest {
        val viewModel = AppManagerViewModel(instanceManager, virtualizationEngine)

        viewModel.onEvent(AppManagerEvent.DeleteInstance("instance-1"))

        verify { virtualizationEngine.deleteInstance("instance-1") }
        verify(exactly = 0) { instanceManager.deleteInstance(any()) }
        verify(exactly = 2) { instanceManager.listInstances() }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `delete failure sets error`() = runTest {
        every { virtualizationEngine.deleteInstance("missing") } returns EngineResult.fail(
            operation = "deleteInstance",
            instanceId = "missing",
            message = "missing"
        )
        val viewModel = AppManagerViewModel(instanceManager, virtualizationEngine)

        viewModel.onEvent(AppManagerEvent.DeleteInstance("missing"))

        assertEquals("missing", viewModel.uiState.value.error)
    }

    @Test
    fun `launch failure emits launch failed event`() = runTest {
        every { virtualizationEngine.launchInstance(LaunchInstanceRequest(instanceId = "instance-1")) } returns
            EngineResult.fail(operation = "launchInstance", instanceId = "instance-1", message = "boom")
        val viewModel = AppManagerViewModel(instanceManager, virtualizationEngine)

        val eventJob = launch(testDispatcher) {
            assertEquals(AppManagerEvent.LaunchFailed("instance-1", "boom"), viewModel.events.first())
        }
        viewModel.onEvent(AppManagerEvent.LaunchInstance("instance-1"))
        eventJob.join()

        verify { virtualizationEngine.launchInstance(LaunchInstanceRequest(instanceId = "instance-1")) }
    }

    private fun testRecord(instanceId: String): VirtualInstanceRecord = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.${instanceId.replace("-", "")}",
        displayName = "Example",
        dataRoot = "C:/tmp/$instanceId",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )
}
