package com.multiapp.core.loader

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.io.File

/**
 * Wraps the host Context and overrides identity fields so the guest app
 * sees its own package name, data directories, source path, and ClassLoader.
 *
 * This is the core of the hosted container model: the guest code running
 * inside ContainerActivity gets a Context that reports the guest's identity,
 * not the host MultiApp identity.
 */
class VirtualContextWrapper(
    private val base: Context,
    private val config: VirtualContextConfig,
    private val guestClassLoader: ClassLoader
) : ContextWrapper(base) {

    override fun getPackageName(): String = config.virtualPackageName

    override fun getApplicationInfo(): ApplicationInfo {
        val baseInfo = super.getApplicationInfo()
        return ApplicationInfo(baseInfo).apply {
            packageName = config.originPackageName
            sourceDir = config.sourceDir
            publicSourceDir = config.sourceDir
            dataDir = config.dataDir
            config.nativeLibraryDir?.let { nativeLibraryDir = it }
        }
    }

    override fun getClassLoader(): ClassLoader = guestClassLoader

    override fun getFilesDir(): File =
        File(config.dataDir, "files").apply { mkdirs() }

    override fun getCacheDir(): File =
        File(config.dataDir, "cache").apply { mkdirs() }

    override fun getDatabasePath(name: String): File {
        val dir = File(config.dataDir, "databases")
        dir.mkdirs()
        return File(dir, name)
    }

    override fun getSharedPreferences(name: String, mode: Int) =
        super.getSharedPreferences("${config.instanceId}_$name", mode)

    override fun getDir(name: String, mode: Int): File {
        val dir = File(config.dataDir, "app_$name")
        dir.mkdirs()
        return dir
    }

    override fun getExternalFilesDir(type: String?): File? {
        val base = File(config.dataDir, "external_files")
        val dir = if (type != null) File(base, type) else base
        dir.mkdirs()
        return dir
    }

    override fun getExternalCacheDir(): File? =
        File(config.dataDir, "external_cache").apply { mkdirs() }
}
