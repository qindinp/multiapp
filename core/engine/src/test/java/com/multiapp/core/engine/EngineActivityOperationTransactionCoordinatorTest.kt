package com.multiapp.core.engine

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineActivityOperationTransactionCoordinatorTest {
    @Test
    fun `commit binds full payload and exact replay is idempotent`() {
        val coordinator = coordinator()
        val transaction = issue(coordinator)

        val wrongPayload = coordinator.commit(
            transaction.copy(payloadFingerprint = "sha256:different"),
            PROCESS_ID
        )
        val wrongSlot = coordinator.commit(transaction.copy(processSlot = NEXT_PROCESS_SLOT), PROCESS_ID)
        val wrongTarget = coordinator.commit(
            transaction.copy(targetActivityToken = "activity-token-other"),
            PROCESS_ID
        )
        val wrongPid = coordinator.commit(transaction, PROCESS_ID + 1)
        val committed = coordinator.commit(transaction, PROCESS_ID)
        val repeated = coordinator.commit(transaction, PROCESS_ID)

        assertFalse(wrongPayload.accepted)
        assertEquals("activity_operation_transaction_identity_mismatch", wrongPayload.reason)
        assertFalse(wrongSlot.accepted)
        assertFalse(wrongTarget.accepted)
        assertFalse(wrongPid.accepted)
        assertEquals("activity_operation_transaction_process_id_mismatch", wrongPid.reason)
        assertTrue(committed.accepted)
        assertFalse(committed.idempotent)
        assertEquals(EngineActivityOperationTransactionState.COMMIT, committed.state)
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
    }

    @Test
    fun `abort is terminal and expiry revokes fail closed`() {
        var now = 0L
        var tokenIndex = 0
        val coordinator = EngineActivityOperationTransactionCoordinator(
            clockNanos = { now },
            tokenFactory = { "activity-transaction-${++tokenIndex}" },
            ttlNanos = 10L
        )
        val abortedTransaction = issue(coordinator)

        val aborted = coordinator.abort(abortedTransaction, PROCESS_ID)
        val repeatedAbort = coordinator.abort(abortedTransaction, PROCESS_ID)
        val commitAfterAbort = coordinator.commit(abortedTransaction, PROCESS_ID)

        assertTrue(aborted.accepted)
        assertTrue(repeatedAbort.accepted)
        assertTrue(repeatedAbort.idempotent)
        assertFalse(commitAfterAbort.accepted)
        assertEquals(EngineActivityOperationTransactionState.ABORT, commitAfterAbort.state)

        val expiring = issue(
            coordinator,
            operation = "mark-state",
            targetActivityToken = "activity-token-expiring"
        )
        now = 10L
        val expired = coordinator.commit(expiring, PROCESS_ID)
        assertFalse(expired.accepted)
        assertEquals(EngineActivityOperationTransactionState.REVOKE, expired.state)
        assertEquals("activity_operation_transaction_expired", expired.reason)
    }

    @Test
    fun `commit action executes once and failed action remains retryable`() {
        val coordinator = coordinator()
        val transaction = issue(coordinator)
        var attempts = 0

        val failed = coordinator.commit(transaction, PROCESS_ID) {
            attempts += 1
            false
        }
        val committed = coordinator.commit(transaction, PROCESS_ID) {
            attempts += 1
            true
        }
        val replay = coordinator.commit(transaction, PROCESS_ID) {
            attempts += 1
            true
        }

        assertFalse(failed.accepted)
        assertEquals("activity_operation_transaction_commit_action_failed", failed.reason)
        assertTrue(committed.accepted)
        assertTrue(replay.idempotent)
        assertEquals(2, attempts)
    }

    @Test
    fun `new generation revokes old transaction and stale generation cannot issue`() {
        var tokenIndex = 0
        val coordinator = EngineActivityOperationTransactionCoordinator(
            tokenFactory = { "generation-activity-transaction-${++tokenIndex}" }
        )
        val old = issue(coordinator)
        val next = issue(
            coordinator,
            runtimeEpoch = EPOCH + 1,
            engineSessionId = "engine-session-43",
            processSlot = NEXT_PROCESS_SLOT,
            processId = PROCESS_ID + 1,
            targetActivityToken = "activity-token-43"
        )

        val staleCommit = coordinator.commit(old, PROCESS_ID)
        assertFalse(staleCommit.accepted)
        assertEquals(EngineActivityOperationTransactionState.REVOKE, staleCommit.state)
        assertEquals("activity_operation_transaction_stale_generation", staleCommit.reason)
        assertFailsWith<IllegalStateException> { issue(coordinator) }
        assertTrue(coordinator.commit(next, PROCESS_ID + 1).accepted)
    }

    @Test
    fun `new runtime epoch cannot reuse engine session`() {
        var tokenIndex = 0
        val coordinator = EngineActivityOperationTransactionCoordinator(
            tokenFactory = { "session-activity-transaction-${++tokenIndex}" }
        )
        issue(coordinator)

        assertFailsWith<IllegalStateException> {
            issue(coordinator, runtimeEpoch = EPOCH + 1)
        }
    }

    @Test
    fun `generation and instance revocation block the revoked generation`() {
        var tokenIndex = 0
        val coordinator = EngineActivityOperationTransactionCoordinator(
            tokenFactory = { "revoked-activity-transaction-${++tokenIndex}" }
        )
        val first = issue(coordinator)
        issue(
            coordinator,
            operation = "set-result",
            targetActivityToken = "activity-token-result"
        )

        assertEquals(2, coordinator.revokeGeneration(INSTANCE_ID, EPOCH, SESSION_ID))
        val revoked = coordinator.commit(first, PROCESS_ID)
        assertFalse(revoked.accepted)
        assertEquals("activity_operation_transaction_generation_revoked", revoked.reason)
        assertFailsWith<IllegalStateException> { issue(coordinator) }

        val next = issue(
            coordinator,
            runtimeEpoch = EPOCH + 1,
            engineSessionId = "engine-session-43",
            processSlot = NEXT_PROCESS_SLOT,
            processId = PROCESS_ID + 1,
            targetActivityToken = "activity-token-43"
        )
        assertEquals(1, coordinator.revokeInstance(INSTANCE_ID))
        assertFalse(coordinator.commit(next, PROCESS_ID + 1).accepted)
        assertFailsWith<IllegalStateException> {
            issue(
                coordinator,
                runtimeEpoch = EPOCH + 1,
                engineSessionId = "engine-session-43",
                processSlot = NEXT_PROCESS_SLOT,
                processId = PROCESS_ID + 1,
                targetActivityToken = "activity-token-after-revoke"
            )
        }
    }

    @Test
    fun `transaction tokens are one time even after terminal state`() {
        val coordinator = EngineActivityOperationTransactionCoordinator(
            tokenFactory = { "fixed-one-time-token" }
        )
        val transaction = issue(coordinator)
        assertTrue(coordinator.commit(transaction, PROCESS_ID).accepted)

        assertFailsWith<IllegalStateException> {
            issue(
                coordinator,
                operation = "finish-second",
                targetActivityToken = "activity-token-second"
            )
        }
    }

    @Test
    fun `concurrent commit performs one transition and all exact retries succeed`() {
        val coordinator = coordinator()
        val transaction = issue(coordinator)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val decisions = (0 until 32).map {
                executor.submit<EngineActivityOperationTransactionDecision> {
                    coordinator.commit(transaction, PROCESS_ID)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }

            assertTrue(decisions.all { it.accepted })
            assertEquals(1, decisions.count { !it.idempotent })
            assertEquals(31, decisions.count { it.idempotent })
            assertEquals(
                setOf(EngineActivityOperationTransactionState.COMMIT),
                decisions.map { it.state }.toSet()
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun coordinator() = EngineActivityOperationTransactionCoordinator(
        clockNanos = { 100L },
        tokenFactory = { "activity-transaction-1" }
    )

    private fun issue(
        coordinator: EngineActivityOperationTransactionCoordinator,
        runtimeEpoch: Long = EPOCH,
        engineSessionId: String = SESSION_ID,
        processSlot: String = PROCESS_SLOT,
        processId: Int = PROCESS_ID,
        operation: String = OPERATION,
        targetActivityToken: String = TARGET_TOKEN
    ) = coordinator.issue(
        instanceId = INSTANCE_ID,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = processSlot,
        processId = processId,
        operation = operation,
        targetActivityToken = targetActivityToken,
        payloadFingerprint = PAYLOAD_FINGERPRINT
    )

    private companion object {
        const val INSTANCE_ID = "instance-activity-transaction"
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
        const val PROCESS_SLOT = "com.multiapp.app:v0"
        const val NEXT_PROCESS_SLOT = "com.multiapp.app:v1"
        const val PROCESS_ID = 4200
        const val OPERATION = "finish"
        const val TARGET_TOKEN = "activity-token-42"
        const val PAYLOAD_FINGERPRINT = "sha256:payload-42"
    }
}
