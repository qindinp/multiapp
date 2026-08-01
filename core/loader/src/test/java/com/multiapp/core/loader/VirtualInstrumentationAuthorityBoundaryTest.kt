package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Boundary test verifying that VirtualInstrumentation.kt enforces fail-closed
 * semantics for guest cache miss paths — no direct construction of
 * JsonInstallRecordStore, JsonInstanceRecordStore, DefaultInstanceManager,
 * no local HostedRuntimeBootstrap, and no removed directory constants.
 */
class VirtualInstrumentationAuthorityBoundaryTest {

    private val sourceFile: File by lazy {
        val projectRoot = File(System.getProperty("user.dir"))
        // Walk up to find the module root containing src/main/java
        val candidate = generateSequence(projectRoot) { it.parentFile }
            .firstOrNull { dir ->
                File(dir, "src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt").isFile
            }
        requireNotNull(candidate) {
            "Unable to locate project root from working directory: $projectRoot"
        }
        File(candidate, "src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt")
    }

    private val sourceContent: String by lazy {
        assertTrue(sourceFile.isFile, "VirtualInstrumentation.kt must exist at: ${sourceFile.absolutePath}")
        sourceFile.readText()
    }

    // ── Forbidden direct-construct imports ────────────────────────────

    @Test
    fun `source does not import JsonInstallRecordStore`() {
        assertFalse(
            sourceContent.contains("import com.multiapp.core.model.installer.JsonInstallRecordStore"),
            "VirtualInstrumentation.kt must not import JsonInstallRecordStore"
        )
    }

    @Test
    fun `source does not import JsonInstanceRecordStore`() {
        assertFalse(
            sourceContent.contains("import com.multiapp.core.model.instance.JsonInstanceRecordStore"),
            "VirtualInstrumentation.kt must not import JsonInstanceRecordStore"
        )
    }

    @Test
    fun `source does not import DefaultInstanceManager`() {
        assertFalse(
            sourceContent.contains("import com.multiapp.core.model.instance.DefaultInstanceManager"),
            "VirtualInstrumentation.kt must not import DefaultInstanceManager"
        )
    }

    // ── Forbidden direct-construct usages ─────────────────────────────

    @Test
    fun `source does not construct JsonInstallRecordStore`() {
        assertFalse(
            sourceContent.contains("JsonInstallRecordStore("),
            "VirtualInstrumentation.kt must not construct JsonInstallRecordStore"
        )
    }

    @Test
    fun `source does not construct JsonInstanceRecordStore`() {
        assertFalse(
            sourceContent.contains("JsonInstanceRecordStore("),
            "VirtualInstrumentation.kt must not construct JsonInstanceRecordStore"
        )
    }

    @Test
    fun `source does not construct DefaultInstanceManager`() {
        assertFalse(
            sourceContent.contains("DefaultInstanceManager("),
            "VirtualInstrumentation.kt must not construct DefaultInstanceManager"
        )
    }

    // ── Forbidden local HostedRuntimeBootstrap ────────────────────────

    @Test
    fun `source does not construct local HostedRuntimeBootstrap`() {
        assertFalse(
            sourceContent.contains("HostedRuntimeBootstrap("),
            "VirtualInstrumentation.kt must not construct HostedRuntimeBootstrap locally"
        )
    }

    @Test
    fun `source does not call processRuntime bindApplication`() {
        assertFalse(
            sourceContent.contains("processRuntime.bindApplication("),
            "VirtualInstrumentation.kt must not call processRuntime.bindApplication()"
        )
    }

    // ── Removed directory constants ───────────────────────────────────

    @Test
    fun `source does not declare INSTANCES_DIR constant`() {
        assertFalse(
            sourceContent.contains("INSTANCES_DIR"),
            "VirtualInstrumentation.kt must not declare or reference INSTANCES_DIR"
        )
    }

    @Test
    fun `source does not declare INSTALLS_DIR constant`() {
        assertFalse(
            sourceContent.contains("INSTALLS_DIR"),
            "VirtualInstrumentation.kt must not declare or reference INSTALLS_DIR"
        )
    }

    @Test
    fun `source does not declare INSTANCE_DATA_DIR constant`() {
        assertFalse(
            sourceContent.contains("INSTANCE_DATA_DIR"),
            "VirtualInstrumentation.kt must not declare or reference INSTANCE_DATA_DIR"
        )
    }

    // ── Fail-closed semantics present ─────────────────────────────────

    @Test
    fun `source contains fail-closed IllegalStateException for cache miss`() {
        assertTrue(
            sourceContent.contains("GUEST_RUNTIME_CACHE_MISS_FAIL_CLOSED"),
            "VirtualInstrumentation.kt must contain fail-closed evidence detail for cache miss"
        )
    }

    @Test
    fun `source writes cache miss fail-closed evidence`() {
        assertTrue(
            sourceContent.contains("writeCacheMissFailClosedEvidence"),
            "VirtualInstrumentation.kt must write evidence before throwing on cache miss"
        )
    }

    @Test
    fun `source throws IllegalStateException on cache miss`() {
        assertTrue(
            sourceContent.contains("throw IllegalStateException"),
            "VirtualInstrumentation.kt must throw IllegalStateException on guest cache miss"
        )
    }
}
