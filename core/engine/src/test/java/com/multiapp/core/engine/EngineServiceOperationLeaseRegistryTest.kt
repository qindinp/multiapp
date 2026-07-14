package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineServiceOperationLeaseRegistryTest {
    @Test
    fun `lease binds primitive runtime operation identity and commit replay is idempotent`() {
        var tokenIndex = 0
        val registry = EngineServiceOperationLeaseRegistry(
            clockNanos = { 100L },
            tokenFactory = { "service-lease-${++tokenIndex}" }
        )
        val identity = registry.issue(
            authoritativeRuntime = runtime(),
            callingPid = PROCESS_ID,
            operation = VirtualServiceOperation.START,
            component = SERVICE_COMPONENT,
            processSlot = PROCESS_SLOT
        )

        assertEquals("service-lease-1", identity.leaseToken)
        assertEquals(INSTANCE_ID, identity.instanceId)
        assertEquals(EPOCH, identity.runtimeEpoch)
        assertEquals(SESSION_ID, identity.engineSessionId)
        assertEquals(PROCESS_SLOT, identity.processSlot)
        assertEquals(PROCESS_ID, identity.processId)
        assertEquals(VirtualServiceOperation.START.name, identity.operation)
        assertEquals(VirtualServiceOperation.START, identity.operationType)
        assertEquals(SERVICE_COMPONENT, identity.component)
        assertEquals(EngineServiceOperationLeaseState.ISSUED, registry.stateOf(identity))

        val constructorTypes = EngineServiceOperationLeaseIdentity::class.java.declaredConstructors
            .single()
            .parameterTypes
        assertTrue(constructorTypes.all { it.isPrimitive || it == String::class.java })

        val mutated = registry.authorize(identity.copy(component = "$ORIGIN_PACKAGE.OtherService"), PROCESS_ID)
        assertFalse(mutated.accepted)
        assertEquals("service_operation_lease_identity_mismatch", mutated.reason)
        assertFalse(registry.authorize(identity, PROCESS_ID + 1).accepted)

        val authorized = registry.authorize(identity, PROCESS_ID)
        val replayedAuthorization = registry.authorize(identity, PROCESS_ID)
        val committed = registry.commit(identity, PROCESS_ID)
        val repeatedCommit = registry.commit(identity, PROCESS_ID)

        assertTrue(authorized.accepted)
        assertEquals(EngineServiceOperationLeaseState.AUTHORIZED, authorized.state)
        assertFalse(replayedAuthorization.accepted)
        assertEquals("service_operation_lease_replayed", replayedAuthorization.reason)
        assertTrue(committed.accepted)
        assertFalse(committed.idempotent)
        assertTrue(repeatedCommit.accepted)
        assertTrue(repeatedCommit.idempotent)
        assertEquals(EngineServiceOperationLeaseState.COMMITTED, registry.stateOf(identity))
    }

    @Test
    fun `commit requires authorization and abort is terminal and idempotent`() {
        var tokenIndex = 0
        val registry = EngineServiceOperationLeaseRegistry(
            tokenFactory = { "service-lease-${++tokenIndex}" }
        )
        val identity = registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.BIND,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )

        val prematureCommit = registry.commit(identity, PROCESS_ID)
        val aborted = registry.abort(identity, PROCESS_ID)
        val repeatedAbort = registry.abort(identity, PROCESS_ID)
        val commitAfterAbort = registry.commit(identity, PROCESS_ID)

        assertFalse(prematureCommit.accepted)
        assertEquals("service_operation_lease_not_authorized", prematureCommit.reason)
        assertTrue(aborted.accepted)
        assertFalse(aborted.idempotent)
        assertTrue(repeatedAbort.accepted)
        assertTrue(repeatedAbort.idempotent)
        assertFalse(commitAfterAbort.accepted)
        assertEquals("service_operation_lease_already_aborted", commitAfterAbort.reason)
    }

    @Test
    fun `connection claim is one-time and remains commit eligible`() {
        val registry = EngineServiceOperationLeaseRegistry(
            clockNanos = { 100L },
            tokenFactory = { "service-lease-connection" }
        )
        val identity = registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.BIND,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertTrue(registry.authorize(identity, PROCESS_ID).accepted)

        val unclaimedCommit = registry.commit(identity, PROCESS_ID)
        val claimed = registry.claimForConnection(identity, PROCESS_ID)
        val replay = registry.claimForConnection(identity, PROCESS_ID)
        val failedCommit = registry.commit(identity, PROCESS_ID) { false }
        val stateAfterFailedCommit = registry.stateOf(identity)
        val committed = registry.commit(identity, PROCESS_ID)

        assertFalse(unclaimedCommit.accepted)
        assertEquals("service_operation_lease_connection_not_claimed", unclaimedCommit.reason)
        assertTrue(claimed.accepted)
        assertEquals(EngineServiceOperationLeaseState.CLAIMED, claimed.state)
        assertFalse(replay.accepted)
        assertEquals("service_operation_lease_connection_replayed", replay.reason)
        assertFalse(failedCommit.accepted)
        assertEquals(EngineServiceOperationLeaseState.CLAIMED, failedCommit.state)
        assertEquals(EngineServiceOperationLeaseState.CLAIMED, stateAfterFailedCommit)
        assertTrue(committed.accepted)
        assertEquals(EngineServiceOperationLeaseState.COMMITTED, committed.state)
    }

    @Test
    fun `commit action runs once and a failed action leaves lease retryable`() {
        val registry = EngineServiceOperationLeaseRegistry(tokenFactory = { "service-lease-action" })
        val identity = registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertTrue(registry.authorize(identity, PROCESS_ID).accepted)
        var attempts = 0

        val failed = registry.commit(identity, PROCESS_ID) {
            attempts += 1
            false
        }
        val committed = registry.commit(identity, PROCESS_ID) {
            attempts += 1
            true
        }
        val replay = registry.commit(identity, PROCESS_ID) {
            attempts += 1
            true
        }

        assertFalse(failed.accepted)
        assertEquals("service_operation_lease_commit_action_failed", failed.reason)
        assertTrue(committed.accepted)
        assertTrue(replay.accepted)
        assertTrue(replay.idempotent)
        assertEquals(2, attempts)
    }

    @Test
    fun `expiry fails closed before commit and removes the lease`() {
        var now = 0L
        val registry = EngineServiceOperationLeaseRegistry(
            clockNanos = { now },
            tokenFactory = { "expiring-service-lease" },
            ttlNanos = 10L
        )
        val identity = registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.STOP,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertTrue(registry.authorize(identity, PROCESS_ID).accepted)

        now = 10L
        val expired = registry.commit(identity, PROCESS_ID)

        assertFalse(expired.accepted)
        assertEquals(EngineServiceOperationLeaseState.EXPIRED, expired.state)
        assertEquals("service_operation_lease_expired", expired.reason)
        assertEquals(0, registry.size())
    }

    @Test
    fun `new generation revokes old leases and revoked generation cannot issue again`() {
        var tokenIndex = 0
        val registry = EngineServiceOperationLeaseRegistry(
            tokenFactory = { "generation-service-lease-${++tokenIndex}" }
        )
        val oldIdentity = registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        val nextRuntime = runtime().copy(
            runtimeEpoch = EPOCH + 1,
            engineSessionId = "engine-session-43",
            processSlot = NEXT_PROCESS_SLOT,
            processId = PROCESS_ID + 1,
            processName = NEXT_PROCESS_SLOT
        )
        val nextIdentity = registry.issue(
            nextRuntime,
            PROCESS_ID + 1,
            VirtualServiceOperation.START_FOREGROUND,
            SERVICE_COMPONENT,
            NEXT_PROCESS_SLOT
        )

        val stale = registry.authorize(oldIdentity, PROCESS_ID)
        assertFalse(stale.accepted)
        assertEquals("service_operation_lease_stale_generation", stale.reason)
        assertFailsWith<IllegalStateException> {
            registry.issue(
                runtime(),
                PROCESS_ID,
                VirtualServiceOperation.START,
                SERVICE_COMPONENT,
                PROCESS_SLOT
            )
        }

        assertTrue(registry.authorize(nextIdentity, PROCESS_ID + 1).accepted)
        assertEquals(
            1,
            registry.revokeGeneration(
                nextIdentity.instanceId,
                nextIdentity.runtimeEpoch,
                nextIdentity.engineSessionId
            )
        )
        val revoked = registry.commit(nextIdentity, PROCESS_ID + 1)
        assertFalse(revoked.accepted)
        assertEquals("service_operation_lease_generation_revoked", revoked.reason)
        assertFailsWith<IllegalStateException> {
            registry.issue(
                nextRuntime,
                PROCESS_ID + 1,
                VirtualServiceOperation.START,
                SERVICE_COMPONENT,
                NEXT_PROCESS_SLOT
            )
        }

        val newestRuntime = nextRuntime.copy(
            runtimeEpoch = EPOCH + 2,
            engineSessionId = "engine-session-44"
        )
        val newest = registry.issue(
            newestRuntime,
            PROCESS_ID + 1,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            NEXT_PROCESS_SLOT
        )
        assertTrue(registry.authorize(newest, PROCESS_ID + 1).accepted)
    }

    @Test
    fun `new runtime epoch cannot reuse the previous engine session`() {
        var tokenIndex = 0
        val registry = EngineServiceOperationLeaseRegistry(
            tokenFactory = { "session-service-lease-${++tokenIndex}" }
        )
        registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )

        assertFailsWith<IllegalStateException> {
            registry.issue(
                runtime().copy(runtimeEpoch = EPOCH + 1),
                PROCESS_ID,
                VirtualServiceOperation.START,
                SERVICE_COMPONENT,
                PROCESS_SLOT
            )
        }
    }

    @Test
    fun `issue rejects non authoritative process bindings and non live runtimes`() {
        val registry = EngineServiceOperationLeaseRegistry(tokenFactory = { "strict-service-lease" })

        assertFailsWith<IllegalArgumentException> {
            registry.issue(runtime(), PROCESS_ID + 1, VirtualServiceOperation.START, SERVICE_COMPONENT, PROCESS_SLOT)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.issue(runtime(), PROCESS_ID, VirtualServiceOperation.START, SERVICE_COMPONENT, NEXT_PROCESS_SLOT)
        }
        assertFailsWith<IllegalArgumentException> {
            registry.issue(
                runtime().copy(processId = null),
                PROCESS_ID,
                VirtualServiceOperation.START,
                SERVICE_COMPONENT,
                PROCESS_SLOT
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.issue(
                runtime().copy(state = VirtualRuntimeState.DEAD),
                PROCESS_ID,
                VirtualServiceOperation.START,
                SERVICE_COMPONENT,
                PROCESS_SLOT
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.issue(runtime(), PROCESS_ID, VirtualServiceOperation.START, " ", PROCESS_SLOT)
        }
    }

    @Test
    fun `concurrent commit has one transition and idempotent retries`() {
        val registry = EngineServiceOperationLeaseRegistry(tokenFactory = { "concurrent-service-lease" })
        val identity = registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.UNBIND,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        assertTrue(registry.authorize(identity, PROCESS_ID).accepted)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val decisions = (0 until 32).map {
                executor.submit<EngineServiceOperationLeaseDecision> {
                    registry.commit(identity, PROCESS_ID)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }

            assertTrue(decisions.all { it.accepted })
            assertEquals(1, decisions.count { !it.idempotent })
            assertEquals(31, decisions.count { it.idempotent })
            assertEquals(setOf(EngineServiceOperationLeaseState.COMMITTED), decisions.map { it.state }.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `instance revoke removes all leases and leaves generation revoked`() {
        var tokenIndex = 0
        val registry = EngineServiceOperationLeaseRegistry(
            tokenFactory = { "instance-service-lease-${++tokenIndex}" }
        )
        val first = registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.START,
            SERVICE_COMPONENT,
            PROCESS_SLOT
        )
        registry.issue(
            runtime(),
            PROCESS_ID,
            VirtualServiceOperation.BIND,
            "$ORIGIN_PACKAGE.BoundService",
            PROCESS_SLOT
        )

        assertEquals(2, registry.revokeInstance(INSTANCE_ID))
        val revoked = registry.authorize(first, PROCESS_ID)
        assertFalse(revoked.accepted)
        assertEquals("service_operation_lease_generation_revoked", revoked.reason)
        assertFailsWith<IllegalStateException> {
            registry.issue(
                runtime(),
                PROCESS_ID,
                VirtualServiceOperation.START,
                SERVICE_COMPONENT,
                PROCESS_SLOT
            )
        }
    }

    private fun runtime() = VirtualInstanceRuntime(
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
        evidenceSessionId = "evidence-42",
        runtimeEpoch = EPOCH,
        engineSessionId = SESSION_ID,
        processId = PROCESS_ID,
        processName = PROCESS_SLOT,
        state = VirtualRuntimeState.RUNNING
    )

    private companion object {
        const val INSTANCE_ID = "instance-service-lease"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-service-lease"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v0"
        const val NEXT_PROCESS_SLOT = "$HOST_PACKAGE:v1"
        const val SERVICE_COMPONENT = "$ORIGIN_PACKAGE.SyncService"
        const val PROCESS_ID = 4200
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
    }
}
