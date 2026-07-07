package com.multiapp.app.container

import android.content.Context
import android.content.Intent
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualServiceStartRequest
import com.multiapp.core.loader.toSummary
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedServiceRuntimeBinderTest {

    @Test
    fun `ensureBound skips invalid proxy intent`() {
        val binder = HostedServiceRuntimeBinder(
            requestDecoder = { _, _ -> null }
        )
        val context = hostContext()

        val result = binder.ensureBound(context, proxyIntent())

        assertTrue(result is HostedServiceRuntimeBindResult.NotRequested)
        val skipped = result as HostedServiceRuntimeBindResult.NotRequested
        assertEquals("missingServiceProxyRequest", skipped.detail)
    }

    @Test
    fun `ensureBound returns cached runtime without bootstrapping`() {
        val runtime = VirtualProcessRuntime()
        val reusable = hostedResult("inst-001")
        runtime.bindApplication("inst-001") { reusable }
        var bootstrapCalls = 0
        val cachedProxyIntent = proxyIntent()
        val binder = HostedServiceRuntimeBinder(
            runtime = runtime,
            requestDecoder = { hostPackageName, intent ->
                assertEquals("com.multiapp.app", hostPackageName)
                assertSame(cachedProxyIntent, intent)
                serviceStartRequest()
            },
            bootstrapRunner = { _, _ ->
                bootstrapCalls += 1
                hostedResult("inst-001")
            }
        )

        val result = binder.ensureBound(hostContext(), cachedProxyIntent)

        assertTrue(result is HostedServiceRuntimeBindResult.Bound)
        val bound = result as HostedServiceRuntimeBindResult.Bound
        assertEquals("CACHED", bound.status)
        assertEquals("runtimeAlreadyReusable", bound.detail)
        assertSame(reusable, bound.result)
        assertEquals(0, bootstrapCalls)
    }

    @Test
    fun `ensureBound bootstraps runtime through single-flight binder`() {
        val runtime = VirtualProcessRuntime()
        var bootstrapCalls = 0
        val bootstrapped = hostedResult("inst-001")
        val coldProxyIntent = proxyIntent()
        val binder = HostedServiceRuntimeBinder(
            runtime = runtime,
            requestDecoder = { hostPackageName, intent ->
                assertEquals("com.multiapp.app", hostPackageName)
                assertSame(coldProxyIntent, intent)
                serviceStartRequest()
            },
            bootstrapRunner = { _, instanceId ->
                bootstrapCalls += 1
                assertEquals("inst-001", instanceId)
                bootstrapped
            }
        )

        val result = binder.ensureBound(hostContext(), coldProxyIntent)

        assertTrue(result is HostedServiceRuntimeBindResult.Bound)
        val bound = result as HostedServiceRuntimeBindResult.Bound
        assertEquals("BOUND", bound.status)
        assertEquals("runtimeBoundForServiceProxy", bound.detail)
        assertSame(bootstrapped, bound.result)
        assertSame(bootstrapped, runtime.get("inst-001")?.result)
        assertEquals(1, bootstrapCalls)
    }

    @Test
    fun `ensureBound reports bootstrap failure`() {
        val failingProxyIntent = proxyIntent()
        val binder = HostedServiceRuntimeBinder(
            runtime = VirtualProcessRuntime(),
            requestDecoder = { hostPackageName, intent ->
                assertEquals("com.multiapp.app", hostPackageName)
                assertSame(failingProxyIntent, intent)
                serviceStartRequest()
            },
            bootstrapRunner = { _, _ -> error("boom") }
        )

        val result = binder.ensureBound(hostContext(), failingProxyIntent)

        assertTrue(result is HostedServiceRuntimeBindResult.Failed)
        val failed = result as HostedServiceRuntimeBindResult.Failed
        assertEquals("FAILED", failed.status)
        assertEquals("inst-001", failed.instanceId)
        assertEquals("runtimeBindFailed", failed.detail)
        assertEquals("java.lang.IllegalStateException", failed.errorClassName)
        assertEquals("boom", failed.errorMessage)
    }

    private fun hostContext(): Context = mockk(relaxed = true) {
        every { packageName } returns "com.multiapp.app"
        every { filesDir } returns File("build/tmp/hosted-service-runtime-binder-test")
        every { applicationContext } returns this
    }

    private fun proxyIntent(): Intent = mockk(relaxed = true)

    private fun serviceStartRequest(): VirtualServiceStartRequest = VirtualServiceStartRequest(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        guestServiceClassName = "com.example.app.SyncService",
        sourceIntent = proxyIntent(),
        reason = "explicit",
        foreground = false
    )

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
