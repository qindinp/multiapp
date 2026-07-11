package com.multiapp.core.loader

import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationThreadRunnerTest {
    @Test
    fun `main looper caller executes inline`() {
        val mainLooper = mockk<Looper>()
        val handlerFactory = mockk<(Looper) -> Handler>()
        val runner = MainLooperApplicationThreadRunner(
            mainLooperProvider = { mainLooper },
            currentLooperProvider = { mainLooper },
            handlerFactory = handlerFactory
        )

        assertEquals("inline", runner.run { "inline" })
        verify(exactly = 0) { handlerFactory.invoke(any()) }
    }

    @Test
    fun `background caller posts work and returns its value`() {
        val mainLooper = mockk<Looper>()
        val handler = mockk<Handler>()
        every { handler.post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
        val runner = MainLooperApplicationThreadRunner(
            mainLooperProvider = { mainLooper },
            currentLooperProvider = { null },
            handlerFactory = { handler }
        )

        assertEquals(42, runner.run { 42 })
        verify(exactly = 1) { handler.post(any()) }
    }

    @Test
    fun `failed main looper post fails before waiting`() {
        val mainLooper = mockk<Looper>()
        val handler = mockk<Handler>()
        every { handler.post(any()) } returns false
        val runner = MainLooperApplicationThreadRunner(
            mainLooperProvider = { mainLooper },
            currentLooperProvider = { null },
            handlerFactory = { handler }
        )

        val error = assertFailsWith<IllegalStateException> {
            runner.run { "not-run" }
        }

        assertEquals(
            "Unable to dispatch guest Application binding to the process main looper",
            error.message
        )
    }

    @Test
    fun `background execution exposes original failure`() {
        val mainLooper = mockk<Looper>()
        val handler = mockk<Handler>()
        every { handler.post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
        val runner = MainLooperApplicationThreadRunner(
            mainLooperProvider = { mainLooper },
            currentLooperProvider = { null },
            handlerFactory = { handler }
        )

        val error = assertFailsWith<IllegalArgumentException> {
            runner.run<Unit> { throw IllegalArgumentException("guest failure") }
        }

        assertEquals("guest failure", error.message)
    }
}
