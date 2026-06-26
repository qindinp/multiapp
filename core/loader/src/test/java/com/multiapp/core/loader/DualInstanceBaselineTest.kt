package com.multiapp.core.loader

import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.InstanceRecordStore
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.InstallRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualStoragePaths
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step 6: Ordinary app dual-instance baseline.
 *
 * Pure JVM tests verifying that two instances of the same origin package
 * can coexist with isolated data roots, independent ClassLoaders, and
 * separate VirtualContextConfig paths.
 */
class DualInstanceBaselineTest {

    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceRecordStore
    private lateinit var installStore: InstallRecordStore
    private lateinit var instanceManager: DefaultInstanceManager
    private lateinit var originApk: File

    @BeforeEach
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "multiapp-dual-test-${System.nanoTime()}")
        tempDir.mkdirs()

        instanceStore = JsonInstanceRecordStore(tempDir.resolve("instances"))
        installStore = JsonInstallRecordStore(tempDir.resolve("installs"))
        instanceManager = DefaultInstanceManager(
            store = instanceStore,
            dataRootBase = tempDir.resolve("data"),
            clock = { 1000L }
        )

        // Create a fake origin APK
        originApk = tempDir.resolve("base.apk")
        originApk.writeText("fake apk content")

        // Create an install record
        val record = InstallRecord(
            packageName = "com.example.testapp",
            originApkPath = originApk.absolutePath,
            originApkSha256 = "abc123",
            originCertSha256 = "def456",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 34,
            minSdk = 21,
            packageLabel = "TestApp",
            installTimeMs = 1000L,
            updatedAtMs = 1000L
        )
        installStore.save(record)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Dual instance creation ────────────────────────────────────────

    @Test
    fun `same origin package can create two instances with different IDs`() {
        val r1 = instanceManager.createInstance("com.example.testapp", "Instance A")
        val r2 = instanceManager.createInstance("com.example.testapp", "Instance B")

        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        assertNotEquals(r1.getOrThrow().instanceId, r2.getOrThrow().instanceId)
    }

    @Test
    fun `two instances have different dataRoot`() {
        val r1 = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val r2 = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()

        assertNotEquals(r1.dataRoot, r2.dataRoot)
    }

    @Test
    fun `two instances share same origin package name`() {
        val r1 = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val r2 = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()

        assertEquals("com.example.testapp", r1.originPackageName)
        assertEquals("com.example.testapp", r2.originPackageName)
    }

    // ── HostedRuntimeBootstrap dual instance ──────────────────────────

    @Test
    fun `HostedRuntimeBootstrap can bootstrap first instance`() {
        val instance = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val bootstrap = HostedRuntimeBootstrap(instanceManager, installStore)
        val result = bootstrap.run(instance.instanceId)

        assertTrue(result.success)
        assertEquals("com.example.testapp", result.originPackageName)
        assertNotNull(result.guestClassLoader)
    }

    @Test
    fun `HostedRuntimeBootstrap can bootstrap second instance`() {
        val instance = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()
        val bootstrap = HostedRuntimeBootstrap(instanceManager, installStore)
        val result = bootstrap.run(instance.instanceId)

        assertTrue(result.success)
        assertEquals("com.example.testapp", result.originPackageName)
        assertNotNull(result.guestClassLoader)
    }

    @Test
    fun `both instances share same origin APK path`() {
        val i1 = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val i2 = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()

        val bootstrap = HostedRuntimeBootstrap(instanceManager, installStore)
        val r1 = bootstrap.run(i1.instanceId)
        val r2 = bootstrap.run(i2.instanceId)

        assertEquals(r1.originApkPath, r2.originApkPath)
    }

    @Test
    fun `each instance gets its own ClassLoader`() {
        val i1 = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val i2 = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()

        var classLoaderCount = 0
        val factory = { _: String, _: String? -> ClassLoader.getSystemClassLoader().let { object : ClassLoader(it) {} } }

        val bootstrap = HostedRuntimeBootstrap(instanceManager, installStore, classLoaderFactory = factory)
        val r1 = bootstrap.run(i1.instanceId)
        val r2 = bootstrap.run(i2.instanceId)

        assertNotNull(r1.guestClassLoader)
        assertNotNull(r2.guestClassLoader)
        assertNotEquals(r1.guestClassLoader, r2.guestClassLoader)
    }

    // ── VirtualContextConfig isolation ────────────────────────────────

    @Test
    fun `VirtualContextConfig for each instance has different dataDir`() {
        val i1 = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val i2 = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()

        val config1 = VirtualContextConfig(
            instanceId = i1.instanceId,
            originPackageName = i1.originPackageName,
            virtualPackageName = i1.virtualPackageName,
            dataDir = i1.dataRoot,
            sourceDir = originApk.absolutePath,
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )
        val config2 = VirtualContextConfig(
            instanceId = i2.instanceId,
            originPackageName = i2.originPackageName,
            virtualPackageName = i2.virtualPackageName,
            dataDir = i2.dataRoot,
            sourceDir = originApk.absolutePath,
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )

        assertNotEquals(config1.dataDir, config2.dataDir)
    }

    @Test
    fun `VirtualContextConfig for each instance has same sourceDir`() {
        val i1 = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val i2 = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()

        val config1 = VirtualContextConfig(
            instanceId = i1.instanceId,
            originPackageName = i1.originPackageName,
            virtualPackageName = i1.virtualPackageName,
            dataDir = i1.dataRoot,
            sourceDir = originApk.absolutePath,
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )
        val config2 = VirtualContextConfig(
            instanceId = i2.instanceId,
            originPackageName = i2.originPackageName,
            virtualPackageName = i2.virtualPackageName,
            dataDir = i2.dataRoot,
            sourceDir = originApk.absolutePath,
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )

        assertEquals(config1.sourceDir, config2.sourceDir)
    }

    // ── Instance deletion ─────────────────────────────────────────────

    @Test
    fun `deleting one instance does not affect the other`() {
        val i1 = instanceManager.createInstance("com.example.testapp", "Instance A").getOrThrow()
        val i2 = instanceManager.createInstance("com.example.testapp", "Instance B").getOrThrow()

        instanceManager.deleteInstance(i1.instanceId)

        assertNull(instanceManager.getInstance(i1.instanceId))
        assertNotNull(instanceManager.getInstance(i2.instanceId))
    }

    // ── Storage isolation ─────────────────────────────────────────────

    @Test
    fun `VirtualStorageManager allocates separate directories for each instance`() {
        val storageManager = com.multiapp.core.model.virtual.FileBasedStorageManager(tempDir.resolve("storage").absolutePath)

        val paths1 = storageManager.allocateStorage("inst-1", tempDir.resolve("storage").absolutePath)
        val paths2 = storageManager.allocateStorage("inst-2", tempDir.resolve("storage").absolutePath)

        assertNotEquals(paths1.dataDir, paths2.dataDir)
        assertNotEquals(paths1.filesDir, paths2.filesDir)
        assertNotEquals(paths1.cacheDir, paths2.cacheDir)
        assertNotEquals(paths1.databasesDir, paths2.databasesDir)
        assertNotEquals(paths1.sharedPrefsDir, paths2.sharedPrefsDir)
    }
}
