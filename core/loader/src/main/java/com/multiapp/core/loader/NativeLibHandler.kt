package com.multiapp.core.loader

import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * native 库处理
 * 将原始 APK 的 lib/ 目录复制到 Stub 的 nativeLibraryDir
 */
class NativeLibHandler {

    fun copyNativeLibs(originalApk: File, stubLibDir: File) {
        Timber.d("NativeLibHandler: copying libs from ${originalApk.name} to ${stubLibDir.absolutePath}")
        var copiedCount = 0
        ZipFile(originalApk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/") && !it.isDirectory }
                .forEach { entry ->
                    val target = File(stubLibDir, entry.name)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    copiedCount++
                    Timber.d("NativeLibHandler: copied ${entry.name}")
                }
        }
        Timber.d("NativeLibHandler: copied $copiedCount native libraries")
    }
}
