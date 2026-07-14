package com.multiapp.app.container

import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.engine.EngineComponentProcessLaunchTicket
import com.multiapp.core.engine.EngineComponentProcessOperationResult
import com.multiapp.core.engine.EngineComponentProcessState
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineRuntimeIpcSnapshot
import com.multiapp.core.engine.EngineServiceStartRoute
import com.multiapp.core.engine.HostedRuntimeBindOutcome
import com.multiapp.core.engine.HostedRuntimeEngine
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.toSummary
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
            authorityQuery = { authoritySnapshot() },
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
            authorityQuery = { authoritySnapshot() },
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
            authorityQuery = { authoritySnapshot() },
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
            authorityQuery = { authoritySnapshot(processSlot) },
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
            authorityQuery = { authoritySnapshot("com.multiapp.app:v4") },
            requestDecoder = { _, _ -> serviceStartRoute(processSlot = "com.multiapp.app:v4") }
        )

        val result = binder.ensureBound(hostContext(), proxyIntent())

        val failed = result as HostedServiceRuntimeBindResult.Failed
        assertEquals("runtimeProcessSlotMismatch", failed.detail)
        assertEquals("com.multiapp.app:v4", failed.processSlot)
        assertEquals(0, runtime.bindCalls)
    }

    @Test
    fun `ensureBound derives custom Service process from attached component authority`() {
        val processSlot = "com.multiapp.app:v3"
        val effectiveGuestProcessName = "com.example.app:remote"
        val runtime = FakeHostedRuntimeEngine(
            bindResult = hostedResult("inst-001", processSlot = processSlot)
        )
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = {
                authoritySnapshot("com.multiapp.app:v0").copy(liveAuthority = false)
            },
            componentAuthorityQuery = {
                componentAuthority(
                    processSlot = processSlot,
                    effectiveGuestProcessName = effectiveGuestProcessName
                )
            },
            requestDecoder = { _, _ -> serviceStartRoute(processSlot = processSlot) }
        )

        val result = binder.ensureBound(hostContext(), proxyIntent())

        assertTrue(result is HostedServiceRuntimeBindResult.Bound)
        assertEquals(listOf("inst-001" to processSlot), runtime.bindRequests)
        assertEquals(listOf(effectiveGuestProcessName), runtime.effectiveGuestProcessRequests)
        assertEquals(listOf(false), runtime.providerHookRequests)
    }

    @Test
    fun `ensureBound rejects unattached or wrong-slot component authority`() {
        val processSlot = "com.multiapp.app:v3"
        val runtime = FakeHostedRuntimeEngine(
            bindResult = hostedResult("inst-001", processSlot = processSlot)
        )
        val unattached = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
            componentAuthorityQuery = { null },
            requestDecoder = { _, _ -> serviceStartRoute(processSlot = processSlot) }
        ).ensureBound(hostContext(), proxyIntent())
        val wrongSlot = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
            componentAuthorityQuery = {
                componentAuthority(
                    processSlot = "com.multiapp.app:v4",
                    effectiveGuestProcessName = "com.example.app:remote"
                )
            },
            requestDecoder = { _, _ -> serviceStartRoute(processSlot = processSlot) }
        ).ensureBound(hostContext(), proxyIntent())

        assertTrue(unattached is HostedServiceRuntimeBindResult.Failed)
        assertTrue(wrongSlot is HostedServiceRuntimeBindResult.Failed)
        assertEquals(0, runtime.bindCalls)
    }

    @Test
    fun `ensureBound binds then attaches a ticketed custom Service process`() {
        val processSlot = "com.multiapp.app:v3"
        val effectiveGuestProcessName = "com.example.app:remote"
        val ticket = componentTicket(processSlot, effectiveGuestProcessName)
        val token = mockk<IBinder>()
        val events = mutableListOf<String>()
        val runtime = FakeHostedRuntimeEngine(
            bindResult = hostedResult("inst-001", processSlot = processSlot),
            onBind = { events += "bind" }
        )
        var attachedTicket: EngineComponentProcessLaunchTicket? = null
        var attachedToken: IBinder? = null
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
            componentAuthorityQuery = { null },
            componentClientAttacher = { actualTicket, actualToken ->
                events += "attach"
                attachedTicket = actualTicket
                attachedToken = actualToken
                componentAuthority(processSlot, effectiveGuestProcessName).copy(
                    operation = "attachComponentProcessClient",
                    alreadyRunning = false,
                    reason = "component_process_client_attached"
                )
            },
            processToken = token,
            requestDecoder = { _, _ ->
                serviceStartRoute(
                    processSlot = processSlot,
                    componentProcessLaunchTicket = ticket
                )
            }
        )

        val result = binder.ensureBound(hostContext(), proxyIntent())

        assertTrue(result is HostedServiceRuntimeBindResult.Bound)
        assertEquals("componentProcessAttached", result.detail)
        assertEquals(listOf(effectiveGuestProcessName), runtime.effectiveGuestProcessRequests)
        assertEquals(listOf(false), runtime.providerHookRequests)
        assertEquals(listOf("attach", "bind"), events)
        assertSame(ticket, attachedTicket)
        assertSame(token, attachedToken)
    }

    @Test
    fun `ensureBound reuses live authority before consuming a shared ticket again`() {
        val processSlot = "com.multiapp.app:v3"
        val effectiveGuestProcessName = "com.example.app:remote"
        val ticket = componentTicket(processSlot, effectiveGuestProcessName)
        val attached = AtomicBoolean(false)
        val attachCalls = AtomicInteger()
        val runtime = FakeHostedRuntimeEngine(
            bindResult = hostedResult("inst-001", processSlot = processSlot)
        )
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
            componentAuthorityQuery = {
                if (attached.get()) {
                    componentAuthority(processSlot, effectiveGuestProcessName)
                } else {
                    null
                }
            },
            componentClientAttacher = { actualTicket, _ ->
                assertSame(ticket, actualTicket)
                attachCalls.incrementAndGet()
                attached.set(true)
                attachedComponentAuthority(processSlot, effectiveGuestProcessName)
            },
            processToken = mockk()
        )
        val route = serviceStartRoute(
            processSlot = processSlot,
            componentProcessLaunchTicket = ticket
        )

        val first = binder.ensureBound(hostContext(), route)
        val second = binder.ensureBound(hostContext(), route)

        assertTrue(first is HostedServiceRuntimeBindResult.Bound)
        assertTrue(second is HostedServiceRuntimeBindResult.Bound)
        assertEquals("componentProcessAttached", first.detail)
        assertEquals("componentProcessAlreadyAttached", second.detail)
        assertEquals(1, attachCalls.get())
        assertEquals(1, runtime.bindCalls)
    }

    @Test
    fun `ensureBound handles concurrent routes that reuse one pending ticket`() {
        val processSlot = "com.multiapp.app:v3"
        val effectiveGuestProcessName = "com.example.app:remote"
        val ticket = componentTicket(processSlot, effectiveGuestProcessName)
        val initialAuthorityQueries = CountDownLatch(2)
        val authorityQueries = AtomicInteger()
        val attached = AtomicBoolean(false)
        val attachCalls = AtomicInteger()
        val reusable = hostedResult("inst-001", processSlot = processSlot)
        val runtime = FakeHostedRuntimeEngine(
            reusableResults = mutableMapOf("inst-001" to reusable)
        )
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
            componentAuthorityQuery = {
                val queryNumber = authorityQueries.incrementAndGet()
                if (queryNumber <= 2) {
                    initialAuthorityQueries.countDown()
                    check(initialAuthorityQueries.await(5, TimeUnit.SECONDS)) {
                        "concurrent authority queries did not rendezvous"
                    }
                    null
                } else if (attached.get()) {
                    componentAuthority(processSlot, effectiveGuestProcessName)
                } else {
                    null
                }
            },
            componentClientAttacher = { _, _ ->
                if (attachCalls.incrementAndGet() == 1) {
                    attached.set(true)
                    attachedComponentAuthority(processSlot, effectiveGuestProcessName)
                } else {
                    rejectedComponentAttach()
                }
            },
            processToken = mockk()
        )
        val route = serviceStartRoute(
            processSlot = processSlot,
            componentProcessLaunchTicket = ticket
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                executor.submit<HostedServiceRuntimeBindResult> {
                    binder.ensureBound(hostContext(), route)
                }
            }
            val results = futures.map { future -> future.get(5, TimeUnit.SECONDS) }

            assertTrue(results.all { result -> result is HostedServiceRuntimeBindResult.Bound })
            assertEquals(
                setOf("componentProcessAttached", "componentProcessAlreadyAttached"),
                results.map { result -> result.detail }.toSet()
            )
            assertEquals(2, attachCalls.get())
            assertEquals(3, authorityQueries.get())
            assertEquals(0, runtime.bindCalls)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `ensureBound treats matching authority after raced attach failure as idempotent success`() {
        val processSlot = "com.multiapp.app:v3"
        val effectiveGuestProcessName = "com.example.app:remote"
        val ticket = componentTicket(processSlot, effectiveGuestProcessName)
        val authorityQueries = AtomicInteger()
        val attachCalls = AtomicInteger()
        val reusable = hostedResult("inst-001", processSlot = processSlot)
        val runtime = FakeHostedRuntimeEngine(
            reusableResults = mutableMapOf("inst-001" to reusable)
        )
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
            componentAuthorityQuery = {
                if (authorityQueries.incrementAndGet() == 1) {
                    null
                } else {
                    componentAuthority(processSlot, effectiveGuestProcessName)
                }
            },
            componentClientAttacher = { _, _ ->
                attachCalls.incrementAndGet()
                rejectedComponentAttach()
            },
            processToken = mockk()
        )

        val result = binder.ensureBound(
            hostContext(),
            serviceStartRoute(
                processSlot = processSlot,
                componentProcessLaunchTicket = ticket
            )
        )

        assertTrue(result is HostedServiceRuntimeBindResult.Bound)
        val bound = result as HostedServiceRuntimeBindResult.Bound
        assertEquals("componentProcessAlreadyAttached", bound.detail)
        assertSame(reusable, bound.result)
        assertEquals(1, attachCalls.get())
        assertEquals(2, authorityQueries.get())
    }

    @Test
    fun `ensureBound fails closed when raced authority identity or liveness mismatches ticket`() {
        val processSlot = "com.multiapp.app:v3"
        val effectiveGuestProcessName = "com.example.app:remote"
        val ticket = componentTicket(processSlot, effectiveGuestProcessName)
        val matchingAuthority = componentAuthority(processSlot, effectiveGuestProcessName)
        val matchingState = requireNotNull(matchingAuthority.processState)
        val mismatches = listOf(
            "instanceId" to matchingAuthority.copy(
                instanceId = "inst-002",
                processState = matchingState.copy(instanceId = "inst-002")
            ),
            "processSlot" to matchingAuthority.copy(
                processState = matchingState.copy(processSlot = "com.multiapp.app:v4")
            ),
            "effectiveGuestProcessName" to matchingAuthority.copy(
                processState = matchingState.copy(effectiveGuestProcessName = "com.example.app:other")
            ),
            "live" to matchingAuthority.copy(
                processState = matchingState.copy(live = false)
            )
        )

        mismatches.forEach { (field, mismatchedAuthority) ->
            val authorityQueries = AtomicInteger()
            val runtime = FakeHostedRuntimeEngine(
                bindResult = hostedResult("inst-001", processSlot = processSlot)
            )
            val binder = HostedServiceRuntimeBinder(
                runtimeEngineFactory = { runtime },
                authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
                componentAuthorityQuery = {
                    if (authorityQueries.incrementAndGet() == 1) null else mismatchedAuthority
                },
                componentClientAttacher = { _, _ -> rejectedComponentAttach() },
                processToken = mockk()
            )

            val result = binder.ensureBound(
                hostContext(),
                serviceStartRoute(
                    processSlot = processSlot,
                    componentProcessLaunchTicket = ticket
                )
            )

            assertTrue(result is HostedServiceRuntimeBindResult.Failed, field)
            assertEquals("componentProcessAttachFailed", result.detail, field)
            assertEquals(0, runtime.bindCalls, field)
        }
    }

    @Test
    fun `ensureBound fails closed when ticket attach is rejected`() {
        val processSlot = "com.multiapp.app:v3"
        val ticket = componentTicket(processSlot, "com.example.app:remote")
        val runtime = FakeHostedRuntimeEngine(
            bindResult = hostedResult("inst-001", processSlot = processSlot)
        )
        val binder = HostedServiceRuntimeBinder(
            runtimeEngineFactory = { runtime },
            authorityQuery = { authoritySnapshot().copy(liveAuthority = false) },
            componentAuthorityQuery = { null },
            componentClientAttacher = { _, _ -> null },
            requestDecoder = { _, _ ->
                serviceStartRoute(
                    processSlot = processSlot,
                    componentProcessLaunchTicket = ticket
                )
            }
        )

        val result = binder.ensureBound(hostContext(), proxyIntent())

        assertTrue(result is HostedServiceRuntimeBindResult.Failed)
        assertEquals("componentProcessAttachFailed", result.detail)
    }

    private fun hostContext(): Context = mockk(relaxed = true) {
        every { packageName } returns "com.multiapp.app"
        every { filesDir } returns File("build/tmp/hosted-service-runtime-binder-test")
        every { applicationContext } returns this
    }

    private fun proxyIntent(): Intent = mockk(relaxed = true)

    private fun authoritySnapshot(processSlot: String? = null) = EngineRuntimeIpcSnapshot(
        found = true,
        instanceId = "inst-001",
        processSlot = processSlot ?: "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        runtimeEpoch = 1L,
        engineSessionId = "engine-1",
        evidenceSessionId = "evidence-1",
        runtimeState = "PREWARMED",
        processId = 4100,
        processName = processSlot ?: "com.multiapp.app:v0",
        reason = null
    )

    private fun componentAuthority(
        processSlot: String,
        effectiveGuestProcessName: String
    ) = EngineComponentProcessOperationResult(
        operation = "queryComponentProcessClient",
        instanceId = "inst-001",
        accepted = true,
        idempotent = false,
        alreadyRunning = true,
        launchTicket = null,
        processState = EngineComponentProcessState(
            instanceId = "inst-001",
            effectiveGuestProcessName = effectiveGuestProcessName,
            processSlot = processSlot,
            processId = 4303,
            processEpoch = 3L,
            live = true
        ),
        reason = "calling_component_process_authorized"
    )

    private fun serviceStartRoute(
        processSlot: String? = null,
        componentProcessLaunchTicket: EngineComponentProcessLaunchTicket? = null
    ): EngineServiceStartRoute = EngineServiceStartRoute.create(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        guestServiceClassName = "com.example.app.SyncService",
        sourceIntent = proxyIntent(),
        reason = "explicit",
        foreground = false,
        processSlot = processSlot,
        componentProcessLaunchTicket = componentProcessLaunchTicket
    )

    private fun componentTicket(
        processSlot: String,
        effectiveGuestProcessName: String
    ) = EngineComponentProcessLaunchTicket(
        instanceId = "inst-001",
        effectiveGuestProcessName = effectiveGuestProcessName,
        processSlot = processSlot,
        attachCapability = "component-service-${"x".repeat(32)}"
    )

    private fun attachedComponentAuthority(
        processSlot: String,
        effectiveGuestProcessName: String
    ): EngineComponentProcessOperationResult =
        componentAuthority(processSlot, effectiveGuestProcessName).copy(
            operation = "attachComponentProcessClient",
            alreadyRunning = false,
            reason = "component_process_client_attached"
        )

    private fun rejectedComponentAttach() = EngineComponentProcessOperationResult(
        operation = "attachComponentProcessClient",
        instanceId = "inst-001",
        accepted = false,
        idempotent = false,
        alreadyRunning = false,
        launchTicket = null,
        processState = null,
        reason = "component_process_launch_capability_not_found"
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
        private val bindError: Throwable? = null,
        private val onBind: () -> Unit = {}
    ) : HostedRuntimeEngine {
        val bindRequests = mutableListOf<Pair<String, String?>>()
        val effectiveGuestProcessRequests = mutableListOf<String?>()
        val providerHookRequests = mutableListOf<Boolean>()
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
        ): HostedRuntimeBindOutcome = bind(
            instanceId = instanceId,
            processSlot = processSlot,
            effectiveGuestProcessName = null,
            providerHookEnabled = providerHookEnabled
        )

        override fun bindApplication(
            instanceId: String,
            providerHookEnabled: Boolean,
            processSlot: String?,
            effectiveGuestProcessName: String?
        ): HostedRuntimeBindOutcome = bind(
            instanceId = instanceId,
            processSlot = processSlot,
            effectiveGuestProcessName = effectiveGuestProcessName,
            providerHookEnabled = providerHookEnabled
        )

        private fun bind(
            instanceId: String,
            processSlot: String?,
            effectiveGuestProcessName: String?,
            providerHookEnabled: Boolean
        ): HostedRuntimeBindOutcome {
            onBind()
            bindRequests += instanceId to processSlot
            effectiveGuestProcessRequests += effectiveGuestProcessName
            providerHookRequests += providerHookEnabled
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
