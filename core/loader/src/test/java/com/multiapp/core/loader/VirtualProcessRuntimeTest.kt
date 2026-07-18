package com.multiapp.core.loader

import android.app.Application
import io.mockk.mockk
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualProcessRuntimeTest {

    @Test
    fun `bindApplication reuses successful runtime for same instance`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        var bootstrapCalls = 0
        val firstResult = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            processSlot = "com.multiapp.app:v3"
        )

        val first = runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            firstResult
        }
        val second = runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader()
            )
        }

        assertEquals(1, bootstrapCalls)
        assertSame(first, second)
        assertSame(firstResult, runtime.get("inst-001")?.result)
        assertEquals("com.multiapp.app:v3", runtime.get("inst-001")?.processName)
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `concurrent bindApplication single flights bootstrap for same instance`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val bootstrapEntered = CountDownLatch(1)
        val releaseBootstrap = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val bootstrapCalls = AtomicInteger(0)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val results = Collections.synchronizedList(mutableListOf<HostedBootstrapResult>())
        val provisionalResult = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        val reusableResult = provisionalResult.copy()

        val owner = Thread(
            {
                try {
                    results.add(
                        runtime.bindApplication("inst-001") {
                            bootstrapCalls.incrementAndGet()
                            runtime.rememberApplication("inst-001", provisionalResult)
                            bootstrapEntered.countDown()
                            assertTrue(releaseBootstrap.await(5, TimeUnit.SECONDS))
                            reusableResult
                        }
                    )
                } catch (error: Throwable) {
                    errors.add(error)
                } finally {
                    completed.countDown()
                }
            },
            "bind-owner"
        )
        owner.start()
        assertTrue(bootstrapEntered.await(5, TimeUnit.SECONDS))
        assertEquals(VirtualProcessRuntimeState.BINDING, runtime.state("inst-001"))
        assertNull(runtime.reusableResult("inst-001"))
        assertNull(runtime.get("inst-001"))

        val follower = Thread(
            {
                try {
                    results.add(
                        runtime.bindApplication("inst-001") {
                            bootstrapCalls.incrementAndGet()
                            hostedResult(
                                instanceId = "inst-001",
                                success = true,
                                guestClassLoader = ClassLoader.getSystemClassLoader()
                            )
                        }
                    )
                } catch (error: Throwable) {
                    errors.add(error)
                } finally {
                    completed.countDown()
                }
            },
            "bind-follower"
        )
        follower.start()

        releaseBootstrap.countDown()
        assertTrue(completed.await(5, TimeUnit.SECONDS))

        assertTrue(errors.isEmpty(), errors.joinToString { it.message ?: it.javaClass.name })
        assertEquals(1, bootstrapCalls.get())
        assertEquals(2, results.size)
        assertSame(reusableResult, results[0])
        assertSame(reusableResult, results[1])
        assertSame(reusableResult, runtime.get("inst-001")?.result)
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `initialization thread can reenter with provisional runtime only`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val provisional = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        val ready = provisional.copy()
        var nestedBootstrapCalls = 0

        val result = runtime.bindApplication("inst-001") {
            runtime.rememberApplication("inst-001", provisional)

            assertEquals(VirtualProcessRuntimeState.BINDING, runtime.state("inst-001"))
            assertSame(provisional, runtime.reusableResult("inst-001"))
            assertSame(provisional, runtime.get("inst-001")?.result)
            assertEquals(VirtualProcessRuntimeState.BINDING, runtime.get("inst-001")?.state)
            assertSame(
                provisional,
                runtime.bindApplication("inst-001") {
                    nestedBootstrapCalls += 1
                    ready
                }
            )
            ready
        }

        assertEquals(0, nestedBootstrapCalls)
        assertSame(ready, result)
        assertSame(ready, runtime.reusableResult("inst-001"))
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
        assertEquals(VirtualProcessRuntimeState.READY, runtime.get("inst-001")?.state)
    }

    @Test
    fun `reentrant bind before provisional publication fails without deadlock`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val ready = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        var nestedBootstrapCalls = 0

        val result = runtime.bindApplication("inst-001") {
            val error = assertFailsWith<IllegalStateException> {
                runtime.bindApplication("inst-001") {
                    nestedBootstrapCalls += 1
                    ready
                }
            }
            assertTrue(error.message.orEmpty().contains("before provisional runtime publication"))
            ready
        }

        assertEquals(0, nestedBootstrapCalls)
        assertSame(ready, result)
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `READY rejects a different Application than provisional runtime`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val provisional = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        val inconsistentReady = provisional.copy(
            guestApplication = mockk<Application>(relaxed = true)
        )

        val error = assertFailsWith<IllegalStateException> {
            runtime.bindApplication("inst-001") {
                runtime.rememberApplication("inst-001", provisional)
                inconsistentReady
            }
        }

        assertTrue(error.message.orEmpty().contains("does not match the provisional"))
        assertEquals(VirtualProcessRuntimeState.FAILED, runtime.state("inst-001"))
        assertNull(runtime.get("inst-001"))
    }

    @Test
    fun `READY accepts a verified LoadedApk Application delegate transition`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val provisional = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        val delegate = mockk<Application>(relaxed = true)
        val ready = provisional.copy(
            guestApplication = delegate,
            stageResults = listOf(
                BootstrapResult.success(
                    stage = RuntimeStage.APPLICATION,
                    evidence = listOf(
                        BootstrapEvidence("loadedApkApplicationCreatorStatus", "PASS"),
                        BootstrapEvidence("loadedApkFinalApplicationStatus", "PASS"),
                        BootstrapEvidence("loadedApkFinalApplicationSource", "DELEGATE"),
                        BootstrapEvidence("loadedApkFinalApplicationReason", "CONTEXT_PACKAGE_INFO_MATCH")
                    )
                )
            )
        )

        val result = runtime.bindApplication("inst-001") {
            runtime.rememberApplication("inst-001", provisional)
            ready
        }

        assertSame(delegate, result.guestApplication)
        assertSame(result, runtime.reusableResult("inst-001"))
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `READY rejects an unverified Application delegate transition`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val provisional = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        val ready = provisional.copy(
            guestApplication = mockk<Application>(relaxed = true),
            stageResults = listOf(
                BootstrapResult.success(
                    stage = RuntimeStage.APPLICATION,
                    evidence = listOf(
                        BootstrapEvidence("loadedApkApplicationCreatorStatus", "PASS"),
                        BootstrapEvidence("loadedApkFinalApplicationStatus", "SKIPPED"),
                        BootstrapEvidence("loadedApkFinalApplicationSource", "DELEGATE")
                    )
                )
            )
        )

        assertFailsWith<IllegalStateException> {
            runtime.bindApplication("inst-001") {
                runtime.rememberApplication("inst-001", provisional)
                ready
            }
        }

        assertEquals(VirtualProcessRuntimeState.FAILED, runtime.state("inst-001"))
        assertNull(runtime.reusableResult("inst-001"))
    }

    @Test
    fun `READY rejects a different ClassLoader than provisional runtime`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val provisional = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        val inconsistentReady = provisional.copy(
            guestClassLoader = object : ClassLoader(provisional.guestClassLoader) {}
        )

        assertFailsWith<IllegalStateException> {
            runtime.bindApplication("inst-001") {
                runtime.rememberApplication("inst-001", provisional)
                inconsistentReady
            }
        }

        assertEquals(VirtualProcessRuntimeState.FAILED, runtime.state("inst-001"))
        assertNull(runtime.reusableResult("inst-001"))
    }

    @Test
    fun `bootstrap owner retains provisional access after main thread publication`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val provisional = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )

        val result = runtime.bindApplication("inst-001") {
            val publisher = Thread {
                runtime.rememberApplication("inst-001", provisional)
            }
            publisher.start()
            publisher.join(5_000L)
            assertFalse(publisher.isAlive)
            assertSame(provisional, runtime.reusableResult("inst-001"))
            provisional
        }

        assertSame(provisional, result)
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `ordinary waiter times out without reusing provisional runtime`() {
        val runtime = VirtualProcessRuntime(
            clock = { 1000L },
            bindingTimeoutMs = 150L
        )
        val provisionalPublished = CountDownLatch(1)
        val releaseBootstrap = CountDownLatch(1)
        val ownerError = AtomicReference<Throwable?>()
        val provisional = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        val ready = provisional.copy()

        val owner = Thread(
            {
                try {
                    runtime.bindApplication("inst-001") {
                        runtime.rememberApplication("inst-001", provisional)
                        provisionalPublished.countDown()
                        assertTrue(releaseBootstrap.await(5, TimeUnit.SECONDS))
                        ready
                    }
                } catch (error: Throwable) {
                    ownerError.set(error)
                }
            },
            "bind-timeout-owner"
        )
        owner.start()
        assertTrue(provisionalPublished.await(5, TimeUnit.SECONDS))

        assertNull(runtime.reusableResult("inst-001"))
        assertNull(runtime.get("inst-001"))
        assertFailsWith<TimeoutException> {
            runtime.bindApplication("inst-001") {
                error("ordinary waiter must not start a second bootstrap")
            }
        }
        assertEquals(VirtualProcessRuntimeState.TIMED_OUT, runtime.state("inst-001"))
        assertNull(runtime.reusableResult("inst-001"))
        assertNull(runtime.get("inst-001"))

        releaseBootstrap.countDown()
        owner.join(5_000L)
        assertFalse(owner.isAlive)
        assertTrue(ownerError.get() is TimeoutException)
        assertFailsWith<TimeoutException> {
            runtime.rememberApplication("inst-001", ready)
        }
        assertEquals(VirtualProcessRuntimeState.TIMED_OUT, runtime.state("inst-001"))

        var retryCalls = 0
        assertFailsWith<TimeoutException> {
            runtime.bindApplication("inst-001") {
                retryCalls += 1
                ready
            }
        }
        assertEquals(0, retryCalls)

        assertTrue(runtime.clear("inst-001"))
        assertSame(
            ready,
            runtime.bindApplication("inst-001") {
                retryCalls += 1
                ready
            }
        )
        assertEquals(1, retryCalls)
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `bootstrap owner cannot publish READY after binding deadline`() {
        val nowNanos = AtomicLong(1_000L)
        val timeoutMs = 25L
        val runtime = VirtualProcessRuntime(
            clock = { 1000L },
            bindingTimeoutMs = timeoutMs,
            monotonicClockNanos = nowNanos::get
        )
        val ready = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )

        assertFailsWith<TimeoutException> {
            runtime.bindApplication("inst-001") {
                nowNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(timeoutMs))
                ready
            }
        }

        assertEquals(VirtualProcessRuntimeState.TIMED_OUT, runtime.state("inst-001"))
        assertNull(runtime.get("inst-001"))
    }

    @Test
    fun `late publication cannot replace an existing READY runtime identity`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val ready = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )
        runtime.bindApplication("inst-001") { ready }
        val stale = ready.copy(guestApplication = mockk<Application>(relaxed = true))

        assertFailsWith<IllegalStateException> {
            runtime.rememberApplication("inst-001", stale)
        }

        assertSame(ready, runtime.reusableResult("inst-001"))
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `READY runtime is reusable only for the same complete binding fingerprint`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val fingerprint = bindingFingerprint()
        val ready = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            processSlot = fingerprint.processSlot
        )

        runtime.bindApplication("inst-001", fingerprint) { ready }

        assertSame(ready, runtime.reusableResult("inst-001", fingerprint))
        val changedApk = fingerprint.copy(baseApkSha256 = "sha256-new")
        assertNull(runtime.reusableResult("inst-001", changedApk))
        assertNull(runtime.reusableResult("inst-001", fingerprint.copy(dataRoot = "/tmp/other-data")))
        assertNull(runtime.reusableResult("inst-001", fingerprint.copy(baseApkPath = "/tmp/moved-base.apk")))
        assertNull(
            runtime.reusableResult(
                "inst-001",
                fingerprint.copy(effectiveGuestProcessName = "com.example.app:remote")
            )
        )
        assertNull(
            runtime.reusableResult(
                "inst-001",
                fingerprint.copy(splitApkPaths = listOf("/tmp/moved-split.apk"))
            )
        )
        var staleBootstrapCalls = 0
        val error = assertFailsWith<IllegalStateException> {
            runtime.bindApplication("inst-001", changedApk) {
                staleBootstrapCalls += 1
                ready
            }
        }
        assertTrue(error.message.orEmpty().contains("different runtime fingerprint"))
        assertEquals(0, staleBootstrapCalls)
        assertSame(ready, runtime.reusableResult("inst-001", fingerprint))
    }

    @Test
    fun `concurrent bind with different profile fingerprint fails closed`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        val baseline = bindingFingerprint()
        val compat = baseline.copy(engineProfile = "COMPAT_HOOK", providerHookEnabled = true)
        val bootstrapEntered = CountDownLatch(1)
        val releaseBootstrap = CountDownLatch(1)
        val owner = Thread {
            runtime.bindApplication("inst-001", baseline) {
                bootstrapEntered.countDown()
                check(releaseBootstrap.await(5, TimeUnit.SECONDS))
                hostedResult(
                    instanceId = "inst-001",
                    success = true,
                    guestClassLoader = ClassLoader.getSystemClassLoader(),
                    processSlot = baseline.processSlot
                )
            }
        }
        owner.start()
        assertTrue(bootstrapEntered.await(5, TimeUnit.SECONDS))

        assertFailsWith<IllegalStateException> {
            runtime.bindApplication("inst-001", compat) {
                error("mismatched binding must not bootstrap")
            }
        }

        releaseBootstrap.countDown()
        owner.join(5_000L)
        assertFalse(owner.isAlive)
        assertNotNull(runtime.reusableResult("inst-001", baseline))
    }

    @Test
    fun `bootstrap result for another instance fails closed`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })

        assertFailsWith<IllegalArgumentException> {
            runtime.bindApplication("inst-001") {
                hostedResult(
                    instanceId = "inst-002",
                    success = true,
                    guestClassLoader = ClassLoader.getSystemClassLoader()
                )
            }
        }

        assertEquals(VirtualProcessRuntimeState.FAILED, runtime.state("inst-001"))
        assertNull(runtime.get("inst-001"))
    }

    @Test
    fun `bindApplication does not cache failed runtime`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        var bootstrapCalls = 0

        runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            hostedResult(
                instanceId = "inst-001",
                success = false,
                guestClassLoader = null
            )
        }
        val retry = runtime.bindApplication("inst-001") {
            bootstrapCalls += 1
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader()
            )
        }

        assertEquals(2, bootstrapCalls)
        assertEquals(true, retry.success)
        assertSame(retry, runtime.get("inst-001")?.result)
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `bindApplication does not cache result without classloader`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })

        runtime.bindApplication("inst-001") {
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = null
            )
        }

        assertNull(runtime.get("inst-001"))
        assertEquals(VirtualProcessRuntimeState.FAILED, runtime.state("inst-001"))
    }

    @Test
    fun `final Application stage requires LoadedApk PASS before runtime becomes READY`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })

        runtime.bindApplication("inst-001") {
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader(),
                stageResults = listOf(applicationStageResult(creatorStatus = null))
            )
        }
        assertEquals(VirtualProcessRuntimeState.FAILED, runtime.state("inst-001"))
        assertNull(runtime.get("inst-001"))

        runtime.bindApplication("inst-001") {
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader(),
                stageResults = listOf(applicationStageResult(creatorStatus = "FALLBACK"))
            )
        }
        assertEquals(VirtualProcessRuntimeState.FAILED, runtime.state("inst-001"))
        assertNull(runtime.get("inst-001"))

        val ready = runtime.bindApplication("inst-001") {
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader(),
                stageResults = listOf(applicationStageResult(creatorStatus = "PASS"))
            )
        }
        assertSame(ready, runtime.get("inst-001")?.result)
        assertEquals(VirtualProcessRuntimeState.READY, runtime.state("inst-001"))
    }

    @Test
    fun `bindApplication executes bootstrap outside runtime monitor`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        var bootstrapHeldRuntimeLock = true

        runtime.bindApplication("inst-001") {
            bootstrapHeldRuntimeLock = Thread.holdsLock(runtime)
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader()
            )
        }

        assertFalse(bootstrapHeldRuntimeLock)
    }

    @Test
    fun `clear removes cached runtime`() {
        val runtime = VirtualProcessRuntime(clock = { 1000L })
        runtime.bindApplication("inst-001") {
            hostedResult(
                instanceId = "inst-001",
                success = true,
                guestClassLoader = ClassLoader.getSystemClassLoader()
            )
        }

        assertEquals(true, runtime.clear("inst-001"))
        assertNull(runtime.get("inst-001"))
    }

    private fun hostedResult(
        instanceId: String,
        success: Boolean,
        guestClassLoader: ClassLoader?,
        processSlot: String? = null,
        stageResults: List<BootstrapResult> = emptyList(),
        guestApplication: Application? = if (success && guestClassLoader != null) {
            mockk<Application>(relaxed = true)
        } else {
            null
        }
    ) = HostedBootstrapResult(
        instanceId = instanceId,
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.example",
        processSlot = processSlot,
        originApkPath = "/tmp/base.apk",
        dataRoot = "/tmp/$instanceId",
        guestClassLoader = guestClassLoader,
        guestApplication = guestApplication,
        installRecord = null,
        packageSnapshot = null,
        launcherActivityClassName = "com.example.app.MainActivity",
        stageResults = stageResults,
        summary = stageResults.toSummary(),
        success = success,
        diagnostics = null
    )

    private fun applicationStageResult(creatorStatus: String?): BootstrapResult =
        BootstrapResult.success(
            stage = RuntimeStage.APPLICATION,
            evidence = creatorStatus?.let {
                listOf(BootstrapEvidence("loadedApkApplicationCreatorStatus", it))
            }.orEmpty()
        )

    private fun bindingFingerprint() = HostedRuntimeBindingFingerprint(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.inst-001",
        processSlot = "com.multiapp.app:v0",
        dataRoot = "/tmp/inst-001",
        versionCode = 1L,
        baseApkPath = "/tmp/base.apk",
        baseApkSha256 = "sha256-base",
        splitApkPaths = listOf("/tmp/split.apk"),
        splitApkSha256s = listOf("sha256-split"),
        applicationClassName = "com.example.App",
        engineProfile = "BASELINE",
        providerHookEnabled = false
    )
}
