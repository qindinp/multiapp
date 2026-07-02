package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeBootstrapTest {

    @Test
    fun `runtime stages keep expected startup order`() {
        assertEquals(
            listOf(
                RuntimeStage.CONFIG,
                RuntimeStage.ORIGIN_APK,
                RuntimeStage.PACKAGE_METADATA,
                RuntimeStage.NATIVE_LIBS,
                RuntimeStage.RESOURCES,
                RuntimeStage.PACKAGE_MANAGER_PROXY,
                RuntimeStage.CLASS_LOADER,
                RuntimeStage.GUEST_CONTEXT,
                RuntimeStage.APPLICATION,
                RuntimeStage.LAUNCHER_ACTIVITY
            ),
            RuntimeStage.ordered()
        )

        val orders = RuntimeStage.ordered().map { it.order }
        assertEquals(orders.sorted(), orders)
        assertEquals(orders.toSet().size, orders.size)
        assertTrue(RuntimeStage.NATIVE_LIBS.order < RuntimeStage.CLASS_LOADER.order)
        assertTrue(RuntimeStage.CLASS_LOADER.order < RuntimeStage.APPLICATION.order)
        assertTrue(RuntimeStage.APPLICATION.order < RuntimeStage.LAUNCHER_ACTIVITY.order)
    }

    @Test
    fun `success result records stage evidence`() {
        val result = BootstrapResult.success(
            stage = RuntimeStage.NATIVE_LIBS,
            message = "origin libs extracted",
            evidence = listOf(BootstrapEvidence("abi", "arm64-v8a", "manifest")),
            durationMs = 12
        )

        assertEquals(BootstrapStatus.SUCCESS, result.status)
        assertTrue(result.isSuccessful)
        assertFalse(result.isTerminalFailure)
        assertEquals("arm64-v8a", result.evidence.single().value)
    }

    @Test
    fun `failed result captures throwable details`() {
        val error = UnsatisfiedLinkError("interface20")
        val result = BootstrapResult.failed(
            stage = RuntimeStage.APPLICATION,
            message = "attachBaseContext failed",
            error = error,
            rollbackNote = "skip optional fallback"
        )

        assertEquals(BootstrapStatus.FAILED, result.status)
        assertFalse(result.isSuccessful)
        assertTrue(result.isTerminalFailure)
        assertEquals(UnsatisfiedLinkError::class.java.name, result.errorClass)
        assertEquals("interface20", result.errorMessage)
        assertEquals("skip optional fallback", result.rollbackNote)
    }

    @Test
    fun `skipped and degraded helpers record non-success outcomes`() {
        val skipped = BootstrapResult.skipped(
            stage = RuntimeStage.PACKAGE_METADATA,
            message = "metadata already available"
        )
        val degradedError = IllegalArgumentException("resource fallback")
        val degraded = BootstrapResult.degraded(
            stage = RuntimeStage.RESOURCES,
            message = "using fallback resources",
            error = degradedError,
            evidence = listOf(BootstrapEvidence("fallback", "stub", "AssetManager")),
            durationMs = 7
        )

        assertEquals(BootstrapStatus.SKIPPED, skipped.status)
        assertFalse(skipped.isSuccessful)
        assertFalse(skipped.isTerminalFailure)
        assertEquals("metadata already available", skipped.message)

        assertEquals(BootstrapStatus.DEGRADED, degraded.status)
        assertFalse(degraded.isSuccessful)
        assertFalse(degraded.isTerminalFailure)
        assertEquals(IllegalArgumentException::class.java.name, degraded.errorClass)
        assertEquals("resource fallback", degraded.errorMessage)
        assertEquals("stub", degraded.evidence.single().value)
    }

    @Test
    fun `bootstrap evidence rejects blank key and duration rejects negative values`() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapEvidence("", "value")
        }
        assertFailsWith<IllegalArgumentException> {
            BootstrapResult.success(RuntimeStage.CONFIG, durationMs = -1)
        }
    }
}
