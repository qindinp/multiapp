package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeBootstrapRecorderTest {

    @Test
    fun `recorder stores results and sends them to sink`() {
        val emitted = mutableListOf<BootstrapResult>()
        val recorder = RuntimeBootstrapRecorder { emitted += it }

        recorder.success(
            stage = RuntimeStage.CONFIG,
            message = "config read",
            evidence = listOf(BootstrapEvidence("stub", "base.apk"))
        )
        recorder.degraded(
            stage = RuntimeStage.NATIVE_LIBS,
            message = "native hook unavailable",
            error = IllegalStateException("no symbol")
        )

        assertEquals(2, recorder.snapshot().size)
        assertEquals(2, emitted.size)
        assertNull(recorder.latestFailure())
        assertTrue(recorder.summary().contains("CONFIG:SUCCESS config read"))
        assertTrue(recorder.summary().contains("NATIVE_LIBS:DEGRADED native hook unavailable"))
    }

    @Test
    fun `latest failure returns last failed stage`() {
        val recorder = RuntimeBootstrapRecorder()

        recorder.failed(
            stage = RuntimeStage.ORIGIN_APK,
            message = "origin missing",
            error = IllegalStateException("missing")
        )
        recorder.success(RuntimeStage.CLASS_LOADER, "swapped")
        recorder.failed(
            stage = RuntimeStage.APPLICATION,
            message = "application failed",
            error = UnsatisfiedLinkError("interface20")
        )

        val failure = recorder.latestFailure()

        assertEquals(RuntimeStage.APPLICATION, failure?.stage)
        assertEquals(UnsatisfiedLinkError::class.java.name, failure?.errorClass)
        assertTrue(recorder.summary().contains("APPLICATION:FAILED application failed"))
    }

    @Test
    fun `reset clears recorded stages`() {
        val recorder = RuntimeBootstrapRecorder()

        recorder.success(RuntimeStage.CONFIG)
        recorder.reset()

        assertTrue(recorder.snapshot().isEmpty())
    }
}
