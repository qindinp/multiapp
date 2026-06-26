package com.multiapp.feature.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.Context
import android.net.Uri
import com.multiapp.core.instance.InstanceInfo
import com.multiapp.core.instance.InstanceManager
import com.multiapp.core.model.CloneProfile
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
import java.io.File

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
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val profile = CloneProfile.forPackage(pkg.packageName)
                        val hasLauncher = packageManager.getLaunchIntentForPackage(pkg.packageName) != null
                        VirtualApp(
                            packageName = pkg.packageName,
                            appName = appInfo.loadLabel(packageManager).toString(),
                            icon = appInfo.loadIcon(packageManager),
                            versionName = pkg.versionName ?: "",
                            apkPath = appInfo.sourceDir,
                            instanceId = "",
                            mainActivity = packageManager.getLaunchIntentForPackage(pkg.packageName)?.component?.className,
                            isSystemApp = isSystem,
                            cloneProfile = profile,
                            riskLabel = when {
                                pkg.packageName == "com.qq.reader" -> "Protected baseline"
                                profile == CloneProfile.QQ_READER_SPECIAL -> "Special experiment"
                                !hasLauncher -> "无启动入口"
                                isSystem -> "系统应用"
                                else -> "普通"
                            }
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

    fun createInstanceFromApkUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(creationStep = "解析 APK…", error = null) }
            try {
                val apkFile = File(context.cacheDir, "picked-${System.currentTimeMillis()}.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    apkFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取选择的 APK")

                val pm = context.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA)
                    ?: error("无法解析 APK")
                val appInfo = pkgInfo.applicationInfo ?: error("APK 缺少 ApplicationInfo")
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath
                val packageName = pkgInfo.packageName ?: error("APK 缺少包名")
                val profile = CloneProfile.forPackage(packageName)
                val app = VirtualApp(
                    packageName = packageName,
                    appName = appInfo.loadLabel(pm).toString().ifBlank { packageName.substringAfterLast(".") },
                    icon = runCatching { appInfo.loadIcon(pm) }.getOrNull(),
                    versionName = pkgInfo.versionName ?: "",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode else @Suppress("DEPRECATION") pkgInfo.versionCode.toLong(),
                    apkPath = apkFile.absolutePath,
                    instanceId = "",
                    mainActivity = null,
                    isSystemApp = false,
                    cloneProfile = profile,
                    riskLabel = if (packageName == "com.qq.reader") "Protected baseline" else if (profile == CloneProfile.QQ_READER_SPECIAL) "Special experiment" else "APK file"
                )
                createInstance(app)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val (friendly, detail) = e.toUserError()
                _uiState.update { it.copy(creationStep = null, error = friendly, errorDetail = detail) }
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
