package com.multiapp.core.loader

import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * APK extraction utilities.
 * Extracted from LoaderFactory to reduce class size.
 */
class ApkExtractor(
    private val log: (String) -> Unit = { Log.d("ApkExtractor", it) }
) {
    companion object {
        private const val TAG = "ApkExtractor"
    }

    // TODO: Migrate extractOriginApk, extractOriginalApk, extractAdditionalDex,
    //       extractOriginNativeLibs, findOriginNativeAbi, currentProcessSupportedAbis,
    //       nativeDirNameForAbi from LoaderFactory.kt

    /**
     * Get current process supported ABIs.
     */
    fun currentProcessSupportedAbis(): Array<String> {
        return try {
            @Suppress("DEPRECATION")
            arrayOf(android.os.Build.CPU_ABI, android.os.Build.CPU_ABI2).filterNotNull().toTypedArray()
        } catch (e: Exception) {
            arrayOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * Get native directory name for a given ABI.
     */
    fun nativeDirNameForAbi(abi: String): String = "lib/$abi"
}
