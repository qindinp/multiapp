package com.multiapp.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineActivityOperationTransactionTest {
    @Test
    fun `transaction identity is primitive only and binds the complete operation`() {
        val transaction = transaction()

        assertEquals("activity-transaction-1", transaction.transactionToken)
        assertEquals(INSTANCE_ID, transaction.instanceId)
        assertEquals(EPOCH, transaction.runtimeEpoch)
        assertEquals(SESSION_ID, transaction.engineSessionId)
        assertEquals(PROCESS_SLOT, transaction.processSlot)
        assertEquals(PROCESS_ID, transaction.processId)
        assertEquals(OPERATION, transaction.operation)
        assertEquals(TARGET_TOKEN, transaction.targetActivityToken)
        assertEquals(PAYLOAD_FINGERPRINT, transaction.payloadFingerprint)
        assertTrue(
            EngineActivityOperationTransaction::class.java.declaredConstructors
                .single()
                .parameterTypes
                .all { it.isPrimitive || it == String::class.java }
        )
        assertEquals(
            listOf("ISSUE", "COMMIT", "ABORT", "REVOKE"),
            EngineActivityOperationTransactionState.entries.map { it.name }
        )
    }

    @Test
    fun `transaction rejects invalid primitive identity fields`() {
        assertFailsWith<IllegalArgumentException> { transaction(instanceId = " ") }
        assertFailsWith<IllegalArgumentException> { transaction(runtimeEpoch = 0L) }
        assertFailsWith<IllegalArgumentException> { transaction(processId = 0) }
        assertFailsWith<IllegalArgumentException> { transaction(operation = "") }
        assertFailsWith<IllegalArgumentException> { transaction(targetActivityToken = "target\u0000token") }
        assertFailsWith<IllegalArgumentException> { transaction(payloadFingerprint = " payload ") }
        assertFailsWith<IllegalArgumentException> {
            transaction(issuedAtNanos = 100L, expiresAtNanos = 100L)
        }
    }

    private fun transaction(
        instanceId: String = INSTANCE_ID,
        runtimeEpoch: Long = EPOCH,
        processId: Int = PROCESS_ID,
        operation: String = OPERATION,
        targetActivityToken: String = TARGET_TOKEN,
        payloadFingerprint: String = PAYLOAD_FINGERPRINT,
        issuedAtNanos: Long = 100L,
        expiresAtNanos: Long = 200L
    ) = EngineActivityOperationTransaction(
        transactionToken = "activity-transaction-1",
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = SESSION_ID,
        processSlot = PROCESS_SLOT,
        processId = processId,
        operation = operation,
        targetActivityToken = targetActivityToken,
        payloadFingerprint = payloadFingerprint,
        issuedAtNanos = issuedAtNanos,
        expiresAtNanos = expiresAtNanos
    )

    private companion object {
        const val INSTANCE_ID = "instance-activity-transaction"
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
        const val PROCESS_SLOT = "com.multiapp.app:v0"
        const val PROCESS_ID = 4200
        const val OPERATION = "finish"
        const val TARGET_TOKEN = "activity-token-42"
        const val PAYLOAD_FINGERPRINT = "sha256:payload-42"
    }
}
