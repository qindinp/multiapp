package com.multiapp.core.loader

import java.io.File

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
        if (input.installRecord?.isolatedSplits == true) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.CLASS_LOADER,
                    message = "Isolated split loading is unsupported by hosted ClassLoader baseline",
                    evidence = listOf(
                        BootstrapEvidence("isolatedSplits", "true"),
                        BootstrapEvidence("classLoaderSplitSupport", "UNSUPPORTED")
                    ),
                    durationMs = clock() - startMs
                )
            )
        }
        val apkPaths = listOf(originApkPath) + input.installRecord?.splitApkPaths.orEmpty()
        val dexPath = apkPaths.distinct().joinToString(File.pathSeparator)

        return runCatching {
            classLoaderFactory(dexPath, input.nativeLibraryDir)
        }.fold(
            onSuccess = { guestClassLoader ->
                BootstrapStageOutput(
                    context = input.copy(guestClassLoader = guestClassLoader),
                    result = BootstrapResult.success(
                        stage = RuntimeStage.CLASS_LOADER,
                        message = "Guest ClassLoader created",
                        evidence = listOf(
                            BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name),
                            BootstrapEvidence("classLoaderDexPath", dexPath),
                            BootstrapEvidence("classLoaderApkPathCount", apkPaths.distinct().size.toString()),
                            BootstrapEvidence(
                                "classLoaderSplitSourceDirs",
                                input.installRecord?.splitApkPaths.orEmpty().joinToString(",")
                            ),
                            BootstrapEvidence("nativeLibraryDir", input.nativeLibraryDir.orEmpty()),
                            BootstrapEvidence(
                                "nativeLibrarySearchPath",
                                NativeLibraryPaths.buildClassLoaderSearchPath(
                                    apkPath = originApkPath,
                                    splitApkPaths = input.installRecord?.splitApkPaths.orEmpty(),
                                    nativeLibraryDir = input.nativeLibraryDir
                                ).orEmpty()
                            )
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
