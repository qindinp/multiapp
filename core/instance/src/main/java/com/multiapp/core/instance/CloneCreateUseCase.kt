package com.multiapp.core.instance

import com.multiapp.core.model.CloneCreationCoordinator
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.VirtualInstanceRecord
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID
import javax.inject.Inject

typealias CloneCreateAttempt = com.multiapp.core.model.CloneCreateAttempt
typealias CloneCreateResult = com.multiapp.core.model.CloneCreateResult
typealias CloneCreateFailureException = com.multiapp.core.model.CloneCreateFailureException

/**
 * Host-side clone creation coordinator that depends solely on [VirtualizationEngine].
 *
 * The engine is the single authority for instance lifecycle. Host-side code
 * only submits requests and interprets authoritative results; it neither
 * duplicates lifecycle state nor performs local compensation.
 */
class CloneCreateUseCase internal constructor(
    private val virtualizationEngine: VirtualizationEngine,
    private val clock: () -> Long,
    private val creationRequestIdFactory: () -> String
) : CloneCreationCoordinator {

    @Inject
    constructor(
        virtualizationEngine: VirtualizationEngine
    ) : this(
        virtualizationEngine = virtualizationEngine,
        clock = System::currentTimeMillis,
        creationRequestIdFactory = { UUID.randomUUID().toString() }
    )

    override fun suggestedDisplayName(app: VirtualApp): String {
        return nextDisplayName(app, virtualizationEngine.listInstances())
    }

    override fun prepareAttempt(
        app: VirtualApp,
        displayName: String?,
        pendingAttempt: CloneCreateAttempt?
    ): CloneCreateAttempt {
        val normalizedDisplayName = displayName.normalizedDisplayName()
        val payloadFingerprint = app.createPayloadFingerprint(normalizedDisplayName)
        return pendingAttempt
            ?.takeIf { it.payloadFingerprint == payloadFingerprint }
            ?: CloneCreateAttempt(
                creationRequestId = creationRequestIdFactory(),
                payloadFingerprint = payloadFingerprint,
                displayName = normalizedDisplayName ?: suggestedDisplayName(app)
            )
    }

    override fun create(app: VirtualApp, attempt: CloneCreateAttempt): Result<CloneCreateResult> {
        val startedAt = clock()
        var shouldRetainCreationRequestId = false

        return try {
            val request = CreateInstanceRequest(
                creationRequestId = attempt.creationRequestId,
                install = app.toEngineInstallRequest(),
                displayName = attempt.displayName
            )
            shouldRetainCreationRequestId = true
            val engineResult = virtualizationEngine.createInstance(request)
            val resultIsUnknown = engineResult.message == ENGINE_AUTHORITY_UNKNOWN_RESULT
            if (!engineResult.success) {
                shouldRetainCreationRequestId = resultIsUnknown
                throw IllegalStateException(engineResult.message ?: "engine createInstance failed")
            }
            val instanceId = engineResult.instanceId
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("engine createInstance returned no instanceId")
            shouldRetainCreationRequestId = false

            Result.success(
                CloneCreateResult(
                    instanceId = instanceId,
                    createLatencyMs = runCatching { clock() - startedAt }.getOrDefault(0L),
                    cleanupStatus = "engine_owned"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val (failureCode, userMessage, technicalReason) = e.toCreateFailure()
            Result.failure(
                CloneCreateFailureException(
                    failureCode = failureCode,
                    userMessage = userMessage,
                    technicalReason = technicalReason,
                    cleanupStatus = "engine_owned",
                    cause = e,
                    shouldRetainCreationRequestId = shouldRetainCreationRequestId
                )
            )
        }
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

private fun String?.normalizedDisplayName(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun VirtualApp.createPayloadFingerprint(requestedDisplayName: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.addValue("clone-create-payload-v1")
    digest.addValue(packageName)
    digest.addValue(apkPath)
    digest.addValue(versionCode.toString())
    digest.addValue(versionName)
    digest.addValue(targetSdkVersion.toString())
    digest.addValue(minSdkVersion.toString())
    digest.addValue(applicationClassName)
    digest.addValue(appName.ifBlank { packageName.substringAfterLast('.') })
    digest.addValues(requestedPermissions)
    digest.addValues(activities)
    digest.addValues(services)
    digest.addValues(receivers)
    digest.addValues(providers)
    digest.addValues(nativeAbis)
    digest.addValues(splitApkPaths)
    digest.addValues(splitPublicSourceDirs)
    digest.addValues(splitNames)
    digest.addValue(isolatedSplits.toString())
    digest.addValue(requestedDisplayName)
    return digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun MessageDigest.addValues(values: List<String>) {
    addValue(values.size.toString())
    values.forEach(::addValue)
}

private fun MessageDigest.addValue(value: String?) {
    if (value == null) {
        update(0)
        return
    }
    update(1)
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private fun VirtualApp.toEngineInstallRequest(): EnginePackageInstallRequest =
    EnginePackageInstallRequest(
        originPackageName = packageName,
        originApkPath = apkPath,
        versionCode = versionCode,
        versionName = versionName,
        targetSdk = targetSdkVersion,
        minSdk = minSdkVersion,
        applicationClassName = applicationClassName,
        packageLabel = appName.ifBlank { packageName.substringAfterLast('.') },
        requestedPermissions = requestedPermissions,
        activityClassNames = activities,
        serviceClassNames = services,
        receiverClassNames = receivers,
        providerClassNames = providers,
        nativeAbis = nativeAbis,
        splitApkPaths = splitApkPaths,
        splitPublicSourceDirs = splitPublicSourceDirs,
        splitNames = splitNames,
        isolatedSplits = isolatedSplits,
        debuggable = isDebuggable,
        sharedUserId = sharedUserId,
        sharedUserLabel = sharedUserLabel
    )

private const val ENGINE_AUTHORITY_UNKNOWN_RESULT =
    "engine_authority_unavailable_or_unknown_result"
