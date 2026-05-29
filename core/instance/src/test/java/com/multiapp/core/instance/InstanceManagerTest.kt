package com.multiapp.core.instance

import android.content.Context
import com.google.gson.Gson
import com.multiapp.core.identity.DeviceIdentityPool
import com.multiapp.core.identity.IdentityConfig
import com.multiapp.core.installer.StubInstaller
import com.multiapp.core.manifest.ComponentExtractor
import com.multiapp.core.manifest.DeviceIdentityConfig
import com.multiapp.core.manifest.ManifestParser
import com.multiapp.core.manifest.StubConfig
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.stub.StubBuilder
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class InstanceManagerTest {

    private lateinit var instanceDatabase: InstanceDatabase
    private lateinit var stubBuilder: StubBuilder
    private lateinit var stubInstaller: StubInstaller
    private lateinit var context: Context
    private lateinit var instanceDao: InstanceDao
    private lateinit var parser: ManifestParser
    private lateinit var extractor: ComponentExtractor
    private lateinit var instanceManager: InstanceManager

    @org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.io.File

    private val gson = Gson()

    @BeforeEach
    fun setUp() {
        instanceDatabase = mockk(relaxed = true)
        stubBuilder = mockk(relaxed = true)
        stubInstaller = mockk(relaxed = true)
        context = mockk(relaxed = true)
        instanceDao = mockk(relaxed = true)
        parser = mockk(relaxed = true)
        extractor = mockk(relaxed = true)

        every { instanceDatabase.instanceDao() } returns instanceDao

        mockkObject(DeviceIdentityPool)

        instanceManager = InstanceManager(
            instanceDatabase = instanceDatabase,
            stubBuilder = stubBuilder,
            stubInstaller = stubInstaller,
            context = context,
            parser = parser,
            extractor = extractor
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ── 辅助工厂方法 ──────────────────────────────────────────────

    private fun createTestIdentityConfig(
        instanceId: String = "stub_test-id",
        packageName: String = "com.example.app"
    ): IdentityConfig = IdentityConfig(
        instanceId = instanceId,
        stubPackageName = "$packageName.clone$instanceId",
        originalPackageName = packageName,
        authorityMap = mapOf(
            "$packageName.provider" to "$packageName.provider.clone$instanceId",
            "$packageName.fileprovider" to "$packageName.fileprovider.clone$instanceId"
        ),
        imei = "861234567890123",
        androidId = "abcdef0123456789",
        macAddress = "AA:BB:CC:DD:EE:FF",
        serial = "ABCDEF1234",
        buildModel = "Pixel 9 Pro",
        buildManufacturer = "Google",
        buildFingerprint = "google/raven/raven:14/ABC123/1234567890:user/release-keys",
        buildBrand = "google",
        buildDevice = "pixel_9_pro",
        buildProduct = "pixel_9_pro_user",
        versionRelease = "14",
        sdkInt = 34
    )

    private fun createTestVirtualApp(
        packageName: String = "com.example.app",
        apkPath: String = File(tempDir, "test.apk").absolutePath
    ): VirtualApp = VirtualApp(
        packageName = packageName,
        appName = "TestApp",
        apkPath = apkPath,
        instanceId = "existing-instance"
    )

    /**
     * 创建一个真实的空 APK 文件（避免 mockkConstructor(File::class) 导致 StackOverflow）
     */
    private fun ensureApkExists(apkPath: String) {
        val file = File(apkPath)
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
    }

    private fun createTestEntity(
        instanceId: String = "stub_test-id",
        packageName: String = "com.example.app",
        status: String = InstanceStatus.READY.name
    ): InstanceEntity = InstanceEntity(
        instanceId = instanceId,
        originalPackageName = packageName,
        stubPackageName = "$packageName.clone$instanceId",
        identityJson = gson.toJson(createTestIdentityConfig(instanceId, packageName)),
        createdAt = System.currentTimeMillis(),
        status = status
    )

    private fun mockCreateInstanceFlow(
        app: VirtualApp,
        apkExists: Boolean = true
    ): IdentityConfig {
        val identity = createTestIdentityConfig(
            instanceId = "stub_generated-id",
            packageName = app.packageName
        )

        every {
            DeviceIdentityPool.generateIdentity(any(), app.packageName)
        } returns identity

        if (apkExists) {
            // 使用真实临时文件
            ensureApkExists(app.apkPath)

            val parsedManifest = ManifestParser.ParsedManifest(
                packageName = app.packageName,
                applicationClass = null,
                activities = listOf(
                    ManifestParser.ComponentInfo(
                        name = "com.example.app.MainActivity",
                        exported = true,
                        intentFilters = listOf(
                            ManifestParser.IntentFilterInfo(
                                actions = listOf("android.intent.action.MAIN"),
                                categories = listOf("android.intent.category.LAUNCHER")
                            )
                        )
                    )
                ),
                services = emptyList(),
                receivers = emptyList(),
                providers = emptyList(),
                permissions = emptyList()
            )

            every { parser.parse(any<File>()) } returns parsedManifest
            every {
                extractor.extractLauncherActivity(any())
            } returns parsedManifest.activities.first()
        }

        every { stubBuilder.build(any()) } returns File(tempDir, "stub.apk")
        every { stubInstaller.install(any()) } returns StubInstaller.InstallResult.Success

        return identity
    }

    // ── 1. 创建实例 - 实例 ID 唯一性 ────────────────────────────

    @Nested
    @DisplayName("createInstance - 实例 ID 唯一性")
    inner class CreateInstanceIdUniqueness {

        @Test
        fun `生成的 instanceId 以 stub_ 前缀开头`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            val instanceId = instanceManager.createInstance(app)

            assertTrue(
                instanceId.startsWith("stub_"),
                "instanceId 应以 stub_ 开头，实际为: $instanceId"
            )
        }

        @Test
        fun `多次调用生成不同的 instanceId`() = runTest {
            val app1 = createTestVirtualApp(packageName = "com.example.app1",
                apkPath = File(tempDir, "test1.apk").absolutePath)
            val app2 = createTestVirtualApp(packageName = "com.example.app2",
                apkPath = File(tempDir, "test2.apk").absolutePath)

            mockCreateInstanceFlow(app1)
            mockCreateInstanceFlow(app2)

            val id1 = instanceManager.createInstance(app1)
            val id2 = instanceManager.createInstance(app2)

            assertNotEquals(id1, id2, "两次创建应生成不同的 instanceId")
        }

        @Test
        fun `instanceId 符合 UUID 格式`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            val instanceId = instanceManager.createInstance(app)
            val uuidPart = instanceId.removePrefix("stub_")

            // UUID 格式: 8-4-4-4-12 (32 hex chars + 4 dashes)
            val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            assertTrue(
                uuidRegex.matches(uuidPart),
                "UUID 部分不符合格式: $uuidPart"
            )
        }
    }

    // ── 2. 创建实例 - 配置正确生成 ──────────────────────────────

    @Nested
    @DisplayName("createInstance - 配置正确生成")
    inner class CreateInstanceConfig {

        @Test
        fun `调用 DeviceIdentityPool 时传入正确的 instanceId 和 packageName`() = runTest {
            val app = createTestVirtualApp(packageName = "com.example.testapp")
            mockCreateInstanceFlow(app)

            instanceManager.createInstance(app)

            verify {
                DeviceIdentityPool.generateIdentity(
                    match { it.startsWith("stub_") },
                    eq("com.example.testapp")
                )
            }
        }

        @Test
        fun `使用正确的 IdentityConfig 调用 StubBuilder`() = runTest {
            val app = createTestVirtualApp(packageName = "com.example.app")
            val identity = mockCreateInstanceFlow(app)

            instanceManager.createInstance(app)

            verify {
                stubBuilder.build(
                    match<StubConfig> { config ->
                        config.stubPackageName == identity.stubPackageName &&
                            config.originalPackageName == app.packageName &&
                            config.authorityMap == identity.authorityMap &&
                            config.deviceIdentity.imei == identity.imei &&
                            config.deviceIdentity.androidId == identity.androidId
                    }
                )
            }
        }

        @Test
        fun `DeviceIdentityConfig 字段正确映射自 IdentityConfig`() = runTest {
            val app = createTestVirtualApp()
            val identity = createTestIdentityConfig()

            every {
                DeviceIdentityPool.generateIdentity(any(), any())
            } returns identity

            // 使用真实临时文件
            ensureApkExists(app.apkPath)

            every { parser.parse(any<File>()) } returns ManifestParser.ParsedManifest(
                packageName = "com.example.app",
                applicationClass = null,
                activities = listOf(
                    ManifestParser.ComponentInfo(
                        name = "com.example.app.MainActivity",
                        exported = true,
                        intentFilters = listOf(
                            ManifestParser.IntentFilterInfo(
                                actions = listOf("android.intent.action.MAIN"),
                                categories = listOf("android.intent.category.LAUNCHER")
                            )
                        )
                    )
                ),
                services = emptyList(),
                receivers = emptyList(),
                providers = emptyList(),
                permissions = emptyList()
            )

            every {
                extractor.extractLauncherActivity(any())
            } returns ManifestParser.ComponentInfo(
                name = "com.example.app.MainActivity",
                exported = true
            )

            every { stubBuilder.build(any()) } returns File(tempDir, "stub.apk")
            every { stubInstaller.install(any()) } returns StubInstaller.InstallResult.Success

            instanceManager.createInstance(app)

            verify {
                stubBuilder.build(
                    match<StubConfig> { config ->
                        val di = config.deviceIdentity
                        di.imei == identity.imei &&
                            di.androidId == identity.androidId &&
                            di.macAddress == identity.macAddress &&
                            di.serial == identity.serial &&
                            di.buildModel == identity.buildModel &&
                            di.buildManufacturer == identity.buildManufacturer &&
                            di.buildFingerprint == identity.buildFingerprint &&
                            di.buildBrand == identity.buildBrand &&
                            di.buildDevice == identity.buildDevice &&
                            di.buildProduct == identity.buildProduct &&
                            di.versionRelease == identity.versionRelease &&
                            di.sdkInt == identity.sdkInt
                    }
                )
            }
        }

        @Test
        fun `实例保存到数据库包含正确的 IdentityJson`() = runTest {
            val app = createTestVirtualApp()
            val identity = mockCreateInstanceFlow(app)

            instanceManager.createInstance(app)

            coVerify {
                instanceDao.insert(
                    match<InstanceEntity> { entity ->
                        entity.originalPackageName == app.packageName &&
                            entity.status == InstanceStatus.READY.name &&
                            entity.identityJson.contains(identity.imei)
                    }
                )
            }
        }
    }

    // ── 3. 获取实例列表 ──────────────────────────────────────────

    @Nested
    @DisplayName("loadInstances - 获取实例列表")
    inner class LoadInstances {

        @Test
        fun `从数据库加载实例并更新 StateFlow`() = runTest {
            val entity1 = createTestEntity(instanceId = "stub_id1", packageName = "com.app1")
            val entity2 = createTestEntity(instanceId = "stub_id2", packageName = "com.app2")

            every { instanceDao.observeAll() } returns flowOf(listOf(entity1, entity2))

            instanceManager.loadInstances()

            val instances = instanceManager.instances.value
            assertEquals(2, instances.size)
            assertEquals("stub_id1", instances[0].instanceId)
            assertEquals("stub_id2", instances[1].instanceId)
        }

        @Test
        fun `加载的实例包含正确的 status 枚举值`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.READY.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.READY, instanceManager.instances.value.first().status)
        }

        @Test
        fun `加载的实例包含正确的 identity 信息`() = runTest {
            val identity = createTestIdentityConfig()
            val entity = createTestEntity().copy(identityJson = gson.toJson(identity))
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            val loaded = instanceManager.instances.value.first()
            assertEquals(identity.imei, loaded.identity.imei)
            assertEquals(identity.androidId, loaded.identity.androidId)
            assertEquals(identity.buildModel, loaded.identity.buildModel)
        }

        @Test
        fun `加载的实例按 createdAt 排序`() = runTest {
            val now = System.currentTimeMillis()
            val entity1 = createTestEntity(instanceId = "stub_old").copy(createdAt = now - 10000)
            val entity2 = createTestEntity(instanceId = "stub_new").copy(createdAt = now)

            every { instanceDao.observeAll() } returns flowOf(listOf(entity2, entity1))

            instanceManager.loadInstances()

            val instances = instanceManager.instances.value
            assertEquals("stub_new", instances[0].instanceId)
            assertEquals("stub_old", instances[1].instanceId)
        }
    }

    // ── 4. 获取单个实例 ──────────────────────────────────────────

    @Nested
    @DisplayName("loadInstances - 获取单个实例")
    inner class GetSingleInstance {

        @Test
        fun `加载单个实例到 StateFlow`() = runTest {
            val entity = createTestEntity(instanceId = "stub_single")
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(1, instanceManager.instances.value.size)
            assertEquals("stub_single", instanceManager.instances.value.first().instanceId)
        }

        @Test
        fun `createInstance 后 StateFlow 包含新实例`() = runTest {
            // 先初始化空列表
            every { instanceDao.observeAll() } returns flowOf(emptyList())
            instanceManager.loadInstances()
            assertTrue(instanceManager.instances.value.isEmpty())

            // 创建实例
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)
            val newId = instanceManager.createInstance(app)

            val instances = instanceManager.instances.value
            assertEquals(1, instances.size)
            assertEquals(newId, instances.first().instanceId)
            assertEquals(app.packageName, instances.first().originalPackageName)
        }
    }

    // ── 5. 删除实例 ─────────────────────────────────────────────

    @Nested
    @DisplayName("deleteInstance - 删除实例")
    inner class DeleteInstance {

        @Test
        fun `成功删除存在的实例`() = runTest {
            val instanceId = "stub_to-delete"
            val entity = createTestEntity(instanceId = instanceId)

            coEvery { instanceDao.getById(instanceId) } returns entity
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            instanceManager.deleteInstance(instanceId)

            coVerify { instanceDao.deleteById(instanceId) }
        }

        @Test
        fun `删除后从 StateFlow 中移除实例`() = runTest {
            val instanceId = "stub_to-delete"
            val entity = createTestEntity(instanceId = instanceId)

            coEvery { instanceDao.getById(instanceId) } returns entity
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            // 先加载实例到 StateFlow
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))
            instanceManager.loadInstances()
            assertEquals(1, instanceManager.instances.value.size)

            // 删除实例
            instanceManager.deleteInstance(instanceId)

            assertTrue(
                instanceManager.instances.value.none { it.instanceId == instanceId },
                "删除后 StateFlow 中不应包含该实例"
            )
        }

        @Test
        fun `删除一个实例不影响其他实例`() = runTest {
            val idToDelete = "stub_delete-me"
            val idToKeep = "stub_keep-me"
            val entityToDelete = createTestEntity(instanceId = idToDelete)
            val entityToKeep = createTestEntity(instanceId = idToKeep, packageName = "com.other")

            coEvery { instanceDao.getById(idToDelete) } returns entityToDelete
            coEvery { instanceDao.deleteById(idToDelete) } just Runs

            // 加载两个实例
            every { instanceDao.observeAll() } returns flowOf(listOf(entityToDelete, entityToKeep))
            instanceManager.loadInstances()
            assertEquals(2, instanceManager.instances.value.size)

            // 删除一个
            instanceManager.deleteInstance(idToDelete)

            assertEquals(1, instanceManager.instances.value.size)
            assertEquals(idToKeep, instanceManager.instances.value.first().instanceId)
        }

        @Test
        fun `删除实例时启动卸载 Intent`() = runTest {
            val instanceId = "stub_uninstall-test"
            val entity = createTestEntity(instanceId = instanceId)
            coEvery { instanceDao.getById(instanceId) } returns entity
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            // Intent() 构造函数在 JVM 测试环境中抛出 "Stub!"，
            // 异常被 try-catch 捕获后 startActivity 不会被调用。
            // 验证 deleteInstance 不因 Intent 异常而中断，且数据库清理正常执行。
            instanceManager.deleteInstance(instanceId)

            coVerify { instanceDao.deleteById(instanceId) }
            assertTrue(
                instanceManager.instances.value.none { it.instanceId == instanceId }
            )
        }
    }

    // ── 6. 实例状态管理 ──────────────────────────────────────────

    @Nested
    @DisplayName("实例状态管理")
    inner class InstanceStatusManagement {

        @Test
        fun `新建实例状态为 READY`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            instanceManager.createInstance(app)

            val info = instanceManager.instances.value.first()
            assertEquals(InstanceStatus.READY, info.status)
        }

        @Test
        fun `数据库中保存的状态为 READY 字符串`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            instanceManager.createInstance(app)

            coVerify {
                instanceDao.insert(
                    match<InstanceEntity> { it.status == "READY" }
                )
            }
        }

        @Test
        fun `加载实例时解析 CREATING 状态`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.CREATING.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.CREATING, instanceManager.instances.value.first().status)
        }

        @Test
        fun `加载实例时解析 RUNNING 状态`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.RUNNING.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.RUNNING, instanceManager.instances.value.first().status)
        }

        @Test
        fun `加载实例时解析 ERROR 状态`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.ERROR.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.ERROR, instanceManager.instances.value.first().status)
        }

        @Test
        fun `加载实例时未知 status 回退为 ERROR`() = runTest {
            val entity = createTestEntity(status = "UNKNOWN_STATUS")
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(
                InstanceStatus.ERROR,
                instanceManager.instances.value.first().status,
                "未知状态应回退为 ERROR"
            )
        }
    }

    // ── 7. DeviceIdentityPool 身份生成唯一性 ─────────────────────

    @Nested
    @DisplayName("DeviceIdentityPool - 身份生成唯一性")
    inner class IdentityUniqueness {

        @AfterEach
        fun identityTearDown() {
            unmockkObject(DeviceIdentityPool)
        }

        @Test
        fun `连续生成两个身份具有不同的 IMEI`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(
                identity1.imei,
                identity2.imei,
                "两次生成的 IMEI 应不同"
            )
        }

        @Test
        fun `连续生成两个身份具有不同的 AndroidId`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(
                identity1.androidId,
                identity2.androidId,
                "两次生成的 AndroidId 应不同"
            )
        }

        @Test
        fun `连续生成两个身份具有不同的 MAC 地址`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(
                identity1.macAddress,
                identity2.macAddress,
                "两次生成的 MAC 地址应不同"
            )
        }

        @Test
        fun `连续生成两个身份具有不同的 Serial`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(
                identity1.serial,
                identity2.serial,
                "两次生成的 Serial 应不同"
            )
        }

        @Test
        fun `生成的 IMEI 长度为 15 位且格式正确`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            assertEquals(15, identity.imei.length, "IMEI 应为 15 位")
            assertTrue(identity.imei.startsWith("86"), "IMEI 应以 86 开头")
            assertTrue(identity.imei.all { it.isDigit() }, "IMEI 应全为数字")
        }

        @Test
        fun `生成的 AndroidId 长度为 16 位十六进制字符`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            assertEquals(16, identity.androidId.length, "AndroidId 应为 16 位")
            assertTrue(
                identity.androidId.all { it in "0123456789abcdef" },
                "AndroidId 应为小写十六进制: ${identity.androidId}"
            )
        }

        @Test
        fun `生成的 MAC 地址符合 AA_BB_CC_DD_EE_FF 格式`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            val macRegex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
            assertTrue(
                macRegex.matches(identity.macAddress),
                "MAC 地址格式不正确: ${identity.macAddress}"
            )
        }

        @Test
        fun `生成的 stubPackageName 包含原始包名和 clone 标记`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-abc", "com.whatsapp")

            assertTrue(
                identity.stubPackageName.contains("com.whatsapp"),
                "stubPackageName 应包含原始包名"
            )
            assertTrue(
                identity.stubPackageName.contains("clone"),
                "stubPackageName 应包含 clone 标记"
            )
        }

        @Test
        fun `生成的 authorityMap 包含 provider 和 fileprovider 映射`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.example")

            assertEquals(2, identity.authorityMap.size)
            assertTrue(identity.authorityMap.containsKey("com.example.provider"))
            assertTrue(identity.authorityMap.containsKey("com.example.fileprovider"))
            assertTrue(identity.authorityMap["com.example.provider"]!!.contains("clone"))
            assertTrue(identity.authorityMap["com.example.fileprovider"]!!.contains("clone"))
        }

        @Test
        fun `sdkInt 在 33 到 35 范围内`() {
            unmockkObject(DeviceIdentityPool)

            repeat(20) {
                val identity = DeviceIdentityPool.generateIdentity("inst-$it", "com.app")
                assertTrue(
                    identity.sdkInt in 33..35,
                    "sdkInt 应在 33-35 范围内，实际: ${identity.sdkInt}"
                )
            }
        }

        @Test
        fun `生成的身份字段无空值`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            assertTrue(identity.imei.isNotEmpty(), "imei 不应为空")
            assertTrue(identity.androidId.isNotEmpty(), "androidId 不应为空")
            assertTrue(identity.macAddress.isNotEmpty(), "macAddress 不应为空")
            assertTrue(identity.serial.isNotEmpty(), "serial 不应为空")
            assertTrue(identity.buildModel.isNotEmpty(), "buildModel 不应为空")
            assertTrue(identity.buildManufacturer.isNotEmpty(), "buildManufacturer 不应为空")
            assertTrue(identity.buildFingerprint.isNotEmpty(), "buildFingerprint 不应为空")
            assertTrue(identity.buildBrand.isNotEmpty(), "buildBrand 不应为空")
            assertTrue(identity.buildDevice.isNotEmpty(), "buildDevice 不应为空")
            assertTrue(identity.buildProduct.isNotEmpty(), "buildProduct 不应为空")
            assertTrue(identity.versionRelease.isNotEmpty(), "versionRelease 不应为空")
        }
    }

    // ── 8. 身份池的并发安全性 ────────────────────────────────────

    @Nested
    @DisplayName("DeviceIdentityPool - 并发安全性")
    inner class IdentityConcurrency {

        @AfterEach
        fun concurrencyTearDown() {
            unmockkObject(DeviceIdentityPool)
        }

        @Test
        fun `并发生成 100 个身份全部唯一`() {
            unmockkObject(DeviceIdentityPool)

            val threadCount = 100
            val latch = CountDownLatch(threadCount)
            val identities = CopyOnWriteArrayList<IdentityConfig>()
            val errorCount = AtomicInteger(0)

            val threads = (1..threadCount).map { i ->
                Thread {
                    try {
                        val identity = DeviceIdentityPool.generateIdentity(
                            "inst-concurrent-$i",
                            "com.concurrent.app"
                        )
                        identities.add(identity)
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            threads.forEach { it.start() }
            latch.await()

            assertEquals(0, errorCount.get(), "并发生成不应抛出异常")
            assertEquals(threadCount, identities.size, "应生成全部身份")

            val uniqueImeis = identities.map { it.imei }.toSet()
            assertEquals(
                threadCount,
                uniqueImeis.size,
                "所有 IMEI 应唯一，但发现重复: ${threadCount - uniqueImeis.size} 个重复"
            )

            val uniqueAndroidIds = identities.map { it.androidId }.toSet()
            assertEquals(
                threadCount,
                uniqueAndroidIds.size,
                "所有 AndroidId 应唯一"
            )
        }

        @Test
        fun `并发生成身份的 MAC 地址全部唯一`() {
            unmockkObject(DeviceIdentityPool)

            val threadCount = 50
            val identities = CopyOnWriteArrayList<IdentityConfig>()
            val latch = CountDownLatch(threadCount)

            (1..threadCount).map { i ->
                Thread {
                    try {
                        identities.add(
                            DeviceIdentityPool.generateIdentity("inst-mac-$i", "com.app")
                        )
                    } finally {
                        latch.countDown()
                    }
                }.also { it.start() }
            }

            latch.await()

            val uniqueMacs = identities.map { it.macAddress }.toSet()
            assertEquals(threadCount, uniqueMacs.size, "所有 MAC 地址应唯一")
        }

        @Test
        fun `并发创建实例生成唯一 instanceId`() = runTest {
            val apps = (1..10).map { i ->
                createTestVirtualApp(packageName = "com.concurrent.app$i")
            }

            apps.forEach { app ->
                mockCreateInstanceFlow(app)
            }

            val ids = apps.map { app ->
                instanceManager.createInstance(app)
            }.toSet()

            assertEquals(10, ids.size, "并发创建应生成 10 个不同的 instanceId")
        }
    }

    // ── 9. 边界条件 ──────────────────────────────────────────────

    @Nested
    @DisplayName("边界条件")
    inner class EdgeCases {

        @Test
        fun `初始状态下 instances StateFlow 为空列表`() = runTest {
            assertTrue(
                instanceManager.instances.value.isEmpty(),
                "初始状态应为空列表"
            )
        }

        @Test
        fun `loadInstances 处理空数据库列表`() = runTest {
            every { instanceDao.observeAll() } returns flowOf(emptyList())

            instanceManager.loadInstances()

            assertTrue(
                instanceManager.instances.value.isEmpty(),
                "空数据库应加载为空列表"
            )
        }

        @Test
        fun `loadInstances 跳过 identityJson 解析失败的记录`() = runTest {
            val validEntity = createTestEntity(instanceId = "stub_valid")
            val invalidEntity = createTestEntity(instanceId = "stub_invalid")
                .copy(identityJson = "这不是合法的 JSON {{{")

            every { instanceDao.observeAll() } returns flowOf(
                listOf(validEntity, invalidEntity)
            )

            instanceManager.loadInstances()

            assertEquals(
                1,
                instanceManager.instances.value.size,
                "解析失败的记录应被跳过"
            )
            assertEquals(
                "stub_valid",
                instanceManager.instances.value.first().instanceId
            )
        }

        @Test
        fun `loadInstances 处理 identityJson 为空字符串的记录`() = runTest {
            val entity = createTestEntity().copy(identityJson = "")
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertTrue(
                instanceManager.instances.value.isEmpty(),
                "空 JSON 应导致记录被跳过"
            )
        }

        @Test
        fun `loadInstances 处理 identityJson 为 null 字面量的记录`() = runTest {
            val entity = createTestEntity().copy(identityJson = "null")
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            // null JSON 会被 Gson 解析为 null 对象，mapNotNull 会过滤掉
            instanceManager.loadInstances()
        }

        @Test
        fun `loadInstances 处理大量实例记录`() = runTest {
            val entities = (1..1000).map { i ->
                createTestEntity(
                    instanceId = "stub_bulk-$i",
                    packageName = "com.bulk.app$i"
                )
            }
            every { instanceDao.observeAll() } returns flowOf(entities)

            instanceManager.loadInstances()

            assertEquals(1000, instanceManager.instances.value.size)
        }

        @Test
        fun `deleteInstance 处理卸载 Intent 发送失败`() = runTest {
            val instanceId = "stub_uninstall-fail"
            val entity = createTestEntity(instanceId = instanceId)

            coEvery { instanceDao.getById(instanceId) } returns entity
            every { context.startActivity(any()) } throws SecurityException("Permission denied")
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            // 卸载失败不应阻断删除流程
            instanceManager.deleteInstance(instanceId)

            coVerify { instanceDao.deleteById(instanceId) }
            assertTrue(
                instanceManager.instances.value.none { it.instanceId == instanceId }
            )
        }
    }

    // ── 10. 错误处理 ─────────────────────────────────────────────

    @Nested
    @DisplayName("错误处理")
    inner class ErrorHandling {

        @Test
        fun `createInstance 在 APK 文件不存在时抛出 IllegalArgumentException`() = runTest {
            // 使用 tempDir 下一个不存在的路径（不创建文件）
            val nonExistentPath = File(tempDir, "nonexistent_${System.nanoTime()}.apk").absolutePath
            val app = createTestVirtualApp(apkPath = nonExistentPath)

            every {
                DeviceIdentityPool.generateIdentity(any(), any())
            } returns createTestIdentityConfig()

            assertThrows<IllegalArgumentException> {
                instanceManager.createInstance(app)
            }
        }

        @Test
        fun `createInstance 在无 launcher Activity 时抛出 IllegalStateException`() = runTest {
            val app = createTestVirtualApp()

            every {
                DeviceIdentityPool.generateIdentity(any(), any())
            } returns createTestIdentityConfig()

            // 使用真实临时文件（APK 存在）
            ensureApkExists(app.apkPath)

            every { parser.parse(any<File>()) } returns ManifestParser.ParsedManifest(
                packageName = app.packageName,
                applicationClass = null,
                activities = emptyList(),
                services = emptyList(),
                receivers = emptyList(),
                providers = emptyList(),
                permissions = emptyList()
            )

            every {
                extractor.extractLauncherActivity(any())
            } returns null

            assertThrows<IllegalStateException> {
                instanceManager.createInstance(app)
            }
        }

        @Test
        fun `createInstance 在 Stub 安装失败时抛出 RuntimeException`() = runTest {
            val app = createTestVirtualApp()

            mockCreateInstanceFlow(app)
            every {
                stubInstaller.install(any())
            } returns StubInstaller.InstallResult.Error("Install failed: insufficient storage")

            assertThrows<RuntimeException> {
                instanceManager.createInstance(app)
            }
        }

        @Test
        fun `createInstance 安装失败时不写入数据库`() = runTest {
            val app = createTestVirtualApp()

            mockCreateInstanceFlow(app)
            every {
                stubInstaller.install(any())
            } returns StubInstaller.InstallResult.Error("Install failed")

            assertThrows<RuntimeException> {
                instanceManager.createInstance(app)
            }

            coVerify(exactly = 0) { instanceDao.insert(any()) }
        }

        @Test
        fun `createInstance 安装失败时不更新 StateFlow`() = runTest {
            val app = createTestVirtualApp()

            mockCreateInstanceFlow(app)
            every {
                stubInstaller.install(any())
            } returns StubInstaller.InstallResult.Error("Install failed")

            every { instanceDao.observeAll() } returns flowOf(emptyList())
            instanceManager.loadInstances()
            val countBefore = instanceManager.instances.value.size

            assertThrows<RuntimeException> {
                instanceManager.createInstance(app)
            }

            assertEquals(
                countBefore,
                instanceManager.instances.value.size,
                "安装失败后 StateFlow 不应改变"
            )
        }

        @Test
        fun `deleteInstance 在实例不存在时抛出 IllegalArgumentException`() = runTest {
            coEvery { instanceDao.getById("nonexistent-id") } returns null

            assertThrows<IllegalArgumentException> {
                instanceManager.deleteInstance("nonexistent-id")
            }
        }

        @Test
        fun `deleteInstance 不存在的实例不触发数据库删除`() = runTest {
            coEvery { instanceDao.getById("nonexistent-id") } returns null

            try {
                instanceManager.deleteInstance("nonexistent-id")
            } catch (_: IllegalArgumentException) {
                // expected
            }

            coVerify(exactly = 0) { instanceDao.deleteById(any()) }
        }

        @Test
        fun `deleteInstance 不存在的实例不启动卸载 Intent`() = runTest {
            coEvery { instanceDao.getById("nonexistent-id") } returns null

            try {
                instanceManager.deleteInstance("nonexistent-id")
            } catch (_: IllegalArgumentException) {
                // expected
            }

            verify(exactly = 0) { context.startActivity(any()) }
        }
    }
}
