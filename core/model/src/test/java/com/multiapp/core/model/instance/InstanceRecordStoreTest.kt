package com.multiapp.core.model.instance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InstanceRecordStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: JsonInstanceRecordStore

    @BeforeEach
    fun setUp() {
        store = JsonInstanceRecordStore(tempDir)
    }

    @Test
    fun `save and load roundtrip`() {
        val record = makeRecord(instanceId = "inst-1", originPackageName = "com.example.app")
        val saveResult = store.save(record)

        assertTrue(saveResult.isSuccess)
        assertEquals("inst-1", saveResult.getOrNull())

        val loaded = store.load("inst-1")
        assertNotNull(loaded)
        assertEquals(record.instanceId, loaded.instanceId)
        assertEquals(record.originPackageName, loaded.originPackageName)
        assertEquals(record.virtualPackageName, loaded.virtualPackageName)
        assertEquals(record.displayName, loaded.displayName)
        assertEquals(record.compatibilityMode, loaded.compatibilityMode)
        assertEquals(record.launchCount, loaded.launchCount)
    }

    @Test
    fun `load returns null for non-existent instance`() {
        assertNull(store.load("non-existent"))
    }

    @Test
    fun `loadByOrigin returns matching instances`() {
        store.save(makeRecord(instanceId = "a", originPackageName = "com.example.one"))
        store.save(makeRecord(instanceId = "b", originPackageName = "com.example.two"))
        store.save(makeRecord(instanceId = "c", originPackageName = "com.example.one"))

        val results = store.loadByOrigin("com.example.one")
        assertEquals(2, results.size)
        assertTrue(results.all { it.originPackageName == "com.example.one" })
    }

    @Test
    fun `loadByOrigin returns empty list when no match`() {
        store.save(makeRecord(instanceId = "a", originPackageName = "com.example.one"))

        val results = store.loadByOrigin("com.nonexistent")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `listAll returns all records`() {
        store.save(makeRecord(instanceId = "a"))
        store.save(makeRecord(instanceId = "b"))
        store.save(makeRecord(instanceId = "c"))

        val all = store.listAll()
        assertEquals(3, all.size)
    }

    @Test
    fun `listAll returns empty list when no records`() {
        assertTrue(store.listAll().isEmpty())
    }

    @Test
    fun `delete removes record`() {
        store.save(makeRecord(instanceId = "inst-1"))
        assertTrue(store.delete("inst-1"))
        assertNull(store.load("inst-1"))
    }

    @Test
    fun `delete returns false for non-existent instance`() {
        assertFalse(store.delete("non-existent"))
    }

    @Test
    fun `atomic write does not leave temp file behind`() {
        store.save(makeRecord(instanceId = "inst-1"))

        val files = tempDir.listFiles() ?: emptyArray()
        val tempFiles = files.filter { it.name.endsWith(".tmp") }
        assertTrue(tempFiles.isEmpty(), "No temp files should remain, found: $tempFiles")
    }

    @Test
    fun `each instance saved to separate JSON file`() {
        store.save(makeRecord(instanceId = "aaa"))
        store.save(makeRecord(instanceId = "bbb"))

        assertTrue(File(tempDir, "aaa.json").exists())
        assertTrue(File(tempDir, "bbb.json").exists())
    }

    @Test
    fun `schemaVersion is preserved through save and load`() {
        val record = makeRecord(schemaVersion = 42)
        store.save(record)

        val loaded = store.load(record.instanceId)
        assertNotNull(loaded)
        assertEquals(42, loaded.schemaVersion)
    }

    @Test
    fun `save overwrites existing record with same instanceId`() {
        store.save(makeRecord(instanceId = "inst-1", displayName = "Original"))
        store.save(makeRecord(instanceId = "inst-1", displayName = "Updated"))

        val loaded = store.load("inst-1")
        assertNotNull(loaded)
        assertEquals("Updated", loaded.displayName)
    }

    @Test
    fun `separate stores serialize concurrent writes to the same record`() {
        val firstStore = JsonInstanceRecordStore(tempDir)
        val secondStore = JsonInstanceRecordStore(tempDir)
        val workersReady = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val writes = listOf("first", "second").map { displayName ->
            executor.submit {
                workersReady.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                check(
                    (if (displayName == "first") firstStore else secondStore)
                        .save(makeRecord(instanceId = "shared", displayName = displayName))
                        .isSuccess
                )
            }
        }
        assertTrue(workersReady.await(5, TimeUnit.SECONDS))
        start.countDown()
        writes.forEach { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        val loaded = store.load("shared")
        assertNotNull(loaded)
        assertTrue(loaded.displayName in setOf("first", "second"))
        assertTrue(File(tempDir, "shared.json").readText().contains(loaded.displayName))
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") || it.name.endsWith(".bak") })
    }

    private fun makeRecord(
        instanceId: String = "id-1",
        originPackageName: String = "com.example",
        virtualPackageName: String = "com.multiapp.instance.id1",
        displayName: String = "Example",
        dataRoot: String = "/data/user/0/com.multiapp.instance.id1",
        compatibilityMode: CompatibilityMode = CompatibilityMode.STANDARD,
        schemaVersion: Int = 1,
        createdAtMs: Long = 1000L,
        updatedAtMs: Long = 1000L,
        launchCount: Int = 0,
        lastLaunchAtMs: Long? = null,
        state: InstanceState = InstanceState.READY
    ) = VirtualInstanceRecord(
        schemaVersion = schemaVersion,
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        displayName = displayName,
        dataRoot = dataRoot,
        compatibilityMode = compatibilityMode,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        launchCount = launchCount,
        lastLaunchAtMs = lastLaunchAtMs,
        state = state
    )
}
