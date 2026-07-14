package com.multiapp.core.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.multiapp.core.loader.VirtualProviderDispatcher
import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualServiceDispatcher
import com.multiapp.core.loader.VirtualServiceIntentStore
import com.multiapp.core.loader.VirtualServiceManager
import com.multiapp.core.loader.VirtualServiceStartRequest
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.ProviderRouteContract

data class EngineProviderDispatchRequest(
    val hostPackageName: String,
    val hostContext: Context,
    val proxyUri: Uri,
    val operationName: String,
    val verifiedRoute: EngineProviderRouteToken? = null,
    val providerCallingUid: Int = -1,
    val providerCallingPid: Int = -1,
    val hostUid: Int = -1,
    val callerProcessSlot: String? = null,
    val accessMode: String? = null,
    val uriGrantPresent: Boolean = false
) {
    init {
        require(hostPackageName.isNotBlank()) { "hostPackageName must not be blank" }
        require(operationName.isNotBlank()) { "operationName must not be blank" }
        require(providerCallingUid >= -1) { "providerCallingUid must be -1 or non-negative" }
        require(providerCallingPid >= -1) { "providerCallingPid must be -1 or non-negative" }
        require(hostUid >= -1) { "hostUid must be -1 or non-negative" }
        require(callerProcessSlot == null || callerProcessSlot.isNotBlank()) {
            "callerProcessSlot must not be blank"
        }
        require(accessMode == null || accessMode.isNotBlank()) { "accessMode must not be blank" }
    }
}

interface EngineProviderDispatcher {
    fun dispatch(request: EngineProviderDispatchRequest): EngineProviderDispatchResult
}

class DefaultEngineProviderDispatcher private constructor(
    private val processRuntime: VirtualProcessRuntime,
    private val activityRecordManager: VirtualActivityRecordManager,
    private val providerService: VirtualProviderService,
    private val loaderDispatch: ((EngineProviderDispatchRequest) -> EngineProviderDispatchResult)?
) : EngineProviderDispatcher {
    constructor() : this(
        processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
        activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
        providerService = IpcBackedVirtualProviderService(
            DefaultVirtualSystemServer(EngineRuntimeRegistry.global).providerService
        ),
        loaderDispatch = null
    )

    constructor(
        providerService: VirtualProviderService,
        loaderDispatch: ((EngineProviderDispatchRequest) -> EngineProviderDispatchResult)? = null
    ) : this(
        processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
        activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
        providerService = providerService,
        loaderDispatch = loaderDispatch
    )

    override fun dispatch(request: EngineProviderDispatchRequest): EngineProviderDispatchResult {
        val route = request.toProviderPlanRoute()
            ?: return EngineProviderDispatchResult.InvalidProxyUri("missing provider route")
        val plan = providerService.planProvider(route.instanceId, route.request)
        val blockedResult = plan.toProviderBlockResult(route.request)
        if (blockedResult != null) {
            providerService.recordDispatchIfPossible(blockedResult, request.operationName)
            return blockedResult
        }
        val result = dispatchThroughLoader(request)
        providerService.recordDispatchIfPossible(result, request.operationName)
        return result
    }

    private fun dispatchThroughLoader(request: EngineProviderDispatchRequest): EngineProviderDispatchResult {
        loaderDispatch?.invoke(request)?.let { return it }
        val result = VirtualProviderDispatcher(
            hostPackageName = request.hostPackageName,
            hostContext = request.hostContext,
            processRuntime = processRuntime,
            activityRecordManager = activityRecordManager
        ).dispatch(request.proxyUri)
        return EngineProviderDispatchResult.fromLoader(result)
    }

    private fun EngineProviderDispatchRequest.toProviderPlanRoute(): EngineProviderPlanRoute? {
        val uriInstanceId = proxyUri.getQueryParameter(ProviderRouteContract.PROXY_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val targetInstanceId = verifiedRoute?.targetInstanceId ?: uriInstanceId
        val guestAuthority = proxyUri.getQueryParameter(ProviderRouteContract.PROXY_GUEST_AUTHORITY)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val processSlot = proxyUri.getQueryParameter(ProviderRouteContract.PROXY_PROCESS_SLOT)
            ?.takeIf { it.isNotBlank() }
        val routeToken = proxyUri.getQueryParameter(ProviderRouteContract.PROXY_ROUTE_TOKEN)
            ?.takeIf { it.isNotBlank() }
        return EngineProviderPlanRoute(
            instanceId = targetInstanceId,
            request = VirtualProviderDispatchPlanRequest(
                operation = EngineProviderOperation.fromOperationName(operationName),
                guestAuthority = guestAuthority,
                proxyAuthority = proxyUri.authority?.takeIf { it.isNotBlank() },
                processSlot = processSlot,
                routeTokenPresent = routeToken != null,
                routeTokenVerified = verifiedRoute != null,
                callerInstanceId = verifiedRoute?.callerInstanceId,
                targetInstanceId = targetInstanceId,
                callingUid = providerCallingUid,
                callingPid = providerCallingPid,
                hostUid = hostUid,
                callerProcessSlot = callerProcessSlot,
                accessMode = accessMode ?: operationName.substringAfter(':', "").takeIf { it.isNotBlank() },
                encodedPath = normalizeProviderGrantPath(proxyUri.encodedPath),
                uriGrantPresent = uriGrantPresent
            )
        )
    }

    private fun VirtualProviderDispatchPlan.toProviderBlockResult(
        request: VirtualProviderDispatchPlanRequest
    ): EngineProviderDispatchResult? =
        when (verdict) {
            EngineResultStatus.PASS,
            EngineResultStatus.PARTIAL -> null
            EngineResultStatus.UNSUPPORTED -> EngineProviderDispatchResult.InvalidProxyUri(
                "engine_provider_plan_unsupported:$message"
            )
            EngineResultStatus.FAIL -> when {
                message.startsWith("runtime_not_found:") -> EngineProviderDispatchResult.InstanceNotFound(instanceId)
                message.startsWith("provider_not_found:") -> EngineProviderDispatchResult.ProviderNotFound(
                    instanceId = instanceId,
                    guestAuthority = guestAuthority,
                    evidence = EngineProviderEvidence(
                        instanceId = instanceId,
                        guestAuthority = guestAuthority,
                        proxyAuthority = request.proxyAuthority,
                        providerClassName = null,
                        operation = request.operation,
                        success = false,
                        reason = message
                    )
                )
                else -> EngineProviderDispatchResult.InvalidProxyUri("engine_provider_plan_failed:$message")
            }
        }

    private fun VirtualProviderService.recordDispatchIfPossible(
        result: EngineProviderDispatchResult,
        operationName: String
    ) {
        val dispatchResult = result.toVirtualProviderOperationResult(operationName) ?: return
        recordProviderDispatch(dispatchResult.instanceId, dispatchResult)
    }
}

private data class EngineProviderPlanRoute(
    val instanceId: String,
    val request: VirtualProviderDispatchPlanRequest
)

object EngineProviderRouteSlots {
    fun stubAuthority(hostPackageName: String, processSlot: String?): String {
        val base = "$hostPackageName.multiapp.provider.stub"
        val index = ProxyActivitySlots.processSlotIndex(hostPackageName, processSlot) ?: return base
        return "$base.v$index"
    }
}

data class EngineServiceDispatchRequest(
    val hostContext: Context,
    val proxyIntent: Intent?,
    val route: EngineServiceStartRoute?,
    val flags: Int,
    val startId: Int
)

class EngineServiceStartRoute internal constructor(
    val instanceId: String,
    val originPackageName: String,
    val guestServiceClassName: String,
    val reason: String,
    val foreground: Boolean,
    val proxyToken: String?,
    val processSlot: String?,
    internal val startRequest: VirtualServiceStartRequest
) {
    companion object {
        fun create(
            instanceId: String,
            originPackageName: String,
            guestServiceClassName: String,
            sourceIntent: Intent = Intent(),
            reason: String = "explicit",
            foreground: Boolean = false,
            proxyToken: String? = null,
            processSlot: String? = null
        ): EngineServiceStartRoute {
            require(instanceId.isNotBlank()) { "instanceId must not be blank" }
            require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
            require(guestServiceClassName.isNotBlank()) { "guestServiceClassName must not be blank" }
            return fromStartRequest(
                VirtualServiceStartRequest(
                    instanceId = instanceId,
                    originPackageName = originPackageName,
                    guestServiceClassName = guestServiceClassName,
                    sourceIntent = sourceIntent,
                    reason = reason,
                    foreground = foreground,
                    proxyToken = proxyToken,
                    processSlot = processSlot
                )
            )
        }

        internal fun fromStartRequest(request: VirtualServiceStartRequest): EngineServiceStartRoute =
            EngineServiceStartRoute(
                instanceId = request.instanceId,
                originPackageName = request.originPackageName,
                guestServiceClassName = request.guestServiceClassName,
                reason = request.reason,
                foreground = request.foreground,
                proxyToken = request.proxyToken,
                processSlot = request.processSlot,
                startRequest = request
            )
    }
}

data class EngineServiceLaunchInfo(
    val instanceId: String = "",
    val originPackageName: String = "",
    val guestServiceClassName: String = "",
    val reason: String = "",
    val foreground: Boolean = false
)

interface EngineServiceRouter {
    fun routeFromProxyIntent(hostPackageName: String, proxyIntent: Intent?): EngineServiceStartRoute?
    fun launchInfo(proxyIntent: Intent?, route: EngineServiceStartRoute?): EngineServiceLaunchInfo
    fun hasReusableRuntime(instanceId: String): Boolean
    fun clearProxyToken(route: EngineServiceStartRoute?)
}

class DefaultEngineServiceRouter(
    private val processRuntime: EngineHostedProcessRuntime = DefaultEngineHostedProcessRuntime(),
    private val serviceManagerFactory: (String) -> VirtualServiceManager = { hostPackageName ->
        VirtualServiceManager(hostPackageName)
    }
) : EngineServiceRouter {
    override fun routeFromProxyIntent(hostPackageName: String, proxyIntent: Intent?): EngineServiceStartRoute? {
        return proxyIntent
            ?.let { serviceManagerFactory(hostPackageName).requestFromProxyIntent(it) }
            ?.let(EngineServiceStartRoute.Companion::fromStartRequest)
    }

    override fun launchInfo(proxyIntent: Intent?, route: EngineServiceStartRoute?): EngineServiceLaunchInfo {
        if (route != null) {
            return EngineServiceLaunchInfo(
                instanceId = route.instanceId,
                originPackageName = route.originPackageName,
                guestServiceClassName = route.guestServiceClassName,
                reason = route.reason,
                foreground = route.foreground
            )
        }
        return EngineServiceLaunchInfo(
            instanceId = proxyIntent?.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID).orEmpty(),
            originPackageName = proxyIntent?.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME).orEmpty(),
            guestServiceClassName = proxyIntent
                ?.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME)
                .orEmpty(),
            reason = proxyIntent?.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON).orEmpty(),
            foreground = proxyIntent?.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) ?: false
        )
    }

    override fun hasReusableRuntime(instanceId: String): Boolean =
        instanceId.isNotBlank() && processRuntime.reusableResult(instanceId) != null

    override fun clearProxyToken(route: EngineServiceStartRoute?) {
        VirtualServiceIntentStore.clear(route?.proxyToken)
    }
}

interface EngineServiceDispatcher {
    fun dispatch(request: EngineServiceDispatchRequest): EngineServiceDispatchResult
}

class DefaultEngineServiceDispatcher private constructor(
    private val processRuntime: VirtualProcessRuntime,
    private val activityRecordManager: VirtualActivityRecordManager,
    private val serviceService: VirtualServiceService,
    private val loaderDispatch: ((EngineServiceDispatchRequest) -> EngineServiceDispatchResult)?
) : EngineServiceDispatcher {
    constructor() : this(
        processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
        activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
        serviceService = IpcBackedVirtualServiceService(
            DefaultVirtualSystemServer(EngineRuntimeRegistry.global).serviceService
        ),
        loaderDispatch = null
    )

    constructor(
        serviceService: VirtualServiceService,
        loaderDispatch: ((EngineServiceDispatchRequest) -> EngineServiceDispatchResult)? = null
    ) : this(
        processRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
        activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
        serviceService = serviceService,
        loaderDispatch = loaderDispatch
    )

    override fun dispatch(request: EngineServiceDispatchRequest): EngineServiceDispatchResult {
        var operationLease: EngineServiceOperationLeaseIdentity? = null
        val planResult = request.route?.let { route ->
            val plan = serviceService.planService(
                instanceId = route.instanceId,
                request = route.toServicePlanRequest()
            )
            operationLease = plan.targets.singleOrNull()?.operationLease
            when (plan.verdict) {
                EngineResultStatus.PASS,
                EngineResultStatus.PARTIAL -> null
                EngineResultStatus.UNSUPPORTED -> EngineServiceDispatchResult.Unsupported(
                    startRequest = EngineServiceStartRequestSnapshot.fromLoader(route.startRequest),
                    reason = "engine_service_plan_unsupported:${plan.message}"
                )
                EngineResultStatus.FAIL -> if (plan.message.startsWith("runtime_not_found:")) {
                    EngineServiceDispatchResult.RuntimeNotBound(
                        startRequest = EngineServiceStartRequestSnapshot.fromLoader(route.startRequest)
                    )
                } else {
                    EngineServiceDispatchResult.Unsupported(
                        startRequest = EngineServiceStartRequestSnapshot.fromLoader(route.startRequest),
                        reason = "engine_service_plan_failed:${plan.message}"
                    )
                }
            }
        }
        if (planResult != null) {
            return planResult
        }
        val injectedResult = loaderDispatch?.invoke(request)
        if (injectedResult != null) {
            serviceService.recordDispatchIfPossible(injectedResult, operationLease)
            return injectedResult
        }
        val dispatcher = VirtualServiceDispatcher(
            hostContext = request.hostContext,
            processRuntime = processRuntime,
            activityRecordManager = activityRecordManager
        )
        val result = request.route?.startRequest?.let { startRequest ->
            dispatcher.dispatch(startRequest, request.flags, request.startId)
        } ?: dispatcher.dispatch(request.proxyIntent, request.flags, request.startId)
        return EngineServiceDispatchResult.fromLoader(result)
            .also { engineResult ->
                serviceService.recordDispatchIfPossible(engineResult, operationLease)
            }
    }

    private fun EngineServiceStartRoute.toServicePlanRequest(): VirtualServiceDispatchPlanRequest {
        val sourceIntent = startRequest.sourceIntent
        val data = runCatching { sourceIntent.data }.getOrNull()
        return VirtualServiceDispatchPlanRequest(
            operation = if (foreground) {
                VirtualServiceOperation.START_FOREGROUND
            } else {
                VirtualServiceOperation.START
            },
            action = runCatching { sourceIntent.action }.getOrNull()?.takeIf { it.isNotBlank() },
            serviceClassName = guestServiceClassName,
            targetPackageName = originPackageName,
            categories = runCatching { sourceIntent.categories.orEmpty() }.getOrDefault(emptySet()),
            dataScheme = data?.scheme?.takeIf { it.isNotBlank() },
            dataMimeType = runCatching { sourceIntent.type }.getOrNull()?.takeIf { it.isNotBlank() },
            dataAuthority = data.toEngineIntentAuthority(),
            dataPath = data?.path?.takeIf { it.isNotBlank() },
            operationLeaseRequested = true
        )
    }

    private fun VirtualServiceService.recordDispatchIfPossible(
        result: EngineServiceDispatchResult,
        operationLease: EngineServiceOperationLeaseIdentity?
    ) {
        val lease = operationLease ?: return
        val dispatchResult = result.toVirtualServiceOperationResult()?.copy(
            processSlot = lease.processSlot,
            operationLease = lease
        ) ?: return
        recordServiceDispatch(dispatchResult.instanceId, dispatchResult)
    }
}
