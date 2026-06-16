package com.multiapp.core.loader

import android.util.Log
import java.io.File

/**
 * File permission utilities.
 * Extracted from LoaderFactory to reduce class size.
 */
object FilePermissions {
    private const val TAG = "FilePermissions"

    fun ensureReadOnly(file: File) {
        if (file.exists()) {
            file.setReadOnly()
        }
    }

    fun ensureWritableFile(file: File) {
        if (file.exists()) {
            file.setWritable(true)
        }
    }

    fun ensureReadOnlyTree(dir: File) {
        dir.walkTopDown().forEach { it.setReadOnly() }
    }

    fun ensureWritableTree(dir: File) {
        dir.walkTopDown().forEach { it.setWritable(true, false) }
    }

    fun ensureWritableDir(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        dir.setWritable(true, false)
        dir.setReadable(true, false)
    }
}
