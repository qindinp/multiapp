package com.multiapp.app.container

import android.content.Context
import android.content.Intent
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineServiceStartRoute
import com.multiapp.core.engine.HostedRuntimeBindOutcome
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
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
        val reusable = hostedResult("inst-001")
        val runtime = FakeHostedRuntimeEngine(reusableResults = mutableMapOf("inst-001" to reusable))
        val cachedProxyIntent = proxyIntent()
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            requestDecoder = { hostPackageName, intent ->
                assertEquals("com.multiapp.app", hostPackageName)
                assertSame(cachedProxyIntent, intent)
                serviceStartRoute()
            }
        )

        val result = binder.ensureBound(hostContext(), cachedProxyIntent)

        assertTrue(result is HostedServiceRuntimeBindResult.Bound)
        val bound = result as HostedServiceRuntimeBindResult.Bound
        assertEquals("CACHED", bound.status)
        assertEquals("runtimeAlreadyReusable", bound.detail)
        assertSame(reusable, bound.result)
        assertEquals(0, runtime.bindCalls)
    }

    @Test
    fun `ensureBound bootstraps runtime through single-flight binder`() {
        val bootstrapped = hostedResult("inst-001")
        val runtime = FakeHostedRuntimeEngine(bindResult = bootstrapped)
        val coldProxyIntent = proxyIntent()
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            requestDecoder = { hostPackageName, intent ->
                assertEquals("com.multiapp.app", hostPackageName)
                assertSame(coldProxyIntent, intent)
                serviceStartRoute()
            }
        )

        val result = binder.ensureBound(hostContext(), coldProxyIntent)

        assertTrue(result is HostedServiceRuntimeBindResult.Bound)
        val bound = result as HostedServiceRuntimeBindResult.Bound
        assertEquals("BOUND", bound.status)
        assertEquals("runtimeBoundForServiceProxy", bound.detail)
        assertSame(bootstrapped, bound.result)
        assertEquals(listOf("inst-001" to null), runtime.bindRequests)
    }

    @Test
    fun `ensureBound reports bootstrap failure`() {
        val failingProxyIntent = proxyIntent()
        val runtime = FakeHostedRuntimeEngine(bindError = IllegalStateException("boom"))
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            requestDecoder = { hostPackageName, intent ->
                assertEquals("com.multiapp.app", hostPackageName)
                assertSame(failingProxyIntent, intent)
                serviceStartRoute()
            }
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

    @Test
    fun `ensureBound passes service process slot into bootstrap`() {
        val processSlot = "com.multiapp.app:v4"
        val bootstrapped = hostedResult("inst-001", processSlot = processSlot)
        val runtime = FakeHostedRuntimeEngine(bindResult = bootstrapped)
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            requestDecoder = { _, _ -> serviceStartRoute(processSlot = processSlot) }
        )

        val result = binder.ensureBound(hostContext(), proxyIntent())

        val bound = result as HostedServiceRuntimeBindResult.Bound
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
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            requestDecoder = { _, _ -> serviceStartRoute(processSlot = "com.multiapp.app:v4") }
        )

        val result = binder.ensureBound(hostContext(), proxyIntent())

        val failed = result as HostedServiceRuntimeBindResult.Failed
        assertEquals("runtimeProcessSlotMismatch", failed.detail)
        assertEquals("com.multiapp.app:v4", failed.processSlot)
        assertEquals(0, runtime.bindCalls)
    }

    private fun hostContext(): Context = mockk(relaxed = true) {
        every { packageName } returns "com.multiapp.app"
        every { filesDir } returns File("build/tmp/hosted-service-runtime-binder-test")
        every { applicationContext } returns this
    }

    private fun proxyIntent(): Intent = mockk(relaxed = true)

    private fun serviceStartRoute(processSlot: String? = null): EngineServiceStartRoute = EngineServiceStartRoute.create(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        guestServiceClassName = "com.example.app.SyncService",
        sourceIntent = proxyIntent(),
        reason = "explicit",
        foreground = false,
        processSlot = processSlot
    )

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
