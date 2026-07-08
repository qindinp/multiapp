package com.multiapp.core.loader

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class VirtualInstrumentationApplicationContextTest {

    @BeforeTest
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @AfterTest
    fun tearDown() {
        VirtualPackageRegistry.global.clear()
        VirtualProcessRuntime.global.clearAll()
        unmockkStatic(Log::class)
    }

    @Test
    fun `newApplication wraps framework virtual context before guest attach`() {
        val snapshot = snapshot()
        VirtualPackageRegistry.global.register(snapshot)
        val hostContext = contextForPackage("com.multiapp.app")
        val frameworkContext = contextForPackage(snapshot.virtualPackageName, hostContext)
        val base = mockk<Instrumentation>()
        val application = mockk<Application>(relaxed = true)
        val contextSlot = slot<Context>()
        val classLoader = ClassLoader.getSystemClassLoader()
        every {
            base.newApplication(classLoader, "li.songe.gkd.App", capture(contextSlot))
        } returns application

        val created = VirtualInstrumentation(base).newApplication(
            classLoader,
            "li.songe.gkd.App",
            frameworkContext
        )

        assertSame(application, created)
        val guestContext = assertIs<VirtualContextWrapper>(contextSlot.captured)
        assertEquals(snapshot.originPackageName, guestContext.packageName)
        verify(exactly = 1) {
            base.newApplication(classLoader, "li.songe.gkd.App", any())
        }
    }

    @Test
    fun `newApplication keeps host context unchanged when no virtual snapshot matches`() {
        val hostContext = contextForPackage("com.multiapp.app")
        val base = mockk<Instrumentation>()
        val application = mockk<Application>(relaxed = true)
        val contextSlot = slot<Context>()
        val classLoader = ClassLoader.getSystemClassLoader()
        every {
            base.newApplication(classLoader, "com.multiapp.HostApp", capture(contextSlot))
        } returns application

        val created = VirtualInstrumentation(base).newApplication(
            classLoader,
            "com.multiapp.HostApp",
            hostContext
        )

        assertSame(application, created)
        assertSame(hostContext, contextSlot.captured)
    }

    private fun contextForPackage(packageName: String, applicationContext: Context? = null): Context {
        val info = ApplicationInfo().apply { this.packageName = packageName }
        return mockk(relaxed = true) {
            every { this@mockk.packageName } returns packageName
            every { this@mockk.applicationInfo } returns info
            every { this@mockk.applicationContext } returns (applicationContext ?: this@mockk)
        }
    }

    private fun snapshot(): VirtualPackageSnapshot =
        VirtualPackageSnapshot(
            instanceId = "inst-001",
            originPackageName = "li.songe.gkd",
            virtualPackageName = "com.multiapp.instance.31624cfbdccd",
            applicationLabel = "GKD",
            versionCode = 1,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "/data/user/0/com.multiapp.app/files/instances/inst-001/base.apk",
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            applicationClassName = "li.songe.gkd.App",
            launcherActivityName = "li.songe.gkd.MainActivity"
        )
}
