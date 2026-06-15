package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IntentRemapDiagnosticsTest {

    @Test
    fun `remapNotificationPackageArgs returns array with same content when no match`() {
        val args = arrayOf<Any?>("com.other.app", 123)
        val result = IntentRemapDiagnostics.remapNotificationPackageArgs(
            "cancelAll", args, "com.example.app", "com.stub.app"
        )
        assertArrayEquals(args, result)
    }

    @Test
    fun `remapNotificationPackageArgs with empty args returns empty array`() {
        val args = emptyArray<Any?>()
        val result = IntentRemapDiagnostics.remapNotificationPackageArgs(
            "empty", args, "com.example.app", "com.stub.app"
        )
        assertEquals(0, result.size)
    }

    @Test
    fun `remapNotificationPackageArgs no match preserves content`() {
        val args = arrayOf<Any?>("com.other.app", 123)
        val result = IntentRemapDiagnostics.remapNotificationPackageArgs(
            "cancelAll", args, "com.example.app", "com.stub.app"
        )
        assertEquals("com.other.app", result[0])
        assertEquals(123, result[1])
    }
}
