package com.multiapp.core.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProxyActivitySlotAuthorityBoundaryTest {

    @Test
    fun `only engine server installer constructs the file backed proxy Activity slot store`() {
        val repoRoot = repoRoot()
        val constructionSites = productionSources(repoRoot)
            .flatMap { source ->
                val content = source.readText()
                fileBackedStoreConstructorRegex.findAll(content).mapNotNull { match ->
                    val lineStart = content.lastIndexOf('\n', match.range.first)
                        .let { index -> if (index < 0) 0 else index + 1 }
                    val lineEnd = content.indexOf('\n', match.range.first)
                        .let { index -> if (index < 0) content.length else index }
                    val line = content.substring(lineStart, lineEnd).trimStart()
                    if (line.startsWith("class FileBackedProxyActivitySlotAssignmentStore")) {
                        null
                    } else {
                        source.relativeTo(repoRoot).invariantSeparatorsPath
                    }
                }
            }
            .toList()

        assertEquals(
            listOf(AUTHORITATIVE_CONSTRUCTION_SITE),
            constructionSites,
            "Only the engine server installer may construct the persistent proxy Activity slot store"
        )
    }

    private fun productionSources(repoRoot: File): Sequence<File> = repoRoot.walkTopDown()
        .onEnter { directory -> directory.name !in excludedDirectoryNames }
        .filter { source ->
            source.isFile &&
                source.extension in sourceExtensions &&
                source.invariantSeparatorsPath.contains("/src/main/")
        }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { file -> file.parentFile?.absoluteFile }
            .firstOrNull { candidate -> File(candidate, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }

    private companion object {
        const val AUTHORITATIVE_CONSTRUCTION_SITE =
            "core/engine/src/main/java/com/multiapp/core/engine/EngineRuntimeInstallers.kt"

        val fileBackedStoreConstructorRegex =
            Regex("""\bFileBackedProxyActivitySlotAssignmentStore\s*\(""")

        val excludedDirectoryNames = setOf(
            ".claude",
            ".git",
            ".gradle",
            ".idea",
            ".tmp",
            "build"
        )
        val sourceExtensions = setOf("java", "kt")
    }
}
