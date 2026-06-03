package com.multiapp.core.loader

import java.net.URL

/**
 * A tiny delegating ClassLoader whose public identity looks like a normal
 * DexPathList for the guest APK instead of exposing the stub package.
 */
class StealthClassLoader(
    private val delegate: ClassLoader,
    private val guestApkPath: String
) : ClassLoader(ClassLoader.getSystemClassLoader()) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val clazz = delegate.loadClass(name)
        if (resolve) resolveClass(clazz)
        return clazz
    }

    override fun getResource(name: String): URL? {
        return delegate.getResource(name) ?: super.getResource(name)
    }

    override fun getResources(name: String): java.util.Enumeration<URL> {
        return delegate.getResources(name)
    }

    override fun toString(): String {
        return "DexPathList[[zip file \"$guestApkPath\"],nativeLibraryDirectories=[]]"
    }
}
