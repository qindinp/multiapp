package com.multiapp.app.container

import android.content.Context
import android.net.Uri
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualProviderManager
import com.multiapp.core.loader.toSummary
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedProviderRuntimeBinderTest {

    @Test
    fun `ensureBound skips uri without provider proxy instance`() {
        val binder = HostedProviderRuntimeBinder()

        val result = binder.ensureBound(hostContext(), proxyUri(instanceId = null))

        assertTrue(result is HostedProviderRuntimeBindResult.NotRequested)
        val skipped = result as HostedProviderRuntimeBindResult.NotRequested
        assertEquals("missingProviderProxyInstanceId", skipped.detail)
    }

    @Test
    fun `ensureBound returns cached runtime without bootstrapping`() {
        val runtime = VirtualProcessRuntime()
        val reusable = hostedResult("inst-001")
        runtime.bindApplication("inst-001") { reusable }
        var bootstrapCalls = 0
        val binder = HostedProviderRuntimeBinder(
            runtime = runtime,
            bootstrapRunner = { _, _ ->
                bootstrapCalls += 1
                hostedResult("inst-001")
            }
        )

        val result = binder.ensureBound(hostContext(), proxyUri())

        assertTrue(result is HostedProviderRuntimeBindResult.Bound)
        val bound = result as HostedProviderRuntimeBindResult.Bound
        assertEquals("CACHED", bound.status)
        assertEquals("runtimeAlreadyReusable", bound.detail)
        assertEquals("com.example.app.probe", bound.guestAuthority)
        assertSame(reusable, bound.result)
        assertEquals(0, bootstrapCalls)
    }

    @Test
    fun `ensureBound bootstraps runtime through process binder`() {
        val runtime = VirtualProcessRuntime()
        val bootstrapped = hostedResult("inst-001")
        var bootstrapCalls = 0
        val binder = HostedProviderRuntimeBinder(
            runtime = runtime,
            bootstrapRunner = { _, instanceId ->
                bootstrapCalls += 1
                assertEquals("inst-001", instanceId)
                bootstrapped
            }
        )

        val result = binder.ensureBound(hostContext(), proxyUri())

        assertTrue(result is HostedProviderRuntimeBindResult.Bound)
        val bound = result as HostedProviderRuntimeBindResult.Bound
        assertEquals("BOUND", bound.status)
        assertEquals("runtimeBoundForProviderProxy", bound.detail)
        assertSame(bootstrapped, bound.result)
        assertSame(bootstrapped, runtime.get("inst-001")?.result)
        assertEquals(1, bootstrapCalls)
    }

    @Test
    fun `ensureBound reports bootstrap failure`() {
        val binder = HostedProviderRuntimeBinder(
            runtime = VirtualProcessRuntime(),
            bootstrapRunner = { _, _ -> error("boom") }
        )

        val result = binder.ensureBound(hostContext(), proxyUri())

        assertTrue(result is HostedProviderRuntimeBindResult.Failed)
        val failed = result as HostedProviderRuntimeBindResult.Failed
        assertEquals("FAILED", failed.status)
        assertEquals("inst-001", failed.instanceId)
        assertEquals("com.example.app.probe", failed.guestAuthority)
        assertEquals("runtimeBindFailed", failed.detail)
        assertEquals("java.lang.IllegalStateException", failed.errorClassName)
        assertEquals("boom", failed.errorMessage)
    }

    private fun hostContext(): Context = mockk(relaxed = true) {
        every { packageName } returns "com.multiapp.app"
        every { filesDir } returns File("build/tmp/hosted-provider-runtime-binder-test")
        every { applicationContext } returns this
    }

    private fun proxyUri(
        instanceId: String? = "inst-001",
        guestAuthority: String? = "com.example.app.probe"
    ): Uri = mockk(relaxed = true) {
        every { getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID) } returns instanceId
        every { getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY) } returns guestAuthority
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
