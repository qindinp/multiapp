package com.multiapp.core.identity

import com.multiapp.core.model.InstanceDataRoot
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages isolated data directories for each MultiApp instance.
 *
 * Each cloned app instance gets its own [InstanceDataRoot] that defines
 * the complete set of data directories. This manager:
 * - Creates and ensures all data directories exist
 * - Maintains a registry of instance data roots
 * - Provides path redirection from original package paths to instance paths
 * - Supports both file-system level and context-level path rewriting
 */
object DataIsolationManager {

    private const val TAG = "DataIsolationManager"

    /** Registry of instance data roots keyed by instanceId */
    private val dataRoots = ConcurrentHashMap<String, InstanceDataRoot>()

    /** Reverse lookup: stubPackageName -> instanceId */
    private val stubToInstance = ConcurrentHashMap<String, String>()

    /**
     * Initialize data isolation for an instance.
     * Creates all required directories and registers the data root.
     *
     * @param dataRoot The data root configuration for this instance
     * @return true if all directories were created or already exist
     */
    fun initialize(dataRoot: InstanceDataRoot): Boolean {
        Timber.tag(TAG).d(
            "Initializing data root for instance=%s, stub=%s",
            dataRoot.instanceId,
            dataRoot.stubPackageName
        )

        val dirs = listOf(
            dataRoot.filesDir,
            dataRoot.cacheDir,
            dataRoot.databasesDir,
            dataRoot.sharedPrefsDir,
            dataRoot.nativeLibDir
        )

        var allOk = true
        for (dir in dirs) {
            val file = File(dir)
            if (!file.exists()) {
                val created = file.mkdirs()
                if (!created && !file.exists()) {
                    Timber.tag(TAG).w("Failed to create directory: %s", dir)
                    allOk = false
                }
            }
        }

        dataRoots[dataRoot.instanceId] = dataRoot
        stubToInstance[dataRoot.stubPackageName] = dataRoot.instanceId

        Timber.tag(TAG).i(
            "Data root initialized for instance=%s, dirs=%d, success=%s",
            dataRoot.instanceId,
            dirs.size,
            allOk
        )
        return allOk
    }

    /**
     * Get the data root for an instance by its ID.
     */
    fun getDataRoot(instanceId: String): InstanceDataRoot? {
        return dataRoots[instanceId]
    }

    /**
     * Get the data root by stub package name.
     */
    fun getDataRootByStubPackage(stubPackageName: String): InstanceDataRoot? {
        val instanceId = stubToInstance[stubPackageName] ?: return null
        return dataRoots[instanceId]
    }

    /**
     * Check if an instance has been initialized.
     */
    fun isInitialized(instanceId: String): Boolean {
        return dataRoots.containsKey(instanceId)
    }

    /**
     * Remove an instance's data root from the registry.
     * Does NOT delete the actual directories.
     */
    fun unregister(instanceId: String) {
        val root = dataRoots.remove(instanceId)
        if (root != null) {
            stubToInstance.remove(root.stubPackageName)
            Timber.tag(TAG).d("Unregistered data root for instance=%s", instanceId)
        }
    }

    /**
     * Redirect a file path from the original package location to the instance's
     * isolated data directory.
     *
     * Handles standard Android data paths:
     *   /data/data/<originalPkg>/...      -> /data/data/<stubPkg>/...
     *   /data/user/0/<originalPkg>/...    -> /data/user/0/<stubPkg>/...
     *   /storage/emulated/0/Android/data/<originalPkg>/... -> .../<stubPkg>/...
     *
     * @param path       The original file path
     * @param instanceId The instance ID to redirect to
     * @return The redirected path, or the original path if no redirect needed
     */
    fun redirectPath(path: String, instanceId: String): String {
        val root = dataRoots[instanceId] ?: return path
        return redirectPathInternal(path, root.originalPackageName, root.stubPackageName)
    }

    /**
     * Redirect a file path using stub package name for lookup.
     */
    fun redirectPathByStub(path: String, stubPackageName: String): String {
        val root = getDataRootByStubPackage(stubPackageName) ?: return path
        return redirectPathInternal(path, root.originalPackageName, root.stubPackageName)
    }

    /**
     * Redirect a [File] to the instance's isolated directory.
     */
    fun redirectFile(file: File, instanceId: String): File {
        val redirected = redirectPath(file.absolutePath, instanceId)
        return if (redirected != file.absolutePath) File(redirected) else file
    }

    /**
     * Get the instance-specific files directory.
     */
    fun getFilesDir(instanceId: String): String? {
        return dataRoots[instanceId]?.filesDir
    }

    /**
     * Get the instance-specific cache directory.
     */
    fun getCacheDir(instanceId: String): String? {
        return dataRoots[instanceId]?.cacheDir
    }

    /**
     * Get the instance-specific databases directory.
     */
    fun getDatabasesDir(instanceId: String): String? {
        return dataRoots[instanceId]?.databasesDir
    }

    /**
     * Get the instance-specific shared preferences directory.
     */
    fun getSharedPrefsDir(instanceId: String): String? {
        return dataRoots[instanceId]?.sharedPrefsDir
    }

    /**
     * Get the instance-specific native library directory.
     */
    fun getNativeLibDir(instanceId: String): String? {
        return dataRoots[instanceId]?.nativeLibDir
    }

    /**
     * Get the database path for a specific database name within an instance.
     */
    fun getDatabasePath(instanceId: String, dbName: String): String? {
        return dataRoots[instanceId]?.let { "${it.databasesDir}/$dbName" }
    }

    /**
     * Get the shared prefs file path for a specific prefs name within an instance.
     */
    fun getSharedPrefsPath(instanceId: String, prefsName: String): String? {
        return dataRoots[instanceId]?.let { "${it.sharedPrefsDir}/$prefsName.xml" }
    }

    /**
     * Get all registered instance IDs.
     */
    fun getRegisteredInstanceIds(): Set<String> {
        return dataRoots.keys.toSet()
    }

    /**
     * Internal path redirection logic.
     * Replaces original package name with stub package name in known data paths.
     */
    private fun redirectPathInternal(
        path: String,
        originalPkg: String,
        stubPkg: String
    ): String {
        if (!path.contains(originalPkg)) return path

        return path
            .replace("/data/data/$originalPkg/", "/data/data/$stubPkg/")
            .replace("/data/user/0/$originalPkg/", "/data/user/0/$stubPkg/")
            .replace("/data/user/10/$originalPkg/", "/data/user/10/$stubPkg/")
            .replace(
                "/storage/emulated/0/Android/data/$originalPkg/",
                "/storage/emulated/0/Android/data/$stubPkg/"
            )
            .replace(
                "/storage/emulated/0/Android/obb/$originalPkg/",
                "/storage/emulated/0/Android/obb/$stubPkg/"
            )
            .replace(
                "/storage/emulated/0/Android/media/$originalPkg/",
                "/storage/emulated/0/Android/media/$stubPkg/"
            )
            .replace("/sdcard/Android/data/$originalPkg/", "/sdcard/Android/data/$stubPkg/")
            .replace("/sdcard/Android/obb/$originalPkg/", "/sdcard/Android/obb/$stubPkg/")
            .replace("/sdcard/Android/media/$originalPkg/", "/sdcard/Android/media/$stubPkg/")
            .replace("/mnt/sdcard/Android/data/$originalPkg/", "/mnt/sdcard/Android/data/$stubPkg/")
    }
}
