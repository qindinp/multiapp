package com.multiapp.core.loader

import java.io.File

internal object VirtualContextStorage {

    const val FILES_DIR = "files"
    const val CACHE_DIR = "cache"
    const val CODE_CACHE_DIR = "code_cache"
    const val NO_BACKUP_DIR = "no_backup"
    const val DATABASES_DIR = "databases"
    const val SHARED_PREFS_DIR = "shared_prefs"
    const val EXTERNAL_FILES_DIR = "external_files"
    const val EXTERNAL_CACHE_DIR = "external_cache"

    fun filesDir(dataRoot: String): File = ensureDir(File(dataRoot), FILES_DIR)

    fun cacheDir(dataRoot: String): File = ensureDir(File(dataRoot), CACHE_DIR)

    fun codeCacheDir(dataRoot: String): File = ensureDir(File(dataRoot), CODE_CACHE_DIR)

    fun noBackupFilesDir(dataRoot: String): File = ensureDir(File(dataRoot), NO_BACKUP_DIR)

    fun databasesDir(dataRoot: String): File = ensureDir(File(dataRoot), DATABASES_DIR)

    fun sharedPrefsDir(dataRoot: String): File = ensureDir(File(dataRoot), SHARED_PREFS_DIR)

    fun externalFilesDir(dataRoot: String, type: String?): File {
        val base = ensureDir(File(dataRoot), EXTERNAL_FILES_DIR)
        return type?.let { ensureDir(base, sanitizePathSegment(it)) } ?: base
    }

    fun externalCacheDir(dataRoot: String): File = ensureDir(File(dataRoot), EXTERNAL_CACHE_DIR)

    fun fileStreamPath(dataRoot: String, name: String): File =
        scopedChild(filesDir(dataRoot), sanitizePathSegment(name))

    fun databasePath(dataRoot: String, name: String): File =
        scopedChild(databasesDir(dataRoot), sanitizePathSegment(name))

    fun sharedPrefsPath(dataRoot: String, name: String): File =
        scopedChild(sharedPrefsDir(dataRoot), "${sanitizeSharedPrefsName(name)}.xml")

    fun appDir(dataRoot: String, name: String): File =
        scopedChild(File(dataRoot), "app_${sanitizePathSegment(name)}").apply { mkdirs() }

    fun listFileNames(dir: File): Array<String> = dir.list()?.sorted()?.toTypedArray() ?: emptyArray()

    fun evidence(
        dataRoot: String,
        operation: StorageOperation,
        logicalName: String?,
        redirectedFile: File,
        nativeLibraryDir: String? = null
    ): VirtualStorageEvidence {
        val root = File(dataRoot)
        return VirtualStorageEvidence(
            dataRoot = root.absolutePath,
            operation = operation,
            logicalName = logicalName,
            redirectedPath = redirectedFile.absolutePath,
            nativeLibraryDir = nativeLibraryDir,
            redirected = true,
            withinDataRoot = redirectedFile.normalizedPath().startsWith(root.normalizedPath() + File.separator) ||
                redirectedFile.normalizedPath() == root.normalizedPath(),
            nativeLibraryRedirected = nativeLibraryDir != null && File(nativeLibraryDir).normalizedPath()
                .startsWith(root.normalizedPath() + File.separator)
        )
    }

    fun sanitizePathSegment(name: String): String {
        require(name.isNotBlank()) { "storage path segment must not be blank" }
        require(name != "." && name != "..") { "unsafe storage path segment" }
        require('/' !in name && '\\' !in name) { "unsafe storage path segment" }
        require(name.none { it.isISOControl() }) { "unsafe storage path segment" }
        return name
    }

    private fun sanitizeSharedPrefsName(name: String): String =
        sanitizePathSegment(name.ifBlank { "default" })

    private fun ensureDir(parent: File, child: String): File =
        scopedChild(parent, child).apply { mkdirs() }

    private fun scopedChild(parent: File, child: String): File {
        val canonicalParent = parent.apply { mkdirs() }.canonicalFile
        val file = File(canonicalParent, child).canonicalFile
        require(file.parentFile == canonicalParent) { "storage path escapes scoped directory" }
        return file
    }

    private fun File.normalizedPath(): String = canonicalFile.absolutePath
}

internal enum class StorageOperation {
    DATA_DIR,
    FILES_DIR,
    CACHE_DIR,
    CODE_CACHE_DIR,
    NO_BACKUP_DIR,
    FILE_STREAM_PATH,
    OPEN_FILE_INPUT,
    OPEN_FILE_OUTPUT,
    DELETE_FILE,
    FILE_LIST,
    DATABASE_PATH,
    OPEN_OR_CREATE_DATABASE,
    DELETE_DATABASE,
    DATABASE_LIST,
    SHARED_PREFERENCES,
    APP_DIR,
    EXTERNAL_FILES_DIR,
    EXTERNAL_CACHE_DIR
}

internal data class VirtualStorageEvidence(
    val dataRoot: String,
    val operation: StorageOperation,
    val logicalName: String?,
    val redirectedPath: String,
    val nativeLibraryDir: String?,
    val redirected: Boolean,
    val withinDataRoot: Boolean,
    val nativeLibraryRedirected: Boolean
)
