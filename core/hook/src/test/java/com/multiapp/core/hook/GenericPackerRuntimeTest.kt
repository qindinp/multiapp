package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenericPackerRuntimeTest {

    @Test
    fun `default dispatcher registers jiagu then generic fallback`() {
        val names = PackerRuntimeDispatcher.getInstance().getRegisteredRuntimeNames()
        assertTrue(names.contains("Jiagu360"), "expected Jiagu360 in $names")
        assertTrue(names.contains("GenericPacker"), "expected GenericPacker in $names")
        assertEquals("Jiagu360", names.first())
        assertEquals("GenericPacker", names.last())
    }

    @Test
    fun `detect returns false for unknown apk path`() {
        val runtime = GenericPackerRuntime()
        assertFalse(runtime.detect(File("."), null))
    }

    @Test
    fun `detect returns false for missing apk`() {
        val runtime = GenericPackerRuntime()
        assertFalse(runtime.detect(File("."), "/no/such/apk.apk"))
    }
}
