package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FilePermissionsTest {

    @TempDir
    lateinit var tempDir: File

    // -- ensureReadOnly --------------------------------------------------------

    @Test
    fun `ensureReadOnly makes file read-only`() {
        val file = File(tempDir, "test.txt")
        file.writeText("test")
        FilePermissions.ensureReadOnly(file)
        assertTrue(file.canRead())
        assertFalse(file.canWrite())
    }

    @Test
    fun `ensureReadOnly does not throw for missing file`() {
        val file = File(tempDir, "nonexistent.txt")
        // Should not throw -- method checks file.exists() first
        FilePermissions.ensureReadOnly(file)
        assertFalse(file.exists())
    }

    // -- ensureWritableDir -----------------------------------------------------

    @Test
    fun `ensureWritableDir creates directory if not exists`() {
        val dir = File(tempDir, "newdir")
        FilePermissions.ensureWritableDir(dir)
        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `ensureWritableDir makes existing directory writable`() {
        val dir = File(tempDir, "existingdir")
        dir.mkdirs()
        dir.setWritable(false)

        FilePermissions.ensureWritableDir(dir)

        assertTrue(dir.exists())
        assertTrue(dir.canWrite())
    }

    @Test
    fun `ensureWritableDir makes directory readable`() {
        val dir = File(tempDir, "readdir")
        FilePermissions.ensureWritableDir(dir)

        assertTrue(dir.canRead())
    }

    // -- ensureWritableFile ----------------------------------------------------

    @Test
    fun `ensureWritableFile makes read-only file writable`() {
        val file = File(tempDir, "writable-test.txt")
        file.writeText("content")
        file.setReadOnly()

        assertFalse(file.canWrite())

        FilePermissions.ensureWritableFile(file)

        assertTrue(file.canWrite())
    }

    @Test
    fun `ensureWritableFile does not throw for missing file`() {
        val file = File(tempDir, "missing.txt")
        // Should not throw
        FilePermissions.ensureWritableFile(file)
    }

    // -- ensureReadOnlyTree ----------------------------------------------------

    @Test
    fun `ensureReadOnlyTree makes all files read-only`() {
        val dir = File(tempDir, "ro-tree")
        dir.mkdirs()
        val file1 = File(dir, "a.txt")
        val file2 = File(dir, "b.txt")
        file1.writeText("a")
        file2.writeText("b")

        FilePermissions.ensureReadOnlyTree(dir)

        assertFalse(file1.canWrite())
        assertFalse(file2.canWrite())
    }

    // -- ensureWritableTree ----------------------------------------------------

    @Test
    fun `ensureWritableTree makes all files writable`() {
        val dir = File(tempDir, "rw-tree")
        dir.mkdirs()
        val file1 = File(dir, "a.txt")
        val file2 = File(dir, "b.txt")
        file1.writeText("a")
        file2.writeText("b")
        file1.setReadOnly()
        file2.setReadOnly()

        FilePermissions.ensureWritableTree(dir)

        assertTrue(file1.canWrite())
        assertTrue(file2.canWrite())
    }
}
