package com.multiapp.core.identity

import com.multiapp.core.hook.HookEngine
import timber.log.Timber
import java.io.File

/**
 * dlopen hook.
 *
 * Phase 4: Redirects native library loading (dlopen / System.loadLibrary)
 * so that each cloned instance loads .so files from its own isolated path,
 * preventing cross-instance symbol conflicts and enabling per-instance
 * native library customization.
 *
 * Hook points:
 * 1. System.loadLibrary() — redirect ClassLoader to original APK's
 * 2. Runtime.nativeLoad() — rewrite library path for dlopen
 */
class DlopenHook : HookPoint {

    override fun apply(config: IdentityConfig, hookEngine: HookEngine) {
        Timber.d(
            "DlopenHook: apply called for instance=%s, stub=%s",
            config.instanceId,
            config.stubPackageName
        )
        applyInternal(config)
    }

    companion object {

        private const val TAG = "DlopenHook"

        fun apply(config: IdentityConfig) {
            Timber.d(
                "DlopenHook: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine.getInstance()
            val originalPkg = config.originalPackageName
            val stubPkg = config.stubPackageName

            // 只 hook Runtime.nativeLoad，不 hook System.loadLibrary
            // hook System.loadLibrary 的 beforeCallback 返回后仍会执行原始方法
            // nativeLoad 是更底层的入口，可以直接改路径
            hookRuntimeNativeLoad(hookEngine, originalPkg, stubPkg)

            Timber.tag(TAG).i(
                "DlopenHook installed for instance=%s",
                config.instanceId
            )
        }

        /**
         * Hook System.loadLibrary() to redirect the ClassLoader used for
         * native library loading.
         *
         * When the cloned app calls System.loadLibrary("foo"), we intercept
         * and ensure the library is loaded from the original APK's native
         * library path rather than the stub's path.
         */
        private fun hookSystemLoadLibrary(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = System::class.java.getDeclaredMethod(
                    "loadLibrary",
                    String::class.java
                )
                hookEngine.hookMethod(
                    method = method,
                    beforeCallback = { _, args ->
                        val libName = args.firstOrNull() as? String
                        if (libName != null) {
                            Timber.tag(TAG).d(
                                "System.loadLibrary(%s) intercepted",
                                libName
                            )
                            // Try to redirect to the original APK's ClassLoader
                            val redirected = redirectLibraryLoad(libName, originalPkg, stubPkg)
                            if (redirected) {
                                // Return a modified args array with the resolved path
                                // This triggers an explicit load instead of loadLibrary
                                val resolvedPath = resolveNativeLibPath(libName, originalPkg)
                                if (resolvedPath != null) {
                                    Timber.tag(TAG).d(
                                        "Redirecting loadLibrary(%s) -> %s",
                                        libName, resolvedPath
                                    )
                                    // Use Runtime.load() with the full path instead
                                    try {
                                        Runtime.getRuntime().load(resolvedPath)
                                        // Return empty args to skip the original loadLibrary call
                                        return@hookMethod arrayOf<Any?>(libName)
                                    } catch (e: Exception) {
                                        Timber.tag(TAG).w(
                                            "Failed to load from redirected path: %s",
                                            e.message
                                        )
                                    }
                                }
                            }
                        }
                        null // no change
                    }
                )
                Timber.tag(TAG).d("Hooked System.loadLibrary()")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook System.loadLibrary()")
            }
        }

        /**
         * Hook Runtime.nativeLoad() to rewrite the library path before
         * the actual dlopen call.
         *
         * nativeLoad is the internal method called by System.load() and
         * System.loadLibrary(). We intercept it to redirect the path.
         */
        private fun hookRuntimeNativeLoad(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val runtimeClass = Runtime::class.java

                // nativeLoad has different signatures across API levels
                // Try the 3-arg version first (API 27+)
                val method = try {
                    runtimeClass.getDeclaredMethod(
                        "nativeLoad",
                        String::class.java,
                        ClassLoader::class.java,
                        String::class.java
                    )
                } catch (_: NoSuchMethodException) {
                    // Fallback to 2-arg version
                    try {
                        runtimeClass.getDeclaredMethod(
                            "nativeLoad",
                            String::class.java,
                            ClassLoader::class.java
                        )
                    } catch (_: NoSuchMethodException) {
                        null
                    }
                }

                if (method != null) {
                    hookEngine.hookMethod(
                        method = method,
                        beforeCallback = { _, args ->
                            val libraryPath = args.firstOrNull() as? String
                                ?: return@hookMethod null
                            val rewrittenPath = rewriteLibraryPath(
                                libraryPath, originalPkg, stubPkg
                            )
                            if (rewrittenPath != libraryPath) {
                                Timber.tag(TAG).d(
                                    "nativeLoad path rewrite: %s -> %s",
                                    libraryPath, rewrittenPath
                                )
                                val newArgs = args.copyOf()
                                newArgs[0] = rewrittenPath
                                newArgs
                            } else {
                                null
                            }
                        }
                    )
                    Timber.tag(TAG).d("Hooked Runtime.nativeLoad()")
                } else {
                    Timber.tag(TAG).w("Could not find Runtime.nativeLoad() method")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Runtime.nativeLoad()")
            }
        }

        /**
         * Check if a library load should be redirected to the original APK.
         */
        private fun redirectLibraryLoad(
            libName: String,
            originalPkg: String,
            stubPkg: String
        ): Boolean {
            // Redirect if the library path might resolve to the stub's directory
            // Most system libraries don't need redirection
            val systemLibs = setOf(
                "c", "m", "dl", "log", "z", "jnigraphics",
                "android", "EGL", "GLESv1_CM", "GLESv2", "GLESv3",
                "vulkan", "OpenSLES", "OpenMAXAL", "camera2ndk",
                "mediandk", "aaudio", "neuralnetworks"
            )
            return libName !in systemLibs
        }

        /**
         * Resolve the full path to a native library in the original APK's
         * native library directory.
         */
        private fun resolveNativeLibPath(
            libName: String,
            originalPkg: String
        ): String? {
            // Standard native library locations
            val libFileName = "lib${libName}.so"
            val possiblePaths = listOf(
                "/data/data/$originalPkg/lib/$libFileName",
                "/data/data/$originalPkg/lib/arm/$libFileName",
                "/data/data/$originalPkg/lib/arm64/$libFileName",
                "/data/app/$originalPkg-1/lib/arm64/$libFileName",
                "/data/app/$originalPkg-1/lib/arm/$libFileName",
                "/data/app/$originalPkg-2/lib/arm64/$libFileName",
                "/data/app/$originalPkg-2/lib/arm/$libFileName"
            )

            for (path in possiblePaths) {
                try {
                    if (File(path).exists()) {
                        return path
                    }
                } catch (_: Exception) { /* ignore permission errors */ }
            }

            return null
        }

        /**
         * Rewrite a library path, replacing stub package paths with
         * the original package paths.
         */
        private fun rewriteLibraryPath(
            path: String,
            originalPkg: String,
            stubPkg: String
        ): String {
            if (!path.contains(stubPkg)) return path

            // Try to find the library in the original package's directory
            val rewritten = path.replace(stubPkg, originalPkg)
            return if (File(rewritten).exists()) {
                rewritten
            } else {
                // Keep original path if the rewritten one doesn't exist
                path
            }
        }
    }
}
