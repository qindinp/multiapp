package com.multiapp.app.container

import com.multiapp.core.loader.ProxyActivitySlots
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyActivityClassParityTest {

    @Test
    fun `all proxy slot classes are declared in app code`() {
        val classNames = ProxyActivitySlots.classNames("com.multiapp.app")

        classNames.forEach { className ->
            assertTrue(
                Class.forName(className).isAssignableTo(ProxyActivityBase::class.java),
                "Missing proxy Activity class: $className"
            )
        }
    }

    @Test
    fun `manifest proxy activity declarations match proxy slot catalog`() {
        val manifest = loadProxyActivityManifestEntries()
        val hostPackageName = "com.multiapp.app"
        val expectedClassNames = ProxyActivitySlots.classNames(hostPackageName)
        val launchModeByClassName = ProxyActivitySlots.launchModeByClassName(hostPackageName)
        val processNameByClassName = ProxyActivitySlots.processNameByClassName(hostPackageName)

        assertEquals(expectedClassNames, manifest.map { entry -> entry.className })
        assertEquals(24, manifest.map { entry -> entry.taskAffinity }.distinct().size)
        assertFalse(manifest.any { entry -> entry.excludeFromRecents })

        manifest.forEach { entry ->
            val expectedLaunchMode = launchModeByClassName.getValue(entry.className) ?: "standard"
            assertEquals(expectedLaunchMode, entry.launchMode, "launchMode mismatch for ${entry.className}")
            assertEquals(
                processNameByClassName.getValue(entry.className).removePrefix(hostPackageName),
                entry.processName,
                "process mismatch for ${entry.className}"
            )
            assertTrue(
                entry.taskAffinity.startsWith("\${applicationId}.multiapp.task."),
                "Proxy taskAffinity must stay host-scoped and unique: ${entry.className}"
            )
        }
    }

    @Test
    fun `manifest container process entries match proxy process slots`() {
        val manifest = loadContainerActivityManifestEntries()

        assertEquals(24, manifest.size)
        manifest.forEachIndexed { index, entry ->
            assertEquals("com.multiapp.app.container.ContainerActivityV$index", entry.className)
            assertEquals(":v$index", entry.processName)
            assertTrue(entry.excludeFromRecents)
            assertEquals("standard", entry.launchMode)
        }
    }

    private fun Class<*>.isAssignableTo(baseClass: Class<*>): Boolean =
        baseClass.isAssignableFrom(this)

    private fun loadProxyActivityManifestEntries(): List<ManifestProxyActivityEntry> {
        val manifestFile = findManifestFile()
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifestFile)
        val activities = document.getElementsByTagName("activity")
        return (0 until activities.length).mapNotNull { index ->
            val element = activities.item(index)
            val name = element.attributes.getNamedItem("android:name")?.nodeValue.orEmpty()
            if (!name.startsWith(".container.ProxyActivity")) return@mapNotNull null
            ManifestProxyActivityEntry(
                className = "com.multiapp.app${name}",
                launchMode = element.attributes.getNamedItem("android:launchMode")?.nodeValue.orEmpty(),
                processName = element.attributes.getNamedItem("android:process")?.nodeValue.orEmpty(),
                taskAffinity = element.attributes.getNamedItem("android:taskAffinity")?.nodeValue.orEmpty(),
                excludeFromRecents = element.attributes
                    .getNamedItem("android:excludeFromRecents")
                    ?.nodeValue
                    ?.toBooleanStrictOrNull()
                    ?: false
            )
        }
    }

    private fun loadContainerActivityManifestEntries(): List<ManifestContainerActivityEntry> {
        val manifestFile = findManifestFile()
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifestFile)
        val activities = document.getElementsByTagName("activity")
        return (0 until activities.length).mapNotNull { index ->
            val element = activities.item(index)
            val name = element.attributes.getNamedItem("android:name")?.nodeValue.orEmpty()
            if (!name.startsWith(".container.ContainerActivityV")) return@mapNotNull null
            ManifestContainerActivityEntry(
                className = "com.multiapp.app${name}",
                launchMode = element.attributes.getNamedItem("android:launchMode")?.nodeValue.orEmpty(),
                processName = element.attributes.getNamedItem("android:process")?.nodeValue.orEmpty(),
                excludeFromRecents = element.attributes
                    .getNamedItem("android:excludeFromRecents")
                    ?.nodeValue
                    ?.toBooleanStrictOrNull()
                    ?: false
            )
        }
    }

    private fun findManifestFile(): File {
        val candidates = generateSequence(File(".").absoluteFile) { file -> file.parentFile }
            .flatMap { dir ->
                sequenceOf(
                    File(dir, "app/src/main/AndroidManifest.xml"),
                    File(dir, "src/main/AndroidManifest.xml")
                )
            }
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate app/src/main/AndroidManifest.xml")
    }

    private data class ManifestProxyActivityEntry(
        val className: String,
        val launchMode: String,
        val processName: String,
        val taskAffinity: String,
        val excludeFromRecents: Boolean
    )

    private data class ManifestContainerActivityEntry(
        val className: String,
        val launchMode: String,
        val processName: String,
        val excludeFromRecents: Boolean
    )
}
