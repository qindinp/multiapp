package com.multiapp.core.loader

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Minimal reflection bridge for objects that behave like Android LoadedApk.
 *
 * The production ActivityThread integration will use this as the single place
 * that mutates LoadedApk-like runtime state. Keeping this out of LoaderFactory
 * prevents v2 hosted container from accumulating another large reflection blob.
 */
object LoadedApkBridge {

    private val SNAPSHOT_FIELDS = listOf(
        "mApplicationInfo",
        "mApplication",
        "mResources",
        "mClassLoader",
        "mBaseClassLoader",
        "mPackageName",
        "mAppDir",
        "mResDir",
        "mSplitAppDirs",
        "mSplitResDirs",
        "mLibDir",
        "mDataDir",
        "mDataDirFile",
        "mCredentialProtectedDataDirFile",
        "mDeviceProtectedDataDirFile"
    )

    fun inspect(target: Any): LoadedApkInspection {
        val appInfo = readField(target, "mApplicationInfo") as? ApplicationInfo
        val packageName = readField(target, "mPackageName") as? String
        return LoadedApkInspection(
            targetClassName = target.javaClass.name,
            packageName = packageName,
            applicationInfoPackageName = appInfo?.packageName
        )
    }

    fun application(target: Any): Application? =
        readField(target, "mApplication") as? Application

    fun applicationInfo(target: Any): ApplicationInfo? =
        readField(target, "mApplicationInfo") as? ApplicationInfo

    fun classLoader(target: Any): ClassLoader? =
        readField(target, "mClassLoader") as? ClassLoader

    internal fun inspectApplicationContextBinding(
        application: Application,
        expectedLoadedApk: Any
    ): LoadedApkApplicationContextBinding {
        val visited = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        var context = runCatching { application.baseContext }
            .getOrElse { error ->
                return LoadedApkApplicationContextBinding(
                    matches = false,
                    contextClassName = null,
                    wrapperDepth = 0,
                    reason = "BASE_CONTEXT_READ_FAILED:${error.javaClass.simpleName}"
                )
            }
            ?: return LoadedApkApplicationContextBinding(
                matches = false,
                contextClassName = null,
                wrapperDepth = 0,
                reason = "BASE_CONTEXT_MISSING"
            )
        var wrapperDepth = 0
        while (visited.add(context)) {
            val packageInfo = readFieldResult(context, "mPackageInfo")
            if (packageInfo.found) {
                return when {
                    packageInfo.error != null -> LoadedApkApplicationContextBinding(
                        matches = false,
                        contextClassName = context.javaClass.name,
                        wrapperDepth = wrapperDepth,
                        reason = "CONTEXT_PACKAGE_INFO_READ_FAILED:${packageInfo.error.javaClass.simpleName}"
                    )
                    packageInfo.value === expectedLoadedApk -> LoadedApkApplicationContextBinding(
                        matches = true,
                        contextClassName = context.javaClass.name,
                        wrapperDepth = wrapperDepth,
                        reason = "CONTEXT_PACKAGE_INFO_MATCH"
                    )
                    else -> LoadedApkApplicationContextBinding(
                        matches = false,
                        contextClassName = context.javaClass.name,
                        wrapperDepth = wrapperDepth,
                        reason = "CONTEXT_PACKAGE_INFO_MISMATCH"
                    )
                }
            }
            val wrapper = context as? ContextWrapper
            if (wrapper == null) {
                return LoadedApkApplicationContextBinding(
                    matches = false,
                    contextClassName = context.javaClass.name,
                    wrapperDepth = wrapperDepth,
                    reason = "CONTEXT_PACKAGE_INFO_FIELD_MISSING"
                )
            }
            context = runCatching { wrapper.baseContext }
                .getOrElse { error ->
                    return LoadedApkApplicationContextBinding(
                        matches = false,
                        contextClassName = wrapper.javaClass.name,
                        wrapperDepth = wrapperDepth,
                        reason = "CONTEXT_UNWRAP_FAILED:${error.javaClass.simpleName}"
                    )
                }
                ?: return LoadedApkApplicationContextBinding(
                    matches = false,
                    contextClassName = wrapper.javaClass.name,
                    wrapperDepth = wrapperDepth,
                    reason = "CONTEXT_BASE_MISSING"
                )
            wrapperDepth += 1
        }
        return LoadedApkApplicationContextBinding(
            matches = false,
            contextClassName = context.javaClass.name,
            wrapperDepth = wrapperDepth,
            reason = "CONTEXT_WRAPPER_CYCLE"
        )
    }

    internal fun snapshot(target: Any): LoadedApkStateSnapshot {
        val values = linkedMapOf<String, Any?>()
        val skipped = mutableListOf<String>()
        SNAPSHOT_FIELDS.forEach { fieldName ->
            val field = findFieldInHierarchy(target.javaClass, fieldName)
            if (field == null) {
                skipped += "$fieldName:FIELD_NOT_FOUND"
            } else {
                runCatching { field.get(target) }
                    .onSuccess { values[fieldName] = it }
                    .onFailure { skipped += "$fieldName:READ_FAILED:${it.javaClass.simpleName}" }
            }
        }
        return LoadedApkStateSnapshot(
            targetClassName = target.javaClass.name,
            values = values,
            skippedFieldReasons = skipped
        )
    }

    internal fun restore(target: Any, snapshot: LoadedApkStateSnapshot): LoadedApkPatchResult {
        val restored = mutableListOf<String>()
        val skipped = mutableListOf<LoadedApkSkippedField>()
        snapshot.values.forEach { (fieldName, value) ->
            val field = findFieldInHierarchy(target.javaClass, fieldName)
            if (field == null) {
                skipped += LoadedApkSkippedField(fieldName, "FIELD_NOT_FOUND_DURING_RESTORE")
            } else {
                setField(target, field, fieldName, value, restored, skipped)
            }
        }
        return LoadedApkPatchResult(
            targetClassName = target.javaClass.name,
            patchedFields = restored,
            skippedFields = skipped.map { it.fieldName },
            skippedFieldReasons = skipped.map { it.reasonEntry }
        )
    }

    internal fun verify(
        target: Any,
        state: LoadedApkRuntimeState,
        requireApplication: Boolean
    ): LoadedApkConsistencyResult {
        val failures = mutableListOf<String>()
        verifyReferenceField(target, "mApplicationInfo", state.applicationInfo, failures)
        verifyReferenceField(target, "mResources", state.resources, failures)
        verifyReferenceField(target, "mClassLoader", state.classLoader, failures)
        verifyValueField(target, "mLibDir", state.applicationInfo.nativeLibraryDir, failures)
        verifyValueField(target, "mPackageName", state.packageName, failures)
        if (requireApplication) {
            val application = state.application
            if (application == null) {
                failures += "mApplication:EXPECTED_APPLICATION_MISSING"
            } else {
                verifyReferenceField(target, "mApplication", application, failures)
            }
        }
        return LoadedApkConsistencyResult(
            targetClassName = target.javaClass.name,
            consistent = failures.isEmpty(),
            failureReasons = failures
        )
    }

    fun patch(target: Any, state: LoadedApkRuntimeState): LoadedApkPatchResult {
        val patched = mutableListOf<String>()
        val skipped = mutableListOf<LoadedApkSkippedField>()
        val appInfo = state.applicationInfo
        val sourceDir = appInfo.sourceDir
        val publicSourceDir = appInfo.publicSourceDir ?: appInfo.sourceDir
        val splitSourceDirs = appInfo.splitSourceDirs ?: emptyArray()
        val splitPublicSourceDirs = appInfo.splitPublicSourceDirs ?: splitSourceDirs
        val nativeLibraryDir = appInfo.nativeLibraryDir
        val dataDir = appInfo.dataDir
        val credentialProtectedDataDir = readStringField(appInfo, "credentialProtectedDataDir") ?: dataDir
        val deviceProtectedDataDir = readStringField(appInfo, "deviceProtectedDataDir") ?: dataDir

        patchField(target, "mApplicationInfo", appInfo, patched, skipped)
        state.application?.let { application ->
            patchField(target, "mApplication", application, patched, skipped)
        }
        patchField(target, "mResources", state.resources, patched, skipped)
        patchField(target, "mClassLoader", state.classLoader, patched, skipped)
        patchField(target, "mBaseClassLoader", state.classLoader, patched, skipped)
        patchField(target, "mPackageName", state.packageName, patched, skipped)
        patchField(target, "mAppDir", sourceDir, patched, skipped)
        patchField(target, "mResDir", publicSourceDir, patched, skipped)
        patchField(target, "mSplitAppDirs", splitSourceDirs, patched, skipped)
        patchField(target, "mSplitResDirs", splitPublicSourceDirs, patched, skipped)
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

    private fun verifyReferenceField(
        target: Any,
        fieldName: String,
        expected: Any,
        failures: MutableList<String>
    ) {
        val read = readFieldResult(target, fieldName)
        when {
            !read.found -> failures += "$fieldName:FIELD_NOT_FOUND"
            read.error != null -> failures += "$fieldName:READ_FAILED:${read.error.javaClass.simpleName}"
            read.value !== expected -> failures += "$fieldName:REFERENCE_MISMATCH"
        }
    }

    private fun verifyValueField(
        target: Any,
        fieldName: String,
        expected: Any?,
        failures: MutableList<String>
    ) {
        val read = readFieldResult(target, fieldName)
        when {
            !read.found -> failures += "$fieldName:FIELD_NOT_FOUND"
            read.error != null -> failures += "$fieldName:READ_FAILED:${read.error.javaClass.simpleName}"
            read.value != expected -> failures += "$fieldName:VALUE_MISMATCH"
        }
    }

    private fun readFieldResult(target: Any, fieldName: String): LoadedApkFieldRead {
        val field = findFieldInHierarchy(target.javaClass, fieldName)
            ?: return LoadedApkFieldRead(found = false)
        return runCatching { field.get(target) }
            .fold(
                onSuccess = { LoadedApkFieldRead(found = true, value = it) },
                onFailure = { LoadedApkFieldRead(found = true, error = it) }
            )
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

internal data class LoadedApkStateSnapshot(
    val targetClassName: String,
    val values: Map<String, Any?>,
    val skippedFieldReasons: List<String>
)

private data class LoadedApkFieldRead(
    val found: Boolean,
    val value: Any? = null,
    val error: Throwable? = null
)

internal data class LoadedApkConsistencyResult(
    val targetClassName: String,
    val consistent: Boolean,
    val failureReasons: List<String>
)

data class LoadedApkApplicationContextBinding(
    val matches: Boolean,
    val contextClassName: String?,
    val wrapperDepth: Int,
    val reason: String
)

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
    val classLoader: ClassLoader,
    val application: Application? = null,
    val binderPackageName: String = packageName
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
