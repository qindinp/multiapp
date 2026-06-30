package com.multiapp.core.loader

import com.multiapp.core.model.installer.InstallRecordStore

class InstallRecordStage(
    private val installRecordStore: InstallRecordStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val instance = input.instance
        if (instance == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.PACKAGE_METADATA,
                    message = "Instance is required before loading install record",
                    durationMs = clock() - startMs
                )
            )
        }

        val installRecord = runCatching {
            installRecordStore.load(instance.originPackageName)
        }.getOrNull()
        val durationMs = clock() - startMs

        if (installRecord == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.PACKAGE_METADATA,
                    message = "Install record not found: ${instance.originPackageName}",
                    durationMs = durationMs
                )
            )
        }

        return BootstrapStageOutput(
            context = input.copy(installRecord = installRecord),
            result = BootstrapResult.success(
                stage = RuntimeStage.PACKAGE_METADATA,
                message = "Install record loaded: ${installRecord.packageName}",
                evidence = listOf(
                    BootstrapEvidence("packageName", installRecord.packageName),
                    BootstrapEvidence("versionName", installRecord.versionName)
                ),
                durationMs = durationMs
            )
        )
    }
}
