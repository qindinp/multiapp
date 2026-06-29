package com.multiapp.core.model.installer

import java.io.File
import java.util.concurrent.CancellationException

/**
 * Production implementation of [VirtualInstallService].
 *
 * Encapsulates [InstalledPackageImporter], artifact directory management,
 * and [InstallRecordStore] persistence. This is the single entry point
 * for ensuring an InstallRecord exists before creating a virtual instance.
 *
 * Thread safety: single-writer (primary process), multi-reader (all processes).
 *
 * @param installRecordStore Persistence backend for install records.
 * @param artifactDir        Directory for copied APK artifacts.
 */
class ProductionVirtualInstallService(
    private val installRecordStore: InstallRecordStore,
    private val artifactDir: File,
    private val metadataResolver: InstallMetadataResolver? = null
) : VirtualInstallService {

    private val importer = InstalledPackageImporter(installRecordStore, artifactDir)

    init {
        if (!artifactDir.exists()) {
            artifactDir.mkdirs()
        }
    }

    override suspend fun importFromInstalledPackage(packageName: String): Result<ImportResult> {
        // Requires Android framework PackageManager - use importFromMetadata() instead
        // with pre-extracted metadata from VirtualApp (populated by LauncherViewModel.loadAllApps).
        return Result.failure(UnsupportedOperationException(
            "Use importFromMetadata() with pre-extracted package metadata from VirtualApp"
        ))
    }

    override fun importFromMetadata(
        packageName: String,
        originApkPath: String,
        versionCode: Long,
        versionName: String,
        targetSdk: Int,
        minSdk: Int,
        applicationClassName: String?,
        packageLabel: String?
    ): Result<ImportResult> {
        return try {
            val existing = installRecordStore.load(packageName)
            val resolvedMetadata = resolveInstallMetadata(packageName, originApkPath)

            // Idempotent for complete records. Older v2 records may have been created
            // before component import existed; refresh those so hosted launch can find
            // the guest launcher Activity.
            if (existing != null && !existing.needsMetadataRefresh(resolvedMetadata)) {
                return Result.success(ImportResult(
                    packageRecord = buildPackageRecord(existing),
                    manifest = buildManifest(existing),
                    recordPath = existing.originApkPath // approximate; actual path is installs/{pkg}.json
                ))
            }

            importer.importFromMetadata(
                packageName = packageName,
                originApkPath = originApkPath,
                versionCode = versionCode,
                versionName = versionName,
                targetSdk = targetSdk,
                minSdk = minSdk,
                applicationClassName = applicationClassName,
                packageLabel = packageLabel,
                permissions = resolvedMetadata.permissions,
                activities = resolvedMetadata.activities,
                services = resolvedMetadata.services,
                receivers = resolvedMetadata.receivers,
                providers = resolvedMetadata.providers
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveInstallMetadata(packageName: String, originApkPath: String): InstallMetadata {
        return runCatching {
            metadataResolver?.resolve(packageName, originApkPath)
        }.getOrNull() ?: InstallMetadata()
    }

    private fun InstallRecord.needsMetadataRefresh(metadata: InstallMetadata): Boolean {
        if (metadata == InstallMetadata()) return false
        return activities.isEmpty() && metadata.activities.isNotEmpty() ||
            services.isEmpty() && metadata.services.isNotEmpty() ||
            receivers.isEmpty() && metadata.receivers.isNotEmpty() ||
            providers.isEmpty() && metadata.providers.isNotEmpty() ||
            permissions.isEmpty() && metadata.permissions.isNotEmpty()
    }

    override fun getInstallRecord(packageName: String): InstallRecord? {
        return installRecordStore.load(packageName)
    }

    override fun listInstallRecords(): List<InstallRecord> {
        return installRecordStore.listAll()
    }

    override fun deleteInstallRecord(packageName: String): Boolean {
        return installRecordStore.delete(packageName)
    }

    override fun hasInstallRecord(packageName: String): Boolean {
        return installRecordStore.load(packageName) != null
    }

    private fun buildManifest(record: InstallRecord): com.multiapp.core.model.InstallArtifactManifest {
        val baseApk = com.multiapp.core.model.InstallArtifact(
            type = com.multiapp.core.model.InstallArtifactType.BASE_APK,
            path = record.originApkPath,
            sha256 = record.originApkSha256,
            sizeBytes = File(record.originApkPath).length()
        )
        return com.multiapp.core.model.InstallArtifactManifest(
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

    private fun buildPackageRecord(record: InstallRecord): com.multiapp.core.model.VirtualPackageRecord {
        val manifest = buildManifest(record)
        return com.multiapp.core.model.VirtualPackageRecord(
            packageName = record.packageName,
            appName = record.packageLabel ?: record.packageName,
            installManifest = manifest,
            versionName = record.versionName,
            versionCode = record.versionCode,
            minSdk = record.minSdk,
            targetSdk = record.targetSdk,
            requestedPermissions = record.permissions,
            activities = record.activities.map {
                com.multiapp.core.model.VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            services = record.services.map {
                com.multiapp.core.model.VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            providers = record.providers.map {
                com.multiapp.core.model.VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            receivers = record.receivers.map {
                com.multiapp.core.model.VirtualComponentRecord(name = it.name, exported = it.exported)
            },
            nativeAbis = record.abiList,
            signingCertificateSha256 = record.originCertSha256,
            installedAt = record.installTimeMs,
            updatedAt = record.updatedAtMs
        )
    }
}
