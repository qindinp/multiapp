package com.multiapp.core.loader

/**
 * Per-stage summary derived from a single [BootstrapResult].
 */
data class StageResultSummary(
    val stage: RuntimeStage,
    val status: BootstrapStatus,
    val elapsedMs: Long,
    val reversibility: StageReversibility,
    val evidenceCount: Int,
    val errorSummary: String?
)

/**
 * Aggregated summary of an entire bootstrap run.
 */
data class BootstrapSummary(
    val totalTimeMs: Long,
    val stageResults: List<StageResultSummary>,
    val overallStatus: BootstrapStatus,
    val failedStage: RuntimeStage?,
    val failureReason: String?
)

/**
 * Aggregate a list of [BootstrapResult]s into a human-readable [BootstrapSummary].
 *
 * Overall status precedence: FAILED > DEGRADED > SUCCESS.
 * If multiple failures exist, the *last* failed stage is reported.
 */
fun List<BootstrapResult>.toSummary(): BootstrapSummary {
    val stageResults = map { it.toStageResultSummary() }
    val totalTimeMs = sumOf { it.durationMs }

    val lastFailure = lastOrNull { it.status == BootstrapStatus.FAILED }
    val hasDegraded = any { it.status == BootstrapStatus.DEGRADED }

    val overallStatus = when {
        lastFailure != null -> BootstrapStatus.FAILED
        hasDegraded -> BootstrapStatus.DEGRADED
        else -> BootstrapStatus.SUCCESS
    }

    return BootstrapSummary(
        totalTimeMs = totalTimeMs,
        stageResults = stageResults,
        overallStatus = overallStatus,
        failedStage = lastFailure?.stage,
        failureReason = lastFailure?.message
    )
}
