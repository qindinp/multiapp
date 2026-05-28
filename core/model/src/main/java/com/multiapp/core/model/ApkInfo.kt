package com.multiapp.core.model

import android.graphics.drawable.Drawable

/**
 * Information parsed from an APK file.
 * This is a snapshot — NOT stored persistently.
 */
data class ApkInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    @Transient val icon: Drawable? = null,
    val apkPath: String,
    val apkSizeBytes: Long,
    /** Requested permissions from manifest */
    val permissions: List<String> = emptyList(),
    /** Fully qualified Activity class names */
    val activities: List<String> = emptyList(),
    /** Fully qualified Service class names */
    val services: List<String> = emptyList(),
    /** Fully qualified ContentProvider class names */
    val providers: List<String> = emptyList(),
    /** Fully qualified BroadcastReceiver class names */
    val receivers: List<String> = emptyList(),
    /** Supported native ABIs, e.g., ["arm64-v8a", "armeabi-v7a"] */
    val nativeLibs: List<String> = emptyList(),
    /** Whether app has 64-bit native support */
    val is64Bit: Boolean = true,
    /** Whether app uses split APKs */
    val usesSplitApk: Boolean = false,
    /** Main launcher activity */
    val mainActivity: String? = null,
    /** Application class name from manifest */
    val applicationClassName: String? = null,
    /** Meta-data from manifest */
    val metaData: Map<String, String> = emptyMap()
)
