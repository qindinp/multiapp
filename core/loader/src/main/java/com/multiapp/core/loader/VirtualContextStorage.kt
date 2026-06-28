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
        File(filesDir(dataRoot), sanitizePathSegment(name))

    fun databasePath(dataRoot: String, name: String): File =
        File(databasesDir(dataRoot), sanitizePathSegment(name))

    fun sharedPrefsPath(dataRoot: String, name: String): File =
        File(sharedPrefsDir(dataRoot), "${sanitizePathSegment(name.ifBlank { "default" })}.xml")

    fun listFileNames(dir: File): Array<String> = dir.list()?.sorted()?.toTypedArray() ?: emptyArray()

    fun sanitizePathSegment(name: String): String = name
        .replace('/', '_')
        .replace('\\', '_')
        .ifBlank { "default" }

    private fun ensureDir(parent: File, child: String): File =
        File(parent, child).apply { mkdirs() }
}