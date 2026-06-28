package com.multiapp.core.loader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualDynamicReceiverRegistryTest {
    @Test
    fun `query returns receiver matching action category and scheme`() {
        val registry = VirtualDynamicReceiverRegistry()
        val receiver = NoopReceiver()
        registry.register(
            instanceId = "inst-001",
            receiver = receiver,
            filter = VirtualDynamicReceiverFilter(
                actions = setOf("com.test.ACTION"),
                categories = setOf("com.test.CATEGORY"),
                dataSchemes = setOf("demo")
            )
        )

        val result = registry.query("inst-001", intent("com.test.ACTION", setOf("com.test.CATEGORY"), "demo"))

        assertEquals(receiver, result.single().receiver)
    }

    @Test
    fun `query rejects mismatched category scheme and instance`() {
        val registry = VirtualDynamicReceiverRegistry()
        registry.register(
            instanceId = "inst-001",
            receiver = NoopReceiver(),
            filter = VirtualDynamicReceiverFilter(
                actions = setOf("com.test.ACTION"),
                categories = setOf("com.test.CATEGORY"),
                dataSchemes = setOf("demo")
            )
        )

        assertTrue(registry.query("inst-002", intent("com.test.ACTION", setOf("com.test.CATEGORY"), "demo")).isEmpty())
        assertTrue(registry.query("inst-001", intent("com.test.ACTION", setOf("other.CATEGORY"), "demo")).isEmpty())
        assertTrue(registry.query("inst-001", intent("com.test.ACTION", setOf("com.test.CATEGORY"), "other")).isEmpty())
    }

    @Test
    fun `unregister removes receiver`() {
        val registry = VirtualDynamicReceiverRegistry()
        val receiver = NoopReceiver()
        registry.register("inst-001", receiver, VirtualDynamicReceiverFilter(actions = setOf("com.test.ACTION")))

        registry.unregister(receiver)

        assertTrue(registry.query("inst-001", intent("com.test.ACTION")).isEmpty())
    }

    private fun intent(action: String, categories: Set<String> = emptySet(), scheme: String? = null): Intent {
        val uri = scheme?.let {
            mockk<Uri>(relaxed = true) {
                every { this@mockk.scheme } returns it
            }
        }
        return mockk(relaxed = true) {
            every { this@mockk.action } returns action
            every { this@mockk.categories } returns categories
            every { this@mockk.data } returns uri
        }
    }

    private class NoopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Unit
    }
}
