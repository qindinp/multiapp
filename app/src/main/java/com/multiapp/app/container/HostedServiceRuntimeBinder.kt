package com.multiapp.app.container

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.multiapp.core.engine.DefaultEngineServiceRouter
import com.multiapp.core.engine.EngineComponentProcessLaunchTicket
import com.multiapp.core.engine.EngineComponentProcessOperationResult
import com.multiapp.core.engine.EngineHostedBootstrapResult
import com.multiapp.core.engine.EngineRuntimeAuthorityValidator
import com.multiapp.core.engine.EngineRuntimeIpcClients
import com.multiapp.core.engine.EngineRuntimeIpcSnapshot
import com.multiapp.core.engine.EngineServiceRouter
import com.multiapp.core.engine.EngineServiceStartRoute
import com.multiapp.core.engine.HostedRuntimeEngine

class HostedServiceRuntimeBinder(
    private val runtimeEngineFactory: (Context) -> HostedRuntimeEngine = ::hostedRuntimeEngineFrom,
    private val serviceRouter: EngineServiceRouter = DefaultEngineServiceRouter(),
    private val requestDecoder: (String, Intent) -> EngineServiceStartRoute? = { hostPackageName, intent ->
        serviceRouter.routeFromProxyIntent(hostPackageName, intent)
    },
    private val authorityQuery: (String) -> EngineRuntimeIpcSnapshot? = EngineRuntimeIpcClients::queryRuntime,
    private val componentAuthorityQuery: (String) -> EngineComponentProcessOperationResult? =
        EngineRuntimeIpcClients::queryCallingComponentProcess,
    private val componentClientAttacher: (
        EngineComponentProcessLaunchTicket,
        IBinder
    ) -> EngineComponentProcessOperationResult? = EngineRuntimeIpcClients::attachComponentProcessClient,
    private val componentBySlotAttacher: (
        String,
        String,
        IBinder
    ) -> EngineComponentProcessOperationResult? = EngineRuntimeIpcClients::attachComponentProcessBySlot,
    private val processToken: IBinder = PROCESS_TOKEN
) {
    fun ensureBound(hostContext: Context, proxyIntent: Intent?): HostedServiceRuntimeBindResult {
        val request = proxyIntent
            ?.let { requestDecoder(hostContext.packageName, it) }
            ?: return HostedServiceRuntimeBindResult.NotRequested("missingServiceProxyRequest")

        return ensureBound(hostContext, request)
    }

    fun ensureBound(hostContext: Context, route: EngineServiceStartRoute): HostedServiceRuntimeBindResult {
        val primaryAuthority = EngineRuntimeAuthorityValidator.validate(
            snapshot = authorityQuery(route.instanceId),
            expectedProcessSlot = route.processSlot
        )
        val launchTicket = route.componentProcessLaunchTicket
        val queriedComponentAuthority = if (primaryAuthority.allowed && launchTicket == null) {
            null
        } else {
            componentAuthorityQuery(route.instanceId)
        }
        val liveComponentAuthority = queriedComponentAuthority
            ?.takeIf { result -> result.isLiveCallerAuthority() }
        if (liveComponentAuthority != null && !liveComponentAuthority.matches(route, launchTicket)) {
            return HostedServiceRuntimeBindResult.Failed(
                instanceId = route.instanceId,
                processSlot = route.processSlot,
                errorClassName = SecurityException::class.java.name,
                errorMessage = "component_process_caller_identity_mismatch",
                detail = "componentProcessAuthorityMismatch"
            )
        }
        var componentAuthority = liveComponentAuthority
        var pendingAttachDetail = if (componentAuthority != null && launchTicket != null) {
            "componentProcessAlreadyAttached"
        } else {
            null
        }
        if (!primaryAuthority.allowed && componentAuthority == null && launchTicket == null) {
            // 无票据路径：guest 自启子进程（如微信 sandbox）由 AMS 直接拉起，没有
            // EXTRA_ENGINE_COMPONENT_PROCESS_LAUNCH_TICKET。按 slot 自证认领——
            // engine 校验 host UID + /proc/<pid>/cmdline == slot + slot 分配表归属。
            val bySlotAttach = componentBySlotAttacher(route.instanceId, route.processSlot.orEmpty(), processToken)
            val bySlotMatches = bySlotAttach?.accepted == true &&
                bySlotAttach.processState?.instanceId == route.instanceId &&
                bySlotAttach.processState?.processSlot == route.processSlot
            if (bySlotMatches) {
                componentAuthority = bySlotAttach
                pendingAttachDetail = "componentProcessAttachedBySlot"
            } else {
                return HostedServiceRuntimeBindResult.Failed(
                    instanceId = route.instanceId,
                    processSlot = route.processSlot,
                    errorClassName = SecurityException::class.java.name,
                    errorMessage = bySlotAttach?.reason ?: primaryAuthority.reason,
                    detail = "componentProcessAttachFailed"
                )
            }
        }
        if (componentAuthority == null && launchTicket != null) {
            val pendingAttach = componentClientAttacher(launchTicket, processToken)
            if (pendingAttach.matchesAttachedProcess(route, launchTicket)) {
                pendingAttachDetail = if (pendingAttach?.idempotent == true) {
                    "componentProcessAlreadyAttached"
                } else {
                    "componentProcessAttached"
                }
            } else {
                componentAuthority = componentAuthorityQuery(route.instanceId)
                    ?.takeIf { result -> result.matches(route, launchTicket) }
                if (componentAuthority == null) {
                    return HostedServiceRuntimeBindResult.Failed(
                        instanceId = route.instanceId,
                        processSlot = route.processSlot,
                        errorClassName = SecurityException::class.java.name,
                        errorMessage = pendingAttach?.reason
                            ?: "component_process_attach_ipc_unavailable",
                        detail = "componentProcessAttachFailed"
                    )
                }
                pendingAttachDetail = "componentProcessAlreadyAttached"
            }
        }
        val effectiveGuestProcessName = componentAuthority
            ?.processState
            ?.effectiveGuestProcessName
            ?: launchTicket?.effectiveGuestProcessName
        val providerHookEnabled = componentAuthority == null && launchTicket == null
        val runtimeEngine = runtimeEngineFactory(hostContext)
        runtimeEngine.reusableResult(
            instanceId = route.instanceId,
            providerHookEnabled = providerHookEnabled,
            processSlot = route.processSlot,
            effectiveGuestProcessName = effectiveGuestProcessName
        )?.let { result ->
            if (!route.processSlot.isNullOrBlank() && result.processSlot != route.processSlot) {
                return HostedServiceRuntimeBindResult.Failed(
                    instanceId = route.instanceId,
                    processSlot = route.processSlot,
                    errorClassName = IllegalStateException::class.java.name,
                    errorMessage = "cached runtime processSlot mismatch: expected=${route.processSlot} actual=${result.processSlot}",
                    detail = "runtimeProcessSlotMismatch"
                )
            }
            val bound = HostedServiceRuntimeBindResult.Bound(
                instanceId = route.instanceId,
                processSlot = route.processSlot,
                result = result,
                status = "CACHED",
                detail = pendingAttachDetail ?: "runtimeAlreadyReusable"
            )
            return bound
        }

        return runCatching {
            val result = runtimeEngine.bindApplication(
                instanceId = route.instanceId,
                providerHookEnabled = providerHookEnabled,
                processSlot = route.processSlot,
                effectiveGuestProcessName = effectiveGuestProcessName
            ).result
            val bound = HostedServiceRuntimeBindResult.Bound(
                instanceId = route.instanceId,
                processSlot = route.processSlot,
                result = result,
                status = "BOUND",
                detail = pendingAttachDetail ?: "runtimeBoundForServiceProxy"
            )
            bound
        }.getOrElse { error ->
            HostedServiceRuntimeBindResult.Failed(
                instanceId = route.instanceId,
                processSlot = route.processSlot,
                errorClassName = error.javaClass.name,
                errorMessage = error.message,
                detail = "runtimeBindFailed"
            )
        }
    }

    private fun EngineComponentProcessOperationResult.isLiveCallerAuthority(): Boolean =
        accepted && alreadyRunning && processState?.live == true

    private fun EngineComponentProcessOperationResult?.matches(
        route: EngineServiceStartRoute,
        ticket: EngineComponentProcessLaunchTicket?
    ): Boolean {
        val state = this?.processState
        return this != null && state != null && isLiveCallerAuthority() &&
            instanceId == route.instanceId &&
            state.instanceId == route.instanceId &&
            state.processSlot == route.processSlot &&
            (ticket == null ||
                ticket.instanceId == route.instanceId &&
                ticket.processSlot == route.processSlot &&
                state.effectiveGuestProcessName == ticket.effectiveGuestProcessName)
    }

    private fun EngineComponentProcessOperationResult?.matchesAttachedProcess(
        route: EngineServiceStartRoute,
        ticket: EngineComponentProcessLaunchTicket
    ): Boolean {
        val state = this?.processState
        return this != null && accepted && !alreadyRunning && state?.live == true &&
            instanceId == route.instanceId &&
            state.instanceId == route.instanceId &&
            ticket.instanceId == route.instanceId &&
            state.effectiveGuestProcessName == ticket.effectiveGuestProcessName &&
            state.processSlot == route.processSlot &&
            ticket.processSlot == route.processSlot
    }

    private companion object {
        val PROCESS_TOKEN: IBinder = Binder()
    }
}

sealed class HostedServiceRuntimeBindResult {
    abstract val status: String
    abstract val detail: String

    data class Bound(
        val instanceId: String,
        val processSlot: String?,
        val result: EngineHostedBootstrapResult,
        override val status: String,
        override val detail: String
    ) : HostedServiceRuntimeBindResult()

    data class Failed(
        val instanceId: String,
        val processSlot: String?,
        val errorClassName: String,
        val errorMessage: String?,
        override val detail: String
    ) : HostedServiceRuntimeBindResult() {
        override val status: String = "FAILED"
    }

    data class NotRequested(
        override val detail: String
    ) : HostedServiceRuntimeBindResult() {
        override val status: String = "NOT_REQUESTED"
    }
}


