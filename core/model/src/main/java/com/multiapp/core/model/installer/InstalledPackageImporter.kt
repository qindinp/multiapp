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
        originCertSha256: String = ""
    ): Result<ImportResult> {
        return try {
            val originFile = File(originApkPath)
            if (!originFile.exists()) {
                return Result.failure(IllegalArgumentException("APK file not found: $originApkPath"))
            }

            val originApkSha256 = computeSha256(originFile)

            val destFile = File(artifactDir, "$packageName-origin.apk")
            originFile.copyTo(destFile, overwrite = true)

            val now = System.currentTimeMillis()
            val record = InstallRecord(
                packageName = packageName,
                originApkPath = destFile.absolutePath,
                originApkSha256 = originApkSha256,
                originCertSha256 = originCertSha256,
                versionCode = versionCode,
                versionName = versionName,
                targetSdk = targetSdk,
                minSdk = minSdk,
                nativeLibraries = nativeLibraries,
                abiList = abiList,
                applicationClassName = applicationClassName,
                packageLabel = packageLabel,
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

    private fun buildManifest(record: InstallRecord): InstallArtifactManifest {
        val baseApk = InstallArtifact(
            type = InstallArtifactType.BASE_APK,
            path = record.originApkPath,
            sha256 = record.originApkSha256,
            sizeBytes = File(record.originApkPath).length()
        )

        return InstallArtifactManifest(
            packageName = record.packageName,
            versionName = record.versionName,
            versionCode = record.versionCode,
            baseApk = baseApk,
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
}
