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
    fun `file and database paths resolve safe relative paths`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "files/probe.txt").canonicalFile,
            VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, "probe.txt")
        )
        assertEquals(
            File(dataRoot, "files/nested/probe.txt").canonicalFile,
            VirtualContextStorage.fileStreamPath(dataRoot.absolutePath, "nested/probe.txt")
        )
        assertEquals(
            File(dataRoot, "databases/probe.db").canonicalFile,
            VirtualContextStorage.databasePath(dataRoot.absolutePath, "probe.db")
        )
        assertEquals(
            File(dataRoot, "databases/room/gkd.db").canonicalFile,
            VirtualContextStorage.databasePath(dataRoot.absolutePath, "room/gkd.db")
        )
        assertEquals(
            File(dataRoot, "shared_prefs/default.xml").canonicalFile,
            VirtualContextStorage.sharedPrefsPath(dataRoot.absolutePath, "")
        )
        assertEquals(
            File(dataRoot, "shared_prefs/settings/main.xml").canonicalFile,
            VirtualContextStorage.sharedPrefsPath(dataRoot.absolutePath, "settings/main")
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
    fun `database paths redirect Android private absolute paths`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "databases/gkd/main.db").canonicalFile,
            VirtualContextStorage.databasePath(
                dataRoot = dataRoot.absolutePath,
                name = "/data/user/0/com.example.app/databases/gkd/main.db",
                originPackageName = "com.example.app",
                virtualPackageName = "com.multiapp.instance.example"
            )
        )
        assertEquals(
            File(dataRoot, "databases/virtual.db").canonicalFile,
            VirtualContextStorage.databasePath(
                dataRoot = dataRoot.absolutePath,
                name = "/data/data/com.multiapp.instance.example/databases/virtual.db",
                originPackageName = "com.example.app",
                virtualPackageName = "com.multiapp.instance.example"
            )
        )
    }

    @Test
    fun `file and database paths reject unsafe relative paths`(@TempDir dataRoot: File) {
        val unsafeNames = listOf(
            "..",
            ".",
            "../escape.txt",
            "a/../escape.txt",
            "a//b.txt",
            "/absolute.txt",
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
        assertEquals(
            File(dataRoot, "external_files/Pictures/Screenshots").canonicalFile,
            VirtualContextStorage.externalFilesDir(dataRoot.absolutePath, "Pictures/Screenshots")
        )
    }

    @Test
    fun `app dir is scoped under instance data root`(@TempDir dataRoot: File) {
        assertEquals(
            File(dataRoot, "app_images_private").canonicalFile,
            VirtualContextStorage.appDir(dataRoot.absolutePath, "images_private")
        )
        assertEquals(
            File(dataRoot, "app_images/private").canonicalFile,
            VirtualContextStorage.appDir(dataRoot.absolutePath, "images/private")
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

    @Test
    fun `java absolute path diagnostics rewrite origin package paths under data root`(@TempDir dataRoot: File) {
        val diagnostics = VirtualStoragePathDiagnostics.javaAbsolutePathDiagnostics(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = dataRoot.absolutePath,
            caller = "test"
        )

        assertEquals(4, diagnostics.size)
        assertEquals(
            File(dataRoot, "files/pr10-data-data.txt").canonicalPath,
            File(diagnostics.single { it.probeName == "data-data" }.redirectedPath).canonicalPath
        )
        assertEquals(
            File(dataRoot, "files/pr10-data-user.txt").canonicalPath,
            File(diagnostics.single { it.probeName == "data-user" }.redirectedPath).canonicalPath
        )
        assertEquals(
            File(dataRoot, "external_files/pr10-sdcard.txt").canonicalPath,
            File(diagnostics.single { it.probeName == "sdcard" }.redirectedPath).canonicalPath
        )
        assertEquals(
            File(dataRoot, "external_files/pr10-storage-emulated.txt").canonicalPath,
            File(diagnostics.single { it.probeName == "storage-emulated" }.redirectedPath).canonicalPath
        )
        diagnostics.forEach { diagnostic ->
            assertEquals(VirtualStorageDiagnosticKind.JAVA_ABSOLUTE_PATH, diagnostic.kind)
            assertEquals(VirtualStorageDiagnosticStatus.REDIRECTED, diagnostic.status)
            assertEquals("inst-001", diagnostic.instanceId)
            assertTrue(diagnostic.withinDataRoot)
        }
    }

    @Test
    fun `java absolute path diagnostics leave sibling package paths unchanged`(@TempDir dataRoot: File) {
        val diagnostic = VirtualStoragePathDiagnostics.diagnoseJavaAbsolutePath(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = dataRoot.absolutePath,
            originalPath = "/data/data/com.example.app2/files/probe.txt",
            caller = "test"
        )

        assertEquals(VirtualStorageDiagnosticStatus.UNCHANGED, diagnostic.status)
        assertEquals("/data/data/com.example.app2/files/probe.txt", diagnostic.redirectedPath)
        assertEquals("PATH_NOT_MATCHED", diagnostic.reason)
        assertTrue(!diagnostic.withinDataRoot)
    }

    @Test
    fun `java absolute path diagnostics reject traversal outside data root`(@TempDir dataRoot: File) {
        val diagnostic = VirtualStoragePathDiagnostics.diagnoseJavaAbsolutePath(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = dataRoot.absolutePath,
            originalPath = "/data/data/com.example.app/../../escaped.txt",
            caller = "test"
        )

        assertEquals(VirtualStorageDiagnosticStatus.UNSUPPORTED, diagnostic.status)
        assertEquals("", diagnostic.redirectedPath)
        assertEquals("REDIRECTED_PATH_ESCAPES_DATA_ROOT", diagnostic.reason)
        assertTrue(diagnostic.candidateWithinDataRoot == false)
        assertTrue(!diagnostic.withinDataRoot)
    }

    @Test
    fun `native io diagnostics are explicit unsupported gaps with candidate redirects`(@TempDir dataRoot: File) {
        val diagnostics = VirtualStoragePathDiagnostics.nativeIoUnsupportedDiagnostics(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = dataRoot.absolutePath,
            caller = "test"
        )

        assertEquals(VirtualStoragePathDiagnostics.DEFAULT_NATIVE_IO_OPERATIONS, diagnostics.map { it.operation })
        diagnostics.forEach { diagnostic ->
            assertEquals(VirtualStorageDiagnosticKind.NATIVE_IO, diagnostic.kind)
            assertEquals(VirtualStorageDiagnosticStatus.UNSUPPORTED, diagnostic.status)
            assertEquals("", diagnostic.redirectedPath)
            assertEquals("NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE", diagnostic.reason)
            assertTrue(diagnostic.candidateWithinDataRoot == true)
        }
    }

    @Test
    fun `native io diagnostics can report missing device probe after hook install`(@TempDir dataRoot: File) {
        val diagnostics = VirtualStoragePathDiagnostics.nativeIoUnsupportedDiagnostics(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.001",
            dataRoot = dataRoot.absolutePath,
            caller = "test",
            reason = "NATIVE_IO_DEVICE_PROBE_NOT_IMPLEMENTED"
        )

        diagnostics.forEach { diagnostic ->
            assertEquals(VirtualStorageDiagnosticKind.NATIVE_IO, diagnostic.kind)
            assertEquals(VirtualStorageDiagnosticStatus.UNSUPPORTED, diagnostic.status)
            assertEquals("NATIVE_IO_DEVICE_PROBE_NOT_IMPLEMENTED", diagnostic.reason)
            assertTrue(diagnostic.candidateWithinDataRoot == true)
        }
    }
}
