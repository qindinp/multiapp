package com.multiapp.app

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineBoundaryTest {

    @Test
    fun `app main keeps direct runtime references capped to migration baseline`() {
        val repoRoot = repoRoot()
        val sourceRoot = File(repoRoot, "app/src/main/java")
        val references = directRuntimeReferences(sourceRoot, repoRoot)

        val violations = runtimeReferenceViolations(
            actualReferenceCounts = references,
            allowedReferenceCounts = allowedLegacyRuntimeReferenceCounts
        )

        assertTrue(
            violations.isEmpty(),
            formatRuntimeReferenceViolations(violations)
        )
    }

    @Test
    fun `scanner catches new import and fully qualified runtime bypasses`() {
        val sourceRoot = Files.createTempDirectory("engine-boundary").toFile()
        try {
            File(sourceRoot, "Legacy.kt").writeText(
                """
                package sample

                import com.multiapp.core.loader.VirtualProcessRuntime
                """.trimIndent()
            )
            File(sourceRoot, "NewBypass.kt").writeText(
                """
                package sample

                import com.multiapp.core.loader.VirtualProcessRuntime
                """.trimIndent()
            )
            File(sourceRoot, "DirectUsage.kt").writeText(
                """
                package sample

                internal val slotName =
                    com.multiapp.core.loader.ProxyActivitySlots.processNameForClassName("com.example", 1)
                """.trimIndent()
            )

            val baseline = mapOf(
                RuntimeReferenceKey(
                    kind = ReferenceKind.IMPORT,
                    relativePath = "Legacy.kt",
                    fqcn = "com.multiapp.core.loader.VirtualProcessRuntime"
                ) to 1
            )

            val violations = runtimeReferenceViolations(
                actualReferenceCounts = directRuntimeReferences(sourceRoot, sourceRoot),
                allowedReferenceCounts = baseline
            )

            assertEquals(
                listOf(
                    RuntimeReferenceViolation(
                        key = RuntimeReferenceKey(
                            kind = ReferenceKind.FULLY_QUALIFIED_USAGE,
                            relativePath = "DirectUsage.kt",
                            fqcn = "com.multiapp.core.loader.ProxyActivitySlots"
                        ),
                        actualCount = 1,
                        allowedCount = 0
                    ),
                    RuntimeReferenceViolation(
                        key = RuntimeReferenceKey(
                            kind = ReferenceKind.IMPORT,
                            relativePath = "NewBypass.kt",
                            fqcn = "com.multiapp.core.loader.VirtualProcessRuntime"
                        ),
                        actualCount = 1,
                        allowedCount = 0
                    )
                ),
                violations
            )
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    private fun directRuntimeReferences(sourceRoot: File, repoRoot: File): Map<RuntimeReferenceKey, Int> {
        if (!sourceRoot.isDirectory) return emptyMap()

        return sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension in sourceExtensions }
            .flatMap { file ->
                val relativePath = file.relativeTo(repoRoot).invariantSeparatorsPath
                file.readLines().asSequence().flatMap { line ->
                    val importMatch = directRuntimeImportRegex.matchEntire(line.trim())
                    if (importMatch != null) {
                        sequenceOf(
                            RuntimeReferenceKey(
                                kind = ReferenceKind.IMPORT,
                                relativePath = relativePath,
                                fqcn = importMatch.groupValues[1]
                            )
                        )
                    } else {
                        directRuntimeFqcnRegex.findAll(line).map { match ->
                            RuntimeReferenceKey(
                                kind = ReferenceKind.FULLY_QUALIFIED_USAGE,
                                relativePath = relativePath,
                                fqcn = match.groupValues[1]
                            )
                        }
                    }
                }
            }
            .groupingBy { reference -> reference }
            .eachCount()
    }

    private fun runtimeReferenceViolations(
        actualReferenceCounts: Map<RuntimeReferenceKey, Int>,
        allowedReferenceCounts: Map<RuntimeReferenceKey, Int>
    ): List<RuntimeReferenceViolation> {
        return actualReferenceCounts.mapNotNull { (reference, actualCount) ->
            val allowedCount = allowedReferenceCounts[reference] ?: 0
            if (actualCount > allowedCount) {
                RuntimeReferenceViolation(
                    key = reference,
                    actualCount = actualCount,
                    allowedCount = allowedCount
                )
            } else {
                null
            }
        }.sortedWith(
            compareBy<RuntimeReferenceViolation> { violation -> violation.key.relativePath }
                .thenBy { violation -> violation.key.kind.name }
                .thenBy { violation -> violation.key.fqcn }
        )
    }

    private fun formatRuntimeReferenceViolations(violations: List<RuntimeReferenceViolation>): String {
        return buildString {
            appendLine("New app direct runtime references must go through :core:engine facades.")
            appendLine("The allowed direct runtime references are migration debt baseline only; shrink this baseline when migration removes them.")
            appendLine("Unexpected direct runtime references:")
            violations.forEach { violation ->
                appendLine(
                    " - ${violation.key.kind.label} ${violation.key.fqcn} in " +
                        "${violation.key.relativePath}: actual=${violation.actualCount}, " +
                        "baseline=${violation.allowedCount}"
                )
            }
        }
    }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { file -> file.parentFile?.absoluteFile }
            .firstOrNull { candidate -> File(candidate, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }

    private enum class ReferenceKind(val label: String) {
        IMPORT("import"),
        FULLY_QUALIFIED_USAGE("fully-qualified usage")
    }

    private data class RuntimeReferenceKey(
        val kind: ReferenceKind,
        val relativePath: String,
        val fqcn: String
    )

    private data class RuntimeReferenceViolation(
        val key: RuntimeReferenceKey,
        val actualCount: Int,
        val allowedCount: Int
    )

    private companion object {
        val sourceExtensions = setOf("java", "kt")

        val directRuntimeImportRegex =
            Regex("""import\s+(com\.multiapp\.core\.(?:loader|hook|xposed)\.[A-Za-z0-9_.*]+)(?:\s+as\s+[A-Za-z0-9_]+)?;?""")

        val directRuntimeFqcnRegex =
            Regex("""\b(com\.multiapp\.core\.(?:loader|hook|xposed)\.[A-Z][A-Za-z0-9_]*(?:\.[A-Z][A-Za-z0-9_]*)*)\b""")

        val allowedLegacyRuntimeReferenceCounts = emptyMap<RuntimeReferenceKey, Int>()
    }
}
