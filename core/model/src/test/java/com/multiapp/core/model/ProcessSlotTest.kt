package com.multiapp.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessSlotTest {

    @Test
    fun `default values - isOccupied is false`() {
        val slot = ProcessSlot(slotIndex = 0, processName = ":p0")
        assertFalse(slot.isOccupied)
    }

    @Test
    fun `default values - pid is -1`() {
        val slot = ProcessSlot(slotIndex = 0, processName = ":p0")
        assertEquals(-1, slot.pid)
    }

    @Test
    fun `default values - assignedInstanceId is null`() {
        val slot = ProcessSlot(slotIndex = 0, processName = ":p0")
        assertNull(slot.assignedInstanceId)
    }

    @Test
    fun `copy with isOccupied change creates new instance`() {
        val original = ProcessSlot(slotIndex = 0, processName = ":p0")
        val occupied = original.copy(isOccupied = true)

        assertFalse(original.isOccupied)
        assertTrue(occupied.isOccupied)
        assertEquals(original.slotIndex, occupied.slotIndex)
        assertEquals(original.processName, occupied.processName)
    }

    @Test
    fun `copy with pid change creates new instance`() {
        val original = ProcessSlot(slotIndex = 0, processName = ":p0")
        val withPid = original.copy(pid = 12345)

        assertEquals(-1, original.pid)
        assertEquals(12345, withPid.pid)
    }

    @Test
    fun `copy with assignedInstanceId creates new instance`() {
        val original = ProcessSlot(slotIndex = 0, processName = ":p0")
        val assigned = original.copy(assignedInstanceId = "instance-abc")

        assertNull(original.assignedInstanceId)
        assertEquals("instance-abc", assigned.assignedInstanceId)
    }

    @Test
    fun `equal instances with same values`() {
        val slot1 = ProcessSlot(
            slotIndex = 0,
            processName = ":p0",
            assignedInstanceId = "inst-1",
            isOccupied = true,
            pid = 100
        )
        val slot2 = ProcessSlot(
            slotIndex = 0,
            processName = ":p0",
            assignedInstanceId = "inst-1",
            isOccupied = true,
            pid = 100
        )

        assertEquals(slot1, slot2)
        assertEquals(slot1.hashCode(), slot2.hashCode())
    }

    @Test
    fun `not equal when slotIndex differs`() {
        val slot1 = ProcessSlot(slotIndex = 0, processName = ":p0")
        val slot2 = ProcessSlot(slotIndex = 1, processName = ":p0")

        assertTrue(slot1 != slot2)
    }

    @Test
    fun `not equal when processName differs`() {
        val slot1 = ProcessSlot(slotIndex = 0, processName = ":p0")
        val slot2 = ProcessSlot(slotIndex = 0, processName = ":p1")

        assertTrue(slot1 != slot2)
    }

    @Test
    fun `toString contains slot info`() {
        val slot = ProcessSlot(
            slotIndex = 3,
            processName = ":p3",
            assignedInstanceId = "inst-x",
            isOccupied = true,
            pid = 999
        )
        val str = slot.toString()

        assertTrue(str.contains("3"))
        assertTrue(str.contains(":p3"))
        assertTrue(str.contains("inst-x"))
        assertTrue(str.contains("999"))
    }

    @Test
    fun `destructuring works`() {
        val slot = ProcessSlot(
            slotIndex = 5,
            processName = ":p5",
            assignedInstanceId = "inst-5",
            isOccupied = false,
            pid = -1
        )

        val (index, name, instanceId, occupied, pid) = slot

        assertEquals(5, index)
        assertEquals(":p5", name)
        assertEquals("inst-5", instanceId)
        assertFalse(occupied)
        assertEquals(-1, pid)
    }
}
