package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EvidenceSinkTest {

    private fun ev(key: String) = BootstrapEvidence(key, "true")

    @Test
    fun `NoopEvidenceSink returns result unchanged`() {
        val result = BootstrapResult.success(RuntimeStage.CONFIG, "test")
        val returned = NoopEvidenceSink.emit(result)
        assertSame(result, returned)
    }

    @Test
    fun `NoopEvidenceSink returns failed result unchanged`() {
        val result = BootstrapResult.failed(
            RuntimeStage.NATIVE_LIBS,
            "crash",
            error = IllegalStateException("boom")
        )
        val returned = NoopEvidenceSink.emit(result)
        assertSame(result, returned)
    }

    @Test
    fun `RecorderEvidenceSink delegates to recorder`() {
        val emitted = mutableListOf<BootstrapResult>()
        val recorder = RuntimeBootstrapRecorder { emitted += it }
        val sink = RecorderEvidenceSink(recorder)

        val result = BootstrapResult.success(RuntimeStage.CONFIG, "config read")
        val returned = sink.emit(result)

        assertSame(result, returned)
        assertEquals(1, emitted.size)
        assertSame(result, emitted[0])
    }

    @Test
    fun `RecorderEvidenceSink preserves failed result with error`() {
        val emitted = mutableListOf<BootstrapResult>()
        val recorder = RuntimeBootstrapRecorder { emitted += it }
        val sink = RecorderEvidenceSink(recorder)

        val result = BootstrapResult.failed(
            RuntimeStage.APPLICATION,
            "app crash",
            error = RuntimeException("kaboom")
        )
        val returned = sink.emit(result)

        assertSame(result, returned)
        assertEquals(1, emitted.size)
        assertEquals(BootstrapStatus.FAILED, emitted[0].status)
        assertEquals(RuntimeStage.APPLICATION, emitted[0].stage)
        assertEquals("kaboom", emitted[0].errorMessage)
    }

    @Test
    fun `EvidenceSink success helper builds correct result`() {
        val result = EvidenceSink.success(
            RuntimeStage.CLASS_LOADER,
            "swapped",
            evidence = listOf(BootstrapEvidence("key", "val"))
        )
        assertEquals(BootstrapStatus.SUCCESS, result.status)
        assertEquals(RuntimeStage.CLASS_LOADER, result.stage)
        assertEquals("swapped", result.message)
        assertEquals(1, result.evidence.size)
        assertEquals(0L, result.durationMs)
    }

    @Test
    fun `EvidenceSink failed helper captures error`() {
        val error = IllegalStateException("bad state")
        val result = EvidenceSink.failed(
            RuntimeStage.NATIVE_LIBS,
            "native load failed",
            error = error,
            durationMs = 42,
            rollbackNote = "fallback"
        )
        assertEquals(BootstrapStatus.FAILED, result.status)
        assertEquals(RuntimeStage.NATIVE_LIBS, result.stage)
        assertEquals("native load failed", result.message)
        assertEquals(IllegalStateException::class.java.name, result.errorClass)
        assertEquals("bad state", result.errorMessage)
        assertEquals(42L, result.durationMs)
        assertEquals("fallback", result.rollbackNote)
    }

    @Test
    fun `EvidenceSink skipped helper builds correct result`() {
        val result = EvidenceSink.skipped(RuntimeStage.RESOURCES, "no resources")
        assertEquals(BootstrapStatus.SKIPPED, result.status)
        assertEquals(RuntimeStage.RESOURCES, result.stage)
        assertEquals("no resources", result.message)
    }

    @Test
    fun `EvidenceSink degraded helper captures error`() {
        val error = UnsatisfiedLinkError("symbol missing")
        val result = EvidenceSink.degraded(
            RuntimeStage.NATIVE_LIBS,
            "partial load",
            error = error,
            durationMs = 100
        )
        assertEquals(BootstrapStatus.DEGRADED, result.status)
        assertEquals("partial load", result.message)
        assertEquals(UnsatisfiedLinkError::class.java.name, result.errorClass)
        assertEquals(100L, result.durationMs)
    }

    @Test
    fun `BootstrapResult helpers match recorder semantics`() {
        val success = BootstrapResult.success(RuntimeStage.CONFIG, "loaded", listOf(ev("config")), 1L)
        val failed = BootstrapResult.failed(RuntimeStage.ORIGIN_APK, "missing", evidence = listOf(ev("origin")), durationMs = 2L)
        val skipped = BootstrapResult.skipped(RuntimeStage.RESOURCES, "not needed", listOf(ev("resource")))
        val degraded = BootstrapResult.degraded(RuntimeStage.NATIVE_LIBS, "fallback", evidence = listOf(ev("native")), durationMs = 4L)

        assertEquals(BootstrapStatus.SUCCESS, success.status)
        assertEquals(BootstrapStatus.FAILED, failed.status)
        assertEquals(BootstrapStatus.SKIPPED, skipped.status)
        assertEquals(BootstrapStatus.DEGRADED, degraded.status)
    }
}
