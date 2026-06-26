package com.multiapp.core.loader

class RuntimeBootstrapRecorder(
    private val sink: (BootstrapResult) -> Unit = {}
) {
    private val records = mutableListOf<BootstrapResult>()

    @Synchronized
    fun record(result: BootstrapResult): BootstrapResult {
        records += result
        sink(result)
        return result
    }

    fun success(
        stage: RuntimeStage,
        message: String = "",
        evidence: List<BootstrapEvidence> = emptyList(),
        durationMs: Long = 0
    ): BootstrapResult = record(
        BootstrapResult.success(stage, message, evidence, durationMs)
    )

    fun failed(
        stage: RuntimeStage,
        message: String,
        error: Throwable? = null,
        evidence: List<BootstrapEvidence> = emptyList(),
        durationMs: Long = 0,
        rollbackNote: String? = null
    ): BootstrapResult = record(
        BootstrapResult.failed(stage, message, error, evidence, durationMs, rollbackNote)
    )

    fun skipped(
        stage: RuntimeStage,
        message: String = "",
        evidence: List<BootstrapEvidence> = emptyList()
    ): BootstrapResult = record(
        BootstrapResult.skipped(stage, message, evidence)
    )

    fun degraded(
        stage: RuntimeStage,
        message: String,
        error: Throwable? = null,
        evidence: List<BootstrapEvidence> = emptyList(),
        durationMs: Long = 0
    ): BootstrapResult = record(
        BootstrapResult.degraded(stage, message, error, evidence, durationMs)
    )

    @Synchronized
    fun snapshot(): List<BootstrapResult> = records.toList()

    @Synchronized
    fun reset() {
        records.clear()
    }

    fun latestFailure(): BootstrapResult? =
        snapshot().lastOrNull { it.status == BootstrapStatus.FAILED }

    fun summary(): String =
        snapshot().joinToString(separator = "\n") { result ->
            buildString {
                append(result.stage.name)
                append(":")
                append(result.status.name)
                if (result.message.isNotBlank()) {
                    append(" ")
                    append(result.message)
                }
                if (result.errorClass != null) {
                    append(" error=")
                    append(result.errorClass.substringAfterLast('.'))
                    result.errorMessage?.let {
                        append(":")
                        append(it)
                    }
                }
            }
        }
}
