package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDiagnosticsProfileTest {

    // ===== 1. Empty evidence -> JNI_ONLOAD_NOT_EXECUTED =====

    @Test
    fun `verdict is JNI_ONLOAD_NOT_EXECUTED when no evidence provided`() {
        val result = NativeDiagnosticsProfile.analyze(
            NativeDiagnosticsConfig(),
            emptyList()
        )
        assertEquals(Interface20Verdict.JNI_ONLOAD_NOT_EXECUTED, result.verdict)
    }

    @Test
    fun `verdict is JNI_ONLOAD_NOT_EXECUTED when evidence lacks jni_onload key`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("some_other_key", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.JNI_ONLOAD_NOT_EXECUTED, result.verdict)
    }

    @Test
    fun `verdict is JNI_ONLOAD_NOT_EXECUTED when jni_onload value is false`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "false")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.JNI_ONLOAD_NOT_EXECUTED, result.verdict)
    }

    // ===== 2. JNI_OnLoad executed but no RegisterNatives =====

    @Test
    fun `verdict is REGISTER_NATIVES_NOT_EXECUTED when jni_onload but no register_natives`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.REGISTER_NATIVES_NOT_EXECUTED, result.verdict)
    }

    @Test
    fun `verdict is REGISTER_NATIVES_NOT_EXECUTED when register_natives value is false`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "false")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.REGISTER_NATIVES_NOT_EXECUTED, result.verdict)
    }

    // ===== 3. RegisterNatives called for wrong class =====

    @Test
    fun `verdict is REGISTER_NATIVES_WRONG_CLASS when wrong class registered`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.other.Class")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.REGISTER_NATIVES_WRONG_CLASS, result.verdict)
    }

    @Test
    fun `verdict is REGISTER_NATIVES_WRONG_CLASS when class is empty string`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.REGISTER_NATIVES_WRONG_CLASS, result.verdict)
    }

    // ===== 4. Original shell registered (happy path) =====

    @Test
    fun `verdict is ORIGINAL_SHELL_REGISTERED when original shell path with stub class`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.stub.StubApp"),
            NativeDiagnosticsEvidence("original_shell_path", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.ORIGINAL_SHELL_REGISTERED, result.verdict)
    }

    @Test
    fun `verdict is ORIGINAL_SHELL_REGISTERED when original shell path with qihoo class`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.qihoo.util.StubApp"),
            NativeDiagnosticsEvidence("original_shell_path", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.ORIGINAL_SHELL_REGISTERED, result.verdict)
    }

    // ===== 5. Fallback registered =====

    @Test
    fun `verdict is FALLBACK_REGISTERED when only fallback observed`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.stub.StubApp"),
            NativeDiagnosticsEvidence("original_shell_path", "false"),
            NativeDiagnosticsEvidence("fallback_registered", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.FALLBACK_REGISTERED, result.verdict)
    }

    @Test
    fun `verdict is FALLBACK_REGISTERED when no original_shell_path key and fallback is true`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.stub.StubApp"),
            NativeDiagnosticsEvidence("fallback_registered", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.FALLBACK_REGISTERED, result.verdict)
    }

    // ===== 6. INSUFFICIENT_EVIDENCE when no shell path and no fallback =====

    @Test
    fun `verdict is INSUFFICIENT_EVIDENCE when no original_shell_path and no fallback`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.stub.StubApp")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(Interface20Verdict.INSUFFICIENT_EVIDENCE, result.verdict)
    }

    // ===== 7. Verdict reason is human readable =====

    @Test
    fun `verdict reason for JNI_ONLOAD_NOT_EXECUTED is human readable`() {
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), emptyList())
        assertTrue(result.verdictReason.isNotEmpty())
        assertTrue(result.verdictReason.contains("JNI_OnLoad"))
    }

    @Test
    fun `verdict reason for REGISTER_NATIVES_NOT_EXECUTED mentions RegisterNatives`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertTrue(result.verdictReason.contains("RegisterNatives"))
    }

    @Test
    fun `verdict reason for REGISTER_NATIVES_WRONG_CLASS includes the wrong class name`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.evil.Class")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertTrue(result.verdictReason.contains("com.evil.Class"))
    }

    @Test
    fun `verdict reason for ORIGINAL_SHELL_REGISTERED is positive`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.stub.StubApp"),
            NativeDiagnosticsEvidence("original_shell_path", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertTrue(result.verdictReason.contains("Original shell"))
        assertTrue(result.verdictReason.contains("successfully"))
    }

    // ===== 8. Config defaults =====

    @Test
    fun `config defaults have safe root-requiring flags disabled`() {
        val config = NativeDiagnosticsConfig()
        assertTrue(config.recordNativeLoad)
        assertTrue(config.recordJniOnLoad)
        assertTrue(config.recordFindClass)
        assertTrue(config.recordRegisterNatives)
        assertTrue(config.recordLibraryPath)
        assertTrue(config.recordClassLoader)
        assertFalse(config.recordNativeNamespace)
        assertFalse(config.recordProcMaps)
        assertFalse(config.recordLinkerMessage)
    }

    // ===== 9. Result preserves config and evidence =====

    @Test
    fun `result preserves the config that was passed in`() {
        val config = NativeDiagnosticsConfig(recordNativeLoad = false, recordProcMaps = true)
        val result = NativeDiagnosticsProfile.analyze(config, emptyList())
        assertEquals(config, result.config)
        assertFalse(result.config.recordNativeLoad)
        assertTrue(result.config.recordProcMaps)
    }

    @Test
    fun `result preserves the evidence list that was passed in`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("key1", "val1", "source1"),
            NativeDiagnosticsEvidence("key2", "val2", "source2")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(evidence, result.evidence)
        assertEquals(2, result.evidence.size)
    }

    // ===== 10. Evidence timestamp is preserved =====

    @Test
    fun `evidence timestamps are preserved in result`() {
        val ts = 1700000000000L
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true", timestampMs = ts)
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        assertEquals(ts, result.evidence.first().timestampMs)
    }

    // ===== 11. Multiple evidence entries with same key =====

    @Test
    fun `first matching evidence key is used for verdict`() {
        val evidence = listOf(
            NativeDiagnosticsEvidence("jni_onload_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_executed", "true"),
            NativeDiagnosticsEvidence("register_natives_class", "com.stub.StubApp"),
            NativeDiagnosticsEvidence("register_natives_class", "com.other.Class"),
            NativeDiagnosticsEvidence("original_shell_path", "true")
        )
        val result = NativeDiagnosticsProfile.analyze(NativeDiagnosticsConfig(), evidence)
        // First register_natives_class is com.stub.StubApp, so verdict proceeds to check shell path
        assertEquals(Interface20Verdict.ORIGINAL_SHELL_REGISTERED, result.verdict)
    }
}
