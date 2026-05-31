package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * StealthClassLoader 测试
 */
class StealthClassLoaderTest {

    @Test
    fun `toString does not contain stub or multiapp`() {
        val delegate = ClassLoader.getSystemClassLoader()
        val stealth = StealthClassLoader(delegate, "/data/app/com.test.app/base.apk")

        val str = stealth.toString()
        assertFalse(str.contains("stub", ignoreCase = true)) { "toString should not contain 'stub'" }
        assertFalse(str.contains("multiapp", ignoreCase = true)) { "toString should not contain 'multiapp'" }
        assertFalse(str.contains("clonestub", ignoreCase = true)) { "toString should not contain 'clonestub'" }
        assertTrue(str.contains("com.test.app")) { "toString should contain the fake path" }
    }

    @Test
    fun `toString returns DexPathList format`() {
        val delegate = ClassLoader.getSystemClassLoader()
        val stealth = StealthClassLoader(delegate, "/data/app/com.example/base.apk")

        val str = stealth.toString()
        assertTrue(str.startsWith("DexPathList")) { "Should start with DexPathList" }
        assertTrue(str.contains("nativeLibraryDirectories")) { "Should contain nativeLibraryDirectories" }
    }

    @Test
    fun `parent is systemClassLoader`() {
        val delegate = ClassLoader.getSystemClassLoader()
        val stealth = StealthClassLoader(delegate, "/test.apk")

        assertEquals(ClassLoader.getSystemClassLoader(), stealth.parent)
    }

    @Test
    fun `loadClass delegates to real classloader`() {
        val delegate = ClassLoader.getSystemClassLoader()
        val stealth = StealthClassLoader(delegate, "/test.apk")

        val stringClass = stealth.loadClass("java.lang.String")
        assertNotNull(stringClass)
        assertEquals(String::class.java, stringClass)
    }
}
