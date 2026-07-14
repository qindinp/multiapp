package com.multiapp.core.engine

import android.os.Bundle
import com.multiapp.core.loader.VirtualPackageEnabledComponentType as LoaderEnabledComponentType
import com.multiapp.core.loader.VirtualPackageEnabledStateDispatchResult as LoaderEnabledStateResult
import com.multiapp.core.loader.VirtualPackageEnabledStateDispatcher as LoaderEnabledStateDispatcher
import com.multiapp.core.loader.VirtualPackageEnabledStateOperation as LoaderEnabledStateOperation
import com.multiapp.core.loader.VirtualPackageEnabledStateRequest as LoaderEnabledStateRequest
import com.multiapp.core.loader.VirtualPackageEnabledStateTarget as LoaderEnabledStateTarget
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

internal data class EnginePackageEnabledStateIpcRequest(
    val identity: EngineProcessClientIdentity,
    val target: EnginePackageEnabledStateTarget,
    val componentType: VirtualPackageComponentType? = null,
    val className: String? = null,
    val newState: Int? = null
)

internal fun EngineProcessClientIdentity.toPackageEnabledStateRequestBundle(
    target: EnginePackageEnabledStateTarget,
    componentType: VirtualPackageComponentType? = null,
    className: String? = null,
    newState: Int? = null,
    bundleFactory: () -> Bundle = { Bundle() }
): Bundle = bundleFactory().apply {
    putInt(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_SCHEMA_VERSION, PACKAGE_STATE_IPC_SCHEMA_VERSION)
    putString(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_TARGET, target.name)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, processId)
    if (target == EnginePackageEnabledStateTarget.COMPONENT) {
        putString(EngineRuntimeIpcContract.KEY_COMPONENT_TYPE, componentType?.name)
        putString(EngineRuntimeIpcContract.KEY_COMPONENT_CLASS_NAME, className)
    }
    newState?.let { state -> putInt(EngineRuntimeIpcContract.KEY_ENABLED_STATE, state) }
}

internal fun Bundle.toPackageEnabledStateRequestOrNull(
    expectedTarget: EnginePackageEnabledStateTarget,
    expectsMutation: Boolean
): EnginePackageEnabledStateIpcRequest? = runCatching {
    check(keySet() == packageStateRequestFields(expectedTarget, expectsMutation)) {
        "unexpected package enabled-state request fields"
    }
    check(strictInt(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_SCHEMA_VERSION) ==
        PACKAGE_STATE_IPC_SCHEMA_VERSION) {
        "unsupported package enabled-state request schema"
    }
    val target = requiredBoundedString(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_TARGET)
        .let(EnginePackageEnabledStateTarget::valueOf)
    check(target == expectedTarget) { "package enabled-state target mismatch" }
    val identity = EngineProcessClientIdentity(
        instanceId = requiredBoundedString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
        runtimeEpoch = requireNotNull(strictLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH)),
        engineSessionId = requiredBoundedString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID),
        processSlot = requiredBoundedString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
        processId = requireNotNull(strictInt(EngineRuntimeIpcContract.KEY_PROCESS_ID))
    )
    val componentType = if (target == EnginePackageEnabledStateTarget.COMPONENT) {
        requiredBoundedString(EngineRuntimeIpcContract.KEY_COMPONENT_TYPE)
            .let(VirtualPackageComponentType::valueOf)
    } else {
        null
    }
    val className = if (target == EnginePackageEnabledStateTarget.COMPONENT) {
        requiredBoundedString(EngineRuntimeIpcContract.KEY_COMPONENT_CLASS_NAME)
            .also { value -> check(value.none(Char::isISOControl)) }
    } else {
        null
    }
    EnginePackageEnabledStateIpcRequest(
        identity = identity,
        target = target,
        componentType = componentType,
        className = className,
        newState = if (expectsMutation) {
            requireNotNull(strictInt(EngineRuntimeIpcContract.KEY_ENABLED_STATE))
        } else {
            null
        }
    )
}.getOrNull()

internal fun VirtualPackageEnabledStateResult.toPackageEnabledStateIpcBundle(
    identity: EngineProcessClientIdentity,
    bundleFactory: () -> Bundle = { Bundle() }
): Bundle = bundleFactory().apply {
    putInt(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_SCHEMA_VERSION, PACKAGE_STATE_IPC_SCHEMA_VERSION)
    putString(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_TARGET, target.name)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, identity.runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, identity.engineSessionId)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, identity.processSlot)
    putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, identity.processId)
    putString(EngineRuntimeIpcContract.KEY_COMPONENT_TYPE, componentType?.name.orEmpty())
    putString(EngineRuntimeIpcContract.KEY_COMPONENT_CLASS_NAME, className.orEmpty())
    putInt(EngineRuntimeIpcContract.KEY_ENABLED_STATE, enabledState ?: INVALID_ENABLED_STATE)
    putBoolean(EngineRuntimeIpcContract.KEY_FOUND, found)
    putBoolean(EngineRuntimeIpcContract.KEY_CHANGED, changed)
    putString(EngineRuntimeIpcContract.KEY_STATUS, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

internal fun Bundle.toPackageEnabledStateResultOrNull(): VirtualPackageEnabledStateResult? = runCatching {
    check(keySet() == PACKAGE_STATE_RESPONSE_FIELDS) {
        "unexpected package enabled-state response fields"
    }
    check(strictInt(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_SCHEMA_VERSION) ==
        PACKAGE_STATE_IPC_SCHEMA_VERSION) {
        "unsupported package enabled-state response schema"
    }
    val target = requiredBoundedString(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_TARGET)
        .let(EnginePackageEnabledStateTarget::valueOf)
    val identity = EngineProcessClientIdentity(
        instanceId = requiredBoundedString(EngineRuntimeIpcContract.KEY_INSTANCE_ID),
        runtimeEpoch = requireNotNull(strictLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH)),
        engineSessionId = requiredBoundedString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID),
        processSlot = requiredBoundedString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
        processId = requireNotNull(strictInt(EngineRuntimeIpcContract.KEY_PROCESS_ID))
    )
    val componentTypeText = requireNotNull(
        strictString(EngineRuntimeIpcContract.KEY_COMPONENT_TYPE)
    )
    val classNameText = requireNotNull(
        strictString(EngineRuntimeIpcContract.KEY_COMPONENT_CLASS_NAME)
    )
    check(componentTypeText.length <= MAX_PACKAGE_STATE_IDENTITY_LENGTH)
    check(classNameText.length <= MAX_PACKAGE_STATE_IDENTITY_LENGTH)
    val componentType = componentTypeText.takeIf(String::isNotEmpty)
        ?.let(VirtualPackageComponentType::valueOf)
    val className = classNameText.takeIf(String::isNotEmpty)
    when (target) {
        EnginePackageEnabledStateTarget.APPLICATION -> {
            check(componentType == null && className == null)
        }

        EnginePackageEnabledStateTarget.COMPONENT -> {
            check((componentType == null) == (className == null))
        }
    }
    val verdict = requiredBoundedString(EngineRuntimeIpcContract.KEY_STATUS)
        .let(EngineResultStatus::valueOf)
    val enabledStateValue = requireNotNull(strictInt(EngineRuntimeIpcContract.KEY_ENABLED_STATE))
    val enabledState = enabledStateValue.takeUnless { state -> state == INVALID_ENABLED_STATE }
    val found = requireNotNull(strictBoolean(EngineRuntimeIpcContract.KEY_FOUND))
    val changed = requireNotNull(strictBoolean(EngineRuntimeIpcContract.KEY_CHANGED))
    check(verdict == EngineResultStatus.PASS || !changed)
    VirtualPackageEnabledStateResult(
        instanceId = identity.instanceId,
        target = target,
        componentType = componentType,
        className = className,
        enabledState = enabledState,
        verdict = verdict,
        found = found,
        changed = changed,
        authorityIdentity = identity,
        message = requiredBoundedString(EngineRuntimeIpcContract.KEY_MESSAGE)
    )
}.getOrNull()

class IpcBackedVirtualPackageService(
    @Suppress("UNUSED_PARAMETER") fallback: VirtualPackageService? = null,
    private val remoteRuntime: (String) -> VirtualInstanceRuntime? =
        EngineRuntimeIpcClients::engineQueryRuntimeState,
    private val remoteQueryApplication: (EngineProcessClientIdentity) -> VirtualPackageEnabledStateResult? =
        EngineRuntimeIpcClients::queryApplicationEnabledState,
    private val remoteSetApplication: (EngineProcessClientIdentity, Int) -> VirtualPackageEnabledStateResult? =
        EngineRuntimeIpcClients::setApplicationEnabledState,
    private val remoteQueryComponent: (
        EngineProcessClientIdentity,
        VirtualPackageComponentType,
        String
    ) -> VirtualPackageEnabledStateResult? = EngineRuntimeIpcClients::queryComponentEnabledState,
    private val remoteSetComponent: (
        EngineProcessClientIdentity,
        VirtualPackageComponentType,
        String,
        Int
    ) -> VirtualPackageEnabledStateResult? = EngineRuntimeIpcClients::setComponentEnabledState,
    private val authorityConnected: () -> Boolean = EngineRuntimeIpcClients::isConnected
) : VirtualPackageService {
    override val subsystem = com.multiapp.core.model.engine.EngineSubsystem.PACKAGE

    override fun queryPackageSnapshot(instanceId: String): VirtualPackageSnapshot? =
        authoritativeRuntime(instanceId)?.packageSnapshot

    override fun queryPackageIdentity(instanceId: String): Result<VirtualPackageIdentity> {
        val runtime = authoritativeRuntime(instanceId)
            ?: return Result.failure(IllegalStateException(packageAuthorityFailure("query-identity")))
        return Result.success(VirtualPackageIdentity.from(runtime))
    }

    override fun queryComponent(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): ResolvedComponent? {
        val snapshot = queryPackageSnapshot(instanceId) ?: return null
        val normalized = normalizeVirtualComponentClassName(snapshot.originPackageName, className)
            ?: return null
        return snapshot.componentsForEnabledState(type).firstOrNull { component ->
            normalizeVirtualComponentClassName(snapshot.originPackageName, component.name) == normalized ||
                component.targetActivityName?.let { target ->
                    normalizeVirtualComponentClassName(snapshot.originPackageName, target)
                } == normalized
        }
    }

    override fun queryProviderByAuthority(instanceId: String, authority: String): ResolvedComponent? =
        queryPackageSnapshot(instanceId)?.providers?.firstOrNull { provider ->
            authority.isNotBlank() && authority in provider.authorities
        }

    override fun resolveIntent(
        instanceId: String,
        type: VirtualPackageComponentType,
        action: String,
        categories: Set<String>,
        dataScheme: String?,
        dataMimeType: String?,
        dataAuthority: String?,
        dataPath: String?
    ): List<ResolvedComponent> {
        if (action.isBlank()) return emptyList()
        return queryPackageSnapshot(instanceId)?.componentsForEnabledState(type)
            ?.mapNotNull { component ->
                val priority = component.resolvedIntentFilters
                    .filter { filter ->
                        filter.matchesEngineQuery(
                            action,
                            categories,
                            dataScheme,
                            dataMimeType,
                            dataAuthority,
                            dataPath
                        )
                    }
                    .maxOfOrNull(ResolvedIntentFilter::priority)
                    ?: return@mapNotNull null
                component to priority
            }
            ?.sortedWith(compareByDescending<Pair<ResolvedComponent, Int>> { it.second }.thenBy { it.first.name })
            ?.map(Pair<ResolvedComponent, Int>::first)
            .orEmpty()
    }

    override fun queryApplicationEnabledState(instanceId: String): VirtualPackageEnabledStateResult {
        val identity = authoritativeIdentity(instanceId)
            ?: return ipcFailure(instanceId, EnginePackageEnabledStateTarget.APPLICATION, "query-application")
        return runCatching { remoteQueryApplication(identity) }.getOrNull()
            ?.takeIf { result -> result.matchesApplicationResponse(identity) }
            ?: ipcFailure(instanceId, EnginePackageEnabledStateTarget.APPLICATION, "query-application")
    }

    override fun setApplicationEnabledState(
        instanceId: String,
        newState: Int
    ): VirtualPackageEnabledStateResult {
        val identity = authoritativeIdentity(instanceId)
            ?: return ipcFailure(instanceId, EnginePackageEnabledStateTarget.APPLICATION, "set-application")
        return runCatching { remoteSetApplication(identity, newState) }.getOrNull()
            ?.takeIf { result -> result.matchesApplicationResponse(identity) }
            ?: ipcFailure(instanceId, EnginePackageEnabledStateTarget.APPLICATION, "set-application")
    }

    override fun queryComponentEnabledState(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): VirtualPackageEnabledStateResult {
        val request = componentRequest(instanceId, type, className)
            ?: return ipcComponentFailure(instanceId, type, null, "query-component")
        return runCatching {
            remoteQueryComponent(request.identity, type, request.className)
        }.getOrNull()?.takeIf { result -> result.matchesComponentResponse(request.identity, type, request.className) }
            ?: ipcComponentFailure(instanceId, type, request.className, "query-component")
    }

    override fun setComponentEnabledState(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String,
        newState: Int
    ): VirtualPackageEnabledStateResult {
        val request = componentRequest(instanceId, type, className)
            ?: return ipcComponentFailure(instanceId, type, null, "set-component")
        return runCatching {
            remoteSetComponent(request.identity, type, request.className, newState)
        }.getOrNull()?.takeIf { result -> result.matchesComponentResponse(request.identity, type, request.className) }
            ?: ipcComponentFailure(instanceId, type, request.className, "set-component")
    }

    private fun authoritativeRuntime(instanceId: String): VirtualInstanceRuntime? =
        runCatching { remoteRuntime(instanceId) }.getOrNull()?.takeIf { runtime ->
            runtime.instanceId == instanceId && runtime.packageSnapshot.instanceId == instanceId &&
                runtime.originPackageName == runtime.packageSnapshot.originPackageName &&
                runtime.virtualPackageName == runtime.packageSnapshot.virtualPackageName
        }

    private fun authoritativeIdentity(instanceId: String): EngineProcessClientIdentity? {
        val runtime = authoritativeRuntime(instanceId) ?: return null
        return runtime.processId?.let { processId ->
            runCatching {
                EngineProcessClientIdentity(
                    instanceId = runtime.instanceId,
                    runtimeEpoch = runtime.runtimeEpoch,
                    engineSessionId = runtime.engineSessionId,
                    processSlot = runtime.processSlot,
                    processId = processId
                )
            }.getOrNull()
        }
    }

    private fun componentRequest(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): NormalizedComponentRequest? {
        val runtime = authoritativeRuntime(instanceId) ?: return null
        val identity = authoritativeIdentity(instanceId) ?: return null
        val normalized = normalizeVirtualComponentClassName(runtime.originPackageName, className)
            ?: return null
        return NormalizedComponentRequest(identity, type, normalized)
    }

    private fun VirtualPackageEnabledStateResult.matchesApplicationResponse(
        identity: EngineProcessClientIdentity
    ): Boolean = instanceId == identity.instanceId && authorityIdentity == identity &&
        target == EnginePackageEnabledStateTarget.APPLICATION && componentType == null && className == null

    private fun VirtualPackageEnabledStateResult.matchesComponentResponse(
        identity: EngineProcessClientIdentity,
        type: VirtualPackageComponentType,
        className: String
    ): Boolean = instanceId == identity.instanceId && authorityIdentity == identity &&
        target == EnginePackageEnabledStateTarget.COMPONENT && componentType == type &&
        this.className == className

    private fun ipcFailure(
        instanceId: String,
        target: EnginePackageEnabledStateTarget,
        operation: String
    ) = VirtualPackageEnabledStateResult(
        instanceId = instanceId.ifBlank { "invalid" },
        target = target,
        verdict = EngineResultStatus.FAIL,
        found = false,
        message = packageAuthorityFailure(operation)
    )

    private fun ipcComponentFailure(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String?,
        operation: String
    ) = VirtualPackageEnabledStateResult(
        instanceId = instanceId.ifBlank { "invalid" },
        target = EnginePackageEnabledStateTarget.COMPONENT,
        componentType = type.takeIf { className != null },
        className = className,
        verdict = EngineResultStatus.FAIL,
        found = false,
        message = packageAuthorityFailure(operation)
    )

    private fun packageAuthorityFailure(operation: String): String =
        if (authorityConnected()) {
            "engine_package_ipc_invalid:$operation"
        } else {
            "engine_package_authority_unavailable:$operation"
        }

    private data class NormalizedComponentRequest(
        val identity: EngineProcessClientIdentity,
        val type: VirtualPackageComponentType,
        val className: String
    )
}

internal class EngineVirtualPackageEnabledStateDispatcher(
    private val packageService: VirtualPackageService
) : LoaderEnabledStateDispatcher {
    override fun dispatch(request: LoaderEnabledStateRequest): LoaderEnabledStateResult {
        val snapshot = runCatching {
            packageService.queryPackageSnapshot(request.instanceId)
        }.getOrNull() ?: return LoaderEnabledStateResult.unavailable(
            "engine_package_snapshot_unavailable"
        )
        if (!snapshot.matchesPackageName(request.packageName)) {
            return LoaderEnabledStateResult.unavailable(
                "engine_package_identity_mismatch"
            )
        }

        val result = when (request.target) {
            LoaderEnabledStateTarget.APPLICATION -> when (request.operation) {
                LoaderEnabledStateOperation.QUERY ->
                    packageService.queryApplicationEnabledState(request.instanceId)

                LoaderEnabledStateOperation.SET ->
                    packageService.setApplicationEnabledState(
                        request.instanceId,
                        requireNotNull(request.newState)
                    )
            }

            LoaderEnabledStateTarget.COMPONENT -> {
                val componentType = requireNotNull(request.componentType).toEngineComponentType()
                val className = requireNotNull(request.className)
                when (request.operation) {
                    LoaderEnabledStateOperation.QUERY ->
                        packageService.queryComponentEnabledState(
                            request.instanceId,
                            componentType,
                            className
                        )

                    LoaderEnabledStateOperation.SET ->
                        packageService.setComponentEnabledState(
                            request.instanceId,
                            componentType,
                            className,
                            requireNotNull(request.newState)
                        )
                }
            }
        }
        val enabledState = result.enabledState
        if (
            result.verdict != EngineResultStatus.PASS ||
            result.authorityIdentity == null ||
            !result.found ||
            enabledState == null
        ) {
            return LoaderEnabledStateResult.unavailable(result.message)
        }
        return LoaderEnabledStateResult(
            authoritative = true,
            found = true,
            enabledState = enabledState,
            changed = result.changed,
            reason = result.message
        )
    }

    private fun LoaderEnabledComponentType.toEngineComponentType(): VirtualPackageComponentType =
        VirtualPackageComponentType.valueOf(name)
}

private fun packageStateRequestFields(
    target: EnginePackageEnabledStateTarget,
    mutation: Boolean
): Set<String> = buildSet {
    add(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_SCHEMA_VERSION)
    add(EngineRuntimeIpcContract.KEY_PACKAGE_STATE_TARGET)
    add(EngineRuntimeIpcContract.KEY_INSTANCE_ID)
    add(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH)
    add(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID)
    add(EngineRuntimeIpcContract.KEY_PROCESS_SLOT)
    add(EngineRuntimeIpcContract.KEY_PROCESS_ID)
    if (target == EnginePackageEnabledStateTarget.COMPONENT) {
        add(EngineRuntimeIpcContract.KEY_COMPONENT_TYPE)
        add(EngineRuntimeIpcContract.KEY_COMPONENT_CLASS_NAME)
    }
    if (mutation) add(EngineRuntimeIpcContract.KEY_ENABLED_STATE)
}

private fun Bundle.requiredBoundedString(key: String): String =
    requireNotNull(strictString(key)).also { value ->
        check(value.isNotBlank() && value.length <= MAX_PACKAGE_STATE_IDENTITY_LENGTH)
    }

@Suppress("DEPRECATION")
private fun Bundle.strictString(key: String): String? =
    if (!containsKey(key)) null else runCatching { get(key) as? String }.getOrNull()

@Suppress("DEPRECATION")
private fun Bundle.strictInt(key: String): Int? =
    if (!containsKey(key)) null else runCatching { get(key) as? Int }.getOrNull()

@Suppress("DEPRECATION")
private fun Bundle.strictLong(key: String): Long? =
    if (!containsKey(key)) null else runCatching { get(key) as? Long }.getOrNull()

@Suppress("DEPRECATION")
private fun Bundle.strictBoolean(key: String): Boolean? =
    if (!containsKey(key)) null else runCatching { get(key) as? Boolean }.getOrNull()

private val PACKAGE_STATE_RESPONSE_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_PACKAGE_STATE_SCHEMA_VERSION,
    EngineRuntimeIpcContract.KEY_PACKAGE_STATE_TARGET,
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH,
    EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_PROCESS_ID,
    EngineRuntimeIpcContract.KEY_COMPONENT_TYPE,
    EngineRuntimeIpcContract.KEY_COMPONENT_CLASS_NAME,
    EngineRuntimeIpcContract.KEY_ENABLED_STATE,
    EngineRuntimeIpcContract.KEY_FOUND,
    EngineRuntimeIpcContract.KEY_CHANGED,
    EngineRuntimeIpcContract.KEY_STATUS,
    EngineRuntimeIpcContract.KEY_MESSAGE
)

private const val PACKAGE_STATE_IPC_SCHEMA_VERSION = 1
private const val INVALID_ENABLED_STATE = -1
private const val MAX_PACKAGE_STATE_IDENTITY_LENGTH = 1_024
