package com.multiapp.feature.appmanager

import android.content.Context
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
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
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        instanceManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { instanceManager.listInstances() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `init loads instances from model manager`() = runTest {
        val records = listOf(testRecord("instance-1"), testRecord("instance-2"))
        every { instanceManager.listInstances() } returns records

        val viewModel = AppManagerViewModel(instanceManager, context)

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
        assertEquals(records, viewModel.uiState.value.instances)
    }

    @Test
    fun `refresh reloads instances`() = runTest {
        val first = listOf(testRecord("instance-1"))
        val second = listOf(testRecord("instance-1"), testRecord("instance-2"))
        every { instanceManager.listInstances() } returnsMany listOf(first, second)
        val viewModel = AppManagerViewModel(instanceManager, context)

        viewModel.onEvent(AppManagerEvent.Refresh)

        assertEquals(second, viewModel.uiState.value.instances)
        verify(exactly = 2) { instanceManager.listInstances() }
    }

    @Test
    fun `delete removes instance and reloads list`() = runTest {
        every { instanceManager.deleteInstance("instance-1") } returns true
        val viewModel = AppManagerViewModel(instanceManager, context)

        viewModel.onEvent(AppManagerEvent.DeleteInstance("instance-1"))

        verify { instanceManager.deleteInstance("instance-1") }
        verify(exactly = 2) { instanceManager.listInstances() }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `delete failure sets error`() = runTest {
        every { instanceManager.deleteInstance("missing") } throws IllegalArgumentException("missing")
        val viewModel = AppManagerViewModel(instanceManager, context)

        viewModel.onEvent(AppManagerEvent.DeleteInstance("missing"))

        assertEquals("missing", viewModel.uiState.value.error)
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
