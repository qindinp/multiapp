package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import java.io.File

internal object ApplicationInfoNativePathCompat {
    private const val INSTANCE_LIB_DIR = "lib"

    fun frameworkSafeNativeLibraryDir(dataDir: String?, nativeLibraryDir: String?): String {
        nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { return it }
        val root = dataDir?.takeIf { it.isNotBlank() } ?: return ""
        val libDir = File(root, INSTANCE_LIB_DIR)
        runCatching { libDir.mkdirs() }
        return libDir.absolutePath
    }

    fun applyTo(appInfo: ApplicationInfo, dataDir: String?, nativeLibraryDir: String?) {
        val safeNativeLibraryDir = frameworkSafeNativeLibraryDir(dataDir, nativeLibraryDir)
        appInfo.nativeLibraryDir = safeNativeLibraryDir
        val nativeRoot = safeNativeLibraryDir
            .takeIf { it.isNotBlank() }
            ?.let { File(it).parentFile?.absolutePath ?: it }
            .orEmpty()
        writeStringField(appInfo, "nativeLibraryRootDir", nativeRoot)
        writeStringField(appInfo, "secondaryNativeLibraryDir", safeNativeLibraryDir)
    }

    private fun writeStringField(target: Any, fieldName: String, value: String) {
        runCatching {
            target.javaClass.getField(fieldName).set(target, value)
        }
    }
}
