package com.multiapp.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var viewModel: SettingsViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = SettingsViewModel()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Nested
    inner class InitialState {

        @Test
        fun `初始状态 appVersion 为默认值`() {
            assertEquals("1.0.0", viewModel.uiState.value.appVersion)
        }

        @Test
        fun `初始状态 packageName 为默认值`() {
            assertEquals("com.multiapp.app", viewModel.uiState.value.packageName)
        }

        @Test
        fun `初始状态 buildType 为默认值`() {
            assertEquals("debug", viewModel.uiState.value.buildType)
        }
    }

    @Nested
    inner class DataCorrectness {

        @Test
        fun `SettingsUiState 默认构造产生正确字段`() {
            val state = SettingsUiState()
            assertEquals("1.0.0", state.appVersion)
            assertEquals("com.multiapp.app", state.packageName)
            assertEquals("debug", state.buildType)
        }

        @Test
        fun `SettingsUiState 自定义构造产生正确字段`() {
            val state = SettingsUiState(
                appVersion = "2.1.0",
                packageName = "com.custom.app",
                buildType = "release"
            )
            assertEquals("2.1.0", state.appVersion)
            assertEquals("com.custom.app", state.packageName)
            assertEquals("release", state.buildType)
        }

        @Test
        fun `uiState 流与直接访问值一致`() {
            val flowValue = viewModel.uiState.value
            assertEquals(flowValue.appVersion, "1.0.0")
            assertEquals(flowValue.packageName, "com.multiapp.app")
            assertEquals(flowValue.buildType, "debug")
        }
    }
}
