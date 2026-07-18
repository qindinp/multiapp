package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.ProxyActivitySlotExhaustedException
import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.IdentityHashMap
import java.util.concurrent.Executor

data class VirtualBroadcastDispatchOptions(
    val ordered: Boolean = false,
    val sticky: Boolean = false,
    val expectsResultReceiver: Boolean = false,
    val abortSupportedRequested: Boolean = false,
    val receiverPermissions: Set<String> = emptySet(),
    val receiverAppOp: String? = null,
    val asUserRequested: Boolean = false,
    val platformOptionsPresent: Boolean = false
) {
    companion object {
        val DEFAULT = VirtualBroadcastDispatchOptions()
    }
}

interface VirtualAmsComponentDispatcher {
    fun resolveStartActivityIntent(intent: Intent): VirtualContextWrapper.StartActivityMappingResult

    fun resolveStartActivityIntents(intents: List<Intent>): List<VirtualContextWrapper.StartActivityMappingResult> =
        intents.map { intent -> resolveStartActivityIntent(intent) }

    fun resolveStartServiceIntent(
        intent: Intent,
        foreground: Boolean
    ): VirtualContextWrapper.StartServiceMappingResult

    fun shouldDispatchServiceToSystem(intent: Intent): Boolean = false

    fun dispatchStopService(intent: Intent): VirtualServiceStopDispatchResult?

    fun dispatchBindService(
        intent: Intent,
        virtualContext: Context,
        guestClassLoader: ClassLoader,
        connection: ServiceConnection,
        flags: Int,
        executor: Executor?
    ): VirtualServiceBindDispatchResult

    fun dispatchUnbindService(connection: ServiceConnection): VirtualServiceUnbindDispatchResult

    fun dispatchBroadcast(
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader
    ): VirtualBroadcastResult

    fun dispatchBroadcast(
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader,
        options: VirtualBroadcastDispatchOptions
    ): VirtualBroadcastResult = dispatchBroadcast(intent, virtualContext, receiverClassLoader)
}

class DefaultVirtualAmsComponentDispatcher(
    private val hostContext: Context? = null,
    private val hostPackageName: String = hostContext?.packageName.orEmpty(),
    private val packageSnapshot: VirtualPackageSnapshot?,
    private val instanceId: String = packageSnapshot?.instanceId.orEmpty(),
    private val processSlot: String? = null,
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    private val proxyActivityRegistry: ProxyActivityRegistry = defaultProxyActivityRegistry(
        hostPackageName,
        processSlot
    ),
    private val proxyActivitySlotAssignmentStore: ProxyActivitySlotAssignmentStore? = null,
    private val servicePackageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    private val serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime.global,
    private val broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
    private val serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { manager, request ->
        manager.createProxyIntent(request)
    },
    private val activityProxyIntentFactory: ((VirtualActivityRecord, Intent) -> Intent)? = null,
    private val processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global
) : VirtualAmsComponentDispatcher {

    private val boundServiceConnections = IdentityHashMap<ServiceConnection, VirtualServiceBoundConnection>()

    private val activityManager: VirtualActivityManager? by lazy(LazyThreadSafetyMode.NONE) {
        hostContext?.let { context ->
            VirtualActivityManager(
                context = context,
                proxyActivityRegistry = proxyActivityRegistry,
                hostPackageName = hostPackageName,
                activityRecordManager = activityRecordManager
            )
        }
    }

    override fun resolveStartActivityIntent(intent: Intent): VirtualContextWrapper.StartActivityMappingResult {
        return when (val plan = planStartActivityIntent(intent)) {
            is ActivityStartPlan.Blocked -> plan.result
            is ActivityStartPlan.Resolved -> {
                if (
                    activityProxyIntentFactory == null &&
                    proxyActivitySlotAssignmentStore == null &&
                    ProxyActivitySlotAssignmentStoreProvider.currentStoreOrNull() == null
                ) {
                    return blockedProxyActivitySlot(intent)
                }
                proxyActivityRegistry.registerExisting(activityRecordManager.list())
                try {
                    remapStartActivityRequest(plan.request)
                } catch (error: Throwable) {
                    if (error.isProxyActivitySlotUnavailable()) {
                        blockedProxyActivitySlot(intent)
                    } else {
                        throw error
                    }
                }
            }
        }
    }

    override fun resolveStartActivityIntents(intents: List<Intent>): List<VirtualContextWrapper.StartActivityMappingResult> {
        val plans = intents.map { intent -> planStartActivityIntent(intent) }
        val blocked = plans.filterIsInstance<ActivityStartPlan.Blocked>().firstOrNull()
        if (blocked != null) return listOf(blocked.result)

        proxyActivityRegistry.registerExisting(activityRecordManager.list())
        val assignmentStore = proxyActivitySlotAssignmentStore
            ?: ProxyActivitySlotAssignmentStoreProvider.currentStoreOrNull()
            ?: return intents.map(::blockedProxyActivitySlot)
        val assignmentRollback = ProxyActivitySlotAssignmentRollback(assignmentStore)
        val activityStateSnapshot = activityRecordManager.snapshotState()
        val registryTokensBeforeBatch = proxyActivityRegistry.listRecords().mapTo(hashSetOf()) { it.token }
        return try {
            plans
                .filterIsInstance<ActivityStartPlan.Resolved>()
                .map { plan ->
                    assignmentRollback.remember(plan.request.proxyActivitySlotKey())
                    remapStartActivityRequest(plan.request)
                }
        } catch (error: Throwable) {
            proxyActivityRegistry.listRecords()
                .filterNot { it.token in registryTokensBeforeBatch }
                .forEach { proxyActivityRegistry.consume(it.token) }
            activityRecordManager.restoreState(activityStateSnapshot)
            assignmentRollback.restore()
            if (error.isProxyActivitySlotUnavailable()) {
                intents.map(::blockedProxyActivitySlot)
            } else {
                throw error
            }
        }
    }

    private fun planStartActivityIntent(intent: Intent): ActivityStartPlan {
        val snapshot = packageSnapshot ?: return ActivityStartPlan.Blocked(
            VirtualContextWrapper.StartActivityMappingResult.Blocked(
                sourceIntent = intent,
                reason = "missingPackageSnapshot"
            )
        )
        val request = VirtualIntentResolver(snapshot).resolveActivity(intent)
            ?: return ActivityStartPlan.Blocked(
                VirtualContextWrapper.StartActivityMappingResult.Blocked(
                    sourceIntent = intent,
                    reason = "unsupportedActivityIntent"
                )
            )
        return ActivityStartPlan.Resolved(request)
    }

    private fun remapStartActivityRequest(
        request: VirtualActivityLaunchRequest
    ): VirtualContextWrapper.StartActivityMappingResult.Remapped {
        val manager = activityManager
        val record = manager?.allocateGuestActivity(request)
            ?: allocateGuestActivityFallback(request)
        val proxyIntentFactory = activityProxyIntentFactory
            ?: { activityRecord: VirtualActivityRecord, sourceIntent: Intent ->
                manager?.createProxyIntent(activityRecord, sourceIntent)
                    ?: createProxyIntent(activityRecord, sourceIntent)
            }
        return VirtualContextWrapper.StartActivityMappingResult.Remapped(
            sourceIntent = request.sourceIntent,
            proxyIntent = proxyIntentFactory(record, request.sourceIntent)
        )
    }

    private fun allocateGuestActivityFallback(request: VirtualActivityLaunchRequest): VirtualActivityRecord {
        val taskAffinity = request.taskAffinity ?: rootTaskAffinity(
            originPackageName = request.originPackageName,
            instanceId = request.instanceId
        )
        val record = proxyActivityRegistry.allocate(
            instanceId = request.instanceId,
            originPackageName = request.originPackageName,
            guestActivityClassName = request.guestActivityClassName,
            launchMode = request.launchMode,
            taskKey = taskAffinity,
            taskAffinity = taskAffinity
        )
        return activityRecordManager.registerLaunch(
            record = record,
            intentFlags = request.sourceIntent.safeFlags(),
            dataIntent = request.sourceIntent.toVirtualIntentSnapshot()
        ).activity
    }

    private fun blockedProxyActivitySlot(
        sourceIntent: Intent
    ): VirtualContextWrapper.StartActivityMappingResult.Blocked =
        VirtualContextWrapper.StartActivityMappingResult.Blocked(
            sourceIntent = sourceIntent,
            reason = PROXY_ACTIVITY_SLOT_UNAVAILABLE_REASON
        )

    private fun Throwable.isProxyActivitySlotUnavailable(): Boolean =
        this is ProxyActivitySlotExhaustedException ||
            this is ProxyActivitySlotAssignmentStoreProviderNotInstalledException

    private sealed class ActivityStartPlan {
        data class Resolved(val request: VirtualActivityLaunchRequest) : ActivityStartPlan()

        data class Blocked(
            val result: VirtualContextWrapper.StartActivityMappingResult.Blocked
        ) : ActivityStartPlan()
    }

    override fun resolveStartServiceIntent(
        intent: Intent,
        foreground: Boolean
    ): VirtualContextWrapper.StartServiceMappingResult {
        if (shouldDispatchServiceToSystem(intent)) {
            return VirtualContextWrapper.StartServiceMappingResult.SystemPassthrough(
                sourceIntent = intent,
                foreground = foreground,
                targetPackageName = intent.externalServiceTargetPackage().orEmpty(),
                reason = "external_system_service"
            )
        }
        val snapshot = packageSnapshot ?: return VirtualContextWrapper.StartServiceMappingResult.Blocked(
            sourceIntent = intent,
            foreground = foreground,
            reason = "missingPackageSnapshot"
        )
        val manager = VirtualServiceManager(hostPackageName = hostPackageName)
        val resolvedRequest = if (foreground) {
            manager.resolveStartForegroundService(snapshot, intent)
        } else {
            manager.resolveStartService(snapshot, intent)
        }
            ?: return VirtualContextWrapper.StartServiceMappingResult.Blocked(
                sourceIntent = intent,
                foreground = foreground,
                reason = "unsupportedServiceIntent"
            )
        val request = resolvedRequest.copy(processSlot = processRuntime.get(resolvedRequest.instanceId)?.result?.processSlot ?: processSlot)
        val proxyIntent = serviceProxyIntentFactory(manager, request)
        return VirtualContextWrapper.StartServiceMappingResult.Remapped(
            sourceIntent = intent,
            foreground = foreground,
            startRequest = request,
            proxyIntent = proxyIntent
        )
    }

    override fun shouldDispatchServiceToSystem(intent: Intent): Boolean {
        val snapshot = packageSnapshot ?: return false
        val targetPackageName = intent.externalServiceTargetPackage()
            ?: hostContext?.packageManager
                ?.resolveService(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.serviceInfo
                ?.packageName
                ?.takeIf { it.isNotBlank() }
            ?: return false
        return targetPackageName != snapshot.originPackageName &&
            targetPackageName != snapshot.virtualPackageName &&
            targetPackageName != hostPackageName
    }

    private fun Intent.externalServiceTargetPackage(): String? =
        runCatching { component?.packageName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { `package` }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun dispatchStopService(intent: Intent): VirtualServiceStopDispatchResult? {
        val snapshot = packageSnapshot ?: return null
        val manager = VirtualServiceManager(hostPackageName = hostPackageName)
        val request = manager.resolveStopService(snapshot, intent) ?: return null
        return VirtualServiceDispatcher(
            hostContext = hostContext,
            packageRegistry = servicePackageRegistry,
            activityRecordManager = activityRecordManager,
            serviceRuntime = serviceRuntime
        ).dispatchStop(request)
    }

    override fun dispatchBindService(
        intent: Intent,
        virtualContext: Context,
        guestClassLoader: ClassLoader,
        connection: ServiceConnection,
        flags: Int,
        executor: Executor?
    ): VirtualServiceBindDispatchResult {
        val snapshot = packageSnapshot ?: return VirtualServiceBindDispatchResult.Blocked(
            sourceIntent = intent,
            reason = "missingPackageSnapshot",
            serviceResolved = false
        )
        val serviceManager = VirtualServiceManager(hostPackageName = hostPackageName)
        val resolvedStartRequest = serviceManager.resolveStartService(snapshot, intent)
        if (resolvedStartRequest == null) {
            return VirtualServiceBindDispatchResult.Blocked(
                sourceIntent = intent,
                reason = "unsupportedServiceIntent",
                serviceResolved = false
            )
        }
        val startRequest = resolvedStartRequest.copy(
            processSlot = processRuntime.get(resolvedStartRequest.instanceId)?.result?.processSlot ?: processSlot
        )
        val existingConnection = boundServiceConnections[connection]
        if (existingConnection != null) {
            if (!existingConnection.startRequest.hasSameServiceTarget(startRequest)) {
                return VirtualServiceBindDispatchResult.Blocked(
                    sourceIntent = intent,
                    reason = "serviceConnectionAlreadyBoundToDifferentService",
                    serviceResolved = true,
                    flags = flags,
                    autoCreate = flags and Context.BIND_AUTO_CREATE != 0,
                    serviceAlreadyRunning = false
                )
            }
            return VirtualServiceBindDispatchResult.Bound(
                startRequest = existingConnection.startRequest,
                componentName = existingConnection.componentName,
                binder = existingConnection.binder,
                cached = true,
                bindKey = existingConnection.bindKey,
                flags = existingConnection.flags,
                bindCount = existingConnection.bindCount,
                activeConnectionCount = existingConnection.activeConnectionCount,
                reusedBinder = true,
                rebindDelivered = false,
                connectionReused = true,
                nullBinding = existingConnection.nullBinding
            )
        }
        val bindRequest = VirtualServiceRuntimeBindRequest(
            startRequest = startRequest,
            guestContext = virtualContext,
            guestClassLoader = guestClassLoader,
            guestApplication = processRuntime.get(startRequest.instanceId)?.result?.guestApplication,
            config = snapshot.toVirtualContextConfig(guestClassLoader),
            flags = flags
        )
        return when (val result = serviceRuntime.bind(bindRequest)) {
            is VirtualServiceRuntimeBindResult.NotCreated -> VirtualServiceBindDispatchResult.Blocked(
                sourceIntent = intent,
                reason = result.reason,
                serviceResolved = true,
                flags = result.flags,
                autoCreate = result.flags and Context.BIND_AUTO_CREATE != 0,
                serviceAlreadyRunning = result.serviceAlreadyRunning
            )
            is VirtualServiceRuntimeBindResult.Bound -> {
                val componentName = android.content.ComponentName(
                    result.startRequest.originPackageName,
                    result.startRequest.guestServiceClassName
                )
                val boundConnection = VirtualServiceBoundConnection(
                    connection = connection,
                    startRequest = result.startRequest,
                    componentName = componentName,
                    binder = result.binder,
                    bindKey = result.bindKey,
                    flags = result.flags,
                    bindCount = result.bindCount,
                    activeConnectionCount = result.activeConnectionCount,
                    nullBinding = result.binder == null
                )
                boundServiceConnections[connection] = boundConnection
                dispatchServiceConnectedCallback(
                    boundConnection = boundConnection,
                    executor = executor
                )
                VirtualServiceBindDispatchResult.Bound(
                    startRequest = result.startRequest,
                    componentName = componentName,
                    binder = result.binder,
                    cached = result.cached,
                    bindKey = result.bindKey,
                    flags = result.flags,
                    bindCount = result.bindCount,
                    activeConnectionCount = result.activeConnectionCount,
                    reusedBinder = result.reusedBinder,
                    rebindDelivered = result.rebindDelivered,
                    connectionReused = false,
                    nullBinding = result.binder == null
                )
            }
            is VirtualServiceRuntimeBindResult.CreateFailed -> VirtualServiceBindDispatchResult.Failed(
                startRequest = result.startRequest,
                stage = "create",
                error = result.error
            )
            is VirtualServiceRuntimeBindResult.AttachFailed -> VirtualServiceBindDispatchResult.Failed(
                startRequest = result.startRequest,
                stage = "attach",
                error = result.error
            )
            is VirtualServiceRuntimeBindResult.OnCreateFailed -> VirtualServiceBindDispatchResult.Failed(
                startRequest = result.startRequest,
                stage = "onCreate",
                error = result.error
            )
            is VirtualServiceRuntimeBindResult.OnBindFailed -> VirtualServiceBindDispatchResult.Failed(
                startRequest = result.startRequest,
                stage = "onBind",
                error = result.error
            )
        }
    }

    override fun dispatchUnbindService(connection: ServiceConnection): VirtualServiceUnbindDispatchResult {
        val bound = boundServiceConnections.remove(connection)
            ?: return VirtualServiceUnbindDispatchResult.NotFound
        return when (val result = serviceRuntime.unbind(
            VirtualServiceRuntimeUnbindRequest(
                startRequest = bound.startRequest
            )
        )) {
            is VirtualServiceRuntimeUnbindResult.Unbound -> VirtualServiceUnbindDispatchResult.Unbound(
                startRequest = result.startRequest,
                destroyed = result.destroyed,
                onUnbindResult = result.onUnbindResult,
                onUnbindCalled = result.onUnbindCalled,
                bindKey = result.bindKey,
                activeConnectionCount = result.activeConnectionCount,
                activeBindCount = result.activeBindCount,
                idleStopResult = result.idleStopResult
            )
            is VirtualServiceRuntimeUnbindResult.NotFound -> VirtualServiceUnbindDispatchResult.NotFound
            is VirtualServiceRuntimeUnbindResult.OnUnbindFailed -> VirtualServiceUnbindDispatchResult.Failed(
                startRequest = result.startRequest,
                stage = "onUnbind",
                error = result.error
            )
            is VirtualServiceRuntimeUnbindResult.OnDestroyFailed -> VirtualServiceUnbindDispatchResult.Failed(
                startRequest = result.startRequest,
                stage = "onDestroy",
                error = result.error
            )
        }
    }

    override fun dispatchBroadcast(
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader
    ): VirtualBroadcastResult {
        return broadcastManager.dispatch(
            instanceId = instanceId,
            snapshot = packageSnapshot,
            intent = intent,
            virtualContext = virtualContext,
            receiverClassLoader = receiverClassLoader
        )
    }

    private fun createProxyIntent(record: VirtualActivityRecord, sourceIntent: Intent): Intent {
        return Intent().apply {
            setClassName(hostPackageName, record.proxyActivityClassName)
            putExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN, record.token)
            putExtra(VirtualActivityManager.EXTRA_INSTANCE_ID, record.instanceId)
            putExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME, record.originPackageName)
            putExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME, record.guestActivityClassName)
            putExtra(VirtualActivityManager.EXTRA_HOST_PACKAGE_NAME, hostPackageName)
            if (!record.launchMode.isNullOrBlank()) {
                putExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_LAUNCH_MODE, record.launchMode)
            }
            if (!record.taskAffinity.isNullOrBlank()) {
                putExtra(VirtualActivityManager.EXTRA_GUEST_TASK_AFFINITY, record.taskAffinity)
            }
            VirtualActivityIntentStore.remember(record.token, sourceIntent)
            if (sourceIntent.safeFlags().hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK)) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun Intent.safeFlags(): Int = runCatching { flags }.getOrDefault(0)

    private fun Int.hasFlag(flag: Int): Boolean = this and flag != 0

    private fun Intent.toVirtualIntentSnapshot(): VirtualIntentSnapshot {
        val sourceExtras = runCatching { extras }.getOrNull()
        val extrasSnapshot = sourceExtras
            ?.keySet()
            ?.associateWith { "<present>" }
            .orEmpty()
        return VirtualIntentSnapshot(
            flags = safeFlags(),
            action = runCatching { action }.getOrNull(),
            dataUri = runCatching { dataString?.redactUriForEvidence() }.getOrNull(),
            categories = runCatching { categories.orEmpty().toSet() }.getOrDefault(emptySet()),
            extras = extrasSnapshot
        )
    }

    private fun String.redactUriForEvidence(): String = EvidenceSanitizer.redactUriForEvidence(this)

    private fun VirtualPackageSnapshot.toVirtualContextConfig(classLoader: ClassLoader) =
        com.multiapp.core.model.virtual.VirtualContextConfig(
            instanceId = instanceId,
            originPackageName = originPackageName,
            virtualPackageName = virtualPackageName,
            dataDir = dataDir,
            sourceDir = sourceDir,
            nativeLibraryDir = nativeLibraryDir,
            classLoader = classLoader,
            applicationLabel = applicationLabel,
            packageSnapshot = this,
            splitSourceDirs = splitSourceDirs,
            splitPublicSourceDirs = splitPublicSourceDirs,
            splitNames = splitNames,
            isolatedSplits = isolatedSplits,
            processSlot = processRuntime.get(instanceId)?.result?.processSlot ?: processSlot
        )

    private data class VirtualServiceBoundConnection(
        val connection: ServiceConnection,
        val startRequest: VirtualServiceStartRequest,
        val componentName: android.content.ComponentName,
        val binder: android.os.IBinder?,
        val bindKey: String,
        val flags: Int,
        val bindCount: Int,
        val activeConnectionCount: Int,
        val nullBinding: Boolean
    )

    private fun VirtualServiceStartRequest.hasSameServiceTarget(
        other: VirtualServiceStartRequest
    ): Boolean =
        instanceId == other.instanceId &&
            originPackageName == other.originPackageName &&
            guestServiceClassName == other.guestServiceClassName

    private fun dispatchServiceConnectedCallback(
        boundConnection: VirtualServiceBoundConnection,
        executor: Executor?
    ) {
        val callback = Runnable {
            if (boundServiceConnections[boundConnection.connection] !== boundConnection) {
                return@Runnable
            }
            if (boundConnection.nullBinding) {
                boundConnection.connection.onNullBinding(boundConnection.componentName)
            } else {
                boundConnection.connection.onServiceConnected(
                    boundConnection.componentName,
                    boundConnection.binder
                )
            }
        }
        dispatchServiceConnectionCallback(executor, callback)
    }

    private fun dispatchServiceConnectionCallback(executor: Executor?, callback: Runnable) {
        val dispatch = Runnable {
            if (executor != null) {
                executor.execute(callback)
            } else {
                callback.run()
            }
        }
        runCatching {
            Handler(Looper.getMainLooper()).post(dispatch)
        }.getOrElse {
            dispatch.run()
        }
    }

    companion object {
        internal const val PROXY_ACTIVITY_SLOT_UNAVAILABLE_REASON = "proxyActivitySlotUnavailable"

        fun defaultProxyActivityRegistry(
            hostPackageName: String,
            processSlot: String? = null
        ): ProxyActivityRegistry {
            return ProxyActivityRegistry(
                ProxyActivitySlots.classNamesForProcessSlot(hostPackageName, processSlot),
                ProxyActivitySlots.launchModeByClassName(hostPackageName),
                ProviderBackedProxyActivitySlotAssignmentStore
            )
        }

        private fun rootTaskAffinity(originPackageName: String, instanceId: String): String =
            "$originPackageName:$instanceId"
    }
}
