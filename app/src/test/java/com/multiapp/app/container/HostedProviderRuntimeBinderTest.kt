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
            bootstrapRunner = { _, _, _ ->
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
            bootstrapRunner = { _, instanceId, processSlot ->
                bootstrapCalls += 1
                assertEquals("inst-001", instanceId)
                assertEquals(null, processSlot)
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
            bootstrapRunner = { _, _, _ -> error("boom") }
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

    @Test
    fun `ensureBound passes provider route process slot into bootstrap`() {
        val runtime = VirtualProcessRuntime()
        val processSlot = "com.multiapp.app:v3"
        val bootstrapped = hostedResult("inst-001", processSlot = processSlot)
        var capturedProcessSlot: String? = null
        val binder = HostedProviderRuntimeBinder(
            runtime = runtime,
            bootstrapRunner = { _, instanceId, requestedProcessSlot ->
                assertEquals("inst-001", instanceId)
                capturedProcessSlot = requestedProcessSlot
                bootstrapped
            }
        )

        val result = binder.ensureBound(hostContext(), proxyUri(processSlot = processSlot))

        val bound = result as HostedProviderRuntimeBindResult.Bound
        assertEquals(processSlot, capturedProcessSlot)
        assertEquals(processSlot, bound.processSlot)
        assertEquals(processSlot, runtime.get("inst-001")?.result?.processSlot)
    }

    @Test
    fun `ensureBound rejects cached runtime from another process slot`() {
        val runtime = VirtualProcessRuntime()
        runtime.bindApplication("inst-001") {
            hostedResult("inst-001", processSlot = "com.multiapp.app:v1")
        }
        val binder = HostedProviderRuntimeBinder(
            runtime = runtime,
            bootstrapRunner = { _, _, _ -> error("should not bootstrap") }
        )

        val result = binder.ensureBound(hostContext(), proxyUri(processSlot = "com.multiapp.app:v3"))

        val failed = result as HostedProviderRuntimeBindResult.Failed
        assertEquals("runtimeProcessSlotMismatch", failed.detail)
        assertEquals("com.multiapp.app:v3", failed.processSlot)
    }

    private fun hostContext(): Context = mockk(relaxed = true) {
        every { packageName } returns "com.multiapp.app"
        every { filesDir } returns File("build/tmp/hosted-provider-runtime-binder-test")
        every { applicationContext } returns this
    }

    private fun proxyUri(
        instanceId: String? = "inst-001",
        guestAuthority: String? = "com.example.app.probe",
        processSlot: String? = null
    ): Uri = mockk(relaxed = true) {
        every { getQueryParameter(VirtualProviderManager.PROXY_INSTANCE_ID) } returns instanceId
        every { getQueryParameter(VirtualProviderManager.PROXY_GUEST_AUTHORITY) } returns guestAuthority
        every { getQueryParameter(VirtualProviderManager.PROXY_PROCESS_SLOT) } returns processSlot
    }

    private fun hostedResult(
        instanceId: String,
        processSlot: String? = null
    ): HostedBootstrapResult = HostedBootstrapResult(
        instanceId = instanceId,
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.example",
        processSlot = processSlot,
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
