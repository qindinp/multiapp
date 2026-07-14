package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityLaunchAllocation
import com.multiapp.core.loader.VirtualActivityLaunchAllocationProvider
import com.multiapp.core.loader.VirtualActivityLaunchAllocationRequest
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ProxyActivitySlotKey

class EngineActivityLaunchAllocator(
    private val runtimeRegistry: EngineRuntimeRegistry,
    private val activityService: VirtualActivityService,
    private val capabilities: EngineActivityLaunchCapabilityRegistry
) {
    fun allocate(
        request: VirtualActivityLaunchAllocationRequest,
        callingPid: Int
    ): VirtualActivityLaunchAllocation {
        val runtime = runtimeRegistry.get(request.instanceId)
            ?: return rejected(request, "activity_allocation_runtime_not_found")
        if (
            runtime.originPackageName != request.originPackageName ||
            runtime.processSlot != request.processSlot ||
            runtime.processId == null || runtime.processId != callingPid ||
            runtime.state !in setOf(VirtualRuntimeState.PREWARMED, VirtualRuntimeState.RUNNING)
        ) {
            return rejected(request, "activity_allocation_runtime_identity_mismatch")
        }
        if (runtime.packageSnapshot.activities.none { component ->
                component.name == request.guestActivityClassName ||
                    component.targetActivityName == request.guestActivityClassName
            }
        ) {
            return rejected(request, "activity_allocation_component_not_in_snapshot")
        }
        val key = request.slotKey()
        val candidates = EngineProxyActivitySlots.classNamesForProcessSlot(
            hostPackageName = runtime.hostPackageName,
            processSlot = runtime.processSlot,
            launchMode = key.launchMode
        )
        if (candidates.isEmpty()) return rejected(request, "activity_allocation_proxy_catalog_empty")
        var previous = activityService.queryProxyActivitySlot(runtime.instanceId, key)
        if (previous.verdict == EngineResultStatus.FAIL) {
            return rejected(request, "activity_allocation_query_failed:${previous.message}")
        }
        val staleProxy = previous.proxyActivityClassName?.takeIf { it !in candidates }
        if (staleProxy != null) {
            val cleared = activityService.compareAndSetProxyActivitySlot(
                runtime.instanceId,
                key,
                staleProxy,
                null
            )
            if (cleared.verdict != EngineResultStatus.PASS || !cleared.matched) {
                return rejected(request, "activity_allocation_stale_slot_clear_failed:${cleared.message}")
            }
            previous = activityService.queryProxyActivitySlot(runtime.instanceId, key)
            if (previous.verdict == EngineResultStatus.FAIL || previous.proxyActivityClassName != null) {
                return rejected(request, "activity_allocation_stale_slot_clear_unconfirmed:${previous.message}")
            }
        }
        val reservation = activityService.reserveProxyActivitySlot(runtime.instanceId, key, candidates)
        if (reservation.verdict != EngineResultStatus.PASS || reservation.proxyActivityClassName == null) {
            return rejected(request, "activity_allocation_reserve_failed:${reservation.message}")
        }
        val proxyActivityClassName = reservation.proxyActivityClassName
        val identity = runCatching {
            capabilities.issue(
                runtime = runtime,
                processId = callingPid,
                proxyActivityClassName = proxyActivityClassName,
                guestActivityClassName = request.guestActivityClassName,
                allocationKey = key,
                previousProxyActivityClassName = previous.proxyActivityClassName
            )
        }.getOrElse { error ->
            activityService.compareAndSetProxyActivitySlot(
                runtime.instanceId,
                key,
                proxyActivityClassName,
                previous.proxyActivityClassName
            )
            return rejected(
                request,
                "activity_allocation_capability_failed:${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
        return VirtualActivityLaunchAllocation(
            accepted = true,
            request = request,
            proxyActivityClassName = proxyActivityClassName,
            launchIdentity = identity.toLoaderIdentity(),
            reason = "activity_allocation_authorized"
        )
    }

    fun release(
        allocation: VirtualActivityLaunchAllocation,
        callingPid: Int
    ): Boolean {
        if (!allocation.accepted) return false
        val identity = allocation.launchIdentity?.toEngineIdentity() ?: return false
        val release = capabilities.releaseUnconsumedAllocation(identity, callingPid)
        val key = release.key ?: return false
        val allocated = release.allocatedProxyActivityClassName ?: return false
        if (!release.accepted) return false
        val result = activityService.compareAndSetProxyActivitySlot(
            instanceId = identity.instanceId,
            key = key,
            expected = allocated,
            new = release.previousProxyActivityClassName
        )
        if (result.verdict != EngineResultStatus.PASS || !result.matched) return false
        return capabilities.revoke(identity.capabilityToken)
    }

    private fun rejected(
        request: VirtualActivityLaunchAllocationRequest,
        reason: String
    ) = VirtualActivityLaunchAllocation(
        accepted = false,
        request = request,
        reason = reason
    )
}

class IpcVirtualActivityLaunchAllocationProvider : VirtualActivityLaunchAllocationProvider {
    override fun allocate(request: VirtualActivityLaunchAllocationRequest): VirtualActivityLaunchAllocation =
        EngineRuntimeIpcClients.allocateActivityLaunch(request)
            ?: VirtualActivityLaunchAllocation(
                accepted = false,
                request = request,
                reason = "engine_activity_allocation_authority_unavailable"
            )

    override fun release(allocation: VirtualActivityLaunchAllocation): Boolean =
        EngineRuntimeIpcClients.releaseActivityLaunch(allocation) == true
}

internal fun VirtualActivityLaunchAllocationRequest.slotKey(): ProxyActivitySlotKey =
    ProxyActivitySlotKey(
        instanceId = instanceId,
        launchMode = EngineProxyActivitySlots.normalizeLaunchMode(launchMode),
        taskKey = taskAffinity ?: "$originPackageName:$instanceId"
    )

internal fun EngineActivityLaunchIdentity.toLoaderIdentity() = VirtualActivityLaunchIdentity(
    capabilityToken = capabilityToken,
    instanceId = instanceId,
    runtimeEpoch = runtimeEpoch,
    engineSessionId = engineSessionId,
    processSlot = processSlot,
    proxyActivityClassName = proxyActivityClassName,
    guestActivityClassName = guestActivityClassName
)

internal fun VirtualActivityLaunchIdentity.toEngineIdentity() = EngineActivityLaunchIdentity(
    capabilityToken = capabilityToken,
    instanceId = instanceId,
    runtimeEpoch = runtimeEpoch,
    engineSessionId = engineSessionId,
    processSlot = processSlot,
    proxyActivityClassName = proxyActivityClassName,
    guestActivityClassName = guestActivityClassName
)
