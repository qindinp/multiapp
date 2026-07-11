package com.multiapp.core.engine

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class EngineRuntimeInstallersTest {

    @AfterTest
    fun tearDown() {
        EngineRuntimeInstallers.clearProcessHostContextForTest()
    }

    @Test
    fun `registered process host context wins while guest Application is attaching`() {
        val hostApplication = mockk<Context>()
        val hostEntryContext = mockk<Context>() {
            every { applicationContext } returns hostApplication
        }
        val attachingGuestContext = mockk<Context>() {
            every { applicationContext } returns null
        }

        EngineRuntimeInstallers.rememberProcessHostContextForTest(hostEntryContext)

        assertSame(
            hostApplication,
            EngineRuntimeInstallers.resolveProcessHostContextForTest(attachingGuestContext)
        )
    }

    @Test
    fun `missing process host context fails explicitly instead of dereferencing null`() {
        val attachingGuestContext = mockk<Context>() {
            every { applicationContext } returns null
        }

        assertFailsWith<IllegalStateException> {
            EngineRuntimeInstallers.resolveProcessHostContextForTest(attachingGuestContext)
        }
    }
}
