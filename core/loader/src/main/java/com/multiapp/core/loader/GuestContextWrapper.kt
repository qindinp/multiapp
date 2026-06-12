package com.multiapp.core.loader

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.view.LayoutInflater

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
    private val guestResourceDir: String = guestSourceDir,
    private val guestNativeLibDir: String?,
    private val guestMetaData: android.os.Bundle?,
    private val guestResources: android.content.res.Resources? = null
) : ContextWrapper(base) {

    /**
     * Some hotfix loaders (Tinker/RFix) reflect ContextImpl.mOuterContext from
     * Application.getBaseContext(). Expose the same field name so their
     * replacement flow does not fail when the base context is wrapped.
     */
    @JvmField
    var mOuterContext: Context = base

    private var guestThemeResId: Int = 0
    private var guestTheme: Resources.Theme? = null
    private var guestInflater: LayoutInflater? = null

    override fun getPackageName(): String = guestPackageName

    override fun setTheme(resid: Int) {
        guestThemeResId = resid
        guestTheme = null
    }

    override fun getTheme(): Resources.Theme {
        val resources = guestResources ?: return super.getTheme()
        val existing = guestTheme
        if (existing != null) {
            return existing
        }

        val theme = resources.newTheme()
        try {
            theme.setTo(super.getTheme())
        } catch (_: Throwable) {
            // Cross-table platform themes may fail; keep an origin-backed empty theme.
        }
        if (guestThemeResId != 0) {
            theme.applyStyle(guestThemeResId, true)
        }
        guestTheme = theme
        return theme
    }

    override fun getSystemService(name: String): Any? {
        if (name == LAYOUT_INFLATER_SERVICE) {
            val existing = guestInflater
            if (existing != null) {
                return existing
            }
            val baseInflater = super.getSystemService(name) as? LayoutInflater
            val cloned = baseInflater?.cloneInContext(this)
            if (cloned != null) {
                guestInflater = cloned
                return cloned
            }
        }
        return super.getSystemService(name)
    }

    override fun getApplicationInfo(): ApplicationInfo {
        // ★ Priority 2 修复：返回防御性拷贝，不修改共享的 ApplicationInfo 对象
        // 之前直接 mutate super.getApplicationInfo() 会导致系统框架看到错误的包名
        val info = ApplicationInfo(super.getApplicationInfo())
        info.packageName = guestPackageName
        info.sourceDir = guestSourceDir
        info.publicSourceDir = guestResourceDir
        if (guestNativeLibDir != null) {
            info.nativeLibraryDir = guestNativeLibDir
        }
        if (guestMetaData != null) {
            info.metaData = android.os.Bundle(guestMetaData)
        }
        info.icon = android.R.drawable.sym_def_app_icon
        try {
            val roundIconField = info.javaClass.getDeclaredField("roundIcon")
            roundIconField.isAccessible = true
            roundIconField.setInt(info, android.R.drawable.sym_def_app_icon)
        } catch (_: Throwable) {
            // roundIcon availability differs by platform version.
        }
        return info
    }

    override fun getPackageCodePath(): String = guestSourceDir
    override fun getPackageResourcePath(): String = guestResourceDir
    override fun getResources(): Resources = guestResources ?: super.getResources()
    override fun getAssets(): android.content.res.AssetManager = getResources().assets
}
