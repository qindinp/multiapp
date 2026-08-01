package com.multiapp.core.hook.compat.qqreader

import android.util.Log
import com.multiapp.core.hook.HookEngine
import java.io.File

object QqReaderProviderDiag {
    private const val TAG = "QqReaderProviderDiag"

    @Volatile
    private var installed = false

    fun install(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        if (installed) return true
        val enabled = isTruthy(System.getProperty("debug.multiapp.online.provider_diag")) ||
            isTruthy(getProp("debug.multiapp.online.provider_diag"))
        if (!enabled) {
            Log.d(TAG, "provider_diag disabled")
            return false
        }

        var ok = false
        ok = hookProviderPaths(hookEngine, classLoader) || ok
        ok = hookFileValidity(hookEngine, classLoader) || ok
        installed = ok
        Log.i(TAG, "provider_diag installed=$ok")
        return ok
    }

    private fun hookProviderPaths(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        return try {
            val cls = Class.forName("com.qq.reader.ywreader.component.compatible.qdaf", false, classLoader)
            val pathMethod = cls.getDeclaredMethod(
                "getOnlineChapterFilePath",
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                String::class.java,
                java.lang.Boolean.TYPE
            )
            pathMethod.isAccessible = true
            val pathOk = hookEngine.hookMethodPassThrough(pathMethod, afterCallback = { _, args, result ->
                val cid = args.getOrNull(0)
                val uuid = args.getOrNull(1)
                val bookId = args.getOrNull(2)
                val preload = args.getOrNull(3)
                val path = result as? String
                Log.i(TAG, "provider_path cid=$cid uuid=$uuid bookId=$bookId preload=$preload result=$path file=${describeFile(path)}")
                result
            })

            val resMethod = cls.getDeclaredMethod(
                "search",
                java.lang.Long.TYPE,
                java.lang.Long.TYPE,
                String::class.java,
                java.lang.Boolean.TYPE
            )
            resMethod.isAccessible = true
            val resOk = hookEngine.hookMethodPassThrough(resMethod, afterCallback = { _, args, result ->
                val cid = args.getOrNull(0)
                val uuid = args.getOrNull(1)
                val bookId = args.getOrNull(2)
                val preload = args.getOrNull(3)
                val path = result as? String
                Log.i(TAG, "provider_res_path cid=$cid uuid=$uuid bookId=$bookId preload=$preload result=$path file=${describeFile(path)}")
                result
            })
            pathOk && resOk
        } catch (t: Throwable) {
            Log.w(TAG, "provider path hook failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun hookFileValidity(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        return try {
            val cls = Class.forName("com.yuewen.reader.framework.utils.qdag", false, classLoader)
            val method = cls.getDeclaredMethod("judian", String::class.java)
            method.isAccessible = true
            hookEngine.hookMethodPassThrough(method, afterCallback = { _, args, result ->
                val path = args.getOrNull(0) as? String
                if (isInteresting(path)) {
                    Log.i(TAG, "file_validity path=$path result=$result file=${describeFile(path)}")
                }
                result
            })
        } catch (t: Throwable) {
            Log.w(TAG, "file validity hook failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun isInteresting(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val p = path.replace('\\', '/')
        return p.contains("/QQReader/Online/") &&
            (p.endsWith(".eqct") || p.endsWith(".qct") || p.endsWith(".eres"))
    }

    private fun describeFile(path: String?): String {
        if (path.isNullOrEmpty()) return "empty"
        return try {
            val file = File(path)
            "exists=${file.exists()} length=${file.length()} isFile=${file.isFile}"
        } catch (t: Throwable) {
            "error=${t.javaClass.simpleName}:${t.message}"
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