package com.multiapp.core.model.instance

import java.io.File

/**
 * Represents the isolated data root for a single virtual app instance.
 *
 * Each instance gets its own set of directories under [baseDir].
 * This ensures complete data isolation between instances.
 *
 * Directory layout under baseDir:
 *   data/          - app data (/data/user/0/<virtualPkg>/...)
 *   cache/         - cache directory
 *   files/         - files directory
 *   shared_prefs/  - SharedPreferences directory
 *   databases/     - database directory
 *   external_files/ - external files directory (optional)
 *
 * @property instanceId       Unique instance identifier.
 * @property baseDir          Root directory for this instance.
 * @property dataDir          App data directory.
 * @property cacheDir         Cache directory.
 * @property filesDir         Files directory.
 * @property sharedPrefsDir   SharedPreferences directory.
 * @property databaseDir      Database directory.
 * @property externalFilesDir External files directory, null if not available.
 */
data class InstanceDataRoot(
    val instanceId: String,
    val baseDir: File,
    val dataDir: File,
    val cacheDir: File,
    val filesDir: File,
    val sharedPrefsDir: File,
    val databaseDir: File,
    val externalFilesDir: File?
) {
    companion object {

        /**
         * Create an [InstanceDataRoot] by deriving all sub-directories from a base.
         *
         * @param instanceId Unique instance identifier.
         * @param baseDir    Root directory for this instance.
         */
        fun fromBaseDir(instanceId: String, baseDir: File): InstanceDataRoot {
            return InstanceDataRoot(
                instanceId = instanceId,
                baseDir = baseDir,
                dataDir = File(baseDir, "data"),
                cacheDir = File(baseDir, "cache"),
                filesDir = File(baseDir, "files"),
                sharedPrefsDir = File(baseDir, "shared_prefs"),
                databaseDir = File(baseDir, "databases"),
                externalFilesDir = File(baseDir, "external_files")
            )
        }
    }
}
