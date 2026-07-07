package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ResolvedPackage
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

class PackageSnapshotStage(
    private val packageMetadataResolver: (String) -> ResolvedPackage?,
    private val packageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val instance = input.instance ?: return failed(
            input = input,
            startMs = startMs,
            message = "Instance is required before package snapshot"
        )
        val installRecord = input.installRecord ?: return failed(
            input = input,
            startMs = startMs,
            message = "Install record is required before package snapshot"
        )
        val originApkPath = input.originApkPath ?: return failed(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before package snapshot"
        )

        val resolvedPackage = runCatching {
            packageMetadataResolver(originApkPath)
        }.getOrNull()
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instance,
            installRecord = installRecord,
            resolvedPackage = resolvedPackage,
            nativeLibraryDir = input.nativeLibraryDir
        )
        val registeredSnapshot = packageRegistry.register(snapshot)
        val durationMs = clock() - startMs

        return BootstrapStageOutput(
            context = input.copy(
                resolvedPackage = resolvedPackage,
                packageSnapshot = registeredSnapshot
            ),
            result = BootstrapResult.success(
                stage = RuntimeStage.RESOURCES,
                message = "Package snapshot registered: ${registeredSnapshot.virtualPackageName}",
                evidence = snapshotEvidence(registeredSnapshot),
                durationMs = durationMs
            )
        )
    }

    private fun failed(
        input: BootstrapStageInput,
        startMs: Long,
        message: String
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.RESOURCES,
            message = message,
            durationMs = clock() - startMs
        )
    )

    private fun snapshotEvidence(snapshot: VirtualPackageSnapshot): List<BootstrapEvidence> =
        listOf(
            BootstrapEvidence("instanceId", snapshot.instanceId),
            BootstrapEvidence("originPackageName", snapshot.originPackageName),
            BootstrapEvidence("virtualPackageName", snapshot.virtualPackageName),
            BootstrapEvidence("sourceDir", snapshot.sourceDir),
            BootstrapEvidence("splitSourceDirCount", snapshot.splitSourceDirs.size.toString()),
            BootstrapEvidence("splitSourceDirs", snapshot.splitSourceDirs.joinToString(",")),
            BootstrapEvidence("splitNames", snapshot.splitNames.joinToString(",")),
            BootstrapEvidence("isolatedSplits", snapshot.isolatedSplits.toString()),
            BootstrapEvidence("dataDir", snapshot.dataDir),
            BootstrapEvidence("nativeLibraryDir", snapshot.nativeLibraryDir.orEmpty()),
            BootstrapEvidence("providerCount", snapshot.providers.size.toString()),
            BootstrapEvidence("activityCount", snapshot.activities.size.toString())
        )
}
