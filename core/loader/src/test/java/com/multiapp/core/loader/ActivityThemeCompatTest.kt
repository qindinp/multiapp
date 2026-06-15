package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActivityThemeCompatTest {

    @Test
    fun `findFieldInHierarchy finds declared field`() {
        val field = ActivityThemeCompat.findFieldInHierarchy(TestTarget::class.java, "name")
        assertNotNull(field)
        assertEquals("name", field!!.name)
    }

    @Test
    fun `findFieldInHierarchy finds inherited field`() {
        val field = ActivityThemeCompat.findFieldInHierarchy(ChildTarget::class.java, "name")
        assertNotNull(field)
        assertEquals("name", field!!.name)
    }

    @Test
    fun `findFieldInHierarchy returns null for nonexistent field`() {
        val field = ActivityThemeCompat.findFieldInHierarchy(TestTarget::class.java, "nonexistent")
        assertNull(field)
    }

    @Test
    fun `replaceFieldIfPresent sets field value`() {
        val target = TestTarget()
        val result = ActivityThemeCompat.replaceFieldIfPresent(target, "name", "hello")
        assertTrue(result)
        assertEquals("hello", target.name)
    }

    @Test
    fun `replaceFieldIfPresent returns false for missing field`() {
        val target = TestTarget()
        val result = ActivityThemeCompat.replaceFieldIfPresent(target, "missing", "value")
        assertFalse(result)
    }

    @Test
    fun `replaceFieldIfPresent sets inherited field`() {
        val target = ChildTarget()
        val result = ActivityThemeCompat.replaceFieldIfPresent(target, "inherited", 99)
        assertTrue(result)
        assertEquals(99, target.inherited)
    }

    @Test
    fun `resolveActivityTheme returns activity theme from map`() {
        val themes = mapOf("com.example.MainActivity" to 0x7f0a0001)
        val result = ActivityThemeCompat.resolveActivityTheme(
            className = "com.example.MainActivity",
            activityThemes = themes,
            applicationThemeId = 0x7f0a0099,
            stubPackageName = null,
            guestPackageName = null
        )
        assertEquals(0x7f0a0001, result)
    }

    @Test
    fun `resolveActivityTheme prefers activity theme over application theme`() {
        val themes = mapOf("com.example.Specific" to 0x7f0a0005)
        val result = ActivityThemeCompat.resolveActivityTheme(
            className = "com.example.Specific",
            activityThemes = themes,
            applicationThemeId = 0x7f0a0099,
            stubPackageName = null,
            guestPackageName = null
        )
        assertEquals(0x7f0a0005, result)
    }

    @Test
    fun `resolveActivityTheme falls back to application theme`() {
        val result = ActivityThemeCompat.resolveActivityTheme(
            className = "com.example.SecondActivity",
            activityThemes = emptyMap(),
            applicationThemeId = 0x7f0a0099,
            stubPackageName = null,
            guestPackageName = null
        )
        assertEquals(0x7f0a0099, result)
    }

    // Test helper classes
    open class TestTarget {
        @JvmField var name: String = ""
    }

    class ChildTarget : TestTarget() {
        @JvmField var inherited: Int = 0
    }
}
