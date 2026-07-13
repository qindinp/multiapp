package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.engine.EngineActivityLaunchRequest
import com.multiapp.core.engine.EngineActivityLaunchIdentity
import com.multiapp.core.engine.EngineActivityLauncher
import com.multiapp.core.engine.EngineActivityProxyLauncher
import com.multiapp.core.engine.EngineActivityTaskControllers
import com.multiapp.core.engine.EngineLaunchSpec
import com.multiapp.core.engine.EngineProxyActivitySlots
import com.multiapp.core.engine.EngineProcessBootstrapState
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStore

class EngineReadyActivityLauncher(
    context: Context,
    private val proxyLauncher: EngineActivityProxyLauncher = EngineActivityProxyLauncher()
) : EngineActivityLauncher {
    private val hostContext = context.applicationContext ?: context

    override fun launch(spec: EngineLaunchSpec) {
        check(spec.bootstrapState == EngineProcessBootstrapState.READY) {
            "foreground launch requires a READY process bootstrap"
        }
        check(spec.bootstrapVerdict != EngineResultStatus.FAIL) {
            "foreground launch requires a non-failing process bootstrap"
        }
        check(spec.processId > 0) { "foreground launch requires a target process id" }
        val expectedProcessSlot = EngineProxyActivitySlots.processSlotForClassName(
            hostPackageName = hostContext.packageName,
            className = spec.proxySlot
        )
        check(expectedProcessSlot == spec.processSlot) {
            "proxy slot process mismatch: expected=$expectedProcessSlot,actual=${spec.processSlot}"
        }
        proxyLauncher.launchGuestLauncher(
            EngineActivityLaunchRequest(
                hostContext = hostContext,
                candidateProxyActivityClassNames = listOf(spec.proxySlot),
                proxyLaunchModeByClassName = EngineProxyActivitySlots.launchModeByClassName(hostContext.packageName),
                slotAssignmentStore = FileBackedProxyActivitySlotAssignmentStore(
                    ContainerRuntimePaths.proxyActivitySlotsFile(hostContext)
                ),
                instanceId = spec.instanceId,
                originPackageName = spec.originPackageName,
                guestActivityClassName = spec.guestActivityClassName,
                launchMode = spec.launchMode,
                taskAffinity = spec.taskAffinity,
                launchIdentity = EngineActivityLaunchIdentity(
                    capabilityToken = spec.launchCapabilityToken,
                    instanceId = spec.instanceId,
                    runtimeEpoch = spec.runtimeEpoch,
                    engineSessionId = spec.engineSessionId,
                    processSlot = spec.processSlot,
                    proxyActivityClassName = spec.proxySlot,
                    guestActivityClassName = spec.guestActivityClassName
                )
            )
        ).getOrElse { error -> throw error }
        EngineActivityTaskControllers.fileBacked(hostContext).persist(spec.instanceId)
    }
}
