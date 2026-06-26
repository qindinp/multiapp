package com.multiapp.core.loader

/**
 * Immutable snapshot of the environment at bootstrap start.
 *
 * Deliberately avoids Android framework type references so it can be
 * used in unit tests and pure-JVM modules.
 */
data class RuntimeBootstrapContext(
    val entryClassLoader: ClassLoader,
    val processName: String = "",
    val threadName: String = "",
    val startedAtMs: Long = 0L,
    val stubApkPath: String? = null,
    val dataDir: String? = null,
    val stubPackageName: String? = null,
    val originalPackageName: String? = null,
    val cloneProfile: String? = null,
    val originApkPath: String? = null,
    val originalApkPath: String? = null,
    val resourceApkPath: String? = null,
    val originNativeLibDir: String? = null,
    val guestClassLoaderName: String? = null,
    val extras: Map<String, String> = emptyMap()
)
