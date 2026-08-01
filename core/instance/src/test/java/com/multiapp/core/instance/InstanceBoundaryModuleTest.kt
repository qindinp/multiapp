package com.multiapp.core.instance

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-boundary test that verifies [CloneCreateUseCase] does not reference
 * durable owner store / manager / journal types.
 *
 * This is a compile-time safety net: the production code must depend only on
 * [com.multiapp.core.model.engine.VirtualizationEngine] for instance operations.
 */
class InstanceBoundaryModuleTest {

    @Test
    fun `CloneCreateUseCase does not reference owner InstanceManager`() {
        val source = useCaseSource().readText()
        assertFalse(
            Regex("""\bInstanceManager\b""").containsMatchIn(source),
            "CloneCreateUseCase must not reference InstanceManager; " +
                "use VirtualizationEngine.listInstances() instead"
        )
    }

    @Test
    fun `CloneCreateUseCase does not reference package generation journal types`() {
        val source = useCaseSource().readText()
        forbiddenJournalTypes.forEach { type ->
            assertFalse(
                Regex("""\b${Regex.escape(type)}\b""").containsMatchIn(source),
                "CloneCreateUseCase must not reference $type; " +
                    "package generation journaling is engine-owned"
            )
        }
    }

    @Test
    fun `CloneCreateUseCase depends on VirtualizationEngine`() {
        val source = useCaseSource().readText()
        assertTrue(
            Regex("""\bVirtualizationEngine\b""").containsMatchIn(source),
            "CloneCreateUseCase must depend on VirtualizationEngine for instance operations"
        )
    }

    @Test
    fun `InstanceBoundaryModule binds CloneCreationCoordinator to CloneCreateUseCase`() {
        val source = boundaryModuleSource().readText()
        assertTrue(
            bindCoordinatorRegex.containsMatchIn(source),
            "InstanceBoundaryModule must bind CloneCreateUseCase as CloneCreationCoordinator"
        )
    }

    @Test
    fun `InstanceBoundaryModule binds InstalledAppCatalog to InstalledAppRepository`() {
        val source = boundaryModuleSource().readText()
        assertTrue(
            bindCatalogRegex.containsMatchIn(source),
            "InstanceBoundaryModule must bind InstalledAppRepository as InstalledAppCatalog"
        )
    }

    private fun useCaseSource(): File {
        val source = File(repoRoot(), "core/instance/src/main/java/$USE_CASE_PATH")
        check(source.isFile) { "Unable to locate production source: $source" }
        return source
    }

    private fun boundaryModuleSource(): File {
        val source = File(repoRoot(), "core/instance/src/main/java/$MODULE_PATH")
        check(source.isFile) { "Unable to locate boundary module source: $source" }
        return source
    }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { file -> file.parentFile?.absoluteFile }
            .firstOrNull { candidate -> File(candidate, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }

    private companion object {
        const val USE_CASE_PATH =
            "com/multiapp/core/instance/CloneCreateUseCase.kt"
        const val MODULE_PATH =
            "com/multiapp/core/instance/InstanceBoundaryModule.kt"

        val forbiddenJournalTypes = listOf(
            "PackageGenerationJournal",
            "PackageGenerationTransaction",
            "PackageGenerationTransactionJournal"
        )

        val bindCoordinatorRegex = Regex(
            """fun\s+\w+\s*\(\s*\w+\s*:\s*CloneCreateUseCase\s*\)\s*:\s*CloneCreationCoordinator"""
        )

        val bindCatalogRegex = Regex(
            """fun\s+\w+\s*\(\s*\w+\s*:\s*InstalledAppRepository\s*\)\s*:\s*InstalledAppCatalog"""
        )
    }
}
