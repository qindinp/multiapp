package com.multiapp.core.identity
import com.multiapp.core.model.IdentityConfig

import android.content.Context
import androidx.annotation.VisibleForTesting
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

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
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
                        val path = args.firstOrNull() as? String ?: return@hookMethod args
                        val rewritten = rewritePath(path, originalPkg, stubPkg)
                        if (rewritten != path) {
                            Timber.tag(TAG).d("File path rewrite: %s -> %s", path, rewritten)
                            arrayOf<Any?>(rewritten)
                        } else {
                            args // pass through (don't skip File constructor)
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

        private val USER_DATA_PATTERN = Regex("""/data/user/(\d+)/(.+)""")
        private val USER_DE_PATTERN = Regex("""/data/user_de/(\d+)/(.+)""")

        /**
         * Rewrite a file path string, replacing occurrences of the original
         * package name with the stub package name in data directory paths.
         *
         * 覆盖范围: /data/data/、/data/user/{id}/、/data/user_de/{id}/、
         * /storage/、/sdcard/、/mnt/ 下所有包含原始包名的路径
         */
        @VisibleForTesting
        internal fun rewritePath(path: String, originalPkg: String, stubPkg: String): String {
            if (!path.contains(originalPkg)) return path

            var result = path

            // 1. 处理 /data/data/ (等同于 /data/user/0/)
            result = result.replace(
                "/data/data/$originalPkg/",
                "/data/data/$stubPkg/"
            )

            // 2. 处理 /data/user/{id}/
            result = USER_DATA_PATTERN.replace(result) { match ->
                val userId = match.groupValues[1]
                val remaining = match.groupValues[2]
                if (remaining.startsWith("$originalPkg/") || remaining.contains("/$originalPkg/")) {
                    val rewritten = remaining.replace("$originalPkg/", "$stubPkg/")
                    "/data/user/$userId/$rewritten"
                } else {
                    match.value
                }
            }

            // 3. 处理 /data/user_de/{id}/ (设备加密存储)
            result = USER_DE_PATTERN.replace(result) { match ->
                val userId = match.groupValues[1]
                val remaining = match.groupValues[2]
                if (remaining.startsWith("$originalPkg/") || remaining.contains("/$originalPkg/")) {
                    val rewritten = remaining.replace("$originalPkg/", "$stubPkg/")
                    "/data/user_de/$userId/$rewritten"
                } else {
                    match.value
                }
            }

            // 4. 处理外部存储路径
            val externalPaths = listOf(
                "/storage/emulated/0/Android/data/",
                "/storage/emulated/0/Android/obb/",
                "/storage/emulated/0/Android/media/",
                "/sdcard/Android/data/",
                "/sdcard/Android/obb/",
                "/sdcard/Android/media/",
                "/mnt/sdcard/Android/data/"
            )

            for (prefix in externalPaths) {
                result = result.replace(
                    "$prefix$originalPkg/",
                    "$prefix$stubPkg/"
                )
            }

            return result
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
