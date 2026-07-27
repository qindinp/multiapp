package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineCapability
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState

enum class EngineSystemServiceId {
    PACKAGE_MANAGER,
    NOTIFICATION,
    JOB_SCHEDULER,
    ALARM,
    PENDING_INTENT,
    ACCOUNT,
    APP_OPS,
    URI_GRANTS,
    CONTENT,
    STORAGE,
    MEDIA_PROVIDER,
    LOCATION,
    LAUNCHER_APPS,
    SHORTCUT,
    CLIPBOARD,
    WEBVIEW
}

data class EngineSystemServiceDescriptor(
    val id: EngineSystemServiceId,
    val minApi: Int = 28,
    val maxApi: Int = 37,
    val releaseCritical: Boolean,
    val baselineStatus: EngineResultStatus,
    val supportedOperations: Set<String> = emptySet(),
    val unsupportedOperations: Set<String> = emptySet(),
    val requiredDeviceEvidence: Set<String> = emptySet()
) {
    init {
        require(minApi in 1..maxApi) { "invalid API range for $id" }
        require(supportedOperations.none { it.isBlank() }) {
            "supportedOperations must not contain blank entries"
        }
        require(unsupportedOperations.none { it.isBlank() }) {
            "unsupportedOperations must not contain blank entries"
        }
    }

    fun supportsApi(apiLevel: Int): Boolean = apiLevel in minApi..maxApi
}

data class EngineSystemServiceBindRequest(
    val instanceId: String,
    val serviceId: EngineSystemServiceId,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val apiLevel: Int,
    val adapterId: String,
    val adapterInstalled: Boolean
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(runtimeEpoch > 0L) { "runtimeEpoch must be positive" }
        require(engineSessionId.isNotBlank()) { "engineSessionId must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(apiLevel > 0) { "apiLevel must be positive" }
        require(adapterId.isNotBlank()) { "adapterId must not be blank" }
    }
}

data class EngineSystemServiceBinding(
    val instanceId: String,
    val serviceId: EngineSystemServiceId,
    val runtimeEpoch: Long,
    val engineSessionId: String,
    val processSlot: String,
    val apiLevel: Int,
    val adapterId: String,
    val status: EngineResultStatus,
    val boundAtMs: Long
)

data class EngineSystemServiceBindResult(
    val verdict: EngineResultStatus,
    val binding: EngineSystemServiceBinding? = null,
    val message: String
)

/**
 * Engine-owned registry for framework-service proxy bindings.
 *
 * Android-specific Binder/reflection adapters live in core:loader, but their
 * identity, lifecycle and capability verdict are accepted only by this
 * registry. A stale runtime epoch/session/slot can therefore never silently
 * replace the active binding for another instance.
 */
class EngineSystemServiceProxyRegistry(
    private val runtimeService: VirtualRuntimeService,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val bindings = linkedMapOf<BindingKey, EngineSystemServiceBinding>()

    fun bind(request: EngineSystemServiceBindRequest): EngineSystemServiceBindResult {
        val runtime = runtimeService.get(request.instanceId)
            ?: return failed("runtime_not_found:${request.instanceId}")
        if (runtime.runtimeEpoch != request.runtimeEpoch) {
            return failed("runtime_epoch_mismatch")
        }
        if (runtime.engineSessionId != request.engineSessionId) {
            return failed("engine_session_mismatch")
        }
        if (runtime.processSlot != request.processSlot) {
            return failed("process_slot_mismatch")
        }
        if (!runtime.isBindingActive()) {
            return failed("runtime_not_active:${runtime.state.name.lowercase()}")
        }
        val descriptor = EngineSystemServiceCatalog.descriptor(request.serviceId)
        if (!descriptor.supportsApi(request.apiLevel)) {
            return EngineSystemServiceBindResult(
                verdict = EngineResultStatus.UNSUPPORTED,
                message = "api_level_unsupported:${request.apiLevel}"
            )
        }
        if (!request.adapterInstalled) {
            return failed("adapter_install_failed:${request.adapterId}")
        }
        if (descriptor.baselineStatus == EngineResultStatus.UNSUPPORTED) {
            return EngineSystemServiceBindResult(
                verdict = EngineResultStatus.UNSUPPORTED,
                message = "service_proxy_not_implemented:${request.serviceId.name.lowercase()}"
            )
        }
        val binding = EngineSystemServiceBinding(
            instanceId = runtime.instanceId,
            serviceId = request.serviceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processSlot = runtime.processSlot,
            apiLevel = request.apiLevel,
            adapterId = request.adapterId,
            status = descriptor.baselineStatus,
            boundAtMs = clock().coerceAtLeast(0L)
        )
        synchronized(lock) {
            val current = runtimeService.get(request.instanceId)
            if (current == null || !current.matches(request) || !current.isBindingActive()) {
                return failed("runtime_generation_changed")
            }
            bindings[BindingKey(binding.instanceId, binding.serviceId)] = binding
        }
        return EngineSystemServiceBindResult(
            verdict = binding.status,
            binding = binding,
            message = "system_service_proxy_bound"
        )
    }

    fun query(instanceId: String, serviceId: EngineSystemServiceId): EngineSystemServiceBinding? {
        val key = BindingKey(instanceId, serviceId)
        val binding = synchronized(lock) { bindings[key] } ?: return null
        if (isCurrent(binding)) return binding
        synchronized(lock) {
            if (bindings[key] == binding) bindings.remove(key)
        }
        return null
    }

    fun snapshot(instanceId: String? = null): List<EngineSystemServiceBinding> {
        val candidates = synchronized(lock) {
            bindings.values
                .filter { instanceId == null || it.instanceId == instanceId }
        }
        val current = candidates.filter(::isCurrent)
        val stale = candidates - current.toSet()
        if (stale.isNotEmpty()) {
            synchronized(lock) {
                stale.forEach { binding ->
                    val key = BindingKey(binding.instanceId, binding.serviceId)
                    if (bindings[key] == binding) bindings.remove(key)
                }
            }
        }
        return current
            .asSequence()
            .sortedWith(compareBy<EngineSystemServiceBinding>({ it.instanceId }, { it.serviceId.name }))
            .toList()
    }

    fun clearInstance(instanceId: String): Int {
        if (instanceId.isBlank()) return 0
        return synchronized(lock) {
            val keys = bindings.keys.filter { it.instanceId == instanceId }
            keys.forEach(bindings::remove)
            keys.size
        }
    }

    fun reconcileActiveRuntimes(): Int {
        val candidates = synchronized(lock) { bindings.values.toList() }
        val staleBindings = candidates.filterNot(::isCurrent)
        return synchronized(lock) {
            staleBindings.count { binding ->
                val key = BindingKey(binding.instanceId, binding.serviceId)
                bindings[key] == binding && bindings.remove(key) != null
            }
        }
    }

    fun revokeGeneration(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String
    ): Int {
        if (
            instanceId.isBlank() || runtimeEpoch <= 0L ||
            engineSessionId.isBlank() || processSlot.isBlank()
        ) {
            return 0
        }
        return synchronized(lock) {
            val keys = bindings
                .filterValues { binding ->
                    binding.instanceId == instanceId &&
                        binding.runtimeEpoch == runtimeEpoch &&
                        binding.engineSessionId == engineSessionId &&
                        binding.processSlot == processSlot
                }
                .keys
                .toList()
            keys.forEach(bindings::remove)
            keys.size
        }
    }

    private fun isCurrent(binding: EngineSystemServiceBinding): Boolean =
        runtimeService.get(binding.instanceId)?.let { runtime ->
            runtime.matches(binding) && runtime.isBindingActive()
        } == true

    private fun failed(message: String) = EngineSystemServiceBindResult(
        verdict = EngineResultStatus.FAIL,
        message = message
    )

    private data class BindingKey(
        val instanceId: String,
        val serviceId: EngineSystemServiceId
    )
}

private fun VirtualInstanceRuntime.matches(request: EngineSystemServiceBindRequest): Boolean =
    instanceId == request.instanceId &&
        runtimeEpoch == request.runtimeEpoch &&
        engineSessionId == request.engineSessionId &&
        processSlot == request.processSlot

private fun VirtualInstanceRuntime.matches(binding: EngineSystemServiceBinding): Boolean =
    instanceId == binding.instanceId &&
        runtimeEpoch == binding.runtimeEpoch &&
        engineSessionId == binding.engineSessionId &&
        processSlot == binding.processSlot

private fun VirtualInstanceRuntime.isBindingActive(): Boolean =
    state != VirtualRuntimeState.STOPPED && state != VirtualRuntimeState.DEAD

internal object EngineSystemServiceCatalog {
    private val deviceEvidence = setOf(
        "api-28-37-method-parity",
        "two-instance-isolation",
        "process-death-cleanup",
        "current-apk-device-proof"
    )

    private val descriptors = listOf(
        descriptor(
            EngineSystemServiceId.PACKAGE_MANAGER,
            releaseCritical = true,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("package-query", "component-query", "intent-resolution"),
            unsupported = setOf("device-proof")
        ),
        descriptor(
            EngineSystemServiceId.NOTIFICATION,
            releaseCritical = true,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("caller-package-remap"),
            unsupported = setOf("instance-id-map", "channel-map", "pending-intent-route", "delete-cleanup")
        ),
        descriptor(EngineSystemServiceId.JOB_SCHEDULER, true),
        descriptor(EngineSystemServiceId.ALARM, true),
        descriptor(EngineSystemServiceId.PENDING_INTENT, true),
        descriptor(EngineSystemServiceId.MEDIA_PROVIDER, true),
        descriptor(
            EngineSystemServiceId.WEBVIEW,
            releaseCritical = true,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("external-renderer-passthrough"),
            unsupported = setOf("data-directory-isolation", "chromium-jni-device-proof")
        ),
        descriptor(
            EngineSystemServiceId.APP_OPS,
            releaseCritical = true,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("check-operation", "caller-package-remap"),
            unsupported = setOf("note-operation", "start-operation", "finish-operation", "attribution-chain")
        ),
        descriptor(
            EngineSystemServiceId.URI_GRANTS,
            releaseCritical = true,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("internal-uri-grant"),
            unsupported = setOf("external-uri-grant")
        ),
        descriptor(
            EngineSystemServiceId.CONTENT,
            releaseCritical = true,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("uri-rewrite", "provider-route"),
            unsupported = setOf("custom-process-device-proof")
        ),
        descriptor(
            EngineSystemServiceId.STORAGE,
            releaseCritical = true,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("private-path", "app-scoped-mkdirs"),
            unsupported = setOf("external-policy", "media-isolation")
        ),
        descriptor(EngineSystemServiceId.ACCOUNT, false),
        descriptor(EngineSystemServiceId.LOCATION, false),
        descriptor(EngineSystemServiceId.SHORTCUT, false),
        descriptor(
            EngineSystemServiceId.LAUNCHER_APPS,
            releaseCritical = false,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("caller-package-remap"),
            unsupported = setOf("shortcut-ownership")
        ),
        descriptor(
            EngineSystemServiceId.CLIPBOARD,
            releaseCritical = false,
            status = EngineResultStatus.PARTIAL,
            supported = setOf("caller-package-remap", "host-shared-payload"),
            unsupported = setOf("instance-isolation", "api-37-device-proof")
        )
    ).associateBy(EngineSystemServiceDescriptor::id)

    fun descriptor(id: EngineSystemServiceId): EngineSystemServiceDescriptor = descriptors.getValue(id)

    fun capabilities(
        bindings: Map<EngineSystemServiceId, EngineSystemServiceBinding> = emptyMap()
    ): List<EngineCapability> = descriptors.values
        .sortedBy { it.id.name }
        .map { descriptor ->
            val binding = bindings[descriptor.id]
            EngineCapability(
                id = "system-service:${descriptor.id.name.lowercase()}",
                subsystem = EngineSubsystem.RUNTIME,
                status = binding?.status ?: descriptor.baselineStatus,
                releaseCritical = descriptor.releaseCritical,
                supportedOperations = descriptor.supportedOperations,
                unsupportedOperations = descriptor.unsupportedOperations,
                requiredDeviceEvidence = descriptor.requiredDeviceEvidence,
                message = if (binding == null) {
                    "engine-owned system service capability; adapter not bound"
                } else {
                    "engine-owned system service capability; bound=${binding.adapterId};api=${binding.apiLevel}"
                }
            )
        }

    private fun descriptor(
        id: EngineSystemServiceId,
        releaseCritical: Boolean,
        status: EngineResultStatus = EngineResultStatus.UNSUPPORTED,
        supported: Set<String> = emptySet(),
        unsupported: Set<String> = setOf("not-implemented")
    ) = EngineSystemServiceDescriptor(
        id = id,
        releaseCritical = releaseCritical,
        baselineStatus = status,
        supportedOperations = supported,
        unsupportedOperations = unsupported,
        requiredDeviceEvidence = deviceEvidence
    )
}
