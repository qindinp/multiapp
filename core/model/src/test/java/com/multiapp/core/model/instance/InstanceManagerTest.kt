package com.multiapp.core.model.instance

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
}
