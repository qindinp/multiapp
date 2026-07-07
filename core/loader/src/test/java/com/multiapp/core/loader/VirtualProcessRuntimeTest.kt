package com.multiapp.core.loader

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            guestClassLoader = ClassLoader.getSystemClassLoader()
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
        val reusableResult = hostedResult(
            instanceId = "inst-001",
            success = true,
            guestClassLoader = ClassLoader.getSystemClassLoader()
        )

        val owner = Thread(
            {
                try {
                    results.add(
                        runtime.bindApplication("inst-001") {
                            bootstrapCalls.incrementAndGet()
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
        guestClassLoader: ClassLoader?
    ) = HostedBootstrapResult(
        instanceId = instanceId,
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.example",
        originApkPath = "/tmp/base.apk",
        dataRoot = "/tmp/$instanceId",
        guestClassLoader = guestClassLoader,
        guestApplication = null,
        installRecord = null,
        packageSnapshot = null,
        launcherActivityClassName = "com.example.app.MainActivity",
        stageResults = emptyList(),
        summary = emptyList<BootstrapResult>().toSummary(),
        success = success,
        diagnostics = null
    )
}
