package com.multiapp.core.hook

import java.io.File

/**
 * Adaptation facade used by hosted bootstrap stages to detect and execute a
 * packed-shell (加固) runtime.
 *
 * Kept intentionally narrow so runtime stages can depend on the contract
 * without pulling in the logging side effects of [PackerRuntimeDispatcher]
 * (useful for JVM unit tests, which cannot mock android.util.Log by default).
 */
interface PackerRuntimeAdaptation {

    /** Returns the first matching runtime, or null when no shell is detected. */
    fun detect(originLibDir: File?, originApkPath: String?): PackerRuntime?

    /**
     * Runs the full shell lifecycle and returns the load result, or null when
     * no shell was detected.
     */
    fun execute(context: PackerRuntimeContext): PackerLoadResult?
}
