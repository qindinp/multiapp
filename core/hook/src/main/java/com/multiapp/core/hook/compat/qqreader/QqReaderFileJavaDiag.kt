package com.multiapp.core.hook.compat.qqreader

import android.util.Log
import com.multiapp.core.hook.HookEngine
import java.io.File

object QqReaderFileJavaDiag {
    private const val TAG = "QqReaderFileJavaDiag"
    @Volatile
    private var installed = false

    fun install(hookEngine: HookEngine): Boolean {
        if (installed) return true
        val enabled = isTruthy(System.getProperty("debug.multiapp.online.java_file_diag")) ||
            isTruthy(getProp("debug.multiapp.online.java_file_diag"))
        if (!enabled) {
            Log.d(TAG, "java_file_diag disabled")
            return false
        }

        val existsOk = hookFileMethod(hookEngine, "exists")
        val lengthOk = hookFileMethod(hookEngine, "length")
        val listFilesOk = hookFileMethod(hookEngine, "listFiles")
        val deleteOk = hookFileMethod(hookEngine, "delete")
        val ok = existsOk && lengthOk && listFilesOk && deleteOk
        installed = ok
        Log.i(TAG, "java_file_diag installed=$ok")
        return ok
    }

    private fun hookFileMethod(hookEngine: HookEngine, name: String): Boolean {
        return try {
            val method = File::class.java.getDeclaredMethod(name)
            method.isAccessible = true
            hookEngine.hookMethodPassThrough(method, afterCallback = { receiver, _, result ->
                val file = receiver as? File
                val path = file?.absolutePath ?: file?.path
                if (isInteresting(path)) {
                    val rendered = when (result) {
                        is Array<*> -> "arraySize=${result.size} names=${result.filterIsInstance<File>().take(12).joinToString(",") { it.name + ":" + safeLen(it) }}"
                        else -> result?.toString() ?: "null"
                    }
                    Log.i(TAG, "java_file_diag method=$name path=$path result=$rendered")
                    if (name == "delete" && shouldPrintDeleteStack(path)) {
                        Log.i(TAG, "java_file_diag delete_stack path=$path ${shortStack()}")
                    }
                }
                result
            })
        } catch (t: Throwable) {
            Log.w(TAG, "java_file_diag hook $name failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun isInteresting(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val p = path.replace('\\', '/')
        if (!p.contains("/QQReader/Online/")) return false
        return p.endsWith(".eqct") ||
            p.endsWith(".eres") ||
            p.contains("/.mini_") ||
            p.endsWith("chapter.q") ||
            p.endsWith("book.meta") ||
            p.endsWith("adv.m") ||
            p.contains("_s") ||
            p.contains("_ALL_o")
    }

    private fun shouldPrintDeleteStack(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val p = path.replace('\\', '/')
        return p.endsWith(".eqct") || p.endsWith(".eres") || p.contains("/.mini_")
    }

    private fun shortStack(): String {
        return Throwable().stackTrace
            .asSequence()
            .filterNot { it.className == QqReaderFileJavaDiag::class.java.name }
            .filterNot { it.className.startsWith("com.multiapp.core.hook.") }
            .take(10)
            .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    }

    private fun safeLen(file: File): Long {
        return try {
            file.length()
        } catch (_: Throwable) {
            -2L
        }
    }

    private fun isTruthy(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value == "1" || value.equals("true", true) || value.equals("yes", true) || value.equals("on", true)
    }

    private fun getProp(name: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getDeclaredMethod("get", String::class.java, String::class.java)
            method.invoke(null, name, "") as? String
        } catch (_: Throwable) {
            null
        }
    }
}