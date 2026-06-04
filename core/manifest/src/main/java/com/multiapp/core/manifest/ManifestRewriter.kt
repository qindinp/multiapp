package com.multiapp.core.manifest

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 二进制 Manifest 增量修改器
 *
 * 不从零生成 manifest，而是修改原 APK 的二进制 manifest string pool。
 * 保留原 manifest 的所有结构（meta-data、intent-filter、activity-alias 等）。
 *
 * 核心原理：body 中的字符串引用是 pool INDEX（不是字节偏移），
 * 替换同 index 的字符串不影响 body 和 resource map。
 */
class ManifestRewriter {

    companion object {
        private const val TAG = "ManifestRewriter"
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
    }

    /**
     * 替换二进制 manifest string pool 中的特定字符串
     */
    fun rewrite(originalManifest: ByteArray, replacements: Map<String, String>): ByteArray {
        if (replacements.isEmpty()) return originalManifest.copyOf()

        val outerType = getUShortLE(originalManifest, 0)
        require(outerType == RES_XML_TYPE) { "Not a binary XML document" }

        val poolType = getUShortLE(originalManifest, 8)
        require(poolType == RES_STRING_POOL_TYPE) { "Expected string pool at offset 8" }
        val poolSize = getIntLE(originalManifest, 12)
        val stringCount = getIntLE(originalManifest, 16)
        val flags = getIntLE(originalManifest, 24)
        val isUtf8 = (flags and 0x100) != 0
        val stringsStart = getIntLE(originalManifest, 28)

        val strings = parseStringPool(originalManifest, 8, stringsStart, stringCount, isUtf8).toMutableList()

        val indicesToReplace = mutableMapOf<Int, String>()
        for ((old, new) in replacements) {
            val idx = strings.indexOf(old)
            if (idx >= 0) {
                indicesToReplace[idx] = new
                Timber.tag(TAG).d("Replace [$idx]: '$old' -> '$new'")
            } else {
                Timber.tag(TAG).w("String not found: '$old'")
            }
        }
        if (indicesToReplace.isEmpty()) return originalManifest.copyOf()

        // 检查是否所有替换都是等长的
        val allSameLength = indicesToReplace.all { (idx, new) ->
            strings[idx].toByteArray(Charsets.UTF_8).size == new.toByteArray(Charsets.UTF_8).size
        }

        if (allSameLength) {
            // 快速路径：原地替换
            Timber.tag(TAG).d("Fast path: in-place replacement")
            val result = originalManifest.copyOf()
            val stringsStartAbs = 8 + stringsStart
            for ((idx, new) in indicesToReplace) {
                val strOffset = getIntLE(result, 8 + 28 + idx * 4)
                var pos = stringsStartAbs + strOffset
                val b0 = result[pos].toInt() and 0xFF
                pos += if (b0 and 0x80 != 0) 2 else 1
                val b1 = result[pos].toInt() and 0xFF
                pos += if (b1 and 0x80 != 0) 2 else 1
                val newBytes = new.toByteArray(Charsets.UTF_8)
                System.arraycopy(newBytes, 0, result, pos, newBytes.size)
            }
            return result
        }

        // 慢速路径：重建 string pool
        Timber.tag(TAG).d("Slow path: rebuild string pool")
        for ((idx, new) in indicesToReplace) { strings[idx] = new }
        val newPoolBytes = encodeStringPool(strings)

        val poolEnd = 8 + poolSize
        val resMapAndBody = originalManifest.copyOfRange(poolEnd, originalManifest.size)
        val totalSize = 8 + newPoolBytes.size + resMapAndBody.size

        val result = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        result.putShort(RES_XML_TYPE.toShort())
        result.putShort(8)
        result.putInt(totalSize)
        result.put(newPoolBytes)
        result.put(resMapAndBody)
        return result.array()
    }

    private fun parseStringPool(
        data: ByteArray, poolChunkStart: Int,
        stringsStart: Int, stringCount: Int, isUtf8: Boolean
    ): List<String> {
        val strings = mutableListOf<String>()
        val offsets = IntArray(stringCount) { i -> getIntLE(data, poolChunkStart + 28 + i * 4) }

        for (i in 0 until stringCount) {
            var pos = poolChunkStart + stringsStart + offsets[i]
            if (isUtf8) {
                val b0 = data[pos].toInt() and 0xFF
                pos += if (b0 and 0x80 != 0) 2 else 1
                val b1 = data[pos].toInt() and 0xFF
                val byteLen: Int
                if (b1 and 0x80 != 0) {
                    byteLen = ((b1 and 0x7F) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
                } else { byteLen = b1; pos += 1 }
                strings.add(String(data, pos, byteLen, Charsets.UTF_8))
            } else {
                val b0 = getUShortLE(data, pos)
                val charLen: Int
                if (b0 and 0x8000 != 0) {
                    charLen = ((b0 and 0x7FFF) shl 16) or getUShortLE(data, pos + 2); pos += 4
                } else { charLen = b0; pos += 2 }
                strings.add(String(data, pos, charLen * 2, Charsets.UTF_16LE))
            }
        }
        return strings
    }

    private fun encodeStringPool(strings: List<String>): ByteArray {
        val strData = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((i, s) in strings.withIndex()) {
            offsets[i] = strData.size()
            val utf8Bytes = s.toByteArray(Charsets.UTF_8)
            val charCount = s.length; val byteCount = utf8Bytes.size
            if (charCount > 0x7F) { strData.write(0x80 or ((charCount shr 8) and 0x7F)); strData.write(charCount and 0xFF) }
            else strData.write(charCount)
            if (byteCount > 0x7F) { strData.write(0x80 or ((byteCount shr 8) and 0x7F)); strData.write(byteCount and 0xFF) }
            else strData.write(byteCount)
            strData.write(utf8Bytes); strData.write(0x00)
            val pad = (4 - (strData.size() % 4)) % 4; repeat(pad) { strData.write(0) }
        }
        val dataBytes = strData.toByteArray()
        val stringsStart = 28 + strings.size * 4
        val unpaddedTotal = stringsStart + dataBytes.size
        val total = (unpaddedTotal + 3) and 0xFFFFFFFC.toInt()
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE.toShort()); buf.putShort(28)
        buf.putInt(total); buf.putInt(strings.size); buf.putInt(0)
        buf.putInt(0x00000100); buf.putInt(stringsStart); buf.putInt(0)
        for (o in offsets) buf.putInt(o)
        buf.put(dataBytes)
        repeat(total - unpaddedTotal) { buf.put(0) }
        return buf.array()
    }

    private fun getUShortLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun getIntLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)
}
