package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineServiceOperationLeaseCoordinatorTest {
    @Test
    fun `coordinator rejects stale issue snapshot before allocating a token`() {
        var current = runtime(runtimeEpoch = EPOCH + 1, engineSessionId = "engine-session-43")
        var tokenCalls = 0
        val leases = EngineServiceOperationLeaseRegistry(
            tokenFactory = { "coordinated-service-lease-${++tokenCalls}" }
        )
        val coordinator = EngineServiceOperationLeaseCoordinator(
            authoritativeRuntime = { current },
            leases = leases
        )

        assertFailsWith<IllegalStateException> {
            coordinator.issue(
                runtime(),
                PROCESS_ID,
                VirtualServiceOperation.START,
                SERVICE_COMPONENT,
                PROCESS_SLOT
            )
        }
        assertEquals(0, tokenCalls)

        val identity = coordinator.issue(
            current,
            PROCESS_ID,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertEquals(current.runtimeEpoch, identity.runtimeEpoch)
    }

    @Test
    fun `coordinator rejects commit when authority advances without explicit revoke`() {
        var current: VirtualInstanceRuntime? = runtime()
        val leases = EngineServiceOperationLeaseRegistry(tokenFactory = { "coordinated-stale-lease" })
        val coordinator = EngineServiceOperationLeaseCoordinator(
            authoritativeRuntime = { current },
            leases = leases
        )
        val identity = coordinator.issue(
            checkNotNull(current),
            PROCESS_ID,
            VirtualServiceOperation.BIND,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertTrue(coordinator.authorize(identity, PROCESS_ID).accepted)

        current = runtime(runtimeEpoch = EPOCH + 1, engineSessionId = "engine-session-43")
        val staleCommit = coordinator.commit(identity, PROCESS_ID)
        val repeatedCommit = coordinator.commit(identity, PROCESS_ID)

        assertFalse(staleCommit.accepted)
        assertEquals(EngineServiceOperationLeaseState.REVOKED, staleCommit.state)
        assertEquals("service_operation_lease_runtime_generation_mismatch", staleCommit.reason)
        assertFalse(repeatedCommit.accepted)
    }

    @Test
    fun `wrong pid does not revoke a valid lease and valid commit still succeeds`() {
        val current = runtime()
        val coordinator = EngineServiceOperationLeaseCoordinator(
            authoritativeRuntime = { current },
            leases = EngineServiceOperationLeaseRegistry(tokenFactory = { "coordinated-pid-lease" })
        )
        val identity = coordinator.issue(
            current,
            PROCESS_ID,
            VirtualServiceOperation.STOP,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertTrue(coordinator.authorize(identity, PROCESS_ID).accepted)

        val wrongPid = coordinator.commit(identity, PROCESS_ID + 1)
        val committed = coordinator.commit(identity, PROCESS_ID)

        assertFalse(wrongPid.accepted)
        assertEquals("service_operation_lease_process_id_mismatch", wrongPid.reason)
        assertTrue(committed.accepted)
    }

    @Test
    fun `missing or dead runtime revokes pending lease fail closed`() {
        var current: VirtualInstanceRuntime? = runtime()
        var tokenIndex = 0
        val coordinator = EngineServiceOperationLeaseCoordinator(
            authoritativeRuntime = { current },
            leases = EngineServiceOperationLeaseRegistry(
                tokenFactory = { "coordinated-runtime-lease-${++tokenIndex}" }
            )
        )
        val missingIdentity = coordinator.issue(
            checkNotNull(current),
            PROCESS_ID,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        current = null
        val missing = coordinator.abort(missingIdentity, PROCESS_ID)
        assertFalse(missing.accepted)
        assertEquals("service_operation_lease_runtime_not_found", missing.reason)

        current = runtime(runtimeEpoch = EPOCH + 1, engineSessionId = "engine-session-43")
        val deadIdentity = coordinator.issue(
            checkNotNull(current),
            PROCESS_ID,
            VirtualServiceOperation.START_FOREGROUND,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertTrue(coordinator.authorize(deadIdentity, PROCESS_ID).accepted)
        current = checkNotNull(current).copy(state = VirtualRuntimeState.DEAD)
        val dead = coordinator.commit(deadIdentity, PROCESS_ID)
        assertFalse(dead.accepted)
        assertEquals("service_operation_lease_runtime_not_live", dead.reason)
    }

    @Test
    fun `coordinator exposes explicit generation and instance revocation`() {
        val current = runtime()
        var tokenIndex = 0
        val coordinator = EngineServiceOperationLeaseCoordinator(
            authoritativeRuntime = { current },
            leases = EngineServiceOperationLeaseRegistry(
                tokenFactory = { "coordinated-revoke-lease-${++tokenIndex}" }
            )
        )
        val first = coordinator.issue(
            current,
            PROCESS_ID,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertEquals(1, coordinator.revokeGeneration(INSTANCE_ID, EPOCH, SESSION_ID))
        assertFalse(coordinator.authorize(first, PROCESS_ID).accepted)
        assertEquals(0, coordinator.revokeInstance(INSTANCE_ID))
    }

    private fun runtime(
        runtimeEpoch: Long = EPOCH,
        engineSessionId: String = SESSION_ID
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
            dataDir = "build/tmp/$INSTANCE_ID"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = PROCESS_SLOT,
        proxySlot = "$HOST_PACKAGE.container.ProxyActivity0",
        evidenceSessionId = "evidence-$runtimeEpoch",
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processId = PROCESS_ID,
        processName = PROCESS_SLOT,
        state = VirtualRuntimeState.RUNNING
    )

    private companion object {
        const val INSTANCE_ID = "instance-service-coordinator"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-service-coordinator"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v0"
        const val SERVICE_COMPONENT = "$ORIGIN_PACKAGE.SyncService"
        const val PROCESS_ID = 4300
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
    }
}
