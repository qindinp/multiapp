package com.multiapp.feature.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.pm.PackageManager
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import com.multiapp.core.model.VirtualApp
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val errorDetail: String? = null,
    val creationStep: String? = null
)

@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val instanceManager: InstanceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _allApps = MutableStateFlow<List<VirtualApp>>(emptyList())
    val allApps = _allApps.asStateFlow()

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
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("LauncherVM", "Failed to load instances", e)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
        // 单独的观察协程，避免 collect 永不结束阻塞 loadJob
        viewModelScope.launch {
            instanceManager.instances
                .collect { instances ->
                    _uiState.update { it.copy(instances = instances) }
                }
        }
    }

    fun createInstance(app: VirtualApp) {
        viewModelScope.launch {
            _uiState.update { it.copy(creationStep = "准备中…", error = null) }

            try {
                Log.w("LauncherVM", "createInstance called for ${app.packageName}")
                instanceManager.createInstance(app) { step ->
                    Log.w("LauncherVM", "creation step: $step")
                    _uiState.update { it.copy(creationStep = step) }
                }

                _uiState.update { it.copy(creationStep = null) }
                loadInstances()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("LauncherVM", "Failed to create instance", e)
                val (friendly, detail) = e.toUserError()
                _uiState.update {
                    it.copy(creationStep = null, error = friendly, errorDetail = detail)
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
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("LauncherVM", "Failed to delete instance", e)
                _uiState.update { it.copy(error = e.message ?: "Unknown error") }
            }
        }
    }

    fun loadAllApps(packageManager: PackageManager) {
        if (_allApps.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                    .filter { it.packageName != "com.multiapp.app" }
                    .mapNotNull { pkg ->
                        val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                        VirtualApp(
                            packageName = pkg.packageName,
                            appName = appInfo.loadLabel(packageManager).toString(),
                            icon = appInfo.loadIcon(packageManager),
                            versionName = pkg.versionName ?: "",
                            apkPath = appInfo.sourceDir,
                            instanceId = "",
                            mainActivity = packageManager.getLaunchIntentForPackage(pkg.packageName)?.component?.className
                        )
                    }
                    .sortedBy { it.appName.lowercase() }
                _allApps.value = apps
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("LauncherVM", "Failed to load all apps", e)
            }
        }
    }

    /**
     * 将技术异常转换为用户友好的错误信息
     */
    private fun Throwable.toUserError(): Pair<String, String?> {
        val msg = message ?: ""
        return when {
            msg.contains("loader.dex not found") ->
                "创建失败" to "应用构建资源缺失，请重新安装 MultiApp"
            msg.contains("Origin APK not found") ->
                "找不到应用" to "目标应用可能已卸载，请重新安装后再试"
            msg.contains("No launcher activity") ->
                "不支持的应用" to "该应用没有启动入口，无法创建分身"
            msg.contains("INSTALL_FAILED_USER_RESTRICTED") ->
                "安装被阻止" to "请在系统设置中开启「允许安装未知来源应用」"
            msg.contains("INSTALL_FAILED") ->
                "安装失败" to "系统拒绝安装，请检查存储空间和权限"
            msg.contains("timeout", ignoreCase = true) ->
                "安装超时" to "请检查设备连接后重试"
            msg.contains("Cannot install on main thread") ->
                "内部错误" to "请稍后重试"
            msg.contains("SecurityException") ->
                "权限不足" to "请在系统设置中授予 MultiApp 所需权限"
            msg.contains("OutOfMemory") ->
                "内存不足" to "请关闭其他应用后重试"
            else -> "创建失败" to msg.take(100)
        }
    }
}
