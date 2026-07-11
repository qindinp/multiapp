package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VirtualLauncherAppsServiceProxyTest {
    private val aliases = setOf("li.songe.gkd", "com.multiapp.instance.abc")

    @Test
    fun `register callback rewrites guest calling package`() {
        val callback = Any()
        val args = arrayOf<Any?>("li.songe.gkd", callback)

        val result = VirtualLauncherAppsServiceProxy.remapCallingPackageArgs(
            methodName = "addOnAppsChangedListener",
            args = args,
            sourcePackages = aliases,
            hostPackageName = "com.multiapp.app"
        )

        assertEquals("com.multiapp.app", result[0])
        assertSame(callback, result[1])
        assertEquals("li.songe.gkd", args[0])
    }

    @Test
    fun `target package is preserved when caller is already host`() {
        val args = arrayOf<Any?>("com.multiapp.app", "li.songe.gkd", null)

        val result = VirtualLauncherAppsServiceProxy.remapCallingPackageArgs(
            methodName = "getLauncherActivities",
            args = args,
            sourcePackages = aliases,
            hostPackageName = "com.multiapp.app"
        )

        assertSame(args, result)
        assertEquals("li.songe.gkd", result[1])
    }

    @Test
    fun `unknown launcher method does not rewrite package arguments`() {
        val args = arrayOf<Any?>("li.songe.gkd")

        val result = VirtualLauncherAppsServiceProxy.remapCallingPackageArgs(
            methodName = "shouldHideFromSuggestions",
            args = args,
            sourcePackages = aliases,
            hostPackageName = "com.multiapp.app"
        )

        assertSame(args, result)
    }
}
