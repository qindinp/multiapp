package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineSystemServiceProxyRegistryTest {

    @Test
    fun `stale generation cannot overwrite a newer binding after validation`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(runtimeRegistry)
        val oldRuntime = server.runtimeService.register(runtime())
        val oldAtCommitBoundary = CountDownLatch(1)
        val allowOldCommit = CountDownLatch(1)
        val oldResult = AtomicReference<EngineSystemServiceBindResult>()
        val serviceRegistry = EngineSystemServiceProxyRegistry(server.runtimeService) {
            if (Thread.currentThread().name == "old-system-service-bind") {
                oldAtCommitBoundary.countDown()
                check(allowOldCommit.await(5, TimeUnit.SECONDS))
            }
            99L
        }
        val oldThread = thread(name = "old-system-service-bind") {
            oldResult.set(serviceRegistry.bind(bindRequest(oldRuntime)))
        }
        assertTrue(oldAtCommitBoundary.await(5, TimeUnit.SECONDS))
        val newRuntime = server.runtimeService.register(
            oldRuntime.copy(
                runtimeEpoch = oldRuntime.runtimeEpoch + 1L,
                engineSessionId = "engine-session-2"
            )
        )
        val newResult = serviceRegistry.bind(bindRequest(newRuntime).copy(adapterId = "new-adapter"))

        allowOldCommit.countDown()
        oldThread.join(5_000L)

        assertFalse(oldThread.isAlive)
        assertEquals(EngineResultStatus.PARTIAL, newResult.verdict)
        assertEquals(EngineResultStatus.FAIL, oldResult.get().verdict)
        assertEquals(
            "new-adapter",
            serviceRegistry.query(newRuntime.instanceId, EngineSystemServiceId.NOTIFICATION)?.adapterId
        )
    }

    @Test
    fun `query snapshot and reconcile hide stale generations`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(runtimeRegistry)
        val first = server.runtimeService.register(runtime())
        val queryRegistry = EngineSystemServiceProxyRegistry(server.runtimeService) { 1L }
        val reconcileRegistry = EngineSystemServiceProxyRegistry(server.runtimeService) { 1L }
        queryRegistry.bind(bindRequest(first))
        reconcileRegistry.bind(bindRequest(first))
        server.runtimeService.register(
            first.copy(
                runtimeEpoch = first.runtimeEpoch + 1L,
                engineSessionId = "engine-session-2"
            )
        )

        assertNull(queryRegistry.query(first.instanceId, EngineSystemServiceId.NOTIFICATION))
        assertTrue(queryRegistry.snapshot(first.instanceId).isEmpty())
        assertEquals(1, reconcileRegistry.reconcileActiveRuntimes())
        assertTrue(reconcileRegistry.snapshot().isEmpty())
    }

    @Test
    fun `revokeGeneration removes only the exact runtime generation`() {
        val runtimeRegistry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(runtimeRegistry)
        val active = server.runtimeService.register(runtime())
        val serviceRegistry = EngineSystemServiceProxyRegistry(server.runtimeService) { 1L }
        serviceRegistry.bind(bindRequest(active))

        assertEquals(
            0,
            serviceRegistry.revokeGeneration(
                instanceId = active.instanceId,
                runtimeEpoch = active.runtimeEpoch + 1L,
                engineSessionId = "other-session",
                processSlot = active.processSlot
            )
        )
        assertEquals(1, serviceRegistry.snapshot(active.instanceId).size)
        assertEquals(
            1,
            serviceRegistry.revokeGeneration(
                instanceId = active.instanceId,
                runtimeEpoch = active.runtimeEpoch,
                engineSessionId = active.engineSessionId,
                processSlot = active.processSlot
            )
        )
        assertTrue(serviceRegistry.snapshot(active.instanceId).isEmpty())
    }

    private fun bindRequest(runtime: VirtualInstanceRuntime) = EngineSystemServiceBindRequest(
        instanceId = runtime.instanceId,
        serviceId = EngineSystemServiceId.NOTIFICATION,
        runtimeEpoch = runtime.runtimeEpoch,
        engineSessionId = runtime.engineSessionId,
        processSlot = runtime.processSlot,
        apiLevel = 37,
        adapterId = "test-adapter",
        adapterInstalled = true
    )

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = "instance-1",
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.instance1",
        dataRoot = "build/tmp/instance-1",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "instance-1",
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.instance1",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 37,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/instance-1"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-1",
        runtimeEpoch = 42L,
        engineSessionId = "engine-session-1",
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.CREATED
    )
}
