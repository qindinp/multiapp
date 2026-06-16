package com.multiapp.core.model

import java.io.File

/**
 * Represents the isolated data root for a single MultiApp instance.
 *
 * Each cloned app instance gets its own set of data directories under the
 * stub package's data directory. This ensures complete data isolation between
 * multiple clones of the same app and between clones and the host.
 *
 * Directory layout:
 *   /data/data/<stubPackageName>/files/
 *   /data/data/<stubPackageName>/cache/
 *   /data/data/<stubPackageName>/databases/
 *   /data/data/<stubPackageName>/shared_prefs/
 *   /data/data/<stubPackageName>/lib/
 */
data class InstanceDataRoot(
    /** Unique instance identifier, e.g., "com.whatsapp_1700000000000" */
    val instanceId: String,
    /** Original app package name, e.g., "com.whatsapp" */
    val originalPackageName: String,
    /** Stub package name assigned to this clone, e.g., "com.whatsapp.clone1700000000000" */
    val stubPackageName: String,
    /** Instance-specific files directory: /data/data/<stubPkg>/files/ */
    val filesDir: String,
    /** Instance-specific cache directory: /data/data/<stubPkg>/cache/ */
    val cacheDir: String,
    /** Instance-specific databases directory: /data/data/<stubPkg>/databases/ */
    val databasesDir: String,
    /** Instance-specific shared preferences directory: /data/data/<stubPkg>/shared_prefs/ */
    val sharedPrefsDir: String,
    /** Instance-specific native library directory: /data/data/<stubPkg>/lib/ */
    val nativeLibDir: String
) {

    /**
     * The base data directory for this instance: /data/data/<stubPkg>/
     */
    val baseDataDir: String
        get() = File(filesDir).parent
            ?: "/data/data/$stubPackageName"

    companion object {

        /**
         * Create an [InstanceDataRoot] from a base data directory.
         * Derives all sub-directory paths from the base.
         *
         * @param instanceId          Unique instance identifier
         * @param originalPackageName The original app package name
         * @param stubPackageName     The stub package name for this clone
         * @param baseDataDir         Base data directory, e.g., "/data/data/<stubPkg>"
         */
        fun fromBaseDir(
            instanceId: String,
            originalPackageName: String,
            stubPackageName: String,
            baseDataDir: String
        ): InstanceDataRoot {
            return InstanceDataRoot(
                instanceId = instanceId,
                originalPackageName = originalPackageName,
                stubPackageName = stubPackageName,
                filesDir = "$baseDataDir/files",
                cacheDir = "$baseDataDir/cache",
                databasesDir = "$baseDataDir/databases",
                sharedPrefsDir = "$baseDataDir/shared_prefs",
                nativeLibDir = "$baseDataDir/lib"
            )
        }

        /**
         * Create an [InstanceDataRoot] using standard Android data path conventions.
         *
         * @param instanceId          Unique instance identifier
         * @param originalPackageName The original app package name
         * @param stubPackageName     The stub package name for this clone
         * @param userId              Android user ID (default 0 for primary user)
         */
        fun forUser(
            instanceId: String,
            originalPackageName: String,
            stubPackageName: String,
            userId: Int = 0
        ): InstanceDataRoot {
            val baseDataDir = if (userId == 0) {
                "/data/data/$stubPackageName"
            } else {
                "/data/user/$userId/$stubPackageName"
            }
            return fromBaseDir(instanceId, originalPackageName, stubPackageName, baseDataDir)
        }
    }
}
