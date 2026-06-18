package com.multiapp.core.hook

import android.app.Activity
import android.content.Context
import android.util.Log
import java.lang.reflect.Method

object QqReaderYwLoginJavaDiag {
    private const val TAG = "QqReaderYwLoginJavaDiag"

    @Volatile
    private var installed = false

    fun install(hookEngine: HookEngine, classLoader: ClassLoader): Boolean {
        if (installed) return true
        if (isDisabled("debug.multiapp.ywlogin.java_diag")) {
            Log.d(TAG, "java_diag disabled")
            return false
        }

        return try {
            val ywLoginClass = Class.forName("com.yuewen.ywlogin.YWLogin", false, classLoader)
            val callbackClass = Class.forName("com.yuewen.ywlogin.login.YWCallBack", false, classLoader)

            val results = listOf(
                hookPwdLogin(hookEngine, ywLoginClass, callbackClass),
                hookSendPhoneCode(hookEngine, ywLoginClass, callbackClass),
                hookPhoneLogin(hookEngine, ywLoginClass, callbackClass)
            )
            val ok = results.any { it }
            installed = ok
            Log.i(TAG, "java login diag installed=$ok results=$results")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "java login diag install failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun hookPwdLogin(
        hookEngine: HookEngine,
        ywLoginClass: Class<*>,
        callbackClass: Class<*>
    ): Boolean {
        val method = ywLoginClass.getDeclaredMethod(
            "pwdLogin",
            Activity::class.java,
            String::class.java,
            String::class.java,
            callbackClass
        )
        method.isAccessible = true
        return hookVoidLoginMethod(hookEngine, method, "pwdLogin") { args ->
            "activity=${args.getOrNull(0)?.javaClass?.name} account=${mask(args.getOrNull(1))} " +
                "passwordLen=${(args.getOrNull(2) as? String)?.length ?: -1} callback=${args.getOrNull(3)?.javaClass?.name}"
        }
    }

    private fun hookSendPhoneCode(
        hookEngine: HookEngine,
        ywLoginClass: Class<*>,
        callbackClass: Class<*>
    ): Boolean {
        val method = ywLoginClass.getDeclaredMethod(
            "sendPhoneCode",
            Context::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            callbackClass
        )
        method.isAccessible = true
        return hookVoidLoginMethod(hookEngine, method, "sendPhoneCode") { args ->
            "context=${args.getOrNull(0)?.javaClass?.name} phone=${mask(args.getOrNull(1))} " +
                "type=${args.getOrNull(2)} scene=${args.getOrNull(3)} callback=${args.getOrNull(4)?.javaClass?.name}"
        }
    }

    private fun hookPhoneLogin(
        hookEngine: HookEngine,
        ywLoginClass: Class<*>,
        callbackClass: Class<*>
    ): Boolean {
        val method = ywLoginClass.getDeclaredMethod(
            "phoneLogin",
            String::class.java,
            String::class.java,
            String::class.java,
            callbackClass
        )
        method.isAccessible = true
        return hookVoidLoginMethod(hookEngine, method, "phoneLogin") { args ->
            "phone=${mask(args.getOrNull(0))} codeLen=${(args.getOrNull(1) as? String)?.length ?: -1} " +
                "area=${args.getOrNull(2)} callback=${args.getOrNull(3)?.javaClass?.name}"
        }
    }

    private fun hookVoidLoginMethod(
        hookEngine: HookEngine,
        method: Method,
        name: String,
        describe: (Array<Any?>) -> String
    ): Boolean {
        return try {
            hookEngine.hookMethodAround(method) { _, args, callOriginal ->
                Log.w(TAG, "$name before ${describe(args)}")
                try {
                    callOriginal(args)
                    Log.i(TAG, "$name original returned")
                } catch (t: Throwable) {
                    if (!isMissingNative(t)) throw t
                    Log.e(TAG, "$name native missing; suppressing process crash and notifying callback: ${t.message}", t)
                    notifyError(args.lastOrNull(), -90101, "QQ Reader login native is not registered")
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$name hook failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    private fun notifyError(callback: Any?, code: Int, message: String) {
        if (callback == null) return
        try {
            val onError = callback.javaClass.methods.firstOrNull {
                it.name == "onError" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java
            }
            if (onError == null) {
                Log.w(TAG, "callback onError(int,String) not found: ${callback.javaClass.name}")
                return
            }
            onError.isAccessible = true
            onError.invoke(callback, code, message)
            Log.w(TAG, "callback onError invoked code=$code")
        } catch (t: Throwable) {
            Log.e(TAG, "callback onError failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun isMissingNative(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is UnsatisfiedLinkError && cur.message?.contains("No implementation found") == true) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun mask(value: Any?): String {
        val text = value as? String ?: return "null"
        if (text.length <= 4) return "***"
        return "${text.take(2)}***${text.takeLast(2)}"
    }

    private fun isDisabled(name: String): Boolean {
        val sys = System.getProperty(name)
        val prop = getProp(name)
        return isFalse(sys) || isFalse(prop)
    }

    private fun isFalse(value: String?): Boolean {
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
