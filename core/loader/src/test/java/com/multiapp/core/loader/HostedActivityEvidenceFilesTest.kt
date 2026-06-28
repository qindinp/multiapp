package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals

class HostedActivityEvidenceFilesTest {
    @Test
    fun `activity evidence file names are component scoped`() {
        assertEquals(
            "inst-001.activity-instrumentation.properties",
            HostedActivityEvidenceFiles.instrumentation("inst-001")
        )
        assertEquals(
            "inst-001.activity-context.properties",
            HostedActivityEvidenceFiles.context("inst-001")
        )
        assertEquals(
            "inst-001.activity-remap.properties",
            HostedActivityEvidenceFiles.remap("inst-001")
        )
    }
}
