package com.multiapp.feature.appmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class AppManagerUiState(
    val instances: List<InstanceInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val expandedInstanceId: String? = null
)

sealed interface AppManagerEvent {
    data class DeleteInstance(val instanceId: String) : AppManagerEvent
    data class ToggleExpand(val instanceId: String) : AppManagerEvent
    data object Refresh : AppManagerEvent
}

@HiltViewModel
class AppManagerViewModel @Inject constructor(
    private val instanceManager: InstanceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppManagerUiState())
    val uiState: StateFlow<AppManagerUiState> = _uiState.asStateFlow()

    init {
        loadInstances()
    }

    fun onEvent(event: AppManagerEvent) {
        when (event) {
            is AppManagerEvent.DeleteInstance -> deleteInstance(event.instanceId)
            is AppManagerEvent.ToggleExpand -> toggleExpand(event.instanceId)
            is AppManagerEvent.Refresh -> loadInstances()
        }
    }

    private fun loadInstances() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                instanceManager.loadInstances()
                instanceManager.instances.collect { instances ->
                    _uiState.value = _uiState.value.copy(
                        instances = instances,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load instances")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun deleteInstance(instanceId: String) {
        viewModelScope.launch {
            try {
                instanceManager.deleteInstance(instanceId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete instance")
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    private fun toggleExpand(instanceId: String) {
        val current = _uiState.value.expandedInstanceId
        _uiState.value = _uiState.value.copy(
            expandedInstanceId = if (current == instanceId) null else instanceId
        )
    }
}
