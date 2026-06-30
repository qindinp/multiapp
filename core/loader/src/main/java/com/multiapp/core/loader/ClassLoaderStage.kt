package com.multiapp.core.loader

class ClassLoaderStage(
    private val classLoaderFactory: (apkPath: String, nativeLibDir: String?) -> ClassLoader,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(
        input: BootstrapStageInput,
        additionalEvidence: List<BootstrapEvidence> = emptyList()
    ): BootstrapStageOutput {
        val startMs = clock()
        val originApkPath = input.originApkPath ?: return failed(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before ClassLoader creation"
        )

        return runCatching {
            classLoaderFactory(originApkPath, input.nativeLibraryDir)
        }.fold(
            onSuccess = { guestClassLoader ->
                BootstrapStageOutput(
                    context = input.copy(guestClassLoader = guestClassLoader),
                    result = BootstrapResult.success(
                        stage = RuntimeStage.CLASS_LOADER,
                        message = "Guest ClassLoader created",
                        evidence = listOf(
                            BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name),
                            BootstrapEvidence("nativeLibraryDir", input.nativeLibraryDir.orEmpty())
                        ) + additionalEvidence,
                        durationMs = clock() - startMs
                    )
                )
            },
            onFailure = { error ->
                BootstrapStageOutput(
                    context = input,
                    result = BootstrapResult.failed(
                        stage = RuntimeStage.CLASS_LOADER,
                        message = "ClassLoader creation failed: ${error.message}",
                        error = error,
                        durationMs = clock() - startMs
                    )
                )
            }
        )
    }

    private fun failed(
        input: BootstrapStageInput,
        startMs: Long,
        message: String
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.CLASS_LOADER,
            message = message,
            durationMs = clock() - startMs
        )
    )
}
