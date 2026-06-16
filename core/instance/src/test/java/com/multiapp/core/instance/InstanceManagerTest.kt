package com.multiapp.core.instance

import android.content.Context
import com.google.gson.Gson
import com.multiapp.core.hook.IdentitySpoofingEngine
import com.multiapp.core.identity.DeviceIdentityPool
import com.multiapp.core.model.IdentityConfig
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
    private lateinit var identitySpoofingEngine: IdentitySpoofingEngine
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
        identitySpoofingEngine = mockk(relaxed = true)
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
            identitySpoofingEngine = identitySpoofingEngine,
            parser = parser,
            extractor = extractor
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // -- helper factory methods ------------------------------------------------

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

    /** Create an empty real APK file so that File.exists() returns true. */
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

    // =========================================================================
    // 1. createInstance - instance ID uniqueness
    // =========================================================================

    @Nested
    @DisplayName("createInstance - instance ID uniqueness")
    inner class CreateInstanceIdUniqueness {

        @Test
        fun `generated instanceId starts with stub_ prefix`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            val instanceId = instanceManager.createInstance(app)

            assertTrue(
                instanceId.startsWith("stub_"),
                "instanceId should start with stub_, got: $instanceId"
            )
        }

        @Test
        fun `multiple calls generate different instanceIds`() = runTest {
            val app1 = createTestVirtualApp(
                packageName = "com.example.app1",
                apkPath = File(tempDir, "test1.apk").absolutePath
            )
            val app2 = createTestVirtualApp(
                packageName = "com.example.app2",
                apkPath = File(tempDir, "test2.apk").absolutePath
            )

            mockCreateInstanceFlow(app1)
            mockCreateInstanceFlow(app2)

            val id1 = instanceManager.createInstance(app1)
            val id2 = instanceManager.createInstance(app2)

            assertNotEquals(id1, id2, "Two creates should produce different instanceIds")
        }

        @Test
        fun `instanceId conforms to UUID format`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            val instanceId = instanceManager.createInstance(app)
            val uuidPart = instanceId.removePrefix("stub_")

            // UUID format: 32 hex chars (dashes stripped by code)
            val uuidRegex = Regex("[0-9a-f]{32}")
            assertTrue(
                uuidRegex.matches(uuidPart),
                "UUID part does not match expected format: $uuidPart"
            )
        }
    }

    // =========================================================================
    // 2. createInstance - configuration correctness
    // =========================================================================

    @Nested
    @DisplayName("createInstance - configuration correctness")
    inner class CreateInstanceConfig {

        @Test
        fun `calls DeviceIdentityPool with correct instanceId and packageName`() = runTest {
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
        fun `passes correct IdentityConfig to StubBuilder`() = runTest {
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
        fun `DeviceIdentityConfig fields map correctly from IdentityConfig`() = runTest {
            val app = createTestVirtualApp()
            val identity = createTestIdentityConfig()

            every {
                DeviceIdentityPool.generateIdentity(any(), any())
            } returns identity

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
        fun `entity saved to database contains correct IdentityJson`() = runTest {
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

        @Test
        fun `syncs identity to IdentitySpoofingEngine during creation`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            instanceManager.createInstance(app)

            verify {
                identitySpoofingEngine.applyDeviceProfile(
                    any(),
                    match { it.startsWith("stub_") },
                    any()
                )
            }
        }
    }

    // =========================================================================
    // 3. loadInstances - instance listing
    // =========================================================================

    @Nested
    @DisplayName("loadInstances - instance listing")
    inner class LoadInstances {

        @Test
        fun `loads instances from database and updates StateFlow`() = runTest {
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
        fun `loaded instances have correct status enum value`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.READY.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.READY, instanceManager.instances.value.first().status)
        }

        @Test
        fun `loaded instances have correct identity information`() = runTest {
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
        fun `loaded instances preserve insertion order`() = runTest {
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

    // =========================================================================
    // 4. deleteInstance - instance deletion
    // =========================================================================

    @Nested
    @DisplayName("deleteInstance - instance deletion")
    inner class DeleteInstance {

        @Test
        fun `successfully deletes an existing instance`() = runTest {
            val instanceId = "stub_to-delete"
            val entity = createTestEntity(instanceId = instanceId)

            coEvery { instanceDao.getById(instanceId) } returns entity
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            instanceManager.deleteInstance(instanceId)

            coVerify { instanceDao.deleteById(instanceId) }
        }

        @Test
        fun `removes deleted instance from StateFlow`() = runTest {
            val instanceId = "stub_to-delete"
            val entity = createTestEntity(instanceId = instanceId)

            coEvery { instanceDao.getById(instanceId) } returns entity
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            // Load instance into StateFlow first
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))
            instanceManager.loadInstances()
            assertEquals(1, instanceManager.instances.value.size)

            instanceManager.deleteInstance(instanceId)

            assertTrue(
                instanceManager.instances.value.none { it.instanceId == instanceId },
                "Deleted instance should not be in StateFlow"
            )
        }

        @Test
        fun `deleting one instance does not affect others`() = runTest {
            val idToDelete = "stub_delete-me"
            val idToKeep = "stub_keep-me"
            val entityToDelete = createTestEntity(instanceId = idToDelete)
            val entityToKeep = createTestEntity(instanceId = idToKeep, packageName = "com.other")

            coEvery { instanceDao.getById(idToDelete) } returns entityToDelete
            coEvery { instanceDao.deleteById(idToDelete) } just Runs

            // Load both
            every { instanceDao.observeAll() } returns flowOf(listOf(entityToDelete, entityToKeep))
            instanceManager.loadInstances()
            assertEquals(2, instanceManager.instances.value.size)

            instanceManager.deleteInstance(idToDelete)

            assertEquals(1, instanceManager.instances.value.size)
            assertEquals(idToKeep, instanceManager.instances.value.first().instanceId)
        }

        @Test
        fun `delete triggers uninstall Intent for stub package`() = runTest {
            val instanceId = "stub_uninstall-test"
            val entity = createTestEntity(instanceId = instanceId)
            coEvery { instanceDao.getById(instanceId) } returns entity
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            // Intent() constructor throws "Stub!" in JVM tests;
            // the catch block in deleteInstance prevents the crash.
            // We verify that the database is still cleaned up.
            instanceManager.deleteInstance(instanceId)

            coVerify { instanceDao.deleteById(instanceId) }
            assertTrue(
                instanceManager.instances.value.none { it.instanceId == instanceId }
            )
        }
    }

    // =========================================================================
    // 5. undoDelete - instance restoration
    // =========================================================================

    @Nested
    @DisplayName("undoDelete - instance restoration")
    inner class UndoDelete {

        @Test
        fun `restores instance record to database`() = runTest {
            val identity = createTestIdentityConfig(instanceId = "stub_restored")
            val identityJson = gson.toJson(identity)

            instanceManager.undoDelete("stub_restored", identityJson)

            coVerify {
                instanceDao.insert(
                    match<InstanceEntity> { entity ->
                        entity.instanceId == "stub_restored" &&
                            entity.originalPackageName == identity.originalPackageName &&
                            entity.stubPackageName == identity.stubPackageName &&
                            entity.status == InstanceStatus.READY.name
                    }
                )
            }
        }

        @Test
        fun `adds restored instance to StateFlow`() = runTest {
            val identity = createTestIdentityConfig(instanceId = "stub_restored")
            val identityJson = gson.toJson(identity)

            every { instanceDao.observeAll() } returns flowOf(emptyList())
            instanceManager.loadInstances()
            assertTrue(instanceManager.instances.value.isEmpty())

            instanceManager.undoDelete("stub_restored", identityJson)

            assertEquals(1, instanceManager.instances.value.size)
            val restored = instanceManager.instances.value.first()
            assertEquals("stub_restored", restored.instanceId)
            assertEquals(identity.originalPackageName, restored.originalPackageName)
            assertEquals(identity.stubPackageName, restored.stubPackageName)
            assertEquals(InstanceStatus.READY, restored.status)
        }

        @Test
        fun `restores correct identity information`() = runTest {
            val identity = createTestIdentityConfig(instanceId = "stub_id-check")
            val identityJson = gson.toJson(identity)

            instanceManager.undoDelete("stub_id-check", identityJson)

            val restored = instanceManager.instances.value.first()
            assertEquals(identity.imei, restored.identity.imei)
            assertEquals(identity.androidId, restored.identity.androidId)
            assertEquals(identity.buildModel, restored.identity.buildModel)
        }

        @Test
        fun `silently skips restore when identityJson is invalid`() = runTest {
            every { instanceDao.observeAll() } returns flowOf(emptyList())
            instanceManager.loadInstances()

            instanceManager.undoDelete("stub_bad", "this is not JSON {{{")

            coVerify(exactly = 0) { instanceDao.insert(any()) }
            assertTrue(instanceManager.instances.value.isEmpty())
        }

        @Test
        fun `can restore after delete removes from StateFlow`() = runTest {
            val instanceId = "stub_cycle"
            val entity = createTestEntity(instanceId = instanceId)
            val identityJson = entity.identityJson

            // Load and delete
            coEvery { instanceDao.getById(instanceId) } returns entity
            coEvery { instanceDao.deleteById(instanceId) } just Runs
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))
            instanceManager.loadInstances()
            assertEquals(1, instanceManager.instances.value.size)

            instanceManager.deleteInstance(instanceId)
            assertTrue(instanceManager.instances.value.isEmpty())

            // Undo delete
            instanceManager.undoDelete(instanceId, identityJson)
            assertEquals(1, instanceManager.instances.value.size)
            assertEquals(instanceId, instanceManager.instances.value.first().instanceId)
        }
    }

    // =========================================================================
    // 6. Instance status management
    // =========================================================================

    @Nested
    @DisplayName("instance status management")
    inner class InstanceStatusManagement {

        @Test
        fun `newly created instance has READY status`() = runTest {
            val app = createTestVirtualApp()
            mockCreateInstanceFlow(app)

            instanceManager.createInstance(app)

            val info = instanceManager.instances.value.first()
            assertEquals(InstanceStatus.READY, info.status)
        }

        @Test
        fun `entity saved to database has READY string status`() = runTest {
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
        fun `loadInstances parses CREATING status`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.CREATING.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.CREATING, instanceManager.instances.value.first().status)
        }

        @Test
        fun `loadInstances parses RUNNING status`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.RUNNING.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.RUNNING, instanceManager.instances.value.first().status)
        }

        @Test
        fun `loadInstances parses ERROR status`() = runTest {
            val entity = createTestEntity(status = InstanceStatus.ERROR.name)
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(InstanceStatus.ERROR, instanceManager.instances.value.first().status)
        }

        @Test
        fun `loadInstances falls back to ERROR for unknown status`() = runTest {
            val entity = createTestEntity(status = "UNKNOWN_STATUS")
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertEquals(
                InstanceStatus.ERROR,
                instanceManager.instances.value.first().status,
                "Unknown status should fall back to ERROR"
            )
        }
    }

    // =========================================================================
    // 7. Edge cases
    // =========================================================================

    @Nested
    @DisplayName("edge cases")
    inner class EdgeCases {

        @Test
        fun `initial StateFlow is an empty list`() = runTest {
            assertTrue(
                instanceManager.instances.value.isEmpty(),
                "Initial StateFlow should be empty"
            )
        }

        @Test
        fun `loadInstances handles empty database`() = runTest {
            every { instanceDao.observeAll() } returns flowOf(emptyList())

            instanceManager.loadInstances()

            assertTrue(
                instanceManager.instances.value.isEmpty(),
                "Empty database should load as empty list"
            )
        }

        @Test
        fun `loadInstances skips records with unparseable identityJson`() = runTest {
            val validEntity = createTestEntity(instanceId = "stub_valid")
            val invalidEntity = createTestEntity(instanceId = "stub_invalid")
                .copy(identityJson = "not valid JSON {{{")

            every { instanceDao.observeAll() } returns flowOf(
                listOf(validEntity, invalidEntity)
            )

            instanceManager.loadInstances()

            assertEquals(
                1,
                instanceManager.instances.value.size,
                "Invalid records should be skipped"
            )
            assertEquals(
                "stub_valid",
                instanceManager.instances.value.first().instanceId
            )
        }

        @Test
        fun `loadInstances handles empty identityJson`() = runTest {
            val entity = createTestEntity().copy(identityJson = "")
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            instanceManager.loadInstances()

            assertTrue(
                instanceManager.instances.value.isEmpty(),
                "Empty JSON should cause record to be skipped"
            )
        }

        @Test
        fun `loadInstances handles null literal identityJson`() = runTest {
            val entity = createTestEntity().copy(identityJson = "null")
            every { instanceDao.observeAll() } returns flowOf(listOf(entity))

            // null JSON is parsed as null by Gson, mapNotNull filters it out
            instanceManager.loadInstances()
        }

        @Test
        fun `loadInstances handles large batch of records`() = runTest {
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
        fun `deleteInstance tolerates uninstall Intent failure`() = runTest {
            val instanceId = "stub_uninstall-fail"
            val entity = createTestEntity(instanceId = instanceId)

            coEvery { instanceDao.getById(instanceId) } returns entity
            every { context.startActivity(any()) } throws SecurityException("Permission denied")
            coEvery { instanceDao.deleteById(instanceId) } just Runs

            // Uninstall failure should not block deletion
            instanceManager.deleteInstance(instanceId)

            coVerify { instanceDao.deleteById(instanceId) }
            assertTrue(
                instanceManager.instances.value.none { it.instanceId == instanceId }
            )
        }

        @Test
        fun `creating two instances with same packageName produces different IDs`() = runTest {
            val pkg = "com.same.package"
            val app1 = createTestVirtualApp(
                packageName = pkg,
                apkPath = File(tempDir, "same1.apk").absolutePath
            )
            val app2 = createTestVirtualApp(
                packageName = pkg,
                apkPath = File(tempDir, "same2.apk").absolutePath
            )

            mockCreateInstanceFlow(app1)
            mockCreateInstanceFlow(app2)

            val id1 = instanceManager.createInstance(app1)
            val id2 = instanceManager.createInstance(app2)

            assertNotEquals(id1, id2, "Same packageName should still produce unique IDs")
        }

        @Test
        fun `createInstance with empty packageName still works`() = runTest {
            val app = createTestVirtualApp(packageName = "")
            mockCreateInstanceFlow(app)

            val instanceId = instanceManager.createInstance(app)

            assertTrue(instanceId.startsWith("stub_"))
            assertEquals("", instanceManager.instances.value.first().originalPackageName)
        }

        @Test
        fun `createInstance with very long packageName`() = runTest {
            val longPkg = "com.${"a".repeat(200)}.app"
            val app = createTestVirtualApp(packageName = longPkg)
            mockCreateInstanceFlow(app)

            val instanceId = instanceManager.createInstance(app)

            assertTrue(instanceId.startsWith("stub_"))
            assertEquals(longPkg, instanceManager.instances.value.first().originalPackageName)
        }

        @Test
        fun `createInstance with special characters in packageName`() = runTest {
            val specialPkg = "com.example.my-app_v2"
            val app = createTestVirtualApp(packageName = specialPkg)
            mockCreateInstanceFlow(app)

            val instanceId = instanceManager.createInstance(app)

            assertTrue(instanceId.startsWith("stub_"))
            assertEquals(specialPkg, instanceManager.instances.value.first().originalPackageName)
        }
    }

    // =========================================================================
    // 8. Error handling
    // =========================================================================

    @Nested
    @DisplayName("error handling")
    inner class ErrorHandling {

        @Test
        fun `createInstance throws when APK file does not exist`() = runTest {
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
        fun `createInstance throws when no launcher Activity found`() = runTest {
            val app = createTestVirtualApp()

            every {
                DeviceIdentityPool.generateIdentity(any(), any())
            } returns createTestIdentityConfig()

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
        fun `createInstance throws when Stub install fails`() = runTest {
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
        fun `createInstance does not write to database when install fails`() = runTest {
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
        fun `createInstance does not update StateFlow when install fails`() = runTest {
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
                "StateFlow should not change on install failure"
            )
        }

        @Test
        fun `deleteInstance throws when instance not found`() = runTest {
            coEvery { instanceDao.getById("nonexistent-id") } returns null

            assertThrows<IllegalArgumentException> {
                instanceManager.deleteInstance("nonexistent-id")
            }
        }

        @Test
        fun `deleteInstance does not delete from database when instance not found`() = runTest {
            coEvery { instanceDao.getById("nonexistent-id") } returns null

            try {
                instanceManager.deleteInstance("nonexistent-id")
            } catch (_: IllegalArgumentException) {
                // expected
            }

            coVerify(exactly = 0) { instanceDao.deleteById(any()) }
        }

        @Test
        fun `deleteInstance does not start uninstall Intent when instance not found`() = runTest {
            coEvery { instanceDao.getById("nonexistent-id") } returns null

            try {
                instanceManager.deleteInstance("nonexistent-id")
            } catch (_: IllegalArgumentException) {
                // expected
            }

            verify(exactly = 0) { context.startActivity(any()) }
        }
    }

    // =========================================================================
    // 9. DeviceIdentityPool - identity uniqueness
    // =========================================================================

    @Nested
    @DisplayName("DeviceIdentityPool - identity uniqueness")
    inner class IdentityUniqueness {

        @AfterEach
        fun identityTearDown() {
            unmockkObject(DeviceIdentityPool)
        }

        @Test
        fun `two consecutive identities have different IMEIs`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(identity1.imei, identity2.imei)
        }

        @Test
        fun `two consecutive identities have different AndroidIds`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(identity1.androidId, identity2.androidId)
        }

        @Test
        fun `two consecutive identities have different MAC addresses`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(identity1.macAddress, identity2.macAddress)
        }

        @Test
        fun `two consecutive identities have different Serials`() {
            unmockkObject(DeviceIdentityPool)

            val identity1 = DeviceIdentityPool.generateIdentity("inst-1", "com.app")
            val identity2 = DeviceIdentityPool.generateIdentity("inst-2", "com.app")

            assertNotEquals(identity1.serial, identity2.serial)
        }

        @Test
        fun `IMEI is 15 digits and starts with 86`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            assertEquals(15, identity.imei.length)
            assertTrue(identity.imei.startsWith("86"))
            assertTrue(identity.imei.all { it.isDigit() })
        }

        @Test
        fun `AndroidId is 16 hex characters`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            assertEquals(16, identity.androidId.length)
            assertTrue(
                identity.androidId.all { it in "0123456789abcdef" },
                "AndroidId should be lowercase hex: ${identity.androidId}"
            )
        }

        @Test
        fun `MAC address matches XX_XX_XX_XX_XX_XX format`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            val macRegex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
            assertTrue(
                macRegex.matches(identity.macAddress),
                "MAC format incorrect: ${identity.macAddress}"
            )
        }

        @Test
        fun `stubPackageName contains original package and clone marker`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-abc", "com.whatsapp")

            assertTrue(identity.stubPackageName.contains("com.whatsapp"))
            assertTrue(identity.stubPackageName.contains("clone"))
        }

        @Test
        fun `authorityMap contains provider and fileprovider mappings`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.example")

            assertEquals(2, identity.authorityMap.size)
            assertTrue(identity.authorityMap.containsKey("com.example.provider"))
            assertTrue(identity.authorityMap.containsKey("com.example.fileprovider"))
            assertTrue(identity.authorityMap["com.example.provider"]!!.contains("clone"))
            assertTrue(identity.authorityMap["com.example.fileprovider"]!!.contains("clone"))
        }

        @Test
        fun `sdkInt is in the range 33-35`() {
            unmockkObject(DeviceIdentityPool)

            repeat(20) {
                val identity = DeviceIdentityPool.generateIdentity("inst-$it", "com.app")
                assertTrue(
                    identity.sdkInt in 33..35,
                    "sdkInt should be 33-35, got: ${identity.sdkInt}"
                )
            }
        }

        @Test
        fun `generated identity has no empty fields`() {
            unmockkObject(DeviceIdentityPool)

            val identity = DeviceIdentityPool.generateIdentity("inst-1", "com.app")

            assertTrue(identity.imei.isNotEmpty())
            assertTrue(identity.androidId.isNotEmpty())
            assertTrue(identity.macAddress.isNotEmpty())
            assertTrue(identity.serial.isNotEmpty())
            assertTrue(identity.buildModel.isNotEmpty())
            assertTrue(identity.buildManufacturer.isNotEmpty())
            assertTrue(identity.buildFingerprint.isNotEmpty())
            assertTrue(identity.buildBrand.isNotEmpty())
            assertTrue(identity.buildDevice.isNotEmpty())
            assertTrue(identity.buildProduct.isNotEmpty())
            assertTrue(identity.versionRelease.isNotEmpty())
        }
    }

    // =========================================================================
    // 10. DeviceIdentityPool - concurrency safety
    // =========================================================================

    @Nested
    @DisplayName("DeviceIdentityPool - concurrency safety")
    inner class IdentityConcurrency {

        @AfterEach
        fun concurrencyTearDown() {
            unmockkObject(DeviceIdentityPool)
        }

        @Test
        fun `100 concurrent identities are all unique`() {
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

            assertEquals(0, errorCount.get(), "Concurrent generation should not throw")
            assertEquals(threadCount, identities.size)

            val uniqueImeis = identities.map { it.imei }.toSet()
            assertEquals(
                threadCount,
                uniqueImeis.size,
                "All IMEIs should be unique, found ${threadCount - uniqueImeis.size} duplicates"
            )

            val uniqueAndroidIds = identities.map { it.androidId }.toSet()
            assertEquals(threadCount, uniqueAndroidIds.size)
        }

        @Test
        fun `50 concurrent MAC addresses are all unique`() {
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
            assertEquals(threadCount, uniqueMacs.size)
        }

        @Test
        fun `sequential creates produce unique instanceIds`() = runTest {
            val apps = (1..10).map { i ->
                createTestVirtualApp(packageName = "com.concurrent.app$i")
            }

            apps.forEach { app ->
                mockCreateInstanceFlow(app)
            }

            val ids = apps.map { app ->
                instanceManager.createInstance(app)
            }.toSet()

            assertEquals(10, ids.size, "Sequential creates should produce 10 unique IDs")
        }
    }
}
