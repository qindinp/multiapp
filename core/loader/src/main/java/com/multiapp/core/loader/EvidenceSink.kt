package com.multiapp.core.loader

/**
 * Abstraction for emitting [BootstrapResult] events.
 *
 * Implementations decide where results go (recorder, log, noop, etc.).
 * The interface is intentionally minimal: a single [emit] method that
 * returns the same result it received, enabling decorator chaining.
 */
interface EvidenceSink {

    /** Emit [result] to the underlying store and return it unchanged. */
    fun emit(result: BootstrapResult): BootstrapResult

    companion object {
        // ── Convenience factory helpers ────────────────────────────────

        fun success(
            stage: RuntimeStage,
            message: String = "",
            evidence: List<BootstrapEvidence> = emptyList(),
            durationMs: Long = 0
        ): BootstrapResult = BootstrapResult.success(stage, message, evidence, durationMs)

        fun failed(
            stage: RuntimeStage,
            message: String,
            error: Throwable? = null,
            evidence: List<BootstrapEvidence> = emptyList(),
            durationMs: Long = 0,
            rollbackNote: String? = null
        ): BootstrapResult = BootstrapResult.failed(stage, message, error, evidence, durationMs, rollbackNote)

        fun skipped(
            stage: RuntimeStage,
            message: String = "",
            evidence: List<BootstrapEvidence> = emptyList()
        ): BootstrapResult = BootstrapResult.skipped(stage, message, evidence)

        fun degraded(
            stage: RuntimeStage,
            message: String,
            error: Throwable? = null,
            evidence: List<BootstrapEvidence> = emptyList(),
            durationMs: Long = 0
        ): BootstrapResult = BootstrapResult.degraded(stage, message, error, evidence, durationMs)
    }
}

/**
 * [EvidenceSink] backed by [RuntimeBootstrapRecorder].
 * Every emitted result is recorded and forwarded to the recorder's sink callback.
 */
class RecorderEvidenceSink(
    private val recorder: RuntimeBootstrapRecorder
) : EvidenceSink {

    override fun emit(result: BootstrapResult): BootstrapResult =
        recorder.record(result)
}

/**
 * [EvidenceSink] that returns the result without side-effects.
 * Useful in tests or when bootstrap diagnostics are not needed.
 */
object NoopEvidenceSink : EvidenceSink {

    override fun emit(result: BootstrapResult): BootstrapResult = result
}
