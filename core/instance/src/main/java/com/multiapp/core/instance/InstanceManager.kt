package com.multiapp.core.instance

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.multiapp.core.identity.DeviceIdentityPool
import com.multiapp.core.model.IdentityConfig
import com.multiapp.core.hook.IdentitySpoofingEngine
import com.multiapp.core.installer.StubInstaller
import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.DeviceIdentityConfig
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.StubConfig
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.stub.StubBuilder
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实例生命周期管理
 * 创建、启动、停止、删除分身实例
 */
@Singleton
class InstanceManager @Inject constructor(
    private val instanceDatabase: InstanceDatabase,
    private val stubBuilder: StubBuilder,
    private val stubInstaller: StubInstaller,
    @ApplicationContext private val context: Context,
    private val identitySpoofingEngine: IdentitySpoofingEngine,
    private val parser: ManifestParser = ManifestParser(context),
    private val extractor: ComponentExtractor = ComponentExtractor()
) {
    private val _instances = MutableStateFlow<List<InstanceInfo>>(emptyList())
    val instances: StateFlow<List<InstanceInfo>> = _instances.asStateFlow()

    private val gson = Gson()

    /**
     * 创建分身实例
     *
     * 流程:
     * 1. 生成 instanceId
     * 2. 用 DeviceIdentityPool 生成设备身份
     * 3. 用 ManifestParser 解析原始 APK
     * 4. 用 ComponentExtractor 提取 launcher Activity
     * 5. 构建 StubConfig
     * 6. 用 StubBuilder.build() 构建 Stub APK
     * 7. 用 StubInstaller.install() 安装
     * 8. 创建 InstanceEntity 保存到数据库
     * 9. 更新 _instances StateFlow
     *
     * @param app 要创建分身的虚拟应用
     * @return 新创建实例的 instanceId
     * @throws RuntimeException 如果安装失败
     * @throws IllegalArgumentException 如果原始 APK 不存在或无 launcher Activity
     */
    suspend fun createInstance(
        app: VirtualApp,
        onProgress: suspend (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        Log.w("InstanceMgr", "createInstance: ${app.packageName}, apkPath=${app.apkPath}")
        val instanceId = "stub_${UUID.randomUUID().toString().replace("-", "")}"

        // 1. 生成设备身份 (DeviceIdentityPool 是 object 单例，直接调用)
        onProgress("解析APK")
        val originApkFile = File(app.apkPath)
        require(originApkFile.exists()) { "Origin APK not found: ${app.apkPath}" }
        val manifest = parser.parse(originApkFile)
        Log.w("InstanceMgr", "parsed manifest: ${manifest.activities.size} activities")

        // 2. 提取 launcher Activity
        val launcherActivity = extractor.extractLauncherActivity(manifest)
            ?: error("No launcher activity found in ${app.packageName}")
        Log.w("InstanceMgr", "launcher activity = ${launcherActivity.name}")

        onProgress("生成身份")
        val identity = DeviceIdentityPool.generateIdentity(instanceId, app.packageName)
        Log.w("InstanceMgr", "identity generated, stubPackage=${identity.stubPackageName}")

        // 2.5. 将身份同步到 IdentitySpoofingEngine，确保运行时使用相同的身份
        try {
            val deviceProfile = identity.toDeviceProfile()
            identitySpoofingEngine.applyDeviceProfile(deviceProfile, instanceId, identity)
            Log.w("InstanceMgr", "identity synced to IdentitySpoofingEngine")
        } catch (e: Throwable) {
            Log.e("InstanceMgr", "applyDeviceProfile failed (non-fatal, continuing)", e)
        }

        // 3. 将 IdentityConfig 转换为 StubConfig 所需的 DeviceIdentityConfig
        val deviceIdentityConfig = DeviceIdentityConfig(
            imei = identity.imei,
            androidId = identity.androidId,
            macAddress = identity.macAddress,
            serial = identity.serial,
            buildModel = identity.buildModel,
            buildManufacturer = identity.buildManufacturer,
            buildFingerprint = identity.buildFingerprint,
            buildBrand = identity.buildBrand,
            buildDevice = identity.buildDevice,
            buildProduct = identity.buildProduct,
            versionRelease = identity.versionRelease,
            sdkInt = identity.sdkInt
        )

        // 4. DEX Patch (可选 — 对加固 APK 执行检测代码删除)
        val patchedDexPaths = runDexPatch(originApkFile, instanceId)

        onProgress("构建Stub")
        Log.w("InstanceMgr", "building stub APK")
        // 5. 构建 StubConfig (originalSignatures 存放 originApk 路径)
        val stubConfig = StubConfig(
            instanceId = instanceId,
            stubPackageName = identity.stubPackageName,
            originalPackageName = app.packageName,
            launchActivity = launcherActivity.name,
            originalSignatures = listOf(app.apkPath),
            authorityMap = identity.authorityMap,
            deviceIdentity = deviceIdentityConfig,
            patchedDexPaths = patchedDexPaths
        )

        // 6. 构建 Stub APK
        val stubApk = stubBuilder.build(stubConfig)
        Log.w("InstanceMgr", "stub APK built: ${stubApk.absolutePath}, size=${stubApk.length()}")

        onProgress("安装中")
        // 7. 安装 Stub (智能降级: Session API → 系统安装器 Intent)
        // 如果没有安装权限，会自动降级到系统安装器，用户手动点确认即可
        val installResult = stubInstaller.install(stubApk)
        when (installResult) {
            is StubInstaller.InstallResult.Success -> {
                Log.w("InstanceMgr", "stub install initiated successfully")
            }
            is StubInstaller.InstallResult.PendingUserConfirmation -> {
                Log.w("InstanceMgr", "waiting for user to confirm installation")
            }
            is StubInstaller.InstallResult.Error -> {
                Log.e("InstanceMgr", "stub install failed: ${installResult.message}")
                throw RuntimeException("Stub install failed: ${installResult.message}")
            }
        }

        // 等待安装完成 — 通过轮询包管理器确认安装成功
        // PendingUserConfirmation 时给予更长等待时间 (60s)
        val maxAttempts = if (installResult is StubInstaller.InstallResult.PendingUserConfirmation) 60 else 30
        var installConfirmed = false
        for (attempt in 1..maxAttempts) {
            try {
                context.packageManager.getPackageInfo(identity.stubPackageName, 0)
                installConfirmed = true
                Log.w("InstanceMgr", "install confirmed on attempt $attempt")
                break
            } catch (_: Exception) {
                kotlinx.coroutines.delay(1000)
            }
        }
        if (!installConfirmed) {
            throw RuntimeException("Stub installation not confirmed after ${maxAttempts}s — user may have cancelled or installation failed")
        }

        // 9. 保存实例信息到数据库
        val identityJson = gson.toJson(identity)
        val now = System.currentTimeMillis()
        val entity = InstanceEntity(
            instanceId = instanceId,
            originalPackageName = app.packageName,
            stubPackageName = identity.stubPackageName,
            identityJson = identityJson,
            createdAt = now,
            status = InstanceStatus.READY.name
        )
        instanceDatabase.instanceDao().insert(entity)
        Log.w("InstanceMgr", "instance saved to database")

        // 10. 更新 StateFlow
        val info = InstanceInfo(
            instanceId = instanceId,
            originalPackageName = app.packageName,
            stubPackageName = identity.stubPackageName,
            identity = identity,
            createdAt = now,
            status = InstanceStatus.READY
        )
        _instances.update { it + info }

        Log.w("InstanceMgr", "instance created successfully, id=$instanceId")
        instanceId
    }

    /**
     * 删除分身实例
     *
     * 流程:
     * 1. 从数据库查找实例
     * 2. 通过 Intent 卸载 Stub 包
     * 3. 从数据库删除记录
     * 4. 更新 _instances StateFlow
     *
     * @param instanceId 要删除的实例 ID
     * @throws IllegalArgumentException 如果实例不存在
     */
    suspend fun deleteInstance(instanceId: String) = withContext(Dispatchers.IO) {
        Timber.d("InstanceManager: deleting $instanceId")

        // 1. 从数据库查找实例
        val entity = instanceDatabase.instanceDao().getById(instanceId)
            ?: throw IllegalArgumentException("Instance not found: $instanceId")

        // 2. 卸载 Stub 包
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${entity.stubPackageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Timber.d("InstanceManager: uninstall initiated for ${entity.stubPackageName}")
        } catch (e: Exception) {
            Timber.e(e, "InstanceManager: failed to uninstall ${entity.stubPackageName}")
            // 卸载失败不阻断流程，仍需清理数据库记录
        }

        // 3. 从数据库删除
        instanceDatabase.instanceDao().deleteById(instanceId)
        Timber.d("InstanceManager: instance removed from database")

        // 4. 更新 StateFlow
        _instances.update { list -> list.filter { it.instanceId != instanceId } }

        Timber.d("InstanceManager: instance deleted successfully, id=$instanceId")
    }

    /**
     * 撤销删除分身实例
     *
     * 重新将实例记录写入数据库并刷新 StateFlow。
     * 注意：此方法不会重新安装 Stub APK，仅恢复数据库记录。
     *
     * @param instanceId 要恢复的实例 ID
     * @param identityJson 实例的身份配置 JSON
     */
    suspend fun undoDelete(instanceId: String, identityJson: String) = withContext(Dispatchers.IO) {
        Timber.d("InstanceManager: undoing delete for $instanceId")

        val identity = try {
            gson.fromJson(identityJson, IdentityConfig::class.java)
        } catch (e: Exception) {
            Timber.e(e, "InstanceManager: failed to parse identity JSON for undo")
            return@withContext
        }

        val now = System.currentTimeMillis()
        val entity = InstanceEntity(
            instanceId = instanceId,
            originalPackageName = identity.originalPackageName,
            stubPackageName = identity.stubPackageName,
            identityJson = identityJson,
            createdAt = now,
            status = InstanceStatus.READY.name
        )
        instanceDatabase.instanceDao().insert(entity)

        val info = InstanceInfo(
            instanceId = instanceId,
            originalPackageName = identity.originalPackageName,
            stubPackageName = identity.stubPackageName,
            identity = identity,
            createdAt = now,
            status = InstanceStatus.READY
        )
        _instances.update { it + info }

        Timber.d("InstanceManager: undo delete successful, id=$instanceId")
    }

    /**
     * 从数据库加载所有实例
     *
     * 读取 InstanceEntity 列表，反序列化 IdentityConfig，
     * 转换为 InstanceInfo 并更新 StateFlow。
     */
    suspend fun loadInstances() = withContext(Dispatchers.IO) {
        Timber.d("InstanceManager: loading instances from database")

        val entities = instanceDatabase.instanceDao().observeAll().first()
        val infos = entities.mapNotNull { entity ->
            try {
                val identity = gson.fromJson(entity.identityJson, IdentityConfig::class.java)
                InstanceInfo(
                    instanceId = entity.instanceId,
                    originalPackageName = entity.originalPackageName,
                    stubPackageName = entity.stubPackageName,
                    identity = identity,
                    createdAt = entity.createdAt,
                    status = try {
                        InstanceStatus.valueOf(entity.status)
                    } catch (_: Exception) {
                        InstanceStatus.ERROR
                    }
                )
            } catch (e: Exception) {
                Timber.e(e, "InstanceManager: failed to parse instance ${entity.instanceId}")
                null
            }
        }

        _instances.value = infos
        Timber.d("InstanceManager: loaded ${infos.size} instances")
    }

    /**
     * 对原始 APK 执行 DEX Patch (可选)
     *
     * 使用 dexlib2 删除加固壳的检测方法。
     * 失败不阻断流程 — 返回空列表表示跳过 patch。
     */
    private fun runDexPatch(originApk: File, instanceId: String): List<String> {
        return try {
            val patcher = com.multiapp.core.hook.dexpatch.DexPatcher()
            val workDir = File(System.getProperty("java.io.tmpdir"), "multiapp_dexpatch_$instanceId")
            workDir.mkdirs()

            // 从原始 APK 解压 DEX 文件
            val dexFiles = mutableListOf<File>()
            java.util.zip.ZipFile(originApk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".dex") }
                    .forEach { entry ->
                        val dexFile = File(workDir, entry.name)
                        zip.getInputStream(entry).use { input ->
                            dexFile.outputStream().use { out -> input.copyTo(out) }
                        }
                        dexFiles.add(dexFile)
                    }
            }

            if (dexFiles.isEmpty()) {
                Timber.w("InstanceManager: no DEX files found in origin APK")
                return emptyList()
            }

            // 执行 patch (默认使用 universal 特征库)
            val report = patcher.patch(dexFiles, "universal")
            Timber.d("InstanceManager: DEX patch result: ${report.patchedMethodCount} methods patched")

            if (report.isSuccess && report.patchedMethodCount > 0) {
                dexFiles.map { it.absolutePath }
            } else {
                // patch 失败或无方法被修补，清理临时文件
                workDir.deleteRecursively()
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "InstanceManager: DEX patch failed, skipping")
            emptyList()
        }
    }
}

data class InstanceInfo(
    val instanceId: String,
    val originalPackageName: String,
    val stubPackageName: String,
    val identity: IdentityConfig,
    val createdAt: Long,
    val status: InstanceStatus
)

enum class InstanceStatus {
    CREATING, READY, RUNNING, ERROR
}

/**
 * Convert IdentityConfig to DeviceProfile for IdentitySpoofingEngine.
 * This ensures the same identity values are used in both StubConfig and runtime spoofing.
 */
private fun IdentityConfig.toDeviceProfile(): com.multiapp.core.model.DeviceProfile {
    return com.multiapp.core.model.DeviceProfile(
        id = instanceId,
        name = "Instance $instanceId",
        brand = buildBrand,
        manufacturer = buildManufacturer,
        model = buildModel,
        device = buildDevice,
        product = buildProduct,
        board = buildDevice,
        hardware = buildDevice,
        fingerprint = buildFingerprint,
        androidVersion = versionRelease,
        sdkInt = sdkInt,
        buildId = buildFingerprint.substringAfter(":").substringBefore("/"),
        serial = serial,
        imei = imei,
        macAddress = macAddress
    )
}
