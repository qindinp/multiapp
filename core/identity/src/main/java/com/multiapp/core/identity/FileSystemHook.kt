package com.multiapp.core.identity

import android.content.Context
import com.multiapp.core.hook.HookEngine
import timber.log.Timber
import java.io.File

/**
 * File system hook.
 *
 * Phase 4: Redirects file system paths so each cloned instance uses an
 * isolated data directory while the target app believes it is running in
 * its standard location.
 *
 * Hook points:
 * 1. File(String) constructor — rewrite paths containing the original package name
 * 2. Context.getFilesDir() — return instance-specific files directory
 * 3. Context.getCacheDir() — return instance-specific cache directory
 * 4. Context.getDatabasePath() — return instance-specific database path
 */
class FileSystemHook : HookPoint {

    override fun apply(config: IdentityConfig) {
        Timber.d(
            "FileSystemHook: apply called for instance=%s, stub=%s",
            config.instanceId,
            config.stubPackageName
        )
        applyInternal(config)
    }

    companion object {

        private const val TAG = "FileSystemHook"

        fun apply(config: IdentityConfig) {
            Timber.d(
                "FileSystemHook: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine.getInstance()
            val originalPkg = config.originalPackageName
            val stubPkg = config.stubPackageName

            hookFileConstructor(hookEngine, originalPkg, stubPkg)
            hookContextGetFilesDir(hookEngine, originalPkg, stubPkg)
            hookContextGetCacheDir(hookEngine, originalPkg, stubPkg)
            hookContextGetDatabasePath(hookEngine, originalPkg, stubPkg)

            Timber.tag(TAG).i(
                "FileSystemHook installed for instance=%s, path rewrite: %s -> %s",
                config.instanceId, originalPkg, stubPkg
            )
        }

        /**
         * Hook File(String) constructor to rewrite paths that contain
         * the original package name, replacing them with the stub package path.
         *
         * This catches file operations like:
         *   /data/data/<originalPkg>/files/  -> /data/data/<stubPkg>/files/
         *   /data/user/0/<originalPkg>/      -> /data/user/0/<stubPkg>/
         */
        private fun hookFileConstructor(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = File::class.java.getDeclaredConstructor(String::class.java)
                hookEngine.hookMethod(
                    method = method,
                    beforeCallback = { _, args ->
                        val path = args.firstOrNull() as? String ?: return@hookMethod null
                        val rewritten = rewritePath(path, originalPkg, stubPkg)
                        if (rewritten != path) {
                            Timber.tag(TAG).d("File path rewrite: %s -> %s", path, rewritten)
                            arrayOf<Any?>(rewritten)
                        } else {
                            null // no change
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked File(String) constructor")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook File(String) constructor")
            }
        }

        /**
         * Hook Context.getFilesDir() to return the instance-specific files directory.
         * Rewrites /data/data/<originalPkg>/files to /data/data/<stubPkg>/files.
         */
        private fun hookContextGetFilesDir(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod("getFilesDir")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewriteFileResult(result, originalPkg, stubPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked Context.getFilesDir()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Context.getFilesDir()")
            }
        }

        /**
         * Hook Context.getCacheDir() to return the instance-specific cache directory.
         */
        private fun hookContextGetCacheDir(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod("getCacheDir")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewriteFileResult(result, originalPkg, stubPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked Context.getCacheDir()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Context.getCacheDir()")
            }
        }

        /**
         * Hook Context.getDatabasePath() to return the instance-specific database path.
         */
        private fun hookContextGetDatabasePath(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = Context::class.java.getDeclaredMethod(
                    "getDatabasePath",
                    String::class.java
                )
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, _, result ->
                        rewriteFileResult(result, originalPkg, stubPkg)
                    }
                )
                Timber.tag(TAG).d("Hooked Context.getDatabasePath()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Context.getDatabasePath()")
            }
        }

        /**
         * Rewrite a file path string, replacing occurrences of the original
         * package name with the stub package name in data directory paths.
         *
         * 覆盖范围: /data/、/storage/、/sdcard/、/mnt/ 下所有包含原始包名的路径
         */
        private fun rewritePath(path: String, originalPkg: String, stubPkg: String): String {
            if (!path.contains(originalPkg)) return path

            return path
                .replace("/data/data/$originalPkg/", "/data/data/$stubPkg/")
                .replace("/data/user/0/$originalPkg/", "/data/user/0/$stubPkg/")
                .replace("/data/user/10/$originalPkg/", "/data/user/10/$stubPkg/")
                .replace("/storage/emulated/0/Android/data/$originalPkg/", "/storage/emulated/0/Android/data/$stubPkg/")
                .replace("/storage/emulated/0/Android/obb/$originalPkg/", "/storage/emulated/0/Android/obb/$stubPkg/")
                .replace("/sdcard/Android/data/$originalPkg/", "/sdcard/Android/data/$stubPkg/")
                .replace("/sdcard/Android/obb/$originalPkg/", "/sdcard/Android/obb/$stubPkg/")
                .replace("/mnt/sdcard/Android/data/$originalPkg/", "/mnt/sdcard/Android/data/$stubPkg/")
        }

        /**
         * Rewrite a File result object.
         */
        private fun rewriteFileResult(
            result: Any?,
            originalPkg: String,
            stubPkg: String
        ): Any? {
            if (result !is File) return result
            val originalPath = result.absolutePath
            val rewrittenPath = rewritePath(originalPath, originalPkg, stubPkg)
            return if (rewrittenPath != originalPath) File(rewrittenPath) else result
        }
    }
}
