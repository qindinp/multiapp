package com.multiapp.core.engine

import android.content.Context
import android.content.Intent
import com.multiapp.core.loader.ProxyActivitySlots
import com.multiapp.core.loader.VirtualActivityLaunchAllocation
import com.multiapp.core.loader.VirtualActivityLaunchAllocationProvider
import com.multiapp.core.loader.VirtualActivityLaunchAllocationRequest
import com.multiapp.core.loader.VirtualActivityFinishResultRecord
import com.multiapp.core.loader.VirtualActivityIntentStore
import com.multiapp.core.loader.VirtualActivityLaunchRequest
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import com.multiapp.core.loader.VirtualActivityManager
import com.multiapp.core.loader.VirtualActivityOperations
import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.loader.VirtualContextWrapper
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.PreassignedProxyActivitySlotStore
import com.multiapp.core.model.virtual.ProxyActivityRegistry
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

    fun classNamesForProcessSlot(
        hostPackageName: String,
        processSlot: String,
        launchMode: String?
    ): List<String> {
        val normalizedMode = normalizeLaunchMode(launchMode)
        val modes = launchModeByClassName(hostPackageName)
        return ProxyActivitySlots.classNamesForProcessSlot(hostPackageName, processSlot)
            .filter { className -> normalizeLaunchMode(modes[className]) == normalizedMode }
    }

    fun normalizeLaunchMode(launchMode: String?): String? =
        ProxyActivityRegistry.normalizeLaunchMode(launchMode)
}

class EngineActivityLaunchCoordinator(
    private val hostContext: Context,
    private val processSlot: String?,
    private val activityRecordManager: VirtualActivityRecordManager =
        EngineHostedProcessRuntimeDefaults.activityRecordManager,
    private val allocationProvider: VirtualActivityLaunchAllocationProvider =
        IpcVirtualActivityLaunchAllocationProvider(),
    private val proxyIntentFactory: (VirtualActivityManager, VirtualActivityRecord, Intent) -> Intent =
        { manager, record, sourceIntent -> manager.createProxyIntent(record, sourceIntent) }
) {
    private val hostPackageName = hostContext.packageName

    init {
        require(hostPackageName.isNotBlank()) { "host Context packageName must not be blank" }
        processSlot?.let { slot ->
            require(slot.substringBefore(':') == hostPackageName) {
                "host Context packageName must own the configured process slot"
            }
        }
    }

    fun remap(
        sourceIntent: Intent,
        plan: VirtualActivityDispatchPlan
    ): VirtualContextWrapper.StartActivityMappingResult = remapInternal(
        sourceIntent = sourceIntent,
        plan = plan,
        releaseOnFailure = true
    )

    private fun remapInternal(
        sourceIntent: Intent,
        plan: VirtualActivityDispatchPlan,
        releaseOnFailure: Boolean
    ): VirtualContextWrapper.StartActivityMappingResult {
        val target = plan.targets.singleOrNull() ?: run {
            if (releaseOnFailure) {
                plan.targets.mapNotNull { it.toAuthoritativeAllocation() }
                    .asReversed()
                    .forEach(::release)
            }
            return blocked(sourceIntent, "engine_activity_target_count:${plan.targets.size}")
        }
        val allocation = target.toAuthoritativeAllocation()
            ?: return blocked(sourceIntent, "engine_activity_launch_allocation_missing")
        if (target.instanceId != plan.instanceId) {
            if (releaseOnFailure) release(allocation)
            return blocked(sourceIntent, "engine_activity_instance_mismatch:${target.instanceId}")
        }
        if (!processSlot.isNullOrBlank() && target.processSlot != processSlot) {
            if (releaseOnFailure) release(allocation)
            return blocked(
                sourceIntent,
                "engine_activity_process_slot_mismatch:expected=$processSlot,actual=${target.processSlot}"
            )
        }
        val identity = checkNotNull(allocation.launchIdentity)
        val proxyActivityClassName = checkNotNull(allocation.proxyActivityClassName)
        val allocatedProcessSlot = EngineProxyActivitySlots.processSlotForClassName(
            hostPackageName = hostPackageName,
            className = proxyActivityClassName
        )
        if (allocatedProcessSlot != target.processSlot) {
            if (releaseOnFailure) release(allocation)
            return blocked(
                sourceIntent,
                "engine_activity_proxy_process_slot_mismatch:" +
                    "expected=${target.processSlot},actual=${allocatedProcessSlot.orEmpty()}"
            )
        }
        val managerSnapshot = activityRecordManager.snapshotState()
        val assignmentKey = target.assignmentKey()
        return runCatching {
            val registry = ProxyActivityRegistry(
                listOf(proxyActivityClassName),
                ProxyActivitySlots.launchModeByClassName(hostPackageName),
                PreassignedProxyActivitySlotStore(assignmentKey, proxyActivityClassName)
            )
            val manager = VirtualActivityManager(
                context = hostContext,
                proxyActivityRegistry = registry,
                hostPackageName = hostPackageName,
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
                    .attachEngineLaunchIdentity(identity)
            )
        }.getOrElse { error ->
            activityRecordManager.restoreState(managerSnapshot)
            val released = releaseOnFailure && release(allocation)
            blocked(
                sourceIntent,
                "engine_activity_allocation_failed:${error.javaClass.name}:" +
                    "${error.message.orEmpty()}:allocationReleased=$released:" +
                    "allocationReleaseDeferred=${!releaseOnFailure}"
            )
        }
    }

    fun remapBatch(
        entries: List<Pair<Intent, VirtualActivityDispatchPlan>>
    ): List<VirtualContextWrapper.StartActivityMappingResult> {
        if (entries.isEmpty()) return emptyList()
        val managerSnapshot = activityRecordManager.snapshotState()
        val allocations = entries.flatMap { (_, plan) ->
            plan.targets.mapNotNull { it.toAuthoritativeAllocation() }
        }
        val hasExactlyOneAllocationPerEntry = entries.all { (_, plan) ->
            plan.targets.size == 1 && plan.targets.single().toAuthoritativeAllocation() != null
        }
        if (!hasExactlyOneAllocationPerEntry || allocations.size != entries.size) {
            allocations.asReversed().forEach(::release)
            return entries.map { (sourceIntent, _) ->
                blocked(sourceIntent, "engine_activity_batch_allocation_missing")
            }
        }
        val results = mutableListOf<VirtualContextWrapper.StartActivityMappingResult>()
        entries.forEach { (intent, plan) ->
            val result = remapInternal(intent, plan, releaseOnFailure = false)
            if (result is VirtualContextWrapper.StartActivityMappingResult.Blocked) {
                activityRecordManager.restoreState(managerSnapshot)
                allocations.asReversed().forEach(::release)
                return entries.map { (sourceIntent, _) ->
                    blocked(sourceIntent, "engine_activity_batch_rolled_back:${result.reason}")
                }
            }
            results += result
        }
        return results
    }

    private fun VirtualActivityDispatchTarget.toAuthoritativeAllocation(): VirtualActivityLaunchAllocation? {
        val proxyActivityClassName = proxyActivityClassName?.takeIf { it.isNotBlank() } ?: return null
        val capabilityToken = launchCapabilityToken?.takeIf { it.isNotBlank() } ?: return null
        val runtimeEpoch = runtimeEpoch?.takeIf { it > 0L } ?: return null
        val engineSessionId = engineSessionId?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val request = VirtualActivityLaunchAllocationRequest(
                instanceId = instanceId,
                originPackageName = originPackageName,
                guestActivityClassName = activityClassName,
                processSlot = processSlot,
                launchMode = launchMode,
                taskAffinity = taskAffinity
            )
            VirtualActivityLaunchAllocation(
                accepted = true,
                request = request,
                proxyActivityClassName = proxyActivityClassName,
                launchIdentity = VirtualActivityLaunchIdentity(
                    capabilityToken = capabilityToken,
                    instanceId = instanceId,
                    runtimeEpoch = runtimeEpoch,
                    engineSessionId = engineSessionId,
                    processSlot = processSlot,
                    proxyActivityClassName = proxyActivityClassName,
                    guestActivityClassName = activityClassName
                ),
                reason = "activity_allocation_authorized"
            )
        }.getOrNull()
    }

    private fun VirtualActivityDispatchTarget.assignmentKey(): ProxyActivitySlotKey {
        val taskKey = taskAffinity ?: "$originPackageName:$instanceId"
        return ProxyActivitySlotKey(
            instanceId = instanceId,
            launchMode = ProxyActivityRegistry.normalizeLaunchMode(launchMode),
            taskKey = taskKey
        )
    }

    private fun release(allocation: VirtualActivityLaunchAllocation): Boolean =
        runCatching { allocationProvider.release(allocation) }.getOrDefault(false)

    private fun Intent.attachEngineLaunchIdentity(identity: VirtualActivityLaunchIdentity): Intent = apply {
        putExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, identity.runtimeEpoch)
        putExtra(VirtualActivityManager.EXTRA_ENGINE_SESSION_ID, identity.engineSessionId)
        putExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT, identity.processSlot)
        putExtra(
            VirtualActivityManager.EXTRA_ENGINE_PROXY_ACTIVITY_CLASS_NAME,
            identity.proxyActivityClassName
        )
        putExtra(VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY, identity.capabilityToken)
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
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val launchMode: String?,
    val taskAffinity: String?,
    val launchIdentity: EngineActivityLaunchIdentity,
    val launchAction: String? = null
)

class EngineActivityProxyLauncher private constructor(
    private val manager: VirtualActivityRecordManager,
    private val prepare: (EngineActivityLaunchCommitRequest) -> EngineActivityLaunchCommitResult?
) {
    constructor() : this(
        manager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
        prepare = EngineRuntimeIpcClients::prepareActivityLaunch
    )

    internal constructor(
        manager: VirtualActivityRecordManager,
        prepare: (EngineActivityLaunchCommitRequest) -> EngineActivityLaunchCommitResult?,
        @Suppress("UNUSED_PARAMETER") marker: EngineActivityRuntimeInternal = EngineActivityRuntimeInternal
    ) : this(manager, prepare)

    fun launchGuestLauncher(request: EngineActivityLaunchRequest): Result<VirtualActivityRecord> {
        val identity = request.launchIdentity
        require(identity.instanceId == request.instanceId) { "engine launch instance mismatch" }
        require(identity.guestActivityClassName == request.guestActivityClassName) {
            "engine launch guest Activity mismatch"
        }
        require(identity.proxyActivityClassName in request.candidateProxyActivityClassNames) {
            "engine launch proxy was not preassigned"
        }
        val assignmentKey = ProxyActivitySlotKey(
            instanceId = request.instanceId,
            launchMode = EngineProxyActivitySlots.normalizeLaunchMode(request.launchMode),
            taskKey = request.taskAffinity ?: "${request.originPackageName}:${request.instanceId}"
        )
        val activityManager = VirtualActivityManager(
            context = request.hostContext,
            proxyActivityRegistry = ProxyActivityRegistry(
                listOf(identity.proxyActivityClassName),
                request.proxyLaunchModeByClassName,
                PreassignedProxyActivitySlotStore(assignmentKey, identity.proxyActivityClassName)
            ),
            activityRecordManager = manager
        )
        val sourceIntent = Intent().apply {
            action = request.launchAction
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val snapshot = manager.snapshotState()
        return runCatching {
            val record = activityManager.allocateGuestActivity(
                VirtualActivityLaunchRequest(
                    instanceId = request.instanceId,
                    originPackageName = request.originPackageName,
                    guestActivityClassName = request.guestActivityClassName,
                    sourceIntent = sourceIntent,
                    reason = "engine_foreground_launcher",
                    launchMode = request.launchMode,
                    taskAffinity = request.taskAffinity
                )
            )
            val prepared = prepare(
                EngineActivityLaunchCommitRequest(
                    identity = identity,
                    record = record,
                    intentFlags = sourceIntent.flags,
                    dataIntent = VirtualIntentSnapshot(
                        flags = sourceIntent.flags,
                        action = sourceIntent.action
                    )
                )
            )
            check(prepared?.accepted == true) {
                "engine_activity_launch_prepare_failed:${prepared?.reason.orEmpty()}"
            }
            val proxyIntent = activityManager.createProxyIntent(
                record = record,
                sourceIntent = sourceIntent,
                forceNewTask = true,
                engineLaunchIdentity = identity.toLoaderIdentity()
            ).putExtra(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT, Intent(sourceIntent))
            request.hostContext.startActivity(proxyIntent)
            prepared.activity ?: record
        }.onFailure {
            manager.restoreState(snapshot)
        }
    }
}

data class EngineProxyActivityObservation(
    val recordFound: Boolean,
    val recordRecovered: Boolean,
    val record: VirtualActivityRecord? = null,
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
                record = null,
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
            record = observedRecord,
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
        val state = activityService.queryTaskState(instanceId)
        if (state.verdict == EngineResultStatus.FAIL) {
            return EngineActivityTaskControlResult(
                status = "FAIL",
                activityCount = 0,
                taskCount = null,
                detail = state.message
            )
        }
        val restoredActivityCount = taskRecords.restore(state.tasks)
        return EngineActivityTaskControlResult(
            status = "RESTORED",
            activityCount = restoredActivityCount,
            taskCount = state.taskCount
        )
    }

    fun restorePersistedIfEmpty(instanceId: String): EngineActivityTaskControlResult {
        requireValidInstanceId(instanceId)
        if (taskRecords.snapshot().activityCount > 0) {
            return EngineActivityTaskControlResult(
                status = "SKIPPED_OR_EMPTY",
                activityCount = 0,
                taskCount = null
            )
        }
        val state = activityService.queryTaskState(instanceId)
        if (state.verdict == EngineResultStatus.FAIL) {
            return EngineActivityTaskControlResult(
                status = "FAIL",
                activityCount = 0,
                taskCount = null,
                detail = state.message
            )
        }
        val restoredActivityCount = taskRecords.restore(state.tasks)
        return EngineActivityTaskControlResult(
            status = if (restoredActivityCount > 0) "RESTORED" else "SKIPPED_OR_EMPTY",
            activityCount = restoredActivityCount,
            taskCount = state.taskCount
        )
    }

    fun persist(instanceId: String): EngineActivityTaskControlResult {
        requireValidInstanceId(instanceId)
        val state = activityService.queryTaskState(instanceId)
        return EngineActivityTaskControlResult(
            status = if (state.verdict == EngineResultStatus.FAIL) "FAIL" else "ENGINE_OWNED",
            activityCount = state.activityCount,
            taskCount = state.taskCount,
            detail = state.message
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
        @Suppress("UNUSED_VARIABLE")
        val ignored = filesDir
        val activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
        return EngineActivityTaskController(
            activityService = IpcBackedVirtualActivityService(
                localTaskSnapshot = { activityRecordManager.exportTasks() }
            ),
            taskRecords = EngineActivityTaskRecords(
                manager = activityRecordManager,
                stateStore = InMemoryEngineActivityTaskStateStore()
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
        @Suppress("UNUSED_VARIABLE")
        val ignored = filesDir
        val activityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
        return EngineVirtualActivityOperations(
            activityService = IpcBackedVirtualActivityService(
                localTaskSnapshot = { activityRecordManager.exportTasks() }
            )
        )
    }
}
