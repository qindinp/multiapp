package com.multiapp.core.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineComponentProcessSlotAllocatorTest {
    @Test
    fun `Application process reuses primary slot and custom processes reserve distinct slots`() {
        val allocator = EngineComponentProcessSlotAllocator()

        val primary = allocator.allocate(key(), APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        val repeatedPrimary = allocator.allocate(
            key(),
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            CANDIDATE_SLOTS.reversed()
        )
        val firstCustom = allocator.allocate(
            key("$APPLICATION_PROCESS:remote"),
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            CANDIDATE_SLOTS
        )
        val secondCustom = allocator.allocate(
            key("$APPLICATION_PROCESS:worker"),
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            CANDIDATE_SLOTS
        )

        assertTrue(primary === repeatedPrimary)
        assertEquals(PRIMARY_SLOT, primary.processSlot)
        assertEquals(CUSTOM_SLOT_1, firstCustom.processSlot)
        assertEquals(CUSTOM_SLOT_2, secondCustom.processSlot)
        assertEquals(key(), allocator.ownerOf(PRIMARY_SLOT))
        assertEquals(firstCustom, allocator.query(firstCustom.key))
        assertEquals(3, allocator.size())
    }

    @Test
    fun `same custom key is idempotent while its declared slot remains valid`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val customKey = key("$APPLICATION_PROCESS:remote")
        val first = allocator.allocate(
            customKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            CANDIDATE_SLOTS
        )

        val repeated = allocator.allocate(
            customKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            CANDIDATE_SLOTS.reversed()
        )

        assertTrue(first === repeated)
        assertEquals(CUSTOM_SLOT_1, repeated.processSlot)
        assertEquals(customKey, allocator.ownerOf(CUSTOM_SLOT_1))
    }

    @Test
    fun `slot exhaustion is explicit and does not disturb the live owner`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val ownerKey = key("$APPLICATION_PROCESS:remote")
        val waitingKey = key("$APPLICATION_PROCESS:worker")
        val owner = allocator.allocate(
            ownerKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            listOf(CUSTOM_SLOT_1)
        )

        val exhausted = assertFailsWith<EngineComponentProcessSlotExhaustedException> {
            allocator.allocate(
                waitingKey,
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                listOf(CUSTOM_SLOT_1)
            )
        }
        val emptyCatalog = assertFailsWith<EngineComponentProcessSlotExhaustedException> {
            allocator.allocate(
                key("$APPLICATION_PROCESS:isolated"),
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                emptyList()
            )
        }

        assertEquals(waitingKey, exhausted.key)
        assertEquals(listOf(CUSTOM_SLOT_1), exhausted.candidateSlots)
        assertEquals(1, exhausted.candidateCount)
        assertEquals(0, emptyCatalog.candidateCount)
        assertEquals(owner, allocator.query(ownerKey))
        assertEquals(ownerKey, allocator.ownerOf(CUSTOM_SLOT_1))
        assertNull(allocator.query(waitingKey))
    }

    @Test
    fun `one host slot cannot be owned by live keys from different instances`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val firstKey = key("$APPLICATION_PROCESS:remote")
        val secondKey = key(
            guestProcessName = "com.example.other:remote",
            instanceId = "instance-other",
            engineSessionId = "engine-session-other"
        )
        allocator.allocate(
            firstKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            listOf(CUSTOM_SLOT_1)
        )

        assertFailsWith<EngineComponentProcessSlotExhaustedException> {
            allocator.allocate(
                secondKey,
                "com.example.other",
                CUSTOM_SLOT_2,
                listOf(CUSTOM_SLOT_1)
            )
        }
        assertEquals(firstKey, allocator.ownerOf(CUSTOM_SLOT_1))
        assertNull(allocator.query(secondKey))
    }

    @Test
    fun `concurrent custom allocations reserve one slot for exactly one key`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val keys = (0 until 12).map { index -> key("$APPLICATION_PROCESS:worker$index") }
        val ready = CountDownLatch(keys.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(keys.size)
        val futures = keys.map { candidateKey ->
            executor.submit<Result<EngineComponentProcessSlotAssignment>> {
                ready.countDown()
                start.await()
                runCatching {
                    allocator.allocate(
                        candidateKey,
                        APPLICATION_PROCESS,
                        PRIMARY_SLOT,
                        listOf(CUSTOM_SLOT_1)
                    )
                }
            }
        }

        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { future -> future.get(5, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(
                keys.size - 1,
                results.count { it.exceptionOrNull() is EngineComponentProcessSlotExhaustedException }
            )
            val assignment = results.single { it.isSuccess }.getOrThrow()
            assertEquals(assignment.key, allocator.ownerOf(CUSTOM_SLOT_1))
            assertEquals(1, allocator.size())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `release compares complete assignment and frees both indexes`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val firstKey = key("$APPLICATION_PROCESS:remote")
        val assignment = allocator.allocate(
            firstKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            listOf(CUSTOM_SLOT_1)
        )
        val forged = assignment.copy(processSlot = CUSTOM_SLOT_2)

        assertFalse(allocator.release(forged))
        assertEquals(firstKey, allocator.ownerOf(CUSTOM_SLOT_1))
        assertTrue(allocator.release(assignment))
        assertNull(allocator.query(firstKey))
        assertNull(allocator.ownerOf(CUSTOM_SLOT_1))
        assertFalse(allocator.release(firstKey))

        val replacementKey = key("$APPLICATION_PROCESS:worker")
        val replacement = allocator.allocate(
            replacementKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            listOf(CUSTOM_SLOT_1)
        )
        assertEquals(CUSTOM_SLOT_1, replacement.processSlot)
        assertTrue(allocator.release(replacementKey))
    }

    @Test
    fun `new generation atomically replaces old generation and rejects rollback`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val oldKey = key("$APPLICATION_PROCESS:remote")
        allocator.allocate(
            oldKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            listOf(CUSTOM_SLOT_1)
        )
        val newKey = key(
            guestProcessName = "$APPLICATION_PROCESS:remote",
            runtimeEpoch = EPOCH + 1,
            engineSessionId = NEXT_SESSION_ID
        )

        val replacement = allocator.allocate(
            newKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            listOf(CUSTOM_SLOT_1)
        )

        assertEquals(CUSTOM_SLOT_1, replacement.processSlot)
        assertNull(allocator.query(oldKey))
        assertEquals(newKey, allocator.ownerOf(CUSTOM_SLOT_1))
        assertFailsWith<IllegalStateException> {
            allocator.allocate(
                oldKey,
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                listOf(CUSTOM_SLOT_1)
            )
        }
        assertFailsWith<IllegalStateException> {
            allocator.allocate(
                newKey.copy(engineSessionId = "conflicting-session"),
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                listOf(CUSTOM_SLOT_1)
            )
        }
        assertFailsWith<IllegalStateException> {
            allocator.allocate(
                newKey.copy(runtimeEpoch = EPOCH + 2),
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                listOf(CUSTOM_SLOT_1)
            )
        }
    }

    @Test
    fun `generation and instance revocation are terminal for the current generation`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val primaryKey = key()
        val customKey = key("$APPLICATION_PROCESS:remote")
        allocator.allocate(primaryKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        allocator.allocate(customKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)

        assertEquals(2, allocator.revokeGeneration(INSTANCE_ID, EPOCH, SESSION_ID))
        assertNull(allocator.ownerOf(PRIMARY_SLOT))
        assertNull(allocator.ownerOf(CUSTOM_SLOT_1))
        assertFailsWith<IllegalStateException> {
            allocator.allocate(customKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        }

        val nextKey = key(
            runtimeEpoch = EPOCH + 1,
            engineSessionId = NEXT_SESSION_ID
        )
        allocator.allocate(nextKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        assertEquals(1, allocator.revokeInstance(INSTANCE_ID))
        assertFailsWith<IllegalStateException> {
            allocator.allocate(nextKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        }
    }

    @Test
    fun `reconcile removes undeclared slots and stale generations while preserving valid owners`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val primaryKey = key()
        val firstCustomKey = key("$APPLICATION_PROCESS:remote")
        val secondCustomKey = key("$APPLICATION_PROCESS:worker")
        allocator.allocate(primaryKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        allocator.allocate(firstCustomKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        allocator.allocate(secondCustomKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)

        val candidateRemovalCount = allocator.reconcile(
            generation = primaryKey.generation,
            applicationGuestProcessName = APPLICATION_PROCESS,
            primaryProcessSlot = PRIMARY_SLOT,
            declaredCandidateSlots = listOf(CUSTOM_SLOT_2)
        )

        assertEquals(1, candidateRemovalCount)
        assertEquals(PRIMARY_SLOT, allocator.query(primaryKey)?.processSlot)
        assertNull(allocator.query(firstCustomKey))
        assertEquals(CUSTOM_SLOT_2, allocator.query(secondCustomKey)?.processSlot)
        assertNull(allocator.ownerOf(CUSTOM_SLOT_1))

        val nextGeneration = EngineComponentProcessSlotGeneration(
            INSTANCE_ID,
            EPOCH + 1,
            NEXT_SESSION_ID
        )
        val staleRemovalCount = allocator.reconcile(
            nextGeneration,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            CANDIDATE_SLOTS
        )

        assertEquals(2, staleRemovalCount)
        assertEquals(0, allocator.size())
        assertFailsWith<IllegalStateException> {
            allocator.allocate(primaryKey, APPLICATION_PROCESS, PRIMARY_SLOT, CANDIDATE_SLOTS)
        }
        val current = allocator.allocate(
            key(runtimeEpoch = EPOCH + 1, engineSessionId = NEXT_SESSION_ID),
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            CANDIDATE_SLOTS
        )
        assertEquals(PRIMARY_SLOT, current.processSlot)
    }

    @Test
    fun `identity and candidate catalog validation is strict and side effect free`() {
        val allocator = EngineComponentProcessSlotAllocator()
        val customKey = key("$APPLICATION_PROCESS:remote")

        assertFailsWith<IllegalArgumentException> {
            EngineComponentProcessSlotKey(" instance", EPOCH, SESSION_ID, APPLICATION_PROCESS)
        }
        assertFailsWith<IllegalArgumentException> {
            EngineComponentProcessSlotKey(INSTANCE_ID, 0L, SESSION_ID, APPLICATION_PROCESS)
        }
        assertFailsWith<IllegalArgumentException> {
            EngineComponentProcessSlotKey(INSTANCE_ID, EPOCH, SESSION_ID, "bad\nprocess")
        }
        assertFailsWith<IllegalArgumentException> {
            allocator.allocate(
                customKey,
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                listOf(CUSTOM_SLOT_1, CUSTOM_SLOT_1)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            allocator.allocate(
                customKey,
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                listOf(PRIMARY_SLOT, CUSTOM_SLOT_1)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            allocator.allocate(
                customKey,
                APPLICATION_PROCESS,
                PRIMARY_SLOT,
                listOf(" $CUSTOM_SLOT_1")
            )
        }

        assertEquals(0, allocator.size())
        val valid = allocator.allocate(
            customKey,
            APPLICATION_PROCESS,
            PRIMARY_SLOT,
            listOf(CUSTOM_SLOT_1)
        )
        assertEquals(CUSTOM_SLOT_1, valid.processSlot)
        assertFailsWith<IllegalStateException> {
            allocator.allocate(
                customKey,
                "com.example.changed",
                PRIMARY_SLOT,
                listOf(CUSTOM_SLOT_1)
            )
        }
        assertFailsWith<IllegalStateException> {
            allocator.allocate(
                customKey,
                APPLICATION_PROCESS,
                CUSTOM_SLOT_2,
                listOf(CUSTOM_SLOT_1)
            )
        }
        assertEquals(valid, allocator.query(customKey))
    }

    private fun key(
        guestProcessName: String = APPLICATION_PROCESS,
        instanceId: String = INSTANCE_ID,
        runtimeEpoch: Long = EPOCH,
        engineSessionId: String = SESSION_ID
    ) = EngineComponentProcessSlotKey(
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        guestProcessName = guestProcessName
    )

    private companion object {
        const val INSTANCE_ID = "instance-component-process"
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
        const val NEXT_SESSION_ID = "engine-session-43"
        const val APPLICATION_PROCESS = "com.example.app"
        const val PRIMARY_SLOT = "com.multiapp.app:v0"
        const val CUSTOM_SLOT_1 = "com.multiapp.app:v1"
        const val CUSTOM_SLOT_2 = "com.multiapp.app:v2"
        val CANDIDATE_SLOTS = listOf(CUSTOM_SLOT_1, CUSTOM_SLOT_2)
    }
}
