package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.engine.EngineHostedBootstrapResult
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
        val reusable = hostedResult("inst-001")
        val runtime = FakeHostedRuntimeEngine(reusableResults = mutableMapOf("inst-001" to reusable))
        val binder = HostedActivityRuntimeBinder(
            runtimeEngineFactory = { runtime }
        )

        val result = binder.ensureBound(hostContext(), "inst-001")

        assertTrue(result is HostedActivityRuntimeBindResult.Bound)
        val bound = result as HostedActivityRuntimeBindResult.Bound
        assertEquals("CACHED", bound.status)
        assertEquals("runtimeAlreadyReusable", bound.detail)
        assertSame(reusable, bound.result)
        assertEquals(0, runtime.bindCalls)
    }

    @Test
    fun `ensureBound bootstraps runtime through process binder`() {
        val bootstrapped = hostedResult("inst-001")
        val runtime = FakeHostedRuntimeEngine(bindResult = bootstrapped)
        val binder = HostedActivityRuntimeBinder(
            runtimeEngineFactory = { runtime }
        )

        val result = binder.ensureBound(hostContext(), "inst-001")

        assertTrue(result is HostedActivityRuntimeBindResult.Bound)
        val bound = result as HostedActivityRuntimeBindResult.Bound
        assertEquals("BOUND", bound.status)
        assertEquals("runtimeBoundForActivityProxy", bound.detail)
        assertSame(bootstrapped, bound.result)
        assertEquals(listOf("inst-001" to null), runtime.bindRequests)
    }

    @Test
    fun `ensureBound reports bootstrap failure`() {
        val runtime = FakeHostedRuntimeEngine(bindError = IllegalStateException("boom"))
        val binder = HostedActivityRuntimeBinder(
            runtimeEngineFactory = { runtime }
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

    private fun hostedResult(instanceId: String): EngineHostedBootstrapResult =
        EngineHostedBootstrapResult.fromLoader(
            HostedBootstrapResult(
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
