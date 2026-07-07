package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.toSummary
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedActivityRuntimeBinderTest {

    @Test
    fun `ensureBound skips missing instance id`() {
        val binder = HostedActivityRuntimeBinder()

        val result = binder.ensureBound(hostContext(), instanceId = null)

        assertTrue(result is HostedActivityRuntimeBindResult.NotRequested)
        val skipped = result as HostedActivityRuntimeBindResult.NotRequested
        assertEquals("missingActivityProxyInstanceId", skipped.detail)
    }

    @Test
    fun `ensureBound returns cached runtime without bootstrapping`() {
        val runtime = VirtualProcessRuntime()
        val reusable = hostedResult("inst-001")
        runtime.bindApplication("inst-001") { reusable }
        var bootstrapCalls = 0
        val binder = HostedActivityRuntimeBinder(
            runtime = runtime,
            bootstrapRunner = { _, _ ->
                bootstrapCalls += 1
                hostedResult("inst-001")
            }
        )

        val result = binder.ensureBound(hostContext(), "inst-001")

        assertTrue(result is HostedActivityRuntimeBindResult.Bound)
        val bound = result as HostedActivityRuntimeBindResult.Bound
        assertEquals("CACHED", bound.status)
        assertEquals("runtimeAlreadyReusable", bound.detail)
        assertSame(reusable, bound.result)
        assertEquals(0, bootstrapCalls)
    }

    @Test
    fun `ensureBound bootstraps runtime through process binder`() {
        val runtime = VirtualProcessRuntime()
        val bootstrapped = hostedResult("inst-001")
        var bootstrapCalls = 0
        val binder = HostedActivityRuntimeBinder(
            runtime = runtime,
            bootstrapRunner = { _, instanceId ->
                bootstrapCalls += 1
                assertEquals("inst-001", instanceId)
                bootstrapped
            }
        )

        val result = binder.ensureBound(hostContext(), "inst-001")

        assertTrue(result is HostedActivityRuntimeBindResult.Bound)
        val bound = result as HostedActivityRuntimeBindResult.Bound
        assertEquals("BOUND", bound.status)
        assertEquals("runtimeBoundForActivityProxy", bound.detail)
        assertSame(bootstrapped, bound.result)
        assertSame(bootstrapped, runtime.get("inst-001")?.result)
        assertEquals(1, bootstrapCalls)
    }

    @Test
    fun `ensureBound reports bootstrap failure`() {
        val binder = HostedActivityRuntimeBinder(
            runtime = VirtualProcessRuntime(),
            bootstrapRunner = { _, _ -> error("boom") }
        )

        val result = binder.ensureBound(hostContext(), "inst-001")

        assertTrue(result is HostedActivityRuntimeBindResult.Failed)
        val failed = result as HostedActivityRuntimeBindResult.Failed
        assertEquals("FAILED", failed.status)
        assertEquals("inst-001", failed.instanceId)
        assertEquals("runtimeBindFailed", failed.detail)
        assertEquals("java.lang.IllegalStateException", failed.errorClassName)
        assertEquals("boom", failed.errorMessage)
    }

    private fun hostContext(): Context = mockk(relaxed = true) {
        every { packageName } returns "com.multiapp.app"
        every { filesDir } returns File("build/tmp/hosted-activity-runtime-binder-test")
        every { applicationContext } returns this
    }

    private fun hostedResult(instanceId: String): HostedBootstrapResult = HostedBootstrapResult(
        instanceId = instanceId,
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.example",
        originApkPath = "/tmp/base.apk",
        dataRoot = "/tmp/$instanceId",
        guestClassLoader = ClassLoader.getSystemClassLoader(),
        guestApplication = null,
        installRecord = null,
        packageSnapshot = null,
        launcherActivityClassName = "com.example.app.MainActivity",
        stageResults = emptyList(),
        summary = emptyList<BootstrapResult>().toSummary(),
        success = true,
        diagnostics = null
    )
}
