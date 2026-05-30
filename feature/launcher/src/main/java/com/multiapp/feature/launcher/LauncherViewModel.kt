package com.multiapp.feature.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import com.multiapp.core.model.VirtualApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class LauncherUiState(
    val instances: List<InstanceInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val creationStep: String? = null
)

@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val instanceManager: InstanceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadInstances()
    }

    fun loadInstances() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                instanceManager.loadInstances()
                instanceManager.instances.collect { instances ->
                    _uiState.update { it.copy(instances = instances, isLoading = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to load instances")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun createInstance(app: VirtualApp) {
        viewModelScope.launch {
            _uiState.update { it.copy(creationStep = "准备中…", error = null) }

            try {
                instanceManager.createInstance(app) { step ->
                    _uiState.update { it.copy(creationStep = step) }
                }

                _uiState.update { it.copy(creationStep = null) }
                loadInstances()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to create instance")
                _uiState.update {
                    it.copy(creationStep = null, error = e.message)
                }
            }
        }
    }

    fun dismissCreationProgress() {
        _uiState.update { it.copy(creationStep = null) }
    }

    fun deleteInstance(instanceId: String) {
        viewModelScope.launch {
            try {
                instanceManager.deleteInstance(instanceId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to delete instance")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}
