package com.multiapp.core.engine

/**
 * Primitive-only identity for one engine-owned Activity operation.
 *
 * [payloadFingerprint] is the caller's stable fingerprint of the complete operation payload. The
 * coordinator compares this entire value object on every transition, so a token cannot be reused
 * for another target or payload.
 */
data class EngineActivityOperationTransaction(
    val transactionToken: String,
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int,
    val operation: String,
    val targetActivityToken: String,
    val payloadFingerprint: String,
    val issuedAtNanos: Long,
    val expiresAtNanos: Long
) {
    init {
        validateActivityTransactionText("transactionToken", transactionToken)
        validateActivityTransactionText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateActivityTransactionText("engineSessionId", engineSessionId)
        validateActivityTransactionText("processSlot", processSlot)
        require(processId > 0) { "processId must be positive" }
        validateActivityTransactionText("operation", operation)
        validateActivityTransactionText("targetActivityToken", targetActivityToken)
        validateActivityTransactionText("payloadFingerprint", payloadFingerprint)
        require(expiresAtNanos != issuedAtNanos) {
            "transaction expiry must differ from issue time"
        }
    }
}

enum class EngineActivityOperationTransactionState {
    ISSUE,
    COMMIT,
    ABORT,
    REVOKE
}

data class EngineActivityOperationTransactionDecision(
    val accepted: Boolean,
    val idempotent: Boolean,
    val state: EngineActivityOperationTransactionState,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

internal fun validateActivityTransactionText(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value == value.trim()) { "$name must be trimmed" }
    require(value.length <= MAX_ACTIVITY_TRANSACTION_TEXT_LENGTH) {
        "$name must be at most $MAX_ACTIVITY_TRANSACTION_TEXT_LENGTH characters"
    }
    require('\u0000' !in value) { "$name must not contain NUL" }
}

private const val MAX_ACTIVITY_TRANSACTION_TEXT_LENGTH = 512
