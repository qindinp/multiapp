package com.multiapp.core.engine

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineProviderRouteTokenRegistryTest {
    @Test
    fun `pending custom Provider target binds first observed process generation then authorizes dispatch`() {
        val registry = registryWithTokens("pending-custom-provider-token")
        val caller = primaryIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        val binding = EngineProviderRouteTargetBinding(
            instanceId = TARGET_INSTANCE,
            runtimeEpoch = RUNTIME_EPOCH,
            engineSessionId = ENGINE_SESSION,
            processSlot = TARGET_SLOT,
            effectiveGuestProcessName = TARGET_PROCESS
        )
        val route = registry.issue(
            authoritativeCallerIdentity = caller,
            authoritativeTargetBinding = binding,
            authority = AUTHORITY,
            operation = OPERATION
        )
        val target = EngineProviderRouteTargetProcessObservation(
            binding = binding,
            processId = TARGET_PID,
            processStartTicks = PROCESS_START_TICKS
        )

        assertEquals(TARGET_SLOT, route.processSlot)
        assertTrue(
            registry.consumePendingTarget(route.token, target, AUTHORITY, OPERATION) {
                it == caller
            }.consumed
        )
        val bound = registry.boundTargetForProcess(
            TARGET_INSTANCE,
            TARGET_PID,
            TARGET_SLOT,
            PROCESS_START_TICKS
        )
        assertEquals(binding, bound?.binding)
        assertTrue(checkNotNull(bound).processEpoch > 0L)

        val forgedProcess = target.copy(processId = TARGET_PID + 1)
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TARGET_GENERATION_MISMATCH,
            registry.authorizePendingTargetDispatch(
                route.token,
                forgedProcess,
                AUTHORITY,
                OPERATION
            ) { true }.status
        )
        assertTrue(
            registry.authorizePendingTargetDispatch(
                route.token,
                target,
                AUTHORITY,
                OPERATION
            ) { it == caller }.consumed
        )
    }

    @Test
    fun `default token is 32 byte URL safe and route uses fixed clocks and normalized operation`() {
        val registry = EngineProviderRouteTokenRegistry(
            clockNanos = { 123L },
            wallClockMillis = { 1_000L },
            ttlNanos = TimeUnit.SECONDS.toNanos(2)
        )

        val route = registry.issue(
            componentIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS),
            componentIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS),
            AUTHORITY,
            "openFileDescriptor:rw"
        )

        assertTrue(route.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(32, Base64.getUrlDecoder().decode(route.token).size)
        assertEquals(CALLER_INSTANCE, route.callerInstanceId)
        assertEquals(TARGET_INSTANCE, route.targetInstanceId)
        assertEquals(AUTHORITY, route.authority)
        assertEquals("openFile", route.operation)
        assertEquals(3_000L, route.expiresAtMillis)
        assertEquals(TARGET_SLOT, route.processSlot)

        val consumed = registry.consume(
            route.token,
            componentIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS),
            AUTHORITY,
            "openFile"
        ) { it == componentIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS) }
        assertTrue(consumed.consumed)
        assertEquals(route, consumed.route)

        val dispatch = registry.authorizeConsumedDispatch(
            route.token,
            componentIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS),
            AUTHORITY,
            "openFile"
        ) { it == componentIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS) }
        assertTrue(dispatch.consumed)
        assertEquals(route, dispatch.route)
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.REPLAYED,
            registry.authorizeConsumedDispatch(
                route.token,
                componentIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS),
                AUTHORITY,
                "openFile"
            ) { true }.status
        )
    }

    @Test
    fun `component generation fields are all present or all absent`() {
        val primary = primaryIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        assertFalse(primary.isComponentProcessGenerationBound)

        val component = componentIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        assertTrue(component.isComponentProcessGenerationBound)

        assertFailsWith<IllegalArgumentException> {
            primary.copy(processEpoch = PROCESS_EPOCH)
        }
        assertFailsWith<IllegalArgumentException> {
            primary.copy(
                processEpoch = PROCESS_EPOCH,
                clientSessionId = CLIENT_SESSION
            )
        }
        assertFailsWith<IllegalArgumentException> {
            component.copy(processStartTicks = null)
        }
    }

    @Test
    fun `forged target and stale caller do not consume a valid token`() {
        val caller = componentIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        val target = componentIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS)
        val registry = registryWithTokens("forgery-token")
        val route = registry.issue(caller, target, AUTHORITY, OPERATION)
        var callerValidationCount = 0

        fun consume(
            attemptedTarget: EngineProviderRouteProcessIdentity = target,
            attemptedAuthority: String = AUTHORITY,
            attemptedOperation: String = OPERATION,
            callerCurrent: Boolean = true
        ): EngineProviderRouteTokenConsumeStatus = registry.consume(
            route.token,
            attemptedTarget,
            attemptedAuthority,
            attemptedOperation
        ) { boundCaller ->
            callerValidationCount++
            callerCurrent && boundCaller == caller
        }.status

        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TARGET_INSTANCE_MISMATCH,
            consume(attemptedTarget = target.copy(instanceId = "other-target"))
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.AUTHORITY_MISMATCH,
            consume(attemptedAuthority = "com.example.other")
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.OPERATION_MISMATCH,
            consume(attemptedOperation = "insert")
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.PROCESS_SLOT_MISMATCH,
            consume(attemptedTarget = target.copy(processSlot = "com.multiapp.app:v9"))
        )

        val generationForgeries = listOf(
            target.copy(runtimeEpoch = target.runtimeEpoch + 1L),
            target.copy(engineSessionId = "engine-session-other"),
            target.copy(processId = target.processId + 1),
            target.copy(effectiveGuestProcessName = "com.example.app:other"),
            target.copy(processEpoch = checkNotNull(target.processEpoch) + 1L),
            target.copy(clientSessionId = "client-session-other"),
            target.copy(processStartTicks = checkNotNull(target.processStartTicks) + 1L)
        )
        generationForgeries.forEach { forgedTarget ->
            assertEquals(
                EngineProviderRouteTokenConsumeStatus.TARGET_GENERATION_MISMATCH,
                consume(attemptedTarget = forgedTarget)
            )
        }
        assertEquals(0, callerValidationCount)

        assertEquals(
            EngineProviderRouteTokenConsumeStatus.CALLER_GENERATION_STALE,
            consume(callerCurrent = false)
        )
        assertEquals(1, callerValidationCount)
        assertEquals(EngineProviderRouteTokenConsumeStatus.CONSUMED, consume())
        assertEquals(2, callerValidationCount)
    }

    @Test
    fun `missing unknown replayed and expired tokens have distinct statuses`() {
        var now = 0L
        var tokenIndex = 0
        val registry = EngineProviderRouteTokenRegistry(
            clockNanos = { now },
            wallClockMillis = { 1_000L },
            tokenFactory = { "status-token-${++tokenIndex}" },
            ttlNanos = 10L
        )
        val caller = primaryIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        val target = primaryIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS)

        assertEquals(
            EngineProviderRouteTokenConsumeStatus.MISSING,
            registry.consume(null, target, AUTHORITY, OPERATION) { true }.status
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND,
            registry.consume("unknown-token", target, AUTHORITY, OPERATION) { true }.status
        )

        val consumedRoute = registry.issue(caller, target, AUTHORITY, OPERATION)
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.CONSUMED,
            registry.consume(consumedRoute.token, target, AUTHORITY, OPERATION) { it == caller }.status
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.REPLAYED,
            registry.consume(consumedRoute.token, target, AUTHORITY, OPERATION) { true }.status
        )

        val expiredRoute = registry.issue(caller, target, AUTHORITY, OPERATION)
        now = 10L
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.EXPIRED,
            registry.consume(expiredRoute.token, target, AUTHORITY, OPERATION) { true }.status
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.EXPIRED,
            registry.consume(expiredRoute.token, target, AUTHORITY, OPERATION) { true }.status
        )
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun `registry retains only token digests in active and tombstone indexes`() {
        val registry = registryWithTokens("plaintext-route-token")
        val caller = primaryIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        val target = primaryIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS)
        val route = registry.issue(caller, target, AUTHORITY, OPERATION)
        val expectedDigest = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(route.token.toByteArray(Charsets.UTF_8))
        )

        val active = registry.privateMap("activeByDigest")
        assertEquals(setOf(expectedDigest), active.keys)
        assertNotEquals(route.token, active.keys.single())
        assertFalse(active.values.single().toString().contains(route.token))

        assertTrue(registry.consume(route.token, target, AUTHORITY, OPERATION) { it == caller }.consumed)
        assertTrue(registry.privateMap("activeByDigest").isEmpty())
        assertEquals(setOf(expectedDigest), registry.privateMap("tombstonesByDigest").keys)
    }

    @Test
    fun `concurrent consume permits exactly one winner`() {
        val registry = registryWithTokens("concurrent-route-token")
        val caller = componentIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        val target = componentIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS)
        val route = registry.issue(caller, target, AUTHORITY, OPERATION)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val results = (0 until 32).map {
                executor.submit<EngineProviderRouteTokenConsumeStatus> {
                    registry.consume(route.token, target, AUTHORITY, OPERATION) { it == caller }.status
                }
            }.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it == EngineProviderRouteTokenConsumeStatus.CONSUMED })
            assertEquals(31, results.count { it == EngineProviderRouteTokenConsumeStatus.REPLAYED })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `generation and instance revocation cover caller and target without broad generation matches`() {
        var tokenIndex = 0
        val registry = EngineProviderRouteTokenRegistry(
            clockNanos = { 0L },
            wallClockMillis = { 0L },
            tokenFactory = { "revoke-token-${++tokenIndex}" }
        )
        val generation = componentIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        val nextGeneration = generation.copy(
            processEpoch = checkNotNull(generation.processEpoch) + 1L,
            clientSessionId = "client-session-next",
            processStartTicks = checkNotNull(generation.processStartTicks) + 1L
        )
        val target = componentIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS)
        val other = primaryIdentity("unrelated-instance", 7000, "com.multiapp.app:v7", "com.other")

        val callerBound = registry.issue(generation, target, AUTHORITY, OPERATION)
        val targetBound = registry.issue(other, generation, AUTHORITY, OPERATION)
        val nextBound = registry.issue(nextGeneration, target, AUTHORITY, OPERATION)
        assertEquals(2, registry.revokeGeneration(generation))
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND,
            registry.consume(callerBound.token, target, AUTHORITY, OPERATION) { true }.status
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND,
            registry.consume(targetBound.token, generation, AUTHORITY, OPERATION) { true }.status
        )
        assertTrue(registry.consume(nextBound.token, target, AUTHORITY, OPERATION) { it == nextGeneration }.consumed)

        val callerInstanceBound = registry.issue(nextGeneration, target, AUTHORITY, OPERATION)
        val targetInstanceBound = registry.issue(other, target, AUTHORITY, OPERATION)
        val unrelated = registry.issue(other, other, AUTHORITY, OPERATION)
        assertEquals(3, registry.revokeInstance(TARGET_INSTANCE))
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND,
            registry.consume(callerInstanceBound.token, target, AUTHORITY, OPERATION) { true }.status
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND,
            registry.consume(targetInstanceBound.token, target, AUTHORITY, OPERATION) { true }.status
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND,
            registry.authorizeConsumedDispatch(
                nextBound.token,
                target,
                AUTHORITY,
                OPERATION
            ) { true }.status
        )
        assertTrue(registry.consume(unrelated.token, other, AUTHORITY, OPERATION) { it == other }.consumed)
    }

    @Test
    fun `active capacity fails closed and tombstones remain bounded`() {
        var tokenIndex = 0
        val registry = EngineProviderRouteTokenRegistry(
            clockNanos = { 0L },
            wallClockMillis = { 0L },
            tokenFactory = { "capacity-token-${++tokenIndex}" },
            maxActiveTokens = 2,
            maxTombstones = 2
        )
        val caller = primaryIdentity(CALLER_INSTANCE, CALLER_PID, CALLER_SLOT, CALLER_PROCESS)
        val target = primaryIdentity(TARGET_INSTANCE, TARGET_PID, TARGET_SLOT, TARGET_PROCESS)
        val first = registry.issue(caller, target, AUTHORITY, OPERATION)
        val second = registry.issue(caller, target, AUTHORITY, OPERATION)

        assertFailsWith<IllegalStateException> {
            registry.issue(caller, target, AUTHORITY, OPERATION)
        }
        assertEquals(2, registry.activeCount())

        assertTrue(registry.consume(first.token, target, AUTHORITY, OPERATION) { true }.consumed)
        val third = registry.issue(caller, target, AUTHORITY, OPERATION)
        assertTrue(registry.consume(second.token, target, AUTHORITY, OPERATION) { true }.consumed)
        assertTrue(registry.consume(third.token, target, AUTHORITY, OPERATION) { true }.consumed)

        assertEquals(0, registry.activeCount())
        assertEquals(2, registry.tombstoneCount())
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.TOKEN_NOT_FOUND,
            registry.consume(first.token, target, AUTHORITY, OPERATION) { true }.status
        )
        assertEquals(
            EngineProviderRouteTokenConsumeStatus.REPLAYED,
            registry.consume(second.token, target, AUTHORITY, OPERATION) { true }.status
        )
        assertNull(
            registry.consume(second.token, target, AUTHORITY, OPERATION) { true }.route
        )
    }

    private fun registryWithTokens(vararg tokens: String): EngineProviderRouteTokenRegistry {
        var index = 0
        return EngineProviderRouteTokenRegistry(
            clockNanos = { 0L },
            wallClockMillis = { 1_000L },
            tokenFactory = { tokens[index++] }
        )
    }

    private fun primaryIdentity(
        instanceId: String,
        processId: Int,
        processSlot: String,
        guestProcessName: String
    ) = EngineProviderRouteProcessIdentity(
        instanceId = instanceId,
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION,
        processSlot = processSlot,
        processId = processId,
        effectiveGuestProcessName = guestProcessName
    )

    private fun componentIdentity(
        instanceId: String,
        processId: Int,
        processSlot: String,
        guestProcessName: String
    ) = primaryIdentity(instanceId, processId, processSlot, guestProcessName).copy(
        processEpoch = PROCESS_EPOCH,
        clientSessionId = CLIENT_SESSION,
        processStartTicks = PROCESS_START_TICKS
    )

    @Suppress("UNCHECKED_CAST")
    private fun EngineProviderRouteTokenRegistry.privateMap(name: String): Map<Any?, Any?> {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as Map<Any?, Any?>
    }

    private companion object {
        const val CALLER_INSTANCE = "caller-instance"
        const val TARGET_INSTANCE = "target-instance"
        const val CALLER_PID = 4100
        const val TARGET_PID = 4200
        const val CALLER_SLOT = "com.multiapp.app:v0"
        const val TARGET_SLOT = "com.multiapp.app:v1"
        const val CALLER_PROCESS = "com.example.caller:worker"
        const val TARGET_PROCESS = "com.example.target:provider"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION = "engine-session-42"
        const val PROCESS_EPOCH = 7L
        const val CLIENT_SESSION = "client-session-7"
        const val PROCESS_START_TICKS = 987_654L
        const val AUTHORITY = "com.example.provider"
        const val OPERATION = "query"
    }
}
