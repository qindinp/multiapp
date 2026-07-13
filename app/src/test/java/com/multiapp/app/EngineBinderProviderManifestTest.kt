package com.multiapp.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EngineBinderProviderManifestTest {
    @Test
    fun `engine Binder provider stays private in host main process`() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val providers = document.getElementsByTagName("provider")
        val provider = (0 until providers.length)
            .map { providers.item(it) }
            .firstOrNull { node ->
                node.attributes
                    ?.getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue == ".container.EngineBinderProvider"
            }

        assertNotNull(provider)
        val requiredProvider = requireNotNull(provider)
        assertEquals(
            "${'$'}{applicationId}.multiapp.engine.server",
            requiredProvider.attributes.getNamedItemNS(ANDROID_NAMESPACE, "authorities")?.nodeValue
        )
        assertEquals(
            "false",
            requiredProvider.attributes.getNamedItemNS(ANDROID_NAMESPACE, "exported")?.nodeValue
        )
        assertEquals(
            null,
            requiredProvider.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
        )
    }

    @Test
    fun `bootstrap Providers cover each declared hosted process slot`() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val providers = document.getElementsByTagName("provider")
        val bootstrapProviders = (0 until providers.length)
            .map { providers.item(it) }
            .mapNotNull { node ->
                val name = node.attributes
                    ?.getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue
                    ?: return@mapNotNull null
                if (!name.startsWith(".container.EngineProcessBootstrapProviderV")) return@mapNotNull null
                Triple(
                    name.removePrefix(".container.EngineProcessBootstrapProviderV").toIntOrNull(),
                    node.attributes.getNamedItemNS(ANDROID_NAMESPACE, "authorities")?.nodeValue,
                    node.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
                )
            }
            .sortedBy { it.first }

        assertEquals((0..7).toList(), bootstrapProviders.mapNotNull { it.first })
        bootstrapProviders.forEach { (index, authority, process) ->
            assertEquals("${'$'}{applicationId}.multiapp.bootstrap.v$index", authority)
            assertEquals(":v$index", process)
        }
    }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { file -> file.parentFile?.absoluteFile }
            .firstOrNull { candidate -> File(candidate, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
