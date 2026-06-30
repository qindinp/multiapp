package com.multiapp.core.loader

import java.io.File

class NativeLibrariesStage(
    private val nativeLibraryDirResolver: (String?) -> String? = ::resolveInstanceLibDir,
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

        val nativeLibraryDir = nativeLibraryDirResolver(instance.dataRoot)
        val durationMs = clock() - startMs
        if (nativeLibraryDir.isNullOrBlank()) {
            return BootstrapStageOutput(
                context = input.copy(nativeLibraryDir = null),
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.NATIVE_LIBS,
                    message = "Instance native library directory not present",
                    evidence = nativeEvidence(
                        nativeLibraryDir = "",
                        reason = "instance lib dir not present"
                    )
                ).copy(durationMs = durationMs)
            )
        }

        return BootstrapStageOutput(
            context = input.copy(nativeLibraryDir = nativeLibraryDir),
            result = BootstrapResult.success(
                stage = RuntimeStage.NATIVE_LIBS,
                message = "Native library directory resolved: $nativeLibraryDir",
                evidence = nativeEvidence(nativeLibraryDir = nativeLibraryDir),
                durationMs = durationMs
            )
        )
    }

    private fun nativeEvidence(
        nativeLibraryDir: String,
        reason: String? = null
    ): List<BootstrapEvidence> {
        val evidence = listOf(
            BootstrapEvidence("nativeLibraryDir", nativeLibraryDir),
            BootstrapEvidence("nativeLibrarySource", "INSTANCE_DATA_ROOT_LIB"),
            BootstrapEvidence("nativeLibrariesExtraction", "DEFERRED")
        )
        return if (reason == null) {
            evidence
        } else {
            evidence + BootstrapEvidence("reason", reason)
        }
    }

    companion object {
        private fun resolveInstanceLibDir(dataRoot: String?): String? {
            if (dataRoot.isNullOrBlank()) return null
            val libDir = File(dataRoot, "lib")
            return libDir.takeIf { it.isDirectory }?.absolutePath
        }
    }
}
