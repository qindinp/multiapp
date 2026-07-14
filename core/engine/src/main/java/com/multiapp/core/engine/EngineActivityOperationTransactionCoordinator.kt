package com.multiapp.core.engine

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Thread-safe owner of generation-bound Activity operation transactions.
 *
 * The coordinator deliberately accepts only primitive identity fields. Integration code must pass
 * values obtained from the engine's authoritative runtime record.
 */
class EngineActivityOperationTransactionCoordinator(
    private val clockNanos: () -> Long = System::nanoTime,
    private val tokenFactory: () -> String = ::secureActivityTransactionToken,
    private val ttlNanos: Long = DEFAULT_TTL_NANOS
) {
    private val records = linkedMapOf<String, TransactionRecord>()
    private val generations = linkedMapOf<String, Generation>()
    private val allocatedTokens = linkedSetOf<String>()

    init {
        require(ttlNanos > 0L) { "ttlNanos must be positive" }
    }

    @Synchronized
    fun issue(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int,
        operation: String,
        targetActivityToken: String,
        payloadFingerprint: String
    ): EngineActivityOperationTransaction {
        validateIssueFields(
            instanceId,
            runtimeEpoch,
            engineSessionId,
            processSlot,
            processId,
            operation,
            targetActivityToken,
            payloadFingerprint
        )
        val requestedGeneration = Generation(
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            processId = processId
        )
        val currentGeneration = generations[instanceId]
        when {
            currentGeneration == null -> Unit
            runtimeEpoch < currentGeneration.runtimeEpoch -> {
                error("cannot issue an Activity operation transaction for a stale runtimeEpoch")
            }
            runtimeEpoch == currentGeneration.runtimeEpoch -> {
                check(requestedGeneration.hasSameBinding(currentGeneration)) {
                    "Activity operation transaction binding changed without a runtimeEpoch advance"
                }
                check(!currentGeneration.revoked) {
                    "cannot issue an Activity operation transaction for a revoked generation"
                }
            }
            else -> check(currentGeneration.engineSessionId != engineSessionId) {
                "Activity operation transaction engineSessionId must change with runtimeEpoch"
            }
        }

        val now = clockNanos()
        pruneExpiredRecordsLocked(now)
        val transactionToken = uniqueTokenLocked()
        if (currentGeneration == null || runtimeEpoch > currentGeneration.runtimeEpoch) {
            revokeRecordsLocked(instanceId) { true }
            generations[instanceId] = requestedGeneration
        }
        val transaction = EngineActivityOperationTransaction(
            transactionToken = transactionToken,
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            processId = processId,
            operation = operation,
            targetActivityToken = targetActivityToken,
            payloadFingerprint = payloadFingerprint,
            issuedAtNanos = now,
            expiresAtNanos = saturatingAdd(now, ttlNanos)
        )
        records[transaction.transactionToken] = TransactionRecord(transaction)
        return transaction
    }

    @Synchronized
    fun commit(
        transaction: EngineActivityOperationTransaction,
        callingPid: Int
    ): EngineActivityOperationTransactionDecision = commit(transaction, callingPid) { true }

    @Synchronized
    fun commit(
        transaction: EngineActivityOperationTransaction,
        callingPid: Int,
        commitAction: () -> Boolean
    ): EngineActivityOperationTransactionDecision {
        val lookup = lookupForTransition(transaction, callingPid)
        val record = lookup.record ?: return checkNotNull(lookup.rejection)
        return when (record.state) {
            EngineActivityOperationTransactionState.ISSUE -> {
                if (!runCatching(commitAction).getOrDefault(false)) {
                    return rejected(
                        EngineActivityOperationTransactionState.ISSUE,
                        "activity_operation_transaction_commit_action_failed"
                    )
                }
                record.state = EngineActivityOperationTransactionState.COMMIT
                accepted(record.state, "activity_operation_transaction_committed")
            }
            EngineActivityOperationTransactionState.COMMIT -> accepted(
                state = record.state,
                reason = "activity_operation_transaction_commit_idempotent",
                idempotent = true
            )
            EngineActivityOperationTransactionState.ABORT -> rejected(
                record.state,
                "activity_operation_transaction_already_aborted"
            )
            EngineActivityOperationTransactionState.REVOKE -> rejected(
                record.state,
                "activity_operation_transaction_revoked"
            )
        }
    }

    @Synchronized
    fun abort(
        transaction: EngineActivityOperationTransaction,
        callingPid: Int
    ): EngineActivityOperationTransactionDecision {
        val lookup = lookupForTransition(transaction, callingPid)
        val record = lookup.record ?: return checkNotNull(lookup.rejection)
        return when (record.state) {
            EngineActivityOperationTransactionState.ISSUE -> {
                record.state = EngineActivityOperationTransactionState.ABORT
                accepted(record.state, "activity_operation_transaction_aborted")
            }
            EngineActivityOperationTransactionState.ABORT -> accepted(
                state = record.state,
                reason = "activity_operation_transaction_abort_idempotent",
                idempotent = true
            )
            EngineActivityOperationTransactionState.COMMIT -> rejected(
                record.state,
                "activity_operation_transaction_already_committed"
            )
            EngineActivityOperationTransactionState.REVOKE -> rejected(
                record.state,
                "activity_operation_transaction_revoked"
            )
        }
    }

    @Synchronized
    fun revokeGeneration(instanceId: String, runtimeEpoch: Long, engineSessionId: String): Int {
        validateActivityTransactionText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateActivityTransactionText("engineSessionId", engineSessionId)
        val revoked = revokeRecordsLocked(instanceId) { transaction ->
            transaction.runtimeEpoch == runtimeEpoch &&
                transaction.engineSessionId == engineSessionId
        }
        val current = generations[instanceId]
        when {
            current == null || runtimeEpoch > current.runtimeEpoch -> {
                generations[instanceId] = Generation(
                    runtimeEpoch = runtimeEpoch,
                    engineSessionId = engineSessionId,
                    processSlot = null,
                    processId = null,
                    revoked = true
                )
            }
            runtimeEpoch == current.runtimeEpoch && engineSessionId == current.engineSessionId -> {
                current.revoked = true
            }
        }
        return revoked
    }

    @Synchronized
    fun revokeInstance(instanceId: String): Int {
        validateActivityTransactionText("instanceId", instanceId)
        val revoked = revokeRecordsLocked(instanceId) { true }
        generations[instanceId]?.revoked = true
        return revoked
    }

    @Synchronized
    internal fun stateOf(
        transaction: EngineActivityOperationTransaction
    ): EngineActivityOperationTransactionState? {
        val record = records[transaction.transactionToken] ?: return null
        expireLocked(record, clockNanos())
        return record.state.takeIf { record.transaction == transaction }
    }

    private fun lookupForTransition(
        transaction: EngineActivityOperationTransaction,
        callingPid: Int
    ): TransactionLookup {
        val record = records[transaction.transactionToken]
            ?: return TransactionLookup(rejection = missingTransactionDecision(transaction))
        if (callingPid <= 0 || callingPid != record.transaction.processId) {
            return TransactionLookup(rejection = rejected(
                record.state,
                "activity_operation_transaction_process_id_mismatch"
            ))
        }
        if (record.transaction != transaction) {
            return TransactionLookup(rejection = rejected(
                record.state,
                "activity_operation_transaction_identity_mismatch"
            ))
        }
        if (expireLocked(record, clockNanos())) {
            return TransactionLookup(rejection = rejected(
                EngineActivityOperationTransactionState.REVOKE,
                "activity_operation_transaction_expired"
            ))
        }
        val generation = generations[transaction.instanceId]
        if (generation == null || generation.isNewerThan(transaction)) {
            record.state = EngineActivityOperationTransactionState.REVOKE
            return TransactionLookup(rejection = rejected(
                record.state,
                "activity_operation_transaction_stale_generation"
            ))
        }
        if (!generation.matches(transaction) || generation.revoked) {
            record.state = EngineActivityOperationTransactionState.REVOKE
            return TransactionLookup(rejection = rejected(
                record.state,
                "activity_operation_transaction_generation_revoked"
            ))
        }
        return TransactionLookup(record = record)
    }

    private fun missingTransactionDecision(
        transaction: EngineActivityOperationTransaction
    ): EngineActivityOperationTransactionDecision {
        val generation = generations[transaction.instanceId]
        val reason = when {
            generation?.isNewerThan(transaction) == true -> {
                "activity_operation_transaction_stale_generation"
            }
            generation?.matches(transaction) == true && generation.revoked -> {
                "activity_operation_transaction_generation_revoked"
            }
            else -> "activity_operation_transaction_not_found"
        }
        return rejected(EngineActivityOperationTransactionState.REVOKE, reason)
    }

    private fun revokeRecordsLocked(
        instanceId: String,
        predicate: (EngineActivityOperationTransaction) -> Boolean
    ): Int {
        var revoked = 0
        records.values.forEach { record ->
            if (
                record.transaction.instanceId == instanceId &&
                predicate(record.transaction) &&
                record.state != EngineActivityOperationTransactionState.REVOKE
            ) {
                record.state = EngineActivityOperationTransactionState.REVOKE
                revoked++
            }
        }
        return revoked
    }

    private fun expireLocked(record: TransactionRecord, now: Long): Boolean {
        if (
            record.state != EngineActivityOperationTransactionState.REVOKE &&
            now - record.transaction.expiresAtNanos >= 0L
        ) {
            record.state = EngineActivityOperationTransactionState.REVOKE
        }
        return record.state == EngineActivityOperationTransactionState.REVOKE &&
            now - record.transaction.expiresAtNanos >= 0L
    }

    private fun uniqueTokenLocked(): String {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val token = tokenFactory().takeIf { it.isNotBlank() }
                ?: error("Activity operation transaction token factory returned blank")
            validateActivityTransactionText("transactionToken", token)
            if (token !in records && allocatedTokens.add(token)) {
                trimTokenTombstonesLocked()
                return token
            }
        }
        error("unable to allocate a unique Activity operation transaction token")
    }

    private fun pruneExpiredRecordsLocked(now: Long) {
        records.entries.removeAll { (_, record) ->
            now - record.transaction.expiresAtNanos >= 0L
        }
        trimTokenTombstonesLocked()
    }

    private fun trimTokenTombstonesLocked() {
        while (allocatedTokens.size > MAX_TOKEN_TOMBSTONES) {
            val removable = allocatedTokens.firstOrNull { token -> token !in records } ?: return
            allocatedTokens.remove(removable)
        }
    }

    private fun validateIssueFields(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int,
        operation: String,
        targetActivityToken: String,
        payloadFingerprint: String
    ) {
        validateActivityTransactionText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateActivityTransactionText("engineSessionId", engineSessionId)
        validateActivityTransactionText("processSlot", processSlot)
        require(processId > 0) { "processId must be positive" }
        validateActivityTransactionText("operation", operation)
        validateActivityTransactionText("targetActivityToken", targetActivityToken)
        validateActivityTransactionText("payloadFingerprint", payloadFingerprint)
    }

    private data class TransactionRecord(
        val transaction: EngineActivityOperationTransaction,
        var state: EngineActivityOperationTransactionState = EngineActivityOperationTransactionState.ISSUE
    )

    private data class Generation(
        val runtimeEpoch: Long,
        val engineSessionId: String,
        val processSlot: String?,
        val processId: Int?,
        var revoked: Boolean = false
    ) {
        fun hasSameBinding(other: Generation): Boolean =
            engineSessionId == other.engineSessionId &&
                processSlot == other.processSlot &&
                processId == other.processId

        fun matches(transaction: EngineActivityOperationTransaction): Boolean =
            runtimeEpoch == transaction.runtimeEpoch &&
                engineSessionId == transaction.engineSessionId &&
                (processSlot == null || processSlot == transaction.processSlot) &&
                (processId == null || processId == transaction.processId)

        fun isNewerThan(transaction: EngineActivityOperationTransaction): Boolean =
            runtimeEpoch > transaction.runtimeEpoch ||
                runtimeEpoch == transaction.runtimeEpoch &&
                engineSessionId != transaction.engineSessionId
    }

    private data class TransactionLookup(
        val record: TransactionRecord? = null,
        val rejection: EngineActivityOperationTransactionDecision? = null
    )

    private companion object {
        const val MAX_TOKEN_ATTEMPTS = 8
        const val MAX_TOKEN_TOMBSTONES = 4096
        val DEFAULT_TTL_NANOS: Long = TimeUnit.MINUTES.toNanos(2)
    }
}

private fun accepted(
    state: EngineActivityOperationTransactionState,
    reason: String,
    idempotent: Boolean = false
) = EngineActivityOperationTransactionDecision(
    accepted = true,
    idempotent = idempotent,
    state = state,
    reason = reason
)

private fun rejected(
    state: EngineActivityOperationTransactionState,
    reason: String
) = EngineActivityOperationTransactionDecision(
    accepted = false,
    idempotent = false,
    state = state,
    reason = reason
)

private fun secureActivityTransactionToken(): String {
    val bytes = ByteArray(32)
    ActivityTransactionSecureRandom.instance.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private object ActivityTransactionSecureRandom {
    val instance = SecureRandom()
}
