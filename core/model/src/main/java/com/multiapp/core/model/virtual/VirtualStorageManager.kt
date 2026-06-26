package com.multiapp.core.model.virtual

import java.io.File

/**
 * Holds the isolated storage paths for a single virtual app instance.
 *
 * All paths are derived from a base data directory unique to the instance,
 * ensuring complete storage isolation between cloned apps.
 *
 * Directory layout:
 *   <baseDir>/<instanceId>/
 *   ├── files/
 *   ├── cache/
 *   ├── databases/
 *   ├── shared_prefs/
 *   ├── external_files/
 *   └── external_cache/
 */
data class VirtualStoragePaths(
    /** Unique identifier for this virtual instance */
    val instanceId: String,
    /** Root data directory for this instance */
    val dataDir: String,
    /** Instance-specific files directory */
    val filesDir: String,
    /** Instance-specific cache directory */
    val cacheDir: String,
    /** Instance-specific databases directory */
    val databasesDir: String,
    /** Instance-specific shared preferences directory */
    val sharedPrefsDir: String,
    /** Instance-specific external files directory (nullable for devices without external storage) */
    val externalFilesDir: String?,
    /** Instance-specific external cache directory (nullable for devices without external storage) */
    val externalCacheDir: String?
)

/**
 * Manages storage allocation and lifecycle for virtual app instances.
 *
 * Each instance gets its own isolated set of directories under a shared base.
 * This interface is pure file-system operations with no Android Context dependency.
 */
interface VirtualStorageManager {
    /**
     * Allocate and create storage directories for a new instance.
     *
     * @param instanceId unique instance identifier
     * @param baseDir base directory under which instance storage is created
     * @return the allocated storage paths (directories are guaranteed to exist)
     */
    fun allocateStorage(instanceId: String, baseDir: String): VirtualStoragePaths

    /**
     * Retrieve storage paths for an existing instance.
     *
     * @param instanceId instance identifier to look up
     * @return the storage paths if the instance directory exists, null otherwise
     */
    fun getStoragePaths(instanceId: String): VirtualStoragePaths?

    /**
     * Delete all storage for an instance, including the instance directory itself.
     *
     * @param instanceId instance identifier to delete
     * @return true if deletion succeeded, false otherwise
     */
    fun deleteStorage(instanceId: String): Boolean

    /**
     * Ensure all directories in the given paths exist. Idempotent.
     *
     * @param paths the storage paths whose directories should be created
     */
    fun ensureDirectories(paths: VirtualStoragePaths)
}

/**
 * File-system-backed implementation of [VirtualStorageManager].
 *
 * @param baseStorageDir the root directory under which all instance storage lives
 */
class FileBasedStorageManager(
    private val baseStorageDir: String
) : VirtualStorageManager {

    override fun allocateStorage(instanceId: String, baseDir: String): VirtualStoragePaths {
        val dataDir = "$baseDir/$instanceId"
        val paths = buildPaths(instanceId, dataDir)
        ensureDirectories(paths)
        return paths
    }

    override fun getStoragePaths(instanceId: String): VirtualStoragePaths? {
        val dataDir = "$baseStorageDir/$instanceId"
        val dir = File(dataDir)
        if (!dir.exists()) return null
        return buildPaths(instanceId, dataDir)
    }

    override fun deleteStorage(instanceId: String): Boolean {
        val dataDir = File("$baseStorageDir/$instanceId")
        return dataDir.deleteRecursively()
    }

    override fun ensureDirectories(paths: VirtualStoragePaths) {
        File(paths.filesDir).mkdirs()
        File(paths.cacheDir).mkdirs()
        File(paths.databasesDir).mkdirs()
        File(paths.sharedPrefsDir).mkdirs()
        paths.externalFilesDir?.let { File(it).mkdirs() }
        paths.externalCacheDir?.let { File(it).mkdirs() }
    }

    private fun buildPaths(instanceId: String, dataDir: String): VirtualStoragePaths {
        return VirtualStoragePaths(
            instanceId = instanceId,
            dataDir = dataDir,
            filesDir = "$dataDir/files",
            cacheDir = "$dataDir/cache",
            databasesDir = "$dataDir/databases",
            sharedPrefsDir = "$dataDir/shared_prefs",
            externalFilesDir = "$dataDir/external_files",
            externalCacheDir = "$dataDir/external_cache"
        )
    }
}
