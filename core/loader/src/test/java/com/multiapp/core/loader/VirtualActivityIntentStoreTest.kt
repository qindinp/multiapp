package com.multiapp.core.loader

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import java.util.ArrayDeque
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class VirtualActivityIntentStoreTest {

    @AfterTest
    fun tearDown() {
        VirtualActivityIntentStore.clearAll()
        VirtualActivityIntentStore.resetIntentCopierForTest()
    }

    @Test
    fun `remember stores defensive copy keyed by activity token`() {
        val source = intent(action = "com.test.SOURCE")
        val storedCopy = intent(action = "com.test.STORED")
        val findCopy = intent(action = "com.test.FOUND")
        val copies = ArrayDeque(listOf(storedCopy, findCopy))
        VirtualActivityIntentStore.setIntentCopierForTest { copies.removeFirst() }

        VirtualActivityIntentStore.remember("token-1", source)

        val stored = VirtualActivityIntentStore.find("token-1")

        assertEquals("com.test.FOUND", stored?.action)
        assertNotSame(source, stored)
        assertNotSame(storedCopy, stored)
    }

    @Test
    fun `find returns defensive copy`() {
        val source = intent(action = "com.test.SOURCE")
        val storedCopy = intent(action = "com.test.STORED")
        val firstFindCopy = intent(action = "com.test.FIRST")
        val secondFindCopy = intent(action = "com.test.SECOND")
        val copies = ArrayDeque(listOf(storedCopy, firstFindCopy, secondFindCopy))
        VirtualActivityIntentStore.setIntentCopierForTest { copies.removeFirst() }

        VirtualActivityIntentStore.remember("token-1", source)

        val first = VirtualActivityIntentStore.find("token-1")
        val second = VirtualActivityIntentStore.find("token-1")

        assertEquals("com.test.FIRST", first?.action)
        assertEquals("com.test.SECOND", second?.action)
        assertNotSame(first, second)
    }

    @Test
    fun `clear removes stored intent`() {
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        VirtualActivityIntentStore.remember("token-1", intent(action = "com.test.ACTION"))

        VirtualActivityIntentStore.clear("token-1")

        assertNull(VirtualActivityIntentStore.find("token-1"))
    }

    private fun intent(action: String): Intent = mockk(relaxed = true) {
        every { this@mockk.action } returns action
    }
}
