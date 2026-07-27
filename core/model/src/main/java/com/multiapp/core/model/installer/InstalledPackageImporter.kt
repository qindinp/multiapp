package com.multiapp.core.model.installer

import com.multiapp.core.model.InstallArtifact
import com.multiapp.core.model.InstallArtifactManifest
import com.multiapp.core.model.InstallArtifactType
import com.multiapp.core.model.VirtualComponentRecord
import com.multiapp.core.model.VirtualPackageRecord
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID

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
        var transaction: ArtifactTransaction? = null
        return try {
            requireSafeInstallPackageName(packageName)
            val originFile = File(originApkPath).canonicalFile
            if (!originFile.isFile) {
                return Result.failure(IllegalArgumentException("APK file not found: $originApkPath"))
            }
            val splitSources = resolveSplitSources(
                splitApkPaths = splitApkPaths,
                splitNames = splitNames
            )
            val previousRecord = store.load(packageName)
            val artifactRoot = requireArtifactRoot()
            val artifactTransaction = ArtifactTransaction()
            transaction = artifactTransaction

            val stagedBase = stageArtifact(
                source = originFile,
                artifactRoot = artifactRoot,
                ordinal = "base",
                transaction = artifactTransaction
            )
            val stagedSplits = splitSources.mapIndexed { index, split ->
                stageArtifact(
                    source = split.file,
                    artifactRoot = artifactRoot,
                    ordinal = "split-$index",
                    transaction = artifactTransaction,
                    splitName = split.splitName,
                    splitIndex = index
                )
            }
            val targets = buildList {
                add(
                    ArtifactTarget(
                        staged = stagedBase,
                        finalFile = artifactFileFor(
                            artifactRoot = artifactRoot,
                            fileName = "$packageName-base-${stagedBase.sha256}.apk"
                        )
                    )
                )
                stagedSplits.forEach { staged ->
                    val index = checkNotNull(staged.splitIndex)
                    val splitName = checkNotNull(staged.splitName)
                    val safeSplitName = safeArtifactSegment(splitName).take(MAX_SPLIT_NAME_LENGTH)
                    add(
                        ArtifactTarget(
                            staged = staged,
                            finalFile = artifactFileFor(
                                artifactRoot = artifactRoot,
                                fileName = buildString {
                                    append(packageName)
                                    append("-split-")
                                    append(index.toString().padStart(SPLIT_INDEX_WIDTH, '0'))
                                    append('-')
                                    append(safeSplitName)
                                    append('-')
                                    append(staged.sha256)
                                    append(".apk")
                                }
                            )
                        )
                    )
                }
            }
            require(targets.map { it.finalFile.absolutePath }.distinct().size == targets.size) {
                "Artifact target paths must be unique"
            }

            val committedArtifacts = commitArtifacts(targets, artifactTransaction)
            val baseArtifact = committedArtifacts.first()
            val splitArtifacts = committedArtifacts.drop(1)
            val copiedSplitApkPaths = splitArtifacts.map { it.file.absolutePath }
            val copiedSplitPublicSourceDirs = copiedSplitApkPaths.takeIf { splitPublicSourceDirs.isNotEmpty() }
                ?: copiedSplitApkPaths

            val now = System.currentTimeMillis()
            val record = InstallRecord(
                packageName = packageName,
                originApkPath = baseArtifact.file.absolutePath,
                originApkSha256 = baseArtifact.sha256,
                originCertSha256 = originCertSha256.ifBlank { signerSha256Digests.lastOrNull().orEmpty() },
                signerSha256Digests = signerSha256Digests,
                hasMultipleSigners = hasMultipleSigners,
                splitApkPaths = copiedSplitApkPaths,
                splitPublicSourceDirs = copiedSplitPublicSourceDirs,
                splitNames = splitArtifacts.map { checkNotNull(it.splitName) },
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
                installTimeMs = previousRecord?.installTimeMs ?: now,
                updatedAtMs = if (previousRecord == null) now else now.coerceAtLeast(previousRecord.updatedAtMs + 1L)
            )

            val manifest = buildManifest(record)
            val packageRecord = buildPackageRecord(record, manifest)
            val recordPath = store.save(record).getOrThrow()
            artifactTransaction.recordCommitted = true
            cleanupSupersededArtifacts(
                artifactRoot = artifactRoot,
                previousRecord = previousRecord,
                currentRecord = record
            )

            Result.success(
                ImportResult(
                    packageRecord = packageRecord,
                    manifest = manifest,
                    recordPath = recordPath
                )
            )
        } catch (e: CancellationException) {
            transaction?.rollback()
            throw e
        } catch (e: Exception) {
            transaction?.rollback()
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

    private fun requireArtifactRoot(): File {
        val artifactRoot = artifactDir.canonicalFile
        if (!artifactRoot.exists() && !artifactRoot.mkdirs()) {
            throw IllegalStateException("Unable to create artifact dir: ${artifactRoot.absolutePath}")
        }
        if (!artifactRoot.isDirectory) {
            throw IllegalStateException("Artifact path is not a directory: ${artifactRoot.absolutePath}")
        }
        return artifactRoot
    }

    private fun artifactFileFor(artifactRoot: File, fileName: String): File {
        val target = File(artifactRoot, fileName).canonicalFile
        require(target.parentFile == artifactRoot) {
            "Artifact path escapes artifactDir"
        }
        return target
    }

    private fun resolveSplitSources(
        splitApkPaths: List<String>,
        splitNames: List<String>
    ): List<SplitSource> {
        if (splitApkPaths.isEmpty()) return emptyList()
        return splitApkPaths.mapIndexed { index, splitApkPath ->
            val splitFile = File(splitApkPath).canonicalFile
            if (!splitFile.isFile) {
                throw IllegalArgumentException("Split APK file not found: $splitApkPath")
            }
            val splitName = splitNames.getOrNull(index)
                ?.takeIf { it.isNotBlank() }
                ?: splitFile.nameWithoutExtension.ifBlank { "split$index" }
            SplitSource(
                splitName = splitName,
                file = splitFile
            )
        }
    }

    private fun stageArtifact(
        source: File,
        artifactRoot: File,
        ordinal: String,
        transaction: ArtifactTransaction,
        splitName: String? = null,
        splitIndex: Int? = null
    ): StagedArtifact {
        val stagingFile = artifactFileFor(
            artifactRoot = artifactRoot,
            fileName = ".install-${UUID.randomUUID()}-$ordinal.tmp"
        )
        transaction.stagedFiles += stagingFile
        source.copyTo(stagingFile, overwrite = false)
        return StagedArtifact(
            splitName = splitName,
            splitIndex = splitIndex,
            stagingFile = stagingFile,
            sha256 = computeSha256(stagingFile)
        )
    }

    private fun commitArtifacts(
        targets: List<ArtifactTarget>,
        transaction: ArtifactTransaction
    ): List<CommittedArtifact> {
        return targets.map { target ->
            val staged = target.staged
            val finalFile = target.finalFile
            if (finalFile.exists()) {
                require(finalFile.isFile) {
                    "Artifact target is not a file: ${finalFile.absolutePath}"
                }
                val existingSha256 = computeSha256(finalFile)
                require(existingSha256 == staged.sha256) {
                    "Artifact digest mismatch at existing target: ${finalFile.absolutePath}"
                }
                deleteArtifact(staged.stagingFile)
            } else {
                if (!staged.stagingFile.renameTo(finalFile)) {
                    throw IllegalStateException(
                        "Unable to commit staged artifact: ${finalFile.absolutePath}"
                    )
                }
                transaction.createdFiles += finalFile
            }

            val finalSha256 = computeSha256(finalFile)
            require(finalSha256 == staged.sha256) {
                "Committed artifact digest mismatch: ${finalFile.absolutePath}"
            }
            require(finalFile.name.endsWith("-$finalSha256.apk")) {
                "Artifact filename does not match its digest: ${finalFile.absolutePath}"
            }
            makeReadOnly(finalFile)
            CommittedArtifact(
                splitName = staged.splitName,
                file = finalFile,
                sha256 = finalSha256
            )
        }
    }

    private fun safeArtifactSegment(value: String): String =
        value.map { char ->
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-' -> char
                else -> '_'
            }
        }.joinToString("").ifBlank { "split" }

    private fun makeReadOnly(file: File) {
        file.setReadable(true, false)
        file.setWritable(false, false)
        if (file.canWrite()) {
            file.setReadOnly()
        }
    }

    private fun deleteArtifact(file: File) {
        if (!file.exists()) return
        file.setWritable(true, false)
        if (!file.delete()) {
            throw IllegalStateException("Unable to delete artifact: ${file.absolutePath}")
        }
    }

    private fun deleteArtifactQuietly(file: File) {
        runCatching { deleteArtifact(file) }
    }

    private fun cleanupSupersededArtifacts(
        artifactRoot: File,
        previousRecord: InstallRecord?,
        currentRecord: InstallRecord
    ) {
        if (previousRecord == null) return
        val currentPaths = currentRecord.codeSourceDirs.mapNotNull { path ->
            runCatching { File(path).canonicalFile.absolutePath }.getOrNull()
        }.toSet()
        previousRecord.codeSourceDirs.forEach { path ->
            val previousArtifact = runCatching { File(path).canonicalFile }.getOrNull() ?: return@forEach
            if (previousArtifact.parentFile == artifactRoot && previousArtifact.absolutePath !in currentPaths) {
                deleteArtifactQuietly(previousArtifact)
            }
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

    private inner class ArtifactTransaction {
        val stagedFiles = mutableListOf<File>()
        val createdFiles = mutableListOf<File>()
        var recordCommitted: Boolean = false

        fun rollback() {
            if (recordCommitted) return
            createdFiles.asReversed().forEach(::deleteArtifactQuietly)
            stagedFiles.asReversed().forEach(::deleteArtifactQuietly)
        }
    }

    private data class SplitSource(
        val splitName: String,
        val file: File
    )

    private data class StagedArtifact(
        val splitName: String?,
        val splitIndex: Int?,
        val stagingFile: File,
        val sha256: String
    )

    private data class ArtifactTarget(
        val staged: StagedArtifact,
        val finalFile: File
    )

    private data class CommittedArtifact(
        val splitName: String?,
        val file: File,
        val sha256: String
    )

    private companion object {
        const val MAX_SPLIT_NAME_LENGTH = 48
        const val SPLIT_INDEX_WIDTH = 3
    }
}
