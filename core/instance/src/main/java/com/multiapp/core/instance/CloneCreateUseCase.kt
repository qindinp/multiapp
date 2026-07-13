package com.multiapp.core.instance

import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.VirtualInstallService
import java.util.concurrent.CancellationException
import javax.inject.Inject

data class CloneCreateResult(
    val instance: VirtualInstanceRecord,
    val createLatencyMs: Long,
    val cleanupStatus: String
)

class CloneCreateFailureException(
    val failureCode: String,
    val userMessage: String,
    val technicalReason: String?,
    val cleanupStatus: String,
    cause: Throwable
) : RuntimeException(technicalReason ?: userMessage, cause)

class CloneCreateUseCase internal constructor(
    private val instanceManager: InstanceManager,
    private val virtualInstallService: VirtualInstallService,
    private val virtualizationEngine: VirtualizationEngine,
    private val clock: () -> Long
) {

    @Inject
    constructor(
        instanceManager: InstanceManager,
        virtualInstallService: VirtualInstallService,
        virtualizationEngine: VirtualizationEngine
    ) : this(
        instanceManager = instanceManager,
        virtualInstallService = virtualInstallService,
        virtualizationEngine = virtualizationEngine,
        clock = System::currentTimeMillis
    )

    fun suggestedDisplayName(app: VirtualApp): String {
        return nextDisplayName(app, instanceManager.listInstances())
    }

    fun create(app: VirtualApp, displayName: String? = null): Result<CloneCreateResult> {
        val startedAt = clock()
        val hadInstallRecord = virtualInstallService.hasInstallRecord(app.packageName)
        var createdInstanceId: String? = null

        return try {
            val instanceName = displayName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: suggestedDisplayName(app)

            virtualInstallService.ensureInstallRecord(app).getOrThrow()
            val created = instanceManager.createInstance(
                originPackageName = app.packageName,
                displayName = instanceName,
                compatibilityMode = CompatibilityMode.DEFAULT
            ).getOrThrow()
            createdInstanceId = created.instanceId

            Result.success(
                CloneCreateResult(
                    instance = created,
                    createLatencyMs = clock() - startedAt,
                    cleanupStatus = "not_required"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cleanupStatus = rollback(app.packageName, hadInstallRecord, createdInstanceId)
            val (failureCode, userMessage, technicalReason) = e.toCreateFailure()
            Result.failure(
                CloneCreateFailureException(
                    failureCode = failureCode,
                    userMessage = userMessage,
                    technicalReason = technicalReason,
                    cleanupStatus = cleanupStatus,
                    cause = e
                )
            )
        }
    }

    private fun rollback(
        packageName: String,
        hadInstallRecord: Boolean,
        createdInstanceId: String?
    ): String {
        val cleanup = mutableListOf<String>()
        var instanceDeleted = createdInstanceId == null
        createdInstanceId?.let { instanceId ->
            instanceDeleted = runCatching {
                virtualizationEngine.deleteInstance(instanceId).success
            }.getOrDefault(false)
            cleanup += if (instanceDeleted) {
                "instance_deleted"
            } else {
                "instance_delete_skipped"
            }
        }
        if (!hadInstallRecord && instanceDeleted) {
            cleanup += if (runCatching { virtualInstallService.deleteInstallRecord(packageName) }.getOrDefault(false)) {
                "install_deleted"
            } else {
                "install_delete_skipped"
            }
        } else if (!hadInstallRecord) {
            cleanup += "install_preserved"
        }
        return cleanup.ifEmpty { listOf("not_required") }.joinToString(",")
    }

    private fun nextDisplayName(app: VirtualApp, instances: List<VirtualInstanceRecord>): String {
        val baseName = app.appName.ifBlank { app.packageName.substringAfterLast(".") }
        val existingCount = instances.count { it.originPackageName == app.packageName }
        return if (existingCount == 0) baseName else "$baseName ${existingCount + 1}"
    }

    private fun Exception.toCreateFailure(): Triple<String, String, String?> {
        val msg = message.orEmpty()
        return when {
            msg.contains("loader.dex not found") ->
                Triple("missing_loader_dex", "创建失败", "应用构建资源缺失，请重新安装 MultiApp")
            msg.contains("Origin APK not found") || msg.contains("APK file not found") ->
                Triple("origin_apk_missing", "找不到应用", "目标应用可能已卸载，请重新安装后再试")
            msg.contains("No launcher activity") ->
                Triple("no_launcher_activity", "不支持的应用", "该应用没有启动入口，无法创建分身")
            msg.contains("INSTALL_FAILED_USER_RESTRICTED") ->
                Triple("install_user_restricted", "安装被阻止", "请在系统设置中开启允许安装未知来源应用")
            msg.contains("INSTALL_FAILED") ->
                Triple("install_failed", "安装失败", "系统拒绝安装，请检查存储空间和权限")
            msg.contains("timeout", ignoreCase = true) ->
                Triple("timeout", "安装超时", "请检查设备连接后重试")
            msg.contains("SecurityException") ->
                Triple("permission_denied", "权限不足", "请在系统设置中授予 MultiApp 所需权限")
            msg.contains("OutOfMemory") ->
                Triple("out_of_memory", "内存不足", "请关闭其他应用后重试")
            msg.contains("InstallRecord not found") ->
                Triple("install_record_missing", "创建失败", "应用信息导入失败，请重试")
            else -> Triple("create_failed", "创建失败", msg.take(120).ifBlank { null })
        }
    }
}
