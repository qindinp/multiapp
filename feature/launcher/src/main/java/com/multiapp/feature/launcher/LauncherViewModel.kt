package com.multiapp.feature.launcher

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.multiapp.core.model.CloneCreateAttempt
import com.multiapp.core.model.CloneCreateFailureException
import com.multiapp.core.model.CloneCreationCoordinator
import com.multiapp.core.model.InstalledAppCatalog
import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.VirtualApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject

internal var launcherIoDispatcher: CoroutineDispatcher = Dispatchers.IO
internal var instancesLoadTimeoutMs: Long = 15_000L
internal var allAppsLoadTimeoutMs: Long = 15_000L

internal fun normalizeApkComponentName(packageName: String, name: String?): String? {
    if (name.isNullOrBlank()) return null
    val trimmed = name.trim()
    return when {
        trimmed.startsWith(".") -> packageName + trimmed
        '.' !in trimmed -> "$packageName.$trimmed"
        else -> trimmed
    }
}

private const val PENDING_CREATE_ATTEMPT_KEY = "launcher.pending_create_attempt"
private const val PENDING_CREATE_ATTEMPT_FIELD_COUNT = 3

data class LauncherUiState(
    val instances: List<VirtualInstanceRecord> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorDetail: String? = null,
    val creationStep: String? = null,
    val lastCreatedInstanceId: String? = null,
    val importedApkCandidate: VirtualApp? = null,
    val lastCreateLatencyMs: Long? = null,
    val allAppsLoading: Boolean = false,
    val allAppsLoaded: Boolean = false,
    val allAppsError: String? = null
)

@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val cloneCreationCoordinator: CloneCreationCoordinator,
    private val installedAppCatalog: InstalledAppCatalog,
    private val virtualizationEngine: VirtualizationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val _allApps = MutableStateFlow<List<VirtualApp>>(emptyList())
    val allApps = _allApps.asStateFlow()

    private var loadJob: Job? = null
    private var loadTimeoutJob: Job? = null
    private var allAppsJob: Job? = null
    private var allAppsTimeoutJob: Job? = null
    private val launchRequestsInFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        loadInstances()
    }

    fun loadInstances() {
        loadJob?.cancel()
        loadTimeoutJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }

        val job = viewModelScope.launch(launcherIoDispatcher) {
            try {
                val records = virtualizationEngine.listInstances()
                ensureActive()
                _uiState.update { it.copy(instances = records, isLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to load instances")
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
        loadJob = job

        val timeoutJob = viewModelScope.launch {
            delay(instancesLoadTimeoutMs)
            if (loadJob === job && job.isActive) {
                Timber.w("Timed out loading instances after ${instancesLoadTimeoutMs}ms")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "读取分身列表超时，请重试"
                    )
                }
                loadJob = null
                loadTimeoutJob = null
                job.cancel()
            }
        }
        loadTimeoutJob = timeoutJob
        job.invokeOnCompletion {
            if (loadJob === job) loadJob = null
            if (loadTimeoutJob === timeoutJob) {
                loadTimeoutJob = null
                timeoutJob.cancel()
            }
        }
    }

    fun createInstance(
        app: VirtualApp,
        displayName: String? = null
    ) {
        val currentState = _uiState.value
        if (currentState.creationStep != null) return
        _uiState.value = currentState.copy(
            creationStep = "读取应用信息…",
            error = null,
            errorDetail = null,
            lastCreatedInstanceId = null,
            importedApkCandidate = null,
            lastCreateLatencyMs = null
        )
        viewModelScope.launch(launcherIoDispatcher) {
            var createAttempt: CloneCreateAttempt? = null
            try {
                _uiState.update { it.copy(creationStep = "复制 APK 并导入元数据…") }
                createAttempt = cloneCreationCoordinator.prepareAttempt(
                    app = app,
                    displayName = displayName,
                    pendingAttempt = pendingCreateAttempt()
                )
                savePendingCreateAttempt(createAttempt)
                val createResult = cloneCreationCoordinator.create(app, createAttempt).getOrThrow()
                clearPendingCreateAttempt(createAttempt)

                _uiState.update { it.copy(creationStep = "刷新分身列表…") }
                val records = virtualizationEngine.listInstances()
                _uiState.update {
                    it.copy(
                        instances = records,
                        isLoading = false,
                        creationStep = null,
                        lastCreatedInstanceId = createResult.instanceId,
                        lastCreateLatencyMs = createResult.createLatencyMs
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e !is CloneCreateFailureException || !e.shouldRetainCreationRequestId) {
                    createAttempt?.let(::clearPendingCreateAttempt)
                }
                Timber.e(e, "Failed to create instance")
                val (friendly, detail) = e.toUserError()
                _uiState.update {
                    it.copy(creationStep = null, error = friendly, errorDetail = detail)
                }
            }
        }
    }

    private fun pendingCreateAttempt(): CloneCreateAttempt? {
        val values = savedStateHandle.get<ArrayList<String>>(PENDING_CREATE_ATTEMPT_KEY)
            ?: return null
        return runCatching {
            require(values.size == PENDING_CREATE_ATTEMPT_FIELD_COUNT)
            CloneCreateAttempt(
                creationRequestId = values[0],
                payloadFingerprint = values[1],
                displayName = values[2]
            )
        }.getOrElse {
            savedStateHandle.remove<ArrayList<String>>(PENDING_CREATE_ATTEMPT_KEY)
            null
        }
    }

    private fun savePendingCreateAttempt(attempt: CloneCreateAttempt) {
        savedStateHandle[PENDING_CREATE_ATTEMPT_KEY] = arrayListOf(
            attempt.creationRequestId,
            attempt.payloadFingerprint,
            attempt.displayName
        )
    }

    private fun clearPendingCreateAttempt(attempt: CloneCreateAttempt) {
        if (pendingCreateAttempt()?.creationRequestId == attempt.creationRequestId) {
            savedStateHandle.remove<ArrayList<String>>(PENDING_CREATE_ATTEMPT_KEY)
        }
    }

    fun suggestedDisplayName(app: VirtualApp): String = cloneCreationCoordinator.suggestedDisplayName(app)

    fun clearError() {
        _uiState.update { it.copy(error = null, errorDetail = null) }
    }

    fun clearLastCreatedInstance() {
        _uiState.update { it.copy(lastCreatedInstanceId = null) }
    }

    fun clearImportedApkCandidate() {
        _uiState.update { it.copy(importedApkCandidate = null) }
    }

    fun launchInstance(instanceId: String) {
        if (!launchRequestsInFlight.add(instanceId)) return
        viewModelScope.launch(launcherIoDispatcher) {
            try {
                val result = virtualizationEngine.launchInstance(LaunchInstanceRequest(instanceId = instanceId, providerHookEnabled = true))
                if (!result.success) {
                    Timber.e("Failed to launch instance via engine: ${result.message}")
                    _uiState.update {
                        it.copy(error = "启动失败", errorDetail = result.message ?: "无法打开分身")
                    }
                }
            } finally {
                launchRequestsInFlight.remove(instanceId)
            }
        }
    }

    fun importApkFile(context: Context, uri: Uri) {
        val currentState = _uiState.value
        if (currentState.creationStep != null) return
        _uiState.value = currentState.copy(
            creationStep = "复制 APK 文件…",
            error = null,
            errorDetail = null,
            importedApkCandidate = null
        )
        viewModelScope.launch(launcherIoDispatcher) {
            try {
                val apkFile = copyApkToImportDir(context.applicationContext, uri)
                _uiState.update { it.copy(creationStep = "解析 APK 信息…") }
                val app = parseApkFile(context.applicationContext, apkFile)
                _uiState.update {
                    it.copy(
                        creationStep = null,
                        importedApkCandidate = app
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to import APK file")
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

    fun stopInstance(instanceId: String) {
        viewModelScope.launch(launcherIoDispatcher) {
            try {
                val result = virtualizationEngine.stopInstance(instanceId)
                if (result.success) {
                    loadInstances()
                } else {
                    _uiState.update {
                        it.copy(error = "停止分身失败", errorDetail = result.message ?: "无法停止分身")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to stop instance")
                _uiState.update { it.copy(error = "停止分身失败", errorDetail = e.message) }
            }
        }
    }

    fun deleteInstance(instanceId: String) {
        viewModelScope.launch(launcherIoDispatcher) {
            try {
                val result = virtualizationEngine.deleteInstance(instanceId)
                if (result.success) {
                    loadInstances()
                } else {
                    _uiState.update {
                        it.copy(error = "删除分身失败", errorDetail = result.message)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to delete instance")
                _uiState.update { it.copy(error = "删除分身失败", errorDetail = e.message) }
            }
        }
    }

    fun loadAllApps(forceRefresh: Boolean = false) {
        val currentState = _uiState.value
        // 列表为空时允许重新加载：首次加载因权限未授予拿到空列表后，
        // 不能因 allAppsLoaded=true 而永久跳过后续查询（权限授予后需能刷新）。
        if (!forceRefresh && currentState.allAppsLoaded && _allApps.value.isNotEmpty()) return
        allAppsJob?.takeIf { it.isActive }?.let { activeJob ->
            if (!forceRefresh) return
            activeJob.cancel()
            allAppsTimeoutJob?.cancel()
        }

        _uiState.update {
            it.copy(
                allAppsLoading = true,
                allAppsError = null
            )
        }

        val job = viewModelScope.launch(launcherIoDispatcher) {
            try {
                val apps = installedAppCatalog.listInstalledApps(forceRefresh)
                ensureActive()
                _allApps.value = apps
                _uiState.update {
                    it.copy(
                        allAppsLoading = false,
                        allAppsLoaded = true,
                        allAppsError = null
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Failed to load all apps")
                _uiState.update {
                    it.copy(
                        allAppsLoading = false,
                        allAppsLoaded = _allApps.value.isNotEmpty(),
                        allAppsError = e.message ?: "Failed to load app list"
                    )
                }
            }
        }
        allAppsJob = job

        val timeoutJob = viewModelScope.launch {
            delay(allAppsLoadTimeoutMs)
            if (allAppsJob === job && job.isActive) {
                Timber.w("Timed out loading all apps after ${allAppsLoadTimeoutMs}ms")
                _uiState.update {
                    it.copy(
                        allAppsLoading = false,
                        allAppsLoaded = _allApps.value.isNotEmpty(),
                        allAppsError = "读取应用列表超时，请重试"
                    )
                }
                allAppsJob = null
                allAppsTimeoutJob = null
                job.cancel()
            }
        }
        allAppsTimeoutJob = timeoutJob
        job.invokeOnCompletion {
            if (allAppsJob === job) allAppsJob = null
            if (allAppsTimeoutJob === timeoutJob) {
                allAppsTimeoutJob = null
                timeoutJob.cancel()
            }
        }
    }

    private fun copyApkToImportDir(context: Context, uri: Uri): File {
        val importDir = File(context.filesDir, "imported_apks").apply { mkdirs() }
        val file = File(importDir, "import-${System.currentTimeMillis()}.apk").canonicalFile
        require(file.parentFile == importDir.canonicalFile) { "APK import path escapes import dir" }
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取 APK 文件")
        require(file.isFile && file.length() > 0L) { "APK file is empty" }
        return file
    }

    private fun parseApkFile(context: Context, apkFile: File): VirtualApp {
        val packageManager = context.packageManager
        val info = packageManager.getArchivePackageInfo(apkFile.absolutePath)
        val manifest = ManifestParser(context.applicationContext).parse(apkFile)
        val packageName = manifest.packageName.ifBlank {
            info?.packageName ?: throw IllegalArgumentException("无法解析 APK 文件")
        }
        val appInfo = info?.applicationInfo
        appInfo?.sourceDir = apkFile.absolutePath
        appInfo?.publicSourceDir = apkFile.absolutePath

        val launcher = ComponentExtractor().extractLauncherActivity(manifest)
        val mainActivity = normalizeApkComponentName(
            packageName,
            launcher?.targetActivityName ?: launcher?.name
        ) ?: inferLauncherActivity(packageName, info)
        require(mainActivity != null) { "No launcher activity" }

        return VirtualApp(
            packageName = packageName,
            appName = appInfo?.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                ?: manifest.applicationLabel
                ?: packageName,
            versionName = info?.versionName ?: "unknown",
            versionCode = info?.longVersionCode?.takeIf { it > 0L } ?: 1L,
            apkPath = apkFile.absolutePath,
            instanceId = "",
            mainActivity = mainActivity,
            isSystemApp = false,
            targetSdkVersion = manifest.targetSdkVersion,
            minSdkVersion = manifest.minSdkVersion,
            applicationClassName = normalizeApkComponentName(packageName, manifest.applicationClass)
                ?: appInfo?.className,
            requestedPermissions = manifest.permissions.ifEmpty {
                info?.requestedPermissions?.toList().orEmpty()
            },
            activities = manifest.activities.mapNotNull { normalizeApkComponentName(packageName, it.name) },
            services = manifest.services.mapNotNull { normalizeApkComponentName(packageName, it.name) },
            receivers = manifest.receivers.mapNotNull { normalizeApkComponentName(packageName, it.name) },
            providers = manifest.providers.mapNotNull { normalizeApkComponentName(packageName, it.name) },
            nativeAbis = detectNativeAbis(apkFile),
            activityAliases = manifest.activities
                .mapNotNull { component ->
                    val alias = normalizeApkComponentName(packageName, component.name)
                    val target = normalizeApkComponentName(packageName, component.targetActivityName)
                    if (alias != null && target != null) alias to target else null
                }
                .toMap()
        )
    }

    private fun inferLauncherActivity(packageName: String, info: PackageInfo?): String? {
        val activities = info?.activities?.mapNotNull { it.name }.orEmpty()
        return when {
            activities.size == 1 -> activities.first()
            else -> activities.firstOrNull { it.substringAfterLast(".") == "MainActivity" }
        }?.let { normalizeApkComponentName(packageName, it) }
    }

    private fun PackageManager.getArchivePackageInfo(apkPath: String): PackageInfo? {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= 33) {
            getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(apkPath, flags)
        }
    }

    private fun detectNativeAbis(apkFile: File): List<String> {
        return runCatching {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .mapNotNull { it.split("/").getOrNull(1) }
                    .filter { it.isNotBlank() }
                    .toSet()
                    .sorted()
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 将技术异常转换为用户友好的错误信息
     */
    private fun Exception.toUserError(): Pair<String, String?> {
        if (this is CloneCreateFailureException) {
            return userMessage to listOfNotNull(
                technicalReason,
                cleanupStatus.takeIf { it != "not_required" }?.let { "cleanup=$it" }
            ).joinToString("\n").takeIf { it.isNotBlank() }
        }
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
            msg.contains("InstallRecord not found") ->
                "创建失败" to "应用信息导入失败，请重试"
            else -> "创建失败" to msg.take(100)
        }
    }
}
