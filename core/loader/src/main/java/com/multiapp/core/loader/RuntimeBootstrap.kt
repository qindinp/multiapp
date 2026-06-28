package com.multiapp.core.loader

enum class RuntimeStage(val order: Int) {
    CONFIG(0),
    ORIGIN_APK(10),
    PACKAGE_METADATA(20),
    NATIVE_LIBS(30),
    RESOURCES(40),
    CLASS_LOADER(50),
    GUEST_CONTEXT(60),
    APPLICATION(70),
    LAUNCHER_ACTIVITY(80);

    companion object {
        fun ordered(): List<RuntimeStage> = values().sortedBy { it.order }
    }
}

enum class StageReversibility {
    /** Can be fully rolled back to pre-stage state. */
    REVERSIBLE,
    /** Cannot be rolled back; failure means abandoning the bootstrap. */
    IRREVERSIBLE,
    /** Some artifacts can be cleaned up, but global state may have changed. */
    PARTIALLY_REVERSIBLE;

    companion object {
        /** Default reversibility for each [RuntimeStage]. */
        fun defaultFor(stage: RuntimeStage): StageReversibility = when (stage) {
            RuntimeStage.CONFIG -> REVERSIBLE
            RuntimeStage.GUEST_CONTEXT -> REVERSIBLE
            RuntimeStage.PACKAGE_METADATA -> REVERSIBLE
            RuntimeStage.ORIGIN_APK -> IRREVERSIBLE
            RuntimeStage.NATIVE_LIBS -> IRREVERSIBLE
            RuntimeStage.RESOURCES -> PARTIALLY_REVERSIBLE
            RuntimeStage.CLASS_LOADER -> IRREVERSIBLE
            RuntimeStage.APPLICATION -> IRREVERSIBLE
            RuntimeStage.LAUNCHER_ACTIVITY -> IRREVERSIBLE
        }
    }
}

enum class BootstrapStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
    DEGRADED
}

data class BootstrapEvidence(
    val key: String,
    val value: String,
    val source: String = ""
) {
    init {
        require(key.isNotBlank()) { "key must not be blank" }
    }
}

data class BootstrapResult(
    val stage: RuntimeStage,
    val status: BootstrapStatus,
    val message: String = "",
    val evidence: List<BootstrapEvidence> = emptyList(),
    val durationMs: Long = 0,
    val rollbackNote: String? = null,
    val errorClass: String? = null,
    val errorMessage: String? = null
) {
    init {
        require(durationMs >= 0) { "durationMs must be non-negative" }
    }

    val isSuccessful: Boolean
        get() = status == BootstrapStatus.SUCCESS

    val isTerminalFailure: Boolean
        get() = status == BootstrapStatus.FAILED

    companion object {
        fun success(
            stage: RuntimeStage,
            message: String = "",
            evidence: List<BootstrapEvidence> = emptyList(),
            durationMs: Long = 0
        ): BootstrapResult = BootstrapResult(
            stage = stage,
            status = BootstrapStatus.SUCCESS,
            message = message,
            evidence = evidence,
            durationMs = durationMs
        )

        fun failed(
            stage: RuntimeStage,
            message: String,
            error: Throwable? = null,
            evidence: List<BootstrapEvidence> = emptyList(),
            durationMs: Long = 0,
            rollbackNote: String? = null
        ): BootstrapResult = BootstrapResult(
            stage = stage,
            status = BootstrapStatus.FAILED,
            message = message,
            evidence = evidence,
            durationMs = durationMs,
            rollbackNote = rollbackNote,
            errorClass = error?.javaClass?.name,
            errorMessage = error?.message
        )

        fun skipped(
            stage: RuntimeStage,
            message: String = "",
            evidence: List<BootstrapEvidence> = emptyList()
        ): BootstrapResult = BootstrapResult(
            stage = stage,
            status = BootstrapStatus.SKIPPED,
            message = message,
            evidence = evidence
        )

        fun degraded(
            stage: RuntimeStage,
            message: String,
            error: Throwable? = null,
            evidence: List<BootstrapEvidence> = emptyList(),
            durationMs: Long = 0
        ): BootstrapResult = BootstrapResult(
            stage = stage,
            status = BootstrapStatus.DEGRADED,
            message = message,
            evidence = evidence,
            durationMs = durationMs,
            errorClass = error?.javaClass?.name,
            errorMessage = error?.message
        )
    }
}

/** Convert a single [BootstrapResult] to a [StageResultSummary]. */
fun BootstrapResult.toStageResultSummary(): StageResultSummary = StageResultSummary(
    stage = stage,
    status = status,
    elapsedMs = durationMs,
    reversibility = StageReversibility.defaultFor(stage),
    evidenceCount = evidence.size,
    errorSummary = if (status == BootstrapStatus.FAILED || status == BootstrapStatus.DEGRADED) {
        message
    } else {
        null
    }
)
