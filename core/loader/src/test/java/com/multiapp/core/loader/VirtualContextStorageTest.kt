package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VirtualContextStorageTest {

    @Test
    fun `storage directories stay under instance data root`(@TempDir dataRoot: File) {
        assertEquals(File(dataRoot, "files").canonicalFile, VirtualContextStorage.filesDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "cache").canonicalFile, VirtualContextStorage.cacheDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "code_cache").canonicalFile, VirtualContextStorage.codeCacheDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "no_backup").canonicalFile, VirtualContextStorage.noBackupFilesDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "databases").canonicalFile, VirtualContextStorage.databasesDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "shared_prefs").canonicalFile, VirtualContextStorage.sharedPrefsDir(dataRoot.absolutePath))
        assertEquals(File(dataRoot, "external_cache").canonicalFile, VirtualContextStorage.externalCacheDir(dataRoot.absolutePath))

        assertTrue(VirtualContextStorage.filesDir(dataRoot.absolutePath).isDirectory)
        assertTrue(VirtualContextStorage.cacheDir(dataRoot.absolutePath).isDirectory)
        assertTrue(VirtualContextStorage.codeCacheDir(dataRoot.absolutePath).isDirectory)
        assertTrue(VirtualContextStorage.noBackupFilesDir(dataRoot.absolutePath).isDirectory)
    }

    @Test
    fun `file and database paths resolve safe path segments`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "files/probe.txt").canonicalFile,
            VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, "probe.txt")
        )
        assertEquals(
            File(dataRoot, "databases/probe.db").canonicalFile,
            VirtualContextStorage.databasePath(dataRoot.absolutePath, "probe.db")
        )
        assertEquals(
            File(dataRoot, "shared_prefs/default.xml").canonicalFile,
            VirtualContextStorage.sharedPrefsPath(dataRoot.absolutePath, "")
        )
    }

    @Test
    fun `file and database paths allow Android compatible safe names`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "files/a..b").canonicalFile,
            VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, "a..b")
        )
        assertEquals(
            File(dataRoot, "databases/name with spaces").canonicalFile,
            VirtualContextStorage.databasePath(dataRoot.absolutePath, "name with spaces")
        )
        assertEquals(
            File(dataRoot, "shared_prefs/.hidden.xml").canonicalFile,
            VirtualContextStorage.sharedPrefsPath(dataRoot.absolutePath, ".hidden")
        )
    }

    @Test
    fun `file and database paths reject unsafe path segments`(@TempDir dataRoot: File) {
        val unsafeNames = listOf(
            "..",
            ".",
            "a/b.txt",
            "a\\b.txt",
            "bad\u0000name"
        )

        unsafeNames.forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, name)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VirtualContextStorage.databasePath(dataRoot.absolutePath, name)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VirtualContextStorage.sharedPrefsPath(dataRoot.absolutePath, name)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VirtualContextStorage.externalFilesDir(dataRoot.absolutePath, name)
            }
            assertThrows(IllegalArgumentException::class.java) {
                VirtualContextStorage.appDir(dataRoot.absolutePath, name)
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualContextStorage.databasePath(dataRoot.absolutePath, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualContextStorage.externalFilesDir(dataRoot.absolutePath, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualContextStorage.appDir(dataRoot.absolutePath, "")
        }
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
            File(dataRoot, "external_files").canonicalFile,
            VirtualContextStorage.externalFilesDir(dataRoot.absolutePath, null)
        )
        assertEquals(
            File(dataRoot, "external_files/images_private").canonicalFile,
            VirtualContextStorage.externalFilesDir(dataRoot.absolutePath, "images_private")
        )
    }

    @Test
    fun `app dir is scoped under instance data root`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "app_images_private").canonicalFile,
            VirtualContextStorage.appDir(dataRoot.absolutePath, "images_private")
        )
    }

    @Test
    fun `storage evidence records redirected path under data root`(@TempDir dataRoot: File) {
        val path = VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, "probe.txt")

        val evidence = VirtualContextStorage.evidence(
            dataRoot = dataRoot.absolutePath,
            operation = StorageOperation.FILE_STREAM_PATH,
            logicalName = "probe.txt",
            redirectedFile = path,
            nativeLibraryDir = File(dataRoot, "lib").absolutePath
        )

        assertEquals(StorageOperation.FILE_STREAM_PATH, evidence.operation)
        assertEquals("probe.txt", evidence.logicalName)
        assertEquals(path.canonicalPath, File(evidence.redirectedPath).canonicalPath)
        assertTrue(evidence.redirected)
        assertTrue(evidence.withinDataRoot)
        assertTrue(evidence.nativeLibraryRedirected)
    }
}
