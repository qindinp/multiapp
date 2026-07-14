package com.multiapp.core.engine

import android.os.Bundle
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

/** Strict, framework-only codec used to move the complete engine runtime across Binder. */
internal fun VirtualInstanceRuntime.toAuthoritativeRuntimeBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putInt(RuntimeCodecKeys.SCHEMA_VERSION, AUTHORITATIVE_RUNTIME_SCHEMA_VERSION)
    putBoolean(EngineRuntimeIpcContract.KEY_FOUND, true)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(RuntimeCodecKeys.HOST_PACKAGE_NAME, hostPackageName)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME, virtualPackageName)
    putString(RuntimeCodecKeys.DATA_ROOT, dataRoot)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE, profile.name)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_PROXY_SLOT, proxySlot)
    putString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID, evidenceSessionId)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
    putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, processId ?: 0)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
    putString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE, state.name)
    putBundle(RuntimeCodecKeys.PACKAGE_SNAPSHOT, packageSnapshot.toBundle(bundleFactory))
}

internal fun Bundle.toAuthoritativeRuntimeOrNull(): VirtualInstanceRuntime? = runCatching {
    requireExactKeys(RUNTIME_FIELDS)
    check(getInt(RuntimeCodecKeys.SCHEMA_VERSION) == AUTHORITATIVE_RUNTIME_SCHEMA_VERSION)
    check(getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
    val runtime = VirtualInstanceRuntime(
        instanceId = requiredBoundedString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
        hostPackageName = requiredBoundedString(RuntimeCodecKeys.HOST_PACKAGE_NAME),
        originPackageName = requiredBoundedString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME),
        virtualPackageName = requiredBoundedString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME),
        dataRoot = requiredBoundedString(RuntimeCodecKeys.DATA_ROOT, MAX_PATH_LENGTH),
        packageSnapshot = getBundle(RuntimeCodecKeys.PACKAGE_SNAPSHOT)
            ?.toPackageSnapshotOrNull()
            ?: error("missing package snapshot"),
        profile = requiredEnum(EngineRuntimeIpcContract.KEY_ENGINE_PROFILE),
        processSlot = requiredBoundedString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
        proxySlot = requiredBoundedString(EngineRuntimeIpcContract.KEY_PROXY_SLOT),
        evidenceSessionId = requiredBoundedString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH).also { check(it > 0L) },
        engineSessionId = requiredBoundedString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID),
        processId = getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID).let { value ->
            when {
                value == 0 -> null
                value > 0 -> value
                else -> error("invalid process id")
            }
        },
        processName = optionalBoundedString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
        state = requiredEnum(EngineRuntimeIpcContract.KEY_RUNTIME_STATE)
    )
    val snapshot = runtime.packageSnapshot
    check(snapshot.instanceId == runtime.instanceId)
    check(snapshot.originPackageName == runtime.originPackageName)
    check(snapshot.virtualPackageName == runtime.virtualPackageName)
    check(snapshot.dataDir == runtime.dataRoot)
    check(runtime.authoritativeRuntimeTextSize() <= MAX_TOTAL_TEXT_LENGTH)
    runtime
}.getOrNull()

private fun VirtualPackageSnapshot.toBundle(bundleFactory: () -> Bundle): Bundle = bundleFactory().apply {
    putInt(RuntimeCodecKeys.SCHEMA_VERSION, PACKAGE_SNAPSHOT_SCHEMA_VERSION)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME, virtualPackageName)
    putString(RuntimeCodecKeys.APPLICATION_LABEL, applicationLabel)
    putLong(RuntimeCodecKeys.VERSION_CODE, versionCode)
    putString(RuntimeCodecKeys.VERSION_NAME, versionName)
    putInt(RuntimeCodecKeys.TARGET_SDK, targetSdk)
    putInt(RuntimeCodecKeys.MIN_SDK, minSdk)
    putString(RuntimeCodecKeys.SOURCE_DIR, sourceDir)
    putString(RuntimeCodecKeys.SOURCE_SHA256, sourceSha256)
    putString(RuntimeCodecKeys.PUBLIC_SOURCE_DIR, publicSourceDir)
    putStringArrayList(RuntimeCodecKeys.SPLIT_SOURCE_DIRS, ArrayList(splitSourceDirs))
    putStringArrayList(RuntimeCodecKeys.SPLIT_SHA256S, ArrayList(splitSha256s))
    putStringArrayList(RuntimeCodecKeys.SPLIT_PUBLIC_SOURCE_DIRS, ArrayList(splitPublicSourceDirs))
    putStringArrayList(RuntimeCodecKeys.SPLIT_NAMES, ArrayList(splitNames))
    putBoolean(RuntimeCodecKeys.ISOLATED_SPLITS, isolatedSplits)
    putString(RuntimeCodecKeys.DATA_DIR, dataDir)
    putString(RuntimeCodecKeys.NATIVE_LIBRARY_DIR, nativeLibraryDir)
    putStringArrayList(RuntimeCodecKeys.NATIVE_LIBRARIES, ArrayList(nativeLibraries))
    putStringArrayList(RuntimeCodecKeys.ABI_LIST, ArrayList(abiList))
    putString(RuntimeCodecKeys.APPLICATION_CLASS_NAME, applicationClassName)
    putString(RuntimeCodecKeys.PACKAGE_PROCESS_NAME, processName)
    putString(RuntimeCodecKeys.TASK_AFFINITY, taskAffinity)
    putInt(RuntimeCodecKeys.THEME_ID, themeId)
    putBundle(RuntimeCodecKeys.META_DATA, metaData.toStringMapBundle(bundleFactory))
    putBundle(RuntimeCodecKeys.TYPED_META_DATA, typedMetaData.toTypedMetaDataBundle(bundleFactory))
    putString(RuntimeCodecKeys.LAUNCHER_ACTIVITY_NAME, launcherActivityName)
    putBundle(RuntimeCodecKeys.ACTIVITIES, activities.toComponentListBundle(bundleFactory))
    putBundle(RuntimeCodecKeys.SERVICES, services.toComponentListBundle(bundleFactory))
    putBundle(RuntimeCodecKeys.RECEIVERS, receivers.toComponentListBundle(bundleFactory))
    putBundle(RuntimeCodecKeys.PROVIDERS, providers.toComponentListBundle(bundleFactory))
    putStringArrayList(RuntimeCodecKeys.PERMISSIONS, ArrayList(permissions))
    putString(RuntimeCodecKeys.ORIGIN_CERT_SHA256, originCertSha256)
    putStringArrayList(RuntimeCodecKeys.SIGNER_SHA256_DIGESTS, ArrayList(signerSha256Digests))
    putBoolean(RuntimeCodecKeys.HAS_MULTIPLE_SIGNERS, hasMultipleSigners)
}

private fun Bundle.toPackageSnapshotOrNull(): VirtualPackageSnapshot? = runCatching {
    requireExactKeys(PACKAGE_FIELDS)
    check(getInt(RuntimeCodecKeys.SCHEMA_VERSION) == PACKAGE_SNAPSHOT_SCHEMA_VERSION)
    val snapshot = VirtualPackageSnapshot(
        instanceId = requiredBoundedString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
        originPackageName = requiredBoundedString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME),
        virtualPackageName = requiredBoundedString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME),
        applicationLabel = requiredBoundedString(RuntimeCodecKeys.APPLICATION_LABEL),
        versionCode = getLong(RuntimeCodecKeys.VERSION_CODE).also { check(it > 0L) },
        versionName = requiredBoundedString(RuntimeCodecKeys.VERSION_NAME),
        targetSdk = getInt(RuntimeCodecKeys.TARGET_SDK).also { check(it > 0) },
        minSdk = getInt(RuntimeCodecKeys.MIN_SDK).also { check(it > 0) },
        sourceDir = requiredBoundedString(RuntimeCodecKeys.SOURCE_DIR, MAX_PATH_LENGTH),
        sourceSha256 = optionalDigest(RuntimeCodecKeys.SOURCE_SHA256),
        publicSourceDir = requiredBoundedString(RuntimeCodecKeys.PUBLIC_SOURCE_DIR, MAX_PATH_LENGTH),
        splitSourceDirs = boundedStringList(RuntimeCodecKeys.SPLIT_SOURCE_DIRS, MAX_SPLIT_COUNT, MAX_PATH_LENGTH),
        splitSha256s = digestList(RuntimeCodecKeys.SPLIT_SHA256S, MAX_SPLIT_COUNT),
        splitPublicSourceDirs = boundedStringList(
            RuntimeCodecKeys.SPLIT_PUBLIC_SOURCE_DIRS,
            MAX_SPLIT_COUNT,
            MAX_PATH_LENGTH
        ),
        splitNames = boundedStringList(RuntimeCodecKeys.SPLIT_NAMES, MAX_SPLIT_COUNT),
        isolatedSplits = getBoolean(RuntimeCodecKeys.ISOLATED_SPLITS),
        dataDir = requiredBoundedString(RuntimeCodecKeys.DATA_DIR, MAX_PATH_LENGTH),
        nativeLibraryDir = optionalBoundedString(RuntimeCodecKeys.NATIVE_LIBRARY_DIR, MAX_PATH_LENGTH),
        nativeLibraries = boundedStringList(RuntimeCodecKeys.NATIVE_LIBRARIES, MAX_NATIVE_ENTRY_COUNT),
        abiList = boundedStringList(RuntimeCodecKeys.ABI_LIST, MAX_NATIVE_ENTRY_COUNT),
        applicationClassName = optionalBoundedString(RuntimeCodecKeys.APPLICATION_CLASS_NAME),
        processName = optionalBoundedString(RuntimeCodecKeys.PACKAGE_PROCESS_NAME),
        taskAffinity = optionalBoundedString(RuntimeCodecKeys.TASK_AFFINITY),
        themeId = getInt(RuntimeCodecKeys.THEME_ID),
        metaData = getBundle(RuntimeCodecKeys.META_DATA)?.toStringMapOrNull() ?: error("missing meta-data"),
        typedMetaData = getBundle(RuntimeCodecKeys.TYPED_META_DATA)
            ?.toTypedMetaDataOrNull()
            ?: error("missing typed meta-data"),
        launcherActivityName = optionalBoundedString(RuntimeCodecKeys.LAUNCHER_ACTIVITY_NAME),
        activities = getBundle(RuntimeCodecKeys.ACTIVITIES)?.toComponentListOrNull() ?: error("missing activities"),
        services = getBundle(RuntimeCodecKeys.SERVICES)?.toComponentListOrNull() ?: error("missing services"),
        receivers = getBundle(RuntimeCodecKeys.RECEIVERS)?.toComponentListOrNull() ?: error("missing receivers"),
        providers = getBundle(RuntimeCodecKeys.PROVIDERS)?.toComponentListOrNull() ?: error("missing providers"),
        permissions = boundedStringList(RuntimeCodecKeys.PERMISSIONS, MAX_PERMISSION_COUNT),
        originCertSha256 = optionalBoundedString(RuntimeCodecKeys.ORIGIN_CERT_SHA256),
        signerSha256Digests = boundedStringList(RuntimeCodecKeys.SIGNER_SHA256_DIGESTS, MAX_SIGNER_COUNT),
        hasMultipleSigners = getBoolean(RuntimeCodecKeys.HAS_MULTIPLE_SIGNERS)
    )
    check(snapshot.activities.hasUniqueComponentNames())
    check(snapshot.services.hasUniqueComponentNames())
    check(snapshot.receivers.hasUniqueComponentNames())
    check(snapshot.providers.hasUniqueComponentNames())
    val authorities = snapshot.providers.flatMap { it.authorities }
    check(authorities.size == authorities.distinct().size)
    snapshot
}.getOrNull()

private fun List<ResolvedComponent>.toComponentListBundle(bundleFactory: () -> Bundle): Bundle =
    bundleFactory().apply {
        putInt(RuntimeCodecKeys.COUNT, size)
        forEachIndexed { index, component ->
            putBundle(index.toString(), component.toBundle(bundleFactory))
        }
    }

private fun Bundle.toComponentListOrNull(): List<ResolvedComponent>? = runCatching {
    val count = getInt(RuntimeCodecKeys.COUNT).also { check(it in 0..MAX_COMPONENT_COUNT) }
    requireIndexedKeys(count)
    (0 until count).map { index ->
        getBundle(index.toString())?.toResolvedComponentOrNull() ?: error("invalid component")
    }
}.getOrNull()

private fun ResolvedComponent.toBundle(bundleFactory: () -> Bundle): Bundle = bundleFactory().apply {
    putString(RuntimeCodecKeys.NAME, name)
    putBoolean(RuntimeCodecKeys.EXPORTED, exported)
    putStringArrayList(RuntimeCodecKeys.INTENT_FILTERS, ArrayList(intentFilters))
    putBundle(RuntimeCodecKeys.RESOLVED_INTENT_FILTERS, resolvedIntentFilters.toIntentFilterListBundle(bundleFactory))
    putStringArrayList(RuntimeCodecKeys.AUTHORITIES, ArrayList(authorities))
    putString(RuntimeCodecKeys.LAUNCH_MODE, launchMode)
    putString(RuntimeCodecKeys.COMPONENT_PROCESS_NAME, processName)
    putString(RuntimeCodecKeys.COMPONENT_TASK_AFFINITY, taskAffinity)
    putInt(RuntimeCodecKeys.COMPONENT_THEME_ID, themeId)
    putString(RuntimeCodecKeys.SCREEN_ORIENTATION, screenOrientation)
    putString(RuntimeCodecKeys.CONFIG_CHANGES, configChanges)
    putString(RuntimeCodecKeys.PERMISSION, permission)
    putString(RuntimeCodecKeys.READ_PERMISSION, readPermission)
    putString(RuntimeCodecKeys.WRITE_PERMISSION, writePermission)
    putBoolean(RuntimeCodecKeys.GRANT_URI_PERMISSIONS, grantUriPermissions)
    putBundle(RuntimeCodecKeys.PATH_PERMISSIONS, pathPermissions.toPathPermissionListBundle(bundleFactory))
    putBundle(RuntimeCodecKeys.URI_PERMISSION_PATTERNS, uriPermissionPatterns.toPathPatternListBundle(bundleFactory))
    putBundle(RuntimeCodecKeys.COMPONENT_META_DATA, metaData.toStringMapBundle(bundleFactory))
    putBundle(RuntimeCodecKeys.COMPONENT_TYPED_META_DATA, typedMetaData.toTypedMetaDataBundle(bundleFactory))
    putString(RuntimeCodecKeys.TARGET_ACTIVITY_NAME, targetActivityName)
}

private fun Bundle.toResolvedComponentOrNull(): ResolvedComponent? = runCatching {
    requireExactKeys(COMPONENT_FIELDS)
    ResolvedComponent(
        name = requiredBoundedString(RuntimeCodecKeys.NAME),
        exported = getBoolean(RuntimeCodecKeys.EXPORTED),
        intentFilters = boundedStringList(RuntimeCodecKeys.INTENT_FILTERS, MAX_FILTER_COUNT),
        resolvedIntentFilters = getBundle(RuntimeCodecKeys.RESOLVED_INTENT_FILTERS)
            ?.toIntentFilterListOrNull()
            ?: error("missing resolved filters"),
        authorities = boundedStringList(RuntimeCodecKeys.AUTHORITIES, MAX_AUTHORITY_COUNT).also {
            check(it.size == it.distinct().size)
        },
        launchMode = optionalBoundedString(RuntimeCodecKeys.LAUNCH_MODE),
        processName = optionalBoundedString(RuntimeCodecKeys.COMPONENT_PROCESS_NAME),
        taskAffinity = optionalBoundedString(RuntimeCodecKeys.COMPONENT_TASK_AFFINITY),
        themeId = getInt(RuntimeCodecKeys.COMPONENT_THEME_ID),
        screenOrientation = optionalBoundedString(RuntimeCodecKeys.SCREEN_ORIENTATION),
        configChanges = optionalBoundedString(RuntimeCodecKeys.CONFIG_CHANGES),
        permission = optionalBoundedString(RuntimeCodecKeys.PERMISSION),
        readPermission = optionalBoundedString(RuntimeCodecKeys.READ_PERMISSION),
        writePermission = optionalBoundedString(RuntimeCodecKeys.WRITE_PERMISSION),
        grantUriPermissions = getBoolean(RuntimeCodecKeys.GRANT_URI_PERMISSIONS),
        pathPermissions = getBundle(RuntimeCodecKeys.PATH_PERMISSIONS)
            ?.toPathPermissionListOrNull()
            ?: error("missing path permissions"),
        uriPermissionPatterns = getBundle(RuntimeCodecKeys.URI_PERMISSION_PATTERNS)
            ?.toPathPatternListOrNull()
            ?: error("missing URI patterns"),
        metaData = getBundle(RuntimeCodecKeys.COMPONENT_META_DATA)?.toStringMapOrNull()
            ?: error("missing component meta-data"),
        typedMetaData = getBundle(RuntimeCodecKeys.COMPONENT_TYPED_META_DATA)?.toTypedMetaDataOrNull()
            ?: error("missing component typed meta-data"),
        targetActivityName = optionalBoundedString(RuntimeCodecKeys.TARGET_ACTIVITY_NAME)
    )
}.getOrNull()

private fun List<ResolvedIntentFilter>.toIntentFilterListBundle(bundleFactory: () -> Bundle): Bundle =
    bundleFactory().apply {
        putInt(RuntimeCodecKeys.COUNT, size)
        forEachIndexed { index, filter -> putBundle(index.toString(), filter.toBundle(bundleFactory)) }
    }

private fun Bundle.toIntentFilterListOrNull(): List<ResolvedIntentFilter>? = runCatching {
    val count = getInt(RuntimeCodecKeys.COUNT).also { check(it in 0..MAX_FILTER_COUNT) }
    requireIndexedKeys(count)
    (0 until count).map { getBundle(it.toString())?.toResolvedIntentFilterOrNull() ?: error("invalid filter") }
}.getOrNull()

private fun ResolvedIntentFilter.toBundle(bundleFactory: () -> Bundle): Bundle = bundleFactory().apply {
    putStringArrayList(RuntimeCodecKeys.ACTIONS, ArrayList(actions))
    putStringArrayList(RuntimeCodecKeys.CATEGORIES, ArrayList(categories))
    putStringArrayList(RuntimeCodecKeys.DATA_SCHEMES, ArrayList(dataSchemes))
    putStringArrayList(RuntimeCodecKeys.DATA_MIME_TYPES, ArrayList(dataMimeTypes))
    putStringArrayList(RuntimeCodecKeys.DATA_AUTHORITIES, ArrayList(dataAuthorities))
    putStringArrayList(RuntimeCodecKeys.DATA_PATHS, ArrayList(dataPaths))
    putBundle(
        RuntimeCodecKeys.AUTHORITY_ENTRIES,
        authorityEntries.toIntentAuthorityListBundle(bundleFactory)
    )
    putBundle(
        RuntimeCodecKeys.PATH_PATTERNS,
        pathPatterns.toResolvedIntentPathPatternListBundle(bundleFactory)
    )
    putInt(RuntimeCodecKeys.PRIORITY, priority)
}

private fun Bundle.toResolvedIntentFilterOrNull(): ResolvedIntentFilter? = runCatching {
    requireExactKeys(INTENT_FILTER_FIELDS)
    ResolvedIntentFilter(
        actions = boundedStringList(RuntimeCodecKeys.ACTIONS, MAX_FILTER_VALUE_COUNT),
        categories = boundedStringList(RuntimeCodecKeys.CATEGORIES, MAX_FILTER_VALUE_COUNT),
        dataSchemes = boundedStringList(RuntimeCodecKeys.DATA_SCHEMES, MAX_FILTER_VALUE_COUNT),
        dataMimeTypes = boundedStringList(RuntimeCodecKeys.DATA_MIME_TYPES, MAX_FILTER_VALUE_COUNT),
        dataAuthorities = boundedStringList(RuntimeCodecKeys.DATA_AUTHORITIES, MAX_FILTER_VALUE_COUNT),
        dataPaths = boundedStringList(RuntimeCodecKeys.DATA_PATHS, MAX_FILTER_VALUE_COUNT),
        priority = getInt(RuntimeCodecKeys.PRIORITY),
        authorityEntries = getBundle(RuntimeCodecKeys.AUTHORITY_ENTRIES)
            ?.toIntentAuthorityListOrNull()
            ?: error("missing intent-filter authorities"),
        pathPatterns = getBundle(RuntimeCodecKeys.PATH_PATTERNS)
            ?.toResolvedIntentPathPatternListOrNull()
            ?: error("missing intent-filter path patterns")
    )
}.getOrNull()

private fun List<ResolvedIntentAuthority>.toIntentAuthorityListBundle(
    bundleFactory: () -> Bundle
): Bundle = toEntryListBundle(bundleFactory) { authority ->
    bundleFactory().apply {
        putString(RuntimeCodecKeys.HOST, authority.host)
        putInt(RuntimeCodecKeys.PORT, authority.port ?: NO_AUTHORITY_PORT)
    }
}

private fun Bundle.toIntentAuthorityListOrNull(): List<ResolvedIntentAuthority>? = runCatching {
    toEntryList(MAX_FILTER_VALUE_COUNT) { entry ->
        entry.requireExactKeys(INTENT_AUTHORITY_FIELDS)
        val encodedPort = entry.getInt(RuntimeCodecKeys.PORT).also { port ->
            check(port == NO_AUTHORITY_PORT || port >= 0)
        }
        ResolvedIntentAuthority(
            host = entry.requiredBoundedString(RuntimeCodecKeys.HOST),
            port = encodedPort.takeUnless { it == NO_AUTHORITY_PORT }
        )
    }
}.getOrNull()

private fun List<ResolvedIntentPathPattern>.toResolvedIntentPathPatternListBundle(
    bundleFactory: () -> Bundle
): Bundle = toEntryListBundle(bundleFactory) { pattern ->
    bundleFactory().apply {
        putString(RuntimeCodecKeys.PATH, pattern.path)
        putString(RuntimeCodecKeys.TYPE, pattern.type.name)
    }
}

private fun Bundle.toResolvedIntentPathPatternListOrNull(): List<ResolvedIntentPathPattern>? =
    runCatching {
        toEntryList(MAX_FILTER_VALUE_COUNT) { entry ->
            entry.requireExactKeys(INTENT_PATH_PATTERN_FIELDS)
            ResolvedIntentPathPattern(
                path = entry.requiredBoundedString(
                    RuntimeCodecKeys.PATH,
                    MAX_PATH_LENGTH,
                    allowEmpty = false
                ),
                type = entry.requiredEnum<ResolvedIntentPathPatternType>(RuntimeCodecKeys.TYPE)
            )
        }
    }.getOrNull()

private fun List<VirtualProviderPathPermission>.toPathPermissionListBundle(
    bundleFactory: () -> Bundle
): Bundle = bundleFactory().apply {
    putInt(RuntimeCodecKeys.COUNT, size)
    forEachIndexed { index, permission -> putBundle(index.toString(), permission.toBundle(bundleFactory)) }
}

private fun Bundle.toPathPermissionListOrNull(): List<VirtualProviderPathPermission>? = runCatching {
    val count = getInt(RuntimeCodecKeys.COUNT).also { check(it in 0..MAX_PATH_POLICY_COUNT) }
    requireIndexedKeys(count)
    (0 until count).map { getBundle(it.toString())?.toPathPermissionOrNull() ?: error("invalid path permission") }
}.getOrNull()

private fun VirtualProviderPathPermission.toBundle(bundleFactory: () -> Bundle): Bundle =
    bundleFactory().apply {
        putBundle(RuntimeCodecKeys.PATTERN, pattern.toBundle(bundleFactory))
        putString(RuntimeCodecKeys.READ_PERMISSION, readPermission)
        putString(RuntimeCodecKeys.WRITE_PERMISSION, writePermission)
    }

private fun Bundle.toPathPermissionOrNull(): VirtualProviderPathPermission? = runCatching {
    requireExactKeys(PATH_PERMISSION_FIELDS)
    VirtualProviderPathPermission(
        pattern = getBundle(RuntimeCodecKeys.PATTERN)?.toPathPatternOrNull() ?: error("missing pattern"),
        readPermission = optionalBoundedString(RuntimeCodecKeys.READ_PERMISSION),
        writePermission = optionalBoundedString(RuntimeCodecKeys.WRITE_PERMISSION)
    )
}.getOrNull()

private fun List<VirtualProviderPathPattern>.toPathPatternListBundle(bundleFactory: () -> Bundle): Bundle =
    bundleFactory().apply {
        putInt(RuntimeCodecKeys.COUNT, size)
        forEachIndexed { index, pattern -> putBundle(index.toString(), pattern.toBundle(bundleFactory)) }
    }

private fun Bundle.toPathPatternListOrNull(): List<VirtualProviderPathPattern>? = runCatching {
    val count = getInt(RuntimeCodecKeys.COUNT).also { check(it in 0..MAX_PATH_POLICY_COUNT) }
    requireIndexedKeys(count)
    (0 until count).map { getBundle(it.toString())?.toPathPatternOrNull() ?: error("invalid path pattern") }
}.getOrNull()

private fun VirtualProviderPathPattern.toBundle(bundleFactory: () -> Bundle): Bundle =
    bundleFactory().apply {
        putString(RuntimeCodecKeys.PATH, path)
        putString(RuntimeCodecKeys.TYPE, type.name)
    }

private fun Bundle.toPathPatternOrNull(): VirtualProviderPathPattern? = runCatching {
    requireExactKeys(PATH_PATTERN_FIELDS)
    VirtualProviderPathPattern(
        path = requiredBoundedString(RuntimeCodecKeys.PATH, MAX_PATH_LENGTH),
        type = requiredEnum(RuntimeCodecKeys.TYPE)
    )
}.getOrNull()

private fun Map<String, String>.toStringMapBundle(bundleFactory: () -> Bundle): Bundle =
    entries.sortedBy { it.key }.toEntryListBundle(bundleFactory) { entry ->
        bundleFactory().apply {
            putString(RuntimeCodecKeys.KEY, entry.key)
            putString(RuntimeCodecKeys.VALUE, entry.value)
        }
    }

private fun Bundle.toStringMapOrNull(): Map<String, String>? = runCatching {
    toEntryList(MAX_META_DATA_COUNT) { entry ->
        entry.requireExactKeys(STRING_MAP_ENTRY_FIELDS)
        entry.requiredBoundedString(RuntimeCodecKeys.KEY) to
            entry.requiredBoundedString(RuntimeCodecKeys.VALUE, MAX_META_DATA_VALUE_LENGTH, allowEmpty = true)
    }.toMap().also { check(it.size == getInt(RuntimeCodecKeys.COUNT)) }
}.getOrNull()

private fun Map<String, VirtualMetaDataValue>.toTypedMetaDataBundle(bundleFactory: () -> Bundle): Bundle =
    entries.sortedBy { it.key }.toEntryListBundle(bundleFactory) { entry ->
        bundleFactory().apply {
            putString(RuntimeCodecKeys.KEY, entry.key)
            putString(RuntimeCodecKeys.TYPE, entry.value.type.name)
            putString(RuntimeCodecKeys.VALUE, entry.value.encodedValue)
        }
    }

private fun Bundle.toTypedMetaDataOrNull(): Map<String, VirtualMetaDataValue>? = runCatching {
    toEntryList(MAX_META_DATA_COUNT) { entry ->
        entry.requireExactKeys(TYPED_META_DATA_ENTRY_FIELDS)
        entry.requiredBoundedString(RuntimeCodecKeys.KEY) to VirtualMetaDataValue(
            type = entry.requiredEnum(RuntimeCodecKeys.TYPE),
            encodedValue = entry.requiredBoundedString(
                RuntimeCodecKeys.VALUE,
                MAX_META_DATA_VALUE_LENGTH,
                allowEmpty = true
            )
        )
    }.toMap().also { check(it.size == getInt(RuntimeCodecKeys.COUNT)) }
}.getOrNull()

private inline fun <T> Collection<T>.toEntryListBundle(
    bundleFactory: () -> Bundle,
    encode: (T) -> Bundle
): Bundle = bundleFactory().apply {
    putInt(RuntimeCodecKeys.COUNT, size)
    forEachIndexed { index, value -> putBundle(index.toString(), encode(value)) }
}

private inline fun <T> Bundle.toEntryList(maxCount: Int, decode: (Bundle) -> T): List<T> {
    val count = getInt(RuntimeCodecKeys.COUNT).also { check(it in 0..maxCount) }
    requireIndexedKeys(count)
    return (0 until count).map { index -> decode(getBundle(index.toString()) ?: error("missing entry")) }
}

private fun Bundle.requireIndexedKeys(count: Int) {
    requireExactKeys(setOf(RuntimeCodecKeys.COUNT) + (0 until count).map(Int::toString))
}

private fun Bundle.requireExactKeys(expected: Set<String>) {
    check(keySet() == expected) { "unexpected runtime Bundle fields" }
}

private fun Bundle.requiredBoundedString(
    key: String,
    maxLength: Int = MAX_IDENTITY_LENGTH,
    allowEmpty: Boolean = false
): String {
    check(containsKey(key))
    return getString(key)?.also { value ->
        check(value.length <= maxLength && '\u0000' !in value)
        check(allowEmpty || value.isNotBlank())
    } ?: error("missing $key")
}

private fun Bundle.optionalBoundedString(
    key: String,
    maxLength: Int = MAX_IDENTITY_LENGTH
): String? {
    check(containsKey(key))
    return getString(key)?.also { value ->
        check(value.isNotBlank() && value.length <= maxLength && '\u0000' !in value)
    }
}

private fun Bundle.boundedStringList(
    key: String,
    maxCount: Int,
    maxLength: Int = MAX_IDENTITY_LENGTH
): List<String> {
    check(containsKey(key))
    return getStringArrayList(key)?.toList()?.also { values ->
        check(values.size <= maxCount)
        check(values.all { it.isNotBlank() && it.length <= maxLength && '\u0000' !in it })
    } ?: error("missing $key")
}

private fun Bundle.optionalDigest(key: String): String? = optionalBoundedString(key)?.also(::requireDigest)

private fun Bundle.digestList(key: String, maxCount: Int): List<String> =
    boundedStringList(key, maxCount, SHA_256_HEX_LENGTH).onEach(::requireDigest)

private fun requireDigest(value: String) {
    check(value.length == SHA_256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' })
}

private inline fun <reified T : Enum<T>> Bundle.requiredEnum(key: String): T =
    enumValueOf(requiredBoundedString(key))

private fun List<ResolvedComponent>.hasUniqueComponentNames(): Boolean =
    size == map { it.name }.distinct().size

private fun VirtualInstanceRuntime.authoritativeRuntimeTextSize(): Int {
    val snapshot = packageSnapshot
    fun Iterable<String?>.sizeOfText(): Int = sumOf { it?.length ?: 0 }
    fun Map<String, String>.sizeOfText(): Int = entries.sumOf { it.key.length + it.value.length }
    fun Map<String, VirtualMetaDataValue>.sizeOfTypedText(): Int =
        entries.sumOf { it.key.length + it.value.type.name.length + it.value.encodedValue.length }
    fun ResolvedIntentFilter.sizeOfText(): Int =
        listOf(actions, categories, dataSchemes, dataMimeTypes, dataAuthorities, dataPaths)
            .sumOf { it.sizeOfText() } +
            authorityEntries.sumOf { authority ->
                authority.host.length + (authority.port?.toString()?.length ?: 0)
            } +
            pathPatterns.sumOf { pattern -> pattern.path.length + pattern.type.name.length }
    fun VirtualProviderPathPattern.sizeOfText(): Int = path.length + type.name.length
    fun ResolvedComponent.sizeOfText(): Int = listOf(
        name,
        launchMode,
        processName,
        taskAffinity,
        screenOrientation,
        configChanges,
        permission,
        readPermission,
        writePermission,
        targetActivityName
    ).sizeOfText() + intentFilters.sizeOfText() + authorities.sizeOfText() +
        resolvedIntentFilters.sumOf { it.sizeOfText() } + metaData.sizeOfText() +
        typedMetaData.sizeOfTypedText() + uriPermissionPatterns.sumOf { it.sizeOfText() } +
        pathPermissions.sumOf { permission ->
            permission.pattern.sizeOfText() +
                listOf(permission.readPermission, permission.writePermission).sizeOfText()
        }
    return listOf(
        instanceId,
        hostPackageName,
        originPackageName,
        virtualPackageName,
        dataRoot,
        profile.name,
        processSlot,
        proxySlot,
        evidenceSessionId,
        engineSessionId,
        processName,
        snapshot.instanceId,
        snapshot.originPackageName,
        snapshot.virtualPackageName,
        snapshot.applicationLabel,
        snapshot.versionName,
        snapshot.sourceDir,
        snapshot.sourceSha256,
        snapshot.publicSourceDir,
        snapshot.dataDir,
        snapshot.nativeLibraryDir,
        snapshot.applicationClassName,
        snapshot.processName,
        snapshot.taskAffinity,
        snapshot.launcherActivityName,
        snapshot.originCertSha256
    ).sizeOfText() + listOf(
        snapshot.splitSourceDirs,
        snapshot.splitSha256s,
        snapshot.splitPublicSourceDirs,
        snapshot.splitNames,
        snapshot.nativeLibraries,
        snapshot.abiList,
        snapshot.permissions,
        snapshot.signerSha256Digests
    ).sumOf { it.sizeOfText() } + snapshot.metaData.sizeOfText() +
        snapshot.typedMetaData.sizeOfTypedText() +
        listOf(snapshot.activities, snapshot.services, snapshot.receivers, snapshot.providers)
            .sumOf { components -> components.sumOf { it.sizeOfText() } }
}

private object RuntimeCodecKeys {
    const val SCHEMA_VERSION = "runtimeCodecSchemaVersion"
    const val HOST_PACKAGE_NAME = "hostPackageName"
    const val DATA_ROOT = "dataRoot"
    const val PACKAGE_SNAPSHOT = "packageSnapshot"
    const val APPLICATION_LABEL = "applicationLabel"
    const val VERSION_CODE = "versionCode"
    const val VERSION_NAME = "versionName"
    const val TARGET_SDK = "targetSdk"
    const val MIN_SDK = "minSdk"
    const val SOURCE_DIR = "sourceDir"
    const val SOURCE_SHA256 = "sourceSha256"
    const val PUBLIC_SOURCE_DIR = "publicSourceDir"
    const val SPLIT_SOURCE_DIRS = "splitSourceDirs"
    const val SPLIT_SHA256S = "splitSha256s"
    const val SPLIT_PUBLIC_SOURCE_DIRS = "splitPublicSourceDirs"
    const val SPLIT_NAMES = "splitNames"
    const val ISOLATED_SPLITS = "isolatedSplits"
    const val DATA_DIR = "dataDir"
    const val NATIVE_LIBRARY_DIR = "nativeLibraryDir"
    const val NATIVE_LIBRARIES = "nativeLibraries"
    const val ABI_LIST = "abiList"
    const val APPLICATION_CLASS_NAME = "applicationClassName"
    const val PACKAGE_PROCESS_NAME = "packageProcessName"
    const val TASK_AFFINITY = "taskAffinity"
    const val THEME_ID = "themeId"
    const val META_DATA = "metaData"
    const val TYPED_META_DATA = "typedMetaData"
    const val LAUNCHER_ACTIVITY_NAME = "launcherActivityName"
    const val ACTIVITIES = "activities"
    const val SERVICES = "services"
    const val RECEIVERS = "receivers"
    const val PROVIDERS = "providers"
    const val PERMISSIONS = "permissions"
    const val ORIGIN_CERT_SHA256 = "originCertSha256"
    const val SIGNER_SHA256_DIGESTS = "signerSha256Digests"
    const val HAS_MULTIPLE_SIGNERS = "hasMultipleSigners"
    const val COUNT = "count"
    const val NAME = "name"
    const val EXPORTED = "exported"
    const val INTENT_FILTERS = "intentFilters"
    const val RESOLVED_INTENT_FILTERS = "resolvedIntentFilters"
    const val AUTHORITIES = "authorities"
    const val LAUNCH_MODE = "launchMode"
    const val COMPONENT_PROCESS_NAME = "componentProcessName"
    const val COMPONENT_TASK_AFFINITY = "componentTaskAffinity"
    const val COMPONENT_THEME_ID = "componentThemeId"
    const val SCREEN_ORIENTATION = "screenOrientation"
    const val CONFIG_CHANGES = "configChanges"
    const val PERMISSION = "permission"
    const val READ_PERMISSION = "readPermission"
    const val WRITE_PERMISSION = "writePermission"
    const val GRANT_URI_PERMISSIONS = "grantUriPermissions"
    const val PATH_PERMISSIONS = "pathPermissions"
    const val URI_PERMISSION_PATTERNS = "uriPermissionPatterns"
    const val COMPONENT_META_DATA = "componentMetaData"
    const val COMPONENT_TYPED_META_DATA = "componentTypedMetaData"
    const val TARGET_ACTIVITY_NAME = "targetActivityName"
    const val ACTIONS = "actions"
    const val CATEGORIES = "categories"
    const val DATA_SCHEMES = "dataSchemes"
    const val DATA_MIME_TYPES = "dataMimeTypes"
    const val DATA_AUTHORITIES = "dataAuthorities"
    const val DATA_PATHS = "dataPaths"
    const val AUTHORITY_ENTRIES = "authorityEntries"
    const val PATH_PATTERNS = "pathPatterns"
    const val HOST = "host"
    const val PORT = "port"
    const val PRIORITY = "priority"
    const val PATTERN = "pattern"
    const val PATH = "path"
    const val TYPE = "type"
    const val KEY = "key"
    const val VALUE = "value"
}

private val RUNTIME_FIELDS = setOf(
    RuntimeCodecKeys.SCHEMA_VERSION,
    EngineRuntimeIpcContract.KEY_FOUND,
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    RuntimeCodecKeys.HOST_PACKAGE_NAME,
    EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME,
    EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME,
    RuntimeCodecKeys.DATA_ROOT,
    EngineRuntimeIpcContract.KEY_ENGINE_PROFILE,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_PROXY_SLOT,
    EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID,
    EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH,
    EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID,
    EngineRuntimeIpcContract.KEY_PROCESS_ID,
    EngineRuntimeIpcContract.KEY_PROCESS_NAME,
    EngineRuntimeIpcContract.KEY_RUNTIME_STATE,
    RuntimeCodecKeys.PACKAGE_SNAPSHOT
)

private val PACKAGE_FIELDS = setOf(
    RuntimeCodecKeys.SCHEMA_VERSION,
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME,
    EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME,
    RuntimeCodecKeys.APPLICATION_LABEL,
    RuntimeCodecKeys.VERSION_CODE,
    RuntimeCodecKeys.VERSION_NAME,
    RuntimeCodecKeys.TARGET_SDK,
    RuntimeCodecKeys.MIN_SDK,
    RuntimeCodecKeys.SOURCE_DIR,
    RuntimeCodecKeys.SOURCE_SHA256,
    RuntimeCodecKeys.PUBLIC_SOURCE_DIR,
    RuntimeCodecKeys.SPLIT_SOURCE_DIRS,
    RuntimeCodecKeys.SPLIT_SHA256S,
    RuntimeCodecKeys.SPLIT_PUBLIC_SOURCE_DIRS,
    RuntimeCodecKeys.SPLIT_NAMES,
    RuntimeCodecKeys.ISOLATED_SPLITS,
    RuntimeCodecKeys.DATA_DIR,
    RuntimeCodecKeys.NATIVE_LIBRARY_DIR,
    RuntimeCodecKeys.NATIVE_LIBRARIES,
    RuntimeCodecKeys.ABI_LIST,
    RuntimeCodecKeys.APPLICATION_CLASS_NAME,
    RuntimeCodecKeys.PACKAGE_PROCESS_NAME,
    RuntimeCodecKeys.TASK_AFFINITY,
    RuntimeCodecKeys.THEME_ID,
    RuntimeCodecKeys.META_DATA,
    RuntimeCodecKeys.TYPED_META_DATA,
    RuntimeCodecKeys.LAUNCHER_ACTIVITY_NAME,
    RuntimeCodecKeys.ACTIVITIES,
    RuntimeCodecKeys.SERVICES,
    RuntimeCodecKeys.RECEIVERS,
    RuntimeCodecKeys.PROVIDERS,
    RuntimeCodecKeys.PERMISSIONS,
    RuntimeCodecKeys.ORIGIN_CERT_SHA256,
    RuntimeCodecKeys.SIGNER_SHA256_DIGESTS,
    RuntimeCodecKeys.HAS_MULTIPLE_SIGNERS
)

private val COMPONENT_FIELDS = setOf(
    RuntimeCodecKeys.NAME,
    RuntimeCodecKeys.EXPORTED,
    RuntimeCodecKeys.INTENT_FILTERS,
    RuntimeCodecKeys.RESOLVED_INTENT_FILTERS,
    RuntimeCodecKeys.AUTHORITIES,
    RuntimeCodecKeys.LAUNCH_MODE,
    RuntimeCodecKeys.COMPONENT_PROCESS_NAME,
    RuntimeCodecKeys.COMPONENT_TASK_AFFINITY,
    RuntimeCodecKeys.COMPONENT_THEME_ID,
    RuntimeCodecKeys.SCREEN_ORIENTATION,
    RuntimeCodecKeys.CONFIG_CHANGES,
    RuntimeCodecKeys.PERMISSION,
    RuntimeCodecKeys.READ_PERMISSION,
    RuntimeCodecKeys.WRITE_PERMISSION,
    RuntimeCodecKeys.GRANT_URI_PERMISSIONS,
    RuntimeCodecKeys.PATH_PERMISSIONS,
    RuntimeCodecKeys.URI_PERMISSION_PATTERNS,
    RuntimeCodecKeys.COMPONENT_META_DATA,
    RuntimeCodecKeys.COMPONENT_TYPED_META_DATA,
    RuntimeCodecKeys.TARGET_ACTIVITY_NAME
)

private val INTENT_FILTER_FIELDS = setOf(
    RuntimeCodecKeys.ACTIONS,
    RuntimeCodecKeys.CATEGORIES,
    RuntimeCodecKeys.DATA_SCHEMES,
    RuntimeCodecKeys.DATA_MIME_TYPES,
    RuntimeCodecKeys.DATA_AUTHORITIES,
    RuntimeCodecKeys.DATA_PATHS,
    RuntimeCodecKeys.AUTHORITY_ENTRIES,
    RuntimeCodecKeys.PATH_PATTERNS,
    RuntimeCodecKeys.PRIORITY
)
private val INTENT_AUTHORITY_FIELDS = setOf(RuntimeCodecKeys.HOST, RuntimeCodecKeys.PORT)
private val INTENT_PATH_PATTERN_FIELDS = setOf(RuntimeCodecKeys.PATH, RuntimeCodecKeys.TYPE)
private val PATH_PERMISSION_FIELDS = setOf(
    RuntimeCodecKeys.PATTERN,
    RuntimeCodecKeys.READ_PERMISSION,
    RuntimeCodecKeys.WRITE_PERMISSION
)
private val PATH_PATTERN_FIELDS = setOf(RuntimeCodecKeys.PATH, RuntimeCodecKeys.TYPE)
private val STRING_MAP_ENTRY_FIELDS = setOf(RuntimeCodecKeys.KEY, RuntimeCodecKeys.VALUE)
private val TYPED_META_DATA_ENTRY_FIELDS = setOf(
    RuntimeCodecKeys.KEY,
    RuntimeCodecKeys.TYPE,
    RuntimeCodecKeys.VALUE
)

private const val AUTHORITATIVE_RUNTIME_SCHEMA_VERSION = 1
private const val PACKAGE_SNAPSHOT_SCHEMA_VERSION = 2
private const val MAX_IDENTITY_LENGTH = 1_024
private const val MAX_PATH_LENGTH = 4_096
private const val MAX_META_DATA_VALUE_LENGTH = 16_384
private const val MAX_TOTAL_TEXT_LENGTH = 512 * 1_024
private const val MAX_SPLIT_COUNT = 256
private const val MAX_NATIVE_ENTRY_COUNT = 4_096
private const val MAX_PERMISSION_COUNT = 4_096
private const val MAX_SIGNER_COUNT = 32
private const val MAX_COMPONENT_COUNT = 4_096
private const val MAX_FILTER_COUNT = 256
private const val MAX_FILTER_VALUE_COUNT = 256
private const val MAX_AUTHORITY_COUNT = 256
private const val MAX_PATH_POLICY_COUNT = 256
private const val MAX_META_DATA_COUNT = 4_096
private const val SHA_256_HEX_LENGTH = 64
private const val NO_AUTHORITY_PORT = -1
