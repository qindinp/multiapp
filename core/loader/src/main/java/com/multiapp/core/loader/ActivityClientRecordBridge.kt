package com.multiapp.core.loader

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo

/**
 * Reflection bridge for ActivityThread ActivityClientRecord-like objects.
 *
 * VirtualApp/BlackBox style containers restore the framework launch record so
 * later lifecycle and framework queries see the guest Activity, not the host
 * proxy slot. This bridge is intentionally small and field-name based because
 * Android versions rename internals frequently.
 */
object ActivityClientRecordBridge {

    fun patchCurrentActivityRecord(
        activityThread: Any,
        activity: Activity,
        state: ActivityClientRecordRuntimeState
    ): ActivityClientRecordPatchResult {
        val token = readField(activity, "mToken")
            ?: return ActivityClientRecordPatchResult(skippedReason = "ACTIVITY_TOKEN_MISSING")
        val record = findRecordByToken(activityThread, token)
            ?: return ActivityClientRecordPatchResult(skippedReason = "ACTIVITY_RECORD_NOT_FOUND")
        return patch(record, state)
    }

    fun patch(
        record: Any,
        state: ActivityClientRecordRuntimeState
    ): ActivityClientRecordPatchResult {
        val patched = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        patchFirstPresent(record, listOf("activityInfo", "mActivityInfo", "info", "mInfo"), state.activityInfo, patched, skipped)
        patchFirstPresent(record, listOf("intent", "mIntent"), state.intent, patched, skipped)
        if (state.loadedApk != null) {
            patchFirstPresent(record, listOf("packageInfo", "mPackageInfo", "loadedApk", "mLoadedApk"), state.loadedApk, patched, skipped)
        } else {
            skipped += "packageInfo"
        }

        return ActivityClientRecordPatchResult(
            targetClassName = record.javaClass.name,
            patchedFields = patched,
            skippedFields = skipped,
            skippedReason = null
        )
    }

    private fun findRecordByToken(activityThread: Any, token: Any): Any? {
        val activities = readField(activityThread, "mActivities") as? Map<*, *> ?: return null
        val direct = activities[token]?.unwrapWeakReference()
        if (direct != null) return direct
        return activities.values.asSequence()
            .mapNotNull { it.unwrapWeakReference() }
            .firstOrNull { record -> readField(record, "token") == token || readField(record, "mToken") == token }
    }

    private fun patchFirstPresent(
        target: Any,
        names: List<String>,
        value: Any?,
        patched: MutableList<String>,
        skipped: MutableList<String>
    ) {
        for (name in names) {
            val field = findFieldInHierarchy(target.javaClass, name) ?: continue
            runCatching {
                field.set(target, value)
                patched += name
            }.onFailure {
                skipped += name
            }
            return
        }
        skipped += names.first()
    }

    private fun Any?.unwrapWeakReference(): Any? = when (this) {
        is java.lang.ref.WeakReference<*> -> get()
        else -> this
    }

    private fun readField(target: Any, fieldName: String): Any? {
        val field = findFieldInHierarchy(target.javaClass, fieldName) ?: return null
        return runCatching { field.get(target) }.getOrNull()
    }

    private fun findFieldInHierarchy(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }
}

data class ActivityClientRecordRuntimeState(
    val activityInfo: ActivityInfo,
    val intent: Intent,
    val loadedApk: Any?
)

data class ActivityClientRecordPatchResult(
    val targetClassName: String? = null,
    val patchedFields: List<String> = emptyList(),
    val skippedFields: List<String> = emptyList(),
    val skippedReason: String? = null
) {
    val patchedCount: Int get() = patchedFields.size
    val skipped: Boolean get() = skippedReason != null
}
