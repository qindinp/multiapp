package com.multiapp.core.loader

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaExitSuppressionHookTest {

    @AfterEach
    fun tearDown() {
        JavaExitSuppressionHook.closeWindow()
    }

    @Test
    fun `bootstrap window swallows non-zero exit and skips original`() {
        JavaExitSuppressionHook.openWindow()
        var originalCalled = false
        val result = JavaExitSuppressionHook.interceptExit(status = 1, callOriginal = {
            originalCalled = true
            Unit
        })
        assertFalse(originalCalled, "suppressed exit must NOT call original")
        assertEquals(1, JavaExitSuppressionHook.suppressedExitCount())
    }

    @Test
    fun `bootstrap window passes through status zero real user exit`() {
        JavaExitSuppressionHook.openWindow()
        var originalCalled = false
        JavaExitSuppressionHook.interceptExit(status = 0, callOriginal = {
            originalCalled = true
            Unit
        })
        assertTrue(originalCalled, "status=0 exit must pass through even inside window")
        assertEquals(0, JavaExitSuppressionHook.suppressedExitCount())
    }

    @Test
    fun `outside window always passes through non-zero exit`() {
        JavaExitSuppressionHook.closeWindow()
        var originalCalled = false
        JavaExitSuppressionHook.interceptExit(status = 1, callOriginal = {
            originalCalled = true
            Unit
        })
        assertTrue(originalCalled, "exit outside bootstrap window must pass through")
    }

    @Test
    fun `shouldSuppress only when window open and status non-zero`() {
        JavaExitSuppressionHook.openWindow()
        assertTrue(JavaExitSuppressionHook.shouldSuppress(1))
        assertFalse(JavaExitSuppressionHook.shouldSuppress(0))

        JavaExitSuppressionHook.closeWindow()
        assertFalse(JavaExitSuppressionHook.shouldSuppress(1))
        assertFalse(JavaExitSuppressionHook.shouldSuppress(0))
    }

    @Test
    fun `install without LSPlant is graceful and keeps window closed`() {
        // JVM 单测无 native/LSPlant：install 必须优雅降级，不抛异常、不打开窗口。
        val result = JavaExitSuppressionHook.install(
            hookEngine = com.multiapp.core.hook.HookEngine.getInstance(),
            guestClassLoader = java.net.URLClassLoader(arrayOf(), ClassLoader.getSystemClassLoader())
        )
        assertFalse(result.anyHooked, "without LSPlant no hooks can be installed")
        assertFalse(JavaExitSuppressionHook.isWindowOpen(), "no hooks installed -> window must stay closed")
    }

    @Test
    fun `interceptExit forwards original result when passing through`() {
        JavaExitSuppressionHook.closeWindow()
        val result = JavaExitSuppressionHook.interceptExit(status = 1, callOriginal = { "original-return" })
        assertEquals("original-return", result)
    }
}
