package com.multiapp.core.loader

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * Wraps the Application base context to redirect external storage paths into
 * the instance sandbox, avoiding EACCES when guest code tries to write to the
 * real app's external data directory (owned by a different UID).
 *
 * Only the methods that resolve to Android/data/<package>/... are overridden:
 * - [getExternalFilesDir]
 * - [getExternalCacheDir]
 * - [getObbDir]
 * - [getExternalFilesDirs]
 * - [getExternalCacheDirs]
 * - [getExternalMediaDirs]
 */
internal class ExternalStorageRedirectContextWrapper(
    base: Context,
    private val dataRoot: String
) : ContextWrapper(base) {

    override fun getExternalFilesDir(type: String?): File? {
        return VirtualContextStorage.externalFilesDir(dataRoot, type)
    }

    override fun getExternalCacheDir(): File? {
        return VirtualContextStorage.externalCacheDir(dataRoot)
    }

    override fun getObbDir(): File? {
        return VirtualContextStorage.externalFilesDir(dataRoot, "obb")
    }

    override fun getExternalFilesDirs(type: String?): Array<File> {
        val dir = getExternalFilesDir(type) ?: return emptyArray()
        return arrayOf(dir)
    }

    override fun getExternalCacheDirs(): Array<File> {
        val dir = getExternalCacheDir() ?: return emptyArray()
        return arrayOf(dir)
    }

    override fun getExternalMediaDirs(): Array<File> {
        return arrayOf(VirtualContextStorage.externalFilesDir(dataRoot, "media"))
    }

    companion object {
        /**
         * Replaces the Application base context with this wrapper so that
         * [Context.getExternalFilesDir] and friends return sandbox paths.
         *
         * Must be called after LoadedApk.makeApplication (attachBaseContext already
         * consumed by the framework), before Application.onCreate.
         *
         * @return true if the redirect was applied, false if the base context is
         *         unavailable (e.g. in a JVM unit test without Android mocking).
         */
        fun redirectApplicationContext(application: android.app.Application, dataRoot: String): Boolean {
            return runCatching {
                val originalBase = application.baseContext
                val wrapper = ExternalStorageRedirectContextWrapper(originalBase, dataRoot)
                val mBaseField = ContextWrapper::class.java.getDeclaredField("mBase")
                mBaseField.isAccessible = true
                mBaseField.set(application, wrapper)
                true
            }.getOrDefault(false)
        }
    }
}
