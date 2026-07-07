package com.multiapp.core.loader

import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

internal object VirtualPackageSnapshotFactory {

    fun create(
        instance: VirtualInstanceRecord,
        installRecord: InstallRecord,
        resolvedPackage: ResolvedPackage?,
        nativeLibraryDir: String?
    ): VirtualPackageSnapshot {
        return VirtualPackageSnapshot(
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            virtualPackageName = instance.virtualPackageName,
            applicationLabel = resolvedPackage?.applicationLabel
                ?: installRecord.packageLabel
                ?: instance.displayName.ifBlank { instance.originPackageName },
            versionCode = installRecord.versionCode,
            versionName = installRecord.versionName,
            targetSdk = resolvedPackage?.targetSdk ?: installRecord.targetSdk,
            minSdk = resolvedPackage?.minSdk ?: installRecord.minSdk,
            sourceDir = installRecord.originApkPath,
            publicSourceDir = installRecord.originApkPath,
            splitSourceDirs = resolvedPackage?.splitSourceDirs?.takeIf { it.isNotEmpty() }
                ?: installRecord.splitApkPaths,
            splitPublicSourceDirs = resolvedPackage?.splitPublicSourceDirs?.takeIf { it.isNotEmpty() }
                ?: installRecord.splitPublicSourceDirs.ifEmpty { installRecord.splitApkPaths },
            splitNames = resolvedPackage?.splitNames?.takeIf { it.isNotEmpty() }
                ?: installRecord.splitNames,
            isolatedSplits = resolvedPackage?.isolatedSplits ?: installRecord.isolatedSplits,
            dataDir = instance.dataRoot,
            nativeLibraryDir = nativeLibraryDir ?: resolvedPackage?.nativeLibDir,
            applicationClassName = resolvedPackage?.applicationClassName ?: installRecord.applicationClassName,
            processName = resolvedPackage?.processName,
            taskAffinity = resolvedPackage?.taskAffinity,
            themeId = resolvedPackage?.themeId ?: 0,
            metaData = resolvedPackage?.metaData ?: emptyMap(),
            launcherActivityName = resolvedPackage?.launcherActivityName
                ?: resolvedPackage?.activities?.resolveLauncherIntentActivityName(),
            activities = resolvedPackage?.activities ?: emptyList(),
            services = resolvedPackage?.services ?: emptyList(),
            receivers = resolvedPackage?.receivers ?: emptyList(),
            providers = resolvedPackage?.providers ?: emptyList(),
            permissions = resolvedPackage?.permissions ?: installRecord.permissions,
            originCertSha256 = installRecord.originCertSha256
        )
    }
}
