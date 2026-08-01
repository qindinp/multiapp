package com.multiapp.app.container

import android.os.IBinder
import com.multiapp.core.engine.EngineComponentProcessLaunchTicket
import com.multiapp.core.engine.EngineProcessBootstrapKind
import com.multiapp.core.engine.EngineProcessBootstrapRequest
import com.multiapp.core.engine.EngineProcessBootstrapResult
import com.multiapp.core.engine.EngineProcessBootstrapState
import com.multiapp.core.model.engine.EngineEvidenceMode
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineProcessBootstrapTransportTest {
    @Test
    fun `package manager evidence preserves bootstrap stage fields`() {
        val fields = readyResult(
            envelope = EngineProcessBootstrapIpc.requestEnvelope(request()),
            token = mockk<IBinder> { every { isBinderAlive } returns true }
        ).copy(
            evidence = mapOf(
                "packageManagerProxy.stage" to "PACKAGE_MANAGER_PROXY",
                "packageManagerProxy.status" to "SUCCESS",
                "packageManagerProxy.globalPmsProxyEnabled" to "true",
                "packageManagerProxy.sPackageManagerPatched" to "true"
            )
        ).packageManagerProxyEvidenceFields()

        assertEquals("PACKAGE_MANAGER_PROXY", fields?.get("stage"))
        assertEquals("SUCCESS", fields?.get("status"))
        assertEquals("true", fields?.get("globalPmsProxyEnabled"))
        assertEquals("true", fields?.get("sPackageManagerPatched"))
    }

    @Test
    fun `process slot maps only to declared bootstrap authorities`() {
        assertEquals(
            "com.multiapp.app.multiapp.bootstrap.v0",
            EngineProcessBootstrapIpc.authority("com.multiapp.app", "com.multiapp.app:v0")
        )
        assertEquals(
            "com.multiapp.app.multiapp.bootstrap.v7",
            EngineProcessBootstrapIpc.authority("com.multiapp.app", "com.multiapp.app:v7")
        )
        assertEquals(
            "com.multiapp.app.multiapp.bootstrap.v23",
            EngineProcessBootstrapIpc.authority("com.multiapp.app", "com.multiapp.app:v23")
        )
        assertNull(EngineProcessBootstrapIpc.authority("com.multiapp.app", "com.multiapp.app:v24"))
        assertNull(EngineProcessBootstrapIpc.authority("com.multiapp.app", "not-a-slot"))
    }

    @Test
    fun `process slot parsing rejects injection aliases and foreign packages`() {
        // P1-SEC-01 严格化（2026-08-01）：多分隔符注入、前导零别名、非十进制均拒绝
        assertEquals(3, EngineProcessBootstrapIpc.processSlotIndex("com.multiapp.app:v3"))
        assertEquals(23, EngineProcessBootstrapIpc.processSlotIndex("com.multiapp.app:v23"))
        assertNull(EngineProcessBootstrapIpc.processSlotIndex("com.multiapp.app:v24"))
        assertNull(EngineProcessBootstrapIpc.processSlotIndex("com.multiapp.app:v"))
        assertNull(EngineProcessBootstrapIpc.processSlotIndex("not-a-slot"))
        // v03 是前导零别名：严格十进制下拒绝（canonical 为 v3）
        assertNull(EngineProcessBootstrapIpc.processSlotIndex("com.multiapp.app:v03"))
        // 多分隔符注入：host:v3:evil:v1 必须整体拒绝，不得解析出 1
        assertNull(EngineProcessBootstrapIpc.processSlotIndex("com.multiapp.app:v3:evil:v1"))
        // foreign 包名前缀（无 host 校验时格式合法，返回 index；host 校验在 authority() 拒绝）
        assertEquals(1, EngineProcessBootstrapIpc.processSlotIndex("foreign.pkg:v1"))
        // authority() 带 host 校验：foreign 包名被拒绝
        assertNull(EngineProcessBootstrapIpc.authority("com.multiapp.app", "foreign.pkg:v1"))
        assertNull(EngineProcessBootstrapIpc.authority("com.multiapp.app", "com.multiapp.app:v03"))
        assertNull(EngineProcessBootstrapIpc.authority("com.multiapp.app", "com.multiapp.app:v3:evil:v1"))
    }

    @Test
    fun `component bootstrap envelope carries kind and complete launch ticket`() {
        val request = componentRequest()

        val envelope = EngineProcessBootstrapIpc.requestEnvelope(request)

        assertEquals(EngineProcessBootstrapKind.COMPONENT_RUNTIME, envelope.kind)
        assertEquals(request.componentLaunchTicket, envelope.componentLaunchTicket)
        assertEquals(request.runtime.processSlot, envelope.processSlot)
        assertTrue(envelope.isWellFormed())
        assertFalse(envelope.copy(processSlot = "com.multiapp.app:v7").isWellFormed())
        assertFalse(
            envelope.copy(
                componentLaunchTicket = envelope.componentLaunchTicket?.copy(instanceId = "other-instance")
            ).isWellFormed()
        )
    }

    @Test
    fun `malformed bootstrap response fails closed`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 1_000L,
                transport = EngineProcessBootstrapTransport { _, _ -> null },
                executor = executor
            )

            val result = bootstrapper.bootstrap(request())

            assertEquals(EngineProcessBootstrapState.STALE, result.state, result.message)
            assertEquals(EngineResultStatus.FAIL, result.verdict)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `bootstrap timeout is bounded and does not report ready`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            var recycleCalls = 0
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 20L,
                transport = EngineProcessBootstrapTransport { _, _ ->
                    Thread.sleep(200L)
                    null
                },
                executor = executor,
                processRecycler = EngineProcessSlotRecycler { recycleRequest ->
                    recycleCalls += 1
                    EngineProcessSlotRecycleResult(
                        "RECYCLED",
                        2468,
                        "recycled ${recycleRequest.processSlot}",
                        slotReusable = true
                    )
                }
            )

            val result = bootstrapper.bootstrap(request())

            assertEquals(EngineProcessBootstrapState.TIMED_OUT, result.state)
            assertEquals(EngineResultStatus.FAIL, result.verdict)
            assertTrue(!result.ready)
            assertEquals(0, recycleCalls)
            val recycleStatus = result.evidence["processSlotRecycleStatus"]
            assertTrue(
                recycleStatus in setOf(
                    "DEFERRED_IN_FLIGHT",
                    "DEFERRED_NOT_CANCELLED",
                    "IDENTITY_UNAVAILABLE",
                    "NOT_STARTED"
                ),
                result.evidence.toString()
            )
            assertEquals(
                (recycleStatus == "NOT_STARTED").toString(),
                result.evidence["processSlotReusable"]
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `timed out binder call force-removes slot tombstone so next launch proceeds`() {
        val entered = CountDownLatch(1)
        val executor = object : ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) {
            override fun execute(command: Runnable) {
                super.execute(command)
                check(entered.await(1, TimeUnit.SECONDS)) {
                    "bootstrap transport did not enter before timeout measurement"
                }
            }
        }.apply { prestartCoreThread() }
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val calls = AtomicInteger()
        val recycleCalls = AtomicInteger()
        val token = mockk<IBinder> { every { isBinderAlive } returns true }
        try {
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 20L,
                transport = EngineProcessBootstrapTransport { _, envelope ->
                    val call = calls.incrementAndGet()
                    if (call == 1) {
                        entered.countDown()
                        while (true) {
                            try {
                                if (release.await(20, TimeUnit.MILLISECONDS)) break
                            } catch (_: InterruptedException) {
                                // Binder calls are not interruptible; model that behavior here.
                            }
                        }
                        exited.countDown()
                    }
                    readyResult(envelope, token)
                },
                executor = executor,
                processRecycler = EngineProcessSlotRecycler { recycleRequest ->
                    recycleCalls.incrementAndGet()
                    assertEquals(42L, recycleRequest.runtimeEpoch)
                    assertEquals(2468, recycleRequest.processId)
                    assertEquals(12_345L, recycleRequest.processStartTicks)
                    EngineProcessSlotRecycleResult(
                        "RECYCLED",
                        2468,
                        "test recycle",
                        slotReusable = true
                    )
                }
            )

            val first = bootstrapper.bootstrap(request())
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(EngineProcessBootstrapState.TIMED_OUT, first.state)

            val blockedRetry = bootstrapper.bootstrap(request(runtimeEpoch = 43L))
            assertEquals(EngineProcessBootstrapState.TIMED_OUT, blockedRetry.state)
            assertNull(blockedRetry.evidence["bootstrapInFlightTombstone"])
            assertEquals(1, calls.get())

            release.countDown()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
            val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (recycleCalls.get() == 0 && System.nanoTime() < cleanupDeadline) {
                Thread.yield()
            }
            assertEquals(1, recycleCalls.get())
            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            var recovered = bootstrapper.bootstrap(request(runtimeEpoch = 44L))
            while (
                recovered.state == EngineProcessBootstrapState.STALE &&
                recovered.evidence["bootstrapInFlightTombstone"] == "true" &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
                recovered = bootstrapper.bootstrap(request(runtimeEpoch = 44L))
            }
            assertEquals(EngineProcessBootstrapState.READY, recovered.state)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed bootstrap keeps slot tombstone until generation cleanup finishes`() {
        val bootstrapExecutor = Executors.newFixedThreadPool(2)
        val callerExecutor = Executors.newSingleThreadExecutor()
        val recycleEntered = CountDownLatch(1)
        val releaseRecycle = CountDownLatch(1)
        val calls = AtomicInteger()
        val recycleCalls = AtomicInteger()
        val token = mockk<IBinder> { every { isBinderAlive } returns true }
        try {
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 1_000L,
                transport = EngineProcessBootstrapTransport { _, envelope ->
                    if (calls.incrementAndGet() == 1) {
                        EngineProcessBootstrapResult(
                            state = EngineProcessBootstrapState.FAILED,
                            verdict = EngineResultStatus.FAIL,
                            instanceId = envelope.instanceId,
                            runtimeEpoch = envelope.runtimeEpoch,
                            engineSessionId = envelope.engineSessionId,
                            processId = 2468,
                            processStartTicks = 12_345L,
                            processName = envelope.processSlot,
                            message = "test bootstrap failure"
                        )
                    } else {
                        readyResult(envelope, token)
                    }
                },
                executor = bootstrapExecutor,
                processRecycler = EngineProcessSlotRecycler { recycleRequest ->
                    recycleCalls.incrementAndGet()
                    recycleEntered.countDown()
                    check(releaseRecycle.await(1, TimeUnit.SECONDS)) { "cleanup release timed out" }
                    EngineProcessSlotRecycleResult(
                        "RECYCLED",
                        2468,
                        "recycled ${recycleRequest.processSlot}",
                        slotReusable = true
                    )
                }
            )
            val first = callerExecutor.submit<EngineProcessBootstrapResult> {
                bootstrapper.bootstrap(request())
            }
            assertTrue(recycleEntered.await(1, TimeUnit.SECONDS))

            val sameGeneration = bootstrapper.bootstrap(request())
            val blockedRetry = bootstrapper.bootstrap(request(runtimeEpoch = 43L))

            assertEquals(EngineProcessBootstrapState.FAILED, sameGeneration.state)
            assertEquals("DEFERRED_TO_OWNER", sameGeneration.evidence["processSlotRecycleStatus"])
            assertEquals(EngineProcessBootstrapState.STALE, blockedRetry.state)
            assertEquals("true", blockedRetry.evidence["bootstrapInFlightTombstone"])
            assertEquals(1, calls.get())
            assertEquals(1, recycleCalls.get())
            releaseRecycle.countDown()
            assertEquals(EngineProcessBootstrapState.FAILED, first.get(1, TimeUnit.SECONDS).state)

            val recovered = bootstrapper.bootstrap(request(runtimeEpoch = 44L))
            assertEquals(EngineProcessBootstrapState.READY, recovered.state)
            assertEquals(2, calls.get())
        } finally {
            releaseRecycle.countDown()
            callerExecutor.shutdownNow()
            bootstrapExecutor.shutdownNow()
        }
    }

    @Test
    fun `stale bootstrap recycles only the exact observed process generation`() {
        val executor = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger()
        var recycleRequest: EngineProcessSlotRecycleRequest? = null
        val token = mockk<IBinder> { every { isBinderAlive } returns true }
        try {
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 1_000L,
                transport = EngineProcessBootstrapTransport { _, envelope ->
                    if (calls.incrementAndGet() == 1) {
                        EngineProcessBootstrapResult(
                            state = EngineProcessBootstrapState.STALE,
                            verdict = EngineResultStatus.FAIL,
                            instanceId = envelope.instanceId,
                            runtimeEpoch = envelope.runtimeEpoch,
                            engineSessionId = envelope.engineSessionId,
                            processId = 2468,
                            processStartTicks = 12_345L,
                            processName = envelope.processSlot,
                            message = "runtime changed after guest bootstrap"
                        )
                    } else {
                        readyResult(envelope, token)
                    }
                },
                executor = executor,
                processRecycler = EngineProcessSlotRecycler { request ->
                    recycleRequest = request
                    EngineProcessSlotRecycleResult(
                        status = "RECYCLED",
                        processId = request.processId,
                        message = "exact generation recycled",
                        slotReusable = true
                    )
                }
            )

            val stale = bootstrapper.bootstrap(request())
            val recovered = bootstrapper.bootstrap(request(runtimeEpoch = 43L))

            assertEquals(EngineProcessBootstrapState.STALE, stale.state)
            assertEquals("RECYCLED", stale.evidence["processSlotRecycleStatus"])
            assertEquals("true", stale.evidence["processSlotReusable"])
            assertEquals("instance-1", recycleRequest?.instanceId)
            assertEquals(42L, recycleRequest?.runtimeEpoch)
            assertEquals("engine-evidence-42", recycleRequest?.engineSessionId)
            assertEquals("com.multiapp.app:v3", recycleRequest?.processSlot)
            assertEquals(2468, recycleRequest?.processId)
            assertEquals(12_345L, recycleRequest?.processStartTicks)
            assertEquals(EngineProcessBootstrapState.READY, recovered.state)
            assertEquals(2, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `bootstrap without exact process identity retains fail closed slot tombstone`() {
        val executor = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger()
        try {
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 1_000L,
                transport = EngineProcessBootstrapTransport { _, _ ->
                    calls.incrementAndGet()
                    null
                },
                executor = executor
            )

            val first = bootstrapper.bootstrap(request())
            val retry = bootstrapper.bootstrap(request(runtimeEpoch = 43L))

            assertEquals(EngineProcessBootstrapState.STALE, first.state)
            assertEquals("IDENTITY_UNAVAILABLE", first.evidence["processSlotRecycleStatus"])
            assertEquals("false", first.evidence["processSlotReusable"])
            assertEquals(EngineProcessBootstrapState.STALE, retry.state)
            assertEquals("true", retry.evidence["bootstrapInFlightTombstone"])
            assertEquals(1, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `matching provider response is accepted as ready`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            var calledAuthority: String? = null
            val processToken = mockk<IBinder> {
                every { isBinderAlive } returns true
            }
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 1_000L,
                transport = EngineProcessBootstrapTransport { authority, envelope ->
                    calledAuthority = authority
                    EngineProcessBootstrapResult(
                        state = EngineProcessBootstrapState.READY,
                        verdict = EngineResultStatus.PASS,
                        instanceId = envelope.instanceId,
                        runtimeEpoch = envelope.runtimeEpoch,
                        engineSessionId = envelope.engineSessionId,
                        clientToken = processToken,
                        processId = 2468,
                        processStartTicks = 12_345L,
                        processName = envelope.processSlot,
                        launcherActivityClassName = "com.test.app.MainActivity",
                        applicationStatus = "PASS",
                        providerPreinstallStatus = "PASS",
                        systemServiceProxyStatus = "SUCCESS",
                        message = "guest process is READY"
                    )
                },
                executor = executor
            )

            val result = bootstrapper.bootstrap(request())

            assertEquals("com.multiapp.app.multiapp.bootstrap.v3", calledAuthority, result.message)
            assertEquals(EngineProcessBootstrapState.READY, result.state)
            assertEquals(2468, result.processId)
            assertEquals("com.test.app.MainActivity", result.launcherActivityClassName)
            assertTrue(result.ready)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `matching cached component response is accepted without launcher activity`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val processToken = mockk<IBinder> {
                every { isBinderAlive } returns true
            }
            var observedEnvelope: EngineProcessBootstrapRequestEnvelope? = null
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 1_000L,
                transport = EngineProcessBootstrapTransport { _, envelope ->
                    observedEnvelope = envelope
                    EngineProcessBootstrapResult(
                        state = EngineProcessBootstrapState.READY,
                        verdict = EngineResultStatus.PASS,
                        instanceId = envelope.instanceId,
                        runtimeEpoch = envelope.runtimeEpoch,
                        engineSessionId = envelope.engineSessionId,
                        clientToken = processToken,
                        processId = 2469,
                        processStartTicks = 12_346L,
                        processName = envelope.processSlot,
                        cached = true,
                        launcherActivityClassName = null,
                        applicationStatus = "PASS",
                        providerPreinstallStatus = "SKIPPED",
                        systemServiceProxyStatus = "SUCCESS",
                        message = "component guest process is READY"
                    )
                },
                executor = executor
            )

            val result = bootstrapper.bootstrap(componentRequest())

            assertEquals(EngineProcessBootstrapState.READY, result.state, result.message)
            assertTrue(result.cached)
            assertNull(result.launcherActivityClassName)
            assertEquals(
                EngineProcessBootstrapKind.COMPONENT_RUNTIME,
                observedEnvelope?.kind
            )
            assertEquals(
                "com.test.app:remote",
                observedEnvelope?.componentLaunchTicket?.effectiveGuestProcessName
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `READY response without live process token fails closed`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val bootstrapper = ContentProviderEngineProcessBootstrapper(
                hostPackageName = "com.multiapp.app",
                timeoutMs = 1_000L,
                transport = EngineProcessBootstrapTransport { _, envelope ->
                    EngineProcessBootstrapResult(
                        state = EngineProcessBootstrapState.READY,
                        verdict = EngineResultStatus.PASS,
                        instanceId = envelope.instanceId,
                        runtimeEpoch = envelope.runtimeEpoch,
                        engineSessionId = envelope.engineSessionId,
                        processId = 2468,
                        processStartTicks = 12_345L,
                        processName = envelope.processSlot,
                        launcherActivityClassName = "com.test.app.MainActivity",
                        message = "guest process is READY"
                    )
                },
                executor = executor
            )

            val result = bootstrapper.bootstrap(request())

            assertEquals(EngineProcessBootstrapState.STALE, result.state)
            assertEquals(EngineResultStatus.FAIL, result.verdict)
            assertTrue(!result.ready)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readyResult(
        envelope: EngineProcessBootstrapRequestEnvelope,
        token: IBinder
    ) = EngineProcessBootstrapResult(
        state = EngineProcessBootstrapState.READY,
        verdict = EngineResultStatus.PASS,
        instanceId = envelope.instanceId,
        runtimeEpoch = envelope.runtimeEpoch,
        engineSessionId = envelope.engineSessionId,
        clientToken = token,
        processId = 2468,
        processStartTicks = 12_345L,
        processName = envelope.processSlot,
        launcherActivityClassName = "com.test.app.MainActivity",
        applicationStatus = "PASS",
        providerPreinstallStatus = "PASS",
        systemServiceProxyStatus = "SUCCESS",
        message = "guest process is READY"
    )

    private fun request(
        runtimeEpoch: Long = 42L,
        engineSessionId: String = "engine-evidence-$runtimeEpoch"
    ): EngineProcessBootstrapRequest = EngineProcessBootstrapRequest(
        runtime = VirtualInstanceRuntime(
            instanceId = "instance-1",
            hostPackageName = "com.multiapp.app",
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.instance.instance1",
            dataRoot = "/data/user/0/com.multiapp.app/files/instances/instance-1",
            packageSnapshot = VirtualPackageSnapshot(
                instanceId = "instance-1",
                originPackageName = "com.test.app",
                virtualPackageName = "com.multiapp.instance.instance1",
                applicationLabel = "Test",
                versionCode = 1L,
                versionName = "1.0",
                targetSdk = 35,
                minSdk = 28,
                sourceDir = "/tmp/base.apk",
                dataDir = "/data/user/0/com.multiapp.app/files/instances/instance-1"
            ),
            profile = EngineProfile.BASELINE,
            processSlot = "com.multiapp.app:v3",
            proxySlot = "com.multiapp.app.container.ProxyActivity3",
            evidenceSessionId = "evidence-1",
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId
        ),
        providerRoutingEnabled = true,
        legacyProviderHookEnabled = false,
        evidenceMode = EngineEvidenceMode.MINIMAL
    )

    private fun componentRequest(): EngineProcessBootstrapRequest {
        val primary = request().runtime
        val ticket = EngineComponentProcessLaunchTicket(
            instanceId = primary.instanceId,
            effectiveGuestProcessName = "${primary.originPackageName}:remote",
            processSlot = "com.multiapp.app:v4",
            attachCapability = "c".repeat(64)
        )
        return EngineProcessBootstrapRequest(
            runtime = primary.copy(
                processSlot = ticket.processSlot,
                processName = ticket.effectiveGuestProcessName
            ),
            providerRoutingEnabled = true,
            legacyProviderHookEnabled = false,
            evidenceMode = EngineEvidenceMode.MINIMAL,
            kind = EngineProcessBootstrapKind.COMPONENT_RUNTIME,
            componentLaunchTicket = ticket
        )
    }
}
