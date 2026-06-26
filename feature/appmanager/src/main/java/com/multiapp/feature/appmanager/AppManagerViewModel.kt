package com.multiapp.feature.appmanager

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import com.multiapp.core.common.formatBytes
import com.multiapp.core.common.getDirSize
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

data class AppManagerUiState(
    val instances: List<InstanceInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val expandedInstanceId: String? = null,
    val dataSizeMap: Map<String, String> = emptyMap()
)

sealed interface AppManagerEvent {
    data class DeleteInstance(val instanceId: String) : AppManagerEvent
    data class ToggleExpand(val instanceId: String) : AppManagerEvent
    data object Refresh : AppManagerEvent
    data class UndoDelete(val instanceId: String, val identityJson: String) : AppManagerEvent
    data class LaunchInstance(val instanceId: String) : AppManagerEvent
    data class LaunchFailed(val instanceId: String, val message: String) : AppManagerEvent
}

@HiltViewModel
class AppManagerViewModel @Inject constructor(
    private val instanceManager: InstanceManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppManagerUiState())
    val uiState: StateFlow<AppManagerUiState> = _uiState.asStateFlow()

    private val _events = Channel<AppManagerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val gson = Gson()

    private var loadJob: Job? = null

    init {
        loadInstances()
    }

    fun onEvent(event: AppManagerEvent) {
        when (event) {
            is AppManagerEvent.DeleteInstance -> deleteInstance(event.instanceId)
            is AppManagerEvent.ToggleExpand -> toggleExpand(event.instanceId)
            is AppManagerEvent.Refresh -> loadInstances()
            is AppManagerEvent.UndoDelete -> undoDelete(event.instanceId, event.identityJson)
            is AppManagerEvent.LaunchInstance -> launchInstance(event.instanceId)
            is AppManagerEvent.LaunchFailed -> {} // Handled by Screen
        }
    }

    private fun loadInstances() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                instanceManager.loadInstances()
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to load instances")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
        // 单独的观察协程，避免 collect 永不结束阻塞 loadJob
        viewModelScope.launch {
            instanceManager.instances
                .collect { instances ->
                    _uiState.update { it.copy(instances = instances) }
                    // 批量计算所有实例的数据目录大小
                    computeDataSizes(instances)
                }
        }
    }

    private fun computeDataSizes(instances: List<InstanceInfo>) {
        viewModelScope.launch {
            val sizeMap = withContext(Dispatchers.IO) {
                instances.associate { instance ->
                    instance.instanceId to try {
                        val dataDir = File("/data/data/${instance.stubPackageName}")
                        if (dataDir.exists()) formatBytes(getDirSize(dataDir)) else "—"
                    } catch (_: Exception) {
                        "—"
                    }
                }
            }
            _uiState.update { it.copy(dataSizeMap = sizeMap) }
        }
    }

    private fun deleteInstance(instanceId: String) {
        viewModelScope.launch {
            try {
                // Capture identity JSON before deletion for undo
                val instance = instanceManager.instances.value.find { it.instanceId == instanceId }
                val identityJson = instance?.let { gson.toJson(it.identity) } ?: ""

                instanceManager.deleteInstance(instanceId)

                // Emit snackbar event with undo capability
                _events.send(AppManagerEvent.UndoDelete(instanceId, identityJson))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to delete instance")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun undoDelete(instanceId: String, identityJson: String) {
        viewModelScope.launch {
            try {
                instanceManager.undoDelete(instanceId, identityJson)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to undo delete")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun launchInstance(instanceId: String) {
        viewModelScope.launch {
            try {
                val instance = instanceManager.instances.value.find { it.instanceId == instanceId }
                    ?: return@launch

                var intent = context.packageManager.getLaunchIntentForPackage(instance.stubPackageName)
                if (intent == null) {
                    intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(instance.stubPackageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val resolveInfos = context.packageManager.queryIntentActivities(intent, 0)
                    if (resolveInfos.isNotEmpty()) {
                        intent.setClassName(instance.stubPackageName, resolveInfos.first().activityInfo.name)
                    } else {
                        intent = null
                    }
                }
                if (intent?.component?.className.isNullOrEmpty()) {
                    _events.send(AppManagerEvent.LaunchFailed(instanceId, "无法启动：找不到入口 Activity"))
                } else {
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to launch instance")
                _events.send(AppManagerEvent.LaunchFailed(instanceId, e.message ?: "未知错误"))
            }
        }
    }

    private fun toggleExpand(instanceId: String) {
        _uiState.update { state ->
            state.copy(expandedInstanceId = if (state.expandedInstanceId == instanceId) null else instanceId)
        }
    }
}
