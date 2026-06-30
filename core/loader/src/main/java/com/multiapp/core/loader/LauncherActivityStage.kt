package com.multiapp.core.loader

import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.VirtualPackageResolver

class LauncherActivityStage(
    private val packageResolver: VirtualPackageResolver?,
    private val launcherActivityResolver: (InstallRecord) -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val installRecord = input.installRecord ?: return failed(
            input = input,
            startMs = startMs,
            message = "Install record is required before launcher Activity resolution"
        )
        val originApkPath = input.originApkPath ?: return failed(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before launcher Activity resolution"
        )
        val guestClassLoader = input.guestClassLoader ?: return failed(
            input = input,
            startMs = startMs,
            message = "Guest ClassLoader is required before launcher Activity resolution"
        )

        val launcherResolution = resolveLauncherActivity(installRecord, originApkPath, input)
        val launcherClassName = launcherResolution.className
        if (launcherClassName == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.LAUNCHER_ACTIVITY,
                    message = "No launcher Activity resolved from manifest or InstallRecord"
                ).copy(durationMs = clock() - startMs),
                terminalFailure = false
            )
        }

        val loadable = runCatching {
            guestClassLoader.loadClass(launcherClassName)
        }.isSuccess

        if (!loadable) {
            return BootstrapStageOutput(
                context = input.copy(launcherActivityClassName = null),
                result = BootstrapResult.failed(
                    stage = RuntimeStage.LAUNCHER_ACTIVITY,
                    message = "Launcher Activity class not loadable: $launcherClassName",
                    evidence = launcherEvidence(launcherClassName, launcherResolution.source, loadable = false),
                    durationMs = clock() - startMs
                ),
                terminalFailure = false
            )
        }

        return BootstrapStageOutput(
            context = input.copy(launcherActivityClassName = launcherClassName),
            result = BootstrapResult.success(
                stage = RuntimeStage.LAUNCHER_ACTIVITY,
                message = "Launcher Activity resolved: $launcherClassName",
                evidence = launcherEvidence(launcherClassName, launcherResolution.source, loadable = true),
                durationMs = clock() - startMs
            ),
            terminalFailure = false
        )
    }

    private fun resolveLauncherActivity(
        installRecord: InstallRecord,
        originApkPath: String,
        input: BootstrapStageInput
    ): LauncherResolution {
        val resolvedFromManifest = input.resolvedPackage?.launcherActivityName ?: runCatching {
            packageResolver?.resolve(originApkPath)?.launcherActivityName
        }.getOrNull()
        if (!resolvedFromManifest.isNullOrBlank()) {
            return LauncherResolution(resolvedFromManifest, VIRTUAL_PACKAGE_RESOLVER)
        }

        val resolvedFromInstallRecord = runCatching {
            launcherActivityResolver(installRecord)
        }.getOrNull()
        return LauncherResolution(resolvedFromInstallRecord, INSTALL_RECORD)
    }

    private fun failed(
        input: BootstrapStageInput,
        startMs: Long,
        message: String
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.LAUNCHER_ACTIVITY,
            message = message,
            durationMs = clock() - startMs
        ),
        terminalFailure = false
    )

    private fun launcherEvidence(
        launcherClassName: String,
        source: String,
        loadable: Boolean
    ): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("launcherActivityClass", launcherClassName),
        BootstrapEvidence("resolver", source),
        BootstrapEvidence("loadable", loadable.toString())
    )

    private data class LauncherResolution(
        val className: String?,
        val source: String
    )

    private companion object {
        private const val VIRTUAL_PACKAGE_RESOLVER = "VirtualPackageResolver"
        private const val INSTALL_RECORD = "InstallRecord"
    }
}
