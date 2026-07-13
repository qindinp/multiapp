package com.multiapp.core.engine

import android.content.Context
import android.content.Intent
import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.loader.VirtualActivityFinishResultRecord
import com.multiapp.core.loader.VirtualActivityIntentStore
import com.multiapp.core.loader.VirtualActivityLaunchRequest
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.loader.VirtualActivityOperations
import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.loader.VirtualContextWrapper
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord
import java.io.File

internal object EngineActivityRuntimeInternal

object EngineProxyActivitySlots {
    fun classNames(hostPackageName: String): List<String> =
        ProxyActivitySlots.classNames(hostPackageName)

    fun launchModeByClassName(hostPackageName: String): Map<String, String?> =
        ProxyActivitySlots.launchModeByClassName(hostPackageName)

    fun processSlotForClassName(hostPackageName: String, className: String): String? =
        ProxyActivitySlots.processNameForClassName(hostPackageName, className)

    fun normalizeLaunchMode(launchMode: String?): String? =
        ProxyActivityRegistry.normalizeLaunchMode(launchMode)
}

class EngineActivityLaunchCoordinator(
    private val hostContext: Context,
    private val processSlot: String?,
    private val slotAssignmentStore: ProxyActivitySlotAssignmentStore =
        FileBackedProxyActivitySlotAssignmentStore(
            File(hostContext.filesDir, ProxyActivitySlots.SLOT_ASSIGNMENT_FILE)
        ),
    private val activityRecordManager: VirtualActivityRecordManager =
        EngineHostedProcessRuntimeDefaults.activityRecordManager,
    private val proxyIntentFactory: (VirtualActivityManager, VirtualActivityRecord, Intent) -> Intent =
        { manager, record, sourceIntent -> manager.createProxyIntent(record, sourceIntent) }
) {
    fun remap(
        sourceIntent: Intent,
        plan: VirtualActivityDispatchPlan
    ): VirtualContextWrapper.StartActivityMappingResult {
        val target = plan.targets.singleOrNull()
            ?: return blocked(sourceIntent, "engine_activity_target_count:${plan.targets.size}")
        if (target.instanceId != plan.instanceId) {
            return blocked(sourceIntent, "engine_activity_instance_mismatch:${target.instanceId}")
        }
        if (!processSlot.isNullOrBlank() && target.processSlot != processSlot) {
            return blocked(
                sourceIntent,
                "engine_activity_process_slot_mismatch:expected=$processSlot,actual=${target.processSlot}"
            )
        }
        val managerSnapshot = activityRecordManager.snapshotState()
        val assignmentKey = target.assignmentKey()
        val previousAssignment = slotAssignmentStore.find(assignmentKey)
        return runCatching {
            val registry = ProxyActivityRegistry(
                ProxyActivitySlots.classNamesForProcessSlot(hostContext.packageName, target.processSlot),
                ProxyActivitySlots.launchModeByClassName(hostContext.packageName),
                slotAssignmentStore
            )
            val manager = VirtualActivityManager(
                context = hostContext,
                proxyActivityRegistry = registry,
                hostPackageName = hostContext.packageName,
                activityRecordManager = activityRecordManager
            )
            val record = manager.allocateGuestActivity(
                VirtualActivityLaunchRequest(
                    instanceId = target.instanceId,
                    originPackageName = target.originPackageName,
                    guestActivityClassName = target.activityClassName,
                    sourceIntent = sourceIntent,
                    reason = target.reason,
                    launchMode = target.launchMode,
                    taskAffinity = target.taskAffinity
                )
            )
            VirtualContextWrapper.StartActivityMappingResult.Remapped(
                sourceIntent = sourceIntent,
                proxyIntent = proxyIntentFactory(manager, record, sourceIntent)
            )
        }.getOrElse { error ->
            activityRecordManager.restoreState(managerSnapshot)
            restoreAssignment(assignmentKey, previousAssignment)
            blocked(
                sourceIntent,
                "engine_activity_allocation_failed:${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
    }

    fun remapBatch(
        entries: List<Pair<Intent, VirtualActivityDispatchPlan>>
    ): List<VirtualContextWrapper.StartActivityMappingResult> {
        if (entries.isEmpty()) return emptyList()
        val managerSnapshot = activityRecordManager.snapshotState()
        val assignmentSnapshots = linkedMapOf<ProxyActivitySlotKey, String?>()
        val results = mutableListOf<VirtualContextWrapper.StartActivityMappingResult>()
        entries.forEach { (intent, plan) ->
            plan.targets.singleOrNull()?.assignmentKey()?.let { key ->
                assignmentSnapshots.putIfAbsent(key, slotAssignmentStore.find(key))
            }
            val result = remap(intent, plan)
            if (result is VirtualContextWrapper.StartActivityMappingResult.Blocked) {
                activityRecordManager.restoreState(managerSnapshot)
                assignmentSnapshots.forEach { (key, previousAssignment) ->
                    restoreAssignment(key, previousAssignment)
                }
                return entries.map { (sourceIntent, _) ->
                    blocked(sourceIntent, "engine_activity_batch_rolled_back:${result.reason}")
                }
            }
            results += result
        }
        return results
    }

    private fun VirtualActivityDispatchTarget.assignmentKey(): ProxyActivitySlotKey {
        val taskKey = taskAffinity ?: "$originPackageName:$instanceId"
        return ProxyActivitySlotKey(
            instanceId = instanceId,
            launchMode = ProxyActivityRegistry.normalizeLaunchMode(launchMode),
            taskKey = taskKey
        )
    }

    private fun restoreAssignment(key: ProxyActivitySlotKey, previousAssignment: String?) {
        val currentAssignment = slotAssignmentStore.find(key)
        if (currentAssignment != previousAssignment) {
            slotAssignmentStore.compareAndSet(key, currentAssignment, previousAssignment)
        }
    }

    private fun blocked(
        sourceIntent: Intent,
        reason: String
    ): VirtualContextWrapper.StartActivityMappingResult.Blocked =
        VirtualContextWrapper.StartActivityMappingResult.Blocked(
            sourceIntent = sourceIntent,
            reason = reason
        )
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
    val taskAffinity: String?,
    val launchIdentity: EngineActivityLaunchIdentity? = null
)

class EngineActivityProxyLauncher private constructor(
    private val manager: VirtualActivityRecordManager
) {
    constructor() : this(EngineHostedProcessRuntimeDefaults.activityRecordManager)

    internal constructor(
        manager: VirtualActivityRecordManager,
        @Suppress("UNUSED_PARAMETER") marker: EngineActivityRuntimeInternal = EngineActivityRuntimeInternal
    ) : this(manager)

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
            taskAffinity = request.taskAffinity,
            engineLaunchIdentity = request.launchIdentity?.let { identity ->
                VirtualActivityLaunchIdentity(
                    capabilityToken = identity.capabilityToken,
                    instanceId = identity.instanceId,
                    runtimeEpoch = identity.runtimeEpoch,
                    engineSessionId = identity.engineSessionId,
                    processSlot = identity.processSlot,
                    proxyActivityClassName = identity.proxyActivityClassName,
                    guestActivityClassName = identity.guestActivityClassName
                )
            }
        )
    }
}

data class EngineProxyActivityObservation(
    val recordFound: Boolean,
    val recordRecovered: Boolean,
    val pendingNewIntent: VirtualActivityPendingNewIntent?,
    val result: VirtualActivityResult?,
    val taskId: Int = 0,
    val taskAffinity: String? = null,
    val launchMode: String? = null,
    val intentFlags: Int = 0
)

data class EngineProxyActivityObserveRequest(
    val proxyActivityClassName: String,
    val proxyIntent: Intent,
    val instanceId: String?,
    val token: String,
    val guestActivityClassName: String,
    val originPackageName: String
)

class EngineProxyActivityRecords private constructor(
    private val manager: VirtualActivityRecordManager
) {
    constructor() : this(EngineHostedProcessRuntimeDefaults.activityRecordManager)

    internal constructor(
        manager: VirtualActivityRecordManager,
        @Suppress("UNUSED_PARAMETER") marker: EngineActivityRuntimeInternal = EngineActivityRuntimeInternal
    ) : this(manager)

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
            result = observedRecord?.result,
            taskId = observedRecord?.taskId ?: 0,
            taskAffinity = observedRecord?.taskAffinity,
            launchMode = observedRecord?.launchMode,
            intentFlags = observedRecord?.intentFlags ?: 0
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
        val resultToToken = request.proxyIntent
            .getStringExtra(VirtualActivityManager.EXTRA_RESULT_TO_TOKEN)
            ?.takeIf { it.isNotBlank() }
        val record = VirtualActivityRecord(
            token = request.token,
            instanceId = request.instanceId,
            originPackageName = request.originPackageName,
            guestActivityClassName = request.guestActivityClassName,
            proxyActivityClassName = request.proxyActivityClassName,
            launchMode = launchMode,
            taskAffinity = taskAffinity,
            resultToToken = resultToToken,
            resultRequestCode = if (resultToToken == null) {
                -1
            } else {
                request.proxyIntent.getIntExtra(VirtualActivityManager.EXTRA_RESULT_REQUEST_CODE, -1)
            },
            state = VirtualActivityState.RESUMED
        )
        if (manager.conflictingProxyOwner(record) != null) {
            return null
        }
        return manager.registerLaunch(
            record = record,
            intentFlags = recoveredIntentFlags(request.proxyIntent)
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

    private fun recoveredIntentFlags(proxyIntent: Intent): Int =
        originalGuestIntent(proxyIntent)?.flags ?: runCatching { proxyIntent.flags }.getOrDefault(0)
}

class EngineActivityTaskRecords private constructor(
    private val manager: VirtualActivityRecordManager,
    private val stateStore: EngineActivityTaskStateStore
) {
    constructor() : this(
        EngineHostedProcessRuntimeDefaults.activityRecordManager,
        InMemoryEngineActivityTaskStateStore()
    )

    internal constructor(
        manager: VirtualActivityRecordManager,
        stateStore: EngineActivityTaskStateStore = InMemoryEngineActivityTaskStateStore(),
        @Suppress("UNUSED_PARAMETER") marker: EngineActivityRuntimeInternal = EngineActivityRuntimeInternal
    ) : this(manager, stateStore)

    fun snapshot(): EngineActivityTaskStateSnapshot =
        EngineActivityTaskStateSnapshot(manager.exportTasks())

    fun persist(instanceId: String? = null): EngineActivityTaskStateSnapshot {
        val snapshot = snapshot()
        return if (instanceId.isNullOrBlank()) {
            stateStore.save(snapshot)
            snapshot
        } else {
            stateStore.mergeInstance(instanceId, snapshot)
        }
    }

    fun restore(snapshot: EngineActivityTaskStateSnapshot): Int =
        manager.restoreTasks(snapshot.tasks)

    fun restore(tasks: List<VirtualTaskRecord>): Int =
        manager.restoreTasks(tasks)

    fun restorePersisted(): Int =
        restore(stateStore.load())

    fun restorePersistedIfEmpty(): Int {
        if (manager.exportTasks().any { it.activities.isNotEmpty() }) {
            return 0
        }
        return restorePersisted()
    }

    fun markState(
        token: String?,
        state: VirtualActivityState,
        persist: Boolean = true
    ): VirtualActivityRecord? {
        val updated = manager.updateState(token, state)
        if (persist) {
            stateStore.save(snapshot())
        }
        return updated
    }

    fun finish(
        token: String?,
        persist: Boolean = true
    ): VirtualActivityRecord? {
        val finished = manager.finish(token)
        if (persist) {
            stateStore.save(snapshot())
        }
        return finished
    }

    fun clearPersisted() {
        stateStore.clear()
    }
}

data class EngineActivityTaskControlResult(
    val status: String,
    val activityCount: Int,
    val taskCount: Int?,
    val detail: String = ""
)

class EngineActivityTaskController(
    private val activityService: VirtualActivityService,
    private val taskRecords: EngineActivityTaskRecords
) {
    fun restorePersisted(instanceId: String): EngineActivityTaskControlResult {
        requireValidInstanceId(instanceId)
        val restoredActivityCount = taskRecords.restorePersisted()
        return EngineActivityTaskControlResult(
            status = "RESTORED",
            activityCount = restoredActivityCount,
            taskCount = null
        )
    }

    fun restorePersistedIfEmpty(instanceId: String): EngineActivityTaskControlResult {
        requireValidInstanceId(instanceId)
        val restoredActivityCount = taskRecords.restorePersistedIfEmpty()
        return EngineActivityTaskControlResult(
            status = if (restoredActivityCount > 0) "RESTORED" else "SKIPPED_OR_EMPTY",
            activityCount = restoredActivityCount,
            taskCount = null
        )
    }

    fun persist(instanceId: String): EngineActivityTaskControlResult {
        requireValidInstanceId(instanceId)
        val snapshot = taskRecords.snapshot()
        val operation = activityService.syncActivityTaskState(
            instanceId = instanceId,
            reason = "activity-task-controller-persist",
            tasks = snapshot.tasks
        )
        return EngineActivityTaskControlResult(
            status = operation.toTaskControlStatus(),
            activityCount = snapshot.activityCount,
            taskCount = snapshot.tasks.size,
            detail = if (operation.verdict == EngineResultStatus.PASS) "" else operation.message
        )
    }

    fun markState(
        instanceId: String,
        token: String,
        state: VirtualActivityState
    ): EngineActivityTaskControlResult {
        requireValidInstanceId(instanceId)
        val operation = activityService.markActivityState(instanceId, token, state)
        val snapshot = taskRecords.snapshot()
        return EngineActivityTaskControlResult(
            status = operation.toTaskControlStatus(),
            activityCount = snapshot.activityCount,
            taskCount = snapshot.tasks.size,
            detail = if (operation.verdict == com.multiapp.core.model.engine.EngineResultStatus.PASS) {
                state.name
            } else {
                operation.message
            }
        )
    }

    fun finish(
        instanceId: String,
        token: String
    ): EngineActivityTaskControlResult {
        requireValidInstanceId(instanceId)
        val operation = activityService.finishActivity(instanceId, token)
        val snapshot = taskRecords.snapshot()
        return EngineActivityTaskControlResult(
            status = operation.toTaskControlStatus(),
            activityCount = snapshot.activityCount,
            taskCount = snapshot.tasks.size,
            detail = if (operation.verdict == com.multiapp.core.model.engine.EngineResultStatus.PASS) {
                VirtualActivityState.FINISHED.name
            } else {
                operation.message
            }
        )
    }

    private fun VirtualActivityOperationResult.toTaskControlStatus(): String =
        when {
            verdict == com.multiapp.core.model.engine.EngineResultStatus.PASS -> "PERSISTED"
            message.startsWith("activity_record_not_found:") -> "MISSING_RECORD"
            else -> "FAIL"
        }

    private fun requireValidInstanceId(instanceId: String) {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
    }
}

object EngineActivityTaskControllers {
    fun fileBacked(context: Context): EngineActivityTaskController =
        fileBacked(context.filesDir)

    fun fileBacked(filesDir: File): EngineActivityTaskController {
        val stateStore = FileBackedEngineActivityTaskStateStore(
            File(filesDir, EngineActivityTaskStateFiles.DEFAULT_FILE_NAME)
        )
        val registry = EngineRuntimeRegistry.global.attachStateStore(
            FileBackedEngineRuntimeStateStore(
                File(filesDir, EngineRuntimeStateFiles.DEFAULT_FILE_NAME)
            )
        )
        val activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
        val systemServer = DefaultVirtualSystemServer(
            registry = registry,
            activityTaskStateStore = stateStore,
            activityRecordManager = activityRecordManager
        )
        return EngineActivityTaskController(
            activityService = IpcBackedVirtualActivityService(
                fallback = systemServer.activityService,
                localTaskSnapshot = { activityRecordManager.exportTasks() }
            ),
            taskRecords = EngineActivityTaskRecords(
                manager = activityRecordManager,
                stateStore = stateStore
            )
        )
    }
}

class EngineVirtualActivityOperations(
    private val activityService: VirtualActivityService
) : VirtualActivityOperations {
    override fun consumePendingNewIntent(instanceId: String, token: String): VirtualActivityPendingNewIntent? =
        activityService.consumePendingNewIntent(instanceId, token)

    override fun recordActivityResultForFinish(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?
    ): VirtualActivityFinishResultRecord {
        val update = activityService.recordActivityResultForFinish(
            instanceId = instanceId,
            token = token,
            resultCode = resultCode,
            dataIntent = dataIntent
        )
        return VirtualActivityFinishResultRecord(
            instanceId = instanceId,
            sourceToken = update.token,
            requestCode = update.requestCode,
            resultCode = resultCode,
            dataIntent = dataIntent,
            recorded = update.verdict == EngineResultStatus.PASS,
            reason = if (update.verdict == EngineResultStatus.PASS) "" else update.message
        )
    }

    override fun setActivityResult(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?,
        requestCode: Int,
        resultWho: String?,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): Boolean =
        activityService.setActivityResult(
            instanceId = instanceId,
            token = token,
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = requestCode,
            resultWho = resultWho,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ).verdict == EngineResultStatus.PASS

    override fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult? =
        activityService.consumeActivityResult(instanceId, token)

    override fun consumeActivityResultForResumeFallback(instanceId: String, token: String): VirtualActivityResult? =
        activityService.consumeActivityResultForResumeFallback(instanceId, token)

    override fun markActivityResultDispatchState(
        instanceId: String,
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): Boolean =
        activityService.markActivityResultDispatchState(
            instanceId = instanceId,
            token = token,
            frameworkDispatchAttempted = frameworkDispatchAttempted,
            frameworkDispatchInvoked = frameworkDispatchInvoked
        ).verdict == EngineResultStatus.PASS

    override fun finishActivity(instanceId: String, token: String): Boolean =
        activityService.finishActivity(instanceId, token).verdict == EngineResultStatus.PASS
}

object EngineVirtualActivityOperationsFactory {
    fun hotPath(filesDir: File? = null): VirtualActivityOperations {
        val activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
        val registry = if (filesDir == null) {
            EngineRuntimeRegistry.global
        } else {
            EngineRuntimeRegistry.global.attachStateStore(
                FileBackedEngineRuntimeStateStore(
                    File(filesDir, EngineRuntimeStateFiles.DEFAULT_FILE_NAME)
                )
            )
        }
        val systemServer = DefaultVirtualSystemServer(
            registry = registry,
            activityTaskStateStore = filesDir?.let {
                FileBackedEngineActivityTaskStateStore(
                    File(it, EngineActivityTaskStateFiles.DEFAULT_FILE_NAME)
                )
            } ?: InMemoryEngineActivityTaskStateStore(),
            activityRecordManager = activityRecordManager
        )
        return EngineVirtualActivityOperations(
            activityService = IpcBackedVirtualActivityService(
                fallback = systemServer.activityService,
                localTaskSnapshot = { activityRecordManager.exportTasks() }
            )
        )
    }
}
