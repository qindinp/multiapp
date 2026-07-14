package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

object EnginePackageEnabledStates {
    const val DEFAULT = 0
    const val ENABLED = 1
    const val DISABLED = 2
    const val DISABLED_USER = 3
    const val DISABLED_UNTIL_USED = 4

    fun isValidApplicationState(state: Int): Boolean = state in DEFAULT..DISABLED_UNTIL_USED

    fun isValidComponentState(state: Int): Boolean = state in DEFAULT..DISABLED
}

enum class EnginePackageEnabledStateTarget {
    APPLICATION,
    COMPONENT
}

data class EnginePackageComponentKey(
    val type: VirtualPackageComponentType,
    val className: String
) {
    init {
        require(isSafeNormalizedComponentClassName(className)) {
            "invalid normalized component className"
        }
    }
}

data class EnginePackageGenerationIdentity(
    val instanceId: String,
    val fingerprint: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(fingerprint.matches(SHA_256_PATTERN)) { "fingerprint must be lowercase SHA-256" }
    }
}

data class EnginePackageEnabledStateRecord(
    val generation: EnginePackageGenerationIdentity,
    val applicationState: Int = EnginePackageEnabledStates.DEFAULT,
    val componentStates: Map<EnginePackageComponentKey, Int> = emptyMap()
) {
    init {
        require(EnginePackageEnabledStates.isValidApplicationState(applicationState)) {
            "invalid application enabled state: $applicationState"
        }
        require(componentStates.values.all { state ->
            state != EnginePackageEnabledStates.DEFAULT &&
                EnginePackageEnabledStates.isValidComponentState(state)
        }) {
            "component state records must contain only non-default AOSP component states"
        }
    }

    val overrideCount: Int
        get() = componentStates.size +
            if (applicationState == EnginePackageEnabledStates.DEFAULT) 0 else 1
}

data class EnginePackageEnabledStateMutation(
    val previousState: Int,
    val currentState: Int,
    val changed: Boolean
)

data class VirtualPackageEnabledStateResult(
    val instanceId: String,
    val target: EnginePackageEnabledStateTarget,
    val componentType: VirtualPackageComponentType? = null,
    val className: String? = null,
    val enabledState: Int? = null,
    val verdict: EngineResultStatus,
    val found: Boolean,
    val changed: Boolean = false,
    val authorityIdentity: EngineProcessClientIdentity? = null,
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
        when (target) {
            EnginePackageEnabledStateTarget.APPLICATION -> {
                require(componentType == null) { "application state must not have a component type" }
                require(className == null) { "application state must not have a component className" }
            }

            EnginePackageEnabledStateTarget.COMPONENT -> {
                require((componentType == null) == (className == null)) {
                    "component type and className must be present together"
                }
            }
        }
        if (verdict == EngineResultStatus.PASS) {
            val state = requireNotNull(enabledState) { "successful enabled-state result requires state" }
            require(
                when (target) {
                    EnginePackageEnabledStateTarget.APPLICATION ->
                        EnginePackageEnabledStates.isValidApplicationState(state)
                    EnginePackageEnabledStateTarget.COMPONENT ->
                        EnginePackageEnabledStates.isValidComponentState(state)
                }
            ) { "successful enabled-state result contains invalid state" }
            require(found) { "successful enabled-state result must be found" }
        }
    }
}

interface EnginePackageEnabledStateStore {
    fun read(generation: EnginePackageGenerationIdentity): EnginePackageEnabledStateRecord?

    fun setApplicationState(
        generation: EnginePackageGenerationIdentity,
        state: Int
    ): EnginePackageEnabledStateMutation

    fun setComponentState(
        generation: EnginePackageGenerationIdentity,
        key: EnginePackageComponentKey,
        state: Int
    ): EnginePackageEnabledStateMutation

    fun clearInstance(instanceId: String): Int

    fun clearGeneration(generation: EnginePackageGenerationIdentity): Int

    fun reconcile(
        validInstanceIds: Set<String>,
        currentGenerations: Map<String, EnginePackageGenerationIdentity>
    ): Int
}

object EnginePackageEnabledStateFiles {
    const val DEFAULT_FILE_NAME = "engine_package_enabled_states.properties"
}

class InMemoryEnginePackageEnabledStateStore : EnginePackageEnabledStateStore {
    private val records = linkedMapOf<String, EnginePackageEnabledStateRecord>()

    @Synchronized
    override fun read(generation: EnginePackageGenerationIdentity): EnginePackageEnabledStateRecord? =
        records[generation.instanceId]?.takeIf { record -> record.generation == generation }

    @Synchronized
    override fun setApplicationState(
        generation: EnginePackageGenerationIdentity,
        state: Int
    ): EnginePackageEnabledStateMutation = records.mutateApplicationState(generation, state)

    @Synchronized
    override fun setComponentState(
        generation: EnginePackageGenerationIdentity,
        key: EnginePackageComponentKey,
        state: Int
    ): EnginePackageEnabledStateMutation = records.mutateComponentState(generation, key, state)

    @Synchronized
    override fun clearInstance(instanceId: String): Int =
        records.remove(instanceId)?.overrideCount ?: 0

    @Synchronized
    override fun clearGeneration(generation: EnginePackageGenerationIdentity): Int {
        val current = records[generation.instanceId]
            ?.takeIf { record -> record.generation == generation }
            ?: return 0
        records.remove(generation.instanceId)
        return current.overrideCount
    }

    @Synchronized
    override fun reconcile(
        validInstanceIds: Set<String>,
        currentGenerations: Map<String, EnginePackageGenerationIdentity>
    ): Int = records.reconcileRecords(validInstanceIds, currentGenerations)
}

class FileBackedEnginePackageEnabledStateStore(
    private val file: File
) : EnginePackageEnabledStateStore {
    override fun read(generation: EnginePackageGenerationIdentity): EnginePackageEnabledStateRecord? =
        withFileLock {
            readRecords()[generation.instanceId]
                ?.takeIf { record -> record.generation == generation }
        }

    override fun setApplicationState(
        generation: EnginePackageGenerationIdentity,
        state: Int
    ): EnginePackageEnabledStateMutation = withFileLock {
        val records = readRecords()
        val mutation = records.mutateApplicationState(generation, state)
        writeRecords(records.values.toList())
        mutation
    }

    override fun setComponentState(
        generation: EnginePackageGenerationIdentity,
        key: EnginePackageComponentKey,
        state: Int
    ): EnginePackageEnabledStateMutation = withFileLock {
        val records = readRecords()
        val mutation = records.mutateComponentState(generation, key, state)
        writeRecords(records.values.toList())
        mutation
    }

    override fun clearInstance(instanceId: String): Int = withFileLock {
        val records = readRecords()
        val removed = records.remove(instanceId)?.overrideCount ?: 0
        if (removed > 0) writeRecords(records.values.toList())
        removed
    }

    override fun clearGeneration(generation: EnginePackageGenerationIdentity): Int = withFileLock {
        val records = readRecords()
        val current = records[generation.instanceId]
            ?.takeIf { record -> record.generation == generation }
            ?: return@withFileLock 0
        records.remove(generation.instanceId)
        writeRecords(records.values.toList())
        current.overrideCount
    }

    override fun reconcile(
        validInstanceIds: Set<String>,
        currentGenerations: Map<String, EnginePackageGenerationIdentity>
    ): Int = withFileLock {
        val records = readRecords()
        val removed = records.reconcileRecords(validInstanceIds, currentGenerations)
        if (removed > 0) writeRecords(records.values.toList())
        removed
    }

    private fun readRecords(): LinkedHashMap<String, EnginePackageEnabledStateRecord> {
        if (!file.isFile || file.length() > MAX_STATE_FILE_BYTES) return linkedMapOf()
        return runCatching {
            val properties = Properties()
            file.inputStream().use(properties::load)
            check(properties.required(SCHEMA_VERSION).toInt() == CURRENT_SCHEMA_VERSION) {
                "unsupported package enabled-state schema"
            }
            val recordCount = properties.required(RECORD_COUNT).toInt()
            check(recordCount in 0..MAX_RECORD_COUNT) { "invalid package enabled-state record count" }
            val expectedFields = linkedSetOf(SCHEMA_VERSION, RECORD_COUNT)
            val records = linkedMapOf<String, EnginePackageEnabledStateRecord>()
            repeat(recordCount) { index ->
                val prefix = "$RECORD_PREFIX.$index."
                val instanceIdKey = prefix + INSTANCE_ID
                val generationKey = prefix + GENERATION_FINGERPRINT
                val applicationStateKey = prefix + APPLICATION_STATE
                val componentCountKey = prefix + COMPONENT_COUNT
                expectedFields += setOf(
                    instanceIdKey,
                    generationKey,
                    applicationStateKey,
                    componentCountKey
                )
                val instanceId = properties.required(instanceIdKey)
                check(isSafeStorageIdentity(instanceId)) { "invalid package enabled-state instanceId" }
                val generation = EnginePackageGenerationIdentity(
                    instanceId = instanceId,
                    fingerprint = properties.required(generationKey)
                )
                val applicationState = properties.required(applicationStateKey).toInt()
                val componentCount = properties.required(componentCountKey).toInt()
                check(componentCount in 0..MAX_COMPONENT_RECORD_COUNT) {
                    "invalid package enabled-state component count"
                }
                val componentStates = linkedMapOf<EnginePackageComponentKey, Int>()
                repeat(componentCount) { componentIndex ->
                    val componentPrefix = "$prefix$COMPONENT_PREFIX.$componentIndex."
                    val typeKey = componentPrefix + COMPONENT_TYPE
                    val classNameKey = componentPrefix + COMPONENT_CLASS_NAME
                    val stateKey = componentPrefix + COMPONENT_STATE
                    expectedFields += setOf(typeKey, classNameKey, stateKey)
                    val key = EnginePackageComponentKey(
                        type = VirtualPackageComponentType.valueOf(properties.required(typeKey)),
                        className = properties.required(classNameKey)
                    )
                    val state = properties.required(stateKey).toInt()
                    check(state != EnginePackageEnabledStates.DEFAULT) {
                        "default component states must not be persisted"
                    }
                    check(EnginePackageEnabledStates.isValidComponentState(state)) {
                        "invalid persisted component enabled state"
                    }
                    check(componentStates.put(key, state) == null) {
                        "duplicate package enabled-state component key"
                    }
                }
                val record = EnginePackageEnabledStateRecord(
                    generation = generation,
                    applicationState = applicationState,
                    componentStates = componentStates
                )
                check(record.overrideCount > 0) { "empty package enabled-state record" }
                check(records.put(instanceId, record) == null) {
                    "duplicate package enabled-state instanceId"
                }
            }
            check(properties.stringPropertyNames() == expectedFields) {
                "unexpected package enabled-state fields"
            }
            records
        }.getOrElse { linkedMapOf() }
    }

    private fun writeRecords(records: List<EnginePackageEnabledStateRecord>) {
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION.toString())
            setProperty(RECORD_COUNT, records.size.toString())
            records.sortedBy { record -> record.generation.instanceId }
                .forEachIndexed { index, record ->
                    val prefix = "$RECORD_PREFIX.$index."
                    setProperty(prefix + INSTANCE_ID, record.generation.instanceId)
                    setProperty(prefix + GENERATION_FINGERPRINT, record.generation.fingerprint)
                    setProperty(prefix + APPLICATION_STATE, record.applicationState.toString())
                    val components = record.componentStates.entries.sortedWith(
                        compareBy<Map.Entry<EnginePackageComponentKey, Int>>(
                            { entry -> entry.key.type.name },
                            { entry -> entry.key.className }
                        )
                    )
                    setProperty(prefix + COMPONENT_COUNT, components.size.toString())
                    components.forEachIndexed { componentIndex, entry ->
                        val componentPrefix = "$prefix$COMPONENT_PREFIX.$componentIndex."
                        setProperty(componentPrefix + COMPONENT_TYPE, entry.key.type.name)
                        setProperty(componentPrefix + COMPONENT_CLASS_NAME, entry.key.className)
                        setProperty(componentPrefix + COMPONENT_STATE, entry.value.toString())
                    }
                }
        }
        val tempFile = File(file.absolutePath + TEMP_SUFFIX)
        try {
            FileOutputStream(tempFile).use { output ->
                properties.store(output, "MultiApp engine package enabled states")
                output.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun <T> withFileLock(block: () -> T): T {
        file.parentFile?.mkdirs()
        val path = file.absoluteFile.normalize().path
        val monitor = FILE_MONITORS.computeIfAbsent(path) { Any() }
        return synchronized(monitor) {
            RandomAccessFile(File(file.absolutePath + LOCK_SUFFIX), "rw").channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun Properties.required(name: String): String =
        getProperty(name)?.takeIf { value -> value.isNotBlank() }
            ?: error("missing package enabled-state property: $name")

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_STATE_FILE_BYTES = 4L * 1024L * 1024L
        const val MAX_RECORD_COUNT = 4_096
        const val MAX_COMPONENT_RECORD_COUNT = 16_384
        const val SCHEMA_VERSION = "schemaVersion"
        const val RECORD_COUNT = "record.count"
        const val RECORD_PREFIX = "record"
        const val INSTANCE_ID = "instanceId"
        const val GENERATION_FINGERPRINT = "generationFingerprint"
        const val APPLICATION_STATE = "applicationState"
        const val COMPONENT_COUNT = "component.count"
        const val COMPONENT_PREFIX = "component"
        const val COMPONENT_TYPE = "type"
        const val COMPONENT_CLASS_NAME = "className"
        const val COMPONENT_STATE = "state"
        const val LOCK_SUFFIX = ".lock"
        const val TEMP_SUFFIX = ".tmp"
        val FILE_MONITORS = ConcurrentHashMap<String, Any>()
    }
}

internal fun VirtualInstanceRuntime.toPackageGenerationIdentityOrNull(): EnginePackageGenerationIdentity? {
    val snapshot = packageSnapshot
    if (
        instanceId.isBlank() ||
        instanceId != snapshot.instanceId ||
        originPackageName != snapshot.originPackageName ||
        virtualPackageName != snapshot.virtualPackageName ||
        dataRoot != snapshot.dataDir
    ) {
        return null
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.addCanonical(instanceId)
    digest.addCanonical(hostPackageName)
    digest.addCanonical(originPackageName)
    digest.addCanonical(virtualPackageName)
    digest.addCanonical(dataRoot)
    digest.addCanonical(snapshot.versionCode.toString())
    digest.addCanonical(snapshot.versionName)
    digest.addCanonical(snapshot.targetSdk.toString())
    digest.addCanonical(snapshot.minSdk.toString())
    digest.addCanonical(snapshot.sourceDir)
    digest.addCanonical(snapshot.sourceSha256)
    digest.addCanonical(snapshot.publicSourceDir)
    digest.addCanonical(snapshot.splitSourceDirs)
    digest.addCanonical(snapshot.splitSha256s)
    digest.addCanonical(snapshot.splitPublicSourceDirs)
    digest.addCanonical(snapshot.splitNames)
    digest.addCanonical(snapshot.isolatedSplits.toString())
    digest.addCanonical(snapshot.applicationClassName)
    digest.addCanonical(snapshot.originCertSha256)
    digest.addCanonical(snapshot.signerSha256Digests.sorted())
    digest.addCanonical(snapshot.hasMultipleSigners.toString())
    VirtualPackageComponentType.entries.forEach { type ->
        digest.addCanonical(type.name)
        digest.addCanonical(snapshot.componentsForEnabledState(type).map { component -> component.name }.sorted())
    }
    return EnginePackageGenerationIdentity(
        instanceId = instanceId,
        fingerprint = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    )
}

internal fun normalizeVirtualComponentClassName(
    packageName: String,
    className: String
): String? {
    if (!isSafePackageName(packageName) || className.isBlank() || className.length > MAX_CLASS_NAME_LENGTH) {
        return null
    }
    if (className != className.trim() || className.any { character -> character.isISOControl() }) {
        return null
    }
    val normalized = when {
        className.startsWith('.') -> packageName + className
        '.' !in className -> "$packageName.$className"
        else -> className
    }
    return normalized.takeIf(::isSafeNormalizedComponentClassName)
}

internal fun com.multiapp.core.model.virtual.VirtualPackageSnapshot.componentsForEnabledState(
    type: VirtualPackageComponentType
) = when (type) {
    VirtualPackageComponentType.ACTIVITY -> activities
    VirtualPackageComponentType.SERVICE -> services
    VirtualPackageComponentType.RECEIVER -> receivers
    VirtualPackageComponentType.PROVIDER -> providers
}

private fun MutableMap<String, EnginePackageEnabledStateRecord>.mutateApplicationState(
    generation: EnginePackageGenerationIdentity,
    state: Int
): EnginePackageEnabledStateMutation {
    require(EnginePackageEnabledStates.isValidApplicationState(state)) {
        "invalid application enabled state: $state"
    }
    val existing = this[generation.instanceId]?.takeIf { record -> record.generation == generation }
    val previousState = existing?.applicationState ?: EnginePackageEnabledStates.DEFAULT
    val updated = (existing ?: EnginePackageEnabledStateRecord(generation)).copy(applicationState = state)
    replaceOrRemove(updated)
    return EnginePackageEnabledStateMutation(previousState, state, previousState != state)
}

private fun MutableMap<String, EnginePackageEnabledStateRecord>.mutateComponentState(
    generation: EnginePackageGenerationIdentity,
    key: EnginePackageComponentKey,
    state: Int
): EnginePackageEnabledStateMutation {
    require(EnginePackageEnabledStates.isValidComponentState(state)) {
        "invalid component enabled state: $state"
    }
    val existing = this[generation.instanceId]?.takeIf { record -> record.generation == generation }
    val previousState = existing?.componentStates?.get(key) ?: EnginePackageEnabledStates.DEFAULT
    val componentStates = existing?.componentStates.orEmpty().toMutableMap().apply {
        if (state == EnginePackageEnabledStates.DEFAULT) remove(key) else put(key, state)
    }
    val updated = (existing ?: EnginePackageEnabledStateRecord(generation)).copy(
        componentStates = componentStates
    )
    replaceOrRemove(updated)
    return EnginePackageEnabledStateMutation(previousState, state, previousState != state)
}

private fun MutableMap<String, EnginePackageEnabledStateRecord>.replaceOrRemove(
    record: EnginePackageEnabledStateRecord
) {
    if (record.overrideCount == 0) {
        remove(record.generation.instanceId)
    } else {
        put(record.generation.instanceId, record)
    }
}

private fun MutableMap<String, EnginePackageEnabledStateRecord>.reconcileRecords(
    validInstanceIds: Set<String>,
    currentGenerations: Map<String, EnginePackageGenerationIdentity>
): Int {
    var removed = 0
    entries.removeAll { entry ->
        val currentGeneration = currentGenerations[entry.key]
        val stale = entry.key !in validInstanceIds ||
            (currentGeneration != null && currentGeneration != entry.value.generation)
        if (stale) removed += entry.value.overrideCount
        stale
    }
    return removed
}

private fun MessageDigest.addCanonical(value: String?) {
    if (value == null) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array())
        return
    }
    val bytes = value.toByteArray(Charsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}

private fun MessageDigest.addCanonical(values: List<String>) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(values.size).array())
    values.forEach(::addCanonical)
}

private fun isSafeStorageIdentity(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_IDENTITY_LENGTH &&
        value.none { character -> character.isISOControl() }

private fun isSafePackageName(value: String): Boolean =
    value.length in 1..MAX_CLASS_NAME_LENGTH &&
        value.split('.').all { segment ->
            segment.isNotEmpty() && segment.first().isAsciiIdentifierStart(allowDollar = false) &&
                segment.drop(1).all { character -> character.isAsciiIdentifierPart(allowDollar = false) }
        }

private fun isSafeNormalizedComponentClassName(value: String): Boolean =
    value.length in 1..MAX_CLASS_NAME_LENGTH &&
        value.split('.').size >= 2 &&
        value.split('.').all { segment ->
            segment.isNotEmpty() && segment.first().isAsciiIdentifierStart(allowDollar = true) &&
                segment.drop(1).all { character -> character.isAsciiIdentifierPart(allowDollar = true) }
        }

private fun Char.isAsciiIdentifierStart(allowDollar: Boolean): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this == '_' || (allowDollar && this == '$')

private fun Char.isAsciiIdentifierPart(allowDollar: Boolean): Boolean =
    isAsciiIdentifierStart(allowDollar) || this in '0'..'9'

private const val MAX_IDENTITY_LENGTH = 1_024
private const val MAX_CLASS_NAME_LENGTH = 1_024
private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
