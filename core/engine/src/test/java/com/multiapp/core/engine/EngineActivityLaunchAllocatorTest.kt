package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityLaunchAllocationRequest
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineActivityLaunchAllocatorTest {

    @Test
    fun `allocation identity binds authoritative generation process and slot`() {
        val fixture = fixture(runtime())

        val allocation = fixture.allocator.allocate(request(), PROCESS_ID)
        val identity = assertNotNull(allocation.launchIdentity)

        assertTrue(allocation.accepted)
        assertEquals(RUNTIME_EPOCH, identity.runtimeEpoch)
        assertEquals(ENGINE_SESSION_ID, identity.engineSessionId)
        assertEquals(PROCESS_SLOT, identity.processSlot)
        assertEquals(INSTANCE_ID, identity.instanceId)
        assertFalse(
            fixture.capabilities.authorize(identity.toEngineIdentity(), PROCESS_ID + 1).accepted,
            "a same-UID process must not be able to consume another process's capability"
        )
        assertTrue(fixture.capabilities.authorize(identity.toEngineIdentity(), PROCESS_ID).accepted)
    }

    @Test
    fun `capability issue failure rolls reserved slot back with compare and set`() {
        val registry = EngineRuntimeRegistry().apply { register(runtime()) }
        val activityService = DefaultVirtualSystemServer(registry).activityService
        val capabilities = EngineActivityLaunchCapabilityRegistry(tokenFactory = { "" })
        val allocator = EngineActivityLaunchAllocator(registry, activityService, capabilities)
        val request = request()

        val allocation = allocator.allocate(request, PROCESS_ID)

        assertFalse(allocation.accepted)
        assertTrue(allocation.reason.startsWith("activity_allocation_capability_failed:"))
        assertNull(
            activityService.queryProxyActivitySlot(INSTANCE_ID, request.slotKey())
                .proxyActivityClassName
        )
        assertEquals(0, capabilities.size())
    }

    @Test
    fun `release of unconsumed allocation restores slot and revokes capability`() {
        val fixture = fixture(runtime())
        val request = request()
        val allocation = fixture.allocator.allocate(request, PROCESS_ID)
        val allocatedProxy = assertNotNull(allocation.proxyActivityClassName)

        assertEquals(
            allocatedProxy,
            fixture.activityService.queryProxyActivitySlot(INSTANCE_ID, request.slotKey())
                .proxyActivityClassName
        )

        assertTrue(fixture.allocator.release(allocation, PROCESS_ID))
        assertNull(
            fixture.activityService.queryProxyActivitySlot(INSTANCE_ID, request.slotKey())
                .proxyActivityClassName
        )
        assertEquals(0, fixture.capabilities.size())
    }

    @Test
    fun `unsupported launch mode is rejected before slot reservation`() {
        val fixture = fixture(runtime())

        val allocation = fixture.allocator.allocate(
            request(launchMode = "singleInstance"),
            PROCESS_ID
        )

        assertFalse(allocation.accepted)
        assertEquals(
            "activity_allocation_launch_mode_unsupported:singleInstance",
            allocation.reason
        )
        assertEquals(0, fixture.capabilities.size())
    }

    @Test
    fun `guest cannot downgrade authoritative launch mode`() {
        val fixture = fixture(
            runtime(
                activities = listOf(
                    ResolvedComponent(
                        name = GUEST_ACTIVITY,
                        exported = true,
                        launchMode = "singleTop"
                    )
                )
            )
        )

        val allocation = fixture.allocator.allocate(request(launchMode = null), PROCESS_ID)

        assertFalse(allocation.accepted)
        assertEquals(
            "activity_allocation_launch_mode_mismatch:requested=,authoritative=singleTop",
            allocation.reason
        )
        assertEquals(0, fixture.capabilities.size())
    }

    @Test
    fun `alias cannot hide unsupported target launch mode`() {
        val aliasActivity = "$ORIGIN_PACKAGE.LauncherAlias"
        val fixture = fixture(
            runtime(
                activities = listOf(
                    ResolvedComponent(
                        name = aliasActivity,
                        exported = true,
                        targetActivityName = GUEST_ACTIVITY
                    ),
                    ResolvedComponent(
                        name = GUEST_ACTIVITY,
                        exported = false,
                        launchMode = "singleInstance"
                    )
                )
            )
        )

        val allocation = fixture.allocator.allocate(
            request(guestActivityClassName = aliasActivity),
            PROCESS_ID
        )

        assertFalse(allocation.accepted)
        assertEquals(
            "activity_allocation_launch_mode_unsupported:singleInstance",
            allocation.reason
        )
        assertEquals(0, fixture.capabilities.size())
    }

    @Test
    fun `stale generation release cannot clear successor allocation`() {
        val initialRuntime = runtime()
        val registry = EngineRuntimeRegistry().apply { register(initialRuntime) }
        val activityService = mockk<VirtualActivityService>()
        every { activityService.queryProxyActivitySlot(INSTANCE_ID, any()) } answers {
            proxySlotResult(
                key = secondArg(),
                operation = "queryProxyActivitySlot",
                proxyActivityClassName = null
            )
        }
        var reserveCount = 0
        val oldProxy = EngineProxyActivitySlots.classNamesForProcessSlot(
            HOST_PACKAGE,
            PROCESS_SLOT,
            null
        ).single()
        val successorProxy = EngineProxyActivitySlots.classNamesForProcessSlot(
            HOST_PACKAGE,
            SUCCESSOR_PROCESS_SLOT,
            null
        ).single()
        every { activityService.reserveProxyActivitySlot(INSTANCE_ID, any(), any()) } answers {
            proxySlotResult(
                key = secondArg(),
                operation = "reserveProxyActivitySlot",
                proxyActivityClassName = if (reserveCount++ == 0) oldProxy else successorProxy
            )
        }
        var tokenIndex = 0
        val capabilities = EngineActivityLaunchCapabilityRegistry(
            tokenFactory = { "generation-capability-${++tokenIndex}" }
        )
        val allocator = EngineActivityLaunchAllocator(registry, activityService, capabilities)
        val oldRequest = request()
        val oldAllocation = allocator.allocate(oldRequest, PROCESS_ID)
        assertEquals(oldProxy, oldAllocation.proxyActivityClassName)

        val successorRuntime = initialRuntime.copy(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "engine-session-successor",
            processSlot = SUCCESSOR_PROCESS_SLOT,
            processName = SUCCESSOR_PROCESS_SLOT
        )
        registry.register(successorRuntime)
        val successorRequest = request(processSlot = SUCCESSOR_PROCESS_SLOT)
        val successorAllocation = allocator.allocate(successorRequest, PROCESS_ID)

        assertTrue(successorAllocation.accepted)
        assertEquals(successorProxy, successorAllocation.proxyActivityClassName)
        assertFalse(allocator.release(oldAllocation, PROCESS_ID))
        verify(exactly = 0) {
            activityService.compareAndSetProxyActivitySlot(any(), any(), any(), any())
        }
        assertTrue(
            capabilities.authorize(
                assertNotNull(successorAllocation.launchIdentity).toEngineIdentity(),
                PROCESS_ID
            ).accepted
        )
    }

    private fun proxySlotResult(
        key: ProxyActivitySlotKey,
        operation: String,
        proxyActivityClassName: String?
    ) = VirtualProxyActivitySlotOperationResult(
        instanceId = key.instanceId,
        operation = operation,
        verdict = EngineResultStatus.PASS,
        key = key,
        proxyActivityClassName = proxyActivityClassName,
        matched = proxyActivityClassName != null,
        removedCount = 0,
        message = "proxy_activity_slot_test"
    )

    private fun fixture(runtime: VirtualInstanceRuntime): Fixture {
        val registry = EngineRuntimeRegistry().apply { register(runtime) }
        val activityService = DefaultVirtualSystemServer(registry).activityService
        var tokenIndex = 0
        val capabilities = EngineActivityLaunchCapabilityRegistry(
            tokenFactory = { "allocator-capability-${++tokenIndex}" }
        )
        return Fixture(
            registry = registry,
            activityService = activityService,
            capabilities = capabilities,
            allocator = EngineActivityLaunchAllocator(registry, activityService, capabilities)
        )
    }

    private fun request(
        processSlot: String = PROCESS_SLOT,
        launchMode: String? = null,
        guestActivityClassName: String = GUEST_ACTIVITY
    ) = VirtualActivityLaunchAllocationRequest(
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        guestActivityClassName = guestActivityClassName,
        processSlot = processSlot,
        launchMode = launchMode,
        taskAffinity = TASK_AFFINITY
    )

    private fun runtime(
        activities: List<ResolvedComponent> = listOf(
            ResolvedComponent(name = GUEST_ACTIVITY, exported = true)
        )
    ) = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = HOST_PACKAGE,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = VIRTUAL_PACKAGE,
        dataRoot = "build/tmp/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/$INSTANCE_ID",
            activities = activities
        ),
        profile = EngineProfile.BASELINE,
        processSlot = PROCESS_SLOT,
        proxySlot = "$HOST_PACKAGE.container.ProxyActivity0",
        evidenceSessionId = "evidence-session",
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processId = PROCESS_ID,
        processName = PROCESS_SLOT,
        state = VirtualRuntimeState.PREWARMED
    )

    private data class Fixture(
        val registry: EngineRuntimeRegistry,
        val activityService: VirtualActivityService,
        val capabilities: EngineActivityLaunchCapabilityRegistry,
        val allocator: EngineActivityLaunchAllocator
    )

    private companion object {
        const val INSTANCE_ID = "instance-activity-allocation"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-activity-allocation"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v0"
        const val SUCCESSOR_PROCESS_SLOT = "$HOST_PACKAGE:v1"
        const val GUEST_ACTIVITY = "$ORIGIN_PACKAGE.MainActivity"
        const val TASK_AFFINITY = "$ORIGIN_PACKAGE:$INSTANCE_ID"
        const val PROCESS_ID = 4200
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-42"
    }
}
