package com.multiapp.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class EngineBinderProviderManifestTest {
    @Test
    fun `engine Binder provider stays private in dedicated engine process`() {
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
            ":engine",
            requiredProvider.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
        )
    }

    @Test
    fun `package recovery runs before Binder publication in the dedicated engine process`() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val providers = document.getElementsByTagName("provider")
        val byName = (0 until providers.length)
            .map { providers.item(it) }
            .associateBy { node ->
                node.attributes.getNamedItemNS(ANDROID_NAMESPACE, "name")?.nodeValue.orEmpty()
            }
        val recovery = requireNotNull(
            byName["com.multiapp.core.installer.PackageGenerationRecoveryProvider"]
        )
        val engine = requireNotNull(byName[".container.EngineBinderProvider"])

        assertEquals(
            ":engine",
            recovery.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
        )
        assertEquals(
            ":engine",
            engine.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
        )
        val recoveryOrder = recovery.attributes
            .getNamedItemNS(ANDROID_NAMESPACE, "initOrder")
            ?.nodeValue
            ?.toIntOrNull()
        val engineOrder = engine.attributes
            .getNamedItemNS(ANDROID_NAMESPACE, "initOrder")
            ?.nodeValue
            ?.toIntOrNull()
        assertEquals(true, requireNotNull(recoveryOrder) > requireNotNull(engineOrder))
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

        assertEquals((0..23).toList(), bootstrapProviders.mapNotNull { it.first })
        bootstrapProviders.forEach { (index, authority, process) ->
            assertEquals("${'$'}{applicationId}.multiapp.bootstrap.v$index", authority)
            assertEquals(":v$index", process)
        }
    }

    @Test
    fun `foreground launch relay stays private in the host main process`() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifest)
        val receivers = document.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length)
            .map { receivers.item(it) }
            .firstOrNull { node ->
                node.attributes
                    ?.getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue == ".container.EngineForegroundLaunchReceiver"
            }

        val requiredReceiver = requireNotNull(receiver)
        assertEquals(
            "false",
            requiredReceiver.attributes.getNamedItemNS(ANDROID_NAMESPACE, "exported")?.nodeValue
        )
        assertEquals(
            null,
            requiredReceiver.attributes.getNamedItemNS(ANDROID_NAMESPACE, "process")?.nodeValue
        )
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
