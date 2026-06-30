package com.multiapp.core.loader

import java.io.File

class OriginApkStage(
    private val clock: () -> Long = System::currentTimeMillis,
    private val originApkExists: (String) -> Boolean = { path -> File(path).exists() }
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val installRecord = input.installRecord
        if (installRecord == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.ORIGIN_APK,
                    message = "Install record is required before resolving origin APK",
                    durationMs = clock() - startMs
                )
            )
        }

        val originApkPath = installRecord.originApkPath
        val apkExists = runCatching {
            originApkExists(originApkPath)
        }.getOrDefault(false)
        val durationMs = clock() - startMs

        if (!apkExists) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.ORIGIN_APK,
                    message = "Origin APK not found: $originApkPath",
                    durationMs = durationMs
                )
            )
        }

        return BootstrapStageOutput(
            context = input.copy(originApkPath = originApkPath),
            result = BootstrapResult.success(
                stage = RuntimeStage.ORIGIN_APK,
                message = "Origin APK resolved: $originApkPath",
                evidence = listOf(
                    BootstrapEvidence("originApkPath", originApkPath)
                ),
                durationMs = durationMs
            )
        )
    }
}
