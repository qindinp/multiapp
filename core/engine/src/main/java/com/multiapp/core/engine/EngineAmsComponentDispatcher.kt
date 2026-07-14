package com.multiapp.core.engine

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import com.multiapp.core.loader.VirtualAmsComponentDispatcher
import com.multiapp.core.loader.VirtualBroadcastDispatchOptions
import com.multiapp.core.loader.VirtualBroadcastRecord
import com.multiapp.core.loader.VirtualBroadcastResult
import com.multiapp.core.loader.VirtualBroadcastResultCode
import com.multiapp.core.loader.VirtualContextWrapper
import com.multiapp.core.loader.VirtualServiceBindDispatchResult
import com.multiapp.core.loader.VirtualServiceStartRequest
import com.multiapp.core.loader.VirtualServiceStopDispatchResult
import com.multiapp.core.loader.VirtualServiceUnbindDispatchResult
import com.multiapp.core.model.engine.EngineResultStatus
import java.util.concurrent.Executor
import java.util.IdentityHashMap

class DefaultEngineAmsComponentDispatcher(
    private val fallback: VirtualAmsComponentDispatcher,
    private val instanceId: String,
    private val activityLaunchCoordinator: EngineActivityLaunchCoordinator? = null,
    private val activityService: VirtualActivityService = DefaultVirtualSystemServer(
        EngineRuntimeRegistry.global
    ).activityService,
    private val serviceService: VirtualServiceService = DefaultVirtualSystemServer(
        EngineRuntimeRegistry.global
    ).serviceService,
    private val serviceConnectionAuthority: EngineServiceConnectionAuthority? = null,
    private val broadcastService: VirtualBroadcastService = DefaultVirtualSystemServer(
        EngineRuntimeRegistry.global
    ).broadcastService
) : VirtualAmsComponentDispatcher {
    private val serviceConnectionTokens = IdentityHashMap<ServiceConnection, IBinder>()
    private val serviceConnectionBridges = IdentityHashMap<ServiceConnection, CommitGatedServiceConnection>()

    override fun resolveStartActivityIntent(intent: Intent): VirtualContextWrapper.StartActivityMappingResult {
        val planRequest = intent.toActivityPlanRequest()
        val plan = activityService.planActivity(instanceId, planRequest)
        val blocked = plan.toBlockedStartActivityResult(intent)
        if (blocked != null) {
            activityService.recordActivityDispatchIfPossible(plan.toActivityDispatchResult(planRequest, blocked))
            return blocked
        }
        val result = activityLaunchCoordinator?.remap(intent, plan)
            ?: fallback.resolveStartActivityIntent(intent)
        activityService.recordActivityDispatchIfPossible(
            result.toActivityDispatchResult(
                fallbackInstanceId = instanceId,
                plan = plan,
                request = planRequest,
                remapSource = if (activityLaunchCoordinator == null) "loader" else "engine"
            )
        )
        return result
    }

    override fun resolveStartActivityIntents(
        intents: List<Intent>
    ): List<VirtualContextWrapper.StartActivityMappingResult> {
        val planned = intents.map { intent ->
            val request = intent.toActivityPlanRequest()
            ActivityPlanEntry(
                intent = intent,
                request = request,
                plan = activityService.planActivity(instanceId, request)
            )
        }
        val blocked = planned.firstNotNullOfOrNull { entry ->
            entry.plan.toBlockedStartActivityResult(entry.intent)?.also { result ->
                activityService.recordActivityDispatchIfPossible(
                    entry.plan.toActivityDispatchResult(entry.request, result)
                )
            }
        }
        if (blocked != null && activityLaunchCoordinator == null) return listOf(blocked)

        val results = if (activityLaunchCoordinator == null) {
            fallback.resolveStartActivityIntents(intents)
        } else {
            activityLaunchCoordinator.remapBatch(
                planned.map { entry -> entry.intent to entry.plan }
            )
        }
        results.forEachIndexed { index, result ->
            val entry = planned.getOrNull(index) ?: return@forEachIndexed
            activityService.recordActivityDispatchIfPossible(
                result.toActivityDispatchResult(
                    fallbackInstanceId = instanceId,
                    plan = entry.plan,
                    request = entry.request,
                    remapSource = if (activityLaunchCoordinator == null) "loader" else "engine"
                )
            )
        }
        return results
    }

    override fun resolveStartServiceIntent(
        intent: Intent,
        foreground: Boolean
    ): VirtualContextWrapper.StartServiceMappingResult {
        val operation = if (foreground) {
            VirtualServiceOperation.START_FOREGROUND
        } else {
            VirtualServiceOperation.START
        }
        val planRequest = intent.toServicePlanRequest(operation)
        val plan = serviceService.planService(instanceId, planRequest)
        val blocked = plan.toBlockedStartServiceResult(intent, foreground)
        if (blocked != null) {
            return blocked
        }
        return fallback.resolveStartServiceIntent(intent, foreground)
    }

    override fun dispatchStopService(intent: Intent): VirtualServiceStopDispatchResult? {
        val planRequest = intent.toServicePlanRequest(
            VirtualServiceOperation.STOP,
            operationLeaseRequested = true
        )
        val plan = serviceService.planService(instanceId, planRequest)
        if (plan.shouldBlockLoaderDispatch()) {
            return null
        }
        val result = fallback.dispatchStopService(intent)
        serviceService.recordServiceDispatchIfPossible(
            result?.toServiceOperationResult(instanceId, planRequest)
                ?: plan.toUnsupportedFallbackStopResult(planRequest),
            plan
        )
        return result
    }

    override fun dispatchBindService(
        intent: Intent,
        virtualContext: Context,
        guestClassLoader: ClassLoader,
        connection: ServiceConnection,
        flags: Int,
        executor: Executor?
    ): VirtualServiceBindDispatchResult {
        val planRequest = intent.toServicePlanRequest(
            VirtualServiceOperation.BIND,
            operationLeaseRequested = true
        )
        val plan = serviceService.planService(instanceId, planRequest)
        val blocked = plan.toBlockedBindServiceResult(intent, flags)
        if (blocked != null) {
            return blocked
        }
        val connectionAuthority = serviceConnectionAuthority
        val registration = connectionAuthority?.let { authority ->
            val lease = plan.targets.singleOrNull()?.operationLease
                ?: return serviceConnectionBlocked(intent, flags, "service_connection_lease_missing")
            val tokenWasExisting = serviceConnectionTokenOrNull(connection) != null
            val connectionToken = serviceConnectionToken(connection)
            val result = authority.register(instanceId, lease, connectionToken)
            if (result?.accepted != true || result.bindings.size != 1) {
                if (!tokenWasExisting) {
                    discardServiceConnectionToken(connection, connectionToken)
                }
                return serviceConnectionBlocked(
                    intent,
                    flags,
                    "service_connection_registration_failed:${result?.reason ?: "authority_unavailable"}"
                )
            }
            RegisteredServiceConnection(
                connectionToken,
                result.bindings.single(),
                result.idempotent,
                tokenWasExisting
            )
        }
        val dispatchConnection = registration?.let {
            serviceConnectionBridge(connection, initiallyOpen = it.tokenWasExisting)
        } ?: connection
        val result = fallback.dispatchBindService(
            intent,
            virtualContext,
            guestClassLoader,
            dispatchConnection,
            flags,
            executor
        )
        val recorded = serviceService.recordServiceDispatchIfPossible(
            result.toServiceOperationResult(instanceId, intent),
            plan
        )
        if (registration != null && result is VirtualServiceBindDispatchResult.Bound && recorded) {
            serviceConnectionBridgeOrNull(connection)?.commit()
        }
        if (registration != null && (result !is VirtualServiceBindDispatchResult.Bound || !recorded)) {
            if (!registration.idempotent) {
                connectionAuthority.removeBinding(
                    instanceId,
                    registration.binding,
                    registration.connectionToken
                )
                if (result is VirtualServiceBindDispatchResult.Bound) {
                    fallback.dispatchUnbindService(dispatchConnection)
                }
            }
            if (!registration.tokenWasExisting) {
                discardServiceConnectionToken(connection, registration.connectionToken)
                discardServiceConnectionBridge(connection)
            }
            if (result is VirtualServiceBindDispatchResult.Bound && !recorded) {
                return serviceConnectionBlocked(intent, flags, "service_connection_dispatch_commit_failed")
            }
        }
        return result
    }

    override fun dispatchUnbindService(connection: ServiceConnection): VirtualServiceUnbindDispatchResult {
        val connectionAuthority = serviceConnectionAuthority
        val connectionToken = connectionAuthority?.let { serviceConnectionTokenOrNull(connection) }
        if (connectionAuthority != null && connectionToken == null) {
            return VirtualServiceUnbindDispatchResult.NotFound
        }
        val authoritativeBinding = if (connectionAuthority == null) {
            null
        } else {
            val query = connectionAuthority.query(instanceId, requireNotNull(connectionToken))
            if (query?.accepted != true || query.bindings.size != 1) {
                return VirtualServiceUnbindDispatchResult.NotFound
            }
            query.bindings.single()
        }
        val planRequest = VirtualServiceDispatchPlanRequest(
            operation = VirtualServiceOperation.UNBIND,
            serviceClassName = authoritativeBinding?.component,
            operationLeaseRequested = true
        )
        val plan = serviceService.planService(instanceId, planRequest)
        if (plan.shouldBlockLoaderDispatch()) {
            return VirtualServiceUnbindDispatchResult.NotFound
        }
        val dispatchConnection = if (connectionAuthority == null) {
            connection
        } else {
            serviceConnectionBridgeOrNull(connection)
                ?: return VirtualServiceUnbindDispatchResult.NotFound
        }
        val result = fallback.dispatchUnbindService(dispatchConnection)
        val recorded = serviceService.recordServiceDispatchIfPossible(
            result.toServiceOperationResult(instanceId),
            plan
        )
        if (result is VirtualServiceUnbindDispatchResult.Unbound && !recorded) {
            return VirtualServiceUnbindDispatchResult.Failed(
                startRequest = result.startRequest,
                stage = "engineCommit",
                error = IllegalStateException("service_connection_dispatch_commit_failed")
            )
        }
        if (
            connectionAuthority != null &&
            connectionToken != null &&
            result is VirtualServiceUnbindDispatchResult.Unbound &&
            recorded
        ) {
            val removed = connectionAuthority.remove(instanceId, connectionToken)
            if (removed?.accepted != true) {
                return VirtualServiceUnbindDispatchResult.Failed(
                    startRequest = result.startRequest,
                    stage = "connectionRemoval",
                    error = IllegalStateException(
                        "service_connection_removal_failed:${removed?.reason ?: "authority_unavailable"}"
                    )
                )
            }
            discardServiceConnectionToken(connection, connectionToken)
            discardServiceConnectionBridge(connection)
        }
        return result
    }

    override fun dispatchBroadcast(
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader
    ): VirtualBroadcastResult = dispatchBroadcast(
        intent = intent,
        virtualContext = virtualContext,
        receiverClassLoader = receiverClassLoader,
        options = VirtualBroadcastDispatchOptions.DEFAULT
    )

    override fun dispatchBroadcast(
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader,
        options: VirtualBroadcastDispatchOptions
    ): VirtualBroadcastResult {
        val planRequest = intent.toBroadcastPlanRequest(options)
        val plan = broadcastService.planBroadcast(instanceId, planRequest)
        val blockedResult = plan.toBlockedBroadcastResult(intent)
        if (blockedResult != null) {
            broadcastService.recordBroadcastDispatchIfPossible(
                plan.toBroadcastOperationResult(planRequest, blockedResult)
            )
            return blockedResult
        }
        val result = fallback.dispatchBroadcast(intent, virtualContext, receiverClassLoader)
        result.toVirtualBroadcastOperationResults(instanceId)
            .forEach { broadcastService.recordBroadcastDispatchIfPossible(it) }
        return result
    }

    private fun Intent.toBroadcastPlanRequest(
        options: VirtualBroadcastDispatchOptions
    ): VirtualBroadcastDispatchPlanRequest {
        val component = runCatching { component }.getOrNull()
        val data = runCatching { data }.getOrNull()
        return VirtualBroadcastDispatchPlanRequest(
            action = runCatching { action }.getOrNull()?.takeIf { it.isNotBlank() },
            receiverClassName = component?.className?.takeIf { it.isNotBlank() },
            targetPackageName = component?.packageName?.takeIf { it.isNotBlank() }
                ?: runCatching { `package` }.getOrNull()?.takeIf { it.isNotBlank() },
            categories = runCatching { categories.orEmpty() }.getOrDefault(emptySet()),
            dataScheme = data?.scheme?.takeIf { it.isNotBlank() },
            dataMimeType = runCatching { type }.getOrNull()?.takeIf { it.isNotBlank() },
            dataAuthority = data.toEngineIntentAuthority(),
            dataPath = data?.path?.takeIf { it.isNotBlank() },
            ordered = options.ordered,
            sticky = options.sticky,
            expectsResultReceiver = options.expectsResultReceiver,
            abortSupportedRequested = options.abortSupportedRequested,
            receiverPermissions = options.receiverPermissions,
            receiverAppOp = options.receiverAppOp,
            asUserRequested = options.asUserRequested,
            platformOptionsPresent = options.platformOptionsPresent
        )
    }

    private fun Intent.toActivityPlanRequest(): VirtualActivityDispatchPlanRequest {
        val component = runCatching { component }.getOrNull()
        val data = runCatching { data }.getOrNull()
        return VirtualActivityDispatchPlanRequest(
            action = runCatching { action }.getOrNull()?.takeIf { it.isNotBlank() },
            activityClassName = runCatching { component?.className }.getOrNull()?.takeIf { it.isNotBlank() },
            targetPackageName = runCatching { component?.packageName }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { `package` }.getOrNull()?.takeIf { it.isNotBlank() },
            categories = runCatching { categories.orEmpty() }.getOrDefault(emptySet()),
            dataScheme = data?.scheme?.takeIf { it.isNotBlank() },
            dataMimeType = runCatching { type }.getOrNull()?.takeIf { it.isNotBlank() },
            dataAuthority = data.toEngineIntentAuthority(),
            dataPath = data?.path?.takeIf { it.isNotBlank() },
            launchFlags = runCatching { flags }.getOrDefault(0)
        )
    }

    private fun Intent.toServicePlanRequest(
        operation: VirtualServiceOperation,
        operationLeaseRequested: Boolean = false
    ): VirtualServiceDispatchPlanRequest {
        val component = runCatching { component }.getOrNull()
        val data = runCatching { data }.getOrNull()
        return VirtualServiceDispatchPlanRequest(
            operation = operation,
            action = runCatching { action }.getOrNull()?.takeIf { it.isNotBlank() },
            serviceClassName = component?.className?.takeIf { it.isNotBlank() },
            targetPackageName = component?.packageName?.takeIf { it.isNotBlank() }
                ?: runCatching { `package` }.getOrNull()?.takeIf { it.isNotBlank() },
            categories = runCatching { categories.orEmpty() }.getOrDefault(emptySet()),
            dataScheme = data?.scheme?.takeIf { it.isNotBlank() },
            dataMimeType = runCatching { type }.getOrNull()?.takeIf { it.isNotBlank() },
            dataAuthority = data.toEngineIntentAuthority(),
            dataPath = data?.path?.takeIf { it.isNotBlank() },
            operationLeaseRequested = operationLeaseRequested
        )
    }

    private fun VirtualServiceDispatchPlan.toBlockedStartServiceResult(
        intent: Intent,
        foreground: Boolean
    ): VirtualContextWrapper.StartServiceMappingResult.Blocked? =
        when (verdict) {
            EngineResultStatus.PASS,
            EngineResultStatus.PARTIAL -> null
            EngineResultStatus.FAIL,
            EngineResultStatus.UNSUPPORTED -> VirtualContextWrapper.StartServiceMappingResult.Blocked(
                sourceIntent = intent,
                foreground = foreground,
                reason = "engine_service_plan_${verdict.name.lowercase()}:$message"
            )
        }

    private fun VirtualServiceDispatchPlan.toBlockedBindServiceResult(
        intent: Intent,
        flags: Int
    ): VirtualServiceBindDispatchResult.Blocked? =
        when (verdict) {
            EngineResultStatus.PASS,
            EngineResultStatus.PARTIAL -> null
            EngineResultStatus.FAIL,
            EngineResultStatus.UNSUPPORTED -> VirtualServiceBindDispatchResult.Blocked(
                sourceIntent = intent,
                reason = "engine_service_plan_${verdict.name.lowercase()}:$message",
                serviceResolved = targets.isNotEmpty(),
                flags = flags,
                autoCreate = flags and Context.BIND_AUTO_CREATE != 0
            )
        }

    private fun VirtualServiceDispatchPlan.toServiceOperationResult(
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceOperationResult =
        VirtualServiceOperationResult(
            instanceId = instanceId,
            operation = operation,
            serviceClassName = targets.firstOrNull()?.serviceClassName ?: request.serviceClassName,
            action = action ?: request.action,
            verdict = verdict,
            reason = targets.firstOrNull()?.reason ?: message,
            foreground = operation == VirtualServiceOperation.START_FOREGROUND,
            message = "engine_service_plan_blocked:$message"
        )

    private fun VirtualActivityDispatchPlan.toBlockedStartActivityResult(
        intent: Intent
    ): VirtualContextWrapper.StartActivityMappingResult.Blocked? =
        when (verdict) {
            EngineResultStatus.PASS,
            EngineResultStatus.PARTIAL -> null
            EngineResultStatus.FAIL,
            EngineResultStatus.UNSUPPORTED -> VirtualContextWrapper.StartActivityMappingResult.Blocked(
                sourceIntent = intent,
                reason = "engine_activity_plan_${verdict.name.lowercase()}:$message"
            )
        }

    private fun VirtualActivityDispatchPlan.toActivityDispatchResult(
        request: VirtualActivityDispatchPlanRequest,
        blocked: VirtualContextWrapper.StartActivityMappingResult.Blocked
    ): VirtualActivityDispatchResult =
        VirtualActivityDispatchResult(
            instanceId = instanceId,
            activityClassName = targets.firstOrNull()?.activityClassName ?: request.activityClassName,
            action = action ?: request.action,
            verdict = verdict,
            reason = targets.firstOrNull()?.reason ?: message,
            remapped = false,
            launchFlags = request.launchFlags,
            message = "engine_activity_plan_blocked:${blocked.reason}"
        )

    private fun VirtualContextWrapper.StartActivityMappingResult.toActivityDispatchResult(
        fallbackInstanceId: String,
        plan: VirtualActivityDispatchPlan,
        request: VirtualActivityDispatchPlanRequest,
        remapSource: String
    ): VirtualActivityDispatchResult =
        when (this) {
            is VirtualContextWrapper.StartActivityMappingResult.Remapped -> VirtualActivityDispatchResult(
                instanceId = fallbackInstanceId,
                activityClassName = plan.targets.firstOrNull()?.activityClassName ?: request.activityClassName,
                action = plan.action ?: request.action,
                verdict = EngineResultStatus.PASS,
                reason = plan.targets.firstOrNull()?.reason ?: plan.message,
                remapped = true,
                proxyActivityClassName = proxyIntent.safeComponentClassName(),
                launchFlags = request.launchFlags,
                message = "${remapSource}_activity_remapped"
            )
            is VirtualContextWrapper.StartActivityMappingResult.Blocked -> VirtualActivityDispatchResult(
                instanceId = fallbackInstanceId,
                activityClassName = plan.targets.firstOrNull()?.activityClassName ?: request.activityClassName,
                action = plan.action ?: request.action,
                verdict = EngineResultStatus.FAIL,
                reason = reason,
                remapped = false,
                launchFlags = request.launchFlags,
                message = "loader_activity_blocked:$reason"
            )
        }

    private fun VirtualActivityService.recordActivityDispatchIfPossible(
        result: VirtualActivityDispatchResult
    ) {
        recordActivityDispatch(result.instanceId, result)
    }

    private fun VirtualServiceDispatchPlan.shouldBlockLoaderDispatch(): Boolean =
        when (verdict) {
            EngineResultStatus.PASS,
            EngineResultStatus.PARTIAL -> false
            EngineResultStatus.FAIL,
            EngineResultStatus.UNSUPPORTED -> true
        }

    private fun VirtualServiceDispatchPlan.toUnsupportedFallbackStopResult(
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceOperationResult =
        VirtualServiceOperationResult(
            instanceId = instanceId,
            operation = VirtualServiceOperation.STOP,
            serviceClassName = targets.firstOrNull()?.serviceClassName ?: request.serviceClassName,
            action = action ?: request.action,
            verdict = EngineResultStatus.UNSUPPORTED,
            reason = "fallback_stop_service_unsupported",
            message = "loader_stop_service_unsupported"
        )

    private fun VirtualServiceStopDispatchResult.toServiceOperationResult(
        fallbackInstanceId: String,
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceOperationResult {
        val stopRequest = this.stopRequest
        return when (this) {
            is VirtualServiceStopDispatchResult.ServiceStopped -> VirtualServiceOperationResult(
                instanceId = stopRequest.instanceId,
                operation = VirtualServiceOperation.STOP,
                serviceClassName = stopRequest.guestServiceClassName,
                action = stopRequest.sourceIntent.safeAction() ?: request.action,
                verdict = EngineResultStatus.PASS,
                reason = stopRequest.reason,
                stopped = true,
                destroyed = lifecycleEvidence.activeBindCount == 0,
                activeStartCount = lifecycleEvidence.activeStartCount,
                activeBindCount = lifecycleEvidence.activeBindCount,
                message = "loader_service_stopped"
            )
            is VirtualServiceStopDispatchResult.ServiceNotFound -> VirtualServiceOperationResult(
                instanceId = stopRequest.instanceId,
                operation = VirtualServiceOperation.STOP,
                serviceClassName = stopRequest.guestServiceClassName,
                action = stopRequest.sourceIntent.safeAction() ?: request.action,
                verdict = EngineResultStatus.PARTIAL,
                reason = "service_record_not_found",
                message = "loader_service_stop_not_found"
            )
            is VirtualServiceStopDispatchResult.ServiceOnDestroyFailed -> VirtualServiceOperationResult(
                instanceId = stopRequest.instanceId,
                operation = VirtualServiceOperation.STOP,
                serviceClassName = stopRequest.guestServiceClassName,
                action = stopRequest.sourceIntent.safeAction() ?: request.action,
                verdict = EngineResultStatus.FAIL,
                reason = stopRequest.reason,
                message = "loader_service_stop_on_destroy_failed:${error.javaClass.name}:${error.message.orEmpty()}"
            )
            is VirtualServiceStopDispatchResult.InstanceNotFound -> VirtualServiceOperationResult(
                instanceId = stopRequest.instanceId.takeIf { it.isNotBlank() } ?: fallbackInstanceId,
                operation = VirtualServiceOperation.STOP,
                serviceClassName = stopRequest.guestServiceClassName,
                action = stopRequest.sourceIntent.safeAction() ?: request.action,
                verdict = EngineResultStatus.FAIL,
                reason = "instance_not_found",
                message = "loader_service_stop_instance_not_found"
            )
        }
    }

    private fun VirtualServiceBindDispatchResult.toServiceOperationResult(
        fallbackInstanceId: String,
        sourceIntent: Intent
    ): VirtualServiceOperationResult =
        when (this) {
            is VirtualServiceBindDispatchResult.Bound -> startRequest.toBindOperationResult(
                action = sourceIntent.safeAction(),
                verdict = EngineResultStatus.PASS,
                bound = true,
                activeBindCount = activeConnectionCount,
                cached = cached,
                message = if (cached) "loader_service_bound_cached" else "loader_service_bound"
            )
            is VirtualServiceBindDispatchResult.Blocked -> VirtualServiceOperationResult(
                instanceId = fallbackInstanceId,
                operation = VirtualServiceOperation.BIND,
                serviceClassName = null,
                action = sourceIntent.safeAction(),
                verdict = if (serviceResolved) EngineResultStatus.FAIL else EngineResultStatus.UNSUPPORTED,
                reason = reason,
                message = "loader_service_bind_blocked:$reason"
            )
            is VirtualServiceBindDispatchResult.Failed -> startRequest.toBindOperationResult(
                action = sourceIntent.safeAction(),
                verdict = EngineResultStatus.FAIL,
                bound = false,
                message = "loader_service_bind_failed:$stage:${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }

    private fun VirtualServiceStartRequest.toBindOperationResult(
        action: String?,
        verdict: EngineResultStatus,
        bound: Boolean,
        activeBindCount: Int = 0,
        cached: Boolean = false,
        message: String
    ): VirtualServiceOperationResult =
        VirtualServiceOperationResult(
            instanceId = instanceId,
            operation = VirtualServiceOperation.BIND,
            serviceClassName = guestServiceClassName,
            action = action,
            verdict = verdict,
            reason = reason,
            bound = bound,
            foreground = foreground,
            processSlot = processSlot,
            activeBindCount = activeBindCount,
            cached = cached,
            message = message
        )

    private fun VirtualServiceUnbindDispatchResult.toServiceOperationResult(
        fallbackInstanceId: String
    ): VirtualServiceOperationResult =
        when (this) {
            is VirtualServiceUnbindDispatchResult.Unbound -> VirtualServiceOperationResult(
                instanceId = startRequest.instanceId,
                operation = VirtualServiceOperation.UNBIND,
                serviceClassName = startRequest.guestServiceClassName,
                action = startRequest.sourceIntent.safeAction(),
                verdict = EngineResultStatus.PASS,
                reason = startRequest.reason,
                unbound = true,
                destroyed = destroyed,
                processSlot = startRequest.processSlot,
                activeBindCount = activeBindCount,
                message = "loader_service_unbound"
            )
            is VirtualServiceUnbindDispatchResult.Failed -> VirtualServiceOperationResult(
                instanceId = startRequest.instanceId,
                operation = VirtualServiceOperation.UNBIND,
                serviceClassName = startRequest.guestServiceClassName,
                action = startRequest.sourceIntent.safeAction(),
                verdict = EngineResultStatus.FAIL,
                reason = startRequest.reason,
                message = "loader_service_unbind_failed:$stage:${error.javaClass.name}:${error.message.orEmpty()}"
            )
            VirtualServiceUnbindDispatchResult.NotFound -> VirtualServiceOperationResult(
                instanceId = fallbackInstanceId,
                operation = VirtualServiceOperation.UNBIND,
                serviceClassName = null,
                action = null,
                verdict = EngineResultStatus.UNSUPPORTED,
                reason = "connection_not_tracked",
                message = "loader_service_unbind_not_found"
            )
        }

    private fun Intent.safeAction(): String? =
        runCatching { action }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun Intent.safeComponentClassName(): String? =
        runCatching { component?.className }.getOrNull()?.takeIf { it.isNotBlank() }

    private data class ActivityPlanEntry(
        val intent: Intent,
        val request: VirtualActivityDispatchPlanRequest,
        val plan: VirtualActivityDispatchPlan
    )

    private fun VirtualBroadcastDispatchPlan.toBlockedBroadcastResult(
        intent: Intent
    ): VirtualBroadcastResult? =
        when (verdict) {
            EngineResultStatus.PASS,
            EngineResultStatus.PARTIAL -> null
            EngineResultStatus.UNSUPPORTED -> VirtualBroadcastResult.UnsupportedImplicit(
                sourceIntent = intent,
                record = record(intent, VirtualBroadcastResultCode.UnsupportedImplicit)
            )
            EngineResultStatus.FAIL -> if (message.startsWith("runtime_not_found:")) {
                VirtualBroadcastResult.NoPackageSnapshot(
                    sourceIntent = intent,
                    record = record(intent, VirtualBroadcastResultCode.NoPackageSnapshot)
                )
            } else {
                VirtualBroadcastResult.ReceiverNotFound(
                    sourceIntent = intent,
                    record = record(intent, VirtualBroadcastResultCode.ReceiverNotFound)
                )
            }
        }

    private fun VirtualBroadcastDispatchPlan.toBroadcastOperationResult(
        request: VirtualBroadcastDispatchPlanRequest,
        result: VirtualBroadcastResult
    ): VirtualBroadcastOperationResult =
        VirtualBroadcastOperationResult(
            instanceId = instanceId,
            receiverClassName = result.record.receiverClassName ?: request.receiverClassName,
            action = result.record.action ?: request.action,
            verdict = verdict,
            reason = message,
            delivered = false,
            message = "engine_broadcast_plan_blocked:${result.record.result.name}"
        )

    private fun VirtualBroadcastDispatchPlan.record(
        intent: Intent,
        result: VirtualBroadcastResultCode
    ): VirtualBroadcastRecord =
        VirtualBroadcastRecord(
            instanceId = instanceId,
            receiverClassName = targets.firstOrNull()?.receiverClassName
                ?: runCatching { intent.component?.className }.getOrNull(),
            action = action ?: runCatching { intent.action }.getOrNull(),
            result = result
        )

    private fun VirtualBroadcastService.recordBroadcastDispatchIfPossible(
        result: VirtualBroadcastOperationResult
    ) {
        recordBroadcastDispatch(result.instanceId, result)
    }

    private fun VirtualServiceService.recordServiceDispatchIfPossible(
        result: VirtualServiceOperationResult,
        plan: VirtualServiceDispatchPlan
    ): Boolean {
        val lease = plan.targets.singleOrNull()?.operationLease ?: return false
        return recordServiceDispatch(
            result.instanceId,
            result.copy(
                serviceClassName = result.serviceClassName ?: lease.component,
                processSlot = lease.processSlot,
                operationLease = lease
            )
        )
    }

    private fun serviceConnectionToken(connection: ServiceConnection): IBinder =
        synchronized(serviceConnectionTokens) {
            serviceConnectionTokens[connection] ?: Binder().also { token ->
                serviceConnectionTokens[connection] = token
            }
        }

    private fun serviceConnectionTokenOrNull(connection: ServiceConnection): IBinder? =
        synchronized(serviceConnectionTokens) { serviceConnectionTokens[connection] }

    private fun discardServiceConnectionToken(connection: ServiceConnection, token: IBinder) {
        synchronized(serviceConnectionTokens) {
            if (serviceConnectionTokens[connection] === token) {
                serviceConnectionTokens.remove(connection)
            }
        }
    }

    private fun serviceConnectionBridge(
        connection: ServiceConnection,
        initiallyOpen: Boolean
    ): CommitGatedServiceConnection = synchronized(serviceConnectionBridges) {
        serviceConnectionBridges[connection] ?: CommitGatedServiceConnection(
            delegate = connection,
            initiallyOpen = initiallyOpen
        ).also { bridge ->
            serviceConnectionBridges[connection] = bridge
        }
    }

    private fun serviceConnectionBridgeOrNull(
        connection: ServiceConnection
    ): CommitGatedServiceConnection? = synchronized(serviceConnectionBridges) {
        serviceConnectionBridges[connection]
    }

    private fun discardServiceConnectionBridge(connection: ServiceConnection) {
        val bridge = synchronized(serviceConnectionBridges) {
            serviceConnectionBridges.remove(connection)
        }
        bridge?.cancel()
    }

    private fun serviceConnectionBlocked(
        intent: Intent,
        flags: Int,
        reason: String
    ) = VirtualServiceBindDispatchResult.Blocked(
        sourceIntent = intent,
        reason = reason,
        serviceResolved = true,
        flags = flags,
        autoCreate = flags and Context.BIND_AUTO_CREATE != 0,
        serviceAlreadyRunning = false
    )

    private data class RegisteredServiceConnection(
        val connectionToken: IBinder,
        val binding: EngineServiceConnectionBindingRecord,
        val idempotent: Boolean,
        val tokenWasExisting: Boolean
    )

    private class CommitGatedServiceConnection(
        private val delegate: ServiceConnection,
        initiallyOpen: Boolean
    ) : ServiceConnection {
        private val pendingCallbacks = mutableListOf<(ServiceConnection) -> Unit>()
        private var state = if (initiallyOpen) State.OPEN else State.PENDING

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            dispatch { it.onServiceConnected(name, service) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            dispatch { it.onServiceDisconnected(name) }
        }

        override fun onBindingDied(name: ComponentName) {
            dispatch { it.onBindingDied(name) }
        }

        override fun onNullBinding(name: ComponentName) {
            dispatch { it.onNullBinding(name) }
        }

        fun commit() {
            val callbacks = synchronized(this) {
                if (state != State.PENDING) return
                state = State.OPEN
                pendingCallbacks.toList().also { pendingCallbacks.clear() }
            }
            callbacks.forEach { callback -> callback(delegate) }
        }

        fun cancel() {
            synchronized(this) {
                state = State.CANCELLED
                pendingCallbacks.clear()
            }
        }

        private fun dispatch(callback: (ServiceConnection) -> Unit) {
            val forward = synchronized(this) {
                when (state) {
                    State.PENDING -> {
                        pendingCallbacks += callback
                        null
                    }
                    State.OPEN -> callback
                    State.CANCELLED -> null
                }
            }
            forward?.invoke(delegate)
        }

        private enum class State {
            PENDING,
            OPEN,
            CANCELLED
        }
    }
}

internal fun Uri?.toEngineIntentAuthority(): String? {
    val uri = this ?: return null
    val host = runCatching { uri.host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val port = runCatching { uri.port }.getOrDefault(-1)
    if (port < 0) return host
    return if (':' in host) "[$host]:$port" else "$host:$port"
}

fun VirtualBroadcastResult.toVirtualBroadcastOperationResults(
    fallbackInstanceId: String
): List<VirtualBroadcastOperationResult> =
    when (this) {
        is VirtualBroadcastResult.Batch -> results.flatMap {
            it.toVirtualBroadcastOperationResults(fallbackInstanceId)
        }
        else -> listOf(toVirtualBroadcastOperationResult(fallbackInstanceId))
    }

private fun VirtualBroadcastResult.toVirtualBroadcastOperationResult(
    fallbackInstanceId: String
): VirtualBroadcastOperationResult {
    val request = when (this) {
        is VirtualBroadcastResult.Delivered -> request
        is VirtualBroadcastResult.ReceiverClassNotFound -> request
        is VirtualBroadcastResult.ReceiverCreateFailed -> request
        is VirtualBroadcastResult.OnReceiveFailed -> request
        else -> null
    }
    return VirtualBroadcastOperationResult(
        instanceId = record.instanceId ?: request?.instanceId ?: fallbackInstanceId,
        receiverClassName = record.receiverClassName ?: request?.receiverClassName,
        action = record.action ?: request?.action,
        verdict = if (this is VirtualBroadcastResult.Delivered) {
            EngineResultStatus.PASS
        } else {
            EngineResultStatus.FAIL
        },
        reason = request?.reason ?: record.result.name,
        delivered = this is VirtualBroadcastResult.Delivered,
        message = toEngineBroadcastMessage()
    )
}

private fun VirtualBroadcastResult.toEngineBroadcastMessage(): String =
    when (this) {
        is VirtualBroadcastResult.Delivered -> "loader_broadcast_delivered"
        is VirtualBroadcastResult.Batch -> "loader_broadcast_batch:${results.size}"
        is VirtualBroadcastResult.UnsupportedImplicit -> "loader_broadcast_unsupported_implicit"
        is VirtualBroadcastResult.NoPackageSnapshot -> "loader_broadcast_no_package_snapshot"
        is VirtualBroadcastResult.ReceiverNotFound -> "loader_broadcast_receiver_not_found"
        is VirtualBroadcastResult.ReceiverClassNotFound ->
            "loader_broadcast_receiver_class_not_found:${error.javaClass.name}"
        is VirtualBroadcastResult.ReceiverCreateFailed ->
            "loader_broadcast_receiver_create_failed:${error.javaClass.name}"
        is VirtualBroadcastResult.OnReceiveFailed ->
            "loader_broadcast_on_receive_failed:${error.javaClass.name}"
    }
