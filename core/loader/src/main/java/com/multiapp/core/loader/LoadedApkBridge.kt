package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import android.content.res.Resources

/**
 * Minimal reflection bridge for objects that behave like Android LoadedApk.
 *
 * The production ActivityThread integration will use this as the single place
 * that mutates LoadedApk-like runtime state. Keeping this out of LoaderFactory
 * prevents v2 hosted container from accumulating another large reflection blob.
 */
object LoadedApkBridge {

    fun inspect(target: Any): LoadedApkInspection {
        val appInfo = readField(target, "mApplicationInfo") as? ApplicationInfo
        val packageName = readField(target, "mPackageName") as? String
        return LoadedApkInspection(
            targetClassName = target.javaClass.name,
            packageName = packageName,
            applicationInfoPackageName = appInfo?.packageName
        )
    }

    fun patch(target: Any, state: LoadedApkRuntimeState): LoadedApkPatchResult {
        val patched = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        patchField(target, "mApplicationInfo", state.applicationInfo, patched, skipped)
        patchField(target, "mResources", state.resources, patched, skipped)
        patchField(target, "mClassLoader", state.classLoader, patched, skipped)
        patchField(target, "mPackageName", state.packageName, patched, skipped)
        patchField(target, "mAppDir", state.applicationInfo.sourceDir, patched, skipped)
        patchField(target, "mResDir", state.applicationInfo.publicSourceDir, patched, skipped)

        return LoadedApkPatchResult(
            targetClassName = target.javaClass.name,
            patchedFields = patched,
            skippedFields = skipped
        )
    }

    private fun patchField(
        target: Any,
        fieldName: String,
        value: Any?,
        patched: MutableList<String>,
        skipped: MutableList<String>
    ) {
        val field = findFieldInHierarchy(target.javaClass, fieldName)
        if (field == null) {
            skipped += fieldName
            return
        }
        runCatching {
            field.set(target, value)
            patched += fieldName
        }.onFailure {
            skipped += fieldName
        }
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

data class LoadedApkInspection(
    val targetClassName: String,
    val packageName: String?,
    val applicationInfoPackageName: String?
) {
    fun matchesPackage(packageName: String): Boolean =
        this.packageName == packageName || applicationInfoPackageName == packageName
}

data class LoadedApkRuntimeState(
    val packageName: String,
    val applicationInfo: ApplicationInfo,
    val resources: Resources,
    val classLoader: ClassLoader
)

data class LoadedApkPatchResult(
    val targetClassName: String,
    val patchedFields: List<String>,
    val skippedFields: List<String>
) {
    val patchedCount: Int get() = patchedFields.size
}
