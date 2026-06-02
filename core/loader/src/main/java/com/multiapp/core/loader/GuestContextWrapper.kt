package com.multiapp.core.loader

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo

/**
 * Context 包装器 — 让原始 app 代码看到正确的包名和 ApplicationInfo。
 *
 * 设计原则（参考 NEXTVM VirtualContext）：
 * - 底层保持 stub 身份（LoadedApk.mOpPackageName 不改）用于权限检查
 * - 上层通过此包装器返回原始 app 身份给应用代码
 */
class GuestContextWrapper(
    base: Context,
    private val guestPackageName: String,
    private val guestSourceDir: String,
    private val guestNativeLibDir: String?,
    private val guestMetaData: android.os.Bundle?,
    private val guestResources: android.content.res.Resources? = null
) : ContextWrapper(base) {

    override fun getPackageName(): String = guestPackageName

    override fun getApplicationInfo(): ApplicationInfo {
        return super.getApplicationInfo().apply {
            packageName = guestPackageName
            sourceDir = guestSourceDir
            publicSourceDir = guestSourceDir
            if (guestNativeLibDir != null) {
                nativeLibraryDir = guestNativeLibDir
            }
            if (metaData == null && guestMetaData != null) {
                metaData = guestMetaData
            }
        }
    }

    override fun getPackageCodePath(): String = guestSourceDir
    override fun getPackageResourcePath(): String = guestSourceDir
    override fun getResources(): android.content.res.Resources = guestResources ?: super.getResources()
    override fun getAssets(): android.content.res.AssetManager = getResources().assets
}
