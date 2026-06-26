package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BootstrapReversibilityTest {

    @Test
    fun `every runtime stage has a reversibility default`() {
        for (stage in RuntimeStage.values()) {
            val rev = StageReversibility.defaultFor(stage)
            assertNotNull(rev, "Stage $stage must have a default reversibility")
        }
    }

    @Test
    fun `CONFIG is REVERSIBLE`() {
        assertEquals(StageReversibility.REVERSIBLE, StageReversibility.defaultFor(RuntimeStage.CONFIG))
    }

    @Test
    fun `GUEST_CONTEXT is REVERSIBLE`() {
        assertEquals(StageReversibility.REVERSIBLE, StageReversibility.defaultFor(RuntimeStage.GUEST_CONTEXT))
    }

    @Test
    fun `PACKAGE_METADATA is REVERSIBLE`() {
        assertEquals(StageReversibility.REVERSIBLE, StageReversibility.defaultFor(RuntimeStage.PACKAGE_METADATA))
    }

    @Test
    fun `ORIGIN_APK is IRREVERSIBLE`() {
        assertEquals(StageReversibility.IRREVERSIBLE, StageReversibility.defaultFor(RuntimeStage.ORIGIN_APK))
    }

    @Test
    fun `NATIVE_LIBS is IRREVERSIBLE`() {
        assertEquals(StageReversibility.IRREVERSIBLE, StageReversibility.defaultFor(RuntimeStage.NATIVE_LIBS))
    }

    @Test
    fun `RESOURCES is PARTIALLY_REVERSIBLE`() {
        assertEquals(StageReversibility.PARTIALLY_REVERSIBLE, StageReversibility.defaultFor(RuntimeStage.RESOURCES))
    }

    @Test
    fun `CLASS_LOADER is IRREVERSIBLE`() {
        assertEquals(StageReversibility.IRREVERSIBLE, StageReversibility.defaultFor(RuntimeStage.CLASS_LOADER))
    }

    @Test
    fun `APPLICATION is IRREVERSIBLE`() {
        assertEquals(StageReversibility.IRREVERSIBLE, StageReversibility.defaultFor(RuntimeStage.APPLICATION))
    }

    @Test
    fun `stage result summary includes reversibility`() {
        val result = BootstrapResult.success(
            stage = RuntimeStage.CONFIG,
            message = "ok",
            durationMs = 10
        )
        val summary = result.toStageResultSummary()
        assertEquals(StageReversibility.REVERSIBLE, summary.reversibility)
    }
}
