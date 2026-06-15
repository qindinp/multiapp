package de.robv.android.xposed

import com.multiapp.core.hook.HookEngine
import timber.log.Timber
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class XposedBridgeImpl(
    private val hookEngine: HookEngine
) {
    companion object {
        private const val TAG = "XposedBridgeImpl"

        private val additionalFields = ConcurrentHashMap<Int, ConcurrentHashMap<String, Any>>()

        internal fun getAdditionalField(obj: Any, key: String): Any? {
            return additionalFields[System.identityHashCode(obj)]?.get(key)
        }

        internal fun setAdditionalField(obj: Any, key: String, value: Any?): Any? {
            val objFields = additionalFields.getOrPut(System.identityHashCode(obj)) {
                ConcurrentHashMap()
            }
            return if (value != null) {
                objFields.put(key, value)
            } else {
                objFields.remove(key)
            }
        }

        internal fun removeAdditionalField(obj: Any, key: String): Any? {
            return additionalFields[System.identityHashCode(obj)]?.remove(key)
        }
    }

    private val callOriginalFunctions = ConcurrentHashMap<Member, (Array<Any?>) -> Any?>()

    fun installHook(method: Member, callbacks: List<XC_MethodHook>) {
        if (method !is Method) {
            Timber.tag(TAG).w("Only Method hooks are supported, got: ${method.javaClass.simpleName}")
            return
        }

        val snapshot = callbacks.toList()
        val isStatic = Modifier.isStatic(method.modifiers)

        val success = hookEngine.hookMethodAround(method) { receiver, methodArgs, callOriginal ->
            callOriginalFunctions[method] = callOriginal

            val param = XC_MethodHook.MethodHookParam().apply {
                this.method = method
                this.thisObject = receiver
                this.args = methodArgs
                this.hookedMethod = method
                this.invokeOriginal = { origArgs ->
                    @Suppress("UNCHECKED_CAST")
                    val safeArgs = (origArgs ?: arrayOf<Any?>()) as Array<Any?>
                    if (isStatic) {
                        callOriginal(safeArgs)
                    } else {
                        callOriginal(arrayOf<Any?>(receiver, *safeArgs))
                    }
                }
            }

            for (callback in snapshot) {
                try {
                    callback.beforeHookedMethod(param)
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Error in beforeHookedMethod for ${method.name}")
                    param.throwable = t
                    break
                }
            }

            if (param.result != null) {
                return@hookMethodAround param.result
            }

            if (param.throwable != null) {
                throw param.throwable!!
            }

            @Suppress("UNCHECKED_CAST")
            val effectiveArgs = (param.args ?: methodArgs) as Array<Any?>
            val result = if (isStatic) {
                callOriginal(effectiveArgs)
            } else {
                callOriginal(arrayOf<Any?>(receiver, *effectiveArgs))
            }

            param.result = result
            param.throwable = null

            for (callback in snapshot) {
                try {
                    callback.afterHookedMethod(param)
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "Error in afterHookedMethod for ${method.name}")
                }
            }

            param.result
        }

        if (success) {
            Timber.tag(TAG).d("Installed Xposed hook: ${method.declaringClass.name}.${method.name}")
        } else {
            Timber.tag(TAG).w("Failed to install Xposed hook: ${method.declaringClass.name}.${method.name}")
        }
    }

    fun removeHook(method: Member) {
        callOriginalFunctions.remove(method)
        Timber.tag(TAG).d("Removed Xposed hook: ${method.declaringClass.name}.${method.name}")
    }

    fun invokeOriginal(method: Member, thisObj: Any?, args: Array<out Any?>?): Any? {
        val callOriginal = callOriginalFunctions[method]
        @Suppress("UNCHECKED_CAST")
        val safeArgs = (args ?: arrayOf<Any?>()) as Array<Any?>
        if (callOriginal != null) {
            val isStatic = Modifier.isStatic(method.modifiers)
            return if (isStatic) {
                callOriginal(safeArgs)
            } else {
                callOriginal(arrayOf<Any?>(thisObj, *safeArgs))
            }
        }

        if (method !is Method) {
            throw UnsupportedOperationException("Only Method invokeOriginal is supported")
        }
        method.isAccessible = true
        return if (Modifier.isStatic(method.modifiers)) {
            method.invoke(null, *safeArgs)
        } else {
            method.invoke(thisObj, *safeArgs)
        }
    }
}
