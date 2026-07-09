package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.io.File
import java.util.Base64
import java.util.Properties

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
    val launcherActivityName: String?,
    val activities: List<ResolvedComponent>,
    val services: List<ResolvedComponent>,
    val receivers: List<ResolvedComponent>,
    val providers: List<ResolvedComponent>,
    val permissions: List<String>,
    val originCertSha256: String?
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
            publicSourceDir = publicSourceDir,
            splitSourceDirs = splitSourceDirs,
            splitPublicSourceDirs = splitPublicSourceDirs,
            splitNames = splitNames,
            isolatedSplits = isolatedSplits,
            dataDir = packageDataDir,
            nativeLibraryDir = nativeLibraryDir,
            applicationClassName = applicationClassName,
            processName = packageProcessName,
            taskAffinity = taskAffinity,
            themeId = themeId,
            metaData = metaData,
            launcherActivityName = launcherActivityName,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            permissions = permissions,
            originCertSha256 = originCertSha256
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
                nativeLibraryDir = snapshot.nativeLibraryDir,
                applicationClassName = snapshot.applicationClassName,
                packageProcessName = snapshot.processName,
                taskAffinity = snapshot.taskAffinity,
                themeId = snapshot.themeId,
                metaData = snapshot.metaData,
                launcherActivityName = snapshot.launcherActivityName,
                activities = snapshot.activities,
                services = snapshot.services,
                receivers = snapshot.receivers,
                providers = snapshot.providers,
                permissions = snapshot.permissions,
                originCertSha256 = snapshot.originCertSha256
            )
        }
    }
}

interface EngineRuntimeStateStore {
    fun put(record: EngineRuntimeStateRecord)
    fun get(instanceId: String): EngineRuntimeStateRecord?
    fun list(): List<EngineRuntimeStateRecord>
    fun remove(instanceId: String): Boolean
    fun clear()
}

class InMemoryEngineRuntimeStateStore : EngineRuntimeStateStore {
    private val records = linkedMapOf<String, EngineRuntimeStateRecord>()

    @Synchronized
    override fun put(record: EngineRuntimeStateRecord) {
        records[record.instanceId] = record
    }

    @Synchronized
    override fun get(instanceId: String): EngineRuntimeStateRecord? = records[instanceId]

    @Synchronized
    override fun list(): List<EngineRuntimeStateRecord> = records.values.toList()

    @Synchronized
    override fun remove(instanceId: String): Boolean = records.remove(instanceId) != null

    @Synchronized
    override fun clear() {
        records.clear()
    }
}

class FileBackedEngineRuntimeStateStore(
    private val file: File
) : EngineRuntimeStateStore {

    @Synchronized
    override fun put(record: EngineRuntimeStateRecord) {
        val current = load().associateBy { it.instanceId }.toMutableMap()
        current[record.instanceId] = record
        store(current.values.toList())
    }

    @Synchronized
    override fun get(instanceId: String): EngineRuntimeStateRecord? =
        load().firstOrNull { it.instanceId == instanceId }

    @Synchronized
    override fun list(): List<EngineRuntimeStateRecord> = load()

    @Synchronized
    override fun remove(instanceId: String): Boolean {
        val current = load()
        val retained = current.filterNot { it.instanceId == instanceId }
        if (retained.size == current.size) return false
        store(retained)
        return true
    }

    @Synchronized
    override fun clear() {
        if (file.exists()) file.delete()
    }

    private fun load(): List<EngineRuntimeStateRecord> {
        if (!file.isFile) return emptyList()
        val properties = Properties()
        file.inputStream().use { input -> properties.load(input) }
        return properties.stringPropertyNames()
            .asSequence()
            .mapNotNull { name -> name.substringBefore('.').takeIf { it.isNotBlank() } }
            .distinct()
            .sorted()
            .mapNotNull { instanceId -> decodeRecord(properties, instanceId) }
            .toList()
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
            record.runtimeProcessName?.let { properties.setProperty(prefix + RUNTIME_PROCESS_NAME, it) }
            properties.setProperty(prefix + STATE, record.state.name)
            properties.setProperty(prefix + APPLICATION_LABEL, record.applicationLabel)
            properties.setProperty(prefix + VERSION_CODE, record.versionCode.toString())
            properties.setProperty(prefix + VERSION_NAME, record.versionName)
            properties.setProperty(prefix + TARGET_SDK, record.targetSdk.toString())
            properties.setProperty(prefix + MIN_SDK, record.minSdk.toString())
            properties.setProperty(prefix + SOURCE_DIR, record.sourceDir)
            properties.setProperty(prefix + PUBLIC_SOURCE_DIR, record.publicSourceDir)
            properties.setProperty(prefix + SPLIT_SOURCE_DIRS, record.splitSourceDirs.encodeStringList())
            properties.setProperty(prefix + SPLIT_PUBLIC_SOURCE_DIRS, record.splitPublicSourceDirs.encodeStringList())
            properties.setProperty(prefix + SPLIT_NAMES, record.splitNames.encodeStringList())
            properties.setProperty(prefix + ISOLATED_SPLITS, record.isolatedSplits.toString())
            properties.setProperty(prefix + PACKAGE_DATA_DIR, record.packageDataDir)
            record.nativeLibraryDir?.let { properties.setProperty(prefix + NATIVE_LIBRARY_DIR, it) }
            record.applicationClassName?.let { properties.setProperty(prefix + APPLICATION_CLASS_NAME, it) }
            record.packageProcessName?.let { properties.setProperty(prefix + PACKAGE_PROCESS_NAME, it) }
            record.taskAffinity?.let { properties.setProperty(prefix + TASK_AFFINITY, it) }
            properties.setProperty(prefix + THEME_ID, record.themeId.toString())
            properties.storeStringMap(prefix + META_DATA, record.metaData)
            record.launcherActivityName?.let { properties.setProperty(prefix + LAUNCHER_ACTIVITY_NAME, it) }
            properties.storeComponents(prefix + ACTIVITIES, record.activities)
            properties.storeComponents(prefix + SERVICES, record.services)
            properties.storeComponents(prefix + RECEIVERS, record.receivers)
            properties.storeComponents(prefix + PROVIDERS, record.providers)
            properties.setProperty(prefix + PERMISSIONS, record.permissions.encodeStringList())
            record.originCertSha256?.let { properties.setProperty(prefix + ORIGIN_CERT_SHA256, it) }
        }
        file.outputStream().use { output ->
            properties.store(output, "MultiApp engine runtime state")
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
                runtimeProcessName = properties.getProperty(prefix + RUNTIME_PROCESS_NAME),
                state = enumValueOf(properties.required(prefix + STATE)),
                applicationLabel = properties.required(prefix + APPLICATION_LABEL),
                versionCode = properties.required(prefix + VERSION_CODE).toLong(),
                versionName = properties.required(prefix + VERSION_NAME),
                targetSdk = properties.required(prefix + TARGET_SDK).toInt(),
                minSdk = properties.required(prefix + MIN_SDK).toInt(),
                sourceDir = properties.required(prefix + SOURCE_DIR),
                publicSourceDir = properties.required(prefix + PUBLIC_SOURCE_DIR),
                splitSourceDirs = properties.getProperty(prefix + SPLIT_SOURCE_DIRS).decodeStringList(),
                splitPublicSourceDirs = properties.getProperty(prefix + SPLIT_PUBLIC_SOURCE_DIRS).decodeStringList(),
                splitNames = properties.getProperty(prefix + SPLIT_NAMES).decodeStringList(),
                isolatedSplits = properties.getProperty(prefix + ISOLATED_SPLITS).toBoolean(),
                packageDataDir = properties.required(prefix + PACKAGE_DATA_DIR),
                nativeLibraryDir = properties.getProperty(prefix + NATIVE_LIBRARY_DIR),
                applicationClassName = properties.getProperty(prefix + APPLICATION_CLASS_NAME),
                packageProcessName = properties.getProperty(prefix + PACKAGE_PROCESS_NAME),
                taskAffinity = properties.getProperty(prefix + TASK_AFFINITY),
                themeId = properties.getProperty(prefix + THEME_ID).orEmpty().toIntOrNull() ?: 0,
                metaData = properties.decodeStringMap(prefix + META_DATA),
                launcherActivityName = properties.getProperty(prefix + LAUNCHER_ACTIVITY_NAME),
                activities = properties.decodeComponents(prefix + ACTIVITIES),
                services = properties.decodeComponents(prefix + SERVICES),
                receivers = properties.decodeComponents(prefix + RECEIVERS),
                providers = properties.decodeComponents(prefix + PROVIDERS),
                permissions = properties.getProperty(prefix + PERMISSIONS).decodeStringList(),
                originCertSha256 = properties.getProperty(prefix + ORIGIN_CERT_SHA256)
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
            component.launchMode?.let { setProperty(itemPrefix + COMPONENT_LAUNCH_MODE, it) }
            component.processName?.let { setProperty(itemPrefix + COMPONENT_PROCESS_NAME, it) }
            component.taskAffinity?.let { setProperty(itemPrefix + COMPONENT_TASK_AFFINITY, it) }
            setProperty(itemPrefix + COMPONENT_THEME_ID, component.themeId.toString())
            component.screenOrientation?.let { setProperty(itemPrefix + COMPONENT_SCREEN_ORIENTATION, it) }
            component.configChanges?.let { setProperty(itemPrefix + COMPONENT_CONFIG_CHANGES, it) }
            component.permission?.let { setProperty(itemPrefix + COMPONENT_PERMISSION, it) }
            setProperty(itemPrefix + COMPONENT_GRANT_URI_PERMISSIONS, component.grantUriPermissions.toString())
            storeStringMap(itemPrefix + COMPONENT_META_DATA, component.metaData)
            component.targetActivityName?.let { setProperty(itemPrefix + COMPONENT_TARGET_ACTIVITY_NAME, it) }
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
                launchMode = getProperty(itemPrefix + COMPONENT_LAUNCH_MODE),
                processName = getProperty(itemPrefix + COMPONENT_PROCESS_NAME),
                taskAffinity = getProperty(itemPrefix + COMPONENT_TASK_AFFINITY),
                themeId = getProperty(itemPrefix + COMPONENT_THEME_ID).orEmpty().toIntOrNull() ?: 0,
                screenOrientation = getProperty(itemPrefix + COMPONENT_SCREEN_ORIENTATION),
                configChanges = getProperty(itemPrefix + COMPONENT_CONFIG_CHANGES),
                permission = getProperty(itemPrefix + COMPONENT_PERMISSION),
                grantUriPermissions = getProperty(itemPrefix + COMPONENT_GRANT_URI_PERMISSIONS).toBoolean(),
                metaData = decodeStringMap(itemPrefix + COMPONENT_META_DATA),
                targetActivityName = getProperty(itemPrefix + COMPONENT_TARGET_ACTIVITY_NAME)
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
        }
    }

    private fun Properties.decodeResolvedIntentFilters(prefix: String): List<ResolvedIntentFilter> {
        val count = getProperty(prefix + COUNT).orEmpty().toIntOrNull() ?: return emptyList()
        return (0 until count).map { index ->
            val itemPrefix = "$prefix.$index."
            ResolvedIntentFilter(
                actions = getProperty(itemPrefix + FILTER_ACTIONS).decodeStringList(),
                categories = getProperty(itemPrefix + FILTER_CATEGORIES).decodeStringList(),
                dataSchemes = getProperty(itemPrefix + FILTER_DATA_SCHEMES).decodeStringList()
            )
        }
    }

    companion object {
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
        private const val PUBLIC_SOURCE_DIR = "publicSourceDir"
        private const val SPLIT_SOURCE_DIRS = "splitSourceDirs"
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
        private const val LAUNCHER_ACTIVITY_NAME = "launcherActivityName"
        private const val ACTIVITIES = "activities"
        private const val SERVICES = "services"
        private const val RECEIVERS = "receivers"
        private const val PROVIDERS = "providers"
        private const val PERMISSIONS = "permissions"
        private const val ORIGIN_CERT_SHA256 = "originCertSha256"
        private const val COUNT = ".count"
        private const val MAP_KEY = "key"
        private const val MAP_VALUE = "value"
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
        private const val COMPONENT_GRANT_URI_PERMISSIONS = "grantUriPermissions"
        private const val COMPONENT_META_DATA = "metaData"
        private const val COMPONENT_TARGET_ACTIVITY_NAME = "targetActivityName"
        private const val FILTER_ACTIONS = "actions"
        private const val FILTER_CATEGORIES = "categories"
        private const val FILTER_DATA_SCHEMES = "dataSchemes"
        private const val LIST_SEPARATOR = ","
    }
}
