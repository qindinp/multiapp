package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentAuthority
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.ResolvedIntentPathPattern
import com.multiapp.core.model.virtual.ResolvedIntentPathPatternType
import com.multiapp.core.model.virtual.VirtualMetaDataValue
import com.multiapp.core.model.virtual.VirtualMetaDataValueType
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualProviderPathPattern
import com.multiapp.core.model.virtual.VirtualProviderPathPatternType
import com.multiapp.core.model.virtual.VirtualProviderPathPermission
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

data class EngineRuntimeStateRecord(
    val instanceId: String,
    val hostPackageName: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val dataRoot: String,
    val profile: EngineProfile,
    val processSlot: String,
    val proxySlot: String,
    val evidenceSessionId: String,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processId: Int?,
    val runtimeProcessName: String?,
    val state: VirtualRuntimeState,
    val applicationLabel: String,
    val versionCode: Long,
    val versionName: String,
    val targetSdk: Int,
    val minSdk: Int,
    val sourceDir: String,
    val publicSourceDir: String,
    val splitSourceDirs: List<String>,
    val splitPublicSourceDirs: List<String>,
    val splitNames: List<String>,
    val isolatedSplits: Boolean,
    val packageDataDir: String,
    val nativeLibraryDir: String?,
    val applicationClassName: String?,
    val packageProcessName: String?,
    val taskAffinity: String?,
    val themeId: Int,
    val metaData: Map<String, String>,
    val typedMetaData: Map<String, VirtualMetaDataValue> = emptyMap(),
    val launcherActivityName: String?,
    val activities: List<ResolvedComponent>,
    val services: List<ResolvedComponent>,
    val receivers: List<ResolvedComponent>,
    val providers: List<ResolvedComponent>,
    val permissions: List<String>,
    val originCertSha256: String?,
    val signerSha256Digests: List<String> = emptyList(),
    val hasMultipleSigners: Boolean = false,
    val sourceSha256: String? = null,
    val splitSha256s: List<String> = emptyList()
) {
    fun toRuntime(): VirtualInstanceRuntime {
        val snapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            applicationLabel = applicationLabel,
            versionCode = versionCode,
            versionName = versionName,
            targetSdk = targetSdk,
            minSdk = minSdk,
            sourceDir = sourceDir,
            sourceSha256 = sourceSha256,
            publicSourceDir = publicSourceDir,
            splitSourceDirs = splitSourceDirs,
            splitSha256s = splitSha256s,
            splitPublicSourceDirs = splitPublicSourceDirs,
            splitNames = splitNames,
            isolatedSplits = isolatedSplits,
            dataDir = packageDataDir,
            nativeLibraryDir = nativeLibraryDir.normalizedOptionalManifestValue(),
            applicationClassName = applicationClassName.normalizedOptionalManifestValue(),
            processName = packageProcessName.normalizedOptionalManifestValue(),
            taskAffinity = taskAffinity.normalizedOptionalManifestValue(),
            themeId = themeId,
            metaData = metaData,
            typedMetaData = typedMetaData,
            launcherActivityName = launcherActivityName.normalizedOptionalManifestValue(),
            activities = activities.map(ResolvedComponent::normalizedManifestOptionals),
            services = services.map(ResolvedComponent::normalizedManifestOptionals),
            receivers = receivers.map(ResolvedComponent::normalizedManifestOptionals),
            providers = providers.map(ResolvedComponent::normalizedManifestOptionals),
            permissions = permissions,
            originCertSha256 = originCertSha256,
            signerSha256Digests = signerSha256Digests,
            hasMultipleSigners = hasMultipleSigners
        )
        return VirtualInstanceRuntime(
            instanceId = instanceId,
            hostPackageName = hostPackageName,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataRoot = dataRoot,
            packageSnapshot = snapshot,
            profile = profile,
            processSlot = processSlot,
            proxySlot = proxySlot,
            evidenceSessionId = evidenceSessionId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processId = processId,
            processName = runtimeProcessName,
            state = state
        )
    }

    companion object {
        fun from(runtime: VirtualInstanceRuntime): EngineRuntimeStateRecord {
            val snapshot = runtime.packageSnapshot
            return EngineRuntimeStateRecord(
                instanceId = runtime.instanceId,
                hostPackageName = runtime.hostPackageName,
                originPackageName = runtime.originPackageName,
                virtualPackageName = runtime.virtualPackageName,
                dataRoot = runtime.dataRoot,
                profile = runtime.profile,
                processSlot = runtime.processSlot,
                proxySlot = runtime.proxySlot,
                evidenceSessionId = runtime.evidenceSessionId,
                runtimeEpoch = runtime.runtimeEpoch,
                engineSessionId = runtime.engineSessionId,
                processId = runtime.processId,
                runtimeProcessName = runtime.processName,
                state = runtime.state,
                applicationLabel = snapshot.applicationLabel,
                versionCode = snapshot.versionCode,
                versionName = snapshot.versionName,
                targetSdk = snapshot.targetSdk,
                minSdk = snapshot.minSdk,
                sourceDir = snapshot.sourceDir,
                publicSourceDir = snapshot.publicSourceDir,
                splitSourceDirs = snapshot.splitSourceDirs,
                splitPublicSourceDirs = snapshot.splitPublicSourceDirs,
                splitNames = snapshot.splitNames,
                isolatedSplits = snapshot.isolatedSplits,
                packageDataDir = snapshot.dataDir,
                nativeLibraryDir = snapshot.nativeLibraryDir.normalizedOptionalManifestValue(),
                applicationClassName = snapshot.applicationClassName.normalizedOptionalManifestValue(),
                packageProcessName = snapshot.processName.normalizedOptionalManifestValue(),
                taskAffinity = snapshot.taskAffinity.normalizedOptionalManifestValue(),
                themeId = snapshot.themeId,
                metaData = snapshot.metaData,
                typedMetaData = snapshot.typedMetaData,
                launcherActivityName = snapshot.launcherActivityName.normalizedOptionalManifestValue(),
                activities = snapshot.activities.map(ResolvedComponent::normalizedManifestOptionals),
                services = snapshot.services.map(ResolvedComponent::normalizedManifestOptionals),
                receivers = snapshot.receivers.map(ResolvedComponent::normalizedManifestOptionals),
                providers = snapshot.providers.map(ResolvedComponent::normalizedManifestOptionals),
                permissions = snapshot.permissions,
                originCertSha256 = snapshot.originCertSha256,
                signerSha256Digests = snapshot.signerSha256Digests,
                hasMultipleSigners = snapshot.hasMultipleSigners,
                sourceSha256 = snapshot.sourceSha256,
                splitSha256s = snapshot.splitSha256s
            )
        }
    }
}

private fun String?.normalizedOptionalManifestValue(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private fun ResolvedComponent.normalizedManifestOptionals(): ResolvedComponent = copy(
    launchMode = launchMode.normalizedOptionalManifestValue(),
    processName = processName.normalizedOptionalManifestValue(),
    taskAffinity = taskAffinity.normalizedOptionalManifestValue(),
    screenOrientation = screenOrientation.normalizedOptionalManifestValue(),
    configChanges = configChanges.normalizedOptionalManifestValue(),
    permission = permission.normalizedOptionalManifestValue(),
    readPermission = readPermission.normalizedOptionalManifestValue(),
    writePermission = writePermission.normalizedOptionalManifestValue(),
    targetActivityName = targetActivityName.normalizedOptionalManifestValue()
)

interface EngineRuntimeStateStore {
    fun put(record: EngineRuntimeStateRecord)
    fun compareAndSet(expected: EngineRuntimeStateRecord, updated: EngineRuntimeStateRecord): Boolean
    fun putIfNewer(record: EngineRuntimeStateRecord): EngineRuntimeStateRecord {
        val current = get(record.instanceId)
        if (current != null && current.rejectsIdentityReplacement(record)) return current
        put(record)
        return record
    }
    fun get(instanceId: String): EngineRuntimeStateRecord?
    fun list(): List<EngineRuntimeStateRecord>
    fun remove(instanceId: String): Boolean
    fun removeIfEpoch(instanceId: String, runtimeEpoch: Long): Boolean {
        val current = get(instanceId) ?: return false
        if (current.runtimeEpoch != runtimeEpoch) return false
        return remove(instanceId)
    }
    fun clear()
}

object EngineRuntimeStateFiles {
    const val DEFAULT_FILE_NAME = "engine_runtime_state.properties"
}

class InMemoryEngineRuntimeStateStore : EngineRuntimeStateStore {
    private val records = linkedMapOf<String, EngineRuntimeStateRecord>()

    @Synchronized
    override fun put(record: EngineRuntimeStateRecord) {
        records[record.instanceId] = record
    }

    @Synchronized
    override fun compareAndSet(
        expected: EngineRuntimeStateRecord,
        updated: EngineRuntimeStateRecord
    ): Boolean {
        require(expected.instanceId == updated.instanceId) { "runtime CAS changed instanceId" }
        if (records[expected.instanceId] != expected) return false
        records[updated.instanceId] = updated
        return true
    }

    @Synchronized
    override fun putIfNewer(record: EngineRuntimeStateRecord): EngineRuntimeStateRecord {
        val current = records[record.instanceId]
        if (current != null && current.rejectsIdentityReplacement(record)) return current
        records[record.instanceId] = record
        return record
    }

    @Synchronized
    override fun get(instanceId: String): EngineRuntimeStateRecord? = records[instanceId]

    @Synchronized
    override fun list(): List<EngineRuntimeStateRecord> = records.values.toList()

    @Synchronized
    override fun remove(instanceId: String): Boolean = records.remove(instanceId) != null

    @Synchronized
    override fun removeIfEpoch(instanceId: String, runtimeEpoch: Long): Boolean {
        val current = records[instanceId] ?: return false
        if (current.runtimeEpoch != runtimeEpoch) return false
        records.remove(instanceId)
        return true
    }

    @Synchronized
    override fun clear() {
        records.clear()
    }
}

class FileBackedEngineRuntimeStateStore(
    private val file: File
) : EngineRuntimeStateStore {

    override fun put(record: EngineRuntimeStateRecord) = withFileLock {
        val current = load().associateBy { it.instanceId }.toMutableMap()
        current[record.instanceId] = record
        store(current.values.toList())
    }

    override fun compareAndSet(
        expected: EngineRuntimeStateRecord,
        updated: EngineRuntimeStateRecord
    ): Boolean = withFileLock {
        require(expected.instanceId == updated.instanceId) { "runtime CAS changed instanceId" }
        val current = load().associateBy { it.instanceId }.toMutableMap()
        if (current[expected.instanceId] != expected) return@withFileLock false
        current[updated.instanceId] = updated
        store(current.values.toList())
        true
    }

    override fun putIfNewer(record: EngineRuntimeStateRecord): EngineRuntimeStateRecord = withFileLock {
        val current = load().associateBy { it.instanceId }.toMutableMap()
        val existing = current[record.instanceId]
        if (existing != null && existing.rejectsIdentityReplacement(record)) {
            return@withFileLock existing
        }
        current[record.instanceId] = record
        store(current.values.toList())
        record
    }

    override fun get(instanceId: String): EngineRuntimeStateRecord? = withFileLock {
        load().firstOrNull { it.instanceId == instanceId }
    }

    override fun list(): List<EngineRuntimeStateRecord> = withFileLock { load() }

    override fun remove(instanceId: String): Boolean = withFileLock {
        val current = load()
        val retained = current.filterNot { it.instanceId == instanceId }
        if (retained.size == current.size) return@withFileLock false
        store(retained)
        true
    }

    override fun removeIfEpoch(instanceId: String, runtimeEpoch: Long): Boolean = withFileLock {
        val current = load()
        val target = current.firstOrNull { it.instanceId == instanceId } ?: return@withFileLock false
        if (target.runtimeEpoch != runtimeEpoch) return@withFileLock false
        store(current.filterNot { it.instanceId == instanceId })
        true
    }

    override fun clear() = withFileLock {
        if (file.exists()) file.delete()
        val tempFile = tempFile()
        if (tempFile.exists()) tempFile.delete()
        updateReadCache(emptyList())
    }

    private fun load(): List<EngineRuntimeStateRecord> {
        val fingerprint = currentFingerprint()
        val cache = readCache()
        if (cache.fingerprint == fingerprint) return cache.records
        if (!fingerprint.exists) {
            cache.fingerprint = fingerprint
            cache.records = emptyList()
            return emptyList()
        }
        val properties = Properties()
        file.inputStream().use { input -> properties.load(input) }
        val records = properties.stringPropertyNames()
            .asSequence()
            .mapNotNull { name -> name.substringBefore('.').takeIf { it.isNotBlank() } }
            .distinct()
            .sorted()
            .mapNotNull { instanceId -> decodeRecord(properties, instanceId) }
            .toList()
        cache.fingerprint = fingerprint
        cache.records = records
        return records
    }

    private fun store(records: List<EngineRuntimeStateRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        records.sortedBy { it.instanceId }.forEach { record ->
            require(!record.instanceId.hasUnsafeStorageChars()) { "unsafe instanceId for engine runtime state key" }
            val prefix = "${record.instanceId}."
            properties.setProperty(prefix + HOST_PACKAGE_NAME, record.hostPackageName)
            properties.setProperty(prefix + ORIGIN_PACKAGE_NAME, record.originPackageName)
            properties.setProperty(prefix + VIRTUAL_PACKAGE_NAME, record.virtualPackageName)
            properties.setProperty(prefix + DATA_ROOT, record.dataRoot)
            properties.setProperty(prefix + PROFILE, record.profile.name)
            properties.setProperty(prefix + PROCESS_SLOT, record.processSlot)
            properties.setProperty(prefix + PROXY_SLOT, record.proxySlot)
            properties.setProperty(prefix + EVIDENCE_SESSION_ID, record.evidenceSessionId)
            properties.setProperty(prefix + RUNTIME_EPOCH, record.runtimeEpoch.toString())
            properties.setProperty(prefix + ENGINE_SESSION_ID, record.engineSessionId)
            record.processId?.let { properties.setProperty(prefix + PROCESS_ID, it.toString()) }
            record.runtimeProcessName.normalizedOptionalManifestValue()
                ?.let { properties.setProperty(prefix + RUNTIME_PROCESS_NAME, it) }
            properties.setProperty(prefix + STATE, record.state.name)
            properties.setProperty(prefix + APPLICATION_LABEL, record.applicationLabel)
            properties.setProperty(prefix + VERSION_CODE, record.versionCode.toString())
            properties.setProperty(prefix + VERSION_NAME, record.versionName)
            properties.setProperty(prefix + TARGET_SDK, record.targetSdk.toString())
            properties.setProperty(prefix + MIN_SDK, record.minSdk.toString())
            properties.setProperty(prefix + SOURCE_DIR, record.sourceDir)
            record.sourceSha256?.let { properties.setProperty(prefix + SOURCE_SHA256, it) }
            properties.setProperty(prefix + PUBLIC_SOURCE_DIR, record.publicSourceDir)
            properties.setProperty(prefix + SPLIT_SOURCE_DIRS, record.splitSourceDirs.encodeStringList())
            properties.setProperty(prefix + SPLIT_SHA256S, record.splitSha256s.encodeStringList())
            properties.setProperty(prefix + SPLIT_PUBLIC_SOURCE_DIRS, record.splitPublicSourceDirs.encodeStringList())
            properties.setProperty(prefix + SPLIT_NAMES, record.splitNames.encodeStringList())
            properties.setProperty(prefix + ISOLATED_SPLITS, record.isolatedSplits.toString())
            properties.setProperty(prefix + PACKAGE_DATA_DIR, record.packageDataDir)
            record.nativeLibraryDir.normalizedOptionalManifestValue()
                ?.let { properties.setProperty(prefix + NATIVE_LIBRARY_DIR, it) }
            record.applicationClassName.normalizedOptionalManifestValue()
                ?.let { properties.setProperty(prefix + APPLICATION_CLASS_NAME, it) }
            record.packageProcessName.normalizedOptionalManifestValue()
                ?.let { properties.setProperty(prefix + PACKAGE_PROCESS_NAME, it) }
            record.taskAffinity.normalizedOptionalManifestValue()
                ?.let { properties.setProperty(prefix + TASK_AFFINITY, it) }
            properties.setProperty(prefix + THEME_ID, record.themeId.toString())
            properties.storeStringMap(prefix + META_DATA, record.metaData)
            properties.storeVirtualMetaData(prefix + TYPED_META_DATA, record.typedMetaData)
            record.launcherActivityName.normalizedOptionalManifestValue()
                ?.let { properties.setProperty(prefix + LAUNCHER_ACTIVITY_NAME, it) }
            properties.storeComponents(prefix + ACTIVITIES, record.activities)
            properties.storeComponents(prefix + SERVICES, record.services)
            properties.storeComponents(prefix + RECEIVERS, record.receivers)
            properties.storeComponents(prefix + PROVIDERS, record.providers)
            properties.setProperty(prefix + PERMISSIONS, record.permissions.encodeStringList())
            record.originCertSha256?.let { properties.setProperty(prefix + ORIGIN_CERT_SHA256, it) }
            properties.setProperty(prefix + SIGNER_SHA256_DIGESTS, record.signerSha256Digests.encodeStringList())
            properties.setProperty(prefix + HAS_MULTIPLE_SIGNERS, record.hasMultipleSigners.toString())
        }
        val tempFile = tempFile()
        try {
            FileOutputStream(tempFile).use { output ->
                properties.store(output, "MultiApp engine runtime state")
                output.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            updateReadCache(records)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun tempFile(): File = File(file.absolutePath + TEMP_SUFFIX)

    private fun <T> withFileLock(block: () -> T): T {
        file.parentFile?.mkdirs()
        val normalizedPath = normalizedPath()
        val monitor = FILE_MONITORS.computeIfAbsent(normalizedPath) { Any() }
        return synchronized(monitor) {
            val lockFile = File(file.absolutePath + LOCK_SUFFIX)
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                val lock = channel.lock()
                try {
                    block()
                } finally {
                    lock.release()
                }
            }
        }
    }

    private fun updateReadCache(records: List<EngineRuntimeStateRecord>) {
        readCache().apply {
            fingerprint = currentFingerprint()
            this.records = records.sortedBy { it.instanceId }
        }
    }

    private fun readCache(): RuntimeStateReadCache =
        FILE_READ_CACHES.computeIfAbsent(normalizedPath()) { RuntimeStateReadCache() }

    private fun normalizedPath(): String = file.absoluteFile.normalize().path

    private fun currentFingerprint(): RuntimeStateFileFingerprint {
        if (!file.isFile) return RuntimeStateFileFingerprint.MISSING
        return runCatching {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            RuntimeStateFileFingerprint(
                exists = true,
                size = attributes.size(),
                lastModifiedMs = attributes.lastModifiedTime().toMillis(),
                fileKey = attributes.fileKey()?.toString().orEmpty()
            )
        }.getOrElse {
            RuntimeStateFileFingerprint(
                exists = true,
                size = file.length(),
                lastModifiedMs = file.lastModified(),
                fileKey = ""
            )
        }
    }

    private fun decodeRecord(properties: Properties, instanceId: String): EngineRuntimeStateRecord? {
        val prefix = "$instanceId."
        return runCatching {
            EngineRuntimeStateRecord(
                instanceId = instanceId,
                hostPackageName = properties.required(prefix + HOST_PACKAGE_NAME),
                originPackageName = properties.required(prefix + ORIGIN_PACKAGE_NAME),
                virtualPackageName = properties.required(prefix + VIRTUAL_PACKAGE_NAME),
                dataRoot = properties.required(prefix + DATA_ROOT),
                profile = enumValueOf(properties.required(prefix + PROFILE)),
                processSlot = properties.required(prefix + PROCESS_SLOT),
                proxySlot = properties.required(prefix + PROXY_SLOT),
                evidenceSessionId = properties.required(prefix + EVIDENCE_SESSION_ID),
                runtimeEpoch = properties.required(prefix + RUNTIME_EPOCH).toLong(),
                engineSessionId = properties.required(prefix + ENGINE_SESSION_ID),
                processId = properties.getProperty(prefix + PROCESS_ID)?.toIntOrNull(),
                runtimeProcessName = properties.getProperty(prefix + RUNTIME_PROCESS_NAME)
                    .normalizedOptionalManifestValue(),
                state = enumValueOf(properties.required(prefix + STATE)),
                applicationLabel = properties.required(prefix + APPLICATION_LABEL),
                versionCode = properties.required(prefix + VERSION_CODE).toLong(),
                versionName = properties.required(prefix + VERSION_NAME),
                targetSdk = properties.required(prefix + TARGET_SDK).toInt(),
                minSdk = properties.required(prefix + MIN_SDK).toInt(),
                sourceDir = properties.required(prefix + SOURCE_DIR),
                sourceSha256 = properties.getProperty(prefix + SOURCE_SHA256)
                    .normalizedOptionalManifestValue(),
                publicSourceDir = properties.required(prefix + PUBLIC_SOURCE_DIR),
                splitSourceDirs = properties.getProperty(prefix + SPLIT_SOURCE_DIRS).decodeStringList(),
                splitSha256s = properties.getProperty(prefix + SPLIT_SHA256S).decodeStringList(),
                splitPublicSourceDirs = properties.getProperty(prefix + SPLIT_PUBLIC_SOURCE_DIRS).decodeStringList(),
                splitNames = properties.getProperty(prefix + SPLIT_NAMES).decodeStringList(),
                isolatedSplits = properties.getProperty(prefix + ISOLATED_SPLITS).toBoolean(),
                packageDataDir = properties.required(prefix + PACKAGE_DATA_DIR),
                nativeLibraryDir = properties.getProperty(prefix + NATIVE_LIBRARY_DIR)
                    .normalizedOptionalManifestValue(),
                applicationClassName = properties.getProperty(prefix + APPLICATION_CLASS_NAME)
                    .normalizedOptionalManifestValue(),
                packageProcessName = properties.getProperty(prefix + PACKAGE_PROCESS_NAME)
                    .normalizedOptionalManifestValue(),
                taskAffinity = properties.getProperty(prefix + TASK_AFFINITY)
                    .normalizedOptionalManifestValue(),
                themeId = properties.getProperty(prefix + THEME_ID).orEmpty().toIntOrNull() ?: 0,
                metaData = properties.decodeStringMap(prefix + META_DATA),
                typedMetaData = properties.decodeVirtualMetaData(prefix + TYPED_META_DATA),
                launcherActivityName = properties.getProperty(prefix + LAUNCHER_ACTIVITY_NAME)
                    .normalizedOptionalManifestValue(),
                activities = properties.decodeComponents(prefix + ACTIVITIES),
                services = properties.decodeComponents(prefix + SERVICES),
                receivers = properties.decodeComponents(prefix + RECEIVERS),
                providers = properties.decodeComponents(prefix + PROVIDERS),
                permissions = properties.getProperty(prefix + PERMISSIONS).decodeStringList(),
                originCertSha256 = properties.getProperty(prefix + ORIGIN_CERT_SHA256)
                    .normalizedOptionalManifestValue(),
                signerSha256Digests = properties.getProperty(prefix + SIGNER_SHA256_DIGESTS).decodeStringList(),
                hasMultipleSigners = properties.getProperty(prefix + HAS_MULTIPLE_SIGNERS).toBoolean()
            )
        }.getOrNull()
    }

    private fun String.hasUnsafeStorageChars(): Boolean =
        any { it == '\n' || it == '\r' || it == '.' || it.code < 0x20 }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() } ?: error("Missing engine runtime state property: $key")

    private fun List<String>.encodeStringList(): String =
        joinToString(LIST_SEPARATOR) { value -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) }

    private fun String?.decodeStringList(): List<String> {
        if (isNullOrBlank()) return emptyList()
        return split(LIST_SEPARATOR)
            .filter { it.isNotBlank() }
            .map { encoded -> String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }
    }

    private fun Properties.storeStringMap(prefix: String, values: Map<String, String>) {
        setProperty(prefix + COUNT, values.size.toString())
        values.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + MAP_KEY, entry.key)
            setProperty(itemPrefix + MAP_VALUE, entry.value)
        }
    }

    private fun Properties.storeVirtualMetaData(
        prefix: String,
        values: Map<String, VirtualMetaDataValue>
    ) {
        setProperty(prefix + COUNT, values.size.toString())
        values.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + MAP_KEY, entry.key)
            setProperty(itemPrefix + MAP_TYPE, entry.value.type.name)
            setProperty(itemPrefix + MAP_VALUE, entry.value.encodedValue)
        }
    }

    private fun Properties.decodeVirtualMetaData(prefix: String): Map<String, VirtualMetaDataValue> {
        val count = getProperty(prefix + COUNT).orEmpty().toIntOrNull() ?: return emptyMap()
        return (0 until count).mapNotNull { index ->
            val itemPrefix = "$prefix.$index."
            val key = getProperty(itemPrefix + MAP_KEY) ?: return@mapNotNull null
            val type = getProperty(itemPrefix + MAP_TYPE)
                ?.let { value -> runCatching { enumValueOf<VirtualMetaDataValueType>(value) }.getOrNull() }
                ?: return@mapNotNull null
            val value = getProperty(itemPrefix + MAP_VALUE) ?: return@mapNotNull null
            key to VirtualMetaDataValue(type, value)
        }.toMap()
    }

    private fun Properties.decodeStringMap(prefix: String): Map<String, String> {
        val count = getProperty(prefix + COUNT).orEmpty().toIntOrNull() ?: return emptyMap()
        return (0 until count)
            .mapNotNull { index ->
                val itemPrefix = "$prefix.$index."
                val key = getProperty(itemPrefix + MAP_KEY) ?: return@mapNotNull null
                val value = getProperty(itemPrefix + MAP_VALUE) ?: ""
                key to value
            }
            .toMap()
    }

    private fun Properties.storeComponents(prefix: String, components: List<ResolvedComponent>) {
        setProperty(prefix + COUNT, components.size.toString())
        components.forEachIndexed { index, component ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + COMPONENT_NAME, component.name)
            setProperty(itemPrefix + COMPONENT_EXPORTED, component.exported.toString())
            setProperty(itemPrefix + COMPONENT_INTENT_FILTERS, component.intentFilters.encodeStringList())
            storeResolvedIntentFilters(itemPrefix + COMPONENT_RESOLVED_INTENT_FILTERS, component.resolvedIntentFilters)
            setProperty(itemPrefix + COMPONENT_AUTHORITIES, component.authorities.encodeStringList())
            component.launchMode.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_LAUNCH_MODE, it) }
            component.processName.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_PROCESS_NAME, it) }
            component.taskAffinity.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_TASK_AFFINITY, it) }
            setProperty(itemPrefix + COMPONENT_THEME_ID, component.themeId.toString())
            component.screenOrientation.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_SCREEN_ORIENTATION, it) }
            component.configChanges.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_CONFIG_CHANGES, it) }
            component.permission.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_PERMISSION, it) }
            component.readPermission.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_READ_PERMISSION, it) }
            component.writePermission.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_WRITE_PERMISSION, it) }
            setProperty(itemPrefix + COMPONENT_GRANT_URI_PERMISSIONS, component.grantUriPermissions.toString())
            storeProviderPathPermissions(itemPrefix + COMPONENT_PATH_PERMISSIONS, component.pathPermissions)
            storeProviderPathPatterns(itemPrefix + COMPONENT_URI_PERMISSION_PATTERNS, component.uriPermissionPatterns)
            storeStringMap(itemPrefix + COMPONENT_META_DATA, component.metaData)
            storeVirtualMetaData(itemPrefix + COMPONENT_TYPED_META_DATA, component.typedMetaData)
            component.targetActivityName.normalizedOptionalManifestValue()
                ?.let { setProperty(itemPrefix + COMPONENT_TARGET_ACTIVITY_NAME, it) }
        }
    }

    private fun Properties.decodeComponents(prefix: String): List<ResolvedComponent> {
        val count = getProperty(prefix + COUNT).orEmpty().toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val itemPrefix = "$prefix.$index."
            val name = getProperty(itemPrefix + COMPONENT_NAME) ?: return@mapNotNull null
            ResolvedComponent(
                name = name,
                exported = getProperty(itemPrefix + COMPONENT_EXPORTED).toBoolean(),
                intentFilters = getProperty(itemPrefix + COMPONENT_INTENT_FILTERS).decodeStringList(),
                resolvedIntentFilters = decodeResolvedIntentFilters(itemPrefix + COMPONENT_RESOLVED_INTENT_FILTERS),
                authorities = getProperty(itemPrefix + COMPONENT_AUTHORITIES).decodeStringList(),
                launchMode = getProperty(itemPrefix + COMPONENT_LAUNCH_MODE)
                    .normalizedOptionalManifestValue(),
                processName = getProperty(itemPrefix + COMPONENT_PROCESS_NAME)
                    .normalizedOptionalManifestValue(),
                taskAffinity = getProperty(itemPrefix + COMPONENT_TASK_AFFINITY)
                    .normalizedOptionalManifestValue(),
                themeId = getProperty(itemPrefix + COMPONENT_THEME_ID).orEmpty().toIntOrNull() ?: 0,
                screenOrientation = getProperty(itemPrefix + COMPONENT_SCREEN_ORIENTATION)
                    .normalizedOptionalManifestValue(),
                configChanges = getProperty(itemPrefix + COMPONENT_CONFIG_CHANGES)
                    .normalizedOptionalManifestValue(),
                permission = getProperty(itemPrefix + COMPONENT_PERMISSION)
                    .normalizedOptionalManifestValue(),
                readPermission = getProperty(itemPrefix + COMPONENT_READ_PERMISSION)
                    .normalizedOptionalManifestValue(),
                writePermission = getProperty(itemPrefix + COMPONENT_WRITE_PERMISSION)
                    .normalizedOptionalManifestValue(),
                grantUriPermissions = getProperty(itemPrefix + COMPONENT_GRANT_URI_PERMISSIONS).toBoolean(),
                pathPermissions = decodeProviderPathPermissions(itemPrefix + COMPONENT_PATH_PERMISSIONS),
                uriPermissionPatterns = decodeProviderPathPatterns(itemPrefix + COMPONENT_URI_PERMISSION_PATTERNS),
                metaData = decodeStringMap(itemPrefix + COMPONENT_META_DATA),
                typedMetaData = decodeVirtualMetaData(itemPrefix + COMPONENT_TYPED_META_DATA),
                targetActivityName = getProperty(itemPrefix + COMPONENT_TARGET_ACTIVITY_NAME)
                    .normalizedOptionalManifestValue()
            )
        }
    }

    private fun Properties.storeProviderPathPatterns(
        prefix: String,
        patterns: List<VirtualProviderPathPattern>
    ) {
        setProperty(prefix + COUNT, patterns.size.toString())
        patterns.forEachIndexed { index, pattern ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + PATH_PATTERN_PATH, pattern.path)
            setProperty(itemPrefix + PATH_PATTERN_TYPE, pattern.type.name)
        }
    }

    private fun Properties.decodeProviderPathPatterns(prefix: String): List<VirtualProviderPathPattern> {
        val count = getProperty(prefix + COUNT).orEmpty().toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val itemPrefix = "$prefix.$index."
            val path = getProperty(itemPrefix + PATH_PATTERN_PATH)?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val type = getProperty(itemPrefix + PATH_PATTERN_TYPE)
                ?.let { runCatching { enumValueOf<VirtualProviderPathPatternType>(it) }.getOrNull() }
                ?: return@mapNotNull null
            VirtualProviderPathPattern(path, type)
        }
    }

    private fun Properties.storeProviderPathPermissions(
        prefix: String,
        permissions: List<VirtualProviderPathPermission>
    ) {
        setProperty(prefix + COUNT, permissions.size.toString())
        permissions.forEachIndexed { index, permission ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + PATH_PATTERN_PATH, permission.pattern.path)
            setProperty(itemPrefix + PATH_PATTERN_TYPE, permission.pattern.type.name)
            permission.readPermission?.let { setProperty(itemPrefix + PATH_READ_PERMISSION, it) }
            permission.writePermission?.let { setProperty(itemPrefix + PATH_WRITE_PERMISSION, it) }
        }
    }

    private fun Properties.decodeProviderPathPermissions(prefix: String): List<VirtualProviderPathPermission> {
        val count = getProperty(prefix + COUNT).orEmpty().toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val itemPrefix = "$prefix.$index."
            val path = getProperty(itemPrefix + PATH_PATTERN_PATH)?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val type = getProperty(itemPrefix + PATH_PATTERN_TYPE)
                ?.let { runCatching { enumValueOf<VirtualProviderPathPatternType>(it) }.getOrNull() }
                ?: return@mapNotNull null
            val readPermission = getProperty(itemPrefix + PATH_READ_PERMISSION)
            val writePermission = getProperty(itemPrefix + PATH_WRITE_PERMISSION)
            if (readPermission == null && writePermission == null) return@mapNotNull null
            VirtualProviderPathPermission(
                pattern = VirtualProviderPathPattern(path, type),
                readPermission = readPermission,
                writePermission = writePermission
            )
        }
    }

    private fun Properties.storeResolvedIntentFilters(prefix: String, filters: List<ResolvedIntentFilter>) {
        setProperty(prefix + COUNT, filters.size.toString())
        filters.forEachIndexed { index, filter ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + FILTER_ACTIONS, filter.actions.encodeStringList())
            setProperty(itemPrefix + FILTER_CATEGORIES, filter.categories.encodeStringList())
            setProperty(itemPrefix + FILTER_DATA_SCHEMES, filter.dataSchemes.encodeStringList())
            setProperty(itemPrefix + FILTER_DATA_MIME_TYPES, filter.dataMimeTypes.encodeStringList())
            setProperty(itemPrefix + FILTER_DATA_AUTHORITIES, filter.dataAuthorities.encodeStringList())
            setProperty(itemPrefix + FILTER_DATA_PATHS, filter.dataPaths.encodeStringList())
            storeIntentAuthorities(itemPrefix + FILTER_AUTHORITY_ENTRIES, filter.authorityEntries)
            storeIntentPathPatterns(itemPrefix + FILTER_PATH_PATTERNS, filter.pathPatterns)
            setProperty(itemPrefix + FILTER_PRIORITY, filter.priority.toString())
        }
    }

    private fun Properties.decodeResolvedIntentFilters(prefix: String): List<ResolvedIntentFilter> {
        val count = getProperty(prefix + COUNT).orEmpty().toIntOrNull() ?: return emptyList()
        return (0 until count).map { index ->
            val itemPrefix = "$prefix.$index."
            ResolvedIntentFilter(
                actions = getProperty(itemPrefix + FILTER_ACTIONS).decodeStringList(),
                categories = getProperty(itemPrefix + FILTER_CATEGORIES).decodeStringList(),
                dataSchemes = getProperty(itemPrefix + FILTER_DATA_SCHEMES).decodeStringList(),
                dataMimeTypes = getProperty(itemPrefix + FILTER_DATA_MIME_TYPES).decodeStringList(),
                dataAuthorities = getProperty(itemPrefix + FILTER_DATA_AUTHORITIES).decodeStringList(),
                dataPaths = getProperty(itemPrefix + FILTER_DATA_PATHS).decodeStringList(),
                priority = getProperty(itemPrefix + FILTER_PRIORITY).orEmpty().toIntOrNull() ?: 0,
                authorityEntries = decodeIntentAuthorities(itemPrefix + FILTER_AUTHORITY_ENTRIES),
                pathPatterns = decodeIntentPathPatterns(itemPrefix + FILTER_PATH_PATTERNS)
            )
        }
    }

    private fun Properties.storeIntentAuthorities(
        prefix: String,
        authorities: List<ResolvedIntentAuthority>
    ) {
        setProperty(prefix + COUNT, authorities.size.toString())
        authorities.forEachIndexed { index, authority ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + FILTER_AUTHORITY_HOST, authority.host)
            setProperty(
                itemPrefix + FILTER_AUTHORITY_PORT,
                (authority.port ?: NO_AUTHORITY_PORT).toString()
            )
        }
    }

    private fun Properties.decodeIntentAuthorities(prefix: String): List<ResolvedIntentAuthority> {
        val encodedCount = getProperty(prefix + COUNT) ?: return emptyList()
        val count = encodedCount.toIntOrNull()
            ?.takeIf { it in 0..MAX_PERSISTED_FILTER_VALUES }
            ?: error("Invalid persisted intent-filter authority count")
        return (0 until count).map { index ->
            val itemPrefix = "$prefix.$index."
            val host = required(itemPrefix + FILTER_AUTHORITY_HOST)
            val encodedPort = getProperty(itemPrefix + FILTER_AUTHORITY_PORT)
                ?.toIntOrNull()
                ?.takeIf { it == NO_AUTHORITY_PORT || it >= 0 }
                ?: error("Invalid persisted intent-filter authority port")
            ResolvedIntentAuthority(
                host = host,
                port = encodedPort.takeUnless { it == NO_AUTHORITY_PORT }
            )
        }
    }

    private fun Properties.storeIntentPathPatterns(
        prefix: String,
        patterns: List<ResolvedIntentPathPattern>
    ) {
        setProperty(prefix + COUNT, patterns.size.toString())
        patterns.forEachIndexed { index, pattern ->
            val itemPrefix = "$prefix.$index."
            setProperty(itemPrefix + FILTER_PATH_PATTERN_VALUE, pattern.path)
            setProperty(itemPrefix + FILTER_PATH_PATTERN_TYPE, pattern.type.name)
        }
    }

    private fun Properties.decodeIntentPathPatterns(prefix: String): List<ResolvedIntentPathPattern> {
        val encodedCount = getProperty(prefix + COUNT) ?: return emptyList()
        val count = encodedCount.toIntOrNull()
            ?.takeIf { it in 0..MAX_PERSISTED_FILTER_VALUES }
            ?: error("Invalid persisted intent-filter path count")
        return (0 until count).map { index ->
            val itemPrefix = "$prefix.$index."
            val path = getProperty(itemPrefix + FILTER_PATH_PATTERN_VALUE)
                ?.takeIf { it.isNotEmpty() }
                ?: error("Invalid persisted intent-filter path")
            val type = getProperty(itemPrefix + FILTER_PATH_PATTERN_TYPE)
                ?.let { encoded -> enumValueOf<ResolvedIntentPathPatternType>(encoded) }
                ?: error("Missing persisted intent-filter path type")
            ResolvedIntentPathPattern(path, type)
        }
    }

    companion object {
        private const val LOCK_SUFFIX = ".lock"
        private const val TEMP_SUFFIX = ".tmp"
        private val FILE_MONITORS = ConcurrentHashMap<String, Any>()
        private val FILE_READ_CACHES = ConcurrentHashMap<String, RuntimeStateReadCache>()
        private const val HOST_PACKAGE_NAME = "hostPackageName"
        private const val ORIGIN_PACKAGE_NAME = "originPackageName"
        private const val VIRTUAL_PACKAGE_NAME = "virtualPackageName"
        private const val DATA_ROOT = "dataRoot"
        private const val PROFILE = "profile"
        private const val PROCESS_SLOT = "processSlot"
        private const val PROXY_SLOT = "proxySlot"
        private const val EVIDENCE_SESSION_ID = "evidenceSessionId"
        private const val RUNTIME_EPOCH = "runtimeEpoch"
        private const val ENGINE_SESSION_ID = "engineSessionId"
        private const val PROCESS_ID = "processId"
        private const val RUNTIME_PROCESS_NAME = "runtimeProcessName"
        private const val STATE = "state"
        private const val APPLICATION_LABEL = "applicationLabel"
        private const val VERSION_CODE = "versionCode"
        private const val VERSION_NAME = "versionName"
        private const val TARGET_SDK = "targetSdk"
        private const val MIN_SDK = "minSdk"
        private const val SOURCE_DIR = "sourceDir"
        private const val SOURCE_SHA256 = "sourceSha256"
        private const val PUBLIC_SOURCE_DIR = "publicSourceDir"
        private const val SPLIT_SOURCE_DIRS = "splitSourceDirs"
        private const val SPLIT_SHA256S = "splitSha256s"
        private const val SPLIT_PUBLIC_SOURCE_DIRS = "splitPublicSourceDirs"
        private const val SPLIT_NAMES = "splitNames"
        private const val ISOLATED_SPLITS = "isolatedSplits"
        private const val PACKAGE_DATA_DIR = "packageDataDir"
        private const val NATIVE_LIBRARY_DIR = "nativeLibraryDir"
        private const val APPLICATION_CLASS_NAME = "applicationClassName"
        private const val PACKAGE_PROCESS_NAME = "packageProcessName"
        private const val TASK_AFFINITY = "taskAffinity"
        private const val THEME_ID = "themeId"
        private const val META_DATA = "metaData"
        private const val TYPED_META_DATA = "typedMetaData"
        private const val LAUNCHER_ACTIVITY_NAME = "launcherActivityName"
        private const val ACTIVITIES = "activities"
        private const val SERVICES = "services"
        private const val RECEIVERS = "receivers"
        private const val PROVIDERS = "providers"
        private const val PERMISSIONS = "permissions"
        private const val ORIGIN_CERT_SHA256 = "originCertSha256"
        private const val SIGNER_SHA256_DIGESTS = "signerSha256Digests"
        private const val HAS_MULTIPLE_SIGNERS = "hasMultipleSigners"
        private const val COUNT = ".count"
        private const val MAP_KEY = "key"
        private const val MAP_VALUE = "value"
        private const val MAP_TYPE = "type"
        private const val COMPONENT_NAME = "name"
        private const val COMPONENT_EXPORTED = "exported"
        private const val COMPONENT_INTENT_FILTERS = "intentFilters"
        private const val COMPONENT_RESOLVED_INTENT_FILTERS = "resolvedIntentFilters"
        private const val COMPONENT_AUTHORITIES = "authorities"
        private const val COMPONENT_LAUNCH_MODE = "launchMode"
        private const val COMPONENT_PROCESS_NAME = "processName"
        private const val COMPONENT_TASK_AFFINITY = "taskAffinity"
        private const val COMPONENT_THEME_ID = "themeId"
        private const val COMPONENT_SCREEN_ORIENTATION = "screenOrientation"
        private const val COMPONENT_CONFIG_CHANGES = "configChanges"
        private const val COMPONENT_PERMISSION = "permission"
        private const val COMPONENT_READ_PERMISSION = "readPermission"
        private const val COMPONENT_WRITE_PERMISSION = "writePermission"
        private const val COMPONENT_GRANT_URI_PERMISSIONS = "grantUriPermissions"
        private const val COMPONENT_PATH_PERMISSIONS = "pathPermissions"
        private const val COMPONENT_URI_PERMISSION_PATTERNS = "uriPermissionPatterns"
        private const val COMPONENT_META_DATA = "metaData"
        private const val COMPONENT_TYPED_META_DATA = "typedMetaData"
        private const val COMPONENT_TARGET_ACTIVITY_NAME = "targetActivityName"
        private const val FILTER_ACTIONS = "actions"
        private const val FILTER_CATEGORIES = "categories"
        private const val FILTER_DATA_SCHEMES = "dataSchemes"
        private const val FILTER_DATA_MIME_TYPES = "dataMimeTypes"
        private const val FILTER_DATA_AUTHORITIES = "dataAuthorities"
        private const val FILTER_DATA_PATHS = "dataPaths"
        private const val FILTER_AUTHORITY_ENTRIES = "authorityEntries"
        private const val FILTER_AUTHORITY_HOST = "host"
        private const val FILTER_AUTHORITY_PORT = "port"
        private const val FILTER_PATH_PATTERNS = "pathPatterns"
        private const val FILTER_PATH_PATTERN_VALUE = "value"
        private const val FILTER_PATH_PATTERN_TYPE = "type"
        private const val FILTER_PRIORITY = "priority"
        private const val PATH_PATTERN_PATH = "path"
        private const val PATH_PATTERN_TYPE = "type"
        private const val PATH_READ_PERMISSION = "readPermission"
        private const val PATH_WRITE_PERMISSION = "writePermission"
        private const val LIST_SEPARATOR = ","
        private const val NO_AUTHORITY_PORT = -1
        private const val MAX_PERSISTED_FILTER_VALUES = 256
    }
}

private fun EngineRuntimeStateRecord.rejectsIdentityReplacement(
    candidate: EngineRuntimeStateRecord
): Boolean = runtimeEpoch > candidate.runtimeEpoch ||
    (runtimeEpoch == candidate.runtimeEpoch && engineSessionId != candidate.engineSessionId)

private data class RuntimeStateFileFingerprint(
    val exists: Boolean,
    val size: Long,
    val lastModifiedMs: Long,
    val fileKey: String
) {
    companion object {
        val MISSING = RuntimeStateFileFingerprint(
            exists = false,
            size = 0L,
            lastModifiedMs = 0L,
            fileKey = ""
        )
    }
}

private class RuntimeStateReadCache {
    var fingerprint: RuntimeStateFileFingerprint? = null
    var records: List<EngineRuntimeStateRecord> = emptyList()
}
