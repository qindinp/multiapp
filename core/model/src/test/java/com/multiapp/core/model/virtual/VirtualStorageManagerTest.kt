package com.multiapp.core.model.virtual

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualStorageManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var manager: FileBasedStorageManager

    @BeforeEach
    fun setUp() {
        manager = FileBasedStorageManager(tempDir.absolutePath)
    }

    @Test
    fun `allocateStorage creates all directories`() {
        val paths = manager.allocateStorage("instance-1", tempDir.absolutePath)

        assertTrue(File(paths.filesDir).exists(), "filesDir should exist")
        assertTrue(File(paths.cacheDir).exists(), "cacheDir should exist")
        assertTrue(File(paths.databasesDir).exists(), "databasesDir should exist")
        assertTrue(File(paths.sharedPrefsDir).exists(), "sharedPrefsDir should exist")
        assertNotNull(paths.externalFilesDir)
        assertTrue(File(paths.externalFilesDir!!).exists(), "externalFilesDir should exist")
        assertNotNull(paths.externalCacheDir)
        assertTrue(File(paths.externalCacheDir!!).exists(), "externalCacheDir should exist")
    }

    @Test
    fun `allocateStorage returns correct paths`() {
        val baseDir = tempDir.absolutePath
        val paths = manager.allocateStorage("inst-42", baseDir)

        assertEquals("inst-42", paths.instanceId)
        assertEquals("$baseDir/inst-42", paths.dataDir)
        assertEquals("$baseDir/inst-42/files", paths.filesDir)
        assertEquals("$baseDir/inst-42/cache", paths.cacheDir)
        assertEquals("$baseDir/inst-42/databases", paths.databasesDir)
        assertEquals("$baseDir/inst-42/shared_prefs", paths.sharedPrefsDir)
        assertEquals("$baseDir/inst-42/external_files", paths.externalFilesDir)
        assertEquals("$baseDir/inst-42/external_cache", paths.externalCacheDir)
    }

    @Test
    fun `getStoragePaths returns null for nonexistent instance`() {
        val result = manager.getStoragePaths("nonexistent-instance")
        assertNull(result, "Should return null for nonexistent instance")
    }

    @Test
    fun `getStoragePaths returns paths for allocated instance`() {
        val baseDir = tempDir.absolutePath
        val allocated = manager.allocateStorage("inst-100", baseDir)

        val retrieved = manager.getStoragePaths("inst-100")

        assertNotNull(retrieved)
        assertEquals(allocated.instanceId, retrieved.instanceId)
        assertEquals(allocated.dataDir, retrieved.dataDir)
        assertEquals(allocated.filesDir, retrieved.filesDir)
        assertEquals(allocated.cacheDir, retrieved.cacheDir)
        assertEquals(allocated.databasesDir, retrieved.databasesDir)
        assertEquals(allocated.sharedPrefsDir, retrieved.sharedPrefsDir)
        assertEquals(allocated.externalFilesDir, retrieved.externalFilesDir)
        assertEquals(allocated.externalCacheDir, retrieved.externalCacheDir)
    }

    @Test
    fun `deleteStorage removes all directories`() {
        val baseDir = tempDir.absolutePath
        val paths = manager.allocateStorage("inst-to-delete", baseDir)

        // Verify directories exist before deletion
        assertTrue(File(paths.dataDir).exists())

        val deleted = manager.deleteStorage("inst-to-delete")

        assertTrue(deleted, "deleteStorage should return true")
        assertFalse(File(paths.dataDir).exists(), "dataDir should be removed")
        assertFalse(File(paths.filesDir).exists(), "filesDir should be removed")
        assertFalse(File(paths.cacheDir).exists(), "cacheDir should be removed")
        assertFalse(File(paths.databasesDir).exists(), "databasesDir should be removed")
        assertFalse(File(paths.sharedPrefsDir).exists(), "sharedPrefsDir should be removed")
    }

    @Test
    fun `two instances have different storage paths`() {
        val baseDir = tempDir.absolutePath
        val paths1 = manager.allocateStorage("instance-A", baseDir)
        val paths2 = manager.allocateStorage("instance-B", baseDir)

        assertTrue(paths1.dataDir != paths2.dataDir, "dataDir should differ")
        assertTrue(paths1.filesDir != paths2.filesDir, "filesDir should differ")
        assertTrue(paths1.cacheDir != paths2.cacheDir, "cacheDir should differ")
        assertTrue(paths1.databasesDir != paths2.databasesDir, "databasesDir should differ")
        assertTrue(paths1.sharedPrefsDir != paths2.sharedPrefsDir, "sharedPrefsDir should differ")

        // Both should exist independently
        assertTrue(File(paths1.dataDir).exists())
        assertTrue(File(paths2.dataDir).exists())
    }

    @Test
    fun `ensureDirectories is idempotent`() {
        val paths = VirtualStoragePaths(
            instanceId = "idempotent-test",
            dataDir = "${tempDir.absolutePath}/idempotent-test",
            filesDir = "${tempDir.absolutePath}/idempotent-test/files",
            cacheDir = "${tempDir.absolutePath}/idempotent-test/cache",
            databasesDir = "${tempDir.absolutePath}/idempotent-test/databases",
            sharedPrefsDir = "${tempDir.absolutePath}/idempotent-test/shared_prefs",
            externalFilesDir = "${tempDir.absolutePath}/idempotent-test/external_files",
            externalCacheDir = "${tempDir.absolutePath}/idempotent-test/external_cache"
        )

        // Call ensureDirectories twice - should not throw
        manager.ensureDirectories(paths)
        manager.ensureDirectories(paths)

        // All directories should still exist
        assertTrue(File(paths.filesDir).exists())
        assertTrue(File(paths.cacheDir).exists())
        assertTrue(File(paths.databasesDir).exists())
        assertTrue(File(paths.sharedPrefsDir).exists())
        assertTrue(File(paths.externalFilesDir!!).exists())
        assertTrue(File(paths.externalCacheDir!!).exists())
    }
}
