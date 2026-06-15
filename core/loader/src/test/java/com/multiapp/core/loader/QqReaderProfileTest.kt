package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QqReaderProfileTest {

    @Test
    fun `isQqReaderPackage returns true for exact match`() {
        assertTrue(QqReaderProfile.isQqReaderPackage("com.qq.reader"))
    }

    @Test
    fun `isQqReaderPackage returns true for sub-package`() {
        assertTrue(QqReaderProfile.isQqReaderPackage("com.qq.reader.debug"))
    }

    @Test
    fun `isQqReaderPackage returns false for unrelated package`() {
        assertFalse(QqReaderProfile.isQqReaderPackage("com.example.app"))
    }

    @Test
    fun `isQqReaderPackage returns false for null`() {
        assertFalse(QqReaderProfile.isQqReaderPackage(null))
    }

    @Test
    fun `isQqReaderPackage returns false for empty string`() {
        assertFalse(QqReaderProfile.isQqReaderPackage(""))
    }

    @Test
    fun `isQqReaderPackage returns false for similar but different package`() {
        assertFalse(QqReaderProfile.isQqReaderPackage("com.qq.read"))
        assertFalse(QqReaderProfile.isQqReaderPackage("com.qq.readerx"))
        assertFalse(QqReaderProfile.isQqReaderPackage("org.qq.reader"))
    }

    @Test
    fun `HookResult default has all false`() {
        val result = QqReaderProfile.HookResult()
        assertFalse(result.shortcutHooked)
        assertFalse(result.pushHooked)
        assertFalse(result.pangleHooked)
        assertFalse(result.fileDiagInstalled)
        assertFalse(result.providerDiagInstalled)
        assertFalse(result.protocolDiagInstalled)
        assertFalse(result.eqctCompatInstalled)
        assertFalse(result.anyInstalled)
    }

    @Test
    fun `HookResult anyInstalled is true when at least one hook is set`() {
        val result = QqReaderProfile.HookResult(shortcutHooked = true)
        assertTrue(result.anyInstalled)
    }

    @Test
    fun `HookResult anyInstalled is true when diag is set`() {
        val result = QqReaderProfile.HookResult(eqctCompatInstalled = true)
        assertTrue(result.anyInstalled)
    }

    @Test
    fun `HookResult copy preserves other fields`() {
        val original = QqReaderProfile.HookResult(
            shortcutHooked = true,
            pushHooked = true,
            pangleHooked = false
        )
        val copied = original.copy(pangleHooked = true)
        assertTrue(copied.shortcutHooked)
        assertTrue(copied.pushHooked)
        assertTrue(copied.pangleHooked)
    }

    @Test
    fun `HookResult equality works correctly`() {
        val a = QqReaderProfile.HookResult(shortcutHooked = true, pushHooked = false)
        val b = QqReaderProfile.HookResult(shortcutHooked = true, pushHooked = false)
        val c = QqReaderProfile.HookResult(shortcutHooked = false, pushHooked = true)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `DiagResult default has all false`() {
        val result = QqReaderProfile.DiagResult()
        assertFalse(result.fileDiag)
        assertFalse(result.providerDiag)
        assertFalse(result.protocolDiag)
        assertFalse(result.eqctCompat)
    }
}
