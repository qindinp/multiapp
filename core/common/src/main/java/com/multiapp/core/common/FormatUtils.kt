package com.multiapp.core.common

import java.io.File

/**
 * Mask sensitive value for safe logging.
 * Shows first 4 and last 4 characters, replaces middle with "****".
 * Examples: "861234567890123" -> "8612****0123"
 *           "abcdef1234567890" -> "abcd****7890"
 *           "short"            -> "****" (too short to mask)
 */
fun maskSensitive(value: String): String {
    if (value.length <= 8) return "****"
    return value.take(4) + "****" + value.takeLast(4)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return if (digitGroups == 0) {
        "${bytes} B"
    } else {
        String.format("%.1f %s", value, units[digitGroups])
    }
}

fun getDirSize(dir: File): Long {
    var size = 0L
    if (dir.isDirectory) {
        dir.listFiles()?.forEach { child ->
            size += if (child.isDirectory) getDirSize(child) else child.length()
        }
    } else {
        size = dir.length()
    }
    return size
}
