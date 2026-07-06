package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        assertEquals(
            "inst-001.activity-lifecycle.properties",
            HostedActivityEvidenceFiles.lifecycle("inst-001")
        )
        assertEquals(
            "inst-001.activity-new-intent.properties",
            HostedActivityEvidenceFiles.newIntent("inst-001")
        )
        assertEquals(
            "inst-001.activity-result.properties",
            HostedActivityEvidenceFiles.result("inst-001")
        )
        assertEquals(
            "inst-001.protected-diagnostics.properties",
            HostedActivityEvidenceFiles.protectedDiagnostics("inst-001")
        )
        assertEquals(
            "inst-001.native-load.properties",
            HostedActivityEvidenceFiles.nativeLoad("inst-001")
        )
        assertEquals(
            "inst-001.register-natives.properties",
            HostedActivityEvidenceFiles.registerNatives("inst-001")
        )
        assertEquals(
            "inst-001.protected-verdict.properties",
            HostedActivityEvidenceFiles.protectedVerdict("inst-001")
        )
    }

    @Test
    fun `activity evidence file names reject unsafe instance ids`() {
        listOf(
            "../inst-001",
            "inst/001",
            "inst\\001",
            "",
            " ",
            " inst-001",
            "inst-001 ",
            ".",
            "..",
            "a..b",
            "/inst",
            "C:inst",
            "inst:001",
            "inst\u0000evil",
            "%2e%2e",
            "..%2fsecret",
            "%2fabsolute",
            "inst%5c001"
        ).forEach { instanceId ->
            assertFailsWith<IllegalArgumentException> {
                HostedActivityEvidenceFiles.lifecycle(instanceId)
            }
        }
    }
}
