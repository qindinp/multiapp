package com.multiapp.core.loader

import android.app.Activity
import android.app.Fragment
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.UserHandle
import android.util.Log
import java.lang.reflect.InvocationTargetException

class IntentRemappingInstrumentation(
    private val base: Instrumentation,
    private val originalPackageName: String,
    private val stubPackageName: String,
    private val beforeActivityLifecycle: ((Activity, String) -> Unit)? = null,
    private val afterActivityLifecycle: ((Activity, String) -> Unit)? = null
) : Instrumentation() {

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent?,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        return callBase(
            arrayOf(
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Integer.TYPE,
                Bundle::class.java
            ),
            who,
            contextThread,
            token,
            target,
            remap(intent),
            requestCode,
            options
        )
    }

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: String?,
        intent: Intent?,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        return callBase(
            arrayOf(
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                String::class.java,
                Intent::class.java,
                Integer.TYPE,
                Bundle::class.java
            ),
            who,
            contextThread,
            token,
            target,
            remap(intent),
            requestCode,
            options
        )
    }

    @Suppress("DEPRECATION")
    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Fragment?,
        intent: Intent?,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        return callBase(
            arrayOf(
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Fragment::class.java,
                Intent::class.java,
                Integer.TYPE,
                Bundle::class.java
            ),
            who,
            contextThread,
            token,
            target,
            remap(intent),
            requestCode,
            options
        )
    }

    fun execStartActivity(
        who: Context?,
        contextThread: IBinder?,
        token: IBinder?,
        target: Activity?,
        intent: Intent?,
        requestCode: Int,
        options: Bundle?,
        user: UserHandle?
    ): ActivityResult? {
        return callBase(
            arrayOf(
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Integer.TYPE,
                Bundle::class.java,
                UserHandle::class.java
            ),
            who,
            contextThread,
            token,
            target,
            remap(intent),
            requestCode,
            options,
            user
        )
    }

    private fun remap(intent: Intent?): Intent? {
        if (intent == null) return null
        val remapped = Intent(intent)
        rewriteIntent(remapped)
        remapped.selector?.let { selector ->
            val newSelector = Intent(selector)
            rewriteIntent(newSelector)
            remapped.selector = newSelector
        }
        return remapped
    }

    private fun rewriteIntent(intent: Intent) {
        val component = intent.component
        if (component?.packageName == originalPackageName) {
            val rewritten = ComponentName(stubPackageName, component.className)
            intent.component = rewritten
            Log.d(TAG, "remap component: $component -> $rewritten")
        }
        if (intent.`package` == originalPackageName) {
            intent.setPackage(stubPackageName)
            Log.d(TAG, "remap package: $originalPackageName -> $stubPackageName")
        }
    }

    private fun callBase(parameterTypes: Array<Class<*>>, vararg args: Any?): ActivityResult? {
        try {
            val method = findExecStartActivity(parameterTypes)
            method.isAccessible = true
            return method.invoke(base, *args) as? ActivityResult
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } catch (e: Throwable) {
            Log.e(TAG, "execStartActivity delegate failed", e)
            throw RuntimeException(e)
        }
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        beforeActivityLifecycle?.invoke(activity, "callActivityOnCreate")
        try {
            base.callActivityOnCreate(activity, icicle)
        } finally {
            afterActivityLifecycle?.invoke(activity, "callActivityOnCreate")
        }
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?
    ) {
        beforeActivityLifecycle?.invoke(activity, "callActivityOnCreatePersistable")
        try {
            base.callActivityOnCreate(activity, icicle, persistentState)
        } finally {
            afterActivityLifecycle?.invoke(activity, "callActivityOnCreatePersistable")
        }
    }

    override fun callActivityOnResume(activity: Activity) {
        beforeActivityLifecycle?.invoke(activity, "callActivityOnResume")
        base.callActivityOnResume(activity)
    }

    override fun callActivityOnStop(activity: Activity) {
        beforeActivityLifecycle?.invoke(activity, "callActivityOnStop")
        base.callActivityOnStop(activity)
    }

    private fun findExecStartActivity(parameterTypes: Array<Class<*>>) =
        generateSequence(base.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz ->
                try {
                    clazz.getDeclaredMethod("execStartActivity", *parameterTypes)
                } catch (_: NoSuchMethodException) {
                    null
                }
            }
            .first()

    companion object {
        private const val TAG = "IntentRemap"
    }
}
