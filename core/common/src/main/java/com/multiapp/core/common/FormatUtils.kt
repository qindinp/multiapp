package com.multiapp.core.common

import java.io.File

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
