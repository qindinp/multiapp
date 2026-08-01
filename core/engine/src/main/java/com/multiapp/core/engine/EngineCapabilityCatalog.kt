package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineCapability
import com.multiapp.core.model.engine.EngineCapabilityReport
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem

/**
 * Authoritative capability projection for release and diagnostics consumers.
 *
 * The catalog intentionally reports missing device proof as PARTIAL even when
 * the local control-plane contract is implemented. This prevents JVM-only
 * evidence from being promoted to a commercial PASS.
 */
internal object EngineCapabilityCatalog {
    fun report(
        server: VirtualSystemServer,
        instanceId: String?,
        systemServiceProxyRegistry: EngineSystemServiceProxyRegistry? = null,
        generatedAtMs: Long = System.currentTimeMillis()
    ): EngineCapabilityReport {
        val normalizedInstanceId = instanceId?.takeIf { it.isNotBlank() }
        val runtime = normalizedInstanceId?.let(server.runtimeService::get)
        val bindings = if (runtime == null) {
            emptyMap()
        } else {
            listOf(
                server.activityService.queryRuntimeBinding(runtime.instanceId),
                server.providerService.queryRuntimeBinding(runtime.instanceId),
                server.permissionService.queryRuntimeBinding(runtime.instanceId),
                server.appOpsService.queryRuntimeBinding(runtime.instanceId),
                server.serviceService.queryRuntimeBinding(runtime.instanceId),
                server.broadcastService.queryRuntimeBinding(runtime.instanceId),
                server.storageService.queryRuntimeBinding(runtime.instanceId),
                server.nativeService.queryRuntimeBinding(runtime.instanceId)
            ).associateBy(VirtualSubsystemRuntimeBinding::subsystem)
        }

        val capabilities = buildList {
            add(
                EngineCapability(
                    id = "package",
                    subsystem = EngineSubsystem.PACKAGE,
                    status = EngineResultStatus.PARTIAL,
                    releaseCritical = true,
                    supportedOperations = setOf(
                        "snapshot",
                        "component-query",
                        "intent-resolution",
                        "signing-identity",
                        "enabled-state"
                    ),
                    unsupportedOperations = setOf("package-refresh-device-proof"),
                    requiredDeviceEvidence = BASE_DEVICE_EVIDENCE,
                    message = "authoritative package semantics exist; update and device proof remain open"
                )
            )
            addSubsystem("activity", EngineSubsystem.ACTIVITY, bindings, true)
            addSubsystem("provider", EngineSubsystem.PROVIDER, bindings, true)
            addSubsystem("permission", EngineSubsystem.PERMISSION, bindings, true)
            addSubsystem("app-ops", EngineSubsystem.APP_OPS, bindings, true)
            addSubsystem("service", EngineSubsystem.SERVICE, bindings, true)
            addSubsystem("broadcast", EngineSubsystem.BROADCAST, bindings, true)
            addSubsystem("storage", EngineSubsystem.STORAGE, bindings, true)
            addSubsystem("native", EngineSubsystem.NATIVE, bindings, true)

            val systemServiceBindings = if (runtime == null) {
                emptyMap()
            } else {
                systemServiceProxyRegistry
                    ?.snapshot(runtime.instanceId)
                    .orEmpty()
                    .associateBy(EngineSystemServiceBinding::serviceId)
            }
            addAll(EngineSystemServiceCatalog.capabilities(systemServiceBindings))
        }
        val status = capabilities.fold(EngineResultStatus.PASS) { current, capability ->
            current.worst(capability.status)
        }
        val instanceMessage = when {
            normalizedInstanceId == null -> "static engine capability catalog"
            runtime == null -> "runtime_not_found:$normalizedInstanceId"
            else -> "runtime capability catalog"
        }
        return EngineCapabilityReport(
            instanceId = normalizedInstanceId,
            status = if (normalizedInstanceId != null && runtime == null) EngineResultStatus.FAIL else status,
            capabilities = capabilities,
            generatedAtMs = generatedAtMs.coerceAtLeast(0L),
            message = instanceMessage
        )
    }

    private fun MutableList<EngineCapability>.addSubsystem(
        id: String,
        subsystem: EngineSubsystem,
        bindings: Map<EngineSubsystem, VirtualSubsystemRuntimeBinding>,
        releaseCritical: Boolean
    ) {
        val binding = bindings[subsystem]
        val declared = DECLARED_SUBSYSTEM_OPERATIONS.getValue(subsystem)
        val supported = binding?.supportedOperations?.takeIf { it.isNotEmpty() } ?: declared.first
        val unsupported = binding?.unsupportedOperations?.takeIf { it.isNotEmpty() } ?: declared.second
        val status = when {
            binding?.verdict == EngineResultStatus.FAIL -> EngineResultStatus.FAIL
            unsupported.isNotEmpty() -> EngineResultStatus.PARTIAL
            binding == null -> EngineResultStatus.PARTIAL
            else -> binding.verdict
        }
        add(
            EngineCapability(
                id = id,
                subsystem = subsystem,
                status = status,
                releaseCritical = releaseCritical,
                supportedOperations = supported,
                unsupportedOperations = unsupported,
                requiredDeviceEvidence = BASE_DEVICE_EVIDENCE,
                message = binding?.message ?: "declared capability; runtime binding not requested"
            )
        )
    }

    private val BASE_DEVICE_EVIDENCE = setOf(
        "current-apk-sha256",
        "api-28-37-device-matrix",
        "cold-launch-first-frame",
        "process-death-recovery",
        "two-instance-isolation"
    )

    private val DECLARED_SUBSYSTEM_OPERATIONS = mapOf(
        EngineSubsystem.ACTIVITY to (
            setOf("launch", "proxy-slot", "process-slot", "launch-mode-slot", "proxy-process-death-recovery-evidence", "task-state-persistence", "lifecycle-state-persistence", "finish-record", "result-record", "on-new-intent-record", "back-stack-state", "result-delivery", "finish-result-delivery") to
                setOf("recents-device-proof")
            ),
        EngineSubsystem.PROVIDER to (
            setOf("route-token", "same-process-preinstall", "authority-lookup", "operation-route-plan", "uri-grant-record", "uri-grant-check", "uri-grant-revoke", "persisted-uri-grant-take", "persisted-uri-grant-release", "custom-process-provider") to
                setOf("external-uri-grant")
            ),
        EngineSubsystem.PERMISSION to (
            setOf("check-permission", "persistent-instance-grant", "explicit-grant", "explicit-revoke", "permission-flags", "one-time-permission") to
                setOf("runtime-permission-dialog", "auto-reset", "shared-uid-permission")
            ),
        EngineSubsystem.APP_OPS to (
            setOf("check-operation", "check-operation-raw", "persistent-instance-mode", "note-operation", "start-operation", "finish-operation") to
                setOf("attribution-chain")
            ),
        EngineSubsystem.SERVICE to (
            setOf("manifest-route-plan", "explicit-service-route", "implicit-service-route", "start-service-dispatch", "stop-service-route", "on-start-command-result", "bind-service", "unbind-service", "on-bind-result", "on-unbind-result", "process-slot-service-stub", "sticky-restart", "cross-process-service", "binder-death-rebind") to
                emptySet()
            ),
        EngineSubsystem.BROADCAST to (
            setOf("manifest-route-plan", "explicit-receiver-route", "implicit-receiver-route", "ordered-dispatch", "receiver-permission-filter", "receiver-app-op", "abort", "result-receiver", "sticky", "as-user", "broadcast-options") to
                setOf("cross-process-route")
            ),
        EngineSubsystem.STORAGE to (
            setOf("java-private-path", "process-slot-native-binding", "canonical-containment", "external-storage-policy") to
                setOf("media-provider-isolation")
            ),
        EngineSubsystem.NATIVE to (
            setOf("private-path-redirect", "path-containment", "process-slot-binding", "linker-namespace") to
                setOf("runtime-native-load", "register-natives-verdict")
            )
    )
}

private fun EngineResultStatus.worst(other: EngineResultStatus): EngineResultStatus =
    if (rank() >= other.rank()) this else other

private fun EngineResultStatus.rank(): Int = when (this) {
    EngineResultStatus.PASS -> 0
    EngineResultStatus.PARTIAL -> 1
    EngineResultStatus.UNSUPPORTED -> 2
    EngineResultStatus.FAIL -> 3
}
