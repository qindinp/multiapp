package com.multiapp.core.loader

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Debug logging utilities.
 * Extracted from LoaderFactory to reduce class size.
 */
object DebugLogWriter {
    private const val TAG = "MultiApp"

    val buffer = CopyOnWriteArrayList<String>()

    fun logD(msg: String) {
        Log.d(TAG, msg)
        buffer.add("${timestamp()} D/$TAG: $msg")
    }

    fun logE(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        buffer.add("${timestamp()} E/$TAG: $msg${t?.let { " ${it.message}" } ?: ""}")
    }

    fun logW(msg: String) {
        Log.w(TAG, msg)
        buffer.add("${timestamp()} W/$TAG: $msg")
    }

    fun writeDebugLogToFile(file: File) {
        try {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                buffer.forEach { writer.write(it); writer.newLine() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log", e)
        }
    }

    fun dumpDebugLogToLogcat() {
        buffer.forEach { Log.d(TAG, it) }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    }
}
