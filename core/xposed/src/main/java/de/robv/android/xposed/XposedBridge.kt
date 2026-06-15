package de.robv.android.xposed

import timber.log.Timber
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object XposedBridge {
    private const val TAG = "XposedBridge"

    private val hookCallbacks = ConcurrentHashMap<Member, CopyOnWriteArrayList<XC_MethodHook>>()
    private var bridgeImpl: XposedBridgeImpl? = null

    @JvmStatic
    fun init(impl: XposedBridgeImpl) {
        bridgeImpl = impl
        Timber.tag(TAG).i("XposedBridge initialized")
    }

    @JvmStatic
    fun hookMethod(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val callbacks = hookCallbacks.getOrPut(method) { CopyOnWriteArrayList() }
        callbacks.add(callback)
        callbacks.sort()

        bridgeImpl?.installHook(method, callbacks)
            ?: Timber.tag(TAG).w("Bridge not initialized, hook queued for ${method.name}")

        val unhook = XC_MethodHook.Unhook(method, callback)
        unhook.setUnhookAction { m, cb ->
            unhookMethod(m, cb)
        }
        return unhook
    }

    @JvmStatic
    fun unhookMethod(method: Member, callback: XC_MethodHook) {
        val callbacks = hookCallbacks[method] ?: return
        callbacks.remove(callback)
        if (callbacks.isEmpty()) {
            hookCallbacks.remove(method)
            bridgeImpl?.removeHook(method)
        }
    }

    @JvmStatic
    fun invokeOriginalMethod(method: Member, thisObj: Any?, args: Array<out Any?>?): Any? {
        return bridgeImpl?.invokeOriginal(method, thisObj, args)
            ?: throw IllegalStateException("Bridge not initialized")
    }

    internal fun getCallbacks(method: Member): List<XC_MethodHook> {
        return hookCallbacks[method]?.toList() ?: emptyList()
    }
}
