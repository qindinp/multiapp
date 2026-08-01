package com.multiapp.core.hook.compat.qqreader

import android.util.Log
import com.multiapp.core.hook.HookEngine
import java.io.File

object QqReaderEqctPlaintextCompat {
    private const val TAG = "QqReaderEqctCompat"

    @Volatile
    private var installed = false

    fun install(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        if (installed) return true
        val enabled = isEnabledByDefault("debug.multiapp.online.materialize_eqct")
        if (!enabled) {
            Log.d(TAG, "eqct plaintext compat disabled")
            return false
        }

        return try {
            val cls = Class.forName("com.qq.reader.cservice.onlineread.qdae", false, classLoader)
            val method = cls.getDeclaredMethod(
                "search",
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            val ok = hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                val sourcePath = args.getOrNull(0) as? String
                val bookId = args.getOrNull(1) as? String
                val cid = args.getOrNull(2) as? Int ?: -1
                if (shouldReturnPlaintext(sourcePath, bookId, cid)) {
                    val file = File(sourcePath!!)
                    val bytes = file.readBytes()
                    Log.i(TAG, "eqct plaintext return path=$sourcePath bookId=$bookId cid=$cid size=${bytes.size}")
                    bytes
                } else {
                    callOriginal(args)
                }
            }
            installed = ok
            Log.i(TAG, "eqct plaintext compat installed=$ok")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "eqct plaintext compat install failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun shouldReturnPlaintext(sourcePath: String?, bookId: String?, cid: Int): Boolean {
        if (sourcePath.isNullOrEmpty() || bookId.isNullOrEmpty() || cid <= 0) return false
        val normalized = sourcePath.replace('\\', '/')
        if (!normalized.contains("/QQReader/Online/$bookId/")) return false
        if (!normalized.endsWith("/$cid.eqct")) return false
        val file = File(sourcePath)
        if (!file.isFile || file.length() <= 0L) return false
        val marker = File(file.parentFile, ".mini_${cid}.txt")
        return marker.isFile && marker.length() > 0L
    }

    private fun isEnabledByDefault(name: String): Boolean {
        val sys = System.getProperty(name)
        val prop = getProp(name)
        return !isDisabled(sys) && !isDisabled(prop)
    }

    private fun isDisabled(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value == "0" || value.equals("false", true) || value.equals("off", true)
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