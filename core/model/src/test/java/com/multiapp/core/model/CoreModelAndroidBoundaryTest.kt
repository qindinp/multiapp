package com.multiapp.core.model

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.assertTrue

class CoreModelAndroidBoundaryTest {

    @Test
    fun `main model sources do not expose Android framework types`() {
        val violations = Files.walk(mainSourceRoot()).use { paths ->
            paths
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .flatMap { path ->
                    val relativePath = mainSourceRoot().relativize(path).toString()
                    Files.readAllLines(path).mapIndexedNotNull { index, line ->
                        val trimmed = line.trim()
                        when {
                            forbiddenAndroidImport.matches(trimmed) ->
                                "$relativePath:${index + 1}: $trimmed"
                            forbiddenTypedReference.containsMatchIn(trimmed) ->
                                "$relativePath:${index + 1}: $trimmed"
                            else -> null
                        }
                    }.stream()
                }
                .toList()
        }

        assertTrue(
            actual = violations.isEmpty(),
            message = violations.joinToString(
                separator = System.lineSeparator(),
                prefix = "core:model must stay Android-free; move framework types to engine/loader/UI adapters:" +
                    System.lineSeparator()
            )
        )
    }

    private fun mainSourceRoot(): Path = Path.of("src", "main", "java")

    private companion object {
        private val forbiddenAndroidImport = Regex("""^import\s+android\.""")
        private val forbiddenTypedReference = Regex(
            """\bandroid\.(app|content|os|accounts|graphics|net|provider|database|view|widget)\."""
        )
    }
}
