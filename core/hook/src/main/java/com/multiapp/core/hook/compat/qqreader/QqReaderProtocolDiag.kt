package com.multiapp.core.hook.compat.qqreader

import android.util.Log
import com.multiapp.core.hook.HookEngine

object QqReaderProtocolDiag {
    private const val TAG = "QqReaderProtocolDiag"

    @Volatile
    private var installed = false

    fun install(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        if (installed) return true
        val enabled = isTruthy(System.getProperty("debug.multiapp.online.protocol_diag")) ||
            isTruthy(getProp("debug.multiapp.online.protocol_diag"))
        if (!enabled) {
            Log.d(TAG, "protocol_diag disabled")
            return false
        }

        val ok = hookJsonFinish(hookEngine, classLoader)
        installed = ok
        Log.i(TAG, "protocol_diag installed=$ok")
        return ok
    }

    private fun hookJsonFinish(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        return try {
            val taskClass = Class.forName("com.yuewen.component.businesstask.ordinal.ReaderProtocolJSONTask", false, classLoader)
            val responseClass = Class.forName("okhttp3.Response", false, classLoader)
            val method = taskClass.getDeclaredMethod("onFinish", responseClass)
            method.isAccessible = true
            hookEngine.hookMethodPassThrough(method, beforeCallback = { receiver, args ->
                val response = args.getOrNull(0)
                val url = responseUrl(response)
                if (isInteresting(url)) {
                    Log.i(TAG, "json_onFinish_before task=${receiver?.javaClass?.name} url=$url body=${peekBody(response)}")
                }
                null
            })
        } catch (t: Throwable) {
            Log.w(TAG, "json finish hook failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun responseUrl(response: Any?): String {
        return try {
            val request = response?.javaClass?.getMethod("request")?.invoke(response)
            val url = request?.javaClass?.getMethod("url")?.invoke(request)
            url?.toString() ?: ""
        } catch (t: Throwable) {
            "error=${t.javaClass.simpleName}:${t.message}"
        }
    }

    private fun peekBody(response: Any?): String {
        return try {
            val peekBody = response?.javaClass?.getMethod("peekBody", java.lang.Long.TYPE)?.invoke(response, 8192L)
            val text = peekBody?.javaClass?.getMethod("string")?.invoke(peekBody)?.toString() ?: ""
            sanitize(text)
        } catch (t: Throwable) {
            "peek_error=${t.javaClass.simpleName}:${t.message}"
        }
    }

    private fun sanitize(value: String): String {
        if (value.isEmpty()) return "empty"
        val compact = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return if (compact.length > 4096) compact.substring(0, 4096) + "..." else compact
    }

    private fun isInteresting(url: String): Boolean {
        return url.contains("queryChapterLoad") ||
            url.contains("chapterOver") ||
            url.contains("ChapBatAuthWithPD") ||
            url.contains("chapter", ignoreCase = true) ||
            url.contains("bookchapter", ignoreCase = true)
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