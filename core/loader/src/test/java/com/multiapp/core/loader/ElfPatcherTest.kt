package com.multiapp.core.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ElfPatcherTest {

    // -- findBytes -------------------------------------------------------------

    @Test
    fun `findBytes finds pattern at correct offset`() {
        val data = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        val pattern = byteArrayOf(0x02, 0x03)
        assertEquals(2, ElfPatcher.findBytes(data, pattern))
    }

    @Test
    fun `findBytes returns -1 when pattern not found`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val pattern = byteArrayOf(0x05, 0x06)
        assertEquals(-1, ElfPatcher.findBytes(data, pattern))
    }

    @Test
    fun `findBytes finds pattern at offset zero`() {
        val data = byteArrayOf(0x0A, 0x0B, 0x0C)
        val pattern = byteArrayOf(0x0A, 0x0B)
        assertEquals(0, ElfPatcher.findBytes(data, pattern))
    }

    @Test
    fun `findBytes finds pattern at end of data`() {
        val data = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val pattern = byteArrayOf(0x02, 0x03)
        assertEquals(2, ElfPatcher.findBytes(data, pattern))
    }

    @Test
    fun `findBytes returns -1 when pattern longer than data`() {
        val data = byteArrayOf(0x00)
        val pattern = byteArrayOf(0x00, 0x01, 0x02)
        assertEquals(-1, ElfPatcher.findBytes(data, pattern))
    }

    @Test
    fun `findBytes with startOffset skips earlier match`() {
        val data = byteArrayOf(0x01, 0x02, 0x01, 0x02, 0x03)
        val pattern = byteArrayOf(0x01, 0x02)
        // First match at 0, but searching from offset 2 should find match at 2
        assertEquals(2, ElfPatcher.findBytes(data, pattern, startOffset = 2))
    }

    @Test
    fun `findBytes with startOffset past all matches returns -1`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val pattern = byteArrayOf(0x01, 0x02)
        assertEquals(-1, ElfPatcher.findBytes(data, pattern, startOffset = 1))
    }

    @Test
    fun `findBytes finds single byte pattern`() {
        val data = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00)
        val pattern = byteArrayOf(0xFF.toByte())
        assertEquals(2, ElfPatcher.findBytes(data, pattern))
    }

    @Test
    fun `findBytes matches full pattern not partial overlap`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x01, 0x02, 0x03, 0x04)
        val pattern = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(3, ElfPatcher.findBytes(data, pattern))
    }

    // -- readIntLE -------------------------------------------------------------

    @Test
    fun `readIntLE reads little-endian integer`() {
        val data = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678, ElfPatcher.readIntLE(data, 0))
    }

    @Test
    fun `readIntLE reads zero correctly`() {
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertEquals(0, ElfPatcher.readIntLE(data, 0))
    }

    @Test
    fun `readIntLE reads max int correctly`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertEquals(Int.MAX_VALUE, ElfPatcher.readIntLE(data, 0))
    }

    @Test
    fun `readIntLE reads at nonzero offset`() {
        // Skip first 2 bytes, read from offset 2
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x01, 0x00, 0x00, 0x00)
        assertEquals(1, ElfPatcher.readIntLE(data, 2))
    }

    // -- readLongLE ------------------------------------------------------------

    @Test
    fun `readLongLE reads little-endian long`() {
        val data = byteArrayOf(
            0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01
        )
        assertEquals(0x0102030405060708L, ElfPatcher.readLongLE(data, 0))
    }

    @Test
    fun `readLongLE reads zero correctly`() {
        val data = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(0L, ElfPatcher.readLongLE(data, 0))
    }

    @Test
    fun `readLongLE reads at nonzero offset`() {
        val data = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(),
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        assertEquals(1L, ElfPatcher.readLongLE(data, 2))
    }

    // -- readShortLE -----------------------------------------------------------

    @Test
    fun `readShortLE reads little-endian short`() {
        val data = byteArrayOf(0x34, 0x12)
        assertEquals(0x1234.toShort(), ElfPatcher.readShortLE(data, 0))
    }

    @Test
    fun `readShortLE reads zero correctly`() {
        val data = byteArrayOf(0x00, 0x00)
        assertEquals(0.toShort(), ElfPatcher.readShortLE(data, 0))
    }

    @Test
    fun `readShortLE reads negative one correctly`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals((-1).toShort(), ElfPatcher.readShortLE(data, 0))
    }

    @Test
    fun `readShortLE reads at nonzero offset`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x37, 0x01)
        assertEquals(0x0137.toShort(), ElfPatcher.readShortLE(data, 2))
    }
}
