package com.multiapp.core.model.installer

import com.multiapp.core.model.InstallArtifact
import com.multiapp.core.model.InstallArtifactManifest
import com.multiapp.core.model.InstallArtifactType
import com.multiapp.core.model.VirtualComponentRecord
import com.multiapp.core.model.VirtualPackageRecord
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Imports installed packages from the device into virtual install records.
 *
 * First round: reads package info from PackageManager, copies APK to artifact
 * directory, computes SHA-256 digests, and persists via InstallRecordStore.
 */
class InstalledPackageImporter(
    private val store: InstallRecordStore,
    private val artifactDir: File
) {
    init {
        if (!artifactDir.exists()) {
            artifactDir.mkdirs()
        }
    }

    /**
     * Creates an InstallRecord from pre-extracted package metadata.
     * This avoids direct Android framework dependency in unit tests.
     */
    fun importFromMetadata(
        packageName: String,
        originApkPath: String,
        versionCode: Long,
        versionName: String,
        targetSdk: Int,
        minSdk: Int,
        nativeLibraries: List<String> = emptyList(),
        abiList: List<String> = emptyList(),
        applicationClassName: String? = null,
        packageLabel: String? = null,
        permissions: List<String> = emptyList(),
        activities: List<ComponentInfo> = emptyList(),
        services: List<ComponentInfo> = emptyList(),
        receivers: List<ComponentInfo> = emptyList(),
        providers: List<ComponentInfo> = emptyList(),
        originCertSha256: String = "",
        signerSha256Digests: List<String> = emptyList(),
        hasMultipleSigners: Boolean = false,
        applicationMetaData: Map<String, com.multiapp.core.model.virtual.VirtualMetaDataValue> = emptyMap(),
        splitApkPaths: List<String> = emptyList(),
        splitPublicSourceDirs: List<String> = emptyList(),
        splitNames: List<String> = emptyList(),
        isolatedSplits: Boolean = false
    ): Result<ImportResult> {
        return try {
            requireSafeInstallPackageName(packageName)
            val originFile = File(originApkPath).canonicalFile
            if (!originFile.isFile) {
                return Result.failure(IllegalArgumentException("APK file not found: $originApkPath"))
            }

            val originApkSha256 = computeSha256(originFile)

            val destFile = artifactFileFor(packageName)
            copyAsReadOnlyArtifact(originFile, destFile)
            val splitArtifacts = copySplitArtifacts(
                packageName = packageName,
                splitApkPaths = splitApkPaths,
                splitNames = splitNames
            )
            val copiedSplitApkPaths = splitArtifacts.map { it.file.absolutePath }
            val copiedSplitPublicSourceDirs = copiedSplitApkPaths.takeIf { splitPublicSourceDirs.isNotEmpty() }
                ?: copiedSplitApkPaths

            val now = System.currentTimeMillis()
            val record = InstallRecord(
                packageName = packageName,
                originApkPath = destFile.absolutePath,
                originApkSha256 = originApkSha256,
                originCertSha256 = originCertSha256.ifBlank { signerSha256Digests.lastOrNull().orEmpty() },
                signerSha256Digests = signerSha256Digests,
                hasMultipleSigners = hasMultipleSigners,
                splitApkPaths = copiedSplitApkPaths,
                splitPublicSourceDirs = copiedSplitPublicSourceDirs,
                splitNames = splitArtifacts.map { it.splitName },
                splitApkSha256s = splitArtifacts.map { it.sha256 },
                isolatedSplits = isolatedSplits,
                versionCode = versionCode,
                versionName = versionName,
                targetSdk = targetSdk,
                minSdk = minSdk,
                nativeLibraries = nativeLibraries,
                abiList = abiList,
                applicationClassName = applicationClassName,
                packageLabel = packageLabel,
                applicationMetaData = applicationMetaData,
                permissions = permissions,
                activities = activities,
                services = services,
                receivers = receivers,
                providers = providers,
                installTimeMs = now
            )

            val recordPath = store.save(record).getOrThrow()

            val manifest = buildManifest(record)
            val packageRecord = buildPackageRecord(record, manifest)

            Result.success(
                ImportResult(
                    packageRecord = packageRecord,
                    manifest = manifest,
                    recordPath = recordPath
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun artifactFileFor(packageName: String): File {
        val artifactRoot = artifactDir.canonicalFile
        if (!artifactRoot.exists() && !artifactRoot.mkdirs()) {
            throw IllegalStateException("Unable to create artifact dir: ${artifactRoot.absolutePath}")
        }
        val destFile = File(artifactRoot, "$packageName-origin.apk").canonicalFile
        require(destFile.parentFile == artifactRoot) {
            "Artifact path escapes artifactDir"
        }
        return destFile
    }

    private fun splitArtifactFileFor(packageName: String, splitName: String, index: Int): File {
        val artifactRoot = artifactDir.canonicalFile
        if (!artifactRoot.exists() && !artifactRoot.mkdirs()) {
            throw IllegalStateException("Unable to create artifact dir: ${artifactRoot.absolutePath}")
        }
        val safeSplitName = safeArtifactSegment(splitName.ifBlank { "split$index" })
        val destFile = File(artifactRoot, "$packageName-$safeSplitName-split.apk").canonicalFile
        require(destFile.parentFile == artifactRoot) {
            "Split artifact path escapes artifactDir"
        }
        return destFile
    }

    private fun copySplitArtifacts(
        packageName: String,
        splitApkPaths: List<String>,
        splitNames: List<String>
    ): List<CopiedSplitArtifact> {
        if (splitApkPaths.isEmpty()) return emptyList()
        return splitApkPaths.mapIndexed { index, splitApkPath ->
            val splitFile = File(splitApkPath).canonicalFile
            if (!splitFile.isFile) {
                throw IllegalArgumentException("Split APK file not found: $splitApkPath")
            }
            val splitName = splitNames.getOrNull(index)
                ?.takeIf { it.isNotBlank() }
                ?: splitFile.nameWithoutExtension.ifBlank { "split$index" }
            val destFile = splitArtifactFileFor(packageName, splitName, index)
            copyAsReadOnlyArtifact(splitFile, destFile)
            CopiedSplitArtifact(
                splitName = splitName,
                file = destFile,
                sha256 = computeSha256(splitFile)
            )
        }
    }

    private fun safeArtifactSegment(value: String): String =
        value.map { char ->
            when {
                char.isLetterOrDigit() || char == '_' || char == '-' -> char
                else -> '_'
            }
        }.joinToString("").ifBlank { "split" }

    private fun copyAsReadOnlyArtifact(originFile: File, destFile: File) {
        if (!artifactDir.exists() && !artifactDir.mkdirs()) {
            throw IllegalStateException("Unable to create artifact dir: ${artifactDir.absolutePath}")
        }

        if (destFile.exists()) {
            destFile.setWritable(true, false)
            if (!destFile.delete()) {
                throw IllegalStateException("Unable to delete stale artifact: ${destFile.absolutePath}")
            }
        }

        originFile.copyTo(destFile, overwrite = false)
        destFile.setReadable(true, false)
        destFile.setWritable(false, false)
        if (destFile.canWrite()) {
            destFile.setReadOnly()
        }
    }

    private fun buildManifest(record: InstallRecord): InstallArtifactManifest {
        val baseApk = InstallArtifact(
            type = InstallArtifactType.BASE_APK,
            path = record.originApkPath,
            sha256 = record.originApkSha256,
            sizeBytes = File(record.originApkPath).length()
        )
        val splitApks = record.splitApkPaths.mapIndexed { index, path ->
            InstallArtifact(
                type = InstallArtifactType.SPLIT_APK,
                path = path,
                sha256 = record.splitApkSha256s.getOrNull(index).orEmpty(),
                sizeBytes = File(path).length(),
                splitName = record.splitNames.getOrNull(index)
            )
        }

        return InstallArtifactManifest(
            packageName = record.packageName,
            versionName = record.versionName,
            versionCode = record.versionCode,
            baseApk = baseApk,
            splitApks = splitApks,
            requestedPermissions = record.permissions,
            installedAt = record.installTimeMs,
            originPackageName = record.packageName,
            originVersionName = record.versionName,
            originVersionCode = record.versionCode,
            originApkPath = record.originApkPath,
            originApkSha256 = record.originApkSha256,
            originCertSha256 = record.originCertSha256,
            abiList = record.abiList
        )
    }

    private fun buildPackageRecord(
        record: InstallRecord,
        manifest: InstallArtifactManifest
    ): VirtualPackageRecord {
        return VirtualPackageRecord(
            packageName = record.packageName,
            appName = record.packageLabel ?: record.packageName,
            installManifest = manifest,
            versionName = record.versionName,
            versionCode = record.versionCode,
            minSdk = record.minSdk,
            targetSdk = record.targetSdk,
            requestedPermissions = record.permissions,
            activities = record.activities.map {
                VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            services = record.services.map {
                VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            providers = record.providers.map {
                VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            receivers = record.receivers.map {
                VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            nativeAbis = record.abiList,
            signingCertificateSha256 = record.originCertSha256,
            installedAt = record.installTimeMs,
            updatedAt = record.updatedAtMs
        )
    }

    private data class CopiedSplitArtifact(
        val splitName: String,
        val file: File,
        val sha256: String
    )
}
