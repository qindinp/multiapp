package de.robv.android.xposed

import java.lang.reflect.Member
import java.lang.reflect.Method

abstract class XC_MethodHook(priority: Int = 0) : Comparable<XC_MethodHook> {
    @JvmField
    val priority: Int = priority

    open fun beforeHookedMethod(param: MethodHookParam) {}
    open fun afterHookedMethod(param: MethodHookParam) {}

    override fun compareTo(other: XC_MethodHook): Int {
        return other.priority.compareTo(this.priority)
    }

    class MethodHookParam {
        @JvmField var method: Method? = null
        @JvmField var args: Array<out Any?>? = null
        @JvmField var thisObject: Any? = null
        @JvmField var result: Any? = null
        @JvmField var throwable: Throwable? = null

        var invokeOriginal: ((Array<out Any?>?) -> Any?)? = null
        internal var hookedMethod: Member? = null

        fun getThisObject(): Any = thisObject
            ?: throw IllegalStateException("thisObject is null (static method?)")

        fun getArgs(): Array<Any?> = args?.let {
            @Suppress("UNCHECKED_CAST")
            it as Array<Any?>
        } ?: arrayOf()

        fun setResult(result: Any?) {
            this.result = result
        }

        fun invokeOriginalMethod(): Any? {
            val originalArgs = args ?: arrayOf()
            return invokeOriginal?.invoke(originalArgs) ?: throw IllegalStateException(
                "Original method not available for ${hookedMethod?.name}"
            )
        }

        fun hasThrowable(): Boolean = throwable != null
    }

    class Unhook(private val method: Member, private val callback: XC_MethodHook) {
        private var unhookAction: ((Member, XC_MethodHook) -> Unit)? = null

        internal fun setUnhookAction(action: (Member, XC_MethodHook) -> Unit) {
            unhookAction = action
        }

        fun unhook() {
            unhookAction?.invoke(method, callback)
        }

        fun getHookedMethod(): Member = method
        fun getCallback(): XC_MethodHook = callback
    }
}
