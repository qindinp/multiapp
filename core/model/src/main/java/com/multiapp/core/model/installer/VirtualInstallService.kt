package com.multiapp.core.model.installer

import com.multiapp.core.model.InstallArtifactManifest
import com.multiapp.core.model.VirtualApp
import com.multiapp.core.model.VirtualPackageRecord

data class ImportResult(
    val packageRecord: VirtualPackageRecord,
    val manifest: InstallArtifactManifest,
    val recordPath: String
)

interface VirtualInstallService {
    suspend fun importFromInstalledPackage(packageName: String): Result<ImportResult>

    /**
     * Import an InstallRecord from pre-extracted package metadata.
     *
     * This is the primary entry point for the UI creation path.
     * If an InstallRecord already exists for this package, returns the existing one.
     *
     * @param packageName Origin package name.
     * @param originApkPath Path to the origin APK file.
     * @param versionCode Version code from PackageInfo.
     * @param versionName Version name from PackageInfo.
     * @param targetSdk Target SDK version.
     * @param minSdk Minimum SDK version.
     * @param applicationClassName Application class name (null for default).
     * @param packageLabel User-visible app label.
     * @return ImportResult on success, or failure.
     */
    fun importFromMetadata(
        packageName: String,
        originApkPath: String,
        versionCode: Long,
        versionName: String,
        targetSdk: Int,
        minSdk: Int,
        applicationClassName: String? = null,
        packageLabel: String? = null
    ): Result<ImportResult>

    /**
     * Ensure the origin app has an InstallRecord before instance creation.
     *
     * UI creation paths already hold a [VirtualApp] with PackageManager metadata.
     * Keeping the mapping here prevents callers from creating an instance without
     * first importing the install record required by HostedRuntimeBootstrap.
     */
    fun ensureInstallRecord(app: VirtualApp): Result<ImportResult> {
        return importFromMetadata(
            packageName = app.packageName,
            originApkPath = app.apkPath,
            versionCode = app.versionCode,
            versionName = app.versionName,
            targetSdk = app.targetSdkVersion,
            minSdk = app.minSdkVersion,
            applicationClassName = app.applicationClassName,
            packageLabel = app.appName
        )
    }

    fun getInstallRecord(packageName: String): InstallRecord?
    fun listInstallRecords(): List<InstallRecord>
    fun deleteInstallRecord(packageName: String): Boolean
    fun hasInstallRecord(packageName: String): Boolean
}
