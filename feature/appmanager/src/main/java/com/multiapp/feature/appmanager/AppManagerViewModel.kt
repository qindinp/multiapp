package com.multiapp.feature.appmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multiapp.core.instance.InstanceLaunchUseCase
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val instanceLaunchUseCase: InstanceLaunchUseCase
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
        viewModelScope.launch {
            try {
                instanceManager.deleteInstance(instanceId)
                loadInstances()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to delete instance")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun launchInstance(instanceId: String) {
        viewModelScope.launch {
            val result = instanceLaunchUseCase.launch(instanceId)
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                Timber.e(error, "Failed to launch instance")
                _events.send(AppManagerEvent.LaunchFailed(instanceId, error.message ?: "未知错误"))
            }
        }
    }

    private fun toggleExpand(instanceId: String) {
        _uiState.update { state ->
            state.copy(expandedInstanceId = if (state.expandedInstanceId == instanceId) null else instanceId)
        }
    }
}
