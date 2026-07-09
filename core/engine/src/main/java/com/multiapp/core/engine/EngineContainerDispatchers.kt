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

data class EngineProviderDispatchRequest(
    val hostPackageName: String,
    val hostContext: Context,
    val proxyUri: Uri
) {
    init {
        require(hostPackageName.isNotBlank()) { "hostPackageName must not be blank" }
    }
}

interface EngineProviderDispatcher {
    fun dispatch(request: EngineProviderDispatchRequest): EngineProviderDispatchResult
}

class DefaultEngineProviderDispatcher(
    private val processRuntime: VirtualProcessRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
    private val activityRecordManager: VirtualActivityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
) : EngineProviderDispatcher {
    override fun dispatch(request: EngineProviderDispatchRequest): EngineProviderDispatchResult {
        val result = VirtualProviderDispatcher(
            hostPackageName = request.hostPackageName,
            hostContext = request.hostContext,
            processRuntime = processRuntime,
            activityRecordManager = activityRecordManager
        ).dispatch(request.proxyUri)
        return EngineProviderDispatchResult.fromLoader(result)
    }
}

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

class DefaultEngineServiceDispatcher(
    private val processRuntime: VirtualProcessRuntime = EngineHostedProcessRuntimeDefaults.loaderRuntime,
    private val activityRecordManager: VirtualActivityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
) : EngineServiceDispatcher {
    override fun dispatch(request: EngineServiceDispatchRequest): EngineServiceDispatchResult {
        val dispatcher = VirtualServiceDispatcher(
            hostContext = request.hostContext,
            processRuntime = processRuntime,
            activityRecordManager = activityRecordManager
        )
        val result = request.route?.startRequest?.let { startRequest ->
            dispatcher.dispatch(startRequest, request.flags, request.startId)
        } ?: dispatcher.dispatch(request.proxyIntent, request.flags, request.startId)
        return EngineServiceDispatchResult.fromLoader(result)
    }
}
