package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.UUID

data class VirtualServiceStartRequest(
    val instanceId: String,
    val originPackageName: String,
    val guestServiceClassName: String,
    val sourceIntent: Intent,
    val reason: String,
    val foreground: Boolean = false,
    val proxyToken: String? = null
)

data class VirtualServiceStopRequest(
    val instanceId: String,
    val originPackageName: String,
    val guestServiceClassName: String,
    val sourceIntent: Intent,
    val reason: String
)

data class VirtualServiceProxySpec(
    val hostPackageName: String,
    val stubServiceClassName: String,
    val token: String,
    val instanceId: String,
    val originPackageName: String,
    val guestServiceClassName: String,
    val reason: String,
    val foreground: Boolean = false
)

/**
 * Minimal Service proxy router for hosted containers.
 *
 * This mirrors the first control point used by VirtualApp/DroidPlugin-style
 * containers: guest Service starts are mapped to one host-declared StubService
 * instead of being allowed to fall through to Android's real PackageManager.
 * It does not yet instantiate or bind a guest Service lifecycle.
 */
class VirtualServiceManager(
    private val hostPackageName: String,
    private val stubServiceClassName: String = "$hostPackageName.container.StubService"
) {
    companion object {
        const val EXTRA_INSTANCE_ID = "multiapp.instanceId"
        const val EXTRA_VIRTUAL_SERVICE_TOKEN = "multiapp.virtualServiceToken"
        const val EXTRA_ORIGIN_PACKAGE_NAME = "multiapp.originPackageName"
        const val EXTRA_GUEST_SERVICE_CLASS_NAME = "multiapp.guestServiceClassName"
        const val EXTRA_SERVICE_START_REASON = "multiapp.serviceStartReason"
        const val EXTRA_ORIGINAL_GUEST_INTENT = "multiapp.originalGuestServiceIntent"
        const val EXTRA_FOREGROUND_SERVICE = "multiapp.foregroundService"
    }

    fun resolveStartService(snapshot: VirtualPackageSnapshot, intent: Intent): VirtualServiceStartRequest? {
        val component = intent.component
        return if (component != null) {
            resolveExplicitService(snapshot, component.packageName, component.className, intent, foreground = false)
        } else {
            resolveImplicitService(snapshot, intent, foreground = false)
        }
    }

    fun resolveStartForegroundService(snapshot: VirtualPackageSnapshot, intent: Intent): VirtualServiceStartRequest? {
        val component = intent.component
        return if (component != null) {
            resolveExplicitService(snapshot, component.packageName, component.className, intent, foreground = true)
        } else {
            resolveImplicitService(snapshot, intent, foreground = true)
        }
    }

    fun resolveStopService(snapshot: VirtualPackageSnapshot, intent: Intent): VirtualServiceStopRequest? {
        val component = intent.component ?: return null
        return resolveExplicitStopService(snapshot, component.packageName, component.className, intent)
    }

    internal fun resolveExplicitService(
        snapshot: VirtualPackageSnapshot,
        packageName: String,
        className: String,
        sourceIntent: Intent,
        foreground: Boolean = false
    ): VirtualServiceStartRequest? {
        if (!snapshot.matchesPackageName(packageName)) return null
        val normalizedClassName = normalizeServiceClassName(snapshot.originPackageName, className)
        val service: ResolvedComponent = snapshot.services.firstOrNull { it.name == normalizedClassName }
            ?: return null
        return VirtualServiceStartRequest(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            guestServiceClassName = service.name,
            sourceIntent = sourceIntent,
            reason = if (foreground) "explicitForeground" else "explicit",
            foreground = foreground
        )
    }

    internal fun resolveExplicitStopService(
        snapshot: VirtualPackageSnapshot,
        packageName: String,
        className: String,
        sourceIntent: Intent
    ): VirtualServiceStopRequest? {
        if (!snapshot.matchesPackageName(packageName)) return null
        val normalizedClassName = normalizeServiceClassName(snapshot.originPackageName, className)
        val service: ResolvedComponent = snapshot.services.firstOrNull { it.name == normalizedClassName }
            ?: return null
        return VirtualServiceStopRequest(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            guestServiceClassName = service.name,
            sourceIntent = sourceIntent,
            reason = "explicitStop"
        )
    }

    internal fun resolveImplicitService(
        snapshot: VirtualPackageSnapshot,
        sourceIntent: Intent,
        foreground: Boolean = false
    ): VirtualServiceStartRequest? {
        val serviceInfo = VirtualPackageService(snapshot).resolveService(sourceIntent)?.serviceInfo
            ?: return null
        val serviceName = serviceInfo.name?.takeIf { it.isNotBlank() } ?: return null
        return VirtualServiceStartRequest(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            guestServiceClassName = serviceName,
            sourceIntent = sourceIntent,
            reason = if (foreground) "implicitForeground" else "implicit",
            foreground = foreground
        )
    }

    fun createProxyIntent(request: VirtualServiceStartRequest): Intent {
        val spec = createProxySpec(request)
        return Intent().apply {
            setClassName(spec.hostPackageName, spec.stubServiceClassName)
            putExtra(EXTRA_VIRTUAL_SERVICE_TOKEN, spec.token)
            putExtra(EXTRA_INSTANCE_ID, spec.instanceId)
            putExtra(EXTRA_ORIGIN_PACKAGE_NAME, spec.originPackageName)
            putExtra(EXTRA_GUEST_SERVICE_CLASS_NAME, spec.guestServiceClassName)
            putExtra(EXTRA_SERVICE_START_REASON, spec.reason)
            putExtra(EXTRA_FOREGROUND_SERVICE, spec.foreground)
            VirtualServiceIntentStore.remember(spec.token, request.sourceIntent)
        }
    }

    fun requestFromProxyIntent(proxyIntent: Intent): VirtualServiceStartRequest? {
        val instanceId = proxyIntent.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
        val originPackageName = proxyIntent.getStringExtra(EXTRA_ORIGIN_PACKAGE_NAME).orEmpty()
        val guestServiceClassName = proxyIntent.getStringExtra(EXTRA_GUEST_SERVICE_CLASS_NAME).orEmpty()
        if (instanceId.isBlank() || originPackageName.isBlank() || guestServiceClassName.isBlank()) return null
        val reason = proxyIntent.getStringExtra(EXTRA_SERVICE_START_REASON).orEmpty().ifBlank { "explicit" }
        val proxyToken = proxyIntent.getStringExtra(EXTRA_VIRTUAL_SERVICE_TOKEN)
        val sourceIntent = VirtualServiceIntentStore.find(proxyToken)
            ?: legacyOriginalGuestIntent(proxyIntent)
            ?: Intent().setComponent(ComponentName(originPackageName, guestServiceClassName))
        return VirtualServiceStartRequest(
            instanceId = instanceId,
            originPackageName = originPackageName,
            guestServiceClassName = guestServiceClassName,
            sourceIntent = sourceIntent,
            reason = reason,
            foreground = proxyIntent.getBooleanExtra(EXTRA_FOREGROUND_SERVICE, false),
            proxyToken = proxyToken
        )
    }

    fun createProxySpec(request: VirtualServiceStartRequest): VirtualServiceProxySpec = VirtualServiceProxySpec(
        hostPackageName = hostPackageName,
        stubServiceClassName = stubServiceClassName,
        token = UUID.randomUUID().toString(),
        instanceId = request.instanceId,
        originPackageName = request.originPackageName,
        guestServiceClassName = request.guestServiceClassName,
        reason = request.reason,
        foreground = request.foreground
    )

    private fun normalizeServiceClassName(packageName: String, className: String): String = when {
        className.startsWith(".") -> packageName + className
        '.' !in className -> "$packageName.$className"
        else -> className
    }

    @Suppress("DEPRECATION")
    private fun legacyOriginalGuestIntent(proxyIntent: Intent): Intent? =
        runCatching {
            proxyIntent.getParcelableExtra<Intent>(EXTRA_ORIGINAL_GUEST_INTENT)
        }.getOrNull()
}

class VirtualServiceDispatcher(
    private val hostContext: Context?,
    private val packageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    private val processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    private val serviceManager: VirtualServiceManager = VirtualServiceManager(hostContext?.packageName.orEmpty()),
    private val serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime.global
) {
    fun dispatch(proxyIntent: Intent?, flags: Int, startId: Int): VirtualServiceDispatchResult {
        if (proxyIntent == null) return VirtualServiceDispatchResult.InvalidProxyIntent("missing intent")
        val startRequest = serviceManager.requestFromProxyIntent(proxyIntent)
            ?: return VirtualServiceDispatchResult.InvalidProxyIntent("missing service extras")
        return dispatch(startRequest, flags, startId)
    }

    fun dispatch(
        startRequest: VirtualServiceStartRequest,
        flags: Int,
        startId: Int
    ): VirtualServiceDispatchResult {
        val snapshot = packageRegistry.getByInstanceId(startRequest.instanceId)
            ?: return VirtualServiceDispatchResult.InstanceNotFound(startRequest)
        val runtimeRecord = processRuntime.get(startRequest.instanceId)
            ?: return VirtualServiceDispatchResult.RuntimeNotBound(startRequest)
        val guestClassLoader = runtimeRecord.result.guestClassLoader
            ?: return VirtualServiceDispatchResult.RuntimeIncomplete(startRequest, "missing guestClassLoader")
        val context = hostContext
            ?: return VirtualServiceDispatchResult.RuntimeIncomplete(startRequest, "missing hostContext")
        val config = VirtualContextConfig(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = snapshot.nativeLibraryDir,
            classLoader = guestClassLoader,
            applicationLabel = snapshot.applicationLabel,
            packageSnapshot = snapshot,
            splitSourceDirs = snapshot.splitSourceDirs,
            splitPublicSourceDirs = snapshot.splitPublicSourceDirs,
            splitNames = snapshot.splitNames,
            isolatedSplits = snapshot.isolatedSplits
        )
        val guestContext = VirtualContextWrappers.create(
            base = context,
            config = config,
            guestClassLoader = guestClassLoader
        )

        return when (val result = serviceRuntime.start(
            VirtualServiceRuntimeStartRequest(
                startRequest = startRequest,
                guestContext = guestContext,
                guestClassLoader = guestClassLoader,
                guestApplication = runtimeRecord.result.guestApplication,
                config = config,
                flags = flags,
                startId = startId
            )
        )) {
            is VirtualServiceRuntimeResult.CreatedAndStarted -> VirtualServiceDispatchResult.ServiceStarted(
                startRequest = result.startRequest,
                cached = false,
                startCommandResult = result.startCommandResult,
                lifecycleEvidence = VirtualServiceLifecycleEvidence.from(result)
            )
            is VirtualServiceRuntimeResult.StartedCached -> VirtualServiceDispatchResult.ServiceStarted(
                startRequest = result.startRequest,
                cached = true,
                startCommandResult = result.startCommandResult,
                lifecycleEvidence = VirtualServiceLifecycleEvidence.from(result)
            )
            is VirtualServiceRuntimeResult.CreateFailed -> VirtualServiceDispatchResult.ServiceCreateFailed(
                result.startRequest,
                result.error,
                VirtualServiceLifecycleEvidence.from(result)
            )
            is VirtualServiceRuntimeResult.AttachFailed -> VirtualServiceDispatchResult.ServiceAttachFailed(
                result.startRequest,
                result.error,
                VirtualServiceLifecycleEvidence.from(result)
            )
            is VirtualServiceRuntimeResult.OnCreateFailed -> VirtualServiceDispatchResult.ServiceOnCreateFailed(
                result.startRequest,
                result.error,
                VirtualServiceLifecycleEvidence.from(result)
            )
            is VirtualServiceRuntimeResult.OnStartCommandFailed -> VirtualServiceDispatchResult.ServiceOnStartCommandFailed(
                result.startRequest,
                result.cached,
                result.error,
                VirtualServiceLifecycleEvidence.from(result)
            )
        }
    }

    fun dispatchStop(stopRequest: VirtualServiceStopRequest): VirtualServiceStopDispatchResult {
        packageRegistry.getByInstanceId(stopRequest.instanceId)
            ?: return VirtualServiceStopDispatchResult.InstanceNotFound(stopRequest)

        return when (val result = serviceRuntime.stop(stopRequest)) {
            is VirtualServiceRuntimeStopResult.Stopped -> VirtualServiceStopDispatchResult.ServiceStopped(
                stopRequest = result.stopRequest,
                lifecycleEvidence = VirtualServiceLifecycleEvidence.from(result)
            )
            is VirtualServiceRuntimeStopResult.NotFound -> VirtualServiceStopDispatchResult.ServiceNotFound(
                stopRequest = result.stopRequest,
                lifecycleEvidence = VirtualServiceLifecycleEvidence.from(result)
            )
            is VirtualServiceRuntimeStopResult.OnDestroyFailed -> VirtualServiceStopDispatchResult.ServiceOnDestroyFailed(
                stopRequest = result.stopRequest,
                error = result.error,
                lifecycleEvidence = VirtualServiceLifecycleEvidence.from(result)
            )
        }
    }
}

sealed class VirtualServiceDispatchResult {
    abstract val startRequest: VirtualServiceStartRequest?

    data class ServiceStarted(
        override val startRequest: VirtualServiceStartRequest,
        val cached: Boolean,
        val startCommandResult: Int,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceDispatchResult()

    data class RuntimeNotBound(
        override val startRequest: VirtualServiceStartRequest
    ) : VirtualServiceDispatchResult()

    data class RuntimeIncomplete(
        override val startRequest: VirtualServiceStartRequest,
        val reason: String
    ) : VirtualServiceDispatchResult()

    data class Unsupported(
        override val startRequest: VirtualServiceStartRequest,
        val reason: String
    ) : VirtualServiceDispatchResult()

    data class ServiceCreateFailed(
        override val startRequest: VirtualServiceStartRequest,
        val error: Throwable,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceDispatchResult()

    data class ServiceAttachFailed(
        override val startRequest: VirtualServiceStartRequest,
        val error: Throwable,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceDispatchResult()

    data class ServiceOnCreateFailed(
        override val startRequest: VirtualServiceStartRequest,
        val error: Throwable,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceDispatchResult()

    data class ServiceOnStartCommandFailed(
        override val startRequest: VirtualServiceStartRequest,
        val cached: Boolean,
        val error: Throwable,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceDispatchResult()

    data class InvalidProxyIntent(val reason: String) : VirtualServiceDispatchResult() {
        override val startRequest: VirtualServiceStartRequest? = null
    }

    data class InstanceNotFound(
        override val startRequest: VirtualServiceStartRequest
    ) : VirtualServiceDispatchResult()
}

sealed class VirtualServiceStopDispatchResult {
    abstract val stopRequest: VirtualServiceStopRequest

    data class ServiceStopped(
        override val stopRequest: VirtualServiceStopRequest,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceStopDispatchResult()

    data class ServiceNotFound(
        override val stopRequest: VirtualServiceStopRequest,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceStopDispatchResult()

    data class ServiceOnDestroyFailed(
        override val stopRequest: VirtualServiceStopRequest,
        val error: Throwable,
        val lifecycleEvidence: VirtualServiceLifecycleEvidence
    ) : VirtualServiceStopDispatchResult()

    data class InstanceNotFound(
        override val stopRequest: VirtualServiceStopRequest
    ) : VirtualServiceStopDispatchResult()
}

sealed class VirtualServiceBindDispatchResult {
    abstract val startRequest: VirtualServiceStartRequest?

    data class Bound(
        override val startRequest: VirtualServiceStartRequest,
        val componentName: ComponentName,
        val binder: IBinder?,
        val cached: Boolean,
        val bindKey: String,
        val flags: Int,
        val bindCount: Int,
        val activeConnectionCount: Int,
        val reusedBinder: Boolean,
        val rebindDelivered: Boolean,
        val connectionReused: Boolean = false,
        val nullBinding: Boolean = false
    ) : VirtualServiceBindDispatchResult()

    data class Blocked(
        val sourceIntent: Intent,
        val reason: String,
        val serviceResolved: Boolean,
        val flags: Int? = null,
        val autoCreate: Boolean? = null,
        val serviceAlreadyRunning: Boolean? = null
    ) : VirtualServiceBindDispatchResult() {
        override val startRequest: VirtualServiceStartRequest? = null
    }

    data class Failed(
        override val startRequest: VirtualServiceStartRequest,
        val stage: String,
        val error: Throwable
    ) : VirtualServiceBindDispatchResult()
}

sealed class VirtualServiceUnbindDispatchResult {
    data class Unbound(
        val startRequest: VirtualServiceStartRequest,
        val destroyed: Boolean,
        val onUnbindResult: Boolean,
        val onUnbindCalled: Boolean,
        val bindKey: String,
        val activeConnectionCount: Int,
        val activeBindCount: Int,
        val idleStopResult: HostServiceIdleStopResult = HostServiceIdleStopResult.notRequested("serviceStillActive")
    ) : VirtualServiceUnbindDispatchResult()

    data object NotFound : VirtualServiceUnbindDispatchResult()

    data class Failed(
        val startRequest: VirtualServiceStartRequest,
        val stage: String,
        val error: Throwable
    ) : VirtualServiceUnbindDispatchResult()
}

fun Context.startHostedServiceProxy(snapshot: VirtualPackageSnapshot, intent: Intent): ComponentName? {
    val manager = VirtualServiceManager(packageName)
    val request = manager.resolveStartService(snapshot, intent) ?: return null
    return startService(manager.createProxyIntent(request))
}
