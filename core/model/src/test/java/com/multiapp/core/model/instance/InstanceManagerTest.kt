package com.multiapp.core.model.instance

import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.JsonInstallRecordStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InstanceManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: JsonInstanceRecordStore
    private lateinit var manager: DefaultInstanceManager
    private var currentTimeMs: Long = 1000L

    @BeforeEach
    fun setUp() {
        store = JsonInstanceRecordStore(File(tempDir, "records"))
        store.listFiles() // ensure dir exists
        manager = DefaultInstanceManager(
            store = store,
            dataRootBase = File(tempDir, "data"),
            clock = { currentTimeMs }
        )
    }

    @Test
    fun `createInstance generates unique ID`() {
        val r1 = manager.createInstance("com.example.app", "Example 1")
        val r2 = manager.createInstance("com.example.app", "Example 2")

        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        assertTrue(r1.getOrNull()!!.instanceId != r2.getOrNull()!!.instanceId)
    }

    @Test
    fun `createInstance creates dataRoot directories`() {
        val result = manager.createInstance("com.example.app", "Example")
        assertTrue(result.isSuccess)

        val record = result.getOrNull()!!
        val dataRoot = manager.getDataRoot(record.instanceId)
        assertNotNull(dataRoot)

        assertTrue(dataRoot.baseDir.isDirectory)
        assertTrue(dataRoot.dataDir.isDirectory)
        assertTrue(dataRoot.cacheDir.isDirectory)
        assertTrue(dataRoot.filesDir.isDirectory)
        assertTrue(dataRoot.sharedPrefsDir.isDirectory)
        assertTrue(dataRoot.databaseDir.isDirectory)
    }

    @Test
    fun `createInstance saves record to store`() {
        val result = manager.createInstance("com.example.app", "Example")
        assertTrue(result.isSuccess)

        val record = result.getOrNull()!!
        val loaded = store.load(record.instanceId)
        assertNotNull(loaded)
        assertEquals(record.instanceId, loaded.instanceId)
        assertEquals(record.originPackageName, loaded.originPackageName)
    }

    @Test
    fun `createInstance sets virtualPackageName with short ID`() {
        val result = manager.createInstance("com.example.app", "Example")
        assertTrue(result.isSuccess)

        val record = result.getOrNull()!!
        assertTrue(record.virtualPackageName.startsWith("com.multiapp.instance."))
        assertTrue(record.virtualPackageName.length > "com.multiapp.instance.".length)
    }

    @Test
    fun `createInstance sets state to READY`() {
        val result = manager.createInstance("com.example.app", "Example")
        assertTrue(result.isSuccess)

        assertEquals(InstanceState.READY, result.getOrNull()!!.state)
    }

    @Test
    fun `createInstance sets createdAtMs and updatedAtMs to clock time`() {
        currentTimeMs = 5000L
        val result = manager.createInstance("com.example.app", "Example")
        assertTrue(result.isSuccess)

        val record = result.getOrNull()!!
        assertEquals(5000L, record.createdAtMs)
        assertEquals(5000L, record.updatedAtMs)
    }

    @Test
    fun `getInstance returns created instance`() {
        val created = manager.createInstance("com.example.app", "Example").getOrNull()!!
        val found = manager.getInstance(created.instanceId)

        assertNotNull(found)
        assertEquals(created.instanceId, found.instanceId)
    }

    @Test
    fun `getInstance returns null for non-existent`() {
        assertNull(manager.getInstance("non-existent"))
    }

    @Test
    fun `getInstanceByOrigin returns all instances for origin`() {
        manager.createInstance("com.example.app", "Ex1")
        manager.createInstance("com.example.app", "Ex2")
        manager.createInstance("com.other.app", "Other")

        val results = manager.getInstanceByOrigin("com.example.app")
        assertEquals(2, results.size)
        assertTrue(results.all { it.originPackageName == "com.example.app" })
    }

    @Test
    fun `getInstanceByOrigin returns empty for unknown origin`() {
        assertTrue(manager.getInstanceByOrigin("com.nonexistent").isEmpty())
    }

    @Test
    fun `listInstances returns all instances`() {
        manager.createInstance("com.a", "A")
        manager.createInstance("com.b", "B")
        manager.createInstance("com.c", "C")

        assertEquals(3, manager.listInstances().size)
    }

    @Test
    fun `listInstances returns empty when none created`() {
        assertTrue(manager.listInstances().isEmpty())
    }

    @Test
    fun `deleteInstance removes record and dataRoot`() {
        val created = manager.createInstance("com.example.app", "Example").getOrNull()!!
        val dataRoot = manager.getDataRoot(created.instanceId)!!
        val baseDir = dataRoot.baseDir

        assertTrue(baseDir.exists())
        assertTrue(manager.deleteInstance(created.instanceId))
        assertFalse(baseDir.exists())
        assertNull(manager.getInstance(created.instanceId))
    }

    @Test
    fun `deleteInstance returns false for non-existent`() {
        assertFalse(manager.deleteInstance("non-existent"))
    }

    @Test
    fun `updateLaunchState increments launchCount`() {
        val created = manager.createInstance("com.example.app", "Example").getOrNull()!!
        assertEquals(0, created.launchCount)

        currentTimeMs = 2000L
        val updated = manager.updateLaunchState(created.instanceId)
        assertNotNull(updated)
        assertEquals(1, updated.launchCount)

        currentTimeMs = 3000L
        val updated2 = manager.updateLaunchState(created.instanceId)
        assertNotNull(updated2)
        assertEquals(2, updated2.launchCount)
    }

    @Test
    fun `updateLaunchState updates lastLaunchAtMs`() {
        val created = manager.createInstance("com.example.app", "Example").getOrNull()!!
        assertNull(created.lastLaunchAtMs)

        currentTimeMs = 2000L
        val updated = manager.updateLaunchState(created.instanceId)
        assertNotNull(updated)
        assertEquals(2000L, updated.lastLaunchAtMs)
    }

    @Test
    fun `updateLaunchState returns null for non-existent`() {
        assertNull(manager.updateLaunchState("non-existent"))
    }

    @Test
    fun `two instances for same origin have different dataRoot`() {
        val r1 = manager.createInstance("com.example.app", "Ex1").getOrNull()!!
        val r2 = manager.createInstance("com.example.app", "Ex2").getOrNull()!!

        assertTrue(r1.dataRoot != r2.dataRoot)
        assertTrue(r1.virtualPackageName != r2.virtualPackageName)
    }

    @Test
    fun `getDataRoot returns null for non-existent instance`() {
        assertNull(manager.getDataRoot("non-existent"))
    }

    @Test
    fun `getDataRoot returns data root with correct structure`() {
        val created = manager.createInstance("com.example.app", "Example").getOrNull()!!
        val dataRoot = manager.getDataRoot(created.instanceId)!!

        assertEquals(created.instanceId, dataRoot.instanceId)
        assertTrue(dataRoot.baseDir.path.contains(created.instanceId))
        assertEquals(File(dataRoot.baseDir, "data"), dataRoot.dataDir)
        assertEquals(File(dataRoot.baseDir, "cache"), dataRoot.cacheDir)
        assertEquals(File(dataRoot.baseDir, "files"), dataRoot.filesDir)
        assertEquals(File(dataRoot.baseDir, "shared_prefs"), dataRoot.sharedPrefsDir)
        assertEquals(File(dataRoot.baseDir, "databases"), dataRoot.databaseDir)
    }

    // ── R1: InstallRecord validation tests ──────────────────────────────

    @Test
    fun `createInstance fails when installRecordStore is provided but no InstallRecord exists`() {
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_validation"))
        val managerWithValidation = DefaultInstanceManager(
            store = store,
            dataRootBase = File(tempDir, "data_validation"),
            installRecordStore = installStore,
            clock = { currentTimeMs }
        )

        val result = managerWithValidation.createInstance("com.example.missing", "Missing App")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()!!
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("InstallRecord not found"))
        assertTrue(error.message!!.contains("com.example.missing"))
    }

    @Test
    fun `createInstance succeeds when installRecordStore is provided and InstallRecord exists`() {
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_validation2"))
        // Save an InstallRecord first
        val installRecord = InstallRecord(
            packageName = "com.example.validated",
            originApkPath = "/tmp/test.apk",
            originApkSha256 = "abc123",
            originCertSha256 = "def456",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            installTimeMs = 1000L
        )
        installStore.save(installRecord)

        val managerWithValidation = DefaultInstanceManager(
            store = store,
            dataRootBase = File(tempDir, "data_validation2"),
            installRecordStore = installStore,
            clock = { currentTimeMs }
        )

        val result = managerWithValidation.createInstance("com.example.validated", "Validated App")

        assertTrue(result.isSuccess)
        assertEquals("com.example.validated", result.getOrNull()!!.originPackageName)
    }

    @Test
    fun `createInstance persists only originPackageName reference to InstallRecord facts`() {
        val installStore = JsonInstallRecordStore(File(tempDir, "installs_reference_only"))
        val installRecord = InstallRecord(
            packageName = "com.example.referenceonly",
            originApkPath = "/tmp/reference-only.apk",
            originApkSha256 = "origin-sha",
            originCertSha256 = "cert-sha",
            versionCode = 42,
            versionName = "4.2",
            targetSdk = 35,
            minSdk = 28,
            activities = listOf(com.multiapp.core.model.installer.ComponentInfo("com.example.MainActivity")),
            nativeLibraries = listOf("libsample.so"),
            abiList = listOf("arm64-v8a"),
            installTimeMs = 1000L
        )
        installStore.save(installRecord).getOrThrow()
        val managerWithValidation = DefaultInstanceManager(
            store = store,
            dataRootBase = File(tempDir, "data_reference_only"),
            installRecordStore = installStore,
            clock = { currentTimeMs }
        )

        val result = managerWithValidation.createInstance("com.example.referenceonly", "Reference App")

        assertTrue(result.isSuccess)
        val record = result.getOrThrow()
        val persisted = store.load(record.instanceId)!!
        val persistedJson = File(tempDir, "records/${record.instanceId}.json").readText()
        assertEquals("com.example.referenceonly", persisted.originPackageName)
        assertFalse(persistedJson.contains("originApkPath"))
        assertFalse(persistedJson.contains("originApkSha256"))
        assertFalse(persistedJson.contains("originCertSha256"))
        assertFalse(persistedJson.contains("activities"))
        assertFalse(persistedJson.contains("nativeLibraries"))
    }

    @Test
    fun `createInstance succeeds without installRecordStore validation`() {
        // When installRecordStore is null (default), no validation is performed
        val result = manager.createInstance("com.example.any", "Any App")

        assertTrue(result.isSuccess)
        assertEquals("com.example.any", result.getOrNull()!!.originPackageName)
    }
}
