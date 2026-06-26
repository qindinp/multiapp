package com.multiapp.core.loader

/**
 * Outer orchestrator for bootstrap stages.
 *
 * Owns the [EvidenceSink] and a wall-clock.  All stage evidence in
 * LoaderFactory should eventually flow through [record] or [runStage]
 * instead of directly calling `bootstrapRecorder`.
 *
 * This first release does **not** move LoaderFactory behaviour — it only
 * provides the API and test harness so the next patch can migrate the
 * recorder calls.
 */
class RuntimeBootstrap(
    val plan: RuntimeBootstrapPlan,
    private val evidenceSink: EvidenceSink,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** Start a new bootstrap session. */
    fun begin(context: RuntimeBootstrapContext): RuntimeBootstrapSession =
        RuntimeBootstrapSession(
            context = context,
            plan = plan,
            evidenceSink = evidenceSink,
            currentStage = null,
            clock = clock
        )

    /** Emit an arbitrary [result] to the evidence sink. */
    fun record(result: BootstrapResult): BootstrapResult = evidenceSink.emit(result)

    /**
     * Execute [block] inside the given bootstrap [stage].
     *
     * On success the block's return value is forwarded and a SUCCESS result
     * is emitted.  On exception a FAILED result is emitted and the original
     * exception is rethrown.
     */
    fun <T> runStage(
        stage: RuntimeStage,
        message: String,
        evidence: List<BootstrapEvidence> = emptyList(),
        block: () -> T
    ): T {
        val startMs = clock()
        return try {
            val value = block()
            val durationMs = clock() - startMs
            evidenceSink.emit(BootstrapResult.success(stage, message, evidence, durationMs))
            value
        } catch (e: Throwable) {
            val durationMs = clock() - startMs
            evidenceSink.emit(
                BootstrapResult.failed(stage, message, error = e, evidence = evidence, durationMs = durationMs)
            )
            throw e
        }
    }
}

/**
 * Represents a single bootstrap session — an immutable snapshot of the
 * context in which the orchestrator is running, plus the current stage
 * and evidence sink.
 */
data class RuntimeBootstrapSession(
    val context: RuntimeBootstrapContext,
    val plan: RuntimeBootstrapPlan,
    private val evidenceSink: EvidenceSink,
    val currentStage: RuntimeStage? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** Return a new session with [stage] as the current stage. */
    fun enter(stage: RuntimeStage): RuntimeBootstrapSession =
        copy(currentStage = stage)

    /** Emit a SUCCESS result for the [currentStage]. */
    fun success(
        message: String = "",
        evidence: List<BootstrapEvidence> = emptyList()
    ): BootstrapResult {
        val stage = requireNotNull(currentStage) { "Cannot emit success without a current stage" }
        return evidenceSink.emit(BootstrapResult.success(stage, message, evidence, clock()))
    }

    /** Emit a FAILED result for the [currentStage]. */
    fun failed(
        message: String,
        evidence: List<BootstrapEvidence> = emptyList(),
        error: Throwable? = null,
        rollbackNote: String? = null
    ): BootstrapResult {
        val stage = requireNotNull(currentStage) { "Cannot emit failed without a current stage" }
        return evidenceSink.emit(
            BootstrapResult.failed(stage, message, error, evidence, clock(), rollbackNote)
        )
    }

    /** Emit a DEGRADED result for the [currentStage]. */
    fun degraded(
        message: String,
        evidence: List<BootstrapEvidence> = emptyList(),
        error: Throwable? = null
    ): BootstrapResult {
        val stage = requireNotNull(currentStage) { "Cannot emit degraded without a current stage" }
        return evidenceSink.emit(
            BootstrapResult.degraded(stage, message, error, evidence, clock())
        )
    }

    /** Emit a SKIPPED result for the [currentStage]. */
    fun skipped(
        message: String = "",
        evidence: List<BootstrapEvidence> = emptyList()
    ): BootstrapResult {
        val stage = requireNotNull(currentStage) { "Cannot emit skipped without a current stage" }
        return evidenceSink.emit(BootstrapResult.skipped(stage, message, evidence))
    }
}
