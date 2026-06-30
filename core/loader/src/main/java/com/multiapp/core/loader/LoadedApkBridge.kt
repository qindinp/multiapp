package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import android.content.res.Resources
import java.io.File

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
        val skipped = mutableListOf<LoadedApkSkippedField>()
        val appInfo = state.applicationInfo
        val sourceDir = appInfo.sourceDir
        val publicSourceDir = appInfo.publicSourceDir ?: appInfo.sourceDir
        val nativeLibraryDir = appInfo.nativeLibraryDir
        val dataDir = appInfo.dataDir
        val credentialProtectedDataDir = readStringField(appInfo, "credentialProtectedDataDir") ?: dataDir
        val deviceProtectedDataDir = readStringField(appInfo, "deviceProtectedDataDir") ?: dataDir

        patchField(target, "mApplicationInfo", appInfo, patched, skipped)
        patchField(target, "mResources", state.resources, patched, skipped)
        patchField(target, "mClassLoader", state.classLoader, patched, skipped)
        patchField(target, "mBaseClassLoader", state.classLoader, patched, skipped)
        patchField(target, "mPackageName", state.packageName, patched, skipped)
        patchField(target, "mAppDir", sourceDir, patched, skipped)
        patchField(target, "mResDir", publicSourceDir, patched, skipped)
        patchField(target, "mLibDir", nativeLibraryDir, patched, skipped)
        patchPathField(target, "mDataDir", dataDir, patched, skipped)
        patchPathField(target, "mDataDirFile", dataDir, patched, skipped)
        patchPathField(target, "mCredentialProtectedDataDirFile", credentialProtectedDataDir, patched, skipped)
        patchPathField(target, "mDeviceProtectedDataDirFile", deviceProtectedDataDir, patched, skipped)

        return LoadedApkPatchResult(
            targetClassName = target.javaClass.name,
            patchedFields = patched,
            skippedFields = skipped.map { it.fieldName },
            skippedFieldReasons = skipped.map { it.reasonEntry }
        )
    }

    private fun patchPathField(
        target: Any,
        fieldName: String,
        path: String?,
        patched: MutableList<String>,
        skipped: MutableList<LoadedApkSkippedField>
    ) {
        val field = findFieldInHierarchy(target.javaClass, fieldName)
        if (field == null) {
            skipped += LoadedApkSkippedField(fieldName, "FIELD_NOT_FOUND")
            return
        }
        val value = when {
            path == null -> null
            field.type == File::class.java -> File(path)
            field.type == String::class.java || field.type.isAssignableFrom(String::class.java) -> path
            else -> path
        }
        setField(target, field, fieldName, value, patched, skipped)
    }

    private fun patchField(
        target: Any,
        fieldName: String,
        value: Any?,
        patched: MutableList<String>,
        skipped: MutableList<LoadedApkSkippedField>
    ) {
        val field = findFieldInHierarchy(target.javaClass, fieldName)
        if (field == null) {
            skipped += LoadedApkSkippedField(fieldName, "FIELD_NOT_FOUND")
            return
        }
        setField(target, field, fieldName, value, patched, skipped)
    }

    private fun setField(
        target: Any,
        field: java.lang.reflect.Field,
        fieldName: String,
        value: Any?,
        patched: MutableList<String>,
        skipped: MutableList<LoadedApkSkippedField>
    ) {
        if (value == null && field.type.isPrimitive) {
            skipped += LoadedApkSkippedField(fieldName, "NULL_FOR_PRIMITIVE:${field.type.name}")
            return
        }
        if (value != null && field.type.isPrimitive) {
            skipped += LoadedApkSkippedField(fieldName, "TYPE_MISMATCH:${field.type.name}<-${value.javaClass.name}")
            return
        }
        if (value != null && !field.type.isPrimitive && !field.type.isAssignableFrom(value.javaClass)) {
            skipped += LoadedApkSkippedField(fieldName, "TYPE_MISMATCH:${field.type.name}<-${value.javaClass.name}")
            return
        }
        runCatching {
            field.set(target, value)
            patched += fieldName
        }.onFailure {
            skipped += LoadedApkSkippedField(fieldName, "SET_FAILED:${it.javaClass.simpleName}")
        }
    }

    private fun readField(target: Any, fieldName: String): Any? {
        val field = findFieldInHierarchy(target.javaClass, fieldName) ?: return null
        return runCatching { field.get(target) }.getOrNull()
    }

    private fun readStringField(target: Any, fieldName: String): String? =
        readField(target, fieldName) as? String

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

data class LoadedApkSkippedField(
    val fieldName: String,
    val reason: String
) {
    val reasonEntry: String get() = "$fieldName:$reason"
}

data class LoadedApkPatchResult(
    val targetClassName: String,
    val patchedFields: List<String>,
    val skippedFields: List<String>,
    val skippedFieldReasons: List<String> = skippedFields.map { "$it:UNSPECIFIED" }
) {
    val patchedCount: Int get() = patchedFields.size
}
