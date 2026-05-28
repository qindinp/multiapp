package com.multiapp.feature.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import com.multiapp.core.model.VirtualApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class LauncherUiState(
    val instances: List<InstanceInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val instanceManager: InstanceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    init {
        loadInstances()
    }

    fun loadInstances() {
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

    fun createInstance(app: VirtualApp) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                instanceManager.createInstance(app)
                loadInstances()
            } catch (e: Exception) {
                Timber.e(e, "Failed to create instance")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun deleteInstance(instanceId: String) {
        viewModelScope.launch {
            try {
                instanceManager.deleteInstance(instanceId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete instance")
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}
