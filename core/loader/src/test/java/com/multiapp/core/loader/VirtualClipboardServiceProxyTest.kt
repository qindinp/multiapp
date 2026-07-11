package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VirtualClipboardServiceProxyTest {
    private val aliases = setOf("li.songe.gkd", "com.multiapp.instance.abc")

    @Test
    fun `set primary clip rewrites caller and clears guest attribution`() {
        val clipData = Any()
        val args = arrayOf<Any?>(clipData, "li.songe.gkd", "guest-attribution", 0, 0)

        val result = VirtualClipboardServiceProxy.remapCallingPackageArgs(
            methodName = "setPrimaryClip",
            args = args,
            sourcePackages = aliases,
            hostPackageName = "com.multiapp.app"
        )

        assertSame(clipData, result[0])
        assertEquals("com.multiapp.app", result[1])
        assertEquals(null, result[2])
        assertEquals("li.songe.gkd", args[1])
        assertEquals("guest-attribution", args[2])
    }

    @Test
    fun `listener method rewrites its second calling package argument`() {
        val listener = Any()
        val args = arrayOf<Any?>(listener, "com.multiapp.instance.abc", null, 0, 0)

        val result = VirtualClipboardServiceProxy.remapCallingPackageArgs(
            methodName = "addPrimaryClipChangedListener",
            args = args,
            sourcePackages = aliases,
            hostPackageName = "com.multiapp.app"
        )

        assertSame(listener, result[0])
        assertEquals("com.multiapp.app", result[1])
    }

    @Test
    fun `set primary clip as package preserves source package semantics`() {
        val args = arrayOf<Any?>(
            Any(),
            "li.songe.gkd",
            null,
            0,
            0,
            "com.target.source"
        )

        val result = VirtualClipboardServiceProxy.remapCallingPackageArgs(
            methodName = "setPrimaryClipAsPackage",
            args = args,
            sourcePackages = aliases,
            hostPackageName = "com.multiapp.app"
        )

        assertEquals("com.multiapp.app", result[1])
        assertEquals("com.target.source", result[5])
    }

    @Test
    fun `api 30 caller rewrite preserves user id without attribution slot`() {
        val args = arrayOf<Any?>(Any(), "li.songe.gkd", 10)

        val result = VirtualClipboardServiceProxy.remapCallingPackageArgs(
            methodName = "setPrimaryClip",
            args = args,
            sourcePackages = aliases,
            hostPackageName = "com.multiapp.app"
        )

        assertEquals("com.multiapp.app", result[1])
        assertEquals(10, result[2])
    }

    @Test
    fun `host and unknown method arguments remain unchanged`() {
        val hostArgs = arrayOf<Any?>("com.multiapp.app", null, 0, 0)
        val unknownArgs = arrayOf<Any?>("li.songe.gkd")

        assertSame(
            hostArgs,
            VirtualClipboardServiceProxy.remapCallingPackageArgs(
                methodName = "getPrimaryClip",
                args = hostArgs,
                sourcePackages = aliases,
                hostPackageName = "com.multiapp.app"
            )
        )
        assertSame(
            unknownArgs,
            VirtualClipboardServiceProxy.remapCallingPackageArgs(
                methodName = "vendorClipboardExtension",
                args = unknownArgs,
                sourcePackages = aliases,
                hostPackageName = "com.multiapp.app"
            )
        )
    }
}
