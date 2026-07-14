package com.multiapp.core.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EngineOwnerFileBoundaryTest {

    @Test
    fun `only engine owner constructs or opens durable runtime and task stores`() {
        val repoRoot = repoRoot()
        val sources = productionSources(repoRoot).toList()

        assertEquals(
            listOf(SERVER_RUNTIME),
            callSites(sources, repoRoot, Regex("""\bEngineRuntimeInstallers\.fileBackedSystemServer\s*\(""")),
            "Client and guest processes must obtain runtime facts through Binder"
        )
        assertEquals(
            listOf(SERVER_INSTALLER),
            constructorSites(
                sources,
                repoRoot,
                Regex("""\bFileBackedEngineRuntimeStateStore\s*\("""),
                "class FileBackedEngineRuntimeStateStore"
            ),
            "Only the engine owner may open the durable runtime state store"
        )
        assertEquals(
            listOf(SERVER_INSTALLER),
            constructorSites(
                sources,
                repoRoot,
                Regex("""\bFileBackedEngineActivityTaskStateStore\s*\("""),
                "class FileBackedEngineActivityTaskStateStore"
            ),
            "Only the engine owner may open the durable Activity task store"
        )
        assertEquals(
            listOf(SERVER_INSTALLER),
            constructorSites(
                sources,
                repoRoot,
                Regex("""\bFileBackedEnginePackageEnabledStateStore\s*\("""),
                "class FileBackedEnginePackageEnabledStateStore"
            ),
            "Only the engine owner may open the durable package enabled-state store"
        )
    }

    @Test
    fun `production callers cannot upload loader Activity task snapshots`() {
        val repoRoot = repoRoot()
        val sources = productionSources(repoRoot).toList()

        assertEquals(
            emptyList<String>(),
            callSites(
                sources,
                repoRoot,
                Regex("""(?:\.|\?\.)syncActivityTaskState\s*\(""")
            ),
            "Activity task state must be mutated through engine transactions, not snapshot replacement"
        )
    }

    private fun callSites(
        sources: List<File>,
        repoRoot: File,
        regex: Regex
    ): List<String> = sources.flatMap { source ->
        regex.findAll(source.readText()).map { source.relativePath(repoRoot) }.toList()
    }

    private fun constructorSites(
        sources: List<File>,
        repoRoot: File,
        regex: Regex,
        declarationPrefix: String
    ): List<String> = sources.flatMap { source ->
        val content = source.readText()
        regex.findAll(content).mapNotNull { match ->
            val line = content.lineAt(match.range.first).trimStart()
            source.relativePath(repoRoot).takeUnless { line.startsWith(declarationPrefix) }
        }.toList()
    }

    private fun String.lineAt(index: Int): String {
        val start = lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
        val end = indexOf('\n', index).let { if (it < 0) length else it }
        return substring(start, end)
    }

    private fun File.relativePath(repoRoot: File): String =
        relativeTo(repoRoot).invariantSeparatorsPath

    private fun productionSources(repoRoot: File): Sequence<File> = repoRoot.walkTopDown()
        .onEnter { directory -> directory.name !in EXCLUDED_DIRECTORIES }
        .filter { source ->
            source.isFile && source.extension in SOURCE_EXTENSIONS &&
                source.invariantSeparatorsPath.contains("/src/main/")
        }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile?.absoluteFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }

    private companion object {
        const val SERVER_RUNTIME =
            "core/engine/src/main/java/com/multiapp/core/engine/EngineServerRuntime.kt"
        const val SERVER_INSTALLER =
            "core/engine/src/main/java/com/multiapp/core/engine/EngineRuntimeInstallers.kt"
        val EXCLUDED_DIRECTORIES = setOf(".claude", ".git", ".gradle", ".idea", ".tmp", "build")
        val SOURCE_EXTENSIONS = setOf("java", "kt")
    }
}
