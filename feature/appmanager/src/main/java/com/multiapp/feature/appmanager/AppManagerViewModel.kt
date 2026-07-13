package com.multiapp.feature.appmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

internal var appManagerIoDispatcher: CoroutineDispatcher = Dispatchers.IO

data class AppManagerUiState(
    val instances: List<VirtualInstanceRecord> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val expandedInstanceId: String? = null
)

sealed interface AppManagerEvent {
    data class DeleteInstance(val instanceId: String) : AppManagerEvent
    data class ToggleExpand(val instanceId: String) : AppManagerEvent
    data object Refresh : AppManagerEvent
    data class LaunchInstance(val instanceId: String) : AppManagerEvent
    data class LaunchFailed(val instanceId: String, val message: String) : AppManagerEvent
}

@HiltViewModel
class AppManagerViewModel @Inject constructor(
    private val instanceManager: InstanceManager,
    private val virtualizationEngine: VirtualizationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppManagerUiState())
    val uiState: StateFlow<AppManagerUiState> = _uiState.asStateFlow()

    private val _events = Channel<AppManagerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        loadInstances()
    }

    fun onEvent(event: AppManagerEvent) {
        when (event) {
            is AppManagerEvent.DeleteInstance -> deleteInstance(event.instanceId)
            is AppManagerEvent.ToggleExpand -> toggleExpand(event.instanceId)
            is AppManagerEvent.Refresh -> loadInstances()
            is AppManagerEvent.LaunchInstance -> launchInstance(event.instanceId)
            is AppManagerEvent.LaunchFailed -> {} // Handled by Screen
        }
    }

    private fun loadInstances() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val instances = instanceManager.listInstances()
                _uiState.update { it.copy(instances = instances, isLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to load instances")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun deleteInstance(instanceId: String) {
        viewModelScope.launch(appManagerIoDispatcher) {
            try {
                val result = virtualizationEngine.deleteInstance(instanceId)
                if (result.success) {
                    loadInstances()
                } else {
                    _uiState.update { it.copy(error = result.message ?: "删除分身失败") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to delete instance")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun launchInstance(instanceId: String) {
        viewModelScope.launch(appManagerIoDispatcher) {
            val result = virtualizationEngine.launchInstance(LaunchInstanceRequest(instanceId = instanceId))
            if (!result.success) {
                Timber.e("Failed to launch instance via engine: ${result.message}")
                _events.send(AppManagerEvent.LaunchFailed(instanceId, result.message ?: "未知错误"))
            }
        }
    }

    private fun toggleExpand(instanceId: String) {
        _uiState.update { state ->
            state.copy(expandedInstanceId = if (state.expandedInstanceId == instanceId) null else instanceId)
        }
    }
}
