package com.multiapp.core.engine

import android.content.Context
import android.content.Intent
import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.loader.VirtualActivityIntentStore
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState

object EngineProxyActivitySlots {
    fun classNames(hostPackageName: String): List<String> =
        ProxyActivitySlots.classNames(hostPackageName)

    fun launchModeByClassName(hostPackageName: String): Map<String, String?> =
        ProxyActivitySlots.launchModeByClassName(hostPackageName)

    fun normalizeLaunchMode(launchMode: String?): String? =
        ProxyActivityRegistry.normalizeLaunchMode(launchMode)
}

data class EngineActivityLaunchRequest(
    val hostContext: Context,
    val candidateProxyActivityClassNames: List<String>,
    val proxyLaunchModeByClassName: Map<String, String?>,
    val slotAssignmentStore: ProxyActivitySlotAssignmentStore,
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val launchMode: String?,
    val taskAffinity: String?
)

class EngineActivityProxyLauncher(
    private val manager: VirtualActivityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
) {
    fun launchGuestLauncher(request: EngineActivityLaunchRequest): Result<VirtualActivityRecord> {
        val activityManager = VirtualActivityManager(
            context = request.hostContext,
            proxyActivityRegistry = ProxyActivityRegistry(
                request.candidateProxyActivityClassNames,
                request.proxyLaunchModeByClassName,
                request.slotAssignmentStore
            ),
            activityRecordManager = manager
        )
        return activityManager.launchGuestLauncher(
            instanceId = request.instanceId,
            originPackageName = request.originPackageName,
            guestActivityClassName = request.guestActivityClassName,
            launchMode = request.launchMode,
            taskAffinity = request.taskAffinity
        )
    }
}

data class EngineProxyActivityObservation(
    val recordFound: Boolean,
    val recordRecovered: Boolean,
    val pendingNewIntent: VirtualActivityPendingNewIntent?,
    val result: VirtualActivityResult?
)

data class EngineProxyActivityObserveRequest(
    val proxyActivityClassName: String,
    val proxyIntent: Intent,
    val instanceId: String?,
    val token: String,
    val guestActivityClassName: String,
    val originPackageName: String
)

class EngineProxyActivityRecords(
    private val manager: VirtualActivityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
) {
    fun observeProxyIntent(request: EngineProxyActivityObserveRequest): EngineProxyActivityObservation {
        val resolvedRecord = manager.resolve(request.token)
        if (resolvedRecord != null && !resolvedRecord.matchesOwner(request)) {
            return EngineProxyActivityObservation(
                recordFound = false,
                recordRecovered = false,
                pendingNewIntent = null,
                result = null
            )
        }
        val existingRecord = resolvedRecord
        val recoveredRecord = existingRecord ?: runCatching { recoverActivityRecord(request) }.getOrNull()
        val observedRecord = recoveredRecord ?: existingRecord
        return EngineProxyActivityObservation(
            recordFound = existingRecord != null,
            recordRecovered = existingRecord == null && recoveredRecord != null,
            pendingNewIntent = observedRecord?.pendingNewIntents?.firstOrNull(),
            result = observedRecord?.result
        )
    }

    fun pruneStaleProxyRecords(
        knownProxyActivityClassNames: Set<String>,
        liveProxyActivityClassNames: Set<String>
    ): Int = manager.pruneStaleProxyRecords(
        knownProxyActivityClassNames = knownProxyActivityClassNames,
        liveProxyActivityClassNames = liveProxyActivityClassNames
    )

    private fun recoverActivityRecord(request: EngineProxyActivityObserveRequest): VirtualActivityRecord? {
        if (
            request.instanceId.isNullOrBlank() ||
            request.token.isBlank() ||
            request.guestActivityClassName.isBlank() ||
            request.originPackageName.isBlank()
        ) {
            return null
        }
        val launchMode = request.proxyIntent
            .getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_LAUNCH_MODE)
            ?.takeIf { it.isNotBlank() }
        val taskAffinity = request.proxyIntent
            .getStringExtra(VirtualActivityManager.EXTRA_GUEST_TASK_AFFINITY)
            ?.takeIf { it.isNotBlank() }
        val record = VirtualActivityRecord(
            token = request.token,
            instanceId = request.instanceId,
            originPackageName = request.originPackageName,
            guestActivityClassName = request.guestActivityClassName,
            proxyActivityClassName = request.proxyActivityClassName,
            launchMode = launchMode,
            taskAffinity = taskAffinity,
            state = VirtualActivityState.RESUMED
        )
        if (manager.conflictingProxyOwner(record) != null) {
            return null
        }
        return manager.registerLaunch(
            record = record,
            intentFlags = originalGuestIntent(request.proxyIntent)?.flags ?: 0
        ).activity
    }

    private fun VirtualActivityRecord.matchesOwner(request: EngineProxyActivityObserveRequest): Boolean =
        instanceId == request.instanceId &&
            originPackageName == request.originPackageName &&
            guestActivityClassName == request.guestActivityClassName &&
            proxyActivityClassName == request.proxyActivityClassName

    @Suppress("DEPRECATION")
    private fun originalGuestIntent(proxyIntent: Intent): Intent? =
        VirtualActivityIntentStore.find(proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN))
            ?: runCatching {
                proxyIntent.getParcelableExtra<Intent>(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT)
            }.getOrNull()
}
