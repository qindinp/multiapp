package com.multiapp.core.loader

import android.content.Context
import android.content.ContextWrapper
import java.lang.reflect.Field
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Keeps the framework-created guest ContextImpl while rewriting only the
 * package identity used for calls that leave the process.
 */
internal object FrameworkApplicationContextCompat {
    private const val MAX_CONTEXT_WRAPPER_DEPTH = 32

    fun prepare(context: Context, hostPackageName: String): FrameworkApplicationContextPatchResult {
        if (hostPackageName.isBlank()) {
            return FrameworkApplicationContextPatchResult.skipped(
                targetClassName = context.javaClass.name,
                reason = "HOST_PACKAGE_NAME_MISSING"
            )
        }
        val unwrapResult = unwrap(context)
        if (unwrapResult.context == null) {
            return FrameworkApplicationContextPatchResult(
                targetClassName = context.javaClass.name,
                wrapperDepth = unwrapResult.wrapperDepth,
                cycleDetected = unwrapResult.cycleDetected,
                patchedFields = emptyList(),
                skippedFieldReasons = listOf(unwrapResult.reason ?: "CONTEXT_IMPL_UNAVAILABLE")
            )
        }
        return patchTarget(
            target = unwrapResult.context,
            hostPackageName = hostPackageName,
            wrapperDepth = unwrapResult.wrapperDepth
        )
    }

    internal fun patchTarget(
        target: Any,
        hostPackageName: String,
        wrapperDepth: Int = 0
    ): FrameworkApplicationContextPatchResult {
        val patched = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        patchField(target, "mBasePackageName", hostPackageName, "ContextImpl", patched, skipped)
        patchField(target, "mOpPackageName", hostPackageName, "ContextImpl", patched, skipped)
        patchField(target, "mPackageManager", null, "ContextImpl", patched, skipped)

        val contentResolver = readField(target, "mContentResolver")
        if (contentResolver == null) {
            skipped += "ContentResolver.mPackageName:RESOLVER_UNAVAILABLE"
        } else {
            patchField(
                target = contentResolver,
                fieldName = "mPackageName",
                value = hostPackageName,
                ownerName = "ContentResolver",
                patched = patched,
                skipped = skipped
            )
        }
        return FrameworkApplicationContextPatchResult(
            targetClassName = target.javaClass.name,
            wrapperDepth = wrapperDepth,
            cycleDetected = false,
            patchedFields = patched,
            skippedFieldReasons = skipped
        )
    }

    private fun unwrap(context: Context): ContextUnwrapResult {
        val visited = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        var current = context
        var depth = 0
        visited += current
        while (current is ContextWrapper) {
            if (depth >= MAX_CONTEXT_WRAPPER_DEPTH) {
                return ContextUnwrapResult(
                    context = null,
                    wrapperDepth = depth,
                    cycleDetected = false,
                    reason = "CONTEXT_WRAPPER_DEPTH_EXCEEDED:$MAX_CONTEXT_WRAPPER_DEPTH"
                )
            }
            val next = runCatching { current.baseContext }.getOrElse { error ->
                return ContextUnwrapResult(
                    context = null,
                    wrapperDepth = depth,
                    cycleDetected = false,
                    reason = "BASE_CONTEXT_READ_FAILED:${error.javaClass.simpleName}"
                )
            } ?: return ContextUnwrapResult(
                context = null,
                wrapperDepth = depth,
                cycleDetected = false,
                reason = "BASE_CONTEXT_NULL"
            )
            depth += 1
            if (!visited.add(next)) {
                return ContextUnwrapResult(
                    context = null,
                    wrapperDepth = depth,
                    cycleDetected = true,
                    reason = "CONTEXT_WRAPPER_CYCLE"
                )
            }
            current = next
        }
        return ContextUnwrapResult(
            context = current,
            wrapperDepth = depth,
            cycleDetected = false,
            reason = null
        )
    }

    private fun patchField(
        target: Any,
        fieldName: String,
        value: Any?,
        ownerName: String,
        patched: MutableList<String>,
        skipped: MutableList<String>
    ) {
        val evidenceName = "$ownerName.$fieldName"
        val field = findFieldInHierarchy(target.javaClass, fieldName)
        if (field == null) {
            skipped += "$evidenceName:FIELD_NOT_FOUND"
            return
        }
        if (value != null && !field.type.isAssignableFrom(value.javaClass)) {
            skipped += "$evidenceName:TYPE_MISMATCH:${field.type.name}<-${value.javaClass.name}"
            return
        }
        runCatching { field.set(target, value) }
            .onSuccess { patched += evidenceName }
            .onFailure { error -> skipped += "$evidenceName:SET_FAILED:${error.javaClass.simpleName}" }
    }

    private fun readField(target: Any, fieldName: String): Any? {
        val field = findFieldInHierarchy(target.javaClass, fieldName) ?: return null
        return runCatching { field.get(target) }.getOrNull()
    }

    private fun findFieldInHierarchy(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private data class ContextUnwrapResult(
        val context: Context?,
        val wrapperDepth: Int,
        val cycleDetected: Boolean,
        val reason: String?
    )
}

internal data class FrameworkApplicationContextPatchResult(
    val targetClassName: String,
    val wrapperDepth: Int,
    val cycleDetected: Boolean,
    val patchedFields: List<String>,
    val skippedFieldReasons: List<String>
) {
    val binderIdentityReady: Boolean
        get() = "ContextImpl.mBasePackageName" in patchedFields &&
            "ContextImpl.mOpPackageName" in patchedFields

    companion object {
        fun skipped(targetClassName: String, reason: String) =
            FrameworkApplicationContextPatchResult(
                targetClassName = targetClassName,
                wrapperDepth = 0,
                cycleDetected = false,
                patchedFields = emptyList(),
                skippedFieldReasons = listOf(reason)
            )
    }
}
