package com.multiapp.core.model.virtual

import java.io.File
import java.util.Properties

class FileBackedProxyActivitySlotAssignmentStore(
    private val file: File
) : ProxyActivitySlotAssignmentStore {

    @Synchronized
    override fun find(key: ProxyActivitySlotKey): String? {
        return load()[key.toStorageKey()]?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        val properties = load().toMutableMap()
        properties[key.toStorageKey()] = proxyActivityClassName
        store(properties)
    }

    @Synchronized
    override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? {
        if (proxyActivityClassName.isBlank()) return null
        return load()
            .entries
            .sortedBy { it.key }
            .firstOrNull { it.value == proxyActivityClassName }
            ?.key
            ?.toSlotKeyOrNull()
    }

    @Synchronized
    override fun pruneStaleAssignments(
        validInstanceIds: Set<String>,
        liveProxyActivityClassNames: Set<String>,
        knownProxyActivityClassNames: Set<String>
    ): Int {
        val current = load()
        if (current.isEmpty()) return 0

        val retained = linkedMapOf<String, String>()
        var removed = 0
        current.forEach { (storageKey, proxyActivityClassName) ->
            val owner = storageKey.toSlotKeyOrNull()
            val keep = owner != null &&
                owner.instanceId in validInstanceIds &&
                proxyActivityClassName in knownProxyActivityClassNames &&
                proxyActivityClassName in liveProxyActivityClassNames

            if (keep) {
                retained[storageKey] = proxyActivityClassName
            } else {
                removed += 1
            }
        }

        if (removed > 0) {
            store(retained)
        }
        return removed
    }

    private fun load(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val properties = Properties()
        file.inputStream().use { input -> properties.load(input) }
        return properties.stringPropertyNames().associateWith { name ->
            properties.getProperty(name).orEmpty()
        }
    }

    private fun store(values: Map<String, String>) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        values.forEach { (key, value) -> properties.setProperty(key, value) }
        file.outputStream().use { output ->
            properties.store(output, "MultiApp proxy Activity slot assignments")
        }
    }

    private fun ProxyActivitySlotKey.toStorageKey(): String {
        require(!instanceId.hasUnsafeStorageChars()) { "unsafe instanceId for proxy slot key" }
        require(!taskKey.hasUnsafeStorageChars()) { "unsafe taskKey for proxy slot key" }
        val mode = launchMode ?: "standard"
        require(!mode.hasUnsafeStorageChars()) { "unsafe launchMode for proxy slot key" }
        return "$instanceId|$mode|$taskKey"
    }

    private fun String.toSlotKeyOrNull(): ProxyActivitySlotKey? {
        val parts = split('|')
        if (parts.size != 3) return null
        val mode = parts[1].takeIf { it != "standard" }
        return runCatching {
            ProxyActivitySlotKey(
                instanceId = parts[0],
                launchMode = mode,
                taskKey = parts[2]
            )
        }.getOrNull()
    }

    private fun String.hasUnsafeStorageChars(): Boolean =
        any { it == '\n' || it == '\r' || it == '|' || it.code < 0x20 }
}
