package com.multiapp.core.engine

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.multiapp.core.engine.ipc.IEngineRuntimeService
import com.multiapp.core.loader.VirtualActivityLaunchAllocation
import com.multiapp.core.loader.VirtualActivityLaunchAllocationRequest
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualTaskRecord
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object EngineRuntimeIpcContract {
    const val ENGINE_PROCESS_SUFFIX = ":engine"
    const val AUTHORITY_SUFFIX = ".multiapp.engine.server"
    const val METHOD_GET_BINDER = "getEngineRuntimeBinder"
    const val KEY_BINDER = "engineRuntimeBinder"
    const val KEY_SERVER_GENERATION_ID = "serverGenerationId"
    const val KEY_SERVER_PROCESS_ID = "serverProcessId"
    const val KEY_SERVER_PROCESS_NAME = "serverProcessName"
    const val KEY_FOUND = "found"
    const val KEY_ACCEPTED = "accepted"
    const val KEY_IDEMPOTENT = "idempotent"
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
    const val KEY_LIVE_AUTHORITY = "liveAuthority"
    const val KEY_CLIENT_TOKEN_ALIVE = "clientTokenAlive"
    const val KEY_ATTACH_OPERATION = "attachOperation"
    const val KEY_PROCESS_EPOCH = "processEpoch"
    const val KEY_CLIENT_SESSION_ID = "clientSessionId"
    const val KEY_EFFECTIVE_GUEST_PROCESS_NAME = "effectiveGuestProcessName"
    const val KEY_ALREADY_RUNNING = "alreadyRunning"
    const val KEY_ATTACH_CAPABILITY = "attachCapability"
    const val KEY_COMPONENT_PROCESS_LAUNCH_TICKET = "componentProcessLaunchTicket"
    const val KEY_COMPONENT_PROCESS_STATE = "componentProcessState"
    const val KEY_PROCESS_START_TICKS = "processStartTicks"
    const val KEY_SAME_PROCESS = "sameProcess"
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
    const val KEY_ROUTE_TOKEN = "routeToken"
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
    const val KEY_PERSISTED_MODE_FLAGS = "persistedModeFlags"
    const val KEY_REQUESTED = "requested"
    const val KEY_EXPLICIT = "explicit"
    const val KEY_SOURCE = "source"
    const val KEY_PERMISSION_RECORDS = "permissionRecords"
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
    const val KEY_SERVICE_OPERATION_LEASE_REQUESTED = "serviceOperationLeaseRequested"
    const val KEY_FOREGROUND = "foreground"
    const val KEY_STARTED = "started"
    const val KEY_STOPPED = "stopped"
    const val KEY_BOUND = "bound"
    const val KEY_UNBOUND = "unbound"
    const val KEY_DESTROYED = "destroyed"
    const val KEY_START_COMMAND_RESULT = "startCommandResult"
    const val KEY_ACTIVE_START_COUNT = "activeStartCount"
    const val KEY_ACTIVE_BIND_COUNT = "activeBindCount"
    const val KEY_SERVICE_RECORDS = "serviceRecords"
    const val KEY_SERVICE_STATE = "serviceState"
    const val KEY_SERVICE_CONNECTION_BINDINGS = "serviceConnectionBindings"
    const val KEY_SERVICE_OPERATION_LEASE = "serviceOperationLease"
    const val KEY_SERVICE_LEASE_TOKEN = "serviceLeaseToken"
    const val KEY_ISSUED_AT_NANOS = "issuedAtNanos"
    const val KEY_EXPIRES_AT_NANOS = "expiresAtNanos"
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
    const val KEY_RESTORE_ACTIVITY_ID = "restoreActivityId"
    const val KEY_RESTORE_CAPABILITY_STATUS = "restoreCapabilityStatus"
    const val KEY_PERSISTED_SYSTEM_ACTIVITY_TOKEN_REUSED = "persistedSystemActivityTokenReused"
    const val KEY_REVOKED_CAPABILITY_COUNT = "revokedCapabilityCount"
    const val KEY_LAUNCH_CAPABILITY_TOKEN = "launchCapabilityToken"
    const val KEY_ACTIVITY_STATE = "activityState"
    const val KEY_ACTIVITY = "activity"
    const val KEY_ACTIVITY_RESULT = "activityResult"
    const val KEY_PENDING_NEW_INTENT = "pendingNewIntent"
    const val KEY_RESULT_CODE = "resultCode"
    const val KEY_REQUEST_CODE = "requestCode"
    const val KEY_ACTIVITY_ALLOCATION_REQUEST = "activityAllocationRequest"
    const val KEY_ACTIVITY_LAUNCH_IDENTITY = "activityLaunchIdentity"
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
    const val KEY_TASK_KEY = "taskKey"
    const val KEY_PROXY_ACTIVITY_SLOT_KEY = "proxyActivitySlotKey"
    const val KEY_CANDIDATE_PROXY_ACTIVITY_CLASS_NAMES = "candidateProxyActivityClassNames"
    const val KEY_EXPECTED_PROXY_ACTIVITY_CLASS_NAME = "expectedProxyActivityClassName"
    const val KEY_NEW_PROXY_ACTIVITY_CLASS_NAME = "newProxyActivityClassName"
    const val KEY_MATCHED = "matched"
    const val KEY_REMOVED_COUNT = "removedCount"
    const val KEY_RESULT_TO_TOKEN = "resultToToken"
    const val KEY_PENDING_NEW_INTENTS = "pendingNewIntents"
    const val KEY_TASKS = "tasks"
    const val KEY_TASK_COUNT = "taskCount"
    const val KEY_ACTIVITY_COUNT = "activityCount"
    const val KEY_TOP_TASK_ID = "topTaskId"
    const val KEY_TOP_ACTIVITY_CLASS_NAME = "topActivityClassName"
    const val KEY_TOP_ACTIVITY_STATE = "topActivityState"
    const val KEY_ACTIVITIES = "activities"
    const val KEY_ENGINE_RUNTIME = "engineRuntime"
    const val KEY_ENGINE_EVIDENCE = "engineEvidence"
    const val KEY_ENGINE_PROFILE = "engineProfile"
    const val KEY_ENGINE_SUBSYSTEM_VERDICTS = "engineSubsystemVerdicts"
    const val KEY_ENGINE_OPERATION_EVIDENCE = "engineOperationEvidence"
    const val KEY_PACKAGE_STATE_SCHEMA_VERSION = "packageStateSchemaVersion"
    const val KEY_PACKAGE_STATE_TARGET = "packageStateTarget"
    const val KEY_COMPONENT_TYPE = "componentType"
    const val KEY_COMPONENT_CLASS_NAME = "componentClassName"
    const val KEY_ENABLED_STATE = "enabledState"
    const val KEY_CHANGED = "changed"

    const val MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_COUNT = 64
    const val MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_LENGTH = 512
    const val MAX_PROXY_ACTIVITY_SLOT_CANDIDATE_TOTAL_LENGTH = 16_384
    const val MAX_PROXY_ACTIVITY_SLOT_IDENTITY_LENGTH = 512
    const val MAX_PROXY_ACTIVITY_SLOT_TASK_KEY_LENGTH = 1_024
    const val MAX_SERVICE_CONNECTION_BINDING_COUNT = 128
    const val MAX_SERVICE_CONNECTION_IDENTITY_LENGTH = 1_024
    const val MAX_SERVICE_CONNECTION_REASON_LENGTH = 2_048
    const val MAX_COMPONENT_PROCESS_TEXT_LENGTH = 1_024

    fun authority(hostPackageName: String): String = hostPackageName + AUTHORITY_SUFFIX

    fun engineProcessName(hostPackageName: String): String = hostPackageName + ENGINE_PROCESS_SUFFIX
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
    val reason: String?,
    val liveAuthority: Boolean = true
)

data class EngineRuntimeForegroundAck(
    val accepted: Boolean,
    val idempotent: Boolean,
    val state: String?,
    val reason: String
)

data class EngineRuntimeAuthorityDecision(
    val allowed: Boolean,
    val authorityAvailable: Boolean,
    val reason: String
)

object EngineRuntimeAuthorityValidator {
    fun validate(
        snapshot: EngineRuntimeIpcSnapshot?,
        expectedProcessSlot: String? = null,
        requireLiveAuthority: Boolean = true
    ): EngineRuntimeAuthorityDecision = when {
        snapshot == null -> EngineRuntimeAuthorityDecision(
            allowed = false,
            authorityAvailable = false,
            reason = "ipc_unavailable"
        )
        !snapshot.found -> EngineRuntimeAuthorityDecision(
            allowed = false,
            authorityAvailable = true,
            reason = snapshot.reason ?: "runtime_not_found"
        )
        requireLiveAuthority && !snapshot.liveAuthority -> EngineRuntimeAuthorityDecision(
            allowed = false,
            authorityAvailable = true,
            reason = snapshot.reason ?: "live_runtime_authority_missing"
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

private sealed interface EngineServiceCallerAuthority {
    data class Primary(
        val identity: EngineProcessClientIdentity
    ) : EngineServiceCallerAuthority

    data class Component(
        val identity: EngineComponentProcessClientIdentity
    ) : EngineServiceCallerAuthority
}

private data class EngineUriPermissionCheckTarget(
    val runtime: VirtualInstanceRuntime,
    val processSlot: String,
    val processId: Int,
    val processName: String?,
    val reason: String
)

private data class EngineProviderRouteTargetResolution(
    val binding: EngineProviderRouteTargetBinding,
    val liveIdentity: EngineProviderRouteProcessIdentity?,
    val launchTicket: EngineComponentProcessLaunchTicket?
)

class EngineRuntimeBinderEndpoint(
    private val registry: EngineRuntimeRegistry,
    private val hostPackageName: String,
    private val hostUid: Int,
    private val serverGenerationId: String = UUID.randomUUID().toString(),
    private val activityLaunchCapabilities: EngineActivityLaunchCapabilityRegistry =
        EngineActivityLaunchCapabilityRegistry.global,
    private val activityService: VirtualActivityService = DefaultVirtualSystemServer(registry).activityService,
    private val activityOperationTransactions: EngineActivityOperationTransactionCoordinator =
        EngineActivityOperationTransactionCoordinator(),
    private val activityLaunchAllocator: EngineActivityLaunchAllocator =
        EngineActivityLaunchAllocator(registry, activityService, activityLaunchCapabilities),
    private val providerService: VirtualProviderService = DefaultVirtualSystemServer(registry).providerService,
    private val providerRouteTokens: EngineProviderRouteTokenRegistry =
        EngineProviderRouteTokenRegistry(),
    private val permissionService: VirtualPermissionService =
        DefaultVirtualSystemServer(registry).permissionService,
    private val appOpsService: VirtualAppOpsService = DefaultVirtualSystemServer(registry).appOpsService,
    private val serviceService: VirtualServiceService = DefaultVirtualSystemServer(registry).serviceService,
    private val serviceOperationLeases: EngineServiceOperationLeaseCoordinator =
        EngineServiceOperationLeaseCoordinator(registry),
    private val serviceConnections: EngineServiceConnectionRegistry =
        EngineServiceConnectionRegistry(),
    private val broadcastService: VirtualBroadcastService = DefaultVirtualSystemServer(registry).broadcastService,
    private val packageService: VirtualPackageService = DefaultVirtualPackageService,
    private val virtualizationEngine: VirtualizationEngine? = null,
    private val processControlPlane: EngineProcessControlPlane = EngineProcessControlPlane(
        runtimeRegistry = registry,
        activityLaunchCapabilities = activityLaunchCapabilities
    ),
    private val componentProcessAuthority: EngineComponentProcessAuthority? = null,
    private val componentProcessLaunchCapabilities: EngineComponentProcessLaunchCapabilityRegistry =
        EngineComponentProcessLaunchCapabilityRegistry(),
    private val recentsRestoreCapabilityIssuer: EngineRecentsRestoreCapabilityIssuer =
        EngineRecentsRestoreCapabilityIssuer(
            runtimeRegistry = registry,
            processControlPlane = processControlPlane,
            activityLaunchCapabilities = activityLaunchCapabilities,
            taskStateProvider = activityService::queryTaskState
        ),
    private val callingUid: () -> Int = Binder::getCallingUid,
    private val callingPid: () -> Int = Binder::getCallingPid,
    private val callingProcessName: (Int) -> String? = ::readAndroidProcessName,
    private val callingProcessStartTicks: (Int) -> Long? = ::readAndroidProcessStartTicks,
    private val ipcBundleFactory: () -> Bundle = ::Bundle,
    private val authoritativeRuntimeStreamOpener: (VirtualInstanceRuntime) -> ParcelFileDescriptor? =
        EngineAuthoritativeRuntimeStream::open
) : IEngineRuntimeService.Stub() {

    init {
        require(serverGenerationId.isNotBlank()) { "serverGenerationId must not be blank" }
    }

    override fun getServerGenerationId(): String =
        serverGenerationId.takeIf { callingUid() == hostUid }.orEmpty()

    override fun engineInstallOrRefreshPackage(originPackageName: String): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("installOrRefreshPackage")
        engine.installOrRefreshPackage(originPackageName).toEngineIpcBundle()
    }

    override fun engineCreateInstance(originPackageName: String): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("createInstance")
        engine.createInstance(originPackageName).toEngineIpcBundle()
    }

    override fun engineCreateInstanceWithMetadata(request: Bundle): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("createInstance")
        val decoded = request.toCreateInstanceRequestOrNull()
            ?: return@managementAuthorizedBundle engineInvalidRequestBundle("createInstance")
        engine.createInstance(decoded).toEngineIpcBundle()
    }

    override fun engineLaunchInstance(request: Bundle): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("launchInstance")
        val decoded = request.toLaunchInstanceRequestOrNull()
            ?: return@managementAuthorizedBundle engineInvalidRequestBundle("launchInstance")
        engine.launchInstance(decoded).toEngineIpcBundle()
    }

    override fun engineStopInstance(instanceId: String): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("stopInstance")
        engine.stopInstance(instanceId).toEngineIpcBundle()
    }

    override fun engineDeleteInstance(instanceId: String): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("deleteInstance")
        engine.deleteInstance(instanceId).toEngineIpcBundle()
    }

    override fun engineQueryRuntimeState(instanceId: String): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("queryRuntimeState")
        engine.queryRuntimeState(instanceId)?.toEngineRuntimeIdentityBundle()
            ?: engineMissingRuntimeBundle(instanceId)
    }

    override fun engineOpenRuntimeState(
        instanceId: String,
        request: Bundle
    ): ParcelFileDescriptor? {
        if (!isAuthorized() || instanceId.isBlank()) return null
        val engine = virtualizationEngine ?: return null
        val runtime = engine.queryRuntimeState(instanceId) ?: return null
        val providerTarget = boundProviderTargetForCallingProcess(runtime)
        val componentIdentity = if (providerTarget == null) {
            authoritativeComponentProcessIdentity(runtime)
        } else {
            null
        }
        val requestedSlot = request.getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT)
        val processRuntime = when {
            providerTarget != null -> {
                if (requestedSlot != providerTarget.binding.processSlot) return null
                runtime.toProviderRouteProcessRuntime(providerTarget)
            }
            componentIdentity != null -> {
                if (requestedSlot != componentIdentity.processSlot) return null
                runtime.toComponentProcessRuntime(componentIdentity)
            }
            else -> {
                if (!isAuthoritativeRuntimeStreamCaller(runtime, request)) return null
                runtime
            }
        }
        return authoritativeRuntimeStreamOpener(processRuntime)
    }

    override fun engineExportEvidence(instanceId: String): Bundle = managementAuthorizedBundle {
        val engine = virtualizationEngine
            ?: return@managementAuthorizedBundle engineOperationUnavailableBundle("exportEvidence")
        engine.exportEvidence(instanceId).toEngineEvidenceBundle()
    }

    override fun attachClient(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int,
        clientToken: IBinder
    ): Bundle = authorizedBundle {
        val identity = processIdentityOrNull(
            instanceId,
            runtimeEpoch,
            engineSessionId,
            processSlot,
            processId
        ) ?: return@authorizedBundle invalidProcessAttachBundle(
            EngineProcessAttachOperation.ATTACH_CLIENT,
            instanceId,
            "invalid_process_identity"
        )
        val callerPid = callingPid()
        processControlPlane.attachClient(
            identity = identity,
            clientToken = clientToken,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid)
        ).toIpcBundle()
    }

    override fun processRestarted(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int,
        clientToken: IBinder
    ): Bundle = authorizedBundle {
        val identity = processIdentityOrNull(
            instanceId,
            runtimeEpoch,
            engineSessionId,
            processSlot,
            processId
        ) ?: return@authorizedBundle invalidProcessAttachBundle(
            EngineProcessAttachOperation.PROCESS_RESTARTED,
            instanceId,
            "invalid_process_identity"
        )
        val callerPid = callingPid()
        processControlPlane.processRestarted(
            identity = identity,
            clientToken = clientToken,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid)
        ).toIpcBundle()
    }

    override fun markProcessPrewarmed(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int
    ): Bundle = authorizedBundle {
        val identity = processIdentityOrNull(
            instanceId,
            runtimeEpoch,
            engineSessionId,
            processSlot,
            processId
        ) ?: return@authorizedBundle invalidProcessPrewarmBundle(
            instanceId,
            "invalid_process_identity"
        )
        val callerPid = callingPid()
        processControlPlane.markPrewarmed(
            identity = identity,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid)
        ).toIpcBundle(instanceId)
    }

    override fun abandonProcessClient(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int,
        reason: String
    ): Bundle = authorizedBundle {
        val identity = processIdentityOrNull(
            instanceId,
            runtimeEpoch,
            engineSessionId,
            processSlot,
            processId
        ) ?: return@authorizedBundle invalidProcessAbandonBundle(
            instanceId,
            "invalid_process_identity"
        )
        val callerPid = callingPid()
        processControlPlane.abandon(
            identity = identity,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid),
            reason = reason
        ).toIpcBundle(instanceId)
    }

    override fun issueRecentsRestoreCapability(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int,
        restoreActivityId: String
    ): Bundle = authorizedBundle {
        val identity = processIdentityOrNull(
            instanceId,
            runtimeEpoch,
            engineSessionId,
            processSlot,
            processId
        ) ?: return@authorizedBundle invalidRestoreCapabilityBundle("invalid_process_identity")
        val callerPid = callingPid()
        if (callingProcessName(callerPid) != processSlot) {
            return@authorizedBundle invalidRestoreCapabilityBundle("calling_process_slot_mismatch")
        }
        recentsRestoreCapabilityIssuer.issue(
            identity = identity,
            restoreActivityId = restoreActivityId,
            callingPid = callerPid
        ).toIpcBundle()
    }

    override fun queryRuntime(instanceId: String): Bundle = authorizedBundle {
        val runtime = instanceId.takeIf { it.isNotBlank() }?.let(registry::get)
            ?: return@authorizedBundle missingRuntimeBundle(instanceId)
        boundProviderTargetForCallingProcess(runtime)?.let { providerTarget ->
            return@authorizedBundle runtime.toIpcBundle(
                liveAuthority = true,
                reason = "provider_route_target_process_authorized"
            ).apply {
                putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, providerTarget.binding.processSlot)
                putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, providerTarget.processId)
                putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, providerTarget.binding.processSlot)
            }
        }
        authoritativeComponentProcessIdentity(runtime)?.let { componentIdentity ->
            return@authorizedBundle runtime.toIpcBundle(
                liveAuthority = true,
                reason = "component_process_runtime_authorized"
            ).apply {
                putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, componentIdentity.processSlot)
                putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, componentIdentity.processId)
                putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, componentIdentity.processSlot)
            }
        }
        val authority = processControlPlane.authorize(instanceId, callingPid())
        runtime.toIpcBundle(
            liveAuthority = authority.allowed,
            reason = authority.reason
        )
    }

    override fun queryRuntimeByProcessId(processId: Int): Bundle = authorizedBundle {
        if (processId <= 0) {
            return@authorizedBundle missingProcessRuntimeBundle(processId, "invalid_process_id")
        }
        val callerPid = callingPid()
        if (processId != callerPid) {
            return@authorizedBundle missingProcessRuntimeBundle(
                processId,
                "runtime_process_id_not_calling_pid"
            )
        }
        val matches = registry.list().mapNotNull { runtime ->
            authorizeServiceCaller(runtime.instanceId, callerPid)?.let { authority ->
                val authoritativePid = when (authority) {
                    is EngineServiceCallerAuthority.Primary -> authority.identity.processId
                    is EngineServiceCallerAuthority.Component -> authority.identity.processId
                }
                (runtime to authority).takeIf { authoritativePid == processId }
            }
        }
        val match = matches.singleOrNull()
            ?: return@authorizedBundle missingProcessRuntimeBundle(
                processId,
                if (matches.isEmpty()) "runtime_process_id_not_found" else "runtime_process_id_ambiguous"
            )
        val (runtime, authority) = match
        runtime.toIpcBundle(
            liveAuthority = true,
            reason = when (authority) {
                is EngineServiceCallerAuthority.Primary -> "primary_process_runtime_authorized"
                is EngineServiceCallerAuthority.Component -> "component_process_runtime_authorized"
            }
        ).apply {
            if (authority is EngineServiceCallerAuthority.Component) {
                putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, authority.identity.processSlot)
                putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, authority.identity.processId)
                putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, authority.identity.processSlot)
            }
        }
    }

    override fun resolveUriPermissionCheckTarget(
        callerInstanceId: String,
        targetProcessId: Int
    ): Bundle = authorizedBundle {
        if (callerInstanceId.isBlank()) {
            return@authorizedBundle missingProcessRuntimeBundle(
                targetProcessId,
                "uri_permission_caller_instance_invalid"
            )
        }
        val callerPid = callingPid()
        if (!isLiveUriPermissionCaller(callerInstanceId, callerPid)) {
            return@authorizedBundle missingProcessRuntimeBundle(
                targetProcessId,
                "uri_permission_caller_runtime_unauthorized"
            )
        }
        if (targetProcessId <= 0) {
            return@authorizedBundle missingProcessRuntimeBundle(
                targetProcessId,
                "uri_permission_target_process_invalid"
            )
        }
        val matches = findLiveUriPermissionTargets(targetProcessId)
        val target = matches.singleOrNull()
            ?: return@authorizedBundle missingProcessRuntimeBundle(
                targetProcessId,
                if (matches.isEmpty()) {
                    "uri_permission_target_process_not_found"
                } else {
                    "uri_permission_target_process_ambiguous"
                }
            )
        target.toIpcBundle()
    }

    override fun prepareComponentProcess(
        instanceId: String,
        guestProcessName: String
    ): Bundle = authorizedBundle {
        val authority = componentProcessAuthority
            ?: return@authorizedBundle componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                "component_process_authority_unavailable"
            )
        val caller = authorizeServiceCaller(instanceId, callingPid())
            ?: return@authorizedBundle componentProcessRejected(
                COMPONENT_PROCESS_PREPARE_OPERATION,
                instanceId,
                "component_process_prepare_caller_unauthorized"
            )
        if (caller is EngineServiceCallerAuthority.Component) {
            val runtime = registry.get(instanceId)
            val effectiveRequestedProcess = runtime?.let { current ->
                guestProcessName.toServiceEffectiveGuestProcessName(current.originPackageName)
            }
            if (effectiveRequestedProcess != caller.identity.effectiveGuestProcessName) {
                return@authorizedBundle componentProcessRejected(
                    COMPONENT_PROCESS_PREPARE_OPERATION,
                    instanceId,
                    "component_process_prepare_identity_mismatch"
                )
            }
        }
        authority.prepare(instanceId, guestProcessName).toComponentProcessIpcBundle(ipcBundleFactory)
    }

    override fun attachComponentProcessClient(
        attachCapability: String,
        clientToken: IBinder
    ): Bundle = authorizedBundle {
        if (
            attachCapability.length < MIN_COMPONENT_PROCESS_CAPABILITY_LENGTH ||
            attachCapability.length > EngineRuntimeIpcContract.MAX_COMPONENT_PROCESS_TEXT_LENGTH ||
            attachCapability != attachCapability.trim()
        ) {
            return@authorizedBundle componentProcessRejected(
                COMPONENT_PROCESS_ATTACH_OPERATION,
                UNKNOWN_COMPONENT_PROCESS_INSTANCE,
                "invalid_component_process_attach_capability"
            )
        }
        val authority = componentProcessAuthority
            ?: return@authorizedBundle componentProcessRejected(
                COMPONENT_PROCESS_ATTACH_OPERATION,
                UNKNOWN_COMPONENT_PROCESS_INSTANCE,
                "component_process_authority_unavailable"
            )
        val callerPid = callingPid()
        authority.attach(
            attachCapability = attachCapability,
            clientToken = clientToken,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid),
            callingProcessStartTicks = callingProcessStartTicks(callerPid)
        ).toComponentProcessIpcBundle(ipcBundleFactory)
    }

    override fun queryComponentProcessClient(
        instanceId: String,
        guestProcessName: String
    ): Bundle = primaryRuntimeAuthorizedBundle(instanceId) {
        val authority = componentProcessAuthority
            ?: return@primaryRuntimeAuthorizedBundle componentProcessRejected(
                COMPONENT_PROCESS_QUERY_OPERATION,
                instanceId,
                "component_process_authority_unavailable"
            )
        authority.query(instanceId, guestProcessName).toComponentProcessIpcBundle(ipcBundleFactory)
    }

    override fun queryCallingComponentProcess(instanceId: String): Bundle = authorizedBundle {
        if (instanceId.isBlank()) {
            return@authorizedBundle componentProcessRejected(
                COMPONENT_PROCESS_QUERY_OPERATION,
                UNKNOWN_COMPONENT_PROCESS_INSTANCE,
                "component_process_instance_invalid"
            )
        }
        val authority = componentProcessAuthority
            ?: return@authorizedBundle componentProcessRejected(
                COMPONENT_PROCESS_QUERY_OPERATION,
                instanceId,
                "component_process_authority_unavailable"
            )
        val callerPid = callingPid()
        val identity = authority.authorizeCaller(
            instanceId = instanceId,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid),
            callingProcessStartTicks = callingProcessStartTicks(callerPid)
        ) ?: return@authorizedBundle componentProcessRejected(
            COMPONENT_PROCESS_QUERY_OPERATION,
            instanceId,
            "component_process_caller_unauthorized"
        )
        EngineComponentProcessOperationResult(
            operation = COMPONENT_PROCESS_QUERY_OPERATION,
            instanceId = identity.instanceId,
            accepted = true,
            idempotent = false,
            alreadyRunning = true,
            launchTicket = null,
            processState = identity.toPublicComponentProcessState(),
            reason = "calling_component_process_authorized"
        ).toComponentProcessIpcBundle(ipcBundleFactory)
    }

    override fun queryApplicationEnabledState(instanceId: String, request: Bundle): Bundle =
        packageEnabledStateBundle(
            instanceId = instanceId,
            request = request,
            target = EnginePackageEnabledStateTarget.APPLICATION,
            mutation = false,
            operation = "query_application"
        ) {
            packageService.queryApplicationEnabledState(instanceId)
        }

    override fun setApplicationEnabledState(instanceId: String, request: Bundle): Bundle =
        packageEnabledStateBundle(
            instanceId = instanceId,
            request = request,
            target = EnginePackageEnabledStateTarget.APPLICATION,
            mutation = true,
            operation = "set_application"
        ) { decoded ->
            packageService.setApplicationEnabledState(
                instanceId,
                requireNotNull(decoded.newState)
            )
        }

    override fun queryComponentEnabledState(instanceId: String, request: Bundle): Bundle =
        packageEnabledStateBundle(
            instanceId = instanceId,
            request = request,
            target = EnginePackageEnabledStateTarget.COMPONENT,
            mutation = false,
            operation = "query_component"
        ) { decoded ->
            packageService.queryComponentEnabledState(
                instanceId,
                requireNotNull(decoded.componentType),
                requireNotNull(decoded.className)
            )
        }

    override fun setComponentEnabledState(instanceId: String, request: Bundle): Bundle =
        packageEnabledStateBundle(
            instanceId = instanceId,
            request = request,
            target = EnginePackageEnabledStateTarget.COMPONENT,
            mutation = true,
            operation = "set_component"
        ) { decoded ->
            packageService.setComponentEnabledState(
                instanceId,
                requireNotNull(decoded.componentType),
                requireNotNull(decoded.className),
                requireNotNull(decoded.newState)
            )
        }

    override fun authorizeActivityLaunch(
        capabilityToken: String,
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        proxyActivityClassName: String,
        guestActivityClassName: String
    ): Bundle = authorizedBundle {
        val identity = runCatching {
            EngineActivityLaunchIdentity(
                capabilityToken = capabilityToken,
                instanceId = instanceId,
                runtimeEpoch = runtimeEpoch,
                engineSessionId = engineSessionId,
                processSlot = processSlot,
                proxyActivityClassName = proxyActivityClassName,
                guestActivityClassName = guestActivityClassName
            )
        }.getOrElse {
            return@authorizedBundle activityLaunchAuthorizationBundle(
                EngineActivityLaunchAuthorization(false, false, "invalid_activity_launch_identity")
            )
        }
        val callerPid = callingPid()
        val runtime = registry.get(instanceId)
        val liveAuthority = processControlPlane.authorize(instanceId, callerPid)
        val proxyProcessSlot = runtime?.let { current ->
            EngineProxyActivitySlots.processSlotForClassName(
                hostPackageName = current.hostPackageName,
                className = proxyActivityClassName
            )
        }
        val runtimeAccepted = runtime != null &&
            liveAuthority.allowed &&
            runtime.runtimeEpoch == runtimeEpoch &&
            runtime.engineSessionId == engineSessionId &&
            runtime.processSlot == processSlot &&
            proxyProcessSlot == runtime.processSlot &&
            runtime.processId == callerPid &&
            runtime.state in setOf(VirtualRuntimeState.PREWARMED, VirtualRuntimeState.RUNNING)
        val result = if (runtimeAccepted) {
            activityLaunchCapabilities.authorize(identity, callerPid)
        } else {
            EngineActivityLaunchAuthorization(false, false, "activity_launch_runtime_mismatch")
        }
        registry.registerOperationEvidence(
            instanceId,
            EngineOperationEvidence(
                component = "activity-foreground",
                operation = "launch-capability",
                verdict = if (result.accepted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
                entries = mapOf(
                    "runtimeEpoch" to runtimeEpoch.toString(),
                    "processSlot" to processSlot,
                    "proxyActivityClassName" to proxyActivityClassName,
                    "guestActivityClassName" to guestActivityClassName,
                    "callingPid" to callerPid.toString(),
                    "accepted" to result.accepted.toString(),
                    "idempotent" to result.idempotent.toString(),
                    "reason" to result.reason
                )
            )
        )
        activityLaunchAuthorizationBundle(result)
    }

    override fun acknowledgeActivityResumed(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        capabilityToken: String
    ): Bundle = authorizedBundle {
        if (
            instanceId.isBlank() || runtimeEpoch <= 0L ||
            engineSessionId.isBlank() || processSlot.isBlank() || capabilityToken.isBlank()
        ) {
            return@authorizedBundle invalidRequestBundle(instanceId, "invalid_activity_resume_ack")
        }
        val callerPid = callingPid()
        val liveAuthority = processControlPlane.authorize(instanceId, callerPid)
        if (!liveAuthority.allowed) {
            return@authorizedBundle invalidRequestBundle(instanceId, liveAuthority.reason)
        }
        val capability = activityLaunchCapabilities.validateResume(
            capabilityToken = capabilityToken,
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            callingPid = callerPid
        )
        val result = if (capability.accepted) {
            registry.acknowledgeActivityResumed(
                instanceId = instanceId,
                runtimeEpoch = runtimeEpoch,
                engineSessionId = engineSessionId,
                processSlot = processSlot,
                callingPid = callerPid
            )
        } else {
            EngineRuntimeForegroundAckResult(
                accepted = false,
                idempotent = false,
                state = registry.get(instanceId)?.state,
                reason = capability.reason
            )
        }
        if (result.accepted) {
            activityLaunchCapabilities.complete(capabilityToken)
        }
        registry.registerOperationEvidence(
            instanceId,
            EngineOperationEvidence(
                component = "activity-foreground",
                operation = "resume-ack",
                verdict = if (result.accepted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
                entries = mapOf(
                    "runtimeEpoch" to runtimeEpoch.toString(),
                    "processSlot" to processSlot,
                    "callingPid" to callerPid.toString(),
                    "accepted" to result.accepted.toString(),
                    "idempotent" to result.idempotent.toString(),
                    "runtimeState" to result.state?.name.orEmpty(),
                    "reason" to result.reason
                )
            )
        )
        Bundle().apply {
            putBoolean(EngineRuntimeIpcContract.KEY_FOUND, result.state != null)
            putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, result.accepted)
            putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, result.idempotent)
            putString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE, result.state?.name)
            putString(EngineRuntimeIpcContract.KEY_REASON, result.reason)
        }
    }

    override fun queryEvidence(instanceId: String): Bundle = managementAuthorizedBundle {
        val runtime = instanceId.takeIf { it.isNotBlank() }?.let(registry::get)
            ?: return@managementAuthorizedBundle missingRuntimeBundle(instanceId)
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

    override fun planActivity(instanceId: String, request: Bundle): Bundle = runtimeAuthorizedBundle(instanceId) {
        val decodedRequest = request.toActivityPlanRequestOrNull()
            ?: return@runtimeAuthorizedBundle invalidRequestBundle(instanceId, "invalid_activity_plan_request")
        val plan = activityService.planActivity(instanceId, decodedRequest)
        if (plan.verdict == EngineResultStatus.FAIL || plan.targets.isEmpty()) {
            return@runtimeAuthorizedBundle plan.toIpcBundle()
        }
        val target = plan.targets.singleOrNull()
            ?: return@runtimeAuthorizedBundle plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = "activity_allocation_requires_single_target"
            ).toIpcBundle()
        val allocation = activityLaunchAllocator.allocate(
            VirtualActivityLaunchAllocationRequest(
                instanceId = target.instanceId,
                originPackageName = target.originPackageName,
                guestActivityClassName = target.activityClassName,
                processSlot = target.processSlot,
                launchMode = target.launchMode,
                taskAffinity = target.taskAffinity
            ),
            callingPid()
        )
        val identity = allocation.launchIdentity
        val proxy = allocation.proxyActivityClassName
        if (!allocation.accepted || identity == null || proxy == null) {
            return@runtimeAuthorizedBundle plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = allocation.reason
            ).toIpcBundle()
        }
        plan.copy(
            targets = listOf(
                target.copy(
                    proxyActivityClassName = proxy,
                    launchCapabilityToken = identity.capabilityToken,
                    runtimeEpoch = identity.runtimeEpoch,
                    engineSessionId = identity.engineSessionId
                )
            ),
            message = "${plan.message}:activity_allocation_authorized"
        ).toIpcBundle()
    }

    override fun allocateActivityLaunch(instanceId: String, request: Bundle): Bundle =
        runtimeAuthorizedBundle(instanceId) {
            val decoded = request.toActivityLaunchAllocationRequestOrNull()
                ?.takeIf { it.instanceId == instanceId }
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    instanceId,
                    "invalid_activity_allocation_request"
                )
            activityLaunchAllocator.allocate(decoded, callingPid()).toIpcBundle()
        }

    override fun releaseActivityLaunch(instanceId: String, allocation: Bundle): Boolean {
        if (!isRuntimeAuthorized(instanceId)) return false
        val decoded = allocation.toActivityLaunchAllocationOrNull()
            ?.takeIf { it.accepted && it.request.instanceId == instanceId }
            ?: return false
        return activityLaunchAllocator.release(decoded, callingPid())
    }

    override fun recordActivityDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isRuntimeAuthorized(instanceId)) return false
        val decodedResult = result.toActivityDispatchResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        return activityService.recordActivityDispatch(instanceId, decodedResult)
    }

    override fun mutateActivity(instanceId: String, operation: String, request: Bundle): Bundle =
        runtimeAuthorizedBundle(instanceId) {
            val decodedRequest = request.toActivityMutationRequestOrNull(operation)
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    instanceId,
                    "invalid_activity_mutation_request:$operation"
                )
            val transaction = issueActivityOperationTransaction(
                instanceId = instanceId,
                operation = decodedRequest.operation.wireName,
                targetActivityToken = decodedRequest.token,
                payloadFingerprint = decodedRequest.payloadFingerprint()
            ) ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                instanceId,
                "activity_operation_transaction_issue_failed:$operation"
            )
            var result: VirtualActivityOperationResult? = null
            val commit = activityOperationTransactions.commit(transaction, callingPid()) {
                result = executeActivityMutation(instanceId, decodedRequest)
                result != null
            }
            if (!commit.accepted) {
                return@runtimeAuthorizedBundle invalidRequestBundle(
                    instanceId,
                    "activity_operation_transaction_failed:${commit.reason}"
                )
            }
            recordActivityTransactionEvidence(transaction, commit, "mutation")
            checkNotNull(result).toIpcBundle()
        }

    override fun consumeActivity(instanceId: String, operation: String, request: Bundle): Bundle =
        runtimeAuthorizedBundle(instanceId) {
            val decodedOperation = EngineActivityIpcOperation.fromWireName(operation)
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    instanceId,
                    "invalid_activity_consume_operation:$operation"
                )
            val token = request.getString(EngineRuntimeIpcContract.KEY_TOKEN)
                ?.takeIf { it.isNotBlank() }
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    instanceId,
                    "invalid_activity_consume_token"
                )
            val transaction = issueActivityOperationTransaction(
                instanceId = instanceId,
                operation = decodedOperation.wireName,
                targetActivityToken = token,
                payloadFingerprint = fingerprintActivityPayload(decodedOperation.wireName, token)
            ) ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                instanceId,
                "activity_operation_transaction_issue_failed:$operation"
            )
            var response: EngineActivityIpcConsumeResponse? = null
            val commit = activityOperationTransactions.commit(transaction, callingPid()) {
                response = executeActivityConsume(instanceId, decodedOperation, token)
                response != null
            }
            if (!commit.accepted) {
                return@runtimeAuthorizedBundle invalidRequestBundle(
                    instanceId,
                    "activity_operation_transaction_failed:${commit.reason}"
                )
            }
            recordActivityTransactionEvidence(transaction, commit, "consume")
            checkNotNull(response).normalizeFound().toIpcBundle()
        }

    override fun queryActivityTaskState(instanceId: String): Bundle = runtimeAuthorizedBundle(instanceId) {
        activityService.queryTaskState(instanceId).toIpcBundle()
    }

    override fun syncActivityTaskState(
        instanceId: String,
        reason: String,
        snapshot: Bundle
    ): Bundle = runtimeAuthorizedBundle(instanceId) {
        invalidRequestBundle(instanceId, "direct_activity_task_snapshot_sync_disabled")
    }

    private fun issueActivityOperationTransaction(
        instanceId: String,
        operation: String,
        targetActivityToken: String,
        payloadFingerprint: String
    ): EngineActivityOperationTransaction? {
        val identity = processControlPlane.authorize(instanceId, callingPid()).identity ?: return null
        return runCatching {
            activityOperationTransactions.issue(
                instanceId = identity.instanceId,
                runtimeEpoch = identity.runtimeEpoch,
                engineSessionId = identity.engineSessionId,
                processSlot = identity.processSlot,
                processId = identity.processId,
                operation = operation,
                targetActivityToken = targetActivityToken,
                payloadFingerprint = payloadFingerprint
            )
        }.getOrNull()
    }

    private fun executeActivityMutation(
        instanceId: String,
        request: EngineActivityIpcMutationRequest
    ): VirtualActivityOperationResult? = when (request.operation) {
        EngineActivityIpcOperation.MARK_STATE -> request.state?.let { state ->
            activityService.markActivityState(instanceId, request.token, state)
        }
        EngineActivityIpcOperation.FINISH -> activityService.finishActivity(instanceId, request.token)
        EngineActivityIpcOperation.RECORD_FINISH_RESULT ->
            activityService.recordActivityResultForFinish(
                instanceId,
                request.token,
                request.resultCode,
                request.dataIntent
            )
        EngineActivityIpcOperation.SET_RESULT -> activityService.setActivityResult(
            instanceId,
            request.token,
            request.resultCode,
            request.dataIntent,
            request.requestCode,
            request.resultWho,
            request.frameworkDispatchAttempted,
            request.frameworkDispatchInvoked
        )
        EngineActivityIpcOperation.MARK_RESULT_DISPATCH ->
            activityService.markActivityResultDispatchState(
                instanceId,
                request.token,
                request.frameworkDispatchAttempted,
                request.frameworkDispatchInvoked
            )
        else -> null
    }

    private fun executeActivityConsume(
        instanceId: String,
        operation: EngineActivityIpcOperation,
        token: String
    ): EngineActivityIpcConsumeResponse? = when (operation) {
        EngineActivityIpcOperation.CONSUME_RESULT -> EngineActivityIpcConsumeResponse(
            operation = operation,
            found = true,
            activityResult = activityService.consumeActivityResult(instanceId, token)
        )
        EngineActivityIpcOperation.CONSUME_RESULT_RESUME_FALLBACK -> EngineActivityIpcConsumeResponse(
            operation = operation,
            found = true,
            activityResult = activityService.consumeActivityResultForResumeFallback(instanceId, token)
        )
        EngineActivityIpcOperation.CONSUME_PENDING_NEW_INTENT -> EngineActivityIpcConsumeResponse(
            operation = operation,
            found = true,
            pendingNewIntent = activityService.consumePendingNewIntent(instanceId, token)
        )
        else -> null
    }

    private fun EngineActivityIpcMutationRequest.payloadFingerprint(): String =
        fingerprintActivityPayload(
            operation.wireName,
            token,
            state?.name.orEmpty(),
            resultCode.toString(),
            dataIntent.canonicalActivityIntentPayload(),
            requestCode.toString(),
            resultWho.orEmpty(),
            frameworkDispatchAttempted.toString(),
            frameworkDispatchInvoked.toString()
        )

    private fun VirtualIntentSnapshot?.canonicalActivityIntentPayload(): String {
        val intent = this ?: return "null"
        val categories = intent.categories.sorted().joinToString(separator = "") { value ->
            "${value.length}:$value"
        }
        val extras = intent.extras.toSortedMap().entries.joinToString(separator = "") { (key, value) ->
            "${key.length}:$key${value.length}:$value"
        }
        return listOf(
            intent.flags.toString(),
            intent.action.orEmpty(),
            intent.dataUri.orEmpty(),
            categories,
            extras
        ).joinToString(separator = "") { value -> "${value.length}:$value" }
    }

    private fun fingerprintActivityPayload(vararg fields: String): String {
        val canonical = fields.joinToString(separator = "") { value -> "${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun recordActivityTransactionEvidence(
        transaction: EngineActivityOperationTransaction,
        decision: EngineActivityOperationTransactionDecision,
        phase: String
    ) {
        registry.registerOperationEvidence(
            transaction.instanceId,
            EngineOperationEvidence(
                component = "activity-transaction",
                operation = transaction.operation,
                verdict = if (decision.accepted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
                entries = mapOf(
                    "phase" to phase,
                    "runtimeEpoch" to transaction.runtimeEpoch.toString(),
                    "engineSessionId" to transaction.engineSessionId,
                    "processSlot" to transaction.processSlot,
                    "processId" to transaction.processId.toString(),
                    "targetActivityTokenHash" to fingerprintActivityPayload(
                        transaction.targetActivityToken
                    ),
                    "payloadFingerprint" to transaction.payloadFingerprint,
                    "transactionState" to decision.state.name,
                    "idempotent" to decision.idempotent.toString(),
                    "reason" to decision.reason
                )
            )
        )
    }

    override fun queryProxyActivitySlot(request: Bundle): Bundle = authorizedBundle {
        val key = request.toProxyActivitySlotQueryIpcKeyOrNull()
            ?: return@authorizedBundle invalidRequestBundle(
                "invalid",
                "invalid_proxy_activity_slot_query_request"
            )
        if (!processControlPlane.authorize(key.instanceId, callingPid()).allowed) {
            return@authorizedBundle rejectedProxyActivitySlotOperation(
                key,
                PROXY_ACTIVITY_SLOT_QUERY_OPERATION,
                "proxy_activity_slot_query_runtime_unauthorized"
            )
        }
        activityService.queryProxyActivitySlot(key.instanceId, key).toProxyActivitySlotResultIpcBundle()
    }

    override fun reserveProxyActivitySlot(request: Bundle): Bundle = authorizedBundle {
        val decoded = request.toProxyActivitySlotReserveIpcRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(
                "invalid",
                "invalid_proxy_activity_slot_reserve_request"
            )
        rejectedProxyActivitySlotOperation(
            decoded.key,
            PROXY_ACTIVITY_SLOT_RESERVE_OPERATION,
            "direct_proxy_activity_slot_mutation_disabled"
        )
    }

    override fun compareAndSetProxyActivitySlot(request: Bundle): Bundle = authorizedBundle {
        val decoded = request.toProxyActivitySlotCompareAndSetIpcRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(
                "invalid",
                "invalid_proxy_activity_slot_compare_and_set_request"
            )
        rejectedProxyActivitySlotOperation(
            decoded.key,
            PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION,
            "direct_proxy_activity_slot_mutation_disabled"
        )
    }

    private fun rejectedProxyActivitySlotOperation(
        key: ProxyActivitySlotKey,
        operation: String,
        message: String
    ): Bundle = VirtualProxyActivitySlotOperationResult(
        instanceId = key.instanceId,
        operation = operation,
        verdict = EngineResultStatus.FAIL,
        key = key,
        proxyActivityClassName = null,
        matched = false,
        removedCount = 0,
        message = message
    ).toProxyActivitySlotResultIpcBundle()

    override fun planProvider(instanceId: String, request: Bundle): Bundle = authorizedBundle {
        val decodedRequest = request.toProviderPlanRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(instanceId, "invalid_provider_plan_request")
        val targetBinding = currentProviderRouteTargetBinding(
            decodedRequest.routeToken,
            instanceId
        ) ?: return@authorizedBundle invalidRequestBundle(
            instanceId,
            "provider_plan_target_binding_stale"
        )
        val receipt = authorizeProviderRouteDispatch(
            token = decodedRequest.routeToken,
            targetBinding = targetBinding,
            authority = decodedRequest.guestAuthority,
            operation = decodedRequest.operation.toProviderRouteOperationName()
        )
        val route = receipt.route
            ?: return@authorizedBundle invalidRequestBundle(
                instanceId,
                "provider_plan_route_receipt_rejected:${receipt.status.name}"
            )
        providerService.planProvider(
            instanceId,
            decodedRequest.copy(
                processSlot = route.processSlot,
                routeTokenPresent = true,
                routeTokenVerified = true,
                callerInstanceId = route.callerInstanceId,
                targetInstanceId = route.targetInstanceId,
                callingUid = hostUid,
                callingPid = route.callerProcessId ?: -1,
                hostUid = hostUid,
                callerProcessSlot = route.callerProcessSlot,
                engineCallingUid = callingUid(),
                engineCallingPid = callingPid()
            )
        ).toIpcBundle()
    }

    override fun resolveProviderAuthority(callerInstanceId: String, request: Bundle): Bundle =
        runtimeAuthorizedBundle(callerInstanceId) {
            val decodedRequest = request.toProviderAuthorityResolveRequestOrNull()
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    callerInstanceId,
                    "invalid_provider_authority_resolve_request"
                )
            providerService.resolveProviderAuthority(callerInstanceId, decodedRequest).toIpcBundle()
        }

    override fun issueProviderRouteToken(callerInstanceId: String, request: Bundle): Bundle =
        authorizedBundle {
            val decoded = request.toProviderRouteTokenIssueRequestOrNull()
                ?: return@authorizedBundle providerRouteTokenFailure(
                    "INVALID_REQUEST",
                    "invalid provider route token issue request"
                )
            if (callerInstanceId.isBlank()) {
                return@authorizedBundle providerRouteTokenFailure(
                    "CALLER_UNAUTHORIZED",
                    "provider route caller instance is invalid"
                )
            }
            val callerAuthority = authorizeServiceCaller(callerInstanceId, callingPid())
                ?: return@authorizedBundle providerRouteTokenFailure(
                    "CALLER_UNAUTHORIZED",
                    "provider route caller process is not authoritative"
                )
            val callerIdentity = callerAuthority.toProviderRouteProcessIdentity()
                ?: return@authorizedBundle providerRouteTokenFailure(
                    "CALLER_UNAUTHORIZED",
                    "provider route caller identity is incomplete"
                )
            val resolveRequest = VirtualProviderAuthorityResolveRequest(
                guestAuthority = decoded.guestAuthority,
                operation = EngineProviderOperation.fromOperationName(decoded.operation),
                encodedPath = "/",
                accessMode = decoded.operation.substringAfter(':', "").takeIf { it.isNotBlank() }
            )
            val resolution = providerService.resolveProviderAuthority(callerInstanceId, resolveRequest)
            val targetInstanceId = resolution.targetInstanceId
            if (
                !resolution.virtualAuthority || resolution.verdict == EngineResultStatus.FAIL ||
                targetInstanceId.isNullOrBlank() || targetInstanceId != decoded.targetInstanceId
            ) {
                return@authorizedBundle providerRouteTokenFailure(
                    "TARGET_UNRESOLVED",
                    "provider route target authority was not resolved by engine"
                )
            }
            val target = resolveProviderRouteTarget(
                targetInstanceId,
                decoded.guestAuthority
            ) ?: return@authorizedBundle providerRouteTokenFailure(
                "TARGET_PROCESS_UNAVAILABLE",
                "provider route target component process could not be prepared"
            )
            if (
                decoded.requestedProcessSlot != null &&
                decoded.requestedProcessSlot != target.binding.processSlot
            ) {
                return@authorizedBundle providerRouteTokenFailure(
                    "PROCESS_SLOT_MISMATCH",
                    "provider route target process slot differs from the requested slot"
                )
            }
            val route = runCatching {
                providerRouteTokens.issue(
                    authoritativeCallerIdentity = callerIdentity,
                    authoritativeTargetBinding = target.binding,
                    authoritativeTargetIdentity = target.liveIdentity,
                    targetLaunchTicket = target.launchTicket,
                    authority = decoded.guestAuthority,
                    operation = decoded.operation
                )
            }.getOrElse { error ->
                return@authorizedBundle providerRouteTokenFailure(
                    "ISSUE_FAILED",
                    "provider route token issue failed:${error.javaClass.name}"
                )
            }
            EngineProviderRouteTokenAuthorityResult(
                status = "ISSUED",
                route = route,
                message = "engine provider route token issued"
            ).toProviderRouteTokenIpcBundle(ipcBundleFactory)
        }

    override fun validateAndConsumeProviderRouteToken(
        targetInstanceId: String,
        request: Bundle
    ): Bundle = authorizedBundle {
        val decoded = request.toProviderRouteTokenConsumeRequestOrNull()
            ?: return@authorizedBundle providerRouteTokenFailure(
                "INVALID_REQUEST",
                "invalid provider route token consume request"
            )
        if (
            targetInstanceId.isBlank() || targetInstanceId != decoded.targetInstanceId ||
            decoded.providerCallingUid != hostUid
        ) {
            return@authorizedBundle providerRouteTokenFailure(
                "TARGET_UNAUTHORIZED",
                "provider route target or original caller uid is invalid"
            )
        }
        val targetBinding = currentProviderRouteTargetBinding(
            decoded.token,
            targetInstanceId
        ) ?: return@authorizedBundle providerRouteTokenFailure(
            "TARGET_UNAUTHORIZED",
            "provider route target binding is missing or stale"
        )
        if (decoded.expectedProcessSlot != targetBinding.processSlot) {
            return@authorizedBundle providerRouteTokenFailure(
                "PROCESS_SLOT_MISMATCH",
                "provider route target process slot does not match Binder caller"
            )
        }
        val consumed = consumeProviderRouteToken(decoded, targetBinding)
        EngineProviderRouteTokenAuthorityResult(
            status = if (consumed.consumed) "VALID" else consumed.status.name,
            route = consumed.route,
            message = if (consumed.consumed) {
                "engine provider route token consumed"
            } else {
                "engine provider route token rejected:${consumed.status.name}"
            }
        ).toProviderRouteTokenIpcBundle(ipcBundleFactory)
    }

    override fun recordProviderDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isRuntimeAuthorized(instanceId)) return false
        val decodedResult = result.toProviderOperationResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        return providerService.recordProviderDispatch(instanceId, decodedResult)
    }

    override fun queryProviderRuntimeState(instanceId: String): Bundle = runtimeAuthorizedBundle(instanceId) {
        providerService.queryProviderRuntimeState(instanceId).toIpcBundle()
    }

    override fun grantProviderUriPermission(ownerInstanceId: String, request: Bundle): Bundle =
        runtimeAuthorizedBundle(ownerInstanceId) {
            val decodedRequest = request.toProviderUriGrantRequestOrNull()
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    ownerInstanceId,
                    "invalid_provider_uri_grant_request"
                )
            providerService.grantUriPermission(
                ownerInstanceId,
                decodedRequest.withAuthoritativeCaller()
            ).toIpcBundle()
        }

    override fun revokeProviderUriPermission(ownerInstanceId: String, request: Bundle): Bundle =
        runtimeAuthorizedBundle(ownerInstanceId) {
            val decodedRequest = request.toProviderUriGrantRequestOrNull()
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    ownerInstanceId,
                    "invalid_provider_uri_revoke_request"
                )
            providerService.revokeUriPermission(
                ownerInstanceId,
                decodedRequest.withAuthoritativeCaller()
            ).toIpcBundle()
        }

    override fun checkProviderUriPermission(targetInstanceId: String, request: Bundle): Bundle =
        runtimeAuthorizedBundle(targetInstanceId) {
            val decodedRequest = request.toProviderUriGrantRequestOrNull()
                ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                    targetInstanceId,
                    "invalid_provider_uri_check_request"
                )
            providerService.checkUriPermission(
                targetInstanceId,
                decodedRequest.copy(hostUid = hostUid)
            ).toIpcBundle()
        }

    override fun checkProviderUriPermissionForCaller(
        callerInstanceId: String,
        targetInstanceId: String,
        request: Bundle
    ): Bundle = authorizedBundle {
        val callerPid = callingPid()
        if (!isLiveUriPermissionCaller(callerInstanceId, callerPid)) {
            return@authorizedBundle invalidRequestBundle(
                targetInstanceId,
                "provider_uri_check_caller_runtime_unauthorized"
            )
        }
        val decodedRequest = request.toProviderUriGrantRequestOrNull()
            ?: return@authorizedBundle invalidRequestBundle(
                targetInstanceId,
                "invalid_provider_uri_check_request"
            )
        val targetProcessId = decodedRequest.callingPid
        val target = findLiveUriPermissionTargets(targetProcessId).singleOrNull()
            ?.takeIf { resolved -> resolved.runtime.instanceId == targetInstanceId }
            ?: return@authorizedBundle invalidRequestBundle(
                targetInstanceId,
                "provider_uri_check_target_runtime_unauthorized"
            )
        providerService.checkUriPermission(
            target.runtime.instanceId,
            decodedRequest.copy(
                targetInstanceId = target.runtime.instanceId,
                callingUid = hostUid,
                callingPid = target.processId,
                hostUid = hostUid
            )
        ).toIpcBundle()
    }

    override fun takePersistableProviderUriPermission(
        targetInstanceId: String,
        request: Bundle
    ): Bundle = runtimeAuthorizedBundle(targetInstanceId) {
        val decodedRequest = request.toProviderUriGrantRequestOrNull()
            ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                targetInstanceId,
                "invalid_provider_persistable_uri_take_request"
            )
        providerService.takePersistableUriPermission(
            targetInstanceId,
            decodedRequest.withAuthoritativeCaller()
        ).toIpcBundle()
    }

    override fun releasePersistableProviderUriPermission(
        targetInstanceId: String,
        request: Bundle
    ): Bundle = runtimeAuthorizedBundle(targetInstanceId) {
        val decodedRequest = request.toProviderUriGrantRequestOrNull()
            ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                targetInstanceId,
                "invalid_provider_persistable_uri_release_request"
            )
        providerService.releasePersistableUriPermission(
            targetInstanceId,
            decodedRequest.withAuthoritativeCaller()
        ).toIpcBundle()
    }

    override fun checkPermission(instanceId: String, permissionName: String): Bundle =
        runtimeAuthorizedBundle(instanceId) {
        if (permissionName.isBlank()) {
            return@runtimeAuthorizedBundle invalidRequestBundle(instanceId, "invalid_permission_check_request")
        }
        permissionService.checkPermission(instanceId, permissionName).toIpcBundle()
    }

    override fun queryPermissionRuntimeState(instanceId: String): Bundle = runtimeAuthorizedBundle(instanceId) {
        permissionService.queryRuntimeState(instanceId).toIpcBundle()
    }

    override fun queryAppOp(instanceId: String, request: Bundle): Bundle = runtimeAuthorizedBundle(instanceId) {
        val decodedRequest = request.toAppOpsQueryRequestOrNull()
            ?: return@runtimeAuthorizedBundle invalidRequestBundle(instanceId, "invalid_app_ops_query_request")
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
        val callerPid = callingPid()
        val authority = authorizeServiceCaller(instanceId, callerPid)
            ?: return@authorizedBundle missingLiveAuthorityBundle(
                instanceId,
                "service_process_authority_missing"
            )
        val plan = serviceService.planService(instanceId, decodedRequest)
        when (authority) {
            is EngineServiceCallerAuthority.Primary -> {
                if (
                    decodedRequest.operationLeaseRequested &&
                    plan.targets.any { target -> !target.sameProcess }
                ) {
                    plan.copy(
                        verdict = EngineResultStatus.FAIL,
                        targets = emptyList(),
                        message = "component_service_operation_lease_unsupported"
                    )
                } else {
                    attachServiceOperationLease(
                        instanceId = instanceId,
                        plan = plan,
                        callerPid = callerPid,
                        leaseRequested = decodedRequest.operationLeaseRequested
                    )
                }
            }
            is EngineServiceCallerAuthority.Component -> authorizeComponentServicePlan(
                plan = plan,
                request = decodedRequest,
                identity = authority.identity
            )
        }.toIpcBundle()
    }

    override fun recordServiceDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isAuthorized()) return false
        val decodedResult = result.toServiceOperationResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        val callerPid = callingPid()
        return when (val authority = authorizeServiceCaller(instanceId, callerPid)) {
            is EngineServiceCallerAuthority.Primary -> recordPrimaryServiceDispatch(
                decodedResult,
                callerPid
            )
            is EngineServiceCallerAuthority.Component -> recordComponentServiceDispatch(
                decodedResult,
                authority.identity
            )
            null -> false
        }
    }

    private fun recordPrimaryServiceDispatch(
        decodedResult: VirtualServiceOperationResult,
        callerPid: Int
    ): Boolean {
        val lease = decodedResult.operationLease ?: return false
        if (!decodedResult.matches(lease)) {
            serviceOperationLeases.abort(lease, callerPid)
            return false
        }
        val decision = serviceOperationLeases.commit(lease, callerPid) {
            serviceService.recordServiceDispatch(decodedResult.instanceId, decodedResult)
        }
        recordServiceLeaseEvidence(lease, decision, "dispatch-result")
        if (!decision.accepted && lease.operationType == VirtualServiceOperation.BIND) {
            val aborted = serviceOperationLeases.abort(lease, callerPid)
            recordServiceLeaseEvidence(lease, aborted, "bind-dispatch-abort")
        }
        return decision.accepted
    }

    private fun recordComponentServiceDispatch(
        result: VirtualServiceOperationResult,
        identity: EngineComponentProcessClientIdentity
    ): Boolean {
        if (result.operation !in COMPONENT_SERVICE_START_OPERATIONS) return false
        if (result.operationLease != null || result.processSlot != identity.processSlot) return false
        if (result.foreground != (result.operation == VirtualServiceOperation.START_FOREGROUND)) return false
        val runtime = registry.get(identity.instanceId) ?: return false
        if (
            runtime.runtimeEpoch != identity.runtimeEpoch ||
            runtime.engineSessionId != identity.engineSessionId
        ) {
            return false
        }
        val serviceClassName = result.serviceClassName
            ?.toServiceComponentClassName(runtime.originPackageName)
            ?: return false
        val service = runtime.packageSnapshot.services.singleOrNull { component ->
            component.name == serviceClassName
        } ?: return false
        if (
            service.effectiveServiceProcessName(runtime) != identity.effectiveGuestProcessName
        ) {
            return false
        }
        return serviceService.recordServiceDispatch(
            identity.instanceId,
            result.copy(serviceClassName = serviceClassName)
        )
    }

    private fun authorizeComponentServicePlan(
        plan: VirtualServiceDispatchPlan,
        request: VirtualServiceDispatchPlanRequest,
        identity: EngineComponentProcessClientIdentity
    ): VirtualServiceDispatchPlan {
        if (request.operation !in COMPONENT_SERVICE_START_OPERATIONS) {
            return plan.copy(
                verdict = EngineResultStatus.UNSUPPORTED,
                targets = emptyList(),
                unsupportedOperations = setOf(
                    "component-process-${request.operation.name.lowercase()}-service"
                ),
                message = "component_process_${request.operation.name.lowercase()}_unsupported"
            )
        }
        if (plan.verdict == EngineResultStatus.FAIL || plan.verdict == EngineResultStatus.UNSUPPORTED) {
            return plan
        }
        val target = plan.targets.singleOrNull()
            ?: return plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = "component_service_requires_exactly_one_target"
            )
        if (
            target.sameProcess ||
            target.processName != identity.effectiveGuestProcessName ||
            target.operation != request.operation
        ) {
            return plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = "component_service_process_authority_mismatch"
            )
        }
        return plan.copy(
            targets = listOf(
                target.copy(
                    processSlot = identity.processSlot,
                    operationLease = null
                )
            )
        )
    }

    private fun attachServiceOperationLease(
        instanceId: String,
        plan: VirtualServiceDispatchPlan,
        callerPid: Int,
        leaseRequested: Boolean
    ): VirtualServiceDispatchPlan {
        if (!leaseRequested) return plan
        if (plan.verdict == EngineResultStatus.FAIL || plan.verdict == EngineResultStatus.UNSUPPORTED) {
            return plan
        }
        val target = plan.targets.singleOrNull()
            ?: return plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = "service_operation_lease_requires_exactly_one_target"
            )
        val runtime = registry.get(instanceId)
            ?: return plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = "service_operation_lease_runtime_not_found"
            )
        val lease = runCatching {
            serviceOperationLeases.issue(
                runtime = runtime,
                callingPid = callerPid,
                operation = plan.operation,
                component = target.serviceClassName,
                processSlot = target.processSlot
            )
        }.getOrElse { error ->
            return plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = "service_operation_lease_issue_failed:${error.javaClass.simpleName}"
            )
        }
        val authorization = serviceOperationLeases.authorize(lease, callerPid)
        if (!authorization.accepted) {
            serviceOperationLeases.abort(lease, callerPid)
            return plan.copy(
                verdict = EngineResultStatus.FAIL,
                targets = emptyList(),
                message = "service_operation_lease_authorization_failed:${authorization.reason}"
            )
        }
        recordServiceLeaseEvidence(lease, authorization, "plan-authorized")
        return plan.copy(targets = listOf(target.copy(operationLease = lease)))
    }

    private fun VirtualServiceOperationResult.matches(
        lease: EngineServiceOperationLeaseIdentity
    ): Boolean = instanceId == lease.instanceId &&
        operation == lease.operationType &&
        serviceClassName == lease.component &&
        processSlot == lease.processSlot

    private fun recordServiceLeaseEvidence(
        lease: EngineServiceOperationLeaseIdentity,
        decision: EngineServiceOperationLeaseDecision,
        phase: String
    ) {
        registry.registerOperationEvidence(
            lease.instanceId,
            EngineOperationEvidence(
                component = "service-lease",
                operation = lease.operation.lowercase().replace('_', '-'),
                verdict = if (decision.accepted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
                entries = mapOf(
                    "phase" to phase,
                    "runtimeEpoch" to lease.runtimeEpoch.toString(),
                    "engineSessionId" to lease.engineSessionId,
                    "processSlot" to lease.processSlot,
                    "processId" to lease.processId.toString(),
                    "serviceClassName" to lease.component,
                    "leaseState" to decision.state.name,
                    "idempotent" to decision.idempotent.toString(),
                    "reason" to decision.reason
                )
            )
        )
    }

    override fun queryServiceRuntimeState(instanceId: String): Bundle = primaryRuntimeAuthorizedBundle(instanceId) {
        serviceService.queryServiceRuntimeState(instanceId).toIpcBundle()
    }

    override fun registerServiceConnection(
        instanceId: String,
        operationLease: Bundle,
        connectionToken: IBinder
    ): Bundle = runtimeAuthorizedBundle(instanceId) {
        val lease = operationLease.toServiceOperationLeaseIdentityOrNull()
            ?: return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REGISTER_OPERATION,
                "invalid_service_connection_lease"
            )
        if (lease.instanceId != instanceId || lease.operationType != VirtualServiceOperation.BIND) {
            return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REGISTER_OPERATION,
                "service_connection_lease_mismatch"
            )
        }
        val processIdentity = processControlPlane.authorize(instanceId, callingPid()).identity
            ?: return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REGISTER_OPERATION,
                "service_connection_process_unauthorized"
            )
        if (
            lease.runtimeEpoch != processIdentity.runtimeEpoch ||
            lease.engineSessionId != processIdentity.engineSessionId ||
            lease.processSlot != processIdentity.processSlot ||
            lease.processId != processIdentity.processId
        ) {
            return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REGISTER_OPERATION,
                "service_connection_process_binding_mismatch"
            )
        }
        val leaseDecision = serviceOperationLeases.claimForConnection(lease, callingPid())
        if (!leaseDecision.accepted) {
            return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REGISTER_OPERATION,
                "service_connection_lease_unclaimable:${leaseDecision.reason}"
            )
        }
        val binding = EngineServiceConnectionBindingRecord(
            instanceId = processIdentity.instanceId,
            runtimeEpoch = processIdentity.runtimeEpoch,
            engineSessionId = processIdentity.engineSessionId,
            processSlot = processIdentity.processSlot,
            processId = processIdentity.processId,
            component = lease.component
        )
        val registered = serviceConnections.register(
            binding,
            connectionToken,
            ::recordReleasedServiceConnections
        )
        if (!registered.accepted) {
            serviceOperationLeases.abort(lease, callingPid())
        }
        EngineServiceConnectionOperationResult(
            operation = SERVICE_CONNECTION_REGISTER_OPERATION,
            accepted = registered.accepted,
            idempotent = registered.idempotent,
            bindings = listOfNotNull(registered.binding).takeIf { registered.accepted }.orEmpty(),
            reason = registered.reason
        ).toServiceConnectionIpcBundle(ipcBundleFactory)
    }

    override fun queryServiceConnection(
        instanceId: String,
        connectionToken: IBinder
    ): Bundle = runtimeAuthorizedBundle(instanceId) {
        val identity = processControlPlane.authorize(instanceId, callingPid()).identity
            ?: return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_QUERY_OPERATION,
                "service_connection_process_unauthorized"
            )
        val queried = serviceConnections.query(connectionToken)
        if (queried.bindings.any { binding -> !binding.matches(identity) }) {
            return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_QUERY_OPERATION,
                "service_connection_owner_mismatch"
            )
        }
        EngineServiceConnectionOperationResult(
            operation = SERVICE_CONNECTION_QUERY_OPERATION,
            accepted = queried.found,
            idempotent = false,
            bindings = queried.bindings,
            reason = queried.reason
        ).toServiceConnectionIpcBundle(ipcBundleFactory)
    }

    override fun removeServiceConnectionBinding(
        instanceId: String,
        binding: Bundle,
        connectionToken: IBinder
    ): Bundle = runtimeAuthorizedBundle(instanceId) {
        val decoded = binding.toServiceConnectionBindingOrNull()
            ?: return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REMOVE_BINDING_OPERATION,
                "invalid_service_connection_binding"
            )
        val identity = processControlPlane.authorize(instanceId, callingPid()).identity
            ?: return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REMOVE_BINDING_OPERATION,
                "service_connection_process_unauthorized"
            )
        if (!decoded.matches(identity)) {
            return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REMOVE_BINDING_OPERATION,
                "service_connection_owner_mismatch"
            )
        }
        val removed = serviceConnections.unbind(connectionToken, decoded)
        EngineServiceConnectionOperationResult(
            operation = SERVICE_CONNECTION_REMOVE_BINDING_OPERATION,
            accepted = removed.removed,
            idempotent = false,
            bindings = removed.bindings,
            reason = removed.reason
        ).toServiceConnectionIpcBundle(ipcBundleFactory)
    }

    override fun removeServiceConnection(
        instanceId: String,
        connectionToken: IBinder
    ): Bundle = runtimeAuthorizedBundle(instanceId) {
        val identity = processControlPlane.authorize(instanceId, callingPid()).identity
            ?: return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REMOVE_OPERATION,
                "service_connection_process_unauthorized"
            )
        val queried = serviceConnections.query(connectionToken)
        if (queried.bindings.any { binding -> !binding.matches(identity) }) {
            return@runtimeAuthorizedBundle serviceConnectionRejected(
                SERVICE_CONNECTION_REMOVE_OPERATION,
                "service_connection_owner_mismatch"
            )
        }
        val removed = serviceConnections.removeAll(connectionToken)
        EngineServiceConnectionOperationResult(
            operation = SERVICE_CONNECTION_REMOVE_OPERATION,
            accepted = removed.removed,
            idempotent = false,
            bindings = removed.bindings,
            reason = removed.reason
        ).toServiceConnectionIpcBundle(ipcBundleFactory)
    }

    private fun serviceConnectionRejected(operation: String, reason: String): Bundle =
        EngineServiceConnectionOperationResult(
            operation = operation,
            accepted = false,
            idempotent = false,
            bindings = emptyList(),
            reason = reason
        ).toServiceConnectionIpcBundle(ipcBundleFactory)

    private fun componentProcessRejected(operation: String, instanceId: String, reason: String): Bundle =
        EngineComponentProcessOperationResult(
            operation = operation,
            instanceId = instanceId,
            accepted = false,
            idempotent = false,
            alreadyRunning = false,
            launchTicket = null,
            processState = null,
            reason = reason
        ).toComponentProcessIpcBundle(ipcBundleFactory)

    private fun EngineServiceConnectionBindingRecord.matches(
        identity: EngineProcessClientIdentity
    ): Boolean = instanceId == identity.instanceId &&
        runtimeEpoch == identity.runtimeEpoch &&
        engineSessionId == identity.engineSessionId &&
        processSlot == identity.processSlot &&
        processId == identity.processId

    private fun recordReleasedServiceConnections(
        bindings: List<EngineServiceConnectionBindingRecord>
    ) {
        bindings.distinctBy { binding ->
            listOf(
                binding.instanceId,
                binding.runtimeEpoch.toString(),
                binding.engineSessionId,
                binding.component
            )
        }.forEach { binding ->
            val runtime = registry.get(binding.instanceId) ?: return@forEach
            if (
                runtime.runtimeEpoch != binding.runtimeEpoch ||
                runtime.engineSessionId != binding.engineSessionId
            ) {
                return@forEach
            }
            serviceService.recordServiceDispatch(
                binding.instanceId,
                VirtualServiceOperationResult(
                    instanceId = binding.instanceId,
                    operation = VirtualServiceOperation.UNBIND,
                    serviceClassName = binding.component,
                    action = null,
                    verdict = EngineResultStatus.PARTIAL,
                    reason = "service_connection_authority_released",
                    unbound = true,
                    destroyed = false,
                    processSlot = binding.processSlot,
                    activeBindCount = serviceConnections.activeBindingCount(binding),
                    message = "engine_service_connection_authority_released"
                )
            )
        }
    }

    override fun planBroadcast(instanceId: String, request: Bundle): Bundle = runtimeAuthorizedBundle(instanceId) {
        val decodedRequest = request.toBroadcastPlanRequestOrNull()
            ?: return@runtimeAuthorizedBundle invalidRequestBundle(instanceId, "invalid_broadcast_plan_request")
        broadcastService.planBroadcast(instanceId, decodedRequest).toIpcBundle()
    }

    override fun recordBroadcastDispatch(instanceId: String, result: Bundle): Boolean {
        if (!isRuntimeAuthorized(instanceId)) return false
        val decodedResult = result.toBroadcastOperationResultOrNull() ?: return false
        if (decodedResult.instanceId != instanceId) return false
        return broadcastService.recordBroadcastDispatch(instanceId, decodedResult)
    }

    override fun queryBroadcastRuntimeState(instanceId: String): Bundle = runtimeAuthorizedBundle(instanceId) {
        broadcastService.queryBroadcastRuntimeState(instanceId).toIpcBundle()
    }

    override fun recordOperationEvidence(instanceId: String, evidence: Bundle): Boolean {
        if (!isRuntimeAuthorized(instanceId)) return false
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
        isManagementAuthorized() && instanceId.isNotBlank() && runtimeEpoch > 0L &&
            registry.stopIfEpoch(instanceId, runtimeEpoch)

    private fun managementAuthorizedBundle(block: () -> Bundle): Bundle =
        when {
            !isAuthorized() -> unauthorizedBundle("caller_uid_mismatch")
            !isManagementAuthorized() -> unauthorizedBundle("management_caller_process_mismatch")
            else -> block()
        }

    private fun authorizedBundle(block: () -> Bundle): Bundle =
        if (isAuthorized()) block() else unauthorizedBundle("caller_uid_mismatch")

    private fun unauthorizedBundle(reason: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun packageEnabledStateBundle(
        instanceId: String,
        request: Bundle,
        target: EnginePackageEnabledStateTarget,
        mutation: Boolean,
        operation: String,
        block: (EnginePackageEnabledStateIpcRequest) -> VirtualPackageEnabledStateResult
    ): Bundle = runtimeAuthorizedBundle(instanceId) {
        val decoded = request.toPackageEnabledStateRequestOrNull(target, mutation)
            ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                instanceId,
                "invalid_package_enabled_state_${operation}_request"
            )
        if (decoded.identity.instanceId != instanceId) {
            return@runtimeAuthorizedBundle invalidRequestBundle(
                instanceId,
                "package_enabled_state_instance_identity_mismatch"
            )
        }
        val liveAuthority = processControlPlane.authorize(instanceId, callingPid())
        if (
            !liveAuthority.allowed ||
            liveAuthority.identity != decoded.identity ||
            decoded.identity.processId != callingPid()
        ) {
            return@runtimeAuthorizedBundle invalidRequestBundle(
                instanceId,
                "package_enabled_state_runtime_identity_mismatch"
            )
        }
        val result = runCatching { block(decoded) }.getOrNull()
            ?: return@runtimeAuthorizedBundle invalidRequestBundle(
                instanceId,
                "package_enabled_state_service_failure:$operation"
            )
        if (!result.matchesPackageStateRequest(decoded)) {
            return@runtimeAuthorizedBundle invalidRequestBundle(
                instanceId,
                "invalid_package_enabled_state_service_response:$operation"
            )
        }
        result.copy(authorityIdentity = decoded.identity)
            .toPackageEnabledStateIpcBundle(decoded.identity)
    }

    private fun VirtualPackageEnabledStateResult.matchesPackageStateRequest(
        request: EnginePackageEnabledStateIpcRequest
    ): Boolean {
        if (instanceId != request.identity.instanceId || target != request.target) return false
        return when (target) {
            EnginePackageEnabledStateTarget.APPLICATION -> componentType == null && className == null
            EnginePackageEnabledStateTarget.COMPONENT -> {
                if (componentType == null || className == null) {
                    verdict != EngineResultStatus.PASS && componentType == null && className == null
                } else {
                    componentType == request.componentType &&
                        normalizeVirtualComponentClassName(
                            registry.get(instanceId)?.originPackageName.orEmpty(),
                            className
                        ) == className
                }
            }
        }
    }

    private fun runtimeAuthorizedBundle(instanceId: String, block: () -> Bundle): Bundle {
        if (!isAuthorized()) {
            return Bundle().apply {
                putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
                putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
                putString(EngineRuntimeIpcContract.KEY_REASON, "caller_uid_mismatch")
            }
        }
        val callerPid = callingPid()
        val primaryDecision = processControlPlane.authorize(instanceId, callerPid)
        val authority = authorizeServiceCaller(instanceId, callerPid, primaryDecision)
        return if (authority != null) block() else {
            missingLiveAuthorityBundle(
                instanceId,
                primaryDecision.reason.ifBlank { "runtime_process_not_authoritative" }
            )
        }
    }

    private fun primaryRuntimeAuthorizedBundle(instanceId: String, block: () -> Bundle): Bundle {
        if (!isAuthorized()) {
            return Bundle().apply {
                putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
                putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
                putString(EngineRuntimeIpcContract.KEY_REASON, "caller_uid_mismatch")
            }
        }
        val authority = processControlPlane.authorize(instanceId, callingPid())
        return if (authority.allowed) block() else {
            missingLiveAuthorityBundle(instanceId, authority.reason)
        }
    }

    private fun authorizeServiceCaller(
        instanceId: String,
        callerPid: Int,
        primaryDecision: EngineProcessAuthorityDecision = processControlPlane.authorize(instanceId, callerPid)
    ): EngineServiceCallerAuthority? {
        if (primaryDecision.allowed && primaryDecision.identity != null) {
            return EngineServiceCallerAuthority.Primary(primaryDecision.identity)
        }
        val component = componentProcessAuthority?.authorizeCaller(
            instanceId = instanceId,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid),
            callingProcessStartTicks = callingProcessStartTicks(callerPid)
        ) ?: return null
        return EngineServiceCallerAuthority.Component(component)
    }

    private fun EngineServiceCallerAuthority.toProviderRouteProcessIdentity():
        EngineProviderRouteProcessIdentity? {
        return when (this) {
        is EngineServiceCallerAuthority.Primary -> {
            val runtime = registry.get(identity.instanceId) ?: return null
            if (!runtime.matchesPrimaryProcessIdentity(identity, identity.processId)) return null
            EngineProviderRouteProcessIdentity(
                instanceId = identity.instanceId,
                runtimeEpoch = identity.runtimeEpoch,
                engineSessionId = identity.engineSessionId,
                processSlot = identity.processSlot,
                processId = identity.processId,
                effectiveGuestProcessName = runtime.packageSnapshot.processName
                    .toServiceEffectiveGuestProcessName(runtime.originPackageName)
            )
        }
        is EngineServiceCallerAuthority.Component -> {
            val runtime = registry.get(identity.instanceId) ?: return null
            if (!runtime.matchesComponentProcessIdentity(identity, identity.processId)) return null
            EngineProviderRouteProcessIdentity(
                instanceId = identity.instanceId,
                runtimeEpoch = identity.runtimeEpoch,
                engineSessionId = identity.engineSessionId,
                processSlot = identity.processSlot,
                processId = identity.processId,
                effectiveGuestProcessName = identity.effectiveGuestProcessName,
                processEpoch = identity.processEpoch,
                clientSessionId = identity.clientSessionId,
                processStartTicks = identity.processStartTicks
            )
        }
        }
    }

    private fun resolveProviderRouteTarget(
        targetInstanceId: String,
        guestAuthority: String
    ): EngineProviderRouteTargetResolution? {
        val runtime = registry.get(targetInstanceId) ?: return null
        if (runtime.state == VirtualRuntimeState.STOPPED || runtime.state == VirtualRuntimeState.DEAD) {
            return null
        }
        val provider = packageService.queryProviderByAuthority(targetInstanceId, guestAuthority)
            ?: return null
        val applicationProcessName = runtime.packageSnapshot.processName
            .toServiceEffectiveGuestProcessName(runtime.originPackageName)
        val providerProcessName = provider.processName
            .toServiceEffectiveGuestProcessName(runtime.originPackageName)
        if (providerProcessName == applicationProcessName) {
            val processId = runtime.processId ?: return null
            val authority = processControlPlane.authorize(targetInstanceId, processId)
            val identity = authority.identity
            if (!authority.allowed || identity == null) return null
            val targetIdentity = EngineServiceCallerAuthority.Primary(identity)
                .toProviderRouteProcessIdentity() ?: return null
            return EngineProviderRouteTargetResolution(
                binding = targetIdentity.toProviderTargetBinding(),
                liveIdentity = targetIdentity,
                launchTicket = null
            )
        }
        val componentAuthority = componentProcessAuthority ?: return null
        val prepared = componentAuthority.prepare(targetInstanceId, providerProcessName)
            .takeIf { result -> result.accepted && result.instanceId == targetInstanceId }
            ?: return null
        if (prepared.alreadyRunning) {
            val state = prepared.processState?.takeIf { processState ->
                processState.live && processState.effectiveGuestProcessName == providerProcessName
            } ?: return null
            val identity = componentAuthority.authorizeCaller(
                instanceId = targetInstanceId,
                callingPid = state.processId,
                callingProcessName = callingProcessName(state.processId),
                callingProcessStartTicks = callingProcessStartTicks(state.processId)
            ) ?: return null
            val targetIdentity = EngineServiceCallerAuthority.Component(identity)
                .toProviderRouteProcessIdentity()
                ?.takeIf { resolved ->
                    resolved.processSlot == state.processSlot &&
                        resolved.effectiveGuestProcessName == providerProcessName
                } ?: return null
            return EngineProviderRouteTargetResolution(
                binding = targetIdentity.toProviderTargetBinding(),
                liveIdentity = targetIdentity,
                launchTicket = null
            )
        }
        val ticket = prepared.launchTicket?.takeIf { launch ->
            launch.instanceId == targetInstanceId &&
                launch.effectiveGuestProcessName == providerProcessName &&
                launch.processSlot.isNotBlank()
        } ?: return null
        return EngineProviderRouteTargetResolution(
            binding = EngineProviderRouteTargetBinding(
                instanceId = runtime.instanceId,
                runtimeEpoch = runtime.runtimeEpoch,
                engineSessionId = runtime.engineSessionId,
                processSlot = ticket.processSlot,
                effectiveGuestProcessName = providerProcessName
            ),
            liveIdentity = null,
            launchTicket = ticket
        )
    }

    private fun currentProviderRouteTargetBinding(
        token: String?,
        targetInstanceId: String
    ): EngineProviderRouteTargetBinding? = providerRouteTokens.targetBinding(token)
        ?.takeIf { binding ->
            binding.instanceId == targetInstanceId &&
                registry.get(binding.instanceId)?.let { runtime ->
                    isCurrentProviderRouteTargetBindingForAnyProvider(runtime, binding)
                } == true
        }

    private fun consumeProviderRouteToken(
        request: EngineProviderRouteTokenConsumeRequest,
        targetBinding: EngineProviderRouteTargetBinding
    ): EngineProviderRouteTokenConsumeResult {
        val callerIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean = { callerIdentity ->
            request.providerCallingPid == callerIdentity.processId &&
                isCurrentProviderRouteIdentity(callerIdentity)
        }
        val targetIdentity = authorizeServiceCaller(targetBinding.instanceId, callingPid())
            ?.toProviderRouteProcessIdentity()
            ?: providerRouteTargetObservation(targetBinding)?.let { observation ->
                attachPendingProviderTarget(
                    request = request,
                    targetBinding = targetBinding,
                    observation = observation
                )
            }
            ?: return EngineProviderRouteTokenConsumeResult(
                EngineProviderRouteTokenConsumeStatus.TARGET_GENERATION_MISMATCH
            )
        return providerRouteTokens.consume(
            token = request.token,
            authoritativeTargetIdentity = targetIdentity,
            authority = request.guestAuthority,
            operation = request.operation,
            callerGenerationIsCurrent = callerIsCurrent
        )
    }

    private fun authorizeProviderRouteDispatch(
        token: String?,
        targetBinding: EngineProviderRouteTargetBinding,
        authority: String,
        operation: String
    ): EngineProviderRouteTokenConsumeResult {
        val callerIsCurrent: (EngineProviderRouteProcessIdentity) -> Boolean =
            ::isCurrentProviderRouteIdentity
        val targetIdentity = authorizeServiceCaller(targetBinding.instanceId, callingPid())
            ?.toProviderRouteProcessIdentity()
        if (targetIdentity != null) {
            return providerRouteTokens.authorizeConsumedDispatch(
                token = token,
                authoritativeTargetIdentity = targetIdentity,
                authority = authority,
                operation = operation,
                callerGenerationIsCurrent = callerIsCurrent
            )
        }
        providerRouteTargetObservation(targetBinding)?.let { observation ->
            return providerRouteTokens.authorizePendingTargetDispatch(
                token = token,
                authoritativeTarget = observation,
                authority = authority,
                operation = operation,
                callerGenerationIsCurrent = callerIsCurrent
            )
        }
        return EngineProviderRouteTokenConsumeResult(
            EngineProviderRouteTokenConsumeStatus.TARGET_GENERATION_MISMATCH
        )
    }

    private fun attachPendingProviderTarget(
        request: EngineProviderRouteTokenConsumeRequest,
        targetBinding: EngineProviderRouteTargetBinding,
        observation: EngineProviderRouteTargetProcessObservation
    ): EngineProviderRouteProcessIdentity? {
        val authority = componentProcessAuthority ?: return null
        val ticket = providerRouteTokens.targetLaunchTicket(request.token)
            ?.takeIf { launch ->
                launch.instanceId == targetBinding.instanceId &&
                    launch.effectiveGuestProcessName == targetBinding.effectiveGuestProcessName &&
                    launch.processSlot == targetBinding.processSlot
            } ?: return null
        val attached = authority.attach(
            attachCapability = ticket.attachCapability,
            clientToken = request.targetProcessToken,
            callingPid = observation.processId,
            callingProcessName = targetBinding.processSlot,
            callingProcessStartTicks = observation.processStartTicks
        )
        val attachedState = attached.processState
        if (
            !attached.accepted || attachedState == null || !attachedState.live ||
            attachedState.instanceId != targetBinding.instanceId ||
            attachedState.effectiveGuestProcessName != targetBinding.effectiveGuestProcessName ||
            attachedState.processSlot != targetBinding.processSlot ||
            attachedState.processId != observation.processId
        ) {
            return null
        }
        val identity = authority.authorizeCaller(
            instanceId = targetBinding.instanceId,
            callingPid = observation.processId,
            callingProcessName = targetBinding.processSlot,
            callingProcessStartTicks = observation.processStartTicks
        ) ?: return null
        return EngineServiceCallerAuthority.Component(identity)
            .toProviderRouteProcessIdentity()
            ?.takeIf { it.toProviderTargetBinding() == targetBinding }
    }

    private fun providerRouteTargetObservation(
        binding: EngineProviderRouteTargetBinding
    ): EngineProviderRouteTargetProcessObservation? {
        val runtime = registry.get(binding.instanceId) ?: return null
        val applicationProcessName = runtime.packageSnapshot.processName
            .toServiceEffectiveGuestProcessName(runtime.originPackageName)
        if (binding.effectiveGuestProcessName == applicationProcessName) return null
        val targetPid = callingPid()
        if (callingProcessName(targetPid) != binding.processSlot) return null
        val processStartTicks = callingProcessStartTicks(targetPid)?.takeIf { it > 0L }
            ?: return null
        return EngineProviderRouteTargetProcessObservation(
            binding = binding,
            processId = targetPid,
            processStartTicks = processStartTicks
        )
    }

    private fun boundProviderTargetForCallingProcess(
        runtime: VirtualInstanceRuntime
    ): EngineProviderRouteBoundTargetProcess? {
        val targetPid = callingPid()
        val processSlot = callingProcessName(targetPid) ?: return null
        val processStartTicks = callingProcessStartTicks(targetPid) ?: return null
        return providerRouteTokens.boundTargetForProcess(
            instanceId = runtime.instanceId,
            processId = targetPid,
            processSlot = processSlot,
            processStartTicks = processStartTicks
        )?.takeIf { target ->
            isCurrentProviderRouteTargetBindingForAnyProvider(runtime, target.binding)
        }
    }

    private fun isCurrentProviderRouteTargetBindingForAnyProvider(
        runtime: VirtualInstanceRuntime,
        binding: EngineProviderRouteTargetBinding
    ): Boolean {
        if (
            runtime.state == VirtualRuntimeState.STOPPED || runtime.state == VirtualRuntimeState.DEAD ||
            runtime.instanceId != binding.instanceId ||
            runtime.runtimeEpoch != binding.runtimeEpoch ||
            runtime.engineSessionId != binding.engineSessionId
        ) {
            return false
        }
        val applicationProcessName = runtime.packageSnapshot.processName
            .toServiceEffectiveGuestProcessName(runtime.originPackageName)
        val processDeclared = runtime.packageSnapshot.providers.any { provider ->
            provider.processName.toServiceEffectiveGuestProcessName(runtime.originPackageName) ==
                binding.effectiveGuestProcessName
        }
        if (!processDeclared) return false
        return if (binding.effectiveGuestProcessName == applicationProcessName) {
            binding.processSlot == runtime.processSlot
        } else {
            binding.processSlot != runtime.processSlot &&
                binding.processSlot.substringBefore(':') == runtime.hostPackageName
        }
    }

    private fun VirtualInstanceRuntime.toProviderRouteProcessRuntime(
        target: EngineProviderRouteBoundTargetProcess
    ): VirtualInstanceRuntime = copy(
        processSlot = target.binding.processSlot,
        processId = target.processId,
        processName = target.binding.processSlot,
        packageSnapshot = packageSnapshot.copy(
            processName = target.binding.effectiveGuestProcessName
        )
    )

    private fun authoritativeComponentProcessIdentity(
        runtime: VirtualInstanceRuntime
    ): EngineComponentProcessClientIdentity? {
        val callerPid = callingPid()
        val identity = componentProcessAuthority?.authorizeCaller(
            instanceId = runtime.instanceId,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid),
            callingProcessStartTicks = callingProcessStartTicks(callerPid)
        ) ?: return null
        return identity.takeIf { runtime.matchesComponentProcessIdentity(it, callerPid) }
    }

    private fun VirtualInstanceRuntime.toComponentProcessRuntime(
        identity: EngineComponentProcessClientIdentity
    ): VirtualInstanceRuntime = copy(
        processSlot = identity.processSlot,
        processId = identity.processId,
        processName = identity.processSlot,
        packageSnapshot = packageSnapshot.copy(
            processName = identity.effectiveGuestProcessName
        )
    )

    private fun EngineProviderRouteProcessIdentity.toProviderTargetBinding() =
        EngineProviderRouteTargetBinding(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            effectiveGuestProcessName = effectiveGuestProcessName
        )

    private fun isCurrentProviderRouteIdentity(
        expected: EngineProviderRouteProcessIdentity
    ): Boolean {
        val authority = authorizeServiceCaller(expected.instanceId, expected.processId) ?: return false
        return authority.toProviderRouteProcessIdentity() == expected
    }

    private fun providerRouteTokenFailure(status: String, message: String): Bundle =
        EngineProviderRouteTokenAuthorityResult(
            status = status,
            route = null,
            message = message
        ).toProviderRouteTokenIpcBundle(ipcBundleFactory)

    private fun isLiveUriPermissionCaller(
        callerInstanceId: String,
        callerPid: Int
    ): Boolean {
        val runtime = registry.get(callerInstanceId) ?: return false
        val primary = processControlPlane.authorize(callerInstanceId, callerPid)
        if (
            primary.allowed &&
            primary.identity?.let { identity ->
                runtime.matchesPrimaryProcessIdentity(identity, callerPid)
            } == true
        ) {
            return true
        }
        val component = componentProcessAuthority?.authorizeCaller(
            instanceId = callerInstanceId,
            callingPid = callerPid,
            callingProcessName = callingProcessName(callerPid),
            callingProcessStartTicks = callingProcessStartTicks(callerPid)
        ) ?: return false
        return runtime.matchesComponentProcessIdentity(component, callerPid)
    }

    private fun findLiveUriPermissionTargets(
        targetProcessId: Int
    ): List<EngineUriPermissionCheckTarget> {
        val runtimes = registry.list()
        val targets = mutableListOf<EngineUriPermissionCheckTarget>()
        runtimes.asSequence()
            .filter { runtime -> runtime.processId == targetProcessId }
            .forEach { runtime ->
                val authority = processControlPlane.authorize(runtime.instanceId, targetProcessId)
                val identity = authority.identity
                if (
                    authority.allowed && identity != null &&
                    runtime.matchesPrimaryProcessIdentity(identity, targetProcessId)
                ) {
                    targets += EngineUriPermissionCheckTarget(
                        runtime = runtime,
                        processSlot = identity.processSlot,
                        processId = identity.processId,
                        processName = runtime.processName,
                        reason = "uri_permission_target_primary_runtime_authorized"
                    )
                }
            }

        val componentAuthority = componentProcessAuthority ?: return targets
        val targetProcessName = callingProcessName(targetProcessId)
        val targetProcessStartTicks = callingProcessStartTicks(targetProcessId)
        runtimes.forEach { runtime ->
            val identity = componentAuthority.authorizeCaller(
                instanceId = runtime.instanceId,
                callingPid = targetProcessId,
                callingProcessName = targetProcessName,
                callingProcessStartTicks = targetProcessStartTicks
            ) ?: return@forEach
            if (runtime.matchesComponentProcessIdentity(identity, targetProcessId)) {
                targets += EngineUriPermissionCheckTarget(
                    runtime = runtime,
                    processSlot = identity.processSlot,
                    processId = identity.processId,
                    processName = identity.processSlot,
                    reason = "uri_permission_target_component_runtime_authorized"
                )
            }
        }
        return targets
    }

    private fun VirtualInstanceRuntime.matchesPrimaryProcessIdentity(
        identity: EngineProcessClientIdentity,
        expectedProcessId: Int
    ): Boolean =
        instanceId == identity.instanceId &&
            runtimeEpoch == identity.runtimeEpoch &&
            engineSessionId == identity.engineSessionId &&
            processSlot == identity.processSlot &&
            processId == expectedProcessId &&
            identity.processId == expectedProcessId

    private fun VirtualInstanceRuntime.matchesComponentProcessIdentity(
        identity: EngineComponentProcessClientIdentity,
        expectedProcessId: Int
    ): Boolean =
        instanceId == identity.instanceId &&
            runtimeEpoch == identity.runtimeEpoch &&
            engineSessionId == identity.engineSessionId &&
            identity.processId == expectedProcessId

    private fun isAuthorized(): Boolean = callingUid() == hostUid

    private fun isManagementAuthorized(): Boolean =
        isAuthorized() && callingProcessName(callingPid()) == hostPackageName

    private fun isAuthoritativeRuntimeStreamCaller(
        runtime: VirtualInstanceRuntime,
        request: Bundle
    ): Boolean {
        val callerPid = callingPid()
        val callerProcessName = callingProcessName(callerPid) ?: return false
        if (callerProcessName == runtime.hostPackageName) return true

        val primaryAuthority = processControlPlane.authorize(runtime.instanceId, callerPid)
        val primaryIdentity = primaryAuthority.identity
        if (
            primaryAuthority.allowed && primaryIdentity != null &&
            runtime.matchesPrimaryProcessIdentity(primaryIdentity, callerPid) &&
            callerProcessName == primaryIdentity.processSlot
        ) {
            return true
        }

        val componentIdentity = componentProcessAuthority?.authorizeCaller(
            instanceId = runtime.instanceId,
            callingPid = callerPid,
            callingProcessName = callerProcessName,
            callingProcessStartTicks = callingProcessStartTicks(callerPid)
        )
        if (
            componentIdentity != null &&
            runtime.matchesComponentProcessIdentity(componentIdentity, callerPid) &&
            callerProcessName == componentIdentity.processSlot
        ) {
            return true
        }

        val requestedEpoch = request.getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH)
        val requestedSession = request.getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID)
        val requestedSlot = request.getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT)
        if (
            requestedEpoch != runtime.runtimeEpoch ||
            requestedSession != runtime.engineSessionId ||
            requestedSlot.isNullOrBlank() ||
            callerProcessName != requestedSlot
        ) {
            return false
        }
        if (requestedSlot == runtime.processSlot) return true

        val attachCapability = request.getString(EngineRuntimeIpcContract.KEY_ATTACH_CAPABILITY)
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val pending = componentProcessLaunchCapabilities.query(attachCapability).identity ?: return false
        return pending.instanceId == runtime.instanceId &&
            pending.runtimeEpoch == runtime.runtimeEpoch &&
            pending.engineSessionId == runtime.engineSessionId &&
            pending.processSlot == requestedSlot
    }

    private fun isRuntimeAuthorized(instanceId: String): Boolean =
        isAuthorized() && authorizeServiceCaller(instanceId, callingPid()) != null

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

    private fun missingProcessRuntimeBundle(processId: Int, reason: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, false)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, "")
        putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, processId)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun invalidRequestBundle(instanceId: String, reason: String): Bundle = Bundle().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_VERDICT, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_MESSAGE, reason)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun activityLaunchAuthorizationBundle(result: EngineActivityLaunchAuthorization): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, result.accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, result.accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, result.idempotent)
        putString(EngineRuntimeIpcContract.KEY_REASON, result.reason)
    }

    private fun processIdentityOrNull(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        processId: Int
    ): EngineProcessClientIdentity? = runCatching {
        EngineProcessClientIdentity(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot,
            processId = processId
        )
    }.getOrNull()

    private fun invalidProcessAttachBundle(
        operation: EngineProcessAttachOperation,
        instanceId: String,
        reason: String
    ): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, false)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, false)
        putString(EngineRuntimeIpcContract.KEY_ATTACH_OPERATION, operation.name)
        putString(
            EngineRuntimeIpcContract.KEY_RESTORE_CAPABILITY_STATUS,
            if (operation == EngineProcessAttachOperation.PROCESS_RESTARTED) {
                EngineRecentsRestoreCapabilityStatus.REJECTED.name
            } else {
                EngineRecentsRestoreCapabilityStatus.NOT_REQUESTED.name
            }
        )
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun EngineRecentsRestoreCapabilityResult.toIpcBundle(): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, accepted)
        putString(EngineRuntimeIpcContract.KEY_RESTORE_CAPABILITY_STATUS, status.name)
        putString(EngineRuntimeIpcContract.KEY_RESTORE_ACTIVITY_ID, restoreActivityId)
        putBoolean(
            EngineRuntimeIpcContract.KEY_PERSISTED_SYSTEM_ACTIVITY_TOKEN_REUSED,
            reusedPersistedSystemActivityToken
        )
        identity?.let {
            putString(EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN, it.capabilityToken)
            putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, it.instanceId)
            putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, it.runtimeEpoch)
            putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, it.engineSessionId)
            putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, it.processSlot)
            putString(
                EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME,
                it.proxyActivityClassName
            )
            putString(
                EngineRuntimeIpcContract.KEY_GUEST_ACTIVITY_CLASS_NAME,
                it.guestActivityClassName
            )
        }
        putString(
            EngineRuntimeIpcContract.KEY_STATUS,
            if (accepted) EngineResultStatus.PASS.name else EngineResultStatus.FAIL.name
        )
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun invalidRestoreCapabilityBundle(reason: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, false)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, false)
        putString(
            EngineRuntimeIpcContract.KEY_RESTORE_CAPABILITY_STATUS,
            EngineRecentsRestoreCapabilityStatus.REJECTED.name
        )
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun invalidProcessPrewarmBundle(instanceId: String, reason: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, false)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, false)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun EngineProcessPrewarmResult.toIpcBundle(instanceId: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, runtimeState != null)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, idempotent)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE, runtimeState?.name)
        putString(
            EngineRuntimeIpcContract.KEY_STATUS,
            if (accepted) EngineResultStatus.PASS.name else EngineResultStatus.FAIL.name
        )
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun invalidProcessAbandonBundle(instanceId: String, reason: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, false)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, false)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun EngineProcessAbandonResult.toIpcBundle(instanceId: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, runtimeState != null)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, idempotent)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE, runtimeState?.name)
        putInt(EngineRuntimeIpcContract.KEY_REVOKED_CAPABILITY_COUNT, revokedCapabilityCount)
        putString(
            EngineRuntimeIpcContract.KEY_STATUS,
            if (accepted) EngineResultStatus.PASS.name else EngineResultStatus.FAIL.name
        )
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun EngineProcessClientAttachResult.toIpcBundle(): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, runtimeState != null)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, idempotent)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, liveAuthority)
        putBoolean(
            EngineRuntimeIpcContract.KEY_CLIENT_TOKEN_ALIVE,
            accepted && liveAuthority
        )
        putString(EngineRuntimeIpcContract.KEY_ATTACH_OPERATION, operation.name)
        putString(
            EngineRuntimeIpcContract.KEY_RESTORE_CAPABILITY_STATUS,
            restoreCapabilityStatus.name
        )
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, identity?.instanceId)
        identity?.let {
            putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, it.runtimeEpoch)
            putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, it.engineSessionId)
            putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, it.processSlot)
            putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, it.processId)
        }
        putString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE, runtimeState?.name)
        putString(
            EngineRuntimeIpcContract.KEY_STATUS,
            if (accepted) EngineResultStatus.PASS.name else EngineResultStatus.FAIL.name
        )
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun missingLiveAuthorityBundle(instanceId: String, reason: String): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, false)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, false)
        putString(EngineRuntimeIpcContract.KEY_STATUS, EngineResultStatus.FAIL.name)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun VirtualInstanceRuntime.toIpcBundle(
        liveAuthority: Boolean,
        reason: String? = null
    ): Bundle = Bundle().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_FOUND, true)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, liveAuthority)
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
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

    private fun EngineUriPermissionCheckTarget.toIpcBundle(): Bundle =
        runtime.toIpcBundle(liveAuthority = true, reason = reason).apply {
            putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
            putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, processId)
            putString(EngineRuntimeIpcContract.KEY_PROCESS_NAME, processName)
        }
}

internal data class EngineRuntimeServiceCandidate(
    val service: IEngineRuntimeService,
    val serverGenerationId: String,
    val serverProcessId: Int,
    val serverProcessName: String
)

class EngineRuntimeIpcClient(
    private val context: Context
) {
    internal fun connect(): EngineRuntimeServiceCandidate? {
        val hostContext = context.applicationContext ?: context
        val authority = EngineRuntimeIpcContract.authority(hostContext.packageName)
        val uri = Uri.Builder().scheme("content").authority(authority).build()
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val response = callProvider(hostContext, uri)
            val candidate = response?.toEngineRuntimeServiceCandidateOrNull(
                expectedProcessName = EngineRuntimeIpcContract.engineProcessName(hostContext.packageName),
                clientProcessId = Process.myPid()
            )
            if (candidate != null) return candidate
            if (attempt + 1 < CONNECT_ATTEMPTS) SystemClock.sleep(CONNECT_RETRY_DELAY_MS)
        }
        return null
    }

    private fun callProvider(hostContext: Context, uri: Uri): Bundle? {
        val client = runCatching {
            hostContext.contentResolver.acquireUnstableContentProviderClient(uri)
        }.getOrNull() ?: return null
        return try {
            runCatching {
                client.call(EngineRuntimeIpcContract.METHOD_GET_BINDER, null, null)
            }.getOrNull()
        } finally {
            runCatching { client.close() }
        }
    }

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY_MS = 50L
    }
}

internal fun Bundle.toEngineRuntimeServiceCandidateOrNull(
    expectedProcessName: String,
    clientProcessId: Int? = null
): EngineRuntimeServiceCandidate? = runCatching {
    require(expectedProcessName.isNotBlank())
    val generationId = getString(EngineRuntimeIpcContract.KEY_SERVER_GENERATION_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val processId = getInt(EngineRuntimeIpcContract.KEY_SERVER_PROCESS_ID).takeIf { it > 0 }
        ?: return null
    if (clientProcessId != null && processId == clientProcessId) return null
    val processName = getString(EngineRuntimeIpcContract.KEY_SERVER_PROCESS_NAME)
        ?.takeIf { it == expectedProcessName }
        ?: return null
    val binder = getBinder(EngineRuntimeIpcContract.KEY_BINDER)
        ?.takeIf(IBinder::isBinderAlive)
        ?: return null
    val service = IEngineRuntimeService.Stub.asInterface(binder) ?: return null
    val endpointGeneration = service.getServerGenerationId().takeIf { it.isNotBlank() }
        ?: return null
    require(endpointGeneration == generationId) { "engine server generation handshake mismatch" }
    EngineRuntimeServiceCandidate(
        service = service,
        serverGenerationId = generationId,
        serverProcessId = processId,
        serverProcessName = processName
    )
}.getOrNull()

internal class EngineRuntimeServiceConnection(
    private val connector: () -> EngineRuntimeServiceCandidate?
) {
    private data class LinkedService(
        val generation: Long,
        val serverGenerationId: String,
        val service: IEngineRuntimeService,
        val binder: IBinder,
        val deathRecipient: IBinder.DeathRecipient
    )

    @Volatile
    private var current: LinkedService? = null
    private var nextGeneration: Long = 0L

    fun active(): IEngineRuntimeService? {
        val snapshot = current
        if (snapshot != null && snapshot.binder.isBinderAlive) return snapshot.service
        return reconnect()
    }

    @Synchronized
    fun reconnect(): IEngineRuntimeService? {
        val snapshot = current
        if (snapshot != null && snapshot.binder.isBinderAlive) return snapshot.service

        current = null
        snapshot?.let(::unlink)

        val connected = connector() ?: return null
        val binder = connected.service.asBinder()
        if (!binder.isBinderAlive || connected.serverGenerationId.isBlank()) return null
        val generation = ++nextGeneration
        val recipient = IBinder.DeathRecipient {
            clearIfCurrent(generation, binder)
        }
        val candidate = LinkedService(
            generation = generation,
            serverGenerationId = connected.serverGenerationId,
            service = connected.service,
            binder = binder,
            deathRecipient = recipient
        )

        // Publish before linkToDeath so a synchronous death callback can revoke
        // exactly this generation instead of leaving a dead candidate cached.
        current = candidate
        val linked = runCatching {
            binder.linkToDeath(recipient, 0)
            true
        }.getOrDefault(false)
        if (!linked || !binder.isBinderAlive || current !== candidate) {
            if (current === candidate) current = null
            if (linked) unlink(candidate)
            return null
        }
        return connected.service
    }

    @Synchronized
    internal fun activeServerGenerationId(): String? {
        active() ?: return null
        return current?.serverGenerationId
    }

    @Synchronized
    private fun clearIfCurrent(generation: Long, binder: IBinder) {
        val snapshot = current ?: return
        if (snapshot.generation == generation && snapshot.binder === binder) {
            current = null
        }
    }

    private fun unlink(linked: LinkedService) {
        runCatching { linked.binder.unlinkToDeath(linked.deathRecipient, 0) }
    }
}

object EngineRuntimeIpcClients {
    @Volatile
    private var applicationContext: Context? = null
    private val connection = EngineRuntimeServiceConnection {
        applicationContext?.let { EngineRuntimeIpcClient(it).connect() }
    }
    private val authoritativeRuntimeCache = ConcurrentHashMap<AuthoritativeRuntimeCacheKey, VirtualInstanceRuntime>()

    fun install(context: Context): Boolean {
        applicationContext = context.applicationContext ?: context
        return reconnect() != null
    }

    fun isConnected(): Boolean = activeService() != null

    internal fun engineInstallOrRefreshPackage(originPackageName: String): EngineRemoteResult? =
        invokeEngineResult { service -> service.engineInstallOrRefreshPackage(originPackageName) }

    internal fun engineCreateInstance(originPackageName: String): EngineRemoteResult? =
        invokeEngineResult { service -> service.engineCreateInstance(originPackageName) }

    internal fun engineCreateInstance(
        request: com.multiapp.core.model.engine.CreateInstanceRequest
    ): EngineRemoteResult? =
        invokeEngineResult { service -> service.engineCreateInstanceWithMetadata(request.toEngineIpcBundle()) }

    internal fun engineLaunchInstance(
        request: com.multiapp.core.model.engine.LaunchInstanceRequest
    ): EngineRemoteResult? =
        invokeEngineResult { service -> service.engineLaunchInstance(request.toEngineIpcBundle()) }

    internal fun engineStopInstance(instanceId: String): EngineRemoteResult? =
        invokeEngineResult { service -> service.engineStopInstance(instanceId) }

    internal fun engineDeleteInstance(instanceId: String): EngineRemoteResult? =
        invokeEngineResult { service -> service.engineDeleteInstance(instanceId) }

    fun engineQueryRuntimeState(
        instanceId: String,
        runtimeEpoch: Long? = null,
        engineSessionId: String? = null,
        processSlot: String? = null,
        componentAttachCapability: String? = null
    ): com.multiapp.core.model.engine.VirtualInstanceRuntime? {
        val cacheKey = AuthoritativeRuntimeCacheKey.create(
            instanceId = instanceId,
            runtimeEpoch = runtimeEpoch,
            engineSessionId = engineSessionId,
            processSlot = processSlot
        )
        cacheKey?.let(authoritativeRuntimeCache::get)?.let { cached -> return cached }
        val active = activeService() ?: return null
        val request = Bundle().apply {
            runtimeEpoch?.takeIf { it > 0L }?.let {
                putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, it)
            }
            engineSessionId?.takeIf { it.isNotBlank() }?.let {
                putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, it)
            }
            processSlot?.takeIf { it.isNotBlank() }?.let {
                putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, it)
            }
            componentAttachCapability?.takeIf { it.isNotBlank() }?.let {
                putString(EngineRuntimeIpcContract.KEY_ATTACH_CAPABILITY, it)
            }
        }
        val descriptor = runCatching {
            active.engineOpenRuntimeState(instanceId, request)
        }.getOrNull() ?: return null
        val runtime = EngineAuthoritativeRuntimeStream.read(descriptor)?.takeIf { decoded ->
            decoded.instanceId == instanceId &&
                (runtimeEpoch == null || decoded.runtimeEpoch == runtimeEpoch) &&
                (engineSessionId == null || decoded.engineSessionId == engineSessionId)
        } ?: return null
        if (cacheKey != null) {
            authoritativeRuntimeCache.keys
                .filter { key -> key.instanceId == instanceId && key != cacheKey }
                .forEach(authoritativeRuntimeCache::remove)
            authoritativeRuntimeCache[cacheKey] = runtime
        }
        return runtime
    }

    private data class AuthoritativeRuntimeCacheKey(
        val instanceId: String,
        val runtimeEpoch: Long,
        val engineSessionId: String,
        val processSlot: String
    ) {
        companion object {
            fun create(
                instanceId: String,
                runtimeEpoch: Long?,
                engineSessionId: String?,
                processSlot: String?
            ): AuthoritativeRuntimeCacheKey? {
                if (
                    instanceId.isBlank() || runtimeEpoch == null || runtimeEpoch <= 0L ||
                    engineSessionId.isNullOrBlank() || processSlot.isNullOrBlank()
                ) {
                    return null
                }
                return AuthoritativeRuntimeCacheKey(
                    instanceId = instanceId,
                    runtimeEpoch = runtimeEpoch,
                    engineSessionId = engineSessionId,
                    processSlot = processSlot
                )
            }
        }
    }

    internal fun engineExportEvidence(
        instanceId: String
    ): com.multiapp.core.model.engine.EngineEvidenceReport? {
        val active = activeService() ?: return null
        val response = runCatching { active.engineExportEvidence(instanceId) }.getOrNull() ?: return null
        return response.toEngineEvidenceOrNull()
    }

    fun attachClient(
        identity: EngineProcessClientIdentity,
        clientToken: IBinder
    ): EngineProcessClientAttachResult? = registerProcessClient(
        operation = EngineProcessAttachOperation.ATTACH_CLIENT,
        identity = identity,
        clientToken = clientToken
    )

    fun processRestarted(
        identity: EngineProcessClientIdentity,
        clientToken: IBinder
    ): EngineProcessClientAttachResult? = registerProcessClient(
        operation = EngineProcessAttachOperation.PROCESS_RESTARTED,
        identity = identity,
        clientToken = clientToken
    )

    fun markProcessPrewarmed(identity: EngineProcessClientIdentity): EngineRuntimeForegroundAck? {
        val response = runCatching {
            activeService()?.markProcessPrewarmed(
                identity.instanceId,
                identity.runtimeEpoch,
                identity.engineSessionId,
                identity.processSlot,
                identity.processId
            )
        }.getOrNull() ?: return null
        return response.toForegroundAck()
    }

    fun abandonProcessClient(
        identity: EngineProcessClientIdentity,
        reason: String
    ): EngineRuntimeForegroundAck? {
        if (reason.isBlank()) return null
        val response = runCatching {
            activeService()?.abandonProcessClient(
                identity.instanceId,
                identity.runtimeEpoch,
                identity.engineSessionId,
                identity.processSlot,
                identity.processId,
                reason
            )
        }.getOrNull() ?: return null
        return response.toForegroundAck()
    }

    fun issueRecentsRestoreCapability(
        identity: EngineProcessClientIdentity,
        restoreActivityId: String
    ): EngineRecentsRestoreCapabilityResult? {
        val response = runCatching {
            activeService()?.issueRecentsRestoreCapability(
                identity.instanceId,
                identity.runtimeEpoch,
                identity.engineSessionId,
                identity.processSlot,
                identity.processId,
                restoreActivityId
            )
        }.getOrNull() ?: return null
        val launchIdentity = response.toActivityLaunchIdentityOrNull()
        return EngineRecentsRestoreCapabilityResult(
            accepted = response.getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED),
            status = response.getString(EngineRuntimeIpcContract.KEY_RESTORE_CAPABILITY_STATUS)
                ?.let {
                    runCatching { enumValueOf<EngineRecentsRestoreCapabilityStatus>(it) }.getOrNull()
                }
                ?: EngineRecentsRestoreCapabilityStatus.REJECTED,
            identity = launchIdentity,
            restoreActivityId = response.getString(EngineRuntimeIpcContract.KEY_RESTORE_ACTIVITY_ID),
            reusedPersistedSystemActivityToken = response.getBoolean(
                EngineRuntimeIpcContract.KEY_PERSISTED_SYSTEM_ACTIVITY_TOKEN_REUSED
            ),
            reason = response.getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty()
        )
    }

    fun queryRuntime(instanceId: String): EngineRuntimeIpcSnapshot? {
        val response = runCatching { activeService()?.queryRuntime(instanceId) }.getOrNull() ?: return null
        return response.toRuntimeIpcSnapshotOrNull()
            ?.takeIf { snapshot -> snapshot.instanceId == instanceId || !snapshot.found }
    }

    fun queryRuntimeByProcessId(processId: Int): EngineRuntimeIpcSnapshot? {
        if (processId <= 0) return null
        val response = runCatching { activeService()?.queryRuntimeByProcessId(processId) }.getOrNull()
            ?: return null
        return response.toRuntimeIpcSnapshotOrNull()
            ?.takeIf { snapshot -> snapshot.found && snapshot.processId == processId }
    }

    fun resolveUriPermissionCheckTarget(
        callerInstanceId: String,
        targetProcessId: Int
    ): EngineRuntimeIpcSnapshot? {
        if (callerInstanceId.isBlank() || targetProcessId <= 0) return null
        val response = runCatching {
            activeService()?.resolveUriPermissionCheckTarget(callerInstanceId, targetProcessId)
        }.getOrNull() ?: return null
        return response.toRuntimeIpcSnapshotOrNull()
            ?.takeIf { snapshot ->
                snapshot.found && snapshot.liveAuthority &&
                    snapshot.instanceId.isNotBlank() &&
                    snapshot.processId == targetProcessId &&
                    snapshot.runtimeEpoch > 0L &&
                    !snapshot.engineSessionId.isNullOrBlank() &&
                    !snapshot.processSlot.isNullOrBlank()
            }
    }

    fun prepareComponentProcess(
        instanceId: String,
        guestProcessName: String
    ): EngineComponentProcessOperationResult? {
        if (instanceId.isBlank() || guestProcessName.isBlank()) return null
        val response = runCatching {
            activeService()?.prepareComponentProcess(instanceId, guestProcessName)
        }.getOrNull() ?: return null
        return response.toComponentProcessOperationResultOrNull()
            ?.takeIf { result ->
                result.operation == COMPONENT_PROCESS_PREPARE_OPERATION &&
                    result.instanceId == instanceId
            }
    }

    fun attachComponentProcessClient(
        launchTicket: EngineComponentProcessLaunchTicket,
        clientToken: IBinder
    ): EngineComponentProcessOperationResult? {
        val response = runCatching {
            activeService()?.attachComponentProcessClient(
                launchTicket.attachCapability,
                clientToken
            )
        }.getOrNull() ?: return null
        return response.toComponentProcessOperationResultOrNull()
            ?.takeIf { result ->
                result.operation == COMPONENT_PROCESS_ATTACH_OPERATION &&
                    (!result.accepted || result.processState?.let { state ->
                        state.instanceId == launchTicket.instanceId &&
                            state.effectiveGuestProcessName == launchTicket.effectiveGuestProcessName &&
                            state.processSlot == launchTicket.processSlot
                    } == true)
            }
    }

    fun queryComponentProcessClient(
        instanceId: String,
        guestProcessName: String
    ): EngineComponentProcessOperationResult? {
        if (instanceId.isBlank() || guestProcessName.isBlank()) return null
        val response = runCatching {
            activeService()?.queryComponentProcessClient(instanceId, guestProcessName)
        }.getOrNull() ?: return null
        return response.toComponentProcessOperationResultOrNull()
            ?.takeIf { result ->
                result.operation == COMPONENT_PROCESS_QUERY_OPERATION &&
                    result.instanceId == instanceId
            }
    }

    fun queryCallingComponentProcess(
        instanceId: String
    ): EngineComponentProcessOperationResult? {
        if (instanceId.isBlank()) return null
        val response = runCatching {
            activeService()?.queryCallingComponentProcess(instanceId)
        }.getOrNull() ?: return null
        return response.toComponentProcessOperationResultOrNull()
            ?.takeIf { result ->
                result.operation == COMPONENT_PROCESS_QUERY_OPERATION &&
                    result.instanceId == instanceId &&
                    (!result.accepted || result.processState?.instanceId == instanceId)
            }
    }

    fun queryApplicationEnabledState(
        identity: EngineProcessClientIdentity
    ): VirtualPackageEnabledStateResult? = invokePackageEnabledState(
        identity = identity,
        target = EnginePackageEnabledStateTarget.APPLICATION
    ) { service, request ->
        service.queryApplicationEnabledState(identity.instanceId, request)
    }

    fun setApplicationEnabledState(
        identity: EngineProcessClientIdentity,
        newState: Int
    ): VirtualPackageEnabledStateResult? = invokePackageEnabledState(
        identity = identity,
        target = EnginePackageEnabledStateTarget.APPLICATION,
        newState = newState
    ) { service, request ->
        service.setApplicationEnabledState(identity.instanceId, request)
    }

    fun queryComponentEnabledState(
        identity: EngineProcessClientIdentity,
        type: VirtualPackageComponentType,
        className: String
    ): VirtualPackageEnabledStateResult? = invokePackageEnabledState(
        identity = identity,
        target = EnginePackageEnabledStateTarget.COMPONENT,
        componentType = type,
        className = className
    ) { service, request ->
        service.queryComponentEnabledState(identity.instanceId, request)
    }

    fun setComponentEnabledState(
        identity: EngineProcessClientIdentity,
        type: VirtualPackageComponentType,
        className: String,
        newState: Int
    ): VirtualPackageEnabledStateResult? = invokePackageEnabledState(
        identity = identity,
        target = EnginePackageEnabledStateTarget.COMPONENT,
        componentType = type,
        className = className,
        newState = newState
    ) { service, request ->
        service.setComponentEnabledState(identity.instanceId, request)
    }

    private fun invokePackageEnabledState(
        identity: EngineProcessClientIdentity,
        target: EnginePackageEnabledStateTarget,
        componentType: VirtualPackageComponentType? = null,
        className: String? = null,
        newState: Int? = null,
        call: (IEngineRuntimeService, Bundle) -> Bundle
    ): VirtualPackageEnabledStateResult? {
        val service = activeService() ?: return null
        val request = identity.toPackageEnabledStateRequestBundle(
            target = target,
            componentType = componentType,
            className = className,
            newState = newState
        )
        val response = runCatching { call(service, request) }.getOrNull() ?: return null
        return response.toPackageEnabledStateResultOrNull()?.takeIf { result ->
            result.instanceId == identity.instanceId &&
                result.authorityIdentity == identity &&
                result.target == target &&
                when (target) {
                    EnginePackageEnabledStateTarget.APPLICATION ->
                        result.componentType == null && result.className == null
                    EnginePackageEnabledStateTarget.COMPONENT ->
                        result.componentType == componentType && result.className != null
                }
        }
    }

    private fun Bundle.toRuntimeIpcSnapshotOrNull(): EngineRuntimeIpcSnapshot? = runCatching {
        val found = getBoolean(EngineRuntimeIpcContract.KEY_FOUND)
        val instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty()
        check(!found || instanceId.isNotBlank())
        EngineRuntimeIpcSnapshot(
            found = found,
            instanceId = instanceId,
            processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT),
            proxySlot = getString(EngineRuntimeIpcContract.KEY_PROXY_SLOT),
            runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
            engineSessionId = getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID),
            evidenceSessionId = getString(EngineRuntimeIpcContract.KEY_EVIDENCE_SESSION_ID),
            runtimeState = getString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE),
            processId = if (containsKey(EngineRuntimeIpcContract.KEY_PROCESS_ID)) {
                getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID).takeIf { it > 0 }
            } else {
                null
            },
            processName = getString(EngineRuntimeIpcContract.KEY_PROCESS_NAME),
            reason = getString(EngineRuntimeIpcContract.KEY_REASON),
            liveAuthority = getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY)
        )
    }.getOrNull()

    fun authorizeActivityLaunch(identity: EngineActivityLaunchIdentity): EngineActivityLaunchAuthorization? {
        val response = runCatching {
            activeService()?.authorizeActivityLaunch(
                identity.capabilityToken,
                identity.instanceId,
                identity.runtimeEpoch,
                identity.engineSessionId,
                identity.processSlot,
                identity.proxyActivityClassName,
                identity.guestActivityClassName
            )
        }.getOrNull() ?: return null
        return EngineActivityLaunchAuthorization(
            accepted = response.getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED),
            idempotent = response.getBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT),
            reason = response.getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty()
        )
    }

    fun acknowledgeActivityResumed(
        instanceId: String,
        runtimeEpoch: Long,
        engineSessionId: String,
        processSlot: String,
        capabilityToken: String
    ): EngineRuntimeForegroundAck? {
        val response = runCatching {
            activeService()?.acknowledgeActivityResumed(
                instanceId,
                runtimeEpoch,
                engineSessionId,
                processSlot,
                capabilityToken
            )
        }.getOrNull() ?: return null
        return response.toForegroundAck()
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

    fun allocateActivityLaunch(
        request: VirtualActivityLaunchAllocationRequest
    ): VirtualActivityLaunchAllocation? {
        val response = runCatching {
            activeService()?.allocateActivityLaunch(request.instanceId, request.toIpcBundle())
        }.getOrNull() ?: return null
        return response.toActivityLaunchAllocationOrNull()
            ?.takeIf { allocation -> allocation.request == request }
    }

    fun releaseActivityLaunch(allocation: VirtualActivityLaunchAllocation): Boolean? {
        if (!allocation.accepted) return false
        val active = activeService() ?: return null
        return runCatching {
            active.releaseActivityLaunch(allocation.request.instanceId, allocation.toIpcBundle())
        }.getOrNull()
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
    ): VirtualActivityOperationResult = VirtualActivityOperationResult(
        instanceId = instanceId.ifBlank { "invalid" },
        operation = "sync-task-state",
        verdict = EngineResultStatus.FAIL,
        message = "direct_activity_task_snapshot_sync_disabled"
    )

    internal fun queryProxyActivitySlot(
        key: ProxyActivitySlotKey
    ): VirtualProxyActivitySlotOperationResult? {
        if (!key.isValidProxyActivitySlotIpcKey()) return null
        val response = runCatching {
            activeService()?.queryProxyActivitySlot(key.toProxyActivitySlotQueryIpcBundle())
        }.getOrNull() ?: return null
        return response.toProxyActivitySlotIpcResultOrNull()
            ?.takeIf {
                it.isValidProxyActivitySlotIpcResult(
                    key,
                    PROXY_ACTIVITY_SLOT_QUERY_OPERATION
                )
            }
    }

    internal fun reserveProxyActivitySlot(
        key: ProxyActivitySlotKey,
        candidateProxyActivityClassNames: List<String>
    ): VirtualProxyActivitySlotOperationResult? {
        if (!key.isValidProxyActivitySlotIpcKey() ||
            !candidateProxyActivityClassNames.isValidProxyActivitySlotCandidates()
        ) {
            return null
        }
        val response = runCatching {
            activeService()?.reserveProxyActivitySlot(
                key.toProxyActivitySlotReserveIpcBundle(candidateProxyActivityClassNames)
            )
        }.getOrNull() ?: return null
        return response.toProxyActivitySlotIpcResultOrNull()
            ?.takeIf { result ->
                result.isValidProxyActivitySlotIpcResult(
                    key,
                    PROXY_ACTIVITY_SLOT_RESERVE_OPERATION
                ) &&
                    (result.verdict == EngineResultStatus.FAIL ||
                        result.proxyActivityClassName in candidateProxyActivityClassNames)
            }
    }

    internal fun compareAndSetProxyActivitySlot(
        key: ProxyActivitySlotKey,
        expectedProxyActivityClassName: String?,
        newProxyActivityClassName: String?
    ): VirtualProxyActivitySlotOperationResult? {
        if (!key.isValidProxyActivitySlotIpcKey() ||
            !expectedProxyActivityClassName.isValidNullableProxyActivitySlotClassName() ||
            !newProxyActivityClassName.isValidNullableProxyActivitySlotClassName()
        ) {
            return null
        }
        val response = runCatching {
            activeService()?.compareAndSetProxyActivitySlot(
                key.toProxyActivitySlotCompareAndSetIpcBundle(
                    expectedProxyActivityClassName,
                    newProxyActivityClassName
                )
            )
        }.getOrNull() ?: return null
        return response.toProxyActivitySlotIpcResultOrNull()
            ?.takeIf { result ->
                result.isValidProxyActivitySlotIpcResult(
                    key,
                    PROXY_ACTIVITY_SLOT_COMPARE_AND_SET_OPERATION
                ) &&
                    (result.verdict == EngineResultStatus.FAIL ||
                        result.proxyActivityClassName == newProxyActivityClassName)
            }
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

    fun issueProviderRouteToken(
        callerInstanceId: String,
        request: EngineProviderRouteTokenIssueRequest
    ): EngineProviderRouteTokenAuthorityResult? {
        if (callerInstanceId.isBlank()) return null
        val response = runCatching {
            activeService()?.issueProviderRouteToken(
                callerInstanceId,
                request.toProviderRouteTokenIpcBundle()
            )
        }.getOrNull() ?: return null
        return response.toProviderRouteTokenAuthorityResultOrNull()
            ?.takeIf { result ->
                !result.accepted || result.route?.let { route ->
                    route.callerInstanceId == callerInstanceId &&
                        route.targetInstanceId == request.targetInstanceId &&
                        route.authority == request.guestAuthority &&
                        route.operation == normalizeProviderRouteOperation(request.operation)
                } == true
            }
    }

    fun validateAndConsumeProviderRouteToken(
        request: EngineProviderRouteTokenConsumeRequest
    ): EngineProviderRouteTokenAuthorityResult? {
        val response = runCatching {
            activeService()?.validateAndConsumeProviderRouteToken(
                request.targetInstanceId,
                request.toProviderRouteTokenIpcBundle()
            )
        }.getOrNull() ?: return null
        return response.toProviderRouteTokenAuthorityResultOrNull()
            ?.takeIf { result ->
                !result.accepted || result.route?.let { route ->
                    route.token == request.token &&
                        route.targetInstanceId == request.targetInstanceId &&
                        route.authority == request.guestAuthority &&
                        route.operation == normalizeProviderRouteOperation(request.operation) &&
                        route.processSlot == request.expectedProcessSlot
                } == true
            }
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

    fun checkProviderUriPermissionForCaller(
        callerInstanceId: String,
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        if (callerInstanceId.isBlank() || targetInstanceId.isBlank()) return null
        val response = runCatching {
            activeService()?.checkProviderUriPermissionForCaller(
                callerInstanceId,
                targetInstanceId,
                request.toIpcBundle()
            )
        }.getOrNull() ?: return null
        return response.toProviderUriGrantResultOrNull()
            ?.takeIf { result ->
                result.targetInstanceId == targetInstanceId &&
                    result.guestAuthority == request.guestAuthority &&
                    result.encodedPath == request.encodedPath &&
                    result.modeFlags == request.modeFlags
            }
    }

    fun takePersistableProviderUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        val response = runCatching {
            activeService()?.takePersistableProviderUriPermission(
                targetInstanceId,
                request.toIpcBundle()
            )
        }.getOrNull() ?: return null
        return response.toProviderUriGrantResultOrNull()
    }

    fun releasePersistableProviderUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        val response = runCatching {
            activeService()?.releasePersistableProviderUriPermission(
                targetInstanceId,
                request.toIpcBundle()
            )
        }.getOrNull() ?: return null
        return response.toProviderUriGrantResultOrNull()
    }

    fun checkPermission(
        instanceId: String,
        permissionName: String
    ): VirtualPermissionCheckResult? {
        val response = runCatching {
            activeService()?.checkPermission(instanceId, permissionName)
        }.getOrNull() ?: return null
        return response.toPermissionCheckResultOrNull()
    }

    fun queryPermissionRuntimeState(instanceId: String): VirtualPermissionRuntimeState? {
        val response = runCatching {
            activeService()?.queryPermissionRuntimeState(instanceId)
        }.getOrNull() ?: return null
        return response.toPermissionRuntimeStateOrNull()
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

    internal fun registerServiceConnection(
        instanceId: String,
        operationLease: EngineServiceOperationLeaseIdentity,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? {
        if (operationLease.instanceId != instanceId ||
            operationLease.operationType != VirtualServiceOperation.BIND
        ) {
            return null
        }
        val response = runCatching {
            activeService()?.registerServiceConnection(
                instanceId,
                operationLease.toIpcBundle(),
                connectionToken
            )
        }.getOrNull() ?: return null
        return response.toServiceConnectionOperationResultOrNull()
            ?.takeIf { result ->
                result.operation == SERVICE_CONNECTION_REGISTER_OPERATION &&
                    result.bindings.all { binding ->
                        binding.instanceId == instanceId &&
                            binding.runtimeEpoch == operationLease.runtimeEpoch &&
                            binding.engineSessionId == operationLease.engineSessionId &&
                            binding.processSlot == operationLease.processSlot &&
                            binding.processId == operationLease.processId &&
                            binding.component == operationLease.component
                    } &&
                    (!result.accepted || result.bindings.size == 1)
            }
    }

    internal fun queryServiceConnection(
        instanceId: String,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? = runCatching {
        activeService()?.queryServiceConnection(instanceId, connectionToken)
    }.getOrNull()
        ?.toServiceConnectionOperationResultOrNull()
        ?.takeIf { result ->
            result.operation == SERVICE_CONNECTION_QUERY_OPERATION &&
                result.bindings.all { it.instanceId == instanceId }
        }

    internal fun removeServiceConnectionBinding(
        instanceId: String,
        binding: EngineServiceConnectionBindingRecord,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? {
        if (binding.instanceId != instanceId) return null
        return runCatching {
            activeService()?.removeServiceConnectionBinding(
                instanceId,
                binding.toServiceConnectionIpcBundle(),
                connectionToken
            )
        }.getOrNull()
            ?.toServiceConnectionOperationResultOrNull()
            ?.takeIf { result ->
                result.operation == SERVICE_CONNECTION_REMOVE_BINDING_OPERATION &&
                    result.bindings.all { it == binding }
            }
    }

    internal fun removeServiceConnection(
        instanceId: String,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? = runCatching {
        activeService()?.removeServiceConnection(instanceId, connectionToken)
    }.getOrNull()
        ?.toServiceConnectionOperationResultOrNull()
        ?.takeIf { result ->
            result.operation == SERVICE_CONNECTION_REMOVE_OPERATION &&
                result.bindings.all { it.instanceId == instanceId }
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

    private fun registerProcessClient(
        operation: EngineProcessAttachOperation,
        identity: EngineProcessClientIdentity,
        clientToken: IBinder
    ): EngineProcessClientAttachResult? {
        val active = activeService() ?: return null
        val response = runCatching {
            when (operation) {
                EngineProcessAttachOperation.ATTACH_CLIENT -> active.attachClient(
                    identity.instanceId,
                    identity.runtimeEpoch,
                    identity.engineSessionId,
                    identity.processSlot,
                    identity.processId,
                    clientToken
                )
                EngineProcessAttachOperation.PROCESS_RESTARTED -> active.processRestarted(
                    identity.instanceId,
                    identity.runtimeEpoch,
                    identity.engineSessionId,
                    identity.processSlot,
                    identity.processId,
                    clientToken
                )
            }
        }.getOrNull() ?: return null
        return EngineProcessClientAttachResult(
            accepted = response.getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED),
            idempotent = response.getBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT),
            liveAuthority = response.getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY),
            operation = response.getString(EngineRuntimeIpcContract.KEY_ATTACH_OPERATION)
                ?.let { runCatching { enumValueOf<EngineProcessAttachOperation>(it) }.getOrNull() }
                ?: operation,
            identity = response.toProcessClientIdentityOrNull(),
            runtimeState = response.getString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE)
                ?.let { runCatching { enumValueOf<VirtualRuntimeState>(it) }.getOrNull() },
            reason = response.getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty(),
            restoreCapabilityStatus = response.getString(
                EngineRuntimeIpcContract.KEY_RESTORE_CAPABILITY_STATUS
            )?.let {
                runCatching { enumValueOf<EngineRecentsRestoreCapabilityStatus>(it) }.getOrNull()
            } ?: EngineRecentsRestoreCapabilityStatus.NOT_REQUESTED
        )
    }

    private fun activeService(): IEngineRuntimeService? {
        return connection.active()
    }

    private fun invokeEngineResult(
        operation: (IEngineRuntimeService) -> Bundle
    ): EngineRemoteResult? {
        val active = activeService() ?: return null
        val response = runCatching { operation(active) }.getOrNull() ?: return null
        return response.toEngineRemoteResultOrNull()
    }

    private fun reconnect(): IEngineRuntimeService? {
        return connection.reconnect()
    }
}

private fun Bundle.toForegroundAck() = EngineRuntimeForegroundAck(
    accepted = getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED),
    idempotent = getBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT),
    state = getString(EngineRuntimeIpcContract.KEY_RUNTIME_STATE),
    reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty()
)

private fun Bundle.toProcessClientIdentityOrNull(): EngineProcessClientIdentity? = runCatching {
    EngineProcessClientIdentity(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
        engineSessionId = getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        processId = getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID)
    )
}.getOrNull()

private fun Bundle.toActivityLaunchIdentityOrNull(): EngineActivityLaunchIdentity? = runCatching {
    EngineActivityLaunchIdentity(
        capabilityToken = getString(EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN).orEmpty(),
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
        engineSessionId = getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        proxyActivityClassName = getString(
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME
        ).orEmpty(),
        guestActivityClassName = getString(
            EngineRuntimeIpcContract.KEY_GUEST_ACTIVITY_CLASS_NAME
        ).orEmpty()
    )
}.getOrNull()

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

internal data class EngineProxyActivitySlotReserveIpcRequest(
    val key: ProxyActivitySlotKey,
    val candidateProxyActivityClassNames: List<String>
)

internal data class EngineProxyActivitySlotCompareAndSetIpcRequest(
    val key: ProxyActivitySlotKey,
    val expectedProxyActivityClassName: String?,
    val newProxyActivityClassName: String?
)

internal fun ProxyActivitySlotKey.toProxyActivitySlotQueryIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = toProxyActivitySlotKeyIpcBundle(bundleFactory)

internal fun ProxyActivitySlotKey.toProxyActivitySlotReserveIpcBundle(
    candidateProxyActivityClassNames: List<String>,
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = toProxyActivitySlotKeyIpcBundle(bundleFactory).apply {
    putStringArrayList(
        EngineRuntimeIpcContract.KEY_CANDIDATE_PROXY_ACTIVITY_CLASS_NAMES,
        ArrayList(candidateProxyActivityClassNames)
    )
}

internal fun ProxyActivitySlotKey.toProxyActivitySlotCompareAndSetIpcBundle(
    expectedProxyActivityClassName: String?,
    newProxyActivityClassName: String?,
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = toProxyActivitySlotKeyIpcBundle(bundleFactory).apply {
    putString(
        EngineRuntimeIpcContract.KEY_EXPECTED_PROXY_ACTIVITY_CLASS_NAME,
        expectedProxyActivityClassName
    )
    putString(
        EngineRuntimeIpcContract.KEY_NEW_PROXY_ACTIVITY_CLASS_NAME,
        newProxyActivityClassName
    )
}

internal fun Bundle.toProxyActivitySlotQueryIpcKeyOrNull(): ProxyActivitySlotKey? =
    decodeProxyActivitySlotKeyOrNull(PROXY_ACTIVITY_SLOT_KEY_FIELDS)

internal fun Bundle.toProxyActivitySlotReserveIpcRequestOrNull():
    EngineProxyActivitySlotReserveIpcRequest? = runCatching {
        val expectedFields = PROXY_ACTIVITY_SLOT_KEY_FIELDS +
            EngineRuntimeIpcContract.KEY_CANDIDATE_PROXY_ACTIVITY_CLASS_NAMES
        val key = decodeProxyActivitySlotKeyOrNull(expectedFields) ?: return@runCatching null
        val candidates = getStringArrayList(
            EngineRuntimeIpcContract.KEY_CANDIDATE_PROXY_ACTIVITY_CLASS_NAMES
        )?.toList()?.takeIf { it.isValidProxyActivitySlotCandidates() }
            ?: return@runCatching null
        EngineProxyActivitySlotReserveIpcRequest(key, candidates)
    }.getOrNull()

internal fun Bundle.toProxyActivitySlotCompareAndSetIpcRequestOrNull():
    EngineProxyActivitySlotCompareAndSetIpcRequest? = runCatching {
        val expectedFields = PROXY_ACTIVITY_SLOT_KEY_FIELDS + setOf(
            EngineRuntimeIpcContract.KEY_EXPECTED_PROXY_ACTIVITY_CLASS_NAME,
            EngineRuntimeIpcContract.KEY_NEW_PROXY_ACTIVITY_CLASS_NAME
        )
        val key = decodeProxyActivitySlotKeyOrNull(expectedFields) ?: return@runCatching null
        val expected = getString(EngineRuntimeIpcContract.KEY_EXPECTED_PROXY_ACTIVITY_CLASS_NAME)
        val new = getString(EngineRuntimeIpcContract.KEY_NEW_PROXY_ACTIVITY_CLASS_NAME)
        if (!expected.isValidNullableProxyActivitySlotClassName() ||
            !new.isValidNullableProxyActivitySlotClassName()
        ) {
            return@runCatching null
        }
        EngineProxyActivitySlotCompareAndSetIpcRequest(key, expected, new)
    }.getOrNull()

internal fun VirtualProxyActivitySlotOperationResult.toProxyActivitySlotResultIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_OPERATION, operation)
        putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
        putBundle(
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_SLOT_KEY,
            key.toProxyActivitySlotKeyIpcBundle(bundleFactory)
        )
        putString(
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME,
            proxyActivityClassName
        )
        putBoolean(EngineRuntimeIpcContract.KEY_MATCHED, matched)
        putInt(EngineRuntimeIpcContract.KEY_REMOVED_COUNT, removedCount)
        putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
    }

internal fun Bundle.toProxyActivitySlotIpcResultOrNull(): VirtualProxyActivitySlotOperationResult? =
    runCatching {
        val expectedFields = setOf(
            EngineRuntimeIpcContract.KEY_INSTANCE_ID,
            EngineRuntimeIpcContract.KEY_OPERATION,
            EngineRuntimeIpcContract.KEY_VERDICT,
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_SLOT_KEY,
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME,
            EngineRuntimeIpcContract.KEY_MATCHED,
            EngineRuntimeIpcContract.KEY_REMOVED_COUNT,
            EngineRuntimeIpcContract.KEY_MESSAGE
        )
        if (keySet() != expectedFields) return@runCatching null
        val key = getBundle(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_SLOT_KEY)
            ?.toProxyActivitySlotQueryIpcKeyOrNull()
            ?: return@runCatching null
        val instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID)
            ?.takeIf { it == key.instanceId }
            ?: return@runCatching null
        val proxyActivityClassName = getString(
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME
        )
        if (!proxyActivityClassName.isValidNullableProxyActivitySlotClassName()) {
            return@runCatching null
        }
        VirtualProxyActivitySlotOperationResult(
            instanceId = instanceId,
            operation = getString(EngineRuntimeIpcContract.KEY_OPERATION).orEmpty(),
            verdict = EngineResultStatus.valueOf(
                getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()
            ),
            key = key,
            proxyActivityClassName = proxyActivityClassName,
            matched = getBoolean(EngineRuntimeIpcContract.KEY_MATCHED),
            removedCount = getInt(EngineRuntimeIpcContract.KEY_REMOVED_COUNT),
            message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
        )
    }.getOrNull()

private fun ProxyActivitySlotKey.toProxyActivitySlotKeyIpcBundle(
    bundleFactory: () -> Bundle
): Bundle = bundleFactory().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE, launchMode)
    putString(EngineRuntimeIpcContract.KEY_TASK_KEY, taskKey)
}

private fun Bundle.decodeProxyActivitySlotKeyOrNull(
    expectedFields: Set<String>
): ProxyActivitySlotKey? = runCatching {
    if (keySet() != expectedFields ||
        !containsKey(EngineRuntimeIpcContract.KEY_LAUNCH_MODE)
    ) {
        return@runCatching null
    }
    ProxyActivitySlotKey(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        launchMode = getString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE),
        taskKey = getString(EngineRuntimeIpcContract.KEY_TASK_KEY).orEmpty()
    ).takeIf(ProxyActivitySlotKey::isValidProxyActivitySlotIpcKey)
}.getOrNull()

private val PROXY_ACTIVITY_SLOT_KEY_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_LAUNCH_MODE,
    EngineRuntimeIpcContract.KEY_TASK_KEY
)

private fun VirtualActivityLaunchAllocationRequest.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, originPackageName)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, guestActivityClassName)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE, launchMode)
    putString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY, taskAffinity)
}

private fun Bundle.toActivityLaunchAllocationRequestOrNull(): VirtualActivityLaunchAllocationRequest? =
    runCatching {
        if (keySet() != ACTIVITY_ALLOCATION_REQUEST_FIELDS) return@runCatching null
        VirtualActivityLaunchAllocationRequest(
            instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
            originPackageName = getString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME).orEmpty(),
            guestActivityClassName = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME).orEmpty(),
            processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
            launchMode = getString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE),
            taskAffinity = getString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY)
        )
    }.getOrNull()

private fun VirtualActivityLaunchAllocation.toIpcBundle(): Bundle = Bundle().apply {
    putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, accepted)
    putBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_ALLOCATION_REQUEST, request.toIpcBundle())
    putString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME, proxyActivityClassName)
    putBundle(
        EngineRuntimeIpcContract.KEY_ACTIVITY_LAUNCH_IDENTITY,
        launchIdentity?.toIpcBundle()
    )
    putString(EngineRuntimeIpcContract.KEY_REASON, reason)
}

private fun Bundle.toActivityLaunchAllocationOrNull(): VirtualActivityLaunchAllocation? = runCatching {
    if (keySet() != ACTIVITY_ALLOCATION_FIELDS) return@runCatching null
    val request = getBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_ALLOCATION_REQUEST)
        ?.toActivityLaunchAllocationRequestOrNull()
        ?: return@runCatching null
    val identityBundle = getBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_LAUNCH_IDENTITY)
    val identity = identityBundle?.toLoaderActivityLaunchIdentityOrNull()
    if (identityBundle != null && identity == null) return@runCatching null
    VirtualActivityLaunchAllocation(
        accepted = getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED),
        request = request,
        proxyActivityClassName = getString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME),
        launchIdentity = identity,
        reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty()
    )
}.getOrNull()

private fun VirtualActivityLaunchIdentity.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN, capabilityToken)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME, proxyActivityClassName)
    putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, guestActivityClassName)
}

private fun Bundle.toLoaderActivityLaunchIdentityOrNull(): VirtualActivityLaunchIdentity? = runCatching {
    if (keySet() != ACTIVITY_LAUNCH_IDENTITY_FIELDS) return@runCatching null
    VirtualActivityLaunchIdentity(
        capabilityToken = getString(EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN).orEmpty(),
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
        engineSessionId = getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        proxyActivityClassName = getString(
            EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME
        ).orEmpty(),
        guestActivityClassName = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME).orEmpty()
    )
}.getOrNull()

private val ACTIVITY_ALLOCATION_REQUEST_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME,
    EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_LAUNCH_MODE,
    EngineRuntimeIpcContract.KEY_TASK_AFFINITY
)
private val ACTIVITY_ALLOCATION_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_ACCEPTED,
    EngineRuntimeIpcContract.KEY_ACTIVITY_ALLOCATION_REQUEST,
    EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME,
    EngineRuntimeIpcContract.KEY_ACTIVITY_LAUNCH_IDENTITY,
    EngineRuntimeIpcContract.KEY_REASON
)
private val ACTIVITY_LAUNCH_IDENTITY_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN,
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH,
    EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME,
    EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME
)
private val ACTIVITY_PLAN_REQUEST_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_ACTION,
    EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME,
    EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME,
    EngineRuntimeIpcContract.KEY_CATEGORIES,
    EngineRuntimeIpcContract.KEY_DATA_SCHEME,
    EngineRuntimeIpcContract.KEY_DATA_MIME_TYPE,
    EngineRuntimeIpcContract.KEY_DATA_AUTHORITY,
    EngineRuntimeIpcContract.KEY_DATA_PATH,
    EngineRuntimeIpcContract.KEY_LAUNCH_FLAGS
)
private val ACTIVITY_PLAN_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_VERDICT,
    EngineRuntimeIpcContract.KEY_ACTION,
    EngineRuntimeIpcContract.KEY_TARGETS,
    EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS,
    EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS,
    EngineRuntimeIpcContract.KEY_MESSAGE
)
private val ACTIVITY_TARGET_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME,
    EngineRuntimeIpcContract.KEY_VIRTUAL_PACKAGE_NAME,
    EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME,
    EngineRuntimeIpcContract.KEY_ACTION,
    EngineRuntimeIpcContract.KEY_REASON,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_PROCESS_NAME,
    EngineRuntimeIpcContract.KEY_LAUNCH_MODE,
    EngineRuntimeIpcContract.KEY_TASK_AFFINITY,
    EngineRuntimeIpcContract.KEY_PRIORITY,
    EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME,
    EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN,
    EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH,
    EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID
)

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
    if (keySet() != ACTIVITY_PLAN_REQUEST_FIELDS) return@runCatching null
    val categoryList = getStringArrayList(EngineRuntimeIpcContract.KEY_CATEGORIES)
        ?: return@runCatching null
    if (
        categoryList.size > MAX_ACTIVITY_CATEGORIES ||
        categoryList.any { it.isBlank() } ||
        categoryList.distinct().size != categoryList.size
    ) {
        return@runCatching null
    }
    VirtualActivityDispatchPlanRequest(
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        activityClassName = getString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME),
        targetPackageName = getString(EngineRuntimeIpcContract.KEY_TARGET_PACKAGE_NAME),
        categories = categoryList.toSet(),
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
    putString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME, proxyActivityClassName)
    putString(EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN, launchCapabilityToken)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch ?: 0L)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
}

private fun Bundle.toActivityDispatchPlanOrNull(): VirtualActivityDispatchPlan? = runCatching {
    if (keySet() != ACTIVITY_PLAN_FIELDS) return@runCatching null
    val targetBundles = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_TARGETS)
        ?: return@runCatching null
    if (targetBundles.size > MAX_ACTIVITY_TARGETS) return@runCatching null
    val targets = targetBundles.map { target ->
        target.toActivityDispatchTargetOrNull() ?: return@runCatching null
    }
    val supported = getStringArrayList(EngineRuntimeIpcContract.KEY_SUPPORTED_OPERATIONS)
        ?: return@runCatching null
    val unsupported = getStringArrayList(EngineRuntimeIpcContract.KEY_UNSUPPORTED_OPERATIONS)
        ?: return@runCatching null
    if (
        !supported.isValidActivityOperationList() ||
        !unsupported.isValidActivityOperationList() ||
        supported.any { it in unsupported }
    ) {
        return@runCatching null
    }
    VirtualActivityDispatchPlan(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()),
        action = getString(EngineRuntimeIpcContract.KEY_ACTION),
        targets = targets,
        supportedOperations = supported.toSet(),
        unsupportedOperations = unsupported.toSet(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun Bundle.toActivityDispatchTargetOrNull(): VirtualActivityDispatchTarget? = runCatching {
    if (keySet() != ACTIVITY_TARGET_FIELDS) return@runCatching null
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
        priority = getInt(EngineRuntimeIpcContract.KEY_PRIORITY),
        proxyActivityClassName = getString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME),
        launchCapabilityToken = getString(EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH).takeIf { it > 0L },
        engineSessionId = getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID)
    )
}.getOrNull()

private fun List<String>.isValidActivityOperationList(): Boolean =
    size <= MAX_ACTIVITY_OPERATIONS && all { it.isNotBlank() } && distinct().size == size

private const val MAX_ACTIVITY_CATEGORIES = 64
private const val MAX_ACTIVITY_TARGETS = 128
private const val MAX_ACTIVITY_OPERATIONS = 128

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
    putString(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN, routeToken)
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
        routeToken = getString(EngineRuntimeIpcContract.KEY_ROUTE_TOKEN),
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
    putInt(EngineRuntimeIpcContract.KEY_PERSISTED_MODE_FLAGS, persistedModeFlags)
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
        persistedModeFlags = getInt(EngineRuntimeIpcContract.KEY_PERSISTED_MODE_FLAGS),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualPermissionCheckResult.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_PERMISSION, permissionName)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putBoolean(EngineRuntimeIpcContract.KEY_REQUESTED, requested)
    putBoolean(EngineRuntimeIpcContract.KEY_GRANTED, granted)
    putBoolean(EngineRuntimeIpcContract.KEY_EXPLICIT, explicit)
    putString(EngineRuntimeIpcContract.KEY_SOURCE, source?.name)
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toPermissionCheckResultOrNull(): VirtualPermissionCheckResult? = runCatching {
    VirtualPermissionCheckResult(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        permissionName = getString(EngineRuntimeIpcContract.KEY_PERMISSION).orEmpty(),
        verdict = EngineResultStatus.valueOf(
            getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()
        ),
        requested = getBoolean(EngineRuntimeIpcContract.KEY_REQUESTED),
        granted = getBoolean(EngineRuntimeIpcContract.KEY_GRANTED),
        explicit = getBoolean(EngineRuntimeIpcContract.KEY_EXPLICIT),
        source = getString(EngineRuntimeIpcContract.KEY_SOURCE)
            ?.let(EnginePermissionGrantSource::valueOf),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun VirtualPermissionRuntimeState.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_VERDICT, verdict.name)
    putParcelableArrayList(
        EngineRuntimeIpcContract.KEY_PERMISSION_RECORDS,
        ArrayList(records.map { record -> record.toIpcBundle() })
    )
    putString(EngineRuntimeIpcContract.KEY_MESSAGE, message)
}

private fun Bundle.toPermissionRuntimeStateOrNull(): VirtualPermissionRuntimeState? = runCatching {
    VirtualPermissionRuntimeState(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        verdict = EngineResultStatus.valueOf(
            getString(EngineRuntimeIpcContract.KEY_VERDICT).orEmpty()
        ),
        records = getParcelableArrayList<Bundle>(EngineRuntimeIpcContract.KEY_PERMISSION_RECORDS)
            .orEmpty()
            .mapNotNull { it.toPermissionGrantRecordOrNull() },
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

private fun EnginePermissionGrantRecord.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putString(EngineRuntimeIpcContract.KEY_PERMISSION, permissionName)
    putBoolean(EngineRuntimeIpcContract.KEY_GRANTED, granted)
    putString(EngineRuntimeIpcContract.KEY_SOURCE, source.name)
    putLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS, updatedAtMs)
}

private fun Bundle.toPermissionGrantRecordOrNull(): EnginePermissionGrantRecord? = runCatching {
    EnginePermissionGrantRecord(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        permissionName = getString(EngineRuntimeIpcContract.KEY_PERMISSION).orEmpty(),
        granted = getBoolean(EngineRuntimeIpcContract.KEY_GRANTED),
        source = EnginePermissionGrantSource.valueOf(
            getString(EngineRuntimeIpcContract.KEY_SOURCE).orEmpty()
        ),
        updatedAtMs = getLong(EngineRuntimeIpcContract.KEY_UPDATED_AT_MS)
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
    putBoolean(
        EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE_REQUESTED,
        operationLeaseRequested
    )
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
        stickyRestartRequested = getBoolean(EngineRuntimeIpcContract.KEY_STICKY_RESTART_REQUESTED),
        operationLeaseRequested = getBoolean(
            EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE_REQUESTED
        )
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
    putBoolean(EngineRuntimeIpcContract.KEY_SAME_PROCESS, sameProcess)
    putBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND, foreground)
    putInt(EngineRuntimeIpcContract.KEY_PRIORITY, priority)
    operationLease?.let {
        putBundle(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE, it.toIpcBundle())
    }
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
        sameProcess = getBoolean(EngineRuntimeIpcContract.KEY_SAME_PROCESS, true),
        foreground = getBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND),
        priority = getInt(EngineRuntimeIpcContract.KEY_PRIORITY),
        operationLease = getBundle(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE)
            ?.toServiceOperationLeaseIdentityOrNull()
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
    putBoolean(EngineRuntimeIpcContract.KEY_DESTROYED, destroyed)
    putBoolean(EngineRuntimeIpcContract.KEY_FOREGROUND, foreground)
    startCommandResult?.let { putInt(EngineRuntimeIpcContract.KEY_START_COMMAND_RESULT, it) }
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putInt(EngineRuntimeIpcContract.KEY_ACTIVE_START_COUNT, activeStartCount)
    putInt(EngineRuntimeIpcContract.KEY_ACTIVE_BIND_COUNT, activeBindCount)
    putBoolean(EngineRuntimeIpcContract.KEY_CACHED, cached)
    operationLease?.let {
        putBundle(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE, it.toIpcBundle())
    }
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
        destroyed = getBoolean(EngineRuntimeIpcContract.KEY_DESTROYED),
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
        operationLease = getBundle(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE)
            ?.toServiceOperationLeaseIdentityOrNull(),
        message = getString(EngineRuntimeIpcContract.KEY_MESSAGE).orEmpty()
    )
}.getOrNull()

internal fun EngineServiceOperationLeaseIdentity.toIpcBundle(): Bundle = Bundle().apply {
    putString(EngineRuntimeIpcContract.KEY_SERVICE_LEASE_TOKEN, leaseToken)
    putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
    putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
    putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
    putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
    putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, processId)
    putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, operation)
    putString(EngineRuntimeIpcContract.KEY_COMPONENT, component)
    putLong(EngineRuntimeIpcContract.KEY_ISSUED_AT_NANOS, issuedAtNanos)
    putLong(EngineRuntimeIpcContract.KEY_EXPIRES_AT_NANOS, expiresAtNanos)
}

internal fun Bundle.toServiceOperationLeaseIdentityOrNull(): EngineServiceOperationLeaseIdentity? = runCatching {
    EngineServiceOperationLeaseIdentity(
        leaseToken = getString(EngineRuntimeIpcContract.KEY_SERVICE_LEASE_TOKEN).orEmpty(),
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
        engineSessionId = getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        processId = getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID),
        operation = getString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION).orEmpty(),
        component = getString(EngineRuntimeIpcContract.KEY_COMPONENT).orEmpty(),
        issuedAtNanos = getLong(EngineRuntimeIpcContract.KEY_ISSUED_AT_NANOS),
        expiresAtNanos = getLong(EngineRuntimeIpcContract.KEY_EXPIRES_AT_NANOS)
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

internal const val SERVICE_CONNECTION_REGISTER_OPERATION = "registerServiceConnection"
internal const val SERVICE_CONNECTION_QUERY_OPERATION = "queryServiceConnection"
internal const val SERVICE_CONNECTION_REMOVE_BINDING_OPERATION = "removeServiceConnectionBinding"
internal const val SERVICE_CONNECTION_REMOVE_OPERATION = "removeServiceConnection"

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

private fun String.toServiceComponentClassName(originPackageName: String): String = when {
    startsWith('.') -> originPackageName + this
    '.' !in this -> "$originPackageName.$this"
    else -> this
}

private fun ResolvedComponent.effectiveServiceProcessName(
    runtime: VirtualInstanceRuntime
): String {
    val applicationProcessName = runtime.packageSnapshot.processName
        .toServiceEffectiveGuestProcessName(runtime.originPackageName)
    return processName?.toServiceEffectiveGuestProcessName(runtime.originPackageName)
        ?: applicationProcessName
}

private fun String?.toServiceEffectiveGuestProcessName(originPackageName: String): String =
    this?.takeIf { it.isNotBlank() }
        ?.let { processName ->
            if (processName.startsWith(':')) originPackageName + processName else processName
        }
        ?: originPackageName

private fun readAndroidProcessName(processId: Int): String? {
    if (processId <= 0) return null
    return runCatching {
        File("/proc/$processId/cmdline")
            .readBytes()
            .toString(Charsets.UTF_8)
            .substringBefore('\u0000')
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun readAndroidProcessStartTicks(processId: Int): Long? {
    if (processId <= 0) return null
    return runCatching {
        val stat = File("/proc/$processId/stat").readText()
        val fieldsAfterName = stat.substringAfterLast(") ", missingDelimiterValue = "")
            .trim()
            .split(Regex("\\s+"))
        fieldsAfterName.getOrNull(PROC_STAT_STARTTIME_OFFSET_AFTER_NAME)
            ?.toLongOrNull()
            ?.takeIf { ticks -> ticks > 0L }
    }.getOrNull()
}

private const val PROC_STAT_STARTTIME_OFFSET_AFTER_NAME = 19

private val COMPONENT_SERVICE_START_OPERATIONS = setOf(
    VirtualServiceOperation.START,
    VirtualServiceOperation.START_FOREGROUND
)

private fun EngineProviderOperation.toProviderRouteOperationName(): String = when (this) {
    EngineProviderOperation.ACQUIRE_PROVIDER -> "acquireProvider"
    EngineProviderOperation.QUERY -> "query"
    EngineProviderOperation.GET_TYPE -> "getType"
    EngineProviderOperation.INSERT -> "insert"
    EngineProviderOperation.DELETE -> "delete"
    EngineProviderOperation.UPDATE -> "update"
    EngineProviderOperation.CALL -> "call"
    EngineProviderOperation.OPEN_FILE -> "openFile"
    EngineProviderOperation.OPEN_ASSET_FILE -> "openAssetFile"
    EngineProviderOperation.OPEN_TYPED_ASSET_FILE -> "openTypedAssetFile"
    EngineProviderOperation.BULK_INSERT -> "bulkInsert"
    EngineProviderOperation.APPLY_BATCH -> "applyBatch"
    EngineProviderOperation.NOTIFY_CHANGE -> "notifyChange"
    EngineProviderOperation.REGISTER_CONTENT_OBSERVER -> "registerContentObserver"
    EngineProviderOperation.UNREGISTER_CONTENT_OBSERVER -> "unregisterContentObserver"
    EngineProviderOperation.GRANT_URI_PERMISSION -> "grantUriPermission"
    EngineProviderOperation.REVOKE_URI_PERMISSION -> "revokeUriPermission"
    EngineProviderOperation.CANONICALIZE -> "canonicalize"
    EngineProviderOperation.UNCANONICALIZE -> "uncanonicalize"
    EngineProviderOperation.REFRESH -> "refresh"
    EngineProviderOperation.UNKNOWN -> "unknown"
}
