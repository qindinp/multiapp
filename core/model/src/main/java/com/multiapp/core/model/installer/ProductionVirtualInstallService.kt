package com.multiapp.core.model.installer

import com.multiapp.core.model.VirtualApp
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID

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

    override fun ensureInstallRecord(app: VirtualApp): Result<ImportResult> {
        return try {
            requireSafeInstallPackageName(app.packageName)
            val existing = installRecordStore.load(app.packageName)
            val appMetadata = app.toInstallMetadata()
            val resolvedMetadata = resolveInstallMetadata(app.packageName, app.apkPath)
            val importMetadata = appMetadata.withResolvedFallback(resolvedMetadata)
                .withExistingSignerFallback(existing)
            val appApkSha256 = computeSha256OrNull(app.apkPath)
            val appSplitSha256s = computeSha256sOrNull(importMetadata.splitApkPaths)
            if (existing != null && !existing.needsAppRefresh(app, importMetadata, appApkSha256, appSplitSha256s)) {
                return Result.success(ImportResult(
                    packageRecord = buildPackageRecord(existing),
                    manifest = buildManifest(existing),
                    recordPath = existing.originApkPath
                ))
            }

            importer.importFromMetadata(
                packageName = app.packageName,
                originApkPath = app.apkPath,
                versionCode = app.versionCode,
                versionName = app.versionName,
                targetSdk = app.targetSdkVersion,
                minSdk = app.minSdkVersion,
                applicationClassName = app.applicationClassName,
                packageLabel = app.appName,
                permissions = importMetadata.permissions,
                activities = importMetadata.activities,
                services = importMetadata.services,
                receivers = importMetadata.receivers,
                providers = importMetadata.providers,
                applicationMetaData = importMetadata.applicationMetaData,
                signerSha256Digests = importMetadata.signerSha256Digests,
                hasMultipleSigners = importMetadata.hasMultipleSigners,
                nativeLibraries = importMetadata.nativeLibraries,
                abiList = importMetadata.abiList,
                splitApkPaths = importMetadata.splitApkPaths,
                splitPublicSourceDirs = importMetadata.splitPublicSourceDirs,
                splitNames = importMetadata.splitNames,
                isolatedSplits = importMetadata.isolatedSplits
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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
            requireSafeInstallPackageName(packageName)
            val existing = installRecordStore.load(packageName)
            val resolvedMetadata = resolveInstallMetadata(packageName, originApkPath)
                .withExistingSignerFallback(existing)

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
                providers = resolvedMetadata.providers,
                applicationMetaData = resolvedMetadata.applicationMetaData,
                signerSha256Digests = resolvedMetadata.signerSha256Digests,
                hasMultipleSigners = resolvedMetadata.hasMultipleSigners,
                nativeLibraries = resolvedMetadata.nativeLibraries,
                abiList = resolvedMetadata.abiList,
                splitApkPaths = resolvedMetadata.splitApkPaths,
                splitPublicSourceDirs = resolvedMetadata.splitPublicSourceDirs,
                splitNames = resolvedMetadata.splitNames,
                isolatedSplits = resolvedMetadata.isolatedSplits
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveInstallMetadata(packageName: String, originApkPath: String): InstallMetadata {
        return metadataResolver?.resolve(packageName, originApkPath) ?: InstallMetadata()
    }

    private fun VirtualApp.toInstallMetadata(): InstallMetadata {
        return InstallMetadata(
            permissions = requestedPermissions,
            activities = activities.map { ComponentInfo(it) },
            services = services.map { ComponentInfo(it) },
            receivers = receivers.map { ComponentInfo(it) },
            providers = providers.map { ComponentInfo(it) },
            applicationMetaData = emptyMap(),
            signerSha256Digests = emptyList(),
            hasMultipleSigners = false,
            nativeLibraries = emptyList(),
            abiList = nativeAbis,
            splitApkPaths = splitApkPaths,
            splitPublicSourceDirs = splitPublicSourceDirs.ifEmpty { splitApkPaths },
            splitNames = splitNames,
            isolatedSplits = isolatedSplits
        )
    }

    private fun InstallMetadata.withResolvedFallback(resolved: InstallMetadata): InstallMetadata {
        return InstallMetadata(
            permissions = resolved.permissions.ifEmpty { permissions },
            activities = resolved.activities.ifEmpty { activities },
            services = resolved.services.ifEmpty { services },
            receivers = resolved.receivers.ifEmpty { receivers },
            providers = resolved.providers.ifEmpty { providers },
            applicationMetaData = resolved.applicationMetaData.ifEmpty { applicationMetaData },
            signerSha256Digests = resolved.signerSha256Digests.ifEmpty { signerSha256Digests },
            hasMultipleSigners = resolved.hasMultipleSigners || hasMultipleSigners,
            nativeLibraries = resolved.nativeLibraries.ifEmpty { nativeLibraries },
            abiList = resolved.abiList.ifEmpty { abiList },
            splitApkPaths = resolved.splitApkPaths.ifEmpty { splitApkPaths },
            splitPublicSourceDirs = resolved.splitPublicSourceDirs.ifEmpty { splitPublicSourceDirs },
            splitNames = resolved.splitNames.ifEmpty { splitNames },
            isolatedSplits = resolved.isolatedSplits || isolatedSplits
        )
    }

    private fun InstallMetadata.withExistingSignerFallback(existing: InstallRecord?): InstallMetadata {
        if (signerSha256Digests.isNotEmpty() || existing == null) return this
        return copy(
            signerSha256Digests = existing.signerSha256Digests,
            hasMultipleSigners = existing.hasMultipleSigners
        )
    }

    private fun InstallRecord.needsMetadataRefresh(metadata: InstallMetadata): Boolean {
        if (metadata == InstallMetadata()) return false
        return !matchesInstallMetadata(metadata) ||
            activities.isEmpty() && metadata.activities.isNotEmpty() ||
            services.isEmpty() && metadata.services.isNotEmpty() ||
            receivers.isEmpty() && metadata.receivers.isNotEmpty() ||
            providers.isEmpty() && metadata.providers.isNotEmpty() ||
            applicationMetaData.isEmpty() && metadata.applicationMetaData.isNotEmpty() ||
            signerSha256Digests.isEmpty() && metadata.signerSha256Digests.isNotEmpty() ||
            hasMultipleSigners != metadata.hasMultipleSigners ||
            permissions.isEmpty() && metadata.permissions.isNotEmpty() ||
            nativeLibraries.isEmpty() && metadata.nativeLibraries.isNotEmpty() ||
            abiList.isEmpty() && metadata.abiList.isNotEmpty() ||
            splitApkPaths.isEmpty() && metadata.splitApkPaths.isNotEmpty() ||
            splitNames != metadata.splitNames ||
            isolatedSplits != metadata.isolatedSplits
    }

    private fun InstallRecord.needsAppRefresh(
        app: VirtualApp,
        metadata: InstallMetadata,
        appApkSha256: String?,
        appSplitSha256s: List<String>?
    ): Boolean {
        return versionCode != app.versionCode ||
            versionName != app.versionName ||
            minSdk != app.minSdkVersion ||
            targetSdk != app.targetSdkVersion ||
            applicationClassName != app.applicationClassName ||
            packageLabel != app.appName ||
            appApkSha256 == null ||
            originApkSha256 != appApkSha256 ||
            appSplitSha256s == null ||
            splitApkSha256s != appSplitSha256s ||
            needsMetadataRefresh(metadata)
    }

    private fun InstallRecord.matchesInstallMetadata(metadata: InstallMetadata): Boolean {
        return nativeLibraries == metadata.nativeLibraries &&
            abiList == metadata.abiList &&
            permissions == metadata.permissions &&
            activities == metadata.activities &&
            services == metadata.services &&
            receivers == metadata.receivers &&
            providers == metadata.providers &&
            applicationMetaData == metadata.applicationMetaData &&
            signerSha256Digests == metadata.signerSha256Digests &&
            hasMultipleSigners == metadata.hasMultipleSigners &&
            splitApkPaths == metadata.splitApkPaths &&
            splitPublicSourceDirs == metadata.splitPublicSourceDirs &&
            splitNames == metadata.splitNames &&
            isolatedSplits == metadata.isolatedSplits
    }

    private fun computeSha256OrNull(path: String): String? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun computeSha256sOrNull(paths: List<String>): List<String>? {
        if (paths.isEmpty()) return emptyList()
        val hashes = paths.map { path -> computeSha256OrNull(path) ?: return null }
        return hashes
    }

    override fun getInstallRecord(packageName: String): InstallRecord? {
        requireSafeInstallPackageName(packageName)
        return installRecordStore.load(packageName)
    }

    override fun listInstallRecords(): List<InstallRecord> {
        return installRecordStore.listAll()
    }

    override fun deleteInstallRecord(packageName: String): Boolean {
        requireSafeInstallPackageName(packageName)
        val record = installRecordStore.load(packageName) ?: return false
        val artifactRoot = runCatching { artifactDir.canonicalFile }.getOrNull() ?: return false
        val artifacts = record.codeSourceDirs
            .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            .distinctBy { it.absolutePath }
        if (artifacts.any { file -> file.parentFile != artifactRoot }) return false

        val staged = mutableListOf<Pair<File, File>>()
        for (artifact in artifacts) {
            if (!artifact.exists()) continue
            val tombstone = File(
                artifactRoot,
                ".${artifact.name}.delete-${UUID.randomUUID()}"
            )
            if (!artifact.renameTo(tombstone)) {
                restoreStagedArtifacts(staged)
                return false
            }
            staged += artifact to tombstone
        }

        if (!installRecordStore.delete(packageName)) {
            restoreStagedArtifacts(staged)
            return false
        }

        staged.forEach { (_, tombstone) ->
            tombstone.setWritable(true, false)
            tombstone.delete()
        }
        return true
    }

    override fun hasInstallRecord(packageName: String): Boolean {
        requireSafeInstallPackageName(packageName)
        return installRecordStore.load(packageName) != null
    }

    private fun requireSafeInstallPackageName(packageName: String) {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(!packageName.contains("..") && !packageName.contains("/") && !packageName.contains("\\")) {
            "Invalid packageName: $packageName"
        }
    }

    private fun restoreStagedArtifacts(staged: List<Pair<File, File>>) {
        staged.asReversed().forEach { (artifact, tombstone) ->
            if (tombstone.exists() && !artifact.exists()) {
                tombstone.renameTo(artifact)
            }
        }
    }

    private fun buildManifest(record: InstallRecord): com.multiapp.core.model.InstallArtifactManifest {
        val baseApk = com.multiapp.core.model.InstallArtifact(
            type = com.multiapp.core.model.InstallArtifactType.BASE_APK,
            path = record.originApkPath,
            sha256 = record.originApkSha256,
            sizeBytes = File(record.originApkPath).length()
        )
        val splitApks = record.splitApkPaths.mapIndexed { index, path ->
            com.multiapp.core.model.InstallArtifact(
                type = com.multiapp.core.model.InstallArtifactType.SPLIT_APK,
                path = path,
                sha256 = record.splitApkSha256s.getOrNull(index).orEmpty(),
                sizeBytes = File(path).length(),
                splitName = record.splitNames.getOrNull(index)
            )
        }
        return com.multiapp.core.model.InstallArtifactManifest(
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
