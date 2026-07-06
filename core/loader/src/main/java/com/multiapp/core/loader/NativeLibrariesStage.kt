package com.multiapp.core.loader

import android.content.Context

internal class NativeLibrariesStage(
    private val nativeLibraryResolver: (originApkPath: String?, dataRoot: String?) -> NativeLibraryResolution =
        NativeLibraryPaths::resolveAndExtract,
    private val hostContext: Context? = null,
    private val nativePrivatePathRedirectInstaller: NativePrivatePathRedirectInstaller =
        NativePrivatePathRedirectInstallers.bridge(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val instance = input.instance
        if (instance == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.NATIVE_LIBS,
                    message = "Instance is required before resolving native library directory",
                    durationMs = clock() - startMs
                )
            )
        }

        val resolution = runCatching {
            nativeLibraryResolver(input.originApkPath, instance.dataRoot)
        }.getOrElse { error ->
            return BootstrapStageOutput(
                context = input.copy(nativeLibraryDir = null),
                result = BootstrapResult.failed(
                    stage = RuntimeStage.NATIVE_LIBS,
                    message = "Native library resolution failed: ${error.message}",
                    error = error,
                    evidence = listOf(
                        BootstrapEvidence("originApkPath", input.originApkPath.orEmpty()),
                        BootstrapEvidence("dataRoot", instance.dataRoot)
                    ),
                    durationMs = clock() - startMs
                )
            )
        }
        val nativeLibraryDir = resolution.nativeLibraryDir
        val redirectInstallResult = nativePrivatePathRedirectInstaller.install(
            instanceId = instance.instanceId,
            originPackageName = instance.originPackageName,
            dataRoot = instance.dataRoot,
            hostContext = hostContext
        )
        val evidence = nativeEvidence(resolution, redirectInstallResult)
        val durationMs = clock() - startMs
        if (nativeLibraryDir.isNullOrBlank()) {
            return BootstrapStageOutput(
                context = input.copy(nativeLibraryDir = null),
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.NATIVE_LIBS,
                    message = "Instance native library directory not present",
                    evidence = evidence
                ).copy(durationMs = durationMs)
            )
        }

        return BootstrapStageOutput(
            context = input.copy(nativeLibraryDir = nativeLibraryDir),
            result = BootstrapResult.success(
                stage = RuntimeStage.NATIVE_LIBS,
                message = "Native library directory resolved: $nativeLibraryDir",
                evidence = evidence,
                durationMs = durationMs
            )
        )
    }

    private fun nativeEvidence(
        resolution: NativeLibraryResolution,
        redirectInstallResult: NativePrivatePathRedirectInstallResult
    ): List<BootstrapEvidence> {
        val evidence = mutableListOf(
            BootstrapEvidence("nativeLibraryDir", resolution.nativeLibraryDir.orEmpty()),
            BootstrapEvidence("nativeLibraryRoot", resolution.nativeLibraryRoot.orEmpty()),
            BootstrapEvidence("nativeLibrarySource", resolution.source),
            BootstrapEvidence("nativeLibrariesExtraction", resolution.extractionStatus),
            BootstrapEvidence("nativeLibrarySelectedAbi", resolution.selectedAbi.orEmpty()),
            BootstrapEvidence("nativeLibraryAvailableAbis", resolution.availableAbis.joinToString(",")),
            BootstrapEvidence("nativeLibraryCount", resolution.libraries.size.toString()),
            BootstrapEvidence("nativeLibrariesCopiedCount", resolution.copiedCount.toString()),
            BootstrapEvidence("nativeLibraries", resolution.libraries.joinToString(","))
        )
        resolution.reason?.let { evidence += BootstrapEvidence("reason", it) }
        evidence += redirectInstallResult.evidence()
        return evidence
    }
}
