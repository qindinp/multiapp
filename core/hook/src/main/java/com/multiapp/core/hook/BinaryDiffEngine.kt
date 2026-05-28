package com.multiapp.core.hook

import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binary Diff Engine — Compares two APKs to identify structural differences.
 *
 * Compares at multiple levels:
 * - DEX files: bytecode-level differences (added/removed/modified classes)
 * - Resources: layout, drawable, and string changes
 * - Native libraries: .so file additions, removals, version changes
 * - Permissions: manifest permission differences
 * - Certificate: signing key changes (indicates repackaging)
 *
 * Useful for:
 * - Detecting repackaged malware (legitimate APK modified with malicious payload)
 * - Version diff analysis (what changed between releases)
 * - Integrity verification (detecting unauthorized modifications)
 */
@Singleton
class BinaryDiffEngine @Inject constructor() {

    companion object {
        private const val TAG = "BinaryDiffEngine"
    }

    /**
     * Compare two APK files and produce a detailed diff report.
     *
     * @param apkPathA Path to the first APK (baseline)
     * @param apkPathB Path to the second APK (target)
     * @return DiffReport with all differences, or null on failure
     */
    fun diff(apkPathA: String, apkPathB: String): DiffReport? {
        val fileA = File(apkPathA)
        val fileB = File(apkPathB)

        if (!fileA.exists()) {
            Timber.tag(TAG).e("APK A not found: $apkPathA")
            return null
        }
        if (!fileB.exists()) {
            Timber.tag(TAG).e("APK B not found: $apkPathB")
            return null
        }

        return try {
            val zipA = ZipFile(fileA)
            val zipB = ZipFile(fileB)

            val entriesA = zipA.entries().toList().map { it.name to it }.toMap()
            val entriesB = zipB.entries().toList().map { it.name to it }.toMap()

            val allNames = entriesA.keys.union(entriesB.keys).sorted()

            val addedFiles = mutableListOf<String>()
            val removedFiles = mutableListOf<String>()
            val modifiedFiles = mutableListOf<FileDiff>()
            val unchangedCount: Int

            var changedCount = 0

            for (name in allNames) {
                val entryA = entriesA[name]
                val entryB = entriesB[name]

                when {
                    entryA == null && entryB != null -> addedFiles.add(name)
                    entryA != null && entryB == null -> removedFiles.add(name)
                    entryA != null && entryB != null -> {
                        if (entryA.size != entryB.size || entryA.crc != entryB.crc) {
                            modifiedFiles.add(FileDiff(
                                path = name,
                                oldSize = entryA.size,
                                newSize = entryB.size,
                                oldCrc = entryA.crc,
                                newCrc = entryB.crc
                            ))
                            changedCount++
                        }
                    }
                }
            }

            unchangedCount = allNames.size - addedFiles.size - removedFiles.size - changedCount

            // Categorize differences
            val dexDiff = diffDexFiles(zipA, zipB, modifiedFiles)
            val resourceDiff = diffResources(addedFiles, removedFiles, modifiedFiles)
            val nativeDiff = diffNativeLibs(addedFiles, removedFiles, modifiedFiles)
            val permissionDiff = diffPermissions(zipA, zipB)
            val certDiff = diffCertificates(apkPathA, apkPathB)
            val manifestDiff = diffManifest(zipA, zipB)

            zipA.close()
            zipB.close()

            val similarityScore = calculateSimilarity(
                allNames.size, addedFiles.size, removedFiles.size, changedCount
            )

            val report = DiffReport(
                apkPathA = apkPathA,
                apkPathB = apkPathB,
                totalFilesA = entriesA.size,
                totalFilesB = entriesB.size,
                addedFiles = addedFiles,
                removedFiles = removedFiles,
                modifiedFiles = modifiedFiles,
                unchangedCount = unchangedCount,
                dexDiff = dexDiff,
                resourceDiff = resourceDiff,
                nativeDiff = nativeDiff,
                permissionDiff = permissionDiff,
                certDiff = certDiff,
                manifestDiff = manifestDiff,
                similarityScore = similarityScore,
                comparedAt = System.currentTimeMillis()
            )

            Timber.tag(TAG).i(
                "Diff complete: A=${entriesA.size} B=${entriesB.size} | " +
                    "added=${addedFiles.size} removed=${removedFiles.size} " +
                    "modified=${modifiedFiles.size} | similarity=${"%.1f".format(similarityScore * 100)}%"
            )
            report
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Diff failed: $apkPathA vs $apkPathB")
            null
        }
    }

    // ─── Category-specific diff methods ─────────────────────────────

    private fun diffDexFiles(
        zipA: ZipFile,
        zipB: ZipFile,
        modifiedFiles: List<FileDiff>
    ): DexDiff {
        val dexModified = modifiedFiles.filter { it.path.endsWith(".dex") }
        val dexA = zipA.entries().asSequence().filter { it.name.endsWith(".dex") }.toList()
        val dexB = zipB.entries().asSequence().filter { it.name.endsWith(".dex") }.toList()

        // Count classes by scanning DEX magic + class_defs_size field
        var classesA = 0
        var classesB = 0

        for (entry in dexA) {
            try {
                classesA += countClassesInDex(zipA, entry)
            } catch (_: Exception) { /* skip */ }
        }
        for (entry in dexB) {
            try {
                classesB += countClassesInDex(zipB, entry)
            } catch (_: Exception) { /* skip */ }
        }

        return DexDiff(
            dexCountA = dexA.size,
            dexCountB = dexB.size,
            dexFilesModified = dexModified.map { it.path },
            estimatedClassesA = classesA,
            estimatedClassesB = classesB,
            totalDexSizeA = dexA.sumOf { it.size },
            totalDexSizeB = dexB.sumOf { it.size }
        )
    }

    private fun diffResources(
        added: List<String>,
        removed: List<String>,
        modified: List<FileDiff>
    ): ResourceDiff {
        val resAdded = added.filter { it.startsWith("res/") }
        val resRemoved = removed.filter { it.startsWith("res/") }
        val resModified = modified.filter { it.path.startsWith("res/") }

        val layoutChanges = resModified.count { it.path.startsWith("res/layout") }
        val drawableChanges = resModified.count { it.path.startsWith("res/drawable") }
        val valuesChanges = resModified.count { it.path.startsWith("res/values") }

        return ResourceDiff(
            resourcesAdded = resAdded.size,
            resourcesRemoved = resRemoved.size,
            resourcesModified = resModified.size,
            layoutChanges = layoutChanges,
            drawableChanges = drawableChanges,
            valuesChanges = valuesChanges,
            addedPaths = resAdded.take(20),
            removedPaths = resRemoved.take(20)
        )
    }

    private fun diffNativeLibs(
        added: List<String>,
        removed: List<String>,
        modified: List<FileDiff>
    ): NativeDiff {
        val nativeAdded = added.filter { it.startsWith("lib/") && it.endsWith(".so") }
        val nativeRemoved = removed.filter { it.startsWith("lib/") && it.endsWith(".so") }
        val nativeModified = modified.filter {
            it.path.startsWith("lib/") && it.path.endsWith(".so")
        }

        // Extract ABIs
        val abisA = nativeRemoved.mapNotNull { it.split("/").getOrNull(1) }.distinct()
        val abisB = nativeAdded.mapNotNull { it.split("/").getOrNull(1) }.distinct()

        return NativeDiff(
            libsAdded = nativeAdded,
            libsRemoved = nativeRemoved,
            libsModified = nativeModified.map { it.path },
            abisAdded = abisB - abisA.toSet(),
            abisRemoved = abisA - abisB.toSet(),
            sizeChanges = nativeModified.associate { it.path to (it.newSize - it.oldSize) }
        )
    }

    private fun diffPermissions(zipA: ZipFile, zipB: ZipFile): PermissionDiff {
        val permsA = extractPermissionsFromManifest(zipA)
        val permsB = extractPermissionsFromManifest(zipB)

        val added = permsB - permsA.toSet()
        val removed = permsA - permsB.toSet()

        return PermissionDiff(
            permissionsA = permsA,
            permissionsB = permsB,
            added = added,
            removed = removed
        )
    }

    private fun diffCertificates(apkPathA: String, apkPathB: String): CertDiff {
        val certHashA = extractCertHash(apkPathA)
        val certHashB = extractCertHash(apkPathB)

        return CertDiff(
            certHashA = certHashA,
            certHashB = certHashB,
            sameCertificate = certHashA == certHashB
        )
    }

    private fun diffManifest(zipA: ZipFile, zipB: ZipFile): ManifestDiff {
        val asciiA = readManifestAscii(zipA)
        val asciiB = readManifestAscii(zipB)

        val versionA = Regex("versionName=\"([^\"]+)\"").find(asciiA)?.groupValues?.get(1)
        val versionB = Regex("versionName=\"([^\"]+)\"").find(asciiB)?.groupValues?.get(1)

        val minSdkA = Regex("minSdkVersion=\"([^\"]+)\"").find(asciiA)?.groupValues?.get(1)
        val minSdkB = Regex("minSdkVersion=\"([^\"]+)\"").find(asciiB)?.groupValues?.get(1)

        val targetSdkA = Regex("targetSdkVersion=\"([^\"]+)\"").find(asciiA)?.groupValues?.get(1)
        val targetSdkB = Regex("targetSdkVersion=\"([^\"]+)\"").find(asciiB)?.groupValues?.get(1)

        return ManifestDiff(
            versionA = versionA,
            versionB = versionB,
            minSdkA = minSdkA?.toIntOrNull(),
            minSdkB = minSdkB?.toIntOrNull(),
            targetSdkA = targetSdkA?.toIntOrNull(),
            targetSdkB = targetSdkB?.toIntOrNull()
        )
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private fun countClassesInDex(zip: ZipFile, entry: ZipEntry): Int {
        // DEX file format: class_defs_size is at offset 96 (4 bytes, little-endian)
        // Only read the first 100 bytes instead of the entire file to avoid OOM
        zip.getInputStream(entry).use { stream ->
            val header = ByteArray(100)
            val read = stream.read(header, 0, 100)
            if (read < 100) return 0
            val offset = 96
            return (header[offset].toInt() and 0xFF) or
                ((header[offset + 1].toInt() and 0xFF) shl 8) or
                ((header[offset + 2].toInt() and 0xFF) shl 16) or
                ((header[offset + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun extractPermissionsFromManifest(zip: ZipFile): List<String> {
        val ascii = readManifestAscii(zip)
        return Regex("uses-permission.*?name=\"([^\"]+)\"")
            .findAll(ascii)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun readManifestAscii(zip: ZipFile): String {
        val entry = zip.getEntry("AndroidManifest.xml") ?: return ""
        return try {
            val sb = StringBuilder()
            var current = StringBuilder()
            zip.getInputStream(entry).use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    for (i in 0 until bytesRead) {
                        val c = buffer[i].toInt() and 0xFF
                        if (c in 0x20..0x7E) {
                            current.append(c.toChar())
                        } else {
                            if (current.length >= 4) sb.appendLine(current.toString())
                            current = StringBuilder()
                        }
                    }
                }
            }
            if (current.length >= 4) sb.appendLine(current.toString())
            sb.toString()
        } catch (_: Exception) { "" }
    }

    private fun extractCertHash(apkPath: String): String {
        return try {
            val zip = ZipFile(File(apkPath))
            val certEntry = zip.entries().asSequence().firstOrNull {
                it.name.startsWith("META-INF/") &&
                    (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC"))
            }
            if (certEntry != null) {
                val md = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(certEntry).use { stream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        md.update(buffer, 0, read)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } else {
                "no_certificate"
            }
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    private fun calculateSimilarity(
        totalFiles: Int,
        added: Int,
        removed: Int,
        modified: Int
    ): Double {
        if (totalFiles == 0) return 1.0
        val unchanged = totalFiles - added - removed - modified
        return (unchanged.toDouble() / totalFiles).coerceIn(0.0, 1.0)
    }
}

// ─── Data classes ───────────────────────────────────────────────────

data class FileDiff(
    val path: String,
    val oldSize: Long,
    val newSize: Long,
    val oldCrc: Long,
    val newCrc: Long
) {
    val sizeDelta: Long get() = newSize - oldSize
}

data class DexDiff(
    val dexCountA: Int,
    val dexCountB: Int,
    val dexFilesModified: List<String>,
    val estimatedClassesA: Int,
    val estimatedClassesB: Int,
    val totalDexSizeA: Long,
    val totalDexSizeB: Long
) {
    val classDelta: Int get() = estimatedClassesB - estimatedClassesA
    val sizeDelta: Long get() = totalDexSizeB - totalDexSizeA
}

data class ResourceDiff(
    val resourcesAdded: Int,
    val resourcesRemoved: Int,
    val resourcesModified: Int,
    val layoutChanges: Int,
    val drawableChanges: Int,
    val valuesChanges: Int,
    val addedPaths: List<String>,
    val removedPaths: List<String>
)

data class NativeDiff(
    val libsAdded: List<String>,
    val libsRemoved: List<String>,
    val libsModified: List<String>,
    val abisAdded: List<String>,
    val abisRemoved: List<String>,
    val sizeChanges: Map<String, Long>
)

data class PermissionDiff(
    val permissionsA: List<String>,
    val permissionsB: List<String>,
    val added: List<String>,
    val removed: List<String>
)

data class CertDiff(
    val certHashA: String,
    val certHashB: String,
    val sameCertificate: Boolean
)

data class ManifestDiff(
    val versionA: String?,
    val versionB: String?,
    val minSdkA: Int?,
    val minSdkB: Int?,
    val targetSdkA: Int?,
    val targetSdkB: Int?
)

data class DiffReport(
    val apkPathA: String,
    val apkPathB: String,
    val totalFilesA: Int,
    val totalFilesB: Int,
    val addedFiles: List<String>,
    val removedFiles: List<String>,
    val modifiedFiles: List<FileDiff>,
    val unchangedCount: Int,
    val dexDiff: DexDiff,
    val resourceDiff: ResourceDiff,
    val nativeDiff: NativeDiff,
    val permissionDiff: PermissionDiff,
    val certDiff: CertDiff,
    val manifestDiff: ManifestDiff,
    val similarityScore: Double,
    val comparedAt: Long
) {
    val totalChanges: Int get() = addedFiles.size + removedFiles.size + modifiedFiles.size
    val isRepackaged: Boolean get() = !certDiff.sameCertificate && similarityScore > 0.7

    fun summary(): String = buildString {
        appendLine("=== Binary Diff Report ===")
        appendLine("Similarity: ${"%.1f".format(similarityScore * 100)}%")
        appendLine("Files: +${addedFiles.size} / -${removedFiles.size} / ~${modifiedFiles.size}")
        appendLine("DEX: ${dexDiff.dexCountA}->${dexDiff.dexCountB} dex, " +
            "${dexDiff.estimatedClassesA}->${dexDiff.estimatedClassesB} classes")
        appendLine("Permissions: +${permissionDiff.added.size} / -${permissionDiff.removed.size}")
        appendLine("Native: +${nativeDiff.libsAdded.size} / -${nativeDiff.libsRemoved.size}")
        appendLine("Certificate: ${if (certDiff.sameCertificate) "SAME" else "DIFFERENT"}")
        if (isRepackaged) appendLine("WARNING: Likely repackaged (different cert, high similarity)")
    }
}
