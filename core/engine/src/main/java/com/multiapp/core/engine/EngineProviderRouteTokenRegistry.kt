package com.multiapp.core.engine

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** Complete engine-owned process identity bound to one Provider route token. */
data class EngineProviderRouteProcessIdentity(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val processId: Int,
    val effectiveGuestProcessName: String,
    val processEpoch: Long? = null,
    val clientSessionId: String? = null,
    val processStartTicks: Long? = null
) {
    val isComponentProcessGenerationBound: Boolean
        get() = processEpoch != null

    init {
        validateProviderRouteText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateProviderRouteText("engineSessionId", engineSessionId)
        validateProviderRouteText("processSlot", processSlot)
        require(processId > 0) { "processId must be positive" }
        validateProviderRouteText("effectiveGuestProcessName", effectiveGuestProcessName)

        val componentGenerationFieldCount = listOf(
            processEpoch,
            clientSessionId,
            processStartTicks
        ).count { it != null }
        require(componentGenerationFieldCount == 0 || componentGenerationFieldCount == 3) {
            "processEpoch, clientSessionId, and processStartTicks must be all present or all absent"
        }
        processEpoch?.let { require(it > 0L) { "processEpoch must be positive" } }
        clientSessionId?.let { validateProviderRouteText("clientSessionId", it) }
        processStartTicks?.let { require(it > 0L) { "processStartTicks must be positive" } }
    }
}

/** Engine-owned target identity that is safe to allocate before Android starts the stub process. */
data class EngineProviderRouteTargetBinding(
    val instanceId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val effectiveGuestProcessName: String
) {
    init {
        validateProviderRouteText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateProviderRouteText("engineSessionId", engineSessionId)
        validateProviderRouteText("processSlot", processSlot)
        validateProviderRouteText("effectiveGuestProcessName", effectiveGuestProcessName)
    }
}

/** Kernel-observed process generation used to complete a pending target binding. */
data class EngineProviderRouteTargetProcessObservation(
    val binding: EngineProviderRouteTargetBinding,
    val processId: Int,
    val processStartTicks: Long
) {
    init {
        require(processId > 0) { "processId must be positive" }
        require(processStartTicks > 0L) { "processStartTicks must be positive" }
    }
}

data class EngineProviderRouteBoundTargetProcess(
    val binding: EngineProviderRouteTargetBinding,
    val processId: Int,
    val processEpoch: Long,
    val processStartTicks: Long
)

enum class EngineProviderRouteTokenConsumeStatus {
    CONSUMED,
    MISSING,
    TOKEN_NOT_FOUND,
    REPLAYED,
    EXPIRED,
    TARGET_INSTANCE_MISMATCH,
    AUTHORITY_MISMATCH,
    OPERATION_MISMATCH,
    PROCESS_SLOT_MISMATCH,
    TARGET_GENERATION_MISMATCH,
    CALLER_GENERATION_STALE
}

data class EngineProviderRouteTokenConsumeResult(
    val status: EngineProviderRouteTokenConsumeStatus,
    val route: EngineProviderRouteToken? = null
) {
    val consumed: Boolean
        get() = status == EngineProviderRouteTokenConsumeStatus.CONSUMED

    init {
        require((status == EngineProviderRouteTokenConsumeStatus.CONSUMED) == (route != null)) {
            "route must be present only for a consumed token"
        }
    }
}

/**
 * Thread-safe engine authority for one-time Provider route tokens.
 *
 * Active and tombstone maps are keyed by a SHA-256 digest. The plaintext token is returned to the
 * caller but is never retained by this registry. Expiry uses a monotonic clock; wall clock time is
 * used only to populate the existing public [EngineProviderRouteToken.expiresAtMillis] contract.
 */
class EngineProviderRouteTokenRegistry(
    private val clockNanos: () -> Long = System::nanoTime,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val tokenFactory: () -> String = ::secureProviderRouteToken,
    private val ttlNanos: Long = DEFAULT_TTL_NANOS,
    private val maxActiveTokens: Int = DEFAULT_MAX_ACTIVE_TOKENS,
    private val maxTombstones: Int = DEFAULT_MAX_TOMBSTONES
) {
    private val lock = Any()
    private val nextProviderProcessEpoch = AtomicLong(0L)
    private val activeByDigest = linkedMapOf<String, RouteRecord>()
    private val consumedByDigest = linkedMapOf<String, RouteRecord>()
    private val tombstonesByDigest = linkedMapOf<String, EngineProviderRouteTokenConsumeStatus>()

    init {
        require(ttlNanos > 0L) { "ttlNanos must be positive" }
        require(maxActiveTokens > 0) { "maxActiveTokens must be positive" }
        require(maxTombstones > 0) { "maxTombstones must be positive" }
    }

    fun issue(
        authoritativeCallerIdentity: EngineProviderRouteProcessIdentity,
        authoritativeTargetIdentity: EngineProviderRouteProcessIdentity,
        authority: String,
        operation: String
    ): EngineProviderRouteToken = issue(
        authoritativeCallerIdentity = authoritativeCallerIdentity,
        authoritativeTargetBinding = authoritativeTargetIdentity.toTargetBinding(),
        authoritativeTargetIdentity = authoritativeTargetIdentity,
        targetLaunchTicket = null,
        authority = authority,
        operation = operation
    )

    fun issue(
        authoritativeCallerIdentity: EngineProviderRouteProcessIdentity,
        authoritativeTargetBinding: EngineProviderRouteTargetBinding,
        authoritativeTargetIdentity: EngineProviderRouteProcessIdentity? = null,
        targetLaunchTicket: EngineComponentProcessLaunchTicket? = null,
        authority: String,
        operation: String
    ): EngineProviderRouteToken {
        validateProviderAuthority(authority)
        val normalizedOperation = normalizeOperation(operation)
        validateProviderRouteText("operation", normalizedOperation)
        require(
            authoritativeTargetIdentity == null ||
                authoritativeTargetIdentity.toTargetBinding() == authoritativeTargetBinding
        ) { "target identity must match the pending target binding" }
        require(authoritativeTargetIdentity == null || targetLaunchTicket == null) {
            "live target identity must not retain a launch ticket"
        }
        require(
            targetLaunchTicket == null || (
                targetLaunchTicket.instanceId == authoritativeTargetBinding.instanceId &&
                    targetLaunchTicket.processSlot == authoritativeTargetBinding.processSlot &&
                    targetLaunchTicket.effectiveGuestProcessName ==
                    authoritativeTargetBinding.effectiveGuestProcessName
                )
        ) { "target launch ticket must match the pending target binding" }

        return synchronized(lock) {
            val nowNanos = clockNanos()
            pruneExpiredLocked(nowNanos)
            check(activeByDigest.size < maxActiveTokens) {
                "Provider route token active capacity exhausted"
            }

            val allocated = uniqueTokenLocked()
            val expiresAtNanos = nowNanos + ttlNanos
            val expiresAtMillis = saturatingAdd(
                wallClockMillis(),
                TimeUnit.NANOSECONDS.toMillis(ttlNanos).coerceAtLeast(1L)
            )
            val record = RouteRecord(
                callerIdentity = authoritativeCallerIdentity,
                targetBinding = authoritativeTargetBinding,
                targetIdentity = authoritativeTargetIdentity,
                targetLaunchTicket = targetLaunchTicket,
                authority = authority,
                operation = normalizedOperation,
                expiresAtNanos = expiresAtNanos,
                expiresAtMillis = expiresAtMillis
            )
            activeByDigest[allocated.digest] = record
            record.toPublicRoute(allocated.plaintext)
        }
    }

    /**
     * Consumes a token only after exact target comparison and a fresh caller-generation callback.
     * The callback runs outside the registry lock and must validate the complete bound identity.
     */
    fun consume(
        token: String?,
        authoritativeTargetIdentity: EngineProviderRouteProcessIdentity,
        authority: String,
        operation: String,
        callerGenerationIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean
    ): EngineProviderRouteTokenConsumeResult = consumeInternal(
        token = token,
        targetAttempt = TargetAttempt.Bound(authoritativeTargetIdentity),
        authority = authority,
        operation = operation,
        callerGenerationIsCurrent = callerGenerationIsCurrent
    )

    fun consumePendingTarget(
        token: String?,
        authoritativeTarget: EngineProviderRouteTargetProcessObservation,
        authority: String,
        operation: String,
        callerGenerationIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean
    ): EngineProviderRouteTokenConsumeResult = consumeInternal(
        token = token,
        targetAttempt = TargetAttempt.Pending(authoritativeTarget),
        authority = authority,
        operation = operation,
        callerGenerationIsCurrent = callerGenerationIsCurrent
    )

    /**
     * Converts a consumed route into one engine-authorized Provider plan. This prevents callers
     * from forging routeTokenVerified=true on the separate planProvider IPC.
     */
    fun authorizeConsumedDispatch(
        token: String?,
        authoritativeTargetIdentity: EngineProviderRouteProcessIdentity,
        authority: String,
        operation: String,
        callerGenerationIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean
    ): EngineProviderRouteTokenConsumeResult = authorizeConsumedDispatchInternal(
        token = token,
        targetAttempt = TargetAttempt.Bound(authoritativeTargetIdentity),
        authority = authority,
        operation = operation,
        callerGenerationIsCurrent = callerGenerationIsCurrent
    )

    fun authorizePendingTargetDispatch(
        token: String?,
        authoritativeTarget: EngineProviderRouteTargetProcessObservation,
        authority: String,
        operation: String,
        callerGenerationIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean
    ): EngineProviderRouteTokenConsumeResult = authorizeConsumedDispatchInternal(
        token = token,
        targetAttempt = TargetAttempt.Pending(authoritativeTarget),
        authority = authority,
        operation = operation,
        callerGenerationIsCurrent = callerGenerationIsCurrent
    )

    internal fun targetBinding(token: String?): EngineProviderRouteTargetBinding? {
        if (token.isNullOrBlank()) return null
        val digest = providerRouteTokenDigest(token)
        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            (activeByDigest[digest] ?: consumedByDigest[digest])?.targetBinding
        }
    }

    internal fun targetLaunchTicket(token: String?): EngineComponentProcessLaunchTicket? {
        if (token.isNullOrBlank()) return null
        val digest = providerRouteTokenDigest(token)
        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            activeByDigest[digest]?.targetLaunchTicket
        }
    }

    internal fun boundTargetForProcess(
        instanceId: String,
        processId: Int,
        processSlot: String,
        processStartTicks: Long
    ): EngineProviderRouteBoundTargetProcess? {
        if (instanceId.isBlank() || processId <= 0 || processSlot.isBlank() || processStartTicks <= 0L) {
            return null
        }
        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            val matches = consumedByDigest.values.mapNotNull { record ->
                val target = record.targetIdentity ?: return@mapNotNull null
                if (
                    record.targetBinding.instanceId != instanceId ||
                    record.targetBinding.processSlot != processSlot ||
                    target.processId != processId ||
                    target.processStartTicks != processStartTicks ||
                    target.processEpoch == null
                ) {
                    return@mapNotNull null
                }
                EngineProviderRouteBoundTargetProcess(
                    binding = record.targetBinding,
                    processId = target.processId,
                    processEpoch = target.processEpoch,
                    processStartTicks = processStartTicks
                )
            }
            matches.firstOrNull()?.takeIf { first -> matches.all { it == first } }
        }
    }

    private fun consumeInternal(
        token: String?,
        targetAttempt: TargetAttempt,
        authority: String,
        operation: String,
        callerGenerationIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean
    ): EngineProviderRouteTokenConsumeResult {
        if (token.isNullOrBlank()) return rejected(EngineProviderRouteTokenConsumeStatus.MISSING)
        val digest = providerRouteTokenDigest(token)
        val normalizedOperation = normalizeOperation(operation)
        val record = synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            val active = activeByDigest[digest] ?: return missingTokenResultLocked(digest)
            targetMismatchStatus(active, targetAttempt, authority, normalizedOperation)
                ?.let { return rejected(it) }
            active
        }
        if (!runCatching { callerGenerationIsCurrent(record.callerIdentity) }.getOrDefault(false)) {
            return rejected(EngineProviderRouteTokenConsumeStatus.CALLER_GENERATION_STALE)
        }
        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            val current = activeByDigest[digest] ?: return missingTokenResultLocked(digest)
            if (current !== record) return rejected(EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND)
            targetMismatchStatus(current, targetAttempt, authority, normalizedOperation)
                ?.let { return rejected(it) }
            if (current.targetIdentity == null) {
                current.targetIdentity = targetAttempt.bindTargetIdentity(
                    digest = digest,
                    processEpoch = nextProcessEpoch()
                )
            }
            activeByDigest.remove(digest)
            consumedByDigest[digest] = current
            addTombstoneLocked(digest, EngineProviderRouteTokenConsumeStatus.REPLAYED)
            EngineProviderRouteTokenConsumeResult(
                status = EngineProviderRouteTokenConsumeStatus.CONSUMED,
                route = current.toPublicRoute(token)
            )
        }
    }

    private fun authorizeConsumedDispatchInternal(
        token: String?,
        targetAttempt: TargetAttempt,
        authority: String,
        operation: String,
        callerGenerationIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean
    ): EngineProviderRouteTokenConsumeResult {
        if (token.isNullOrBlank()) return rejected(EngineProviderRouteTokenConsumeStatus.MISSING)
        val digest = providerRouteTokenDigest(token)
        val normalizedOperation = normalizeOperation(operation)
        val record = synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            val consumed = consumedByDigest[digest]
                ?: return missingTokenResultLocked(digest)
            targetMismatchStatus(
                consumed,
                targetAttempt,
                authority,
                normalizedOperation
            )?.let { return rejected(it) }
            consumed
        }
        if (!runCatching { callerGenerationIsCurrent(record.callerIdentity) }.getOrDefault(false)) {
            return rejected(EngineProviderRouteTokenConsumeStatus.CALLER_GENERATION_STALE)
        }
        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            val current = consumedByDigest[digest] ?: return missingTokenResultLocked(digest)
            if (current !== record) {
                return rejected(EngineProviderRouteTokenConsumeStatus.REPLAYED)
            }
            consumedByDigest.remove(digest)
            EngineProviderRouteTokenConsumeResult(
                status = EngineProviderRouteTokenConsumeStatus.CONSUMED,
                route = current.toPublicRoute(token)
            )
        }
    }

    /** Revokes routes bound to this exact caller or target process generation. */
    fun revokeGeneration(identity: EngineProviderRouteProcessIdentity): Int = synchronized(lock) {
        pruneExpiredLocked(clockNanos())
        revokeMatchingLocked { record ->
            record.callerIdentity == identity || record.targetIdentity == identity
        }
    }

    /** Revokes all process bindings owned by one instance-wide runtime generation. */
    fun revokeGeneration(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String
    ): Int {
        validateProviderRouteText("instanceId", instanceId)
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        validateProviderRouteText("engineSessionId", engineSessionId)
        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            revokeMatchingLocked { record ->
                record.callerIdentity.matchesRuntimeGeneration(
                    instanceId,
                    runtimeEpoch,
                    engineSessionId
                ) || record.targetBinding.matchesRuntimeGeneration(
                    instanceId,
                    runtimeEpoch,
                    engineSessionId
                )
            }
        }
    }

    /** Revokes every route where this instance is either caller or target. */
    fun revokeInstance(instanceId: String): Int {
        validateProviderRouteText("instanceId", instanceId)
        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            revokeMatchingLocked { record ->
                record.callerIdentity.instanceId == instanceId ||
                    record.targetBinding.instanceId == instanceId
            }
        }
    }

    internal fun activeCount(): Int = synchronized(lock) {
        pruneExpiredLocked(clockNanos())
        activeByDigest.size
    }

    internal fun tombstoneCount(): Int = synchronized(lock) {
        pruneExpiredLocked(clockNanos())
        tombstonesByDigest.size
    }

    private fun targetMismatchStatus(
        record: RouteRecord,
        targetAttempt: TargetAttempt,
        authority: String,
        normalizedOperation: String
    ): EngineProviderRouteTokenConsumeStatus? = when {
        record.targetBinding.instanceId != targetAttempt.binding.instanceId -> {
            EngineProviderRouteTokenConsumeStatus.TARGET_INSTANCE_MISMATCH
        }
        record.authority != authority -> EngineProviderRouteTokenConsumeStatus.AUTHORITY_MISMATCH
        record.operation != normalizedOperation -> {
            EngineProviderRouteTokenConsumeStatus.OPERATION_MISMATCH
        }
        record.targetBinding.processSlot != targetAttempt.binding.processSlot -> {
            EngineProviderRouteTokenConsumeStatus.PROCESS_SLOT_MISMATCH
        }
        record.targetBinding != targetAttempt.binding ||
            !targetAttempt.matchesBoundIdentity(record.targetIdentity) -> {
            EngineProviderRouteTokenConsumeStatus.TARGET_GENERATION_MISMATCH
        }
        else -> null
    }

    private fun uniqueTokenLocked(): AllocatedToken {
        repeat(MAX_TOKEN_ATTEMPTS) {
            val plaintext = tokenFactory().takeIf { it.isNotBlank() }
                ?: error("Provider route token factory returned blank")
            check(plaintext.all(::isUrlSafeTokenCharacter)) {
                "Provider route token factory returned a non URL-safe token"
            }
            val digest = providerRouteTokenDigest(plaintext)
            if (digest !in activeByDigest && digest !in tombstonesByDigest) {
                return AllocatedToken(plaintext, digest)
            }
        }
        error("unable to allocate a unique Provider route token")
    }

    private fun pruneExpiredLocked(nowNanos: Long) {
        val iterator = activeByDigest.entries.iterator()
        while (iterator.hasNext()) {
            val (digest, record) = iterator.next()
            if (nowNanos - record.expiresAtNanos >= 0L) {
                iterator.remove()
                addTombstoneLocked(digest, EngineProviderRouteTokenConsumeStatus.EXPIRED)
            }
        }
        val consumedIterator = consumedByDigest.entries.iterator()
        while (consumedIterator.hasNext()) {
            val (digest, record) = consumedIterator.next()
            if (nowNanos - record.expiresAtNanos >= 0L) {
                consumedIterator.remove()
                addTombstoneLocked(digest, EngineProviderRouteTokenConsumeStatus.EXPIRED)
            }
        }
    }

    private fun revokeMatchingLocked(predicate: (RouteRecord) -> Boolean): Int {
        var revoked = 0
        val iterator = activeByDigest.entries.iterator()
        while (iterator.hasNext()) {
            val (digest, record) = iterator.next()
            if (predicate(record)) {
                iterator.remove()
                addTombstoneLocked(digest, EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND)
                revoked++
            }
        }
        val consumedIterator = consumedByDigest.entries.iterator()
        while (consumedIterator.hasNext()) {
            val (digest, record) = consumedIterator.next()
            if (predicate(record)) {
                consumedIterator.remove()
                addTombstoneLocked(digest, EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND)
                revoked++
            }
        }
        return revoked
    }

    private fun addTombstoneLocked(
        digest: String,
        status: EngineProviderRouteTokenConsumeStatus
    ) {
        check(
            status == EngineProviderRouteTokenConsumeStatus.REPLAYED ||
                status == EngineProviderRouteTokenConsumeStatus.EXPIRED ||
                status == EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND
        ) { "invalid Provider route token tombstone status" }
        tombstonesByDigest[digest] = status
        while (tombstonesByDigest.size > maxTombstones) {
            val oldestDigest = tombstonesByDigest.keys.first()
            tombstonesByDigest.remove(oldestDigest)
            consumedByDigest.remove(oldestDigest)
        }
    }

    private fun missingTokenResultLocked(digest: String): EngineProviderRouteTokenConsumeResult =
        rejected(
            tombstonesByDigest[digest] ?: EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND
        )

    private data class AllocatedToken(
        val plaintext: String,
        val digest: String
    )

    private class RouteRecord(
        val callerIdentity: EngineProviderRouteProcessIdentity,
        val targetBinding: EngineProviderRouteTargetBinding,
        var targetIdentity: EngineProviderRouteProcessIdentity?,
        val targetLaunchTicket: EngineComponentProcessLaunchTicket?,
        val authority: String,
        val operation: String,
        val expiresAtNanos: Long,
        val expiresAtMillis: Long
    ) {
        fun toPublicRoute(token: String) = EngineProviderRouteToken(
            token = token,
            callerInstanceId = callerIdentity.instanceId,
            targetInstanceId = targetBinding.instanceId,
            authority = authority,
            operation = operation,
            expiresAtMillis = expiresAtMillis,
            processSlot = targetBinding.processSlot,
            callerProcessSlot = callerIdentity.processSlot,
            callerProcessId = callerIdentity.processId
        )
    }

    private sealed interface TargetAttempt {
        val binding: EngineProviderRouteTargetBinding

        fun matchesBoundIdentity(identity: EngineProviderRouteProcessIdentity?): Boolean

        fun bindTargetIdentity(digest: String, processEpoch: Long): EngineProviderRouteProcessIdentity

        data class Bound(
            val identity: EngineProviderRouteProcessIdentity
        ) : TargetAttempt {
            override val binding: EngineProviderRouteTargetBinding = identity.toTargetBinding()

            override fun matchesBoundIdentity(identity: EngineProviderRouteProcessIdentity?): Boolean =
                identity == null || identity == this.identity

            override fun bindTargetIdentity(
                digest: String,
                processEpoch: Long
            ): EngineProviderRouteProcessIdentity = identity
        }

        data class Pending(
            val observation: EngineProviderRouteTargetProcessObservation
        ) : TargetAttempt {
            override val binding: EngineProviderRouteTargetBinding = observation.binding

            override fun matchesBoundIdentity(identity: EngineProviderRouteProcessIdentity?): Boolean =
                identity == null || (
                    identity.toTargetBinding() == binding &&
                        identity.processId == observation.processId &&
                        identity.processStartTicks == observation.processStartTicks
                    )

            override fun bindTargetIdentity(
                digest: String,
                processEpoch: Long
            ): EngineProviderRouteProcessIdentity = EngineProviderRouteProcessIdentity(
                instanceId = binding.instanceId,
                runtimeEpoch = binding.runtimeEpoch,
                engineSessionId = binding.engineSessionId,
                processSlot = binding.processSlot,
                processId = observation.processId,
                effectiveGuestProcessName = binding.effectiveGuestProcessName,
                processEpoch = processEpoch,
                clientSessionId = "provider-route-${digest.take(24)}-$processEpoch",
                processStartTicks = observation.processStartTicks
            )
        }
    }

    private fun nextProcessEpoch(): Long = nextProviderProcessEpoch.incrementAndGet().also { epoch ->
        check(epoch > 0L) { "Provider route process epoch exhausted" }
    }

    companion object {
        val DEFAULT_TTL_NANOS: Long = TimeUnit.MINUTES.toNanos(2)
        const val DEFAULT_MAX_ACTIVE_TOKENS = 2048
        const val DEFAULT_MAX_TOMBSTONES = 4096

        fun normalizeOperation(operation: String): String =
            when (val base = operation.substringBefore(":")) {
                "openFileDescriptor" -> "openFile"
                "openAssetFileDescriptor" -> "openAssetFile"
                "openTypedAssetFileDescriptor" -> "openTypedAssetFile"
                // 归一化所有文件操作到统一 category，避免 openFile/openAssetFile/openTypedAssetFile
                // 各自绑定独立 token 导致 OPERATION_MISMATCH（2026-08-01 真机 bug 发现）
                "openFile", "openAssetFile", "openTypedAssetFile" -> "openFile"
                else -> base
            }

        private const val MAX_TOKEN_ATTEMPTS = 16
    }
}

fun normalizeProviderRouteOperation(operation: String): String =
    EngineProviderRouteTokenRegistry.normalizeOperation(operation)

private fun rejected(
    status: EngineProviderRouteTokenConsumeStatus
) = EngineProviderRouteTokenConsumeResult(status)

private fun validateProviderAuthority(authority: String) {
    validateProviderRouteText("authority", authority)
    require(';' !in authority) { "authority must identify one Provider authority" }
}

private fun validateProviderRouteText(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value == value.trim()) { "$name must be normalized" }
    require(value.length <= MAX_PROVIDER_ROUTE_TEXT_LENGTH) {
        "$name must be at most $MAX_PROVIDER_ROUTE_TEXT_LENGTH characters"
    }
    require('\u0000' !in value) { "$name must not contain NUL" }
}

private fun secureProviderRouteToken(): String {
    val bytes = ByteArray(PROVIDER_ROUTE_TOKEN_BYTE_COUNT)
    ProviderRouteSecureRandom.instance.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun providerRouteTokenDigest(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun isUrlSafeTokenCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
        character == '-' || character == '_'

private fun EngineProviderRouteProcessIdentity.matchesRuntimeGeneration(
    instanceId: String,
    runtimeEpoch: Long,
    engineSessionId: String
): Boolean = this.instanceId == instanceId &&
    this.runtimeEpoch == runtimeEpoch &&
    this.engineSessionId == engineSessionId

private fun EngineProviderRouteTargetBinding.matchesRuntimeGeneration(
    instanceId: String,
    runtimeEpoch: Long,
    engineSessionId: String
): Boolean = this.instanceId == instanceId &&
    this.runtimeEpoch == runtimeEpoch &&
    this.engineSessionId == engineSessionId

private fun EngineProviderRouteProcessIdentity.toTargetBinding() =
    EngineProviderRouteTargetBinding(
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = processSlot,
        effectiveGuestProcessName = effectiveGuestProcessName
    )

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private const val PROVIDER_ROUTE_TOKEN_BYTE_COUNT = 32
private const val MAX_PROVIDER_ROUTE_TEXT_LENGTH = 512

private object ProviderRouteSecureRandom {
    val instance = SecureRandom()
}
