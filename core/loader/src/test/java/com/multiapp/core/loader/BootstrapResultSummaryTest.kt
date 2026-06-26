package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootstrapResultSummaryTest {

    @Test
    fun `toSummary from empty list returns empty summary`() {
        val summary = emptyList<BootstrapResult>().toSummary()
        assertEquals(0, summary.totalTimeMs)
        assertTrue(summary.stageResults.isEmpty())
        assertEquals(BootstrapStatus.SUCCESS, summary.overallStatus)
        assertNull(summary.failedStage)
        assertNull(summary.failureReason)
    }

    @Test
    fun `toSummary from all success results`() {
        val results = listOf(
            BootstrapResult.success(RuntimeStage.CONFIG, "ok", durationMs = 10),
            BootstrapResult.success(RuntimeStage.GUEST_CONTEXT, "ok", durationMs = 20),
            BootstrapResult.success(RuntimeStage.ORIGIN_APK, "ok", durationMs = 30)
        )
        val summary = results.toSummary()
        assertEquals(60L, summary.totalTimeMs)
        assertEquals(3, summary.stageResults.size)
        assertEquals(BootstrapStatus.SUCCESS, summary.overallStatus)
        assertNull(summary.failedStage)
        assertNull(summary.failureReason)
    }

    @Test
    fun `toSummary with one failure identifies failed stage`() {
        val results = listOf(
            BootstrapResult.success(RuntimeStage.CONFIG, "ok", durationMs = 10),
            BootstrapResult.failed(
                RuntimeStage.ORIGIN_APK,
                "origin missing",
                error = IllegalStateException("file not found"),
                durationMs = 5
            )
        )
        val summary = results.toSummary()
        assertEquals(15L, summary.totalTimeMs)
        assertEquals(BootstrapStatus.FAILED, summary.overallStatus)
        assertEquals(RuntimeStage.ORIGIN_APK, summary.failedStage)
        assertEquals("origin missing", summary.failureReason)
    }

    @Test
    fun `toSummary with degraded result`() {
        val results = listOf(
            BootstrapResult.success(RuntimeStage.CONFIG, "ok", durationMs = 10),
            BootstrapResult.degraded(RuntimeStage.RESOURCES, "fallback", durationMs = 5)
        )
        val summary = results.toSummary()
        assertEquals(BootstrapStatus.DEGRADED, summary.overallStatus)
        assertNull(summary.failedStage)
    }

    @Test
    fun `totalTimeMs sums all durations`() {
        val results = listOf(
            BootstrapResult.success(RuntimeStage.CONFIG, "ok", durationMs = 100),
            BootstrapResult.success(RuntimeStage.GUEST_CONTEXT, "ok", durationMs = 200),
            BootstrapResult.success(RuntimeStage.PACKAGE_METADATA, "ok", durationMs = 300)
        )
        val summary = results.toSummary()
        assertEquals(600L, summary.totalTimeMs)
    }

    @Test
    fun `stage result summary counts evidence`() {
        val result = BootstrapResult.success(
            stage = RuntimeStage.CONFIG,
            message = "ok",
            evidence = listOf(
                BootstrapEvidence("k1", "v1"),
                BootstrapEvidence("k2", "v2"),
                BootstrapEvidence("k3", "v3")
            ),
            durationMs = 10
        )
        val summary = result.toStageResultSummary()
        assertEquals(3, summary.evidenceCount)
    }

    @Test
    fun `stage result summary captures error details for failed result`() {
        val result = BootstrapResult.failed(
            stage = RuntimeStage.NATIVE_LIBS,
            message = "hook failed",
            error = UnsatisfiedLinkError("libnotfound.so"),
            durationMs = 5
        )
        val summary = result.toStageResultSummary()
        assertEquals("hook failed", summary.errorSummary)
        assertEquals(BootstrapStatus.FAILED, summary.status)
    }

    @Test
    fun `stage result summary has null error for success`() {
        val result = BootstrapResult.success(RuntimeStage.CONFIG, "ok", durationMs = 10)
        val summary = result.toStageResultSummary()
        assertNull(summary.errorSummary)
    }
}
