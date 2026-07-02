package com.multiapp.core.loader

import android.content.Context
import android.os.Process

class VirtualPackageManagerProxyStage(
    private val hostContext: Context?,
    private val installer: VirtualPackageManagerGlobalInstallAction = VirtualPackageManagerGlobalInstaller(),
    private val runtimeUidProvider: () -> Int = { runCatching { Process.myUid() }.getOrDefault(0) },
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val snapshot = input.packageSnapshot
        if (snapshot == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                    message = "Package snapshot is required before package manager proxy install",
                    durationMs = clock() - startMs
                )
            )
        }

        val runtimeUid = runtimeUidProvider()
        val installResult = installer.install(hostContext, snapshot, runtimeUid)
        val durationMs = clock() - startMs
        val result = when (installResult.status) {
            VirtualPackageManagerGlobalInstallStatus.INSTALLED -> BootstrapResult.success(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                message = "Global package manager proxy install attempted",
                evidence = installResult.toEvidence(),
                durationMs = durationMs
            )
            VirtualPackageManagerGlobalInstallStatus.DEGRADED -> BootstrapResult.degraded(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                message = "Global package manager proxy degraded; local wrapper fallback remains available",
                evidence = installResult.toEvidence(),
                durationMs = durationMs
            )
            VirtualPackageManagerGlobalInstallStatus.SKIPPED -> BootstrapResult.skipped(
                stage = RuntimeStage.PACKAGE_MANAGER_PROXY,
                message = "Global package manager proxy skipped",
                evidence = installResult.toEvidence()
            ).copy(durationMs = durationMs)
        }

        return BootstrapStageOutput(
            context = input,
            result = result,
            terminalFailure = false
        )
    }
}
