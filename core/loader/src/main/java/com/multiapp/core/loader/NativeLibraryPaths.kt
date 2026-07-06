package com.multiapp.core.loader

import android.os.Build
import android.os.Process
import java.io.File
import java.util.zip.ZipFile

internal data class NativeLibraryResolution(
    val nativeLibraryDir: String?,
    val nativeLibraryRoot: String?,
    val source: String,
    val extractionStatus: String,
    val selectedAbi: String? = null,
    val availableAbis: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    val copiedCount: Int = 0,
    val reason: String? = null
)

internal object NativeLibraryPaths {
    private const val SOURCE_INSTANCE_LIB = "INSTANCE_DATA_ROOT_LIB"
    private const val SOURCE_INSTANCE_ABI_LIB = "INSTANCE_DATA_ROOT_LIB_ABI"
    private const val SOURCE_APK_EXTRACTED_ABI_LIB = "APK_EXTRACTED_ABI_DIR"

    fun resolveAndExtract(
        originApkPath: String?,
        dataRoot: String?
    ): NativeLibraryResolution {
        if (dataRoot.isNullOrBlank()) {
            return NativeLibraryResolution(
                nativeLibraryDir = null,
                nativeLibraryRoot = null,
                source = SOURCE_INSTANCE_LIB,
                extractionStatus = "DEFERRED",
                reason = "instance data root not present"
            )
        }

        val libRoot = File(dataRoot, "lib")
        val apkFile = originApkPath?.takeIf { it.isNotBlank() }?.let(::File)
        val availableAbis = apkFile
            ?.takeIf { it.isFile }
            ?.let { findAvailableNativeAbis(it) }
            .orEmpty()
        val selectedAbi = selectAbi(availableAbis)

        if (apkFile?.isFile == true && selectedAbi != null) {
            val abiDir = File(libRoot, selectedAbi)
            val extraction = extractAbiLibraries(apkFile, selectedAbi, abiDir)
            return NativeLibraryResolution(
                nativeLibraryDir = abiDir.absolutePath,
                nativeLibraryRoot = libRoot.absolutePath,
                source = SOURCE_APK_EXTRACTED_ABI_LIB,
                extractionStatus = if (extraction.copiedCount > 0) "EXTRACTED" else "ALREADY_PRESENT",
                selectedAbi = selectedAbi,
                availableAbis = availableAbis,
                libraries = extraction.libraries,
                copiedCount = extraction.copiedCount
            )
        }

        findExistingAbiDir(libRoot)?.let { abiDir ->
            return NativeLibraryResolution(
                nativeLibraryDir = abiDir.absolutePath,
                nativeLibraryRoot = libRoot.absolutePath,
                source = SOURCE_INSTANCE_ABI_LIB,
                extractionStatus = "DEFERRED",
                selectedAbi = abiDir.name,
                availableAbis = availableAbis,
                libraries = abiDir.listSoFiles()
            )
        }

        if (libRoot.isDirectory) {
            return NativeLibraryResolution(
                nativeLibraryDir = libRoot.absolutePath,
                nativeLibraryRoot = libRoot.absolutePath,
                source = SOURCE_INSTANCE_LIB,
                extractionStatus = "DEFERRED",
                availableAbis = availableAbis,
                libraries = libRoot.listSoFiles()
            )
        }

        val reason = when {
            availableAbis.isNotEmpty() -> "no supported native ABI found"
            apkFile?.isFile == true -> "apk contains no native libraries"
            else -> "instance lib dir not present"
        }
        return NativeLibraryResolution(
            nativeLibraryDir = null,
            nativeLibraryRoot = libRoot.absolutePath,
            source = SOURCE_INSTANCE_LIB,
            extractionStatus = "DEFERRED",
            availableAbis = availableAbis,
            reason = reason
        )
    }

    fun buildClassLoaderSearchPath(
        apkPath: String,
        nativeLibraryDir: String?
    ): String? {
        val entries = linkedSetOf<String>()
        if (!nativeLibraryDir.isNullOrBlank()) {
            entries += nativeLibraryDir
        }
        currentProcessSupportedAbis().forEach { abi ->
            entries += "$apkPath!/lib/$abi"
        }
        return entries.joinToString(File.pathSeparator).ifBlank { null }
    }

    fun currentProcessSupportedAbis(): List<String> {
        val processAbis = runCatching {
            if (Process.is64Bit()) {
                Build.SUPPORTED_64_BIT_ABIS
            } else {
                Build.SUPPORTED_32_BIT_ABIS
            }
        }.getOrNull()
            ?.filterNot { it.isNullOrBlank() }
            .orEmpty()

        if (processAbis.isNotEmpty()) return processAbis

        val allAbis = runCatching { Build.SUPPORTED_ABIS }
            .getOrNull()
            ?.filterNot { it.isNullOrBlank() }
            .orEmpty()

        return allAbis.ifEmpty { listOf("arm64-v8a", "armeabi-v7a", "armeabi") }
    }

    private fun selectAbi(availableAbis: List<String>): String? {
        if (availableAbis.isEmpty()) return null
        return currentProcessSupportedAbis().firstOrNull { it in availableAbis }
    }

    private fun findAvailableNativeAbis(apkFile: File): List<String> =
        runCatching {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence()
                    .mapNotNull { entry ->
                        val name = entry.name
                        if (!entry.isDirectory && name.startsWith("lib/") && name.endsWith(".so")) {
                            name.removePrefix("lib/").substringBefore('/')
                        } else {
                            null
                        }
                    }
                    .distinct()
                    .toList()
            }
        }.getOrDefault(emptyList())

    private fun extractAbiLibraries(
        apkFile: File,
        abi: String,
        targetDir: File
    ): ExtractionResult {
        targetDir.mkdirs()
        val prefix = "lib/$abi/"
        val libraries = mutableListOf<String>()
        var copiedCount = 0

        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".so")
                }
                .forEach { entry ->
                    val soName = entry.name.substringAfterLast('/')
                    libraries += soName
                    val outFile = File(targetDir, soName)
                    if (outFile.isFile && outFile.length() == entry.size) {
                        return@forEach
                    }
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    outFile.setReadable(true, false)
                    outFile.setExecutable(true, false)
                    copiedCount++
                }
        }

        return ExtractionResult(
            libraries = libraries.sorted(),
            copiedCount = copiedCount
        )
    }

    private fun findExistingAbiDir(libRoot: File): File? {
        if (!libRoot.isDirectory) return null
        val supportedAbis = currentProcessSupportedAbis()
        return supportedAbis
            .asSequence()
            .map { File(libRoot, it) }
            .firstOrNull { it.isDirectory && it.listSoFiles().isNotEmpty() }
    }

    private fun File.listSoFiles(): List<String> =
        listFiles { file -> file.isFile && file.name.endsWith(".so") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    private data class ExtractionResult(
        val libraries: List<String>,
        val copiedCount: Int
    )
}
