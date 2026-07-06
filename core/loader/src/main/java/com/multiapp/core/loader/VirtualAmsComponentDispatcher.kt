package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import com.multiapp.core.common.EvidenceSanitizer
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

interface VirtualAmsComponentDispatcher {
    fun resolveStartActivityIntent(intent: Intent): VirtualContextWrapper.StartActivityMappingResult

    fun resolveStartActivityIntents(intents: List<Intent>): List<VirtualContextWrapper.StartActivityMappingResult> =
        intents.map { intent -> resolveStartActivityIntent(intent) }

    fun resolveStartServiceIntent(
        intent: Intent,
        foreground: Boolean
    ): VirtualContextWrapper.StartServiceMappingResult

    fun dispatchStopService(intent: Intent): VirtualServiceStopDispatchResult?

    fun dispatchBroadcast(
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader
    ): VirtualBroadcastResult
}

class DefaultVirtualAmsComponentDispatcher(
    private val hostContext: Context? = null,
    private val hostPackageName: String = hostContext?.packageName.orEmpty(),
    private val packageSnapshot: VirtualPackageSnapshot?,
    private val instanceId: String = packageSnapshot?.instanceId.orEmpty(),
    private val activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    private val proxyActivityRegistry: ProxyActivityRegistry = defaultProxyActivityRegistry(hostPackageName),
    private val servicePackageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    private val serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime.global,
    private val broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
    private val serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { manager, request ->
        manager.createProxyIntent(request)
    },
    private val activityProxyIntentFactory: ((VirtualActivityRecord, Intent) -> Intent)? = null
) : VirtualAmsComponentDispatcher {

    override fun resolveStartActivityIntent(intent: Intent): VirtualContextWrapper.StartActivityMappingResult {
        return when (val plan = planStartActivityIntent(intent)) {
            is ActivityStartPlan.Blocked -> plan.result
            is ActivityStartPlan.Resolved -> {
                proxyActivityRegistry.registerExisting(activityRecordManager.list())
                remapStartActivityRequest(plan.request)
            }
        }
    }

    override fun resolveStartActivityIntents(intents: List<Intent>): List<VirtualContextWrapper.StartActivityMappingResult> {
        val plans = intents.map { intent -> planStartActivityIntent(intent) }
        val blocked = plans.filterIsInstance<ActivityStartPlan.Blocked>().firstOrNull()
        if (blocked != null) return listOf(blocked.result)

        proxyActivityRegistry.registerExisting(activityRecordManager.list())
        return plans
            .filterIsInstance<ActivityStartPlan.Resolved>()
            .map { plan -> remapStartActivityRequest(plan.request) }
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
        val record = proxyActivityRegistry.allocate(
            instanceId = request.instanceId,
            originPackageName = request.originPackageName,
            guestActivityClassName = request.guestActivityClassName,
            launchMode = request.launchMode
        )
        val launchResult = activityRecordManager.registerLaunch(
            record = record,
            intentFlags = request.sourceIntent.safeFlags(),
            dataIntent = request.sourceIntent.toVirtualIntentSnapshot()
        )
        val proxyIntentFactory = activityProxyIntentFactory ?: ::createProxyIntent
        return VirtualContextWrapper.StartActivityMappingResult.Remapped(
            sourceIntent = request.sourceIntent,
            proxyIntent = proxyIntentFactory(launchResult.activity, request.sourceIntent)
        )
    }

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
        val snapshot = packageSnapshot ?: return VirtualContextWrapper.StartServiceMappingResult.Blocked(
            sourceIntent = intent,
            foreground = foreground,
            reason = "missingPackageSnapshot"
        )
        if (intent.component == null) {
            return VirtualContextWrapper.StartServiceMappingResult.Blocked(
                sourceIntent = intent,
                foreground = foreground,
                reason = "implicitServiceIntent"
            )
        }
        val manager = VirtualServiceManager(hostPackageName = hostPackageName)
        val request = if (foreground) {
            manager.resolveStartForegroundService(snapshot, intent)
        } else {
            manager.resolveStartService(snapshot, intent)
        } ?: return VirtualContextWrapper.StartServiceMappingResult.Blocked(
            sourceIntent = intent,
            foreground = foreground,
            reason = "unsupportedServiceIntent"
        )
        val proxyIntent = serviceProxyIntentFactory(manager, request)
        return VirtualContextWrapper.StartServiceMappingResult.Remapped(
            sourceIntent = intent,
            foreground = foreground,
            startRequest = request,
            proxyIntent = proxyIntent
        )
    }

    override fun dispatchStopService(intent: Intent): VirtualServiceStopDispatchResult? {
        val snapshot = packageSnapshot ?: return null
        val manager = VirtualServiceManager(hostPackageName = hostPackageName)
        val request = manager.resolveStopService(snapshot, intent) ?: return null
        return VirtualServiceDispatcher(
            hostContext = hostContext,
            packageRegistry = servicePackageRegistry,
            serviceRuntime = serviceRuntime
        ).dispatchStop(request)
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
                putExtra("multiapp.guestActivityLaunchMode", record.launchMode)
            }
            putExtra(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT, Intent(sourceIntent))
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

    companion object {
        fun defaultProxyActivityRegistry(hostPackageName: String): ProxyActivityRegistry {
            return ProxyActivityRegistry(
                ProxyActivitySlots.classNames(hostPackageName),
                ProxyActivitySlots.launchModeByClassName(hostPackageName)
            )
        }
    }
}
