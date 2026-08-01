package com.multiapp.core.loader

import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.toResolvedComponents
import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.toLegacyMetaDataMap

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
            metaData = resolvedPackage?.metaData?.takeIf { it.isNotEmpty() }
                ?: installRecord.applicationMetaData.toLegacyMetaDataMap(),
            typedMetaData = resolvedPackage?.typedMetaData?.takeIf { it.isNotEmpty() }
                ?: installRecord.applicationMetaData,
            launcherActivityName = resolvedPackage?.launcherActivityName
                ?: resolvedPackage?.activities?.resolveLauncherIntentActivityName(),
            activities = resolvedPackage?.activities?.takeIf { it.isNotEmpty() }
                ?: installRecord.activities.toResolvedComponents(),
            services = resolvedPackage?.services?.takeIf { it.isNotEmpty() }
                ?: installRecord.services.toResolvedComponents(),
            receivers = resolvedPackage?.receivers?.takeIf { it.isNotEmpty() }
                ?: installRecord.receivers.toResolvedComponents(),
            providers = resolvedPackage?.providers?.takeIf { it.isNotEmpty() }
                ?: installRecord.providers.toResolvedComponents(),
            permissions = resolvedPackage?.permissions ?: installRecord.permissions,
            originCertSha256 = installRecord.originCertSha256,
            signerSha256Digests = installRecord.signerSha256Digests,
            hasMultipleSigners = installRecord.hasMultipleSigners,
            debuggable = installRecord.debuggable,
            sharedUserId = installRecord.sharedUserId,
            sharedUserLabel = installRecord.sharedUserLabel
        )
    }

}
