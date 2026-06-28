package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileBackedSharedPreferencesTest {

    @Test
    fun `commit persists values to the instance prefs file`(@TempDir tempDir: File) {
        val prefsFile = File(tempDir, "shared_prefs/settings.xml")
        val prefs = FileBackedSharedPreferences(prefsFile)

        val committed = prefs.edit()
            .putString("token", "abc")
            .putInt("launchCount", 3)
            .putBoolean("ready", true)
            .commit()

        assertTrue(committed)
        assertTrue(prefsFile.isFile)

        val reloaded = FileBackedSharedPreferences(prefsFile)
        assertEquals("abc", reloaded.getString("token", null))
        assertEquals(3, reloaded.getInt("launchCount", 0))
        assertTrue(reloaded.getBoolean("ready", false))
    }

    @Test
    fun `different files isolate values`(@TempDir tempDir: File) {
        val first = FileBackedSharedPreferences(File(tempDir, "inst-1/shared_prefs/settings.xml"))
        val second = FileBackedSharedPreferences(File(tempDir, "inst-2/shared_prefs/settings.xml"))

        first.edit().putString("token", "first").commit()
        second.edit().putString("token", "second").commit()

        assertEquals("first", FileBackedSharedPreferences(File(tempDir, "inst-1/shared_prefs/settings.xml")).getString("token", null))
        assertEquals("second", FileBackedSharedPreferences(File(tempDir, "inst-2/shared_prefs/settings.xml")).getString("token", null))
    }

    @Test
    fun `remove and clear update persisted values`(@TempDir tempDir: File) {
        val prefsFile = File(tempDir, "shared_prefs/settings.xml")
        val prefs = FileBackedSharedPreferences(prefsFile)
        prefs.edit()
            .putString("token", "abc")
            .putLong("timestamp", 42L)
            .commit()

        prefs.edit().remove("token").commit()
        assertFalse(FileBackedSharedPreferences(prefsFile).contains("token"))
        assertEquals(42L, FileBackedSharedPreferences(prefsFile).getLong("timestamp", 0L))

        prefs.edit().clear().commit()
        assertTrue(FileBackedSharedPreferences(prefsFile).all.isEmpty())
    }
}