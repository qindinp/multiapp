package com.multiapp.core.loader

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ELF binary patching utilities for 360 jiagu shell compatibility.
 * Extracted from LoaderFactory to reduce class size.
 */
object ElfPatcher {
    private const val TAG = "ElfPatcher"

    // TODO: Migrate patchJiaguSoIfPresent, patchJiaguSoIfEnabled,
    //       patchJiaguLoad, findBytes, readIntLE, readLongLE, readShortLE
    //       from LoaderFactory.kt

    /**
     * Find byte pattern in a byte array.
     */
    fun findBytes(data: ByteArray, pattern: ByteArray, startOffset: Int = 0): Int {
        for (i in startOffset..data.size - pattern.size) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    fun readIntLE(data: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun readLongLE(data: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    fun readShortLE(data: ByteArray, offset: Int): Short {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
    }
}
