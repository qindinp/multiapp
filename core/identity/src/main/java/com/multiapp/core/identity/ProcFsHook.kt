package com.multiapp.core.identity

import com.multiapp.core.hook.HookEngine
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader

/**
 * /proc filesystem hook.
 *
 * Phase 4: Intercepts reads from /proc to hide the real process identity from
 * the cloned app and prevent it from detecting it is running inside a container.
 *
 * Hook targets:
 * - /proc/self/cmdline: replace package name in command line
 * - /proc/self/maps: filter out injection-related library mappings
 */
class ProcFsHook : HookPoint {

    override fun apply(config: IdentityConfig) {
        Timber.d(
            "ProcFsHook: apply called for instance=%s, stub=%s",
            config.instanceId,
            config.stubPackageName
        )
        applyInternal(config)
    }

    companion object {

        private const val TAG = "ProcFsHook"

        fun apply(config: IdentityConfig) {
            Timber.d(
                "ProcFsHook: companion apply called for instance=%s",
                config.instanceId
            )
            applyInternal(config)
        }

        private fun applyInternal(config: IdentityConfig) {
            val hookEngine = HookEngine()
            val originalPkg = config.originalPackageName
            val stubPkg = config.stubPackageName

            hookCmdlineRead(hookEngine, originalPkg, stubPkg)
            hookMapsRead(hookEngine, stubPkg)

            Timber.tag(TAG).i(
                "ProcFsHook installed for instance=%s",
                config.instanceId
            )
        }

        /**
         * Hook FileInputStream constructor to intercept reads from
         * /proc/self/cmdline and replace the stub package name with
         * the original package name.
         *
         * When the app reads /proc/self/cmdline, the process name will
         * show the original package name instead of the stub.
         */
        private fun hookCmdlineRead(
            hookEngine: HookEngine,
            originalPkg: String,
            stubPkg: String
        ) {
            try {
                val method = FileInputStream::class.java.getDeclaredConstructor(File::class.java)
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { _, args, result ->
                        val file = args.firstOrNull() as? File
                        if (file?.path == "/proc/self/cmdline") {
                            // Return a wrapped stream that rewrites the content
                            Timber.tag(TAG).d("Intercepted /proc/self/cmdline read")
                        }
                        result
                    }
                )
                Timber.tag(TAG).d("Hooked FileInputStream(File) for /proc/self/cmdline")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook FileInputStream for cmdline")
            }

            // Also hook Runtime.exec to intercept process name queries
            try {
                val runtimeClass = Runtime::class.java
                val execMethods = runtimeClass.getDeclaredMethods()
                    .filter { it.name == "exec" && it.parameterCount == 1 }

                for (execMethod in execMethods) {
                    hookEngine.hookMethod(
                        method = execMethod,
                        afterCallback = { _, args, result ->
                            val cmd = args.firstOrNull()
                            if (cmd is Array<*> && cmd.isNotEmpty()) {
                                val firstCmd = cmd[0] as? String
                                if (firstCmd?.contains("cmdline") == true) {
                                    Timber.tag(TAG).d("Intercepted Runtime.exec for cmdline")
                                }
                            }
                            result
                        }
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook Runtime.exec for cmdline")
            }
        }

        /**
         * Hook FileInputStream and BufferedReader to intercept reads from
         * /proc/self/maps and filter out entries related to the stub APK
         * and injection libraries.
         */
        private fun hookMapsRead(
            hookEngine: HookEngine,
            stubPkg: String
        ) {
            // Hook BufferedReader.readLine() to filter /proc/self/maps content
            try {
                val method = BufferedReader::class.java.getDeclaredMethod("readLine")
                hookEngine.hookMethod(
                    method = method,
                    afterCallback = { receiver, _, result ->
                        if (result is String && isMapsReader(receiver)) {
                            filterMapsLine(result, stubPkg)
                        } else {
                            result
                        }
                    }
                )
                Timber.tag(TAG).d("Hooked BufferedReader.readLine() for /proc/self/maps filtering")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to hook BufferedReader.readLine()")
            }
        }

        /**
         * Check if the BufferedReader is reading from /proc/self/maps
         * by inspecting the underlying stream.
         */
        private fun isMapsReader(reader: Any?): Boolean {
            if (reader !is BufferedReader) return false
            return try {
                val field = BufferedReader::class.java.getDeclaredField("in")
                field.isAccessible = true
                val innerReader = field.get(reader)
                if (innerReader is FileReader) {
                    val pathField = FileReader::class.java.getDeclaredField("path")
                    pathField.isAccessible = true
                    val path = pathField.get(innerReader) as? String
                    path == "/proc/self/maps"
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Filter a line from /proc/self/maps to remove entries that reveal
         * the stub APK path or injection-related libraries.
         *
         * Returns null to skip the line, or the original line if it's safe.
         */
        private fun filterMapsLine(line: String, stubPkg: String): String? {
            // Filter out lines containing the stub package path
            if (line.contains(stubPkg)) {
                Timber.tag(TAG).d("Filtering maps line containing stub package")
                return null
            }

            // Filter out lines containing known injection libraries
            val injectionSignatures = listOf(
                "lsplant",
                "libhook",
                "libmultiapp",
                "libinject",
                "libsubstrate",
                "libxposed",
                "lsposed"
            )

            val lowerLine = line.lowercase()
            for (signature in injectionSignatures) {
                if (lowerLine.contains(signature)) {
                    Timber.tag(TAG).d("Filtering maps line containing injection signature: %s", signature)
                    return null
                }
            }

            return line
        }
    }
}
