package com.multiapp.core.model.virtual

import java.util.UUID

class ProxyActivityRegistry(
    private val proxyActivityClassNames: List<String>,
    private val launchModeByClassName: Map<String, String?> = emptyMap()
) {
    private val records = LinkedHashMap<String, VirtualActivityRecord>()

    init {
        require(proxyActivityClassNames.isNotEmpty()) { "at least one proxy Activity is required" }
        require(proxyActivityClassNames.all { it.isNotBlank() }) { "proxy Activity class names must not be blank" }
    }

    @Synchronized
    fun allocate(
        instanceId: String,
        originPackageName: String,
        guestActivityClassName: String,
        launchMode: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): VirtualActivityRecord {
        val normalizedLaunchMode = normalizeLaunchMode(launchMode)
        val proxyClassName = selectProxyActivity(normalizedLaunchMode)
        val record = VirtualActivityRecord(
            token = UUID.randomUUID().toString(),
            instanceId = instanceId,
            originPackageName = originPackageName,
            guestActivityClassName = guestActivityClassName,
            proxyActivityClassName = proxyClassName,
            launchMode = normalizedLaunchMode,
            createdAtMs = nowMs
        )
        records[record.token] = record
        return record
    }

    @Synchronized
    fun registerExisting(existingRecords: List<VirtualActivityRecord>) {
        existingRecords
            .filter { it.state != VirtualActivityState.FINISHED && it.state != VirtualActivityState.DESTROYED }
            .forEach { record -> records[record.token] = record }
    }

    @Synchronized
    fun resolve(token: String): VirtualActivityRecord? = records[token]

    @Synchronized
    fun consume(token: String): VirtualActivityRecord? = records.remove(token)

    @Synchronized
    fun listRecords(): List<VirtualActivityRecord> = records.values.toList()

    private fun selectProxyActivity(launchMode: String?): String {
        val candidates = proxyActivityClassNames.filter { className ->
            normalizeLaunchMode(launchModeByClassName[className]) == launchMode
        }.ifEmpty { proxyActivityClassNames }
        val used = records.values.map { it.proxyActivityClassName }.toSet()
        return candidates.firstOrNull { it !in used }
            ?: candidates[records.size % candidates.size]
    }

    companion object {
        fun normalizeLaunchMode(launchMode: String?): String? = when (launchMode) {
            null, "", "standard" -> null
            "singleTop" -> "singleTop"
            "singleTask", "singleInstance", "singleInstancePerTask" -> "singleTask"
            else -> null
        }
    }
}
