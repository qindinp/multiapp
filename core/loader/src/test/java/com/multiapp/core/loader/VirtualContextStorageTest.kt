package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VirtualContextStorageTest {

    @Test
    fun `storage directories stay under instance data root`(@TempDir dataRoot: File) {
        assertEquals(File(dataRoot, "files"), VirtualContextStorage.filesDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "cache"), VirtualContextStorage.cacheDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "code_cache"), VirtualContextStorage.codeCacheDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "no_backup"), VirtualContextStorage.noBackupFilesDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "databases"), VirtualContextStorage.databasesDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "shared_prefs"), VirtualContextStorage.sharedPrefsDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "external_cache"), VirtualContextStorage.externalCacheDir(dataRoot.absolutePath))

        assertTrue(VirtualContextStorage.filesDir(dataRoot.absolutePath).isDirectory)
        assertTrue(VirtualContextStorage.cacheDir(dataRoot.absolutePath).isDirectory)
        assertTrue(VirtualContextStorage.codeCacheDir(dataRoot.absolutePath).isDirectory)
        assertTrue(VirtualContextStorage.noBackupFilesDir(dataRoot.absolutePath).isDirectory)
    }

    @Test
    fun `file and database paths sanitize path separators`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "files/a_b_c.txt"),
            VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, "a/b\\c.txt")
        )
        assertEquals(
            File(dataRoot, "databases/main_db"),
            VirtualContextStorage.databasePath(dataRoot.absolutePath, "main/db")
        )
        assertEquals(
            File(dataRoot, "shared_prefs/default.xml"),
            VirtualContextStorage.sharedPrefsPath(dataRoot.absolutePath, "")
        )
    }

    @Test
    fun `listFileNames returns sorted names`(@TempDir dataRoot: File) {
        val filesDir = VirtualContextStorage.filesDir(dataRoot.absolutePath)
        File(filesDir, "b.txt").writeText("b")
        File(filesDir, "a.txt").writeText("a")

        assertArrayEquals(arrayOf("a.txt", "b.txt"), VirtualContextStorage.listFileNames(filesDir))
    }

    @Test
    fun `external files type is scoped under external files root`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "external_files"),
            VirtualContextStorage.externalFilesDir(dataRoot.absolutePath, null)
        )
        assertEquals(
            File(dataRoot, "external_files/images_private"),
            VirtualContextStorage.externalFilesDir(dataRoot.absolutePath, "images/private")
        )
    }
}