package com.multiapp.core.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunSafeTest {

    @Test
    fun `runSafe returns result when block succeeds`() {
        val result = runSafe { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `runSafe returns null when block throws`() {
        val result: String? = runSafe { throw IllegalStateException("boom") }
        assertNull(result)
    }

    @Test
    fun `runSafe returns string result`() {
        val result = runSafe { "hello" }
        assertEquals("hello", result)
    }

    @Test
    fun `runSafe returns null for arithmetic exception`() {
        val result: Int? = runSafe { 1 / 0 }
        assertNull(result)
    }

    @Test
    fun `runSafe with custom tag returns result`() {
        val result = runSafe(tag = "CustomTag") { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun `runSafeOr returns result when block succeeds`() {
        val result = runSafeOr(default = -1) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `runSafeOr returns default when block throws`() {
        val result = runSafeOr(default = -1) { throw IllegalStateException("boom") }
        assertEquals(-1, result)
    }

    @Test
    fun `runSafeOr returns default string on exception`() {
        val result = runSafeOr(default = "fallback") { throw RuntimeException() }
        assertEquals("fallback", result)
    }

    @Test
    fun `runSafeOr returns default null when block throws and default is null`() {
        val result: String? = runSafeOr(default = null) { throw RuntimeException() }
        assertNull(result)
    }

    @Test
    fun `runSafeOr with custom tag returns result`() {
        val result = runSafeOr(tag = "TestTag", default = "def") { "value" }
        assertEquals("value", result)
    }

    @Test
    fun `runSafeOr returns empty list default on exception`() {
        val defaultList = listOf("default")
        val result = runSafeOr(default = defaultList) { throw IllegalStateException() }
        assertEquals(defaultList, result)
        assertEquals(1, result.size)
    }
}
