package com.multiapp.core.model.virtual

import java.io.File
import java.util.Properties

class FileBackedProxyActivitySlotAssignmentStore(
    private val file: File
) : ProxyActivitySlotAssignmentStore {

    override fun find(key: ProxyActivitySlotKey): String? {
        return withFileLock {
            load()[key.toStorageKey()]?.takeIf { it.isNotBlank() }
        }
    }

    override fun save(key: ProxyActivitySlotKey, proxyActivityClassName: String) {
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        withFileLock {
            val properties = load().toMutableMap()
            properties[key.toStorageKey()] = proxyActivityClassName
            store(properties)
        }
    }

    override fun compareAndSet(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): Boolean {
        require(newProxyActivityClassName == null || newProxyActivityClassName.isNotBlank()) {
            "newProxyActivityClassName must be null or non-blank"
        }
        return withFileLock {
            val properties = load().toMutableMap()
            val storageKey = key.toStorageKey()
            if (properties[storageKey] != expectedProxyActivityClassName) {
                return@withFileLock false
            }
            if (newProxyActivityClassName == null) {
                properties.remove(storageKey)
            } else {
                val ownedByDifferentKey = properties.any { (storedKey, value) ->
                    storedKey != storageKey && value == newProxyActivityClassName
                }
                if (ownedByDifferentKey) {
                    return@withFileLock false
                }
                properties[storageKey] = newProxyActivityClassName
            }
            store(properties)
            true
        }
    }

    override fun reserve(key: ProxyActivitySlotKey, candidateProxyActivityClassNames: List<String>): String? {
        val candidates = candidateProxyActivityClassNames.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null
        return withFileLock {
            val properties = load().toMutableMap()
            val storageKey = key.toStorageKey()
            val assigned = properties[storageKey]?.takeIf { it in candidates }
            if (assigned != null && properties.none { it.key != storageKey && it.value == assigned }) {
                return@withFileLock assigned
            }
            val selected = candidates.firstOrNull { candidate ->
                properties.none { (storedKey, value) ->
                    value == candidate && storedKey.toSlotKeyOrNull() != key
                }
            } ?: return@withFileLock null
            properties[storageKey] = selected
            store(properties)
            selected
        }
    }

    override fun ownerOf(proxyActivityClassName: String): ProxyActivitySlotKey? {
        if (proxyActivityClassName.isBlank()) return null
        return withFileLock {
            load()
                .entries
                .sortedBy { it.key }
                .firstOrNull { it.value == proxyActivityClassName }
                ?.key
                ?.toSlotKeyOrNull()
        }
    }

    override fun pruneStaleAssignments(
        validInstanceIds: Set<String>,
        liveProxyActivityClassNames: Set<String>,
        knownProxyActivityClassNames: Set<String>
    ): Int {
        return withFileLock {
            val current = load()
            if (current.isEmpty()) return@withFileLock 0

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
            removed
        }
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

    private inline fun <T> withFileLock(block: () -> T): T =
        synchronized(lockForFile(file)) {
            block()
        }

    companion object {
        private val locks = linkedMapOf<String, Any>()

        private fun lockForFile(file: File): Any {
            val key = file.absoluteFile.path
            return synchronized(locks) {
                locks.getOrPut(key) { Any() }
            }
        }
    }
}
