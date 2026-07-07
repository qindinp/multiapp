package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import java.io.File

internal object ApplicationInfoNativePathCompat {
    private const val INSTANCE_LIB_DIR = "lib"

    fun frameworkSafeNativeLibraryDir(dataDir: String?, nativeLibraryDir: String?): String {
        nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { return it }
        val root = dataDir?.takeIf { it.isNotBlank() } ?: return ""
        val libDirPath = appendPathSegment(root, INSTANCE_LIB_DIR)
        runCatching { File(libDirPath).mkdirs() }
        return libDirPath
    }

    fun applyTo(appInfo: ApplicationInfo, dataDir: String?, nativeLibraryDir: String?) {
        val safeNativeLibraryDir = frameworkSafeNativeLibraryDir(dataDir, nativeLibraryDir)
        appInfo.nativeLibraryDir = safeNativeLibraryDir
        val nativeRoot = safeNativeLibraryDir
            .takeIf { it.isNotBlank() }
            ?.let { parentPath(it) ?: it }
            .orEmpty()
        writeStringField(appInfo, "nativeLibraryRootDir", nativeRoot)
        writeStringField(appInfo, "secondaryNativeLibraryDir", safeNativeLibraryDir)
    }

    private fun appendPathSegment(root: String, child: String): String {
        val separator = if ('\\' in root && !root.startsWith("/")) "\\" else "/"
        return root.trimEnd('/', '\\') + separator + child
    }

    private fun parentPath(path: String): String? {
        val trimmed = path.trimEnd('/', '\\')
        val slash = trimmed.lastIndexOf('/')
        val backslash = trimmed.lastIndexOf('\\')
        val index = maxOf(slash, backslash)
        return when {
            index <= 0 -> null
            else -> trimmed.substring(0, index)
        }
    }

    private fun writeStringField(target: Any, fieldName: String, value: String) {
        runCatching {
            target.javaClass.getField(fieldName).set(target, value)
        }
    }
}
