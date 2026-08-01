package com.multiapp.core.model.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineProcessSlotContractTest {

    @Test
    fun `slot count covers all manifest declared process slots`() {
        assertEquals(24, EngineProcessSlotContract.PROCESS_SLOT_COUNT)
    }

    @Test
    fun `boundary slots v0 and v23 parse successfully`() {
        assertEquals(
            0,
            EngineProcessSlotContract.processSlotIndex(HOST_PACKAGE, "$HOST_PACKAGE:v0")
        )
        assertEquals(
            23,
            EngineProcessSlotContract.processSlotIndex(HOST_PACKAGE, "$HOST_PACKAGE:v23")
        )
    }

    @Test
    fun `out of range foreign and malformed slots are rejected`() {
        listOf(
            null,
            "",
            " ",
            "$HOST_PACKAGE:v-1",
            "$HOST_PACKAGE:v24",
            "$HOST_PACKAGE:v100",
            "$HOST_PACKAGE:vx",
            "com.other:v3",
            "$HOST_PACKAGE:other3",
            HOST_PACKAGE
        ).forEach { processSlot ->
            assertNull(
                EngineProcessSlotContract.processSlotIndex(HOST_PACKAGE, processSlot),
                "expected null for $processSlot"
            )
        }
    }

    @Test
    fun `lenient parse canonicalizes zero padded numeric suffix`() {
        assertEquals(
            3,
            EngineProcessSlotContract.processSlotIndex(HOST_PACKAGE, "$HOST_PACKAGE:v03")
        )
    }

    @Test
    fun `canonical check accepts declared range and rejects padded or out of range`() {
        assertTrue(EngineProcessSlotContract.isCanonicalProcessSlot(HOST_PACKAGE, "$HOST_PACKAGE:v0"))
        assertTrue(EngineProcessSlotContract.isCanonicalProcessSlot(HOST_PACKAGE, "$HOST_PACKAGE:v9"))
        assertTrue(EngineProcessSlotContract.isCanonicalProcessSlot(HOST_PACKAGE, "$HOST_PACKAGE:v16"))
        assertTrue(EngineProcessSlotContract.isCanonicalProcessSlot(HOST_PACKAGE, "$HOST_PACKAGE:v23"))
        assertFalse(EngineProcessSlotContract.isCanonicalProcessSlot(HOST_PACKAGE, "$HOST_PACKAGE:v03"))
        assertFalse(EngineProcessSlotContract.isCanonicalProcessSlot(HOST_PACKAGE, "$HOST_PACKAGE:v24"))
        assertFalse(EngineProcessSlotContract.isCanonicalProcessSlot(HOST_PACKAGE, "com.other:v3"))
    }

    private companion object {
        const val HOST_PACKAGE = "com.multiapp.app"
    }
}
