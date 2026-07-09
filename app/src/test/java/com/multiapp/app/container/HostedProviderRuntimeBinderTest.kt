package com.multiapp.app.container

import android.content.Context
import android.net.Uri
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.HostedRuntimeBindOutcome
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.toSummary
import com.multiapp.core.model.engine.ProviderRouteContract
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
        val reusable = hostedResult("inst-001")
        val runtime = FakeHostedRuntimeEngine(reusableResults = mutableMapOf("inst-001" to reusable))
        val binder = HostedProviderRuntimeBinder(
            runtimeEngineFactory = { runtime }
        )

        val result = binder.ensureBound(hostContext(), proxyUri())

        assertTrue(result is HostedProviderRuntimeBindResult.Bound)
        val bound = result as HostedProviderRuntimeBindResult.Bound
        assertEquals("CACHED", bound.status)
        assertEquals("runtimeAlreadyReusable", bound.detail)
        assertEquals("com.example.app.probe", bound.guestAuthority)
        assertSame(reusable, bound.result)
        assertEquals(0, runtime.bindCalls)
    }

    @Test
    fun `ensureBound bootstraps runtime through process binder`() {
        val bootstrapped = hostedResult("inst-001")
        val runtime = FakeHostedRuntimeEngine(bindResult = bootstrapped)
        val binder = HostedProviderRuntimeBinder(
            runtimeEngineFactory = { runtime }
        )

        val result = binder.ensureBound(hostContext(), proxyUri())

        assertTrue(result is HostedProviderRuntimeBindResult.Bound)
        val bound = result as HostedProviderRuntimeBindResult.Bound
        assertEquals("BOUND", bound.status)
        assertEquals("runtimeBoundForProviderProxy", bound.detail)
        assertSame(bootstrapped, bound.result)
        assertEquals(listOf("inst-001" to null), runtime.bindRequests)
    }

    @Test
    fun `ensureBound reports bootstrap failure`() {
        val runtime = FakeHostedRuntimeEngine(bindError = IllegalStateException("boom"))
        val binder = HostedProviderRuntimeBinder(
            runtimeEngineFactory = { runtime }
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
        val processSlot = "com.multiapp.app:v3"
        val bootstrapped = hostedResult("inst-001", processSlot = processSlot)
        val runtime = FakeHostedRuntimeEngine(bindResult = bootstrapped)
        val binder = HostedProviderRuntimeBinder(
            runtimeEngineFactory = { runtime }
        )

        val result = binder.ensureBound(hostContext(), proxyUri(processSlot = processSlot))

        val bound = result as HostedProviderRuntimeBindResult.Bound
        assertEquals(listOf("inst-001" to processSlot), runtime.bindRequests)
        assertEquals(processSlot, bound.processSlot)
    }

    @Test
    fun `ensureBound rejects cached runtime from another process slot`() {
        val runtime = FakeHostedRuntimeEngine(
            reusableResults = mutableMapOf(
                "inst-001" to hostedResult("inst-001", processSlot = "com.multiapp.app:v1")
            )
        )
        val binder = HostedProviderRuntimeBinder(
            runtimeEngineFactory = { runtime }
        )

        val result = binder.ensureBound(hostContext(), proxyUri(processSlot = "com.multiapp.app:v3"))

        val failed = result as HostedProviderRuntimeBindResult.Failed
        assertEquals("runtimeProcessSlotMismatch", failed.detail)
        assertEquals("com.multiapp.app:v3", failed.processSlot)
        assertEquals(0, runtime.bindCalls)
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
        every { getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID) } returns instanceId
        every { getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY) } returns guestAuthority
        every { getQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT) } returns processSlot
    }

    private fun hostedResult(
        instanceId: String,
        processSlot: String? = null
    ): EngineHostedBootstrapResult =
        EngineHostedBootstrapResult.fromLoader(
            HostedBootstrapResult(
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
        )

    private class FakeHostedRuntimeEngine(
        private val reusableResults: MutableMap<String, EngineHostedBootstrapResult> = mutableMapOf(),
        private val bindResult: EngineHostedBootstrapResult? = null,
        private val bindError: Throwable? = null
    ) : HostedRuntimeEngine {
        val bindRequests = mutableListOf<Pair<String, String?>>()
        val bindCalls: Int
            get() = bindRequests.size

        override fun reusableResult(instanceId: String): EngineHostedBootstrapResult? = reusableResults[instanceId]

        override fun runBootstrap(
            instanceId: String,
            providerHookEnabled: Boolean,
            processSlot: String?
        ): EngineHostedBootstrapResult = bindResult ?: error("No bind result configured")

        override fun bindApplication(
            instanceId: String,
            providerHookEnabled: Boolean,
            processSlot: String?
        ): HostedRuntimeBindOutcome {
            bindRequests += instanceId to processSlot
            bindError?.let { throw it }
            val result = bindResult ?: error("No bind result configured")
            reusableResults[instanceId] = result
            return HostedRuntimeBindOutcome(
                result = result,
                ranBootstrapOnThisThread = true
            )
        }
    }
}
