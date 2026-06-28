package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class VirtualProcessRuntimeTest {

    @Test
    fun `bindApplication reuses successful runtime for same instance`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        var bootstrapCalls = 0
        val firstResult = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )

        val first = runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            firstResult
        }
        val second = runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader()
            )
        }

        assertEquals(1, bootstrapCalls)
        assertSame(first, second)
        assertSame(firstResult, runtime.get("inst-001")?.result)
    }

    @Test
    fun `bindApplication does not cache failed runtime`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        var bootstrapCalls = 0

        runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            hostedResult(
                instanceId = "inst-001",
                success = false,
                guestClassLoader = null
            )
        }
        val retry = runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader()
            )
        }

        assertEquals(2, bootstrapCalls)
        assertEquals(true, retry.success)
        assertSame(retry, runtime.get("inst-001")?.result)
    }

    @Test
    fun `bindApplication does not cache result without classloader`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })

        runtime.bindApplication("inst-001") {
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = null
            )
        }

        assertNull(runtime.get("inst-001"))
    }

    @Test
    fun `clear removes cached runtime`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        runtime.bindApplication("inst-001") {
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader()
            )
        }

        assertEquals(true, runtime.clear("inst-001"))
        assertNull(runtime.get("inst-001"))
    }

    private fun hostedResult(
        instanceId: String,
        success: Boolean,
        guestClassLoader: ClassLoader?
    ) = HostedBootstrapResult(
        instanceId = instanceId,
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.example",
        originApkPath = "/tmp/base.apk",
        dataRoot = "/tmp/$instanceId",
        guestClassLoader = guestClassLoader,
        guestApplication = null,
        installRecord = null,
        packageSnapshot = null,
        launcherActivityClassName = "com.example.app.MainActivity",
        stageResults = emptyList(),
        summary = emptyList<BootstrapResult>().toSummary(),
        success = success,
        diagnostics = null
    )
}
