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

    @Test
    fun `remapNotificationPackageArgs rewrites origin and virtual packages to host`() {
        val args = arrayOf<Any?>(
            "com.example.app",
            "channel-id",
            "com.multiapp.instance.abc",
            "com.other.app"
        )

        val result = IntentRemapDiagnostics.remapNotificationPackageArgs(
            methodName = "getNotificationChannels",
            args = args,
            sourcePackages = setOf("com.example.app", "com.multiapp.instance.abc"),
            hostPackageName = "com.multiapp.app"
        )

        assertEquals("com.multiapp.app", result[0])
        assertEquals("channel-id", result[1])
        assertEquals("com.multiapp.app", result[2])
        assertEquals("com.other.app", result[3])
    }
}
