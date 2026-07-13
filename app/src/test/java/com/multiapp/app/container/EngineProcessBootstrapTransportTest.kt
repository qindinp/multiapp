package com.multiapp.app.container

import android.os.IBinder
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineProcessBootstrapTransportTest {
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
        assertNull(EngineProcessBootstrapIpc.authority("com.multiapp.app", "com.multiapp.app:v8"))
        assertNull(EngineProcessBootstrapIpc.authority("com.multiapp.app", "not-a-slot"))
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
                processRecycler = EngineProcessSlotRecycler { slot ->
                    recycleCalls += 1
                    EngineProcessSlotRecycleResult("KILL_REQUESTED", 2468, "recycled $slot")
                }
            )

            val result = bootstrapper.bootstrap(request())

            assertEquals(EngineProcessBootstrapState.TIMED_OUT, result.state)
            assertEquals(EngineResultStatus.FAIL, result.verdict)
            assertTrue(!result.ready)
            assertEquals(1, recycleCalls)
            assertEquals("KILL_REQUESTED", result.evidence["processSlotRecycleStatus"])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `timed out binder call keeps slot tombstone until transport actually exits`() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val calls = AtomicInteger()
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
                processRecycler = EngineProcessSlotRecycler {
                    EngineProcessSlotRecycleResult("KILL_REQUESTED", 2468, "test recycle")
                }
            )

            val first = bootstrapper.bootstrap(request())
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(EngineProcessBootstrapState.TIMED_OUT, first.state)

            val blockedRetry = bootstrapper.bootstrap(request(runtimeEpoch = 43L))
            assertEquals(EngineProcessBootstrapState.STALE, blockedRetry.state)
            assertEquals("true", blockedRetry.evidence["bootstrapInFlightTombstone"])
            assertEquals(1, calls.get())

            release.countDown()
            assertTrue(exited.await(1, TimeUnit.SECONDS))
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
}
