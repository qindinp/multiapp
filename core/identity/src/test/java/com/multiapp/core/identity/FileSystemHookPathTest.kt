package com.multiapp.core.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileSystemHookPathTest {

    // Test data
    private val originalPkg = "com.example.original.app"
    private val stubPkg = "com.example.stub.app"

    @Test
    fun `rewritePath replaces data_user_0 path correctly`() {
        val inputPath = "/data/user/0/$originalPkg/files/test.txt"
        val expectedPath = "/data/user/0/$stubPkg/files/test.txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces data_user_10 path correctly`() {
        val inputPath = "/data/user/10/$originalPkg/cache/data.bin"
        val expectedPath = "/data/user/10/$stubPkg/cache/data.bin"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces data_user_de_0 path correctly`() {
        val inputPath = "/data/user_de/0/$originalPkg/files/test.txt"
        val expectedPath = "/data/user_de/0/$stubPkg/files/test.txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces data_user_de_10 path correctly`() {
        val inputPath = "/data/user_de/10/$originalPkg/cache/data.bin"
        val expectedPath = "/data/user_de/10/$stubPkg/cache/data.bin"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces data_data path correctly`() {
        val inputPath = "/data/data/$originalPkg/shared_prefs/config.xml"
        val expectedPath = "/data/data/$stubPkg/shared_prefs/config.xml"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces storage_emulated_0 path correctly`() {
        val inputPath = "/storage/emulated/0/Android/data/$originalPkg/files/image.jpg"
        val expectedPath = "/storage/emulated/0/Android/data/$stubPkg/files/image.jpg"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces storage_emulated_0_obb path correctly`() {
        val inputPath = "/storage/emulated/0/Android/obb/$originalPkg/main.obb"
        val expectedPath = "/storage/emulated/0/Android/obb/$stubPkg/main.obb"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces storage_emulated_0_media path correctly`() {
        val inputPath = "/storage/emulated/0/Android/media/$originalPkg/video.mp4"
        val expectedPath = "/storage/emulated/0/Android/media/$stubPkg/video.mp4"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces sdcard_data path correctly`() {
        val inputPath = "/sdcard/Android/data/$originalPkg/files/document.pdf"
        val expectedPath = "/sdcard/Android/data/$stubPkg/files/document.pdf"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces sdcard_obb path correctly`() {
        val inputPath = "/sdcard/Android/obb/$originalPkg/patch.obb"
        val expectedPath = "/sdcard/Android/obb/$stubPkg/patch.obb"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces sdcard_media path correctly`() {
        val inputPath = "/sdcard/Android/media/$originalPkg/audio.mp3"
        val expectedPath = "/sdcard/Android/media/$stubPkg/audio.mp3"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath replaces mnt_sdcard_data path correctly`() {
        val inputPath = "/mnt/sdcard/Android/data/$originalPkg/files/download.zip"
        val expectedPath = "/mnt/sdcard/Android/data/$stubPkg/files/download.zip"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath does not modify path without original package name`() {
        val inputPath = "/data/user/0/com.other.app/files/test.txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(inputPath, result)
    }

    @Test
    fun `rewritePath does not modify path with similar but different package name`() {
        val inputPath = "/data/user/0/${originalPkg}.extra/files/test.txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(inputPath, result)
    }

    @Test
    fun `rewritePath handles multiple occurrences of package name in path`() {
        val inputPath = "/data/user/0/$originalPkg/files/$originalPkg/data.bin"
        val expectedPath = "/data/user/0/$stubPkg/files/$stubPkg/data.bin"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath handles empty path`() {
        val inputPath = ""
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(inputPath, result)
    }

    @Test
    fun `rewritePath handles path with only package name`() {
        val inputPath = originalPkg
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(inputPath, result)
    }

    @Test
    fun `rewritePath handles path with package name at different positions`() {
        val inputPath = "/some/path/$originalPkg/other/path"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(inputPath, result)
    }

    @Test
    fun `rewritePath handles Windows style paths`() {
        // This test verifies that the function doesn't break on non-Unix paths
        val inputPath = "C:\\Users\\test\\$originalPkg\\files\\test.txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(inputPath, result)
    }

    @Test
    fun `rewritePath handles relative paths`() {
        val inputPath = "./$originalPkg/files/test.txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(inputPath, result)
    }

    @Test
    fun `rewritePath handles path with special characters`() {
        val inputPath = "/data/user/0/$originalPkg/files/test file (1).txt"
        val expectedPath = "/data/user/0/$stubPkg/files/test file (1).txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath handles path with unicode characters`() {
        val inputPath = "/data/user/0/$originalPkg/files/测试文件.txt"
        val expectedPath = "/data/user/0/$stubPkg/files/测试文件.txt"
        
        val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

    @Test
    fun `rewritePath handles long path`() {
        val longPath = "/data/user/0/$originalPkg/" + "a".repeat(1000) + "/test.txt"
        val expectedPath = "/data/user/0/$stubPkg/" + "a".repeat(1000) + "/test.txt"
        
        val result = FileSystemHook.rewritePath(longPath, originalPkg, stubPkg)
        
        assertEquals(expectedPath, result)
    }

}