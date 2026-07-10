package com.multiapp.core.engine

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.multiapp.core.engine.ipc.IEngineRuntimeService
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualTaskRecord

object EngineRuntimeIpcContract {
    const val AUTHORITY_SUFFIX = ".multiapp.engine.server"
    const val METHOD_GET_BINDER = "getEngineRuntimeBinder"
    const val KEY_BINDER = "engineRuntimeBinder"
    const val KEY_FOUND = "found"
    const val KEY_STATUS = "status"
    const val KEY_REASON = "reason"
    const val KEY_INSTANCE_ID = "instanceId"
    const val KEY_PROCESS_SLOT = "processSlot"
    const val KEY_PROXY_SLOT = "proxySlot"
    const val KEY_RUNTIME_EPOCH = "runtimeEpoch"
    const val KEY_ENGINE_SESSION_ID = "engineSessionId"
    const val KEY_EVIDENCE_SESSION_ID = "evidenceSessionId"
    const val KEY_RUNTIME_STATE = "runtimeState"
    const val KEY_PROCESS_ID = "processId"
    const val KEY_PROCESS_NAME = "processName"
    const val KEY_COMPONENT = "component"
    const val KEY_OPERATION = "operation"
    const val KEY_VERDICT = "verdict"
    const val KEY_ENTRIES = "entries"
    const val KEY_OPERATION_COUNT = "operationCount"
    const val KEY_ACTION = "action"
    const val KEY_ACTIVITY_CLASS_NAME = "activityClassName"
    const val KEY_TARGET_PACKAGE_NAME = "targetPackageName"
    const val KEY_CATEGORIES = "categories"
    const val KEY_DATA_SCHEME = "dataScheme"
    const val KEY_DATA_MIME_TYPE = "dataMimeType"
    const val KEY_DATA_AUTHORITY = "dataAuthority"
    const val KEY_DATA_PATH = "dataPath"
    const val KEY_LAUNCH_FLAGS = "launchFlags"
    const val KEY_TARGETS = "targets"
    const val KEY_SUPPORTED_OPERATIONS = "supportedOperations"
    const val KEY_UNSUPPORTED_OPERATIONS = "unsupportedOperations"
    const val KEY_MESSAGE = "message"
    const val KEY_ORIGIN_PACKAGE_NAME = "originPackageName"
    const val KEY_VIRTUAL_PACKAGE_NAME = "virtualPackageName"
    const val KEY_LAUNCH_MODE = "launchMode"
    const val KEY_TASK_AFFINITY = "taskAffinity"
    const val KEY_PRIORITY = "priority"
    const val KEY_REMAPPED = "remapped"
    const val KEY_PROXY_ACTIVITY_CLASS_NAME = "proxyActivityClassName"
    const val KEY_PROVIDER_OPERATION = "providerOperation"
    const val KEY_GUEST_AUTHORITY = "guestAuthority"
    const val KEY_PROXY_AUTHORITY = "proxyAuthority"
    const val KEY_PROVIDER_CLASS_NAME = "providerClassName"
    const val KEY_PROVIDER_RECORDS = "providerRecords"
    const val KEY_PROVIDER_STATE = "providerState"
    const val KEY_LAST_PROVIDER_OPERATION = "lastProviderOperation"
    const val KEY_PROVIDER_OPERATION_COUNT = "providerOperationCount"
    const val KEY_VIRTUAL_AUTHORITY = "virtualAuthority"
    const val KEY_ROUTE_TOKEN_PRESENT = "routeTokenPresent"
    const val KEY_ROUTE_TOKEN_VERIFIED = "routeTokenVerified"
    const val KEY_CALLER_INSTANCE_ID = "callerInstanceId"
    const val KEY_TARGET_INSTANCE_ID = "targetInstanceId"
    const val KEY_CALLING_UID = "callingUid"
    const val KEY_CALLING_PID = "callingPid"
    const val KEY_HOST_UID = "hostUid"
    const val KEY_CALLER_PROCESS_SLOT = "callerProcessSlot"
    const val KEY_ACCESS_MODE = "accessMode"
    const val KEY_ENCODED_PATH = "encodedPath"
    const val KEY_MODE_FLAGS = "modeFlags"
    const val KEY_OWNER_INSTANCE_ID = "ownerInstanceId"
    const val KEY_GRANTED = "granted"
    const val KEY_AFFECTED_GRANT_COUNT = "affectedGrantCount"
    const val KEY_APP_OP_METHOD = "appOpMethod"
    const val KEY_APP_OP_CODE = "appOpCode"
    const val KEY_APP_OP_MODE = "appOpMode"
    const val KEY_EXPLICIT_MODE = "explicitMode"
    const val KEY_INTERCEPT = "intercept"
    const val KEY_BLOCK_SYSTEM_CALL = "blockSystemCall"
    const val KEY_URI_GRANT_PRESENT = "uriGrantPresent"
    const val KEY_ENGINE_CALLING_UID = "engineCallingUid"
    const val KEY_ENGINE_CALLING_PID = "engineCallingPid"
    const val KEY_EXPORTED = "exported"
    const val KEY_PERMISSION = "permission"
    const val KEY_READ_PERMISSION = "readPermission"
    const val KEY_WRITE_PERMISSION = "writePermission"
    const val KEY_GRANT_URI_PERMISSIONS = "grantUriPermissions"
    const val KEY_READY = "ready"
    const val KEY_CACHED = "cached"
    const val KEY_SERVICE_OPERATION = "serviceOperation"
    const val KEY_SERVICE_CLASS_NAME = "serviceClassName"
    const val KEY_REQUESTED_FOREGROUND_SERVICE_TYPES = "requestedForegroundServiceTypes"
    const val KEY_STICKY_RESTART_REQUESTED = "stickyRestartRequested"
    const val KEY_FOREGROUND = "foreground"
    const val KEY_STARTED = "started"
    const val KEY_STOPPED = "stopped"
    const val KEY_BOUND = "bound"
    const val KEY_UNBOUND = "unbound"
    const val KEY_START_COMMAND_RESULT = "startCommandResult"
    const val KEY_ACTIVE_START_COUNT = "activeStartCount"
    const val KEY_ACTIVE_BIND_COUNT = "activeBindCount"
    const val KEY_SERVICE_RECORDS = "serviceRecords"
    const val KEY_SERVICE_STATE = "serviceState"
    const val KEY_RECORD_COUNT = "recordCount"
    const val KEY_RECEIVER_CLASS_NAME = "receiverClassName"
    const val KEY_ORDERED = "ordered"
    const val KEY_STICKY = "sticky"
    const val KEY_EXPECTS_RESULT_RECEIVER = "expectsResultReceiver"
    const val KEY_ABORT_SUPPORTED_REQUESTED = "abortSupportedRequested"
    const val KEY_RECEIVER_PERMISSIONS = "receiverPermissions"
    const val KEY_RECEIVER_APP_OP = "receiverAppOp"
    const val KEY_AS_USER_REQUESTED = "asUserRequested"
    const val KEY_PLATFORM_OPTIONS_PRESENT = "platformOptionsPresent"
    const val KEY_DELIVERED = "delivered"
    const val KEY_BROADCAST_RECORDS = "broadcastRecords"
    const val KEY_BROADCAST_STATE = "broadcastState"
    const val KEY_LAST_BROADCAST_VERDICT = "lastBroadcastVerdict"
    const val KEY_DISPATCH_COUNT = "dispatchCount"
    const val KEY_DELIVERED_COUNT = "deliveredCount"
    const val KEY_BLOCKED_COUNT = "blockedCount"
    const val KEY_FAILURE_COUNT = "failureCount"
    const val KEY_TOKEN = "token"
    const val KEY_ACTIVITY_ID = "activityId"
    const val KEY_ACTIVITY_STATE = "activityState"
    const val KEY_ACTIVITY = "activity"
    const val KEY_ACTIVITY_RESULT = "activityResult"
    const val KEY_PENDING_NEW_INTENT = "pendingNewIntent"
    const val KEY_RESULT_CODE = "resultCode"
    const val KEY_REQUEST_CODE = "requestCode"
    const val KEY_RESULT_WHO = "resultWho"
    const val KEY_FRAMEWORK_DISPATCH_ATTEMPTED = "frameworkDispatchAttempted"
    const val KEY_FRAMEWORK_DISPATCH_INVOKED = "frameworkDispatchInvoked"
    const val KEY_DATA_INTENT = "dataIntent"
    const val KEY_EVENT_ID = "eventId"
    const val KEY_SOURCE_TOKEN = "sourceToken"
    const val KEY_INTENT_FLAGS = "intentFlags"
    const val KEY_CREATED_AT_MS = "createdAtMs"
    const val KEY_UPDATED_AT_MS = "updatedAtMs"
    const val KEY_DATA_URI = "dataUri"
    const val KEY_EXTRAS = "extras"
    const val KEY_GUEST_ACTIVITY_CLASS_NAME = "guestActivityClassName"
    const val KEY_TASK_ID = "taskId"
    const val KEY_RESULT_TO_TOKEN = "resultToToken"
    const val KEY_PENDING_NEW_INTENTS = "pendingNewIntents"
    const val KEY_TASKS = "tasks"
    const val KEY_TASK_COUNT = "taskCount"
    const val KEY_ACTIVITY_COUNT = "activityCount"
    const val KEY_TOP_TASK_ID = "topTaskId"
    const val KEY_TOP_ACTIVITY_CLASS_NAME = "topActivityClassName"
    const val KEY_TOP_ACTIVITY_STATE = "topActivityState"
    const val KEY_ACTIVITIES = "activities"

    fun authority(hostPackageName: String): String = hostPackageName + AUTHORITY_SUFFIX
}

data class EngineRuntimeIpcSnapshot(
    val found: Boolean,
    val instanceId: String,
    val processSlot: String?,
    val proxySlot: String?,
    val runtimeEpoch: Long,
    val engineSessionId: String?,
    val evidenceSessionId: String?,
    val runtimeState: String?,
    val processId: Int?,
    val processName: String?,
    val reason: String?
)

data class EngineRuntimeAuthorityDecision(
    val allowed: Boolean,
    val authorityAvailable: Boolean,
    val reason: String
)

object EngineRuntimeAuthorityValidator {
    fun validate(
        snapshot: EngineRuntimeIpcSnapshot?,
        expectedProcessSlot: String? = null
    ): EngineRuntimeAuthorityDecision = when {
        snapshot == null -> EngineRuntimeAuthorityDecision(
            allowed = true,
            authorityAvailable = false,
            reason = "ipc_unavailable_durable_fallback"
        )
        !snapshot.found -> EngineRuntimeAuthorityDecision(
            allowed = false,
            authorityAvailable = true,
            reason = snapshot.reason ?: "runtime_not_found"
        )
        snapshot.runtimeEpoch <= 0L -> EngineRuntimeAuthorityDecision(
            allowed = false,
            authorityAvailable = true,
            reason = "invalid_runtime_epoch"
        )
        snapshot.runtimeState == "STOPPED" || snapshot.runtimeState == "DEAD" ->
            EngineRuntimeAuthorityDecision(
                allowed = false,
                authorityAvailable = true,
                reason = "runtime_state_${snapshot.runtimeState.lowercase()}"
            )
        !expectedProcessSlot.isNullOrBlank() && snapshot.processSlot != expectedProcessSlot ->
            EngineRuntimeAuthorityDecision(
                allowed = false,
                authorityAvailable = true,
                reason = "runtime_process_slot_mismatch:expected=$expectedProcessSlot,actual=${snapshot.processSlot}"
            )
        else -> EngineRuntimeAuthorityDecision(
            allowed = true,
            authorityAvailable = true,
            reason = "authoritative_runtime_confirmed"
        )
    }
}

class EngineRuntimeBinderEndpoint(
    private val registry: EngineRuntimeRegistry,
    private val hostUid: Int,
    private val activityService: VirtualActivityService = DefaultVirtualSystemServer(registry).activityService,
    private val providerService: VirtualProviderService = DefaultVirtualSystemServer(registry).providerService,
    private val appOpsService: VirtualAppOpsService = DefaultVirtualSystemServer(registry).appOpsService,
    private val serviceService: VirtualServiceService = DefaultVirtualSystemServer(registry).serviceService,
    private val broadcastService: VirtualBroadcastService = DefaultVirtualSystemServer(registry).broadcastService,
    private val callingUid: () -> Int = Binder::getCallingUid,
    private val callingPid: () -> Int = Binder::getCallingPid
) : IEngineRuntimeService.Stub() {

    override fun queryRuntime(instanceId: String): Bundle = authorizedBundle {
        val runtime = instanceId.takeIf { it.isNotBlank() }?.let(registry::get)
            ?: return@authorizedBundle missingRuntimeBundle(instanceId)
        runtime.toIpcBundle()
    }

    override fun queryEvidence(instanceId: String): Bundle = authorizedBundle {
        val runtime = instanceId.takeIf { it.isNotBlank() }?.let(registry::get)
            ?: return@authorizedBundle missingRuntimeBundle(instanceId)
        val report = registry.evidence(runtime.instanceId)
        Bundle().apply {
            putBoolean(EngineRuntimeIpcContract.KEY_FOUND, true)
            putString(EngineRuntimeIpcContract.KEY_STATUS, report.status.name)
            putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, runtime.instanceId)
            putString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID, report.evidenceSessionId)
            putInt(EngineRuntimeIpcContract.KEY_OPERATION_COUNT, report.flattenedOperationEvidence().size)
            putBundle(EngineRuntimeIpcContract.KEY_ENTRIES, report.entries.toStringBundle())
        }
    }

    override fun planActivity(instanceId: String, request: Bundle): Bundle = authorizedBundle {
        val decodedRequest = request.toActivityPlanRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(instanceId, "invalid_activity_plan_request")
        activityService.planActivity(instanceId, decodedRequest).toIpcBundle()
    }

    override fun recordActivityDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isAuthorized()) return false
        val decodedResult = result.toActivityDispatchResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        return activityService.recordActivityDispatch(instanceId, decodedResult)
    }

    override fun mutateActivity(instanceId: String, operation: String, request: Bundle): Bundle =
        authorizedBundle {
            val decodedRequest = request.toActivityMutationRequestOrNull(operation)
                ?: return@authorizedBundle invalidRequestBundle(
                    instanceId,
                    "invalid_activity_mutation_request:$operation"
                )
            val result = when (decodedRequest.operation) {
                EngineActivityIpcOperation.MARK_STATE -> activityService.markActivityState(
                    instanceId = instanceId,
                    token = decodedRequest.token,
                    state = decodedRequest.state ?: return@authorizedBundle invalidRequestBundle(
                        instanceId,
                        "missing_activity_state"
                    )
                )
                EngineActivityIpcOperation.FINISH -> activityService.finishActivity(
                    instanceId = instanceId,
                    token = decodedRequest.token
                )
                EngineActivityIpcOperation.RECORD_FINISH_RESULT ->
                    activityService.recordActivityResultForFinish(
                        instanceId = instanceId,
                        token = decodedRequest.token,
                        resultCode = decodedRequest.resultCode,
                        dataIntent = decodedRequest.dataIntent
                    )
                EngineActivityIpcOperation.SET_RESULT -> activityService.setActivityResult(
                    instanceId = instanceId,
                    token = decodedRequest.token,
                    resultCode = decodedRequest.resultCode,
                    dataIntent = decodedRequest.dataIntent,
                    requestCode = decodedRequest.requestCode,
                    resultWho = decodedRequest.resultWho,
                    frameworkDispatchAttempted = decodedRequest.frameworkDispatchAttempted,
                    frameworkDispatchInvoked = decodedRequest.frameworkDispatchInvoked
                )
                EngineActivityIpcOperation.MARK_RESULT_DISPATCH ->
                    activityService.markActivityResultDispatchState(
                        instanceId = instanceId,
                        token = decodedRequest.token,
                        frameworkDispatchAttempted = decodedRequest.frameworkDispatchAttempted,
                        frameworkDispatchInvoked = decodedRequest.frameworkDispatchInvoked
                    )
                else -> return@authorizedBundle invalidRequestBundle(
                    instanceId,
                    "unsupported_activity_mutation:$operation"
                )
            }
            result.toIpcBundle()
        }

    override fun consumeActivity(instanceId: String, operation: String, request: Bundle): Bundle =
        authorizedBundle {
            val decodedOperation = EngineActivityIpcOperation.fromWireName(operation)
                ?: return@authorizedBundle invalidRequestBundle(
                    instanceId,
                    "invalid_activity_consume_operation:$operation"
                )
            val token = request.getString(EngineRuntimeIpcContract.KEY_TOKEN)
                ?.takeIf { it.isNotBlank() }
                ?: return@authorizedBundle invalidRequestBundle(
                    instanceId,
                    "invalid_activity_consume_token"
                )
            when (decodedOperation) {
                EngineActivityIpcOperation.CONSUME_RESULT -> EngineActivityIpcConsumeResponse(
                    operation = decodedOperation,
                    found = true,
                    activityResult = activityService.consumeActivityResult(instanceId, token)
                ).normalizeFound().toIpcBundle()
                EngineActivityIpcOperation.CONSUME_RESULT_RESUME_FALLBACK ->
                    EngineActivityIpcConsumeResponse(
                        operation = decodedOperation,
                        found = true,
                        activityResult = activityService.consumeActivityResultForResumeFallback(instanceId, token)
                    ).normalizeFound().toIpcBundle()
                EngineActivityIpcOperation.CONSUME_PENDING_NEW_INTENT ->
                    EngineActivityIpcConsumeResponse(
                        operation = decodedOperation,
                        found = true,
                        pendingNewIntent = activityService.consumePendingNewIntent(instanceId, token)
                    ).normalizeFound().toIpcBundle()
                else -> invalidRequestBundle(
                    instanceId,
                    "unsupported_activity_consume:$operation"
                )
            }
        }

    override fun queryActivityTaskState(instanceId: String): Bundle = authorizedBundle {
        activityService.queryTaskState(instanceId).toIpcBundle()
    }

    override fun syncActivityTaskState(
        instanceId: String,
        reason: String,
        snapshot: Bundle
    ): Bundle = authorizedBundle {
        val decoded = snapshot.toActivityTaskStateOrNull()
            ?.takeIf { it.instanceId == instanceId && reason.isNotBlank() }
            ?: return@authorizedBundle invalidRequestBundle(
                instanceId,
                "invalid_activity_task_sync_request"
            )
        activityService.syncActivityTaskState(
            instanceId = instanceId,
            reason = reason,
            tasks = decoded.tasks
        ).toIpcBundle()
    }

    override fun planProvider(instanceId: String, request: Bundle): Bundle = authorizedBundle {
        val decodedRequest = request.toProviderPlanRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(instanceId, "invalid_provider_plan_request")
        providerService.planProvider(
            instanceId,
            decodedRequest.copy(
                hostUid = hostUid,
                engineCallingUid = callingUid(),
                engineCallingPid = callingPid()
            )
        ).toIpcBundle()
    }

    override fun resolveProviderAuthority(callerInstanceId: String, request: Bundle): Bundle =
        authorizedBundle {
            val decodedRequest = request.toProviderAuthorityResolveRequestOrNull()
                ?: return@authorizedBundle invalidRequestBundle(
                    callerInstanceId,
                    "invalid_provider_authority_resolve_request"
                )
            providerService.resolveProviderAuthority(callerInstanceId, decodedRequest).toIpcBundle()
        }

    override fun recordProviderDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isAuthorized()) return false
        val decodedResult = result.toProviderOperationResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        return providerService.recordProviderDispatch(instanceId, decodedResult)
    }

    override fun queryProviderRuntimeState(instanceId: String): Bundle = authorizedBundle {
        providerService.queryProviderRuntimeState(instanceId).toIpcBundle()
    }

    override fun grantProviderUriPermission(ownerInstanceId: String, request: Bundle): Bundle =
        authorizedBundle {
            val decodedRequest = request.toProviderUriGrantRequestOrNull()
                ?: return@authorizedBundle invalidRequestBundle(
                    ownerInstanceId,
                    "invalid_provider_uri_grant_request"
                )
            providerService.grantUriPermission(
                ownerInstanceId,
                decodedRequest.withAuthoritativeCaller()
            ).toIpcBundle()
        }

    override fun revokeProviderUriPermission(ownerInstanceId: String, request: Bundle): Bundle =
        authorizedBundle {
            val decodedRequest = request.toProviderUriGrantRequestOrNull()
                ?: return@authorizedBundle invalidRequestBundle(
                    ownerInstanceId,
                    "invalid_provider_uri_revoke_request"
                )
            providerService.revokeUriPermission(
                ownerInstanceId,
                decodedRequest.withAuthoritativeCaller()
            ).toIpcBundle()
        }

    override fun checkProviderUriPermission(targetInstanceId: String, request: Bundle): Bundle =
        authorizedBundle {
            val decodedRequest = request.toProviderUriGrantRequestOrNull()
                ?: return@authorizedBundle invalidRequestBundle(
                    targetInstanceId,
                    "invalid_provider_uri_check_request"
                )
            providerService.checkUriPermission(
                targetInstanceId,
                decodedRequest.copy(hostUid = hostUid)
            ).toIpcBundle()
        }

    override fun queryAppOp(instanceId: String, request: Bundle): Bundle = authorizedBundle {
        val decodedRequest = request.toAppOpsQueryRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(instanceId, "invalid_app_ops_query_request")
        appOpsService.queryMode(
            instanceId,
            decodedRequest.copy(
                hostUid = hostUid,
                callingPid = callingPid()
            )
        ).toIpcBundle()
    }

    override fun planService(instanceId: String, request: Bundle): Bundle = authorizedBundle {
        val decodedRequest = request.toServicePlanRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(instanceId, "invalid_service_plan_request")
        serviceService.planService(instanceId, decodedRequest).toIpcBundle()
    }

    override fun recordServiceDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isAuthorized()) return false
        val decodedResult = result.toServiceOperationResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        return serviceService.recordServiceDispatch(instanceId, decodedResult)
    }

    override fun queryServiceRuntimeState(instanceId: String): Bundle = authorizedBundle {
        serviceService.queryServiceRuntimeState(instanceId).toIpcBundle()
    }

    override fun planBroadcast(instanceId: String, request: Bundle): Bundle = authorizedBundle {
        val decodedRequest = request.toBroadcastPlanRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(instanceId, "invalid_broadcast_plan_request")
        broadcastService.planBroadcast(instanceId, decodedRequest).toIpcBundle()
    }

    override fun recordBroadcastDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isAuthorized()) return false
        val decodedResult = result.toBroadcastOperationResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        return broadcastService.recordBroadcastDispatch(instanceId, decodedResult)
    }

    override fun queryBroadcastRuntimeState(instanceId: String): Bundle = authorizedBundle {
        broadcastService.queryBroadcastRuntimeState(instanceId).toIpcBundle()
    }

    override fun recordOperationEvidence(instanceId: String, evidence: Bundle): Boolean {
        if (!isAuthorized()) return false
        val component = evidence.getString(EngineRuntimeIpcContract.KEY_COMPONENT)?.takeIf { it.isNotBlank() }
            ?: return false
        val operation = evidence.getString(EngineRuntimeIpcContract.KEY_OPERATION)?.takeIf { it.isNotBlank() }
            ?: return false
        val verdict = evidence.getString(EngineRuntimeIpcContract.KEY_VERDICT)
            ?.let { runCatching { EngineResultStatus.valueOf(it) }.getOrNull() }
            ?: return false
        val entries = evidence.getBundle(EngineRuntimeIpcContract.KEY_ENTRIES).toStringMap()
        return registry.registerOperationEvidence(
            instanceId = instanceId,
            evidence = EngineOperationEvidence(
                component = component,
                operation = operation,
                verdict = verdict,
                entries = entries
            )
        )
    }

    override fun stopRuntime(instanceId: String, runtimeEpoch: Long): Boolean =
        isAuthorized() && instanceId.isNotBlank() && runtimeEpoch > 0L &&
            registry.stopIfEpoch(instanceId, runtimeEpoch)

    private fun authorizedBundle(block: () -> Bundle): Bundle =
        if (isAuthorized()) block() else Bundle().apply {
            putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
            putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
            putString(EngineRuntimeIpcContract.KEY_REASON, "caller_uid_mismatch")
        }

    private fun isAuthorized(): Boolean = callingUid() == hostUid

    private fun VirtualProviderUriGrantRequest.withAuthoritativeCaller(): VirtualProviderUriGrantRequest = copy(
        callingUid = callingUid(),
        callingPid = callingPid(),
        hostUid = hostUid
    )

    private fun missingRuntimeBundle(instanceId: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_REASON, "runtime_not_found")
    }

    private fun invalidRequestBundle(instanceId: String, reason: String): Bundle = Bundle().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_VERDICT, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_MESSAGE, reason)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun VirtualInstanceRuntime.toIpcBundle(): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, true)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.PASS.name)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
        putString(EngineRuntimeIpcContract.KEY_PROXY_SLOT, proxySlot)
        putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
        putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
        putString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID, evidenceSessionId)
        putString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE, state.name)
        processId?.let { putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, it) }
        putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
    }
}

class EngineRuntimeIpcClient(
    private val context: Context
) {
    fun connect(): IEngineRuntimeService? {
        val hostContext = context.applicationContext ?: context
        val authority = EngineRuntimeIpcContract.authority(hostContext.packageName)
        val response = runCatching {
            hostContext.contentResolver.call(
                Uri.Builder().scheme("content").authority(authority).build(),
                EngineRuntimeIpcContract.METHOD_GET_BINDER,
                null,
                null
            )
        }.getOrNull()
        return IEngineRuntimeService.Stub.asInterface(
            response?.getBinder(EngineRuntimeIpcContract.KEY_BINDER)
        )
    }
}

object EngineRuntimeIpcClients {
    @Volatile
    private var service: IEngineRuntimeService? = null
    @Volatile
    private var applicationContext: Context? = null
    private val deathRecipient = android.os.IBinder.DeathRecipient {
        synchronized(this) {
            service = null
        }
    }

    fun install(context: Context): Boolean {
        applicationContext = context.applicationContext ?: context
        return reconnect() != null
    }

    fun isConnected(): Boolean = activeService() != null

    fun queryRuntime(instanceId: String): EngineRuntimeIpcSnapshot? {
        val response = runCatching { activeService()?.queryRuntime(instanceId) }.getOrNull() ?: return null
        return EngineRuntimeIpcSnapshot(
            found = response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND),
            instanceId = response.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
            processSlot = response.getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
            proxySlot = response.getString(EngineRuntimeIpcContract.KEY_PROXY_SLOT),
            runtimeEpoch = response.getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
            engineSessionId = response.getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID),
            evidenceSessionId = response.getString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID),
            runtimeState = response.getString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE),
            processId = if (response.containsKey(EngineRuntimeIpcContract.KEY_PROCESS_ID)) {
                response.getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID)
            } else {
                null
            },
            processName = response.getString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
            reason = response.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
    }

    fun planActivity(
        instanceId: String,
        request: VirtualActivityDispatchPlanRequest
    ): VirtualActivityDispatchPlan? {
        val response = runCatching {
            activeService()?.planActivity(instanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toActivityDispatchPlanOrNull()
    }

    fun recordActivityDispatch(instanceId: String, result: VirtualActivityDispatchResult): Boolean? {
        val activeService = activeService() ?: return null
        return runCatching {
            activeService.recordActivityDispatch(instanceId, result.toIpcBundle())
        }.getOrNull()
    }

    fun mutateActivity(
        instanceId: String,
        request: EngineActivityIpcMutationRequest
    ): VirtualActivityOperationResult? {
        val response = runCatching {
            activeService()?.mutateActivity(
                instanceId,
                request.operation.wireName,
                request.toIpcBundle()
            )
        }.getOrNull() ?: return null
        return response.toActivityOperationResultOrNull()
    }

    fun consumeActivity(
        instanceId: String,
        operation: EngineActivityIpcOperation,
        token: String
    ): EngineActivityIpcConsumeResponse? {
        val response = runCatching {
            activeService()?.consumeActivity(
                instanceId,
                operation.wireName,
                Bundle().apply { putString(EngineRuntimeIpcContract.KEY_TOKEN, token) }
            )
        }.getOrNull() ?: return null
        return response.toActivityConsumeResponseOrNull(operation)
    }

    fun queryActivityTaskState(instanceId: String): VirtualActivityTaskState? {
        val response = runCatching {
            activeService()?.queryActivityTaskState(instanceId)
        }.getOrNull() ?: return null
        return response.toActivityTaskStateOrNull()
    }

    fun syncActivityTaskState(
        instanceId: String,
        reason: String,
        tasks: List<VirtualTaskRecord>
    ): VirtualActivityOperationResult? {
        val snapshot = VirtualActivityTaskState(
            instanceId = instanceId,
            verdict = EngineResultStatus.PARTIAL,
            taskCount = tasks.size,
            activityCount = tasks.sumOf { it.activities.size },
            tasks = tasks,
            message = "activity_task_sync_request"
        )
        val response = runCatching {
            activeService()?.syncActivityTaskState(instanceId, reason, snapshot.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toActivityOperationResultOrNull()
    }

    fun planProvider(
        instanceId: String,
        request: VirtualProviderDispatchPlanRequest
    ): VirtualProviderDispatchPlan? {
        val response = runCatching {
            activeService()?.planProvider(instanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toProviderDispatchPlanOrNull()
    }

    fun resolveProviderAuthority(
        callerInstanceId: String,
        request: VirtualProviderAuthorityResolveRequest
    ): VirtualProviderAuthorityResolveResult? {
        val response = runCatching {
            activeService()?.resolveProviderAuthority(callerInstanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toProviderAuthorityResolveResultOrNull()
    }

    fun recordProviderDispatch(instanceId: String, result: VirtualProviderOperationResult): Boolean? {
        val activeService = activeService() ?: return null
        return runCatching {
            activeService.recordProviderDispatch(instanceId, result.toIpcBundle())
        }.getOrNull()
    }

    fun queryProviderRuntimeState(instanceId: String): VirtualProviderRuntimeState? {
        val response = runCatching {
            activeService()?.queryProviderRuntimeState(instanceId)
        }.getOrNull() ?: return null
        return response.toProviderRuntimeStateOrNull()
    }

    fun grantProviderUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        val response = runCatching {
            activeService()?.grantProviderUriPermission(ownerInstanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toProviderUriGrantResultOrNull()
    }

    fun revokeProviderUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        val response = runCatching {
            activeService()?.revokeProviderUriPermission(ownerInstanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toProviderUriGrantResultOrNull()
    }

    fun checkProviderUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        val response = runCatching {
            activeService()?.checkProviderUriPermission(targetInstanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toProviderUriGrantResultOrNull()
    }

    fun queryAppOp(
        instanceId: String,
        request: VirtualAppOpsQueryRequest
    ): VirtualAppOpsQueryResult? {
        val response = runCatching {
            activeService()?.queryAppOp(instanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toAppOpsQueryResultOrNull()
    }

    fun planService(
        instanceId: String,
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceDispatchPlan? {
        val response = runCatching {
            activeService()?.planService(instanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toServiceDispatchPlanOrNull()
    }

    fun recordServiceDispatch(instanceId: String, result: VirtualServiceOperationResult): Boolean? {
        val activeService = activeService() ?: return null
        return runCatching {
            activeService.recordServiceDispatch(instanceId, result.toIpcBundle())
        }.getOrNull()
    }

    fun queryServiceRuntimeState(instanceId: String): VirtualServiceRuntimeState? {
        val response = runCatching {
            activeService()?.queryServiceRuntimeState(instanceId)
        }.getOrNull() ?: return null
        return response.toServiceRuntimeStateOrNull()
    }

    fun planBroadcast(
        instanceId: String,
        request: VirtualBroadcastDispatchPlanRequest
    ): VirtualBroadcastDispatchPlan? {
        val response = runCatching {
            activeService()?.planBroadcast(instanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toBroadcastDispatchPlanOrNull()
    }

    fun recordBroadcastDispatch(instanceId: String, result: VirtualBroadcastOperationResult): Boolean? {
        val activeService = activeService() ?: return null
        return runCatching {
            activeService.recordBroadcastDispatch(instanceId, result.toIpcBundle())
        }.getOrNull()
    }

    fun queryBroadcastRuntimeState(instanceId: String): VirtualBroadcastRuntimeState? {
        val response = runCatching {
            activeService()?.queryBroadcastRuntimeState(instanceId)
        }.getOrNull() ?: return null
        return response.toBroadcastRuntimeStateOrNull()
    }

    fun evidenceSink(): EngineOperationEvidenceSink = IpcEngineOperationEvidenceSink(::activeService)

    private fun activeService(): IEngineRuntimeService? {
        val current = service
        if (current != null && current.asBinder().isBinderAlive) return current
        return reconnect()
    }

    @Synchronized
    private fun reconnect(): IEngineRuntimeService? {
        val current = service
        if (current != null && current.asBinder().isBinderAlive) return current
        current?.asBinder()?.let { binder ->
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        val context = applicationContext ?: return null
        val connected = EngineRuntimeIpcClient(context).connect() ?: run {
            service = null
            return null
        }
        val linked = runCatching {
            connected.asBinder().linkToDeath(deathRecipient, 0)
            true
        }.getOrDefault(false)
        if (!linked || !connected.asBinder().isBinderAlive) {
            service = null
            return null
        }
        service = connected
        return connected
    }
}

class IpcEngineOperationEvidenceSink(
    private val serviceProvider: () -> IEngineRuntimeService?
) : EngineOperationEvidenceSink {
    override fun record(instanceId: String, evidence: EngineOperationEvidence): EngineOperationEvidenceRecordResult {
        val accepted = runCatching {
            serviceProvider()?.recordOperationEvidence(instanceId, evidence.toIpcBundle()) == true
        }.getOrDefault(false)
        return EngineOperationEvidenceRecordResult(accepted = accepted, report = null)
    }
}

private fun EngineOperationEvidence.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_COMPONENT, component)
    putString(EngineRuntimeIpcContract.KEY_OPERATION, operation)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putBundle(EngineRuntimeIpcContract.KEY_ENTRIES, entries.toStringBundle())
}

private fun VirtualActivityDispatchPlanRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, activityClassName)
    putString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME, targetPackageName)
    putStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES, ArrayList(categories.sorted()))
    putString(EngineRuntimeIpcContract.KEY_DATA_SCHEME, dataScheme)
    putString(EngineRuntimeIpcContract.KEY_DATA_MIME_TYPE, dataMimeType)
    putString(EngineRuntimeIpcContract.KEY_DATA_AUTHORITY, dataAuthority)
    putString(EngineRuntimeIpcContract.KEY_DATA_PATH, dataPath)
    putInt(EngineRuntimeIpcContract.KEY_LAUNCH_FLAGS, launchFlags)
}

private fun Bundle.toActivityPlanRequestOrNull(): VirtualActivityDispatchPlanRequest? = runCatching {
    VirtualActivityDispatchPlanRequest(
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        activityClassName = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME),
        targetPackageName = getString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME),
        categories = getStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES).orEmpty().toSet(),
        dataScheme = getString(EngineRuntimeIpcContract.KEY_DATA_SCHEME),
        dataMimeType = getString(EngineRuntimeIpcContract.KEY_DATA_MIME_TYPE),
        dataAuthority = getString(EngineRuntimeIpcContract.KEY_DATA_AUTHORITY),
        dataPath = getString(EngineRuntimeIpcContract.KEY_DATA_PATH),
        launchFlags = getInt(EngineRuntimeIpcContract.KEY_LAUNCH_FLAGS)
    )
}.getOrNull()

private fun VirtualActivityDispatchPlan.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_TARGETS,
        ArrayList(targets.map { it.toIpcBundle() })
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS,
        ArrayList(supportedOperations.sorted())
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS,
        ArrayList(unsupportedOperations.sorted())
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun VirtualActivityDispatchTarget.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME, virtualPackageName)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, activityClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
    putString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE, launchMode)
    putString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY, taskAffinity)
    putInt(EngineRuntimeIpcContract.KEY_PRIORITY, priority)
}

private fun Bundle.toActivityDispatchPlanOrNull(): VirtualActivityDispatchPlan? = runCatching {
    VirtualActivityDispatchPlan(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        targets = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_TARGETS)
            .orEmpty()
            .mapNotNull { it.toActivityDispatchTargetOrNull() },
        supportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        unsupportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun Bundle.toActivityDispatchTargetOrNull(): VirtualActivityDispatchTarget? = runCatching {
    VirtualActivityDispatchTarget(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        originPackageName = getString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME).orEmpty(),
        virtualPackageName = getString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME).orEmpty(),
        activityClassName = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME).orEmpty(),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        processName = getString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
        launchMode = getString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE),
        taskAffinity = getString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY),
        priority = getInt(EngineRuntimeIpcContract.KEY_PRIORITY)
    )
}.getOrNull()

private fun VirtualActivityDispatchResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, activityClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putBoolean(EngineRuntimeIpcContract.KEY_REMAPPED, remapped)
    putString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME, proxyActivityClassName)
    putInt(EngineRuntimeIpcContract.KEY_LAUNCH_FLAGS, launchFlags)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toActivityDispatchResultOrNull(): VirtualActivityDispatchResult? = runCatching {
    VirtualActivityDispatchResult(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        activityClassName = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        remapped = getBoolean(EngineRuntimeIpcContract.KEY_REMAPPED),
        proxyActivityClassName = getString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME),
        launchFlags = getInt(EngineRuntimeIpcContract.KEY_LAUNCH_FLAGS),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun EngineActivityIpcMutationRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_TOKEN, token)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_STATE, state?.name)
    putInt(EngineRuntimeIpcContract.KEY_RESULT_CODE, resultCode)
    putBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT, dataIntent?.toIpcBundle())
    putInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, requestCode)
    putString(EngineRuntimeIpcContract.KEY_RESULT_WHO, resultWho)
    putBoolean(EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_ATTEMPTED, frameworkDispatchAttempted)
    putBoolean(EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_INVOKED, frameworkDispatchInvoked)
}

private fun Bundle.toActivityMutationRequestOrNull(operation: String): EngineActivityIpcMutationRequest? =
    runCatching {
        val decodedOperation = EngineActivityIpcOperation.fromWireName(operation)
            ?: return@runCatching null
        if (decodedOperation !in setOf(
                EngineActivityIpcOperation.MARK_STATE,
                EngineActivityIpcOperation.FINISH,
                EngineActivityIpcOperation.RECORD_FINISH_RESULT,
                EngineActivityIpcOperation.SET_RESULT,
                EngineActivityIpcOperation.MARK_RESULT_DISPATCH
            )
        ) {
            return@runCatching null
        }
        val token = getString(EngineRuntimeIpcContract.KEY_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val state = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_STATE)?.let(VirtualActivityState::valueOf)
        if (decodedOperation == EngineActivityIpcOperation.MARK_STATE && state == null) {
            return@runCatching null
        }
        if (decodedOperation in setOf(
                EngineActivityIpcOperation.SET_RESULT,
                EngineActivityIpcOperation.RECORD_FINISH_RESULT
            ) &&
            (!containsKey(EngineRuntimeIpcContract.KEY_RESULT_CODE) ||
                (decodedOperation == EngineActivityIpcOperation.SET_RESULT &&
                    !containsKey(EngineRuntimeIpcContract.KEY_REQUEST_CODE)))
        ) {
            return@runCatching null
        }
        EngineActivityIpcMutationRequest(
            operation = decodedOperation,
            token = token,
            state = state,
            resultCode = getInt(EngineRuntimeIpcContract.KEY_RESULT_CODE),
            dataIntent = getBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT)?.toVirtualIntentSnapshotOrNull(),
            requestCode = getInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, -1),
            resultWho = getString(EngineRuntimeIpcContract.KEY_RESULT_WHO),
            frameworkDispatchAttempted = getBoolean(
                EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_ATTEMPTED
            ),
            frameworkDispatchInvoked = getBoolean(
                EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_INVOKED
            )
        )
    }.getOrNull()

private fun VirtualActivityOperationResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_OPERATION, operation)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_TOKEN, token)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_ID, activityId)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, activityClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_STATE, state?.name)
    putBundle(EngineRuntimeIpcContract.KEY_ACTIVITY, activity?.toIpcBundle())
    putInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, requestCode)
    resultCode?.let { putInt(EngineRuntimeIpcContract.KEY_RESULT_CODE, it) }
    putBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT, dataIntent?.toIpcBundle())
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toActivityOperationResultOrNull(): VirtualActivityOperationResult? = runCatching {
    VirtualActivityOperationResult(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        operation = getString(EngineRuntimeIpcContract.KEY_OPERATION).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        token = getString(EngineRuntimeIpcContract.KEY_TOKEN),
        activityId = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_ID),
        activityClassName = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME),
        state = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_STATE)?.let(VirtualActivityState::valueOf),
        activity = getBundle(EngineRuntimeIpcContract.KEY_ACTIVITY)?.toVirtualActivityRecordOrNull(),
        requestCode = getInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, -1),
        resultCode = if (containsKey(EngineRuntimeIpcContract.KEY_RESULT_CODE)) {
            getInt(EngineRuntimeIpcContract.KEY_RESULT_CODE)
        } else {
            null
        },
        dataIntent = getBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT)?.toVirtualIntentSnapshotOrNull(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualActivityTaskState.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putInt(EngineRuntimeIpcContract.KEY_TASK_COUNT, taskCount)
    putInt(EngineRuntimeIpcContract.KEY_ACTIVITY_COUNT, activityCount)
    topTaskId?.let { putInt(EngineRuntimeIpcContract.KEY_TOP_TASK_ID, it) }
    putString(EngineRuntimeIpcContract.KEY_TOP_ACTIVITY_CLASS_NAME, topActivityClassName)
    putString(EngineRuntimeIpcContract.KEY_TOP_ACTIVITY_STATE, topActivityState?.name)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_TASKS,
        ArrayList(tasks.map { it.toIpcBundle() })
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS,
        ArrayList(supportedOperations.sorted())
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS,
        ArrayList(unsupportedOperations.sorted())
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toActivityTaskStateOrNull(): VirtualActivityTaskState? = runCatching {
    val tasks = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_TASKS)
        .orEmpty()
        .mapNotNull { it.toVirtualTaskRecordOrNull() }
    val taskCount = getInt(EngineRuntimeIpcContract.KEY_TASK_COUNT)
    val activityCount = getInt(EngineRuntimeIpcContract.KEY_ACTIVITY_COUNT)
    if (taskCount != tasks.size || activityCount != tasks.sumOf { it.activities.size }) {
        return@runCatching null
    }
    VirtualActivityTaskState(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        taskCount = taskCount,
        activityCount = activityCount,
        topTaskId = if (containsKey(EngineRuntimeIpcContract.KEY_TOP_TASK_ID)) {
            getInt(EngineRuntimeIpcContract.KEY_TOP_TASK_ID)
        } else {
            null
        },
        topActivityClassName = getString(EngineRuntimeIpcContract.KEY_TOP_ACTIVITY_CLASS_NAME),
        topActivityState = getString(EngineRuntimeIpcContract.KEY_TOP_ACTIVITY_STATE)
            ?.let(VirtualActivityState::valueOf),
        tasks = tasks,
        supportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        unsupportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualTaskRecord.toIpcBundle(): Bundle = Bundle().apply {
    putInt(EngineRuntimeIpcContract.KEY_TASK_ID, taskId)
    putString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY, affinity)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_ACTIVITIES,
        ArrayList(activities.map { it.toIpcBundle() })
    )
    putLong(EngineRuntimeIpcContract.KEY_CREATED_AT_MS, createdAtMs)
}

private fun Bundle.toVirtualTaskRecordOrNull(): VirtualTaskRecord? = runCatching {
    VirtualTaskRecord(
        taskId = getInt(EngineRuntimeIpcContract.KEY_TASK_ID),
        affinity = getString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY).orEmpty(),
        activities = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_ACTIVITIES)
            .orEmpty()
            .mapNotNull { it.toVirtualActivityRecordOrNull() },
        createdAtMs = getLong(EngineRuntimeIpcContract.KEY_CREATED_AT_MS)
    )
}.getOrNull()

private fun EngineActivityIpcConsumeResponse.normalizeFound(): EngineActivityIpcConsumeResponse = copy(
    found = when (operation) {
        EngineActivityIpcOperation.CONSUME_RESULT,
        EngineActivityIpcOperation.CONSUME_RESULT_RESUME_FALLBACK -> activityResult != null
        EngineActivityIpcOperation.CONSUME_PENDING_NEW_INTENT -> pendingNewIntent != null
        else -> false
    }
)

private fun EngineActivityIpcConsumeResponse.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.PASS.name)
    putString(EngineRuntimeIpcContract.KEY_OPERATION, operation.wireName)
    putBoolean(EngineRuntimeIpcContract.KEY_FOUND, found)
    putBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_RESULT, activityResult?.toIpcBundle())
    putBundle(EngineRuntimeIpcContract.KEY_PENDING_NEW_INTENT, pendingNewIntent?.toIpcBundle())
}

private fun Bundle.toActivityConsumeResponseOrNull(
    expectedOperation: EngineActivityIpcOperation
): EngineActivityIpcConsumeResponse? = runCatching {
    if (getString(EngineRuntimeIpcContract.KEY_STATUS) != EngineResultStatus.PASS.name) {
        return@runCatching null
    }
    val operation = EngineActivityIpcOperation.fromWireName(
        getString(EngineRuntimeIpcContract.KEY_OPERATION)
    )?.takeIf { it == expectedOperation } ?: return@runCatching null
    val found = getBoolean(EngineRuntimeIpcContract.KEY_FOUND)
    val activityResult = getBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_RESULT)
        ?.toVirtualActivityResultOrNull()
    val pendingNewIntent = getBundle(EngineRuntimeIpcContract.KEY_PENDING_NEW_INTENT)
        ?.toVirtualActivityPendingNewIntentOrNull()
    if (found && when (operation) {
            EngineActivityIpcOperation.CONSUME_RESULT,
            EngineActivityIpcOperation.CONSUME_RESULT_RESUME_FALLBACK -> activityResult == null
            EngineActivityIpcOperation.CONSUME_PENDING_NEW_INTENT -> pendingNewIntent == null
            else -> true
        }
    ) {
        return@runCatching null
    }
    EngineActivityIpcConsumeResponse(
        operation = operation,
        found = found,
        activityResult = activityResult,
        pendingNewIntent = pendingNewIntent
    )
}.getOrNull()

private fun VirtualActivityRecord.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_TOKEN, token)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_ID, activityId)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_GUEST_ACTIVITY_CLASS_NAME, guestActivityClassName)
    putString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME, proxyActivityClassName)
    putString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE, launchMode)
    putLong(EngineRuntimeIpcContract.KEY_CREATED_AT_MS, createdAtMs)
    putInt(EngineRuntimeIpcContract.KEY_TASK_ID, taskId)
    putInt(EngineRuntimeIpcContract.KEY_INTENT_FLAGS, intentFlags)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_STATE, state.name)
    putString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY, taskAffinity)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_PENDING_NEW_INTENTS,
        ArrayList(pendingNewIntents.map { it.toIpcBundle() })
    )
    putString(EngineRuntimeIpcContract.KEY_RESULT_TO_TOKEN, resultToToken)
    putInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, resultRequestCode)
    putBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_RESULT, result?.toIpcBundle())
}

private fun Bundle.toVirtualActivityRecordOrNull(): VirtualActivityRecord? = runCatching {
    VirtualActivityRecord(
        token = getString(EngineRuntimeIpcContract.KEY_TOKEN).orEmpty(),
        activityId = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_ID).orEmpty(),
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        originPackageName = getString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME).orEmpty(),
        guestActivityClassName = getString(
            EngineRuntimeIpcContract.KEY_GUEST_ACTIVITY_CLASS_NAME
        ).orEmpty(),
        proxyActivityClassName = getString(
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME
        ).orEmpty(),
        launchMode = getString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE),
        createdAtMs = getLong(EngineRuntimeIpcContract.KEY_CREATED_AT_MS),
        taskId = getInt(EngineRuntimeIpcContract.KEY_TASK_ID),
        intentFlags = getInt(EngineRuntimeIpcContract.KEY_INTENT_FLAGS),
        state = VirtualActivityState.valueOf(
            getString(EngineRuntimeIpcContract.KEY_ACTIVITY_STATE).orEmpty()
        ),
        taskAffinity = getString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY),
        pendingNewIntents = getParcelableArrayList<Bundle>(
            EngineRuntimeIpcContract.KEY_PENDING_NEW_INTENTS
        ).orEmpty().mapNotNull { it.toVirtualActivityPendingNewIntentOrNull() },
        resultToToken = getString(EngineRuntimeIpcContract.KEY_RESULT_TO_TOKEN),
        resultRequestCode = getInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, -1),
        result = getBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_RESULT)?.toVirtualActivityResultOrNull()
    )
}.getOrNull()

private fun VirtualActivityResult.toIpcBundle(): Bundle = Bundle().apply {
    putInt(EngineRuntimeIpcContract.KEY_RESULT_CODE, resultCode)
    putBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT, dataIntent?.toIpcBundle())
    putInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, requestCode)
    putString(EngineRuntimeIpcContract.KEY_RESULT_WHO, resultWho)
    putBoolean(EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_ATTEMPTED, frameworkDispatchAttempted)
    putBoolean(EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_INVOKED, frameworkDispatchInvoked)
    putLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS, updatedAtMs)
}

private fun Bundle.toVirtualActivityResultOrNull(): VirtualActivityResult? = runCatching {
    VirtualActivityResult(
        resultCode = getInt(EngineRuntimeIpcContract.KEY_RESULT_CODE),
        dataIntent = getBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT)?.toVirtualIntentSnapshotOrNull(),
        requestCode = getInt(EngineRuntimeIpcContract.KEY_REQUEST_CODE, -1),
        resultWho = getString(EngineRuntimeIpcContract.KEY_RESULT_WHO),
        frameworkDispatchAttempted = getBoolean(
            EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_ATTEMPTED
        ),
        frameworkDispatchInvoked = getBoolean(
            EngineRuntimeIpcContract.KEY_FRAMEWORK_DISPATCH_INVOKED
        ),
        updatedAtMs = getLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS)
    )
}.getOrNull()

private fun VirtualActivityPendingNewIntent.toIpcBundle(): Bundle = Bundle().apply {
    putLong(EngineRuntimeIpcContract.KEY_EVENT_ID, eventId)
    putString(EngineRuntimeIpcContract.KEY_SOURCE_TOKEN, sourceToken)
    putInt(EngineRuntimeIpcContract.KEY_INTENT_FLAGS, intentFlags)
    putBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT, dataIntent?.toIpcBundle())
    putLong(EngineRuntimeIpcContract.KEY_CREATED_AT_MS, createdAtMs)
}

private fun Bundle.toVirtualActivityPendingNewIntentOrNull(): VirtualActivityPendingNewIntent? = runCatching {
    VirtualActivityPendingNewIntent(
        eventId = getLong(EngineRuntimeIpcContract.KEY_EVENT_ID),
        sourceToken = getString(EngineRuntimeIpcContract.KEY_SOURCE_TOKEN).orEmpty(),
        intentFlags = getInt(EngineRuntimeIpcContract.KEY_INTENT_FLAGS),
        dataIntent = getBundle(EngineRuntimeIpcContract.KEY_DATA_INTENT)?.toVirtualIntentSnapshotOrNull(),
        createdAtMs = getLong(EngineRuntimeIpcContract.KEY_CREATED_AT_MS)
    )
}.getOrNull()

private fun VirtualIntentSnapshot.toIpcBundle(): Bundle = Bundle().apply {
    putInt(EngineRuntimeIpcContract.KEY_INTENT_FLAGS, flags)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_DATA_URI, dataUri)
    putStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES, ArrayList(categories.sorted()))
    putBundle(EngineRuntimeIpcContract.KEY_EXTRAS, extras.toStringBundle())
}

private fun Bundle.toVirtualIntentSnapshotOrNull(): VirtualIntentSnapshot? = runCatching {
    VirtualIntentSnapshot(
        flags = getInt(EngineRuntimeIpcContract.KEY_INTENT_FLAGS),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        dataUri = getString(EngineRuntimeIpcContract.KEY_DATA_URI),
        categories = getStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES).orEmpty().toSet(),
        extras = getBundle(EngineRuntimeIpcContract.KEY_EXTRAS).toStringMap()
    )
}.getOrNull()

private fun VirtualProviderDispatchPlanRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROXY_AUTHORITY, proxyAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putBoolean(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN_PRESENT, routeTokenPresent)
    putBoolean(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN_VERIFIED, routeTokenVerified)
    putString(EngineRuntimeIpcContract.KEY_CALLER_INSTANCE_ID, callerInstanceId)
    putString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID, targetInstanceId)
    putInt(EngineRuntimeIpcContract.KEY_CALLING_UID, callingUid)
    putInt(EngineRuntimeIpcContract.KEY_CALLING_PID, callingPid)
    putInt(EngineRuntimeIpcContract.KEY_HOST_UID, hostUid)
    putString(EngineRuntimeIpcContract.KEY_CALLER_PROCESS_SLOT, callerProcessSlot)
    putString(EngineRuntimeIpcContract.KEY_ACCESS_MODE, accessMode)
    putString(EngineRuntimeIpcContract.KEY_ENCODED_PATH, encodedPath)
    putBoolean(EngineRuntimeIpcContract.KEY_URI_GRANT_PRESENT, uriGrantPresent)
}

private fun VirtualProviderAuthorityResolveRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_ENCODED_PATH, encodedPath)
    putString(EngineRuntimeIpcContract.KEY_ACCESS_MODE, accessMode)
}

private fun Bundle.toProviderAuthorityResolveRequestOrNull(): VirtualProviderAuthorityResolveRequest? = runCatching {
    VirtualProviderAuthorityResolveRequest(
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        operation = EngineProviderOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION).orEmpty()
        ),
        encodedPath = normalizeProviderGrantPath(
            getString(EngineRuntimeIpcContract.KEY_ENCODED_PATH)
        ),
        accessMode = getString(EngineRuntimeIpcContract.KEY_ACCESS_MODE)
    )
}.getOrNull()

private fun VirtualProviderAuthorityResolveResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_CALLER_INSTANCE_ID, callerInstanceId)
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putBoolean(EngineRuntimeIpcContract.KEY_VIRTUAL_AUTHORITY, virtualAuthority)
    putString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID, targetInstanceId)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toProviderAuthorityResolveResultOrNull(): VirtualProviderAuthorityResolveResult? = runCatching {
    VirtualProviderAuthorityResolveResult(
        callerInstanceId = getString(EngineRuntimeIpcContract.KEY_CALLER_INSTANCE_ID).orEmpty(),
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        virtualAuthority = getBoolean(EngineRuntimeIpcContract.KEY_VIRTUAL_AUTHORITY),
        targetInstanceId = getString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun Bundle.toProviderPlanRequestOrNull(): VirtualProviderDispatchPlanRequest? = runCatching {
    VirtualProviderDispatchPlanRequest(
        operation = EngineProviderOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION).orEmpty()
        ),
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        proxyAuthority = getString(EngineRuntimeIpcContract.KEY_PROXY_AUTHORITY),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
        routeTokenPresent = getBoolean(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN_PRESENT),
        routeTokenVerified = getBoolean(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN_VERIFIED),
        callerInstanceId = getString(EngineRuntimeIpcContract.KEY_CALLER_INSTANCE_ID),
        targetInstanceId = getString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID),
        callingUid = getInt(EngineRuntimeIpcContract.KEY_CALLING_UID, -1),
        callingPid = getInt(EngineRuntimeIpcContract.KEY_CALLING_PID, -1),
        hostUid = getInt(EngineRuntimeIpcContract.KEY_HOST_UID, -1),
        callerProcessSlot = getString(EngineRuntimeIpcContract.KEY_CALLER_PROCESS_SLOT),
        accessMode = getString(EngineRuntimeIpcContract.KEY_ACCESS_MODE),
        encodedPath = normalizeProviderGrantPath(
            getString(EngineRuntimeIpcContract.KEY_ENCODED_PATH)
        ),
        uriGrantPresent = getBoolean(EngineRuntimeIpcContract.KEY_URI_GRANT_PRESENT)
    )
}.getOrNull()

private fun VirtualProviderUriGrantRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_ENCODED_PATH, encodedPath)
    putInt(EngineRuntimeIpcContract.KEY_MODE_FLAGS, modeFlags)
    putString(EngineRuntimeIpcContract.KEY_OWNER_INSTANCE_ID, ownerInstanceId)
    putString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID, targetInstanceId)
    putString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME, targetPackageName)
    putInt(EngineRuntimeIpcContract.KEY_CALLING_UID, callingUid)
    putInt(EngineRuntimeIpcContract.KEY_CALLING_PID, callingPid)
    putInt(EngineRuntimeIpcContract.KEY_HOST_UID, hostUid)
}

private fun Bundle.toProviderUriGrantRequestOrNull(): VirtualProviderUriGrantRequest? = runCatching {
    VirtualProviderUriGrantRequest(
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        encodedPath = normalizeProviderGrantPath(
            getString(EngineRuntimeIpcContract.KEY_ENCODED_PATH)
        ),
        modeFlags = getInt(EngineRuntimeIpcContract.KEY_MODE_FLAGS),
        ownerInstanceId = getString(EngineRuntimeIpcContract.KEY_OWNER_INSTANCE_ID),
        targetInstanceId = getString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID),
        targetPackageName = getString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME),
        callingUid = getInt(EngineRuntimeIpcContract.KEY_CALLING_UID, -1),
        callingPid = getInt(EngineRuntimeIpcContract.KEY_CALLING_PID, -1),
        hostUid = getInt(EngineRuntimeIpcContract.KEY_HOST_UID, -1)
    )
}.getOrNull()

private fun VirtualProviderUriGrantResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_OWNER_INSTANCE_ID, ownerInstanceId)
    putString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID, targetInstanceId)
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_ENCODED_PATH, encodedPath)
    putInt(EngineRuntimeIpcContract.KEY_MODE_FLAGS, modeFlags)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putBoolean(EngineRuntimeIpcContract.KEY_GRANTED, granted)
    putInt(EngineRuntimeIpcContract.KEY_AFFECTED_GRANT_COUNT, affectedGrantCount)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toProviderUriGrantResultOrNull(): VirtualProviderUriGrantResult? = runCatching {
    VirtualProviderUriGrantResult(
        ownerInstanceId = getString(EngineRuntimeIpcContract.KEY_OWNER_INSTANCE_ID),
        targetInstanceId = getString(EngineRuntimeIpcContract.KEY_TARGET_INSTANCE_ID),
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        encodedPath = normalizeProviderGrantPath(
            getString(EngineRuntimeIpcContract.KEY_ENCODED_PATH)
        ),
        modeFlags = getInt(EngineRuntimeIpcContract.KEY_MODE_FLAGS),
        verdict = EngineResultStatus.valueOf(
            getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()
        ),
        granted = getBoolean(EngineRuntimeIpcContract.KEY_GRANTED),
        affectedGrantCount = getInt(EngineRuntimeIpcContract.KEY_AFFECTED_GRANT_COUNT),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualAppOpsQueryRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_APP_OP_METHOD, methodName)
    opCode?.let { putInt(EngineRuntimeIpcContract.KEY_APP_OP_CODE, it) }
    putInt(EngineRuntimeIpcContract.KEY_CALLING_UID, uid)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, packageName)
    putInt(EngineRuntimeIpcContract.KEY_HOST_UID, hostUid)
    putInt(EngineRuntimeIpcContract.KEY_CALLING_PID, callingPid)
}

private fun Bundle.toAppOpsQueryRequestOrNull(): VirtualAppOpsQueryRequest? = runCatching {
    VirtualAppOpsQueryRequest(
        methodName = getString(EngineRuntimeIpcContract.KEY_APP_OP_METHOD).orEmpty(),
        opCode = if (containsKey(EngineRuntimeIpcContract.KEY_APP_OP_CODE)) {
            getInt(EngineRuntimeIpcContract.KEY_APP_OP_CODE)
        } else {
            null
        },
        uid = getInt(EngineRuntimeIpcContract.KEY_CALLING_UID, -1),
        packageName = getString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME),
        hostUid = getInt(EngineRuntimeIpcContract.KEY_HOST_UID, -1),
        callingPid = getInt(EngineRuntimeIpcContract.KEY_CALLING_PID, -1)
    )
}.getOrNull()

private fun VirtualAppOpsQueryResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    mode?.let { putInt(EngineRuntimeIpcContract.KEY_APP_OP_MODE, it) }
    putBoolean(EngineRuntimeIpcContract.KEY_EXPLICIT_MODE, explicitMode)
    putBoolean(EngineRuntimeIpcContract.KEY_INTERCEPT, intercept)
    putBoolean(EngineRuntimeIpcContract.KEY_BLOCK_SYSTEM_CALL, blockSystemCall)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toAppOpsQueryResultOrNull(): VirtualAppOpsQueryResult? = runCatching {
    VirtualAppOpsQueryResult(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        mode = if (containsKey(EngineRuntimeIpcContract.KEY_APP_OP_MODE)) {
            getInt(EngineRuntimeIpcContract.KEY_APP_OP_MODE)
        } else {
            null
        },
        explicitMode = getBoolean(EngineRuntimeIpcContract.KEY_EXPLICIT_MODE),
        intercept = getBoolean(EngineRuntimeIpcContract.KEY_INTERCEPT),
        blockSystemCall = getBoolean(EngineRuntimeIpcContract.KEY_BLOCK_SYSTEM_CALL),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualProviderDispatchPlan.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_TARGETS,
        ArrayList(targets.map { it.toIpcBundle() })
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS,
        ArrayList(supportedOperations.sorted())
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS,
        ArrayList(unsupportedOperations.sorted())
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun VirtualProviderDispatchTarget.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME, virtualPackageName)
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROXY_AUTHORITY, proxyAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_CLASS_NAME, providerClassName)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
    putBoolean(EngineRuntimeIpcContract.KEY_EXPORTED, exported)
    putString(EngineRuntimeIpcContract.KEY_PERMISSION, permission)
    putString(EngineRuntimeIpcContract.KEY_READ_PERMISSION, readPermission)
    putString(EngineRuntimeIpcContract.KEY_WRITE_PERMISSION, writePermission)
    putBoolean(EngineRuntimeIpcContract.KEY_GRANT_URI_PERMISSIONS, grantUriPermissions)
}

private fun Bundle.toProviderDispatchPlanOrNull(): VirtualProviderDispatchPlan? = runCatching {
    VirtualProviderDispatchPlan(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        operation = EngineProviderOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION).orEmpty()
        ),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        targets = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_TARGETS)
            .orEmpty()
            .mapNotNull { it.toProviderDispatchTargetOrNull() },
        supportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        unsupportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun Bundle.toProviderDispatchTargetOrNull(): VirtualProviderDispatchTarget? = runCatching {
    VirtualProviderDispatchTarget(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        originPackageName = getString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME).orEmpty(),
        virtualPackageName = getString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME).orEmpty(),
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        proxyAuthority = getString(EngineRuntimeIpcContract.KEY_PROXY_AUTHORITY),
        providerClassName = getString(EngineRuntimeIpcContract.KEY_PROVIDER_CLASS_NAME).orEmpty(),
        operation = EngineProviderOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION).orEmpty()
        ),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        processName = getString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
        exported = getBoolean(EngineRuntimeIpcContract.KEY_EXPORTED),
        permission = getString(EngineRuntimeIpcContract.KEY_PERMISSION),
        readPermission = getString(EngineRuntimeIpcContract.KEY_READ_PERMISSION),
        writePermission = getString(EngineRuntimeIpcContract.KEY_WRITE_PERMISSION),
        grantUriPermissions = getBoolean(EngineRuntimeIpcContract.KEY_GRANT_URI_PERMISSIONS)
    )
}.getOrNull()

private fun VirtualProviderOperationResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROXY_AUTHORITY, proxyAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_CLASS_NAME, providerClassName)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putBoolean(EngineRuntimeIpcContract.KEY_READY, ready)
    putBoolean(EngineRuntimeIpcContract.KEY_CACHED, cached)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toProviderOperationResultOrNull(): VirtualProviderOperationResult? = runCatching {
    VirtualProviderOperationResult(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        operation = EngineProviderOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION).orEmpty()
        ),
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY),
        proxyAuthority = getString(EngineRuntimeIpcContract.KEY_PROXY_AUTHORITY),
        providerClassName = getString(EngineRuntimeIpcContract.KEY_PROVIDER_CLASS_NAME),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        ready = getBoolean(EngineRuntimeIpcContract.KEY_READY),
        cached = getBoolean(EngineRuntimeIpcContract.KEY_CACHED),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualProviderRuntimeState.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putInt(EngineRuntimeIpcContract.KEY_RECORD_COUNT, records.size)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_PROVIDER_RECORDS,
        ArrayList(records.map { it.toIpcBundle() })
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toProviderRuntimeStateOrNull(): VirtualProviderRuntimeState? = runCatching {
    val records = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_PROVIDER_RECORDS)
        .orEmpty()
        .mapNotNull { it.toEngineProviderRuntimeRecordOrNull() }
    if (getInt(EngineRuntimeIpcContract.KEY_RECORD_COUNT) != records.size) return@runCatching null
    VirtualProviderRuntimeState(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        records = records,
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun EngineProviderRuntimeRecord.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY, guestAuthority)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_CLASS_NAME, providerClassName)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_PROVIDER_STATE, state.name)
    putBoolean(EngineRuntimeIpcContract.KEY_CACHED, cached)
    putString(EngineRuntimeIpcContract.KEY_LAST_PROVIDER_OPERATION, lastOperation.name)
    putLong(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION_COUNT, operationCount)
    putLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS, updatedAtMs)
}

private fun Bundle.toEngineProviderRuntimeRecordOrNull(): EngineProviderRuntimeRecord? = runCatching {
    EngineProviderRuntimeRecord(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        guestAuthority = getString(EngineRuntimeIpcContract.KEY_GUEST_AUTHORITY).orEmpty(),
        providerClassName = getString(EngineRuntimeIpcContract.KEY_PROVIDER_CLASS_NAME).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
        state = EngineProviderLifecycleState.valueOf(
            getString(EngineRuntimeIpcContract.KEY_PROVIDER_STATE).orEmpty()
        ),
        cached = getBoolean(EngineRuntimeIpcContract.KEY_CACHED),
        lastOperation = EngineProviderOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_LAST_PROVIDER_OPERATION).orEmpty()
        ),
        operationCount = getLong(EngineRuntimeIpcContract.KEY_PROVIDER_OPERATION_COUNT),
        updatedAtMs = getLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS)
    )
}.getOrNull()

private fun VirtualServiceDispatchPlanRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME, serviceClassName)
    putString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME, targetPackageName)
    putStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES, ArrayList(categories.sorted()))
    putString(EngineRuntimeIpcContract.KEY_DATA_SCHEME, dataScheme)
    putString(EngineRuntimeIpcContract.KEY_DATA_MIME_TYPE, dataMimeType)
    putString(EngineRuntimeIpcContract.KEY_DATA_AUTHORITY, dataAuthority)
    putString(EngineRuntimeIpcContract.KEY_DATA_PATH, dataPath)
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_REQUESTED_FOREGROUND_SERVICE_TYPES,
        ArrayList(requestedForegroundServiceTypes.sorted())
    )
    putBoolean(EngineRuntimeIpcContract.KEY_STICKY_RESTART_REQUESTED, stickyRestartRequested)
}

private fun Bundle.toServicePlanRequestOrNull(): VirtualServiceDispatchPlanRequest? = runCatching {
    VirtualServiceDispatchPlanRequest(
        operation = VirtualServiceOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION).orEmpty()
        ),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        serviceClassName = getString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME),
        targetPackageName = getString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME),
        categories = getStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES).orEmpty().toSet(),
        dataScheme = getString(EngineRuntimeIpcContract.KEY_DATA_SCHEME),
        dataMimeType = getString(EngineRuntimeIpcContract.KEY_DATA_MIME_TYPE),
        dataAuthority = getString(EngineRuntimeIpcContract.KEY_DATA_AUTHORITY),
        dataPath = getString(EngineRuntimeIpcContract.KEY_DATA_PATH),
        requestedForegroundServiceTypes = getStringArrayList(
            EngineRuntimeIpcContract.KEY_REQUESTED_FOREGROUND_SERVICE_TYPES
        ).orEmpty().toSet(),
        stickyRestartRequested = getBoolean(EngineRuntimeIpcContract.KEY_STICKY_RESTART_REQUESTED)
    )
}.getOrNull()

private fun VirtualServiceDispatchPlan.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_TARGETS,
        ArrayList(targets.map { it.toIpcBundle() })
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS,
        ArrayList(supportedOperations.sorted())
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS,
        ArrayList(unsupportedOperations.sorted())
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun VirtualServiceDispatchTarget.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME, virtualPackageName)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME, serviceClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
    putBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND, foreground)
    putInt(EngineRuntimeIpcContract.KEY_PRIORITY, priority)
}

private fun Bundle.toServiceDispatchPlanOrNull(): VirtualServiceDispatchPlan? = runCatching {
    VirtualServiceDispatchPlan(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        operation = VirtualServiceOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION).orEmpty()
        ),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        targets = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_TARGETS)
            .orEmpty()
            .mapNotNull { it.toServiceDispatchTargetOrNull() },
        supportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        unsupportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun Bundle.toServiceDispatchTargetOrNull(): VirtualServiceDispatchTarget? = runCatching {
    VirtualServiceDispatchTarget(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        originPackageName = getString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME).orEmpty(),
        virtualPackageName = getString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME).orEmpty(),
        serviceClassName = getString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME).orEmpty(),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        operation = VirtualServiceOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION).orEmpty()
        ),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        processName = getString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
        foreground = getBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND),
        priority = getInt(EngineRuntimeIpcContract.KEY_PRIORITY)
    )
}.getOrNull()

private fun VirtualServiceOperationResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, operation.name)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME, serviceClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putBoolean(EngineRuntimeIpcContract.KEY_STARTED, started)
    putBoolean(EngineRuntimeIpcContract.KEY_STOPPED, stopped)
    putBoolean(EngineRuntimeIpcContract.KEY_BOUND, bound)
    putBoolean(EngineRuntimeIpcContract.KEY_UNBOUND, unbound)
    putBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND, foreground)
    startCommandResult?.let { putInt(EngineRuntimeIpcContract.KEY_START_COMMAND_RESULT, it) }
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putInt(EngineRuntimeIpcContract.KEY_ACTIVE_START_COUNT, activeStartCount)
    putInt(EngineRuntimeIpcContract.KEY_ACTIVE_BIND_COUNT, activeBindCount)
    putBoolean(EngineRuntimeIpcContract.KEY_CACHED, cached)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toServiceOperationResultOrNull(): VirtualServiceOperationResult? = runCatching {
    VirtualServiceOperationResult(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        operation = VirtualServiceOperation.valueOf(
            getString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION).orEmpty()
        ),
        serviceClassName = getString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        started = getBoolean(EngineRuntimeIpcContract.KEY_STARTED),
        stopped = getBoolean(EngineRuntimeIpcContract.KEY_STOPPED),
        bound = getBoolean(EngineRuntimeIpcContract.KEY_BOUND),
        unbound = getBoolean(EngineRuntimeIpcContract.KEY_UNBOUND),
        foreground = getBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND),
        startCommandResult = if (containsKey(EngineRuntimeIpcContract.KEY_START_COMMAND_RESULT)) {
            getInt(EngineRuntimeIpcContract.KEY_START_COMMAND_RESULT)
        } else {
            null
        },
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
        activeStartCount = getInt(EngineRuntimeIpcContract.KEY_ACTIVE_START_COUNT),
        activeBindCount = getInt(EngineRuntimeIpcContract.KEY_ACTIVE_BIND_COUNT),
        cached = getBoolean(EngineRuntimeIpcContract.KEY_CACHED),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualServiceRuntimeState.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putInt(EngineRuntimeIpcContract.KEY_RECORD_COUNT, records.size)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_SERVICE_RECORDS,
        ArrayList(records.map { it.toIpcBundle() })
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toServiceRuntimeStateOrNull(): VirtualServiceRuntimeState? = runCatching {
    val records = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_SERVICE_RECORDS)
        .orEmpty()
        .mapNotNull { it.toEngineServiceRuntimeRecordOrNull() }
    if (getInt(EngineRuntimeIpcContract.KEY_RECORD_COUNT) != records.size) return@runCatching null
    VirtualServiceRuntimeState(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        records = records,
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun EngineServiceRuntimeRecord.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME, serviceClassName)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_STATE, state.name)
    putInt(EngineRuntimeIpcContract.KEY_ACTIVE_START_COUNT, activeStartCount)
    putInt(EngineRuntimeIpcContract.KEY_ACTIVE_BIND_COUNT, activeBindCount)
    putBoolean(EngineRuntimeIpcContract.KEY_CACHED, cached)
    startCommandResult?.let { putInt(EngineRuntimeIpcContract.KEY_START_COMMAND_RESULT, it) }
    putLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS, updatedAtMs)
}

private fun Bundle.toEngineServiceRuntimeRecordOrNull(): EngineServiceRuntimeRecord? = runCatching {
    EngineServiceRuntimeRecord(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        serviceClassName = getString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
        state = EngineServiceLifecycleState.valueOf(
            getString(EngineRuntimeIpcContract.KEY_SERVICE_STATE).orEmpty()
        ),
        activeStartCount = getInt(EngineRuntimeIpcContract.KEY_ACTIVE_START_COUNT),
        activeBindCount = getInt(EngineRuntimeIpcContract.KEY_ACTIVE_BIND_COUNT),
        cached = getBoolean(EngineRuntimeIpcContract.KEY_CACHED),
        startCommandResult = if (containsKey(EngineRuntimeIpcContract.KEY_START_COMMAND_RESULT)) {
            getInt(EngineRuntimeIpcContract.KEY_START_COMMAND_RESULT)
        } else {
            null
        },
        updatedAtMs = getLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS)
    )
}.getOrNull()

private fun VirtualBroadcastDispatchPlanRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME, receiverClassName)
    putString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME, targetPackageName)
    putStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES, ArrayList(categories.sorted()))
    putString(EngineRuntimeIpcContract.KEY_DATA_SCHEME, dataScheme)
    putString(EngineRuntimeIpcContract.KEY_DATA_MIME_TYPE, dataMimeType)
    putString(EngineRuntimeIpcContract.KEY_DATA_AUTHORITY, dataAuthority)
    putString(EngineRuntimeIpcContract.KEY_DATA_PATH, dataPath)
    putBoolean(EngineRuntimeIpcContract.KEY_ORDERED, ordered)
    putBoolean(EngineRuntimeIpcContract.KEY_STICKY, sticky)
    putBoolean(EngineRuntimeIpcContract.KEY_EXPECTS_RESULT_RECEIVER, expectsResultReceiver)
    putBoolean(EngineRuntimeIpcContract.KEY_ABORT_SUPPORTED_REQUESTED, abortSupportedRequested)
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_RECEIVER_PERMISSIONS,
        ArrayList(receiverPermissions.sorted())
    )
    putString(EngineRuntimeIpcContract.KEY_RECEIVER_APP_OP, receiverAppOp)
    putBoolean(EngineRuntimeIpcContract.KEY_AS_USER_REQUESTED, asUserRequested)
    putBoolean(EngineRuntimeIpcContract.KEY_PLATFORM_OPTIONS_PRESENT, platformOptionsPresent)
}

private fun Bundle.toBroadcastPlanRequestOrNull(): VirtualBroadcastDispatchPlanRequest? = runCatching {
    VirtualBroadcastDispatchPlanRequest(
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        receiverClassName = getString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME),
        targetPackageName = getString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME),
        categories = getStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES).orEmpty().toSet(),
        dataScheme = getString(EngineRuntimeIpcContract.KEY_DATA_SCHEME),
        dataMimeType = getString(EngineRuntimeIpcContract.KEY_DATA_MIME_TYPE),
        dataAuthority = getString(EngineRuntimeIpcContract.KEY_DATA_AUTHORITY),
        dataPath = getString(EngineRuntimeIpcContract.KEY_DATA_PATH),
        ordered = getBoolean(EngineRuntimeIpcContract.KEY_ORDERED),
        sticky = getBoolean(EngineRuntimeIpcContract.KEY_STICKY),
        expectsResultReceiver = getBoolean(EngineRuntimeIpcContract.KEY_EXPECTS_RESULT_RECEIVER),
        abortSupportedRequested = getBoolean(EngineRuntimeIpcContract.KEY_ABORT_SUPPORTED_REQUESTED),
        receiverPermissions = getStringArrayList(EngineRuntimeIpcContract.KEY_RECEIVER_PERMISSIONS)
            .orEmpty()
            .toSet(),
        receiverAppOp = getString(EngineRuntimeIpcContract.KEY_RECEIVER_APP_OP),
        asUserRequested = getBoolean(EngineRuntimeIpcContract.KEY_AS_USER_REQUESTED),
        platformOptionsPresent = getBoolean(EngineRuntimeIpcContract.KEY_PLATFORM_OPTIONS_PRESENT)
    )
}.getOrNull()

private fun VirtualBroadcastDispatchPlan.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_TARGETS,
        ArrayList(targets.map { it.toIpcBundle() })
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS,
        ArrayList(supportedOperations.sorted())
    )
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS,
        ArrayList(unsupportedOperations.sorted())
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun VirtualBroadcastDispatchTarget.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME, virtualPackageName)
    putString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME, receiverClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
    putInt(EngineRuntimeIpcContract.KEY_PRIORITY, priority)
}

private fun Bundle.toBroadcastDispatchPlanOrNull(): VirtualBroadcastDispatchPlan? = runCatching {
    VirtualBroadcastDispatchPlan(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        targets = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_TARGETS)
            .orEmpty()
            .mapNotNull { it.toBroadcastDispatchTargetOrNull() },
        supportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        unsupportedOperations = getStringArrayList(EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS)
            .orEmpty()
            .toSet(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun Bundle.toBroadcastDispatchTargetOrNull(): VirtualBroadcastDispatchTarget? = runCatching {
    VirtualBroadcastDispatchTarget(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        originPackageName = getString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME).orEmpty(),
        virtualPackageName = getString(EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME).orEmpty(),
        receiverClassName = getString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME).orEmpty(),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        processName = getString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
        priority = getInt(EngineRuntimeIpcContract.KEY_PRIORITY)
    )
}.getOrNull()

private fun VirtualBroadcastOperationResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME, receiverClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    putBoolean(EngineRuntimeIpcContract.KEY_DELIVERED, delivered)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toBroadcastOperationResultOrNull(): VirtualBroadcastOperationResult? = runCatching {
    VirtualBroadcastOperationResult(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        receiverClassName = getString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        delivered = getBoolean(EngineRuntimeIpcContract.KEY_DELIVERED),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualBroadcastRuntimeState.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putInt(EngineRuntimeIpcContract.KEY_RECORD_COUNT, records.size)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_BROADCAST_RECORDS,
        ArrayList(records.map { it.toIpcBundle() })
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toBroadcastRuntimeStateOrNull(): VirtualBroadcastRuntimeState? = runCatching {
    val records = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_BROADCAST_RECORDS)
        .orEmpty()
        .mapNotNull { it.toEngineBroadcastRuntimeRecordOrNull() }
    if (getInt(EngineRuntimeIpcContract.KEY_RECORD_COUNT) != records.size) return@runCatching null
    VirtualBroadcastRuntimeState(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        records = records,
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun EngineBroadcastRuntimeRecord.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME, receiverClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTION, action)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_BROADCAST_STATE, state.name)
    putString(EngineRuntimeIpcContract.KEY_LAST_BROADCAST_VERDICT, lastVerdict.name)
    putString(EngineRuntimeIpcContract.KEY_REASON, lastReason)
    putLong(EngineRuntimeIpcContract.KEY_DISPATCH_COUNT, dispatchCount)
    putLong(EngineRuntimeIpcContract.KEY_DELIVERED_COUNT, deliveredCount)
    putLong(EngineRuntimeIpcContract.KEY_BLOCKED_COUNT, blockedCount)
    putLong(EngineRuntimeIpcContract.KEY_FAILURE_COUNT, failureCount)
    putLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS, updatedAtMs)
}

private fun Bundle.toEngineBroadcastRuntimeRecordOrNull(): EngineBroadcastRuntimeRecord? = runCatching {
    EngineBroadcastRuntimeRecord(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        receiverClassName = getString(EngineRuntimeIpcContract.KEY_RECEIVER_CLASS_NAME),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
        state = EngineBroadcastDeliveryState.valueOf(
            getString(EngineRuntimeIpcContract.KEY_BROADCAST_STATE).orEmpty()
        ),
        lastVerdict = EngineResultStatus.valueOf(
            getString(EngineRuntimeIpcContract.KEY_LAST_BROADCAST_VERDICT).orEmpty()
        ),
        lastReason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
        dispatchCount = getLong(EngineRuntimeIpcContract.KEY_DISPATCH_COUNT),
        deliveredCount = getLong(EngineRuntimeIpcContract.KEY_DELIVERED_COUNT),
        blockedCount = getLong(EngineRuntimeIpcContract.KEY_BLOCKED_COUNT),
        failureCount = getLong(EngineRuntimeIpcContract.KEY_FAILURE_COUNT),
        updatedAtMs = getLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS)
    )
}.getOrNull()

private fun Map<String, String>.toStringBundle(): Bundle = Bundle().apply {
    forEach { (key, value) -> putString(key, value) }
}

private fun Bundle?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return keySet().associateWith { key -> getString(key).orEmpty() }
}
