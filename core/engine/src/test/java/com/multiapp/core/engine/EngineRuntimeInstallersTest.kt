package com.multiapp.core.engine

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `process host context is frozen as a separate host package Context`() {
        val frozenHostContext = mockk<Context>()
        val hostApplication = mockk<Context>() {
            every {
                createPackageContext("com.multiapp.app", Context.CONTEXT_IGNORE_SECURITY)
            } returns frozenHostContext
        }
        val hostEntryContext = mockk<Context>() {
            every { applicationContext } returns hostApplication
            every { packageName } returns "com.multiapp.app"
        }

        EngineRuntimeInstallers.rememberProcessHostContextForTest(hostEntryContext)

        assertSame(
            frozenHostContext,
            EngineRuntimeInstallers.resolveProcessHostContextForTest(hostEntryContext)
        )
    }

    @Test
    fun `guest context cannot replace the process host context after startup`() {
        val hostApplication = mockk<Context>()
        val hostEntryContext = mockk<Context> {
            every { applicationContext } returns hostApplication
        }
        val guestApplication = mockk<Context>()
        val guestEntryContext = mockk<Context> {
            every { applicationContext } returns guestApplication
        }

        EngineRuntimeInstallers.rememberProcessHostContextForTest(hostEntryContext)
        EngineRuntimeInstallers.rememberProcessHostContextForTest(guestEntryContext)

        assertSame(
            hostApplication,
            EngineRuntimeInstallers.resolveProcessHostContextForTest(guestEntryContext)
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

    @Test
    fun `Activity coordinator is wired with frozen host context`() {
        val source = File(
            repoRoot(),
            "core/engine/src/main/java/com/multiapp/core/engine/EngineRuntimeInstallers.kt"
        ).readText()

        assertTrue(
            source.contains("hostContext = resolveProcessHostContext(request.hostContext)"),
            "Engine Activity remap must not derive proxy ComponentName from a guest Context"
        )
    }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile?.absoluteFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }
}
