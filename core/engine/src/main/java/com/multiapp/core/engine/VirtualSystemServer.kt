package com.multiapp.core.engine

import android.net.Uri
import android.os.PatternMatcher
import com.multiapp.core.model.engine.EngineEvidenceReport
import com.multiapp.core.model.engine.EngineOperationEvidence
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineSubsystem
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityState
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import com.multiapp.core.model.virtual.VirtualProviderPathPattern
import com.multiapp.core.model.virtual.VirtualProviderPathPatternType
import com.multiapp.core.model.virtual.VirtualTaskRecord

interface VirtualSystemServer {
    val runtimeService: VirtualRuntimeService
    val packageService: VirtualPackageService
    val activityService: VirtualActivityService
    val providerService: VirtualProviderService
    val appOpsService: VirtualAppOpsService
    val serviceService: VirtualServiceService
    val broadcastService: VirtualBroadcastService
    val storageService: VirtualStorageService
    val nativeService: VirtualNativeService
    val evidenceService: VirtualEvidenceService
}

interface VirtualRuntimeService {
    fun register(runtime: VirtualInstanceRuntime): VirtualInstanceRuntime
    fun get(instanceId: String): VirtualInstanceRuntime?
    fun list(): List<VirtualInstanceRuntime>
    fun stop(instanceId: String): Boolean
    fun evidence(instanceId: String): EngineEvidenceReport
    fun registerOperationEvidence(instanceId: String, evidence: EngineOperationEvidence): Boolean
}

interface VirtualEngineSubsystemService {
    val subsystem: EngineSubsystem
}

interface VirtualPackageService : VirtualEngineSubsystemService {
    fun queryPackageSnapshot(instanceId: String): VirtualPackageSnapshot?
    fun queryPackageIdentity(instanceId: String): Result<VirtualPackageIdentity>
    fun queryComponent(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): ResolvedComponent?

    fun queryProviderByAuthority(instanceId: String, authority: String): ResolvedComponent?
    fun resolveIntent(
        instanceId: String,
        type: VirtualPackageComponentType,
        action: String,
        categories: Set<String> = emptySet(),
        dataScheme: String? = null,
        dataMimeType: String? = null,
        dataAuthority: String? = null,
        dataPath: String? = null
    ): List<ResolvedComponent>
}

interface VirtualRuntimeBoundSubsystemService : VirtualEngineSubsystemService {
    fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding
}

interface VirtualActivityService : VirtualRuntimeBoundSubsystemService {
    fun planActivity(
        instanceId: String,
        request: VirtualActivityDispatchPlanRequest
    ): VirtualActivityDispatchPlan

    fun recordActivityDispatch(
        instanceId: String,
        result: VirtualActivityDispatchResult
    ): Boolean

    fun syncActivityTaskState(
        instanceId: String,
        reason: String,
        tasks: List<VirtualTaskRecord>? = null
    ): VirtualActivityOperationResult

    fun queryTaskState(instanceId: String): VirtualActivityTaskState
    fun markActivityState(
        instanceId: String,
        token: String,
        state: VirtualActivityState
    ): VirtualActivityOperationResult

    fun finishActivity(instanceId: String, token: String): VirtualActivityOperationResult
    fun recordActivityResultForFinish(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null
    ): VirtualActivityOperationResult
    fun setActivityResult(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot? = null,
        requestCode: Int = -1,
        resultWho: String? = null,
        frameworkDispatchAttempted: Boolean = false,
        frameworkDispatchInvoked: Boolean = false
    ): VirtualActivityOperationResult

    fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult?
    fun consumeActivityResultForResumeFallback(instanceId: String, token: String): VirtualActivityResult?
    fun markActivityResultDispatchState(
        instanceId: String,
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): VirtualActivityOperationResult
    fun consumePendingNewIntent(instanceId: String, token: String): VirtualActivityPendingNewIntent?
}
interface VirtualProviderService : VirtualRuntimeBoundSubsystemService {
    fun resolveProviderAuthority(
        callerInstanceId: String,
        request: VirtualProviderAuthorityResolveRequest
    ): VirtualProviderAuthorityResolveResult

    fun planProvider(
        instanceId: String,
        request: VirtualProviderDispatchPlanRequest
    ): VirtualProviderDispatchPlan

    fun recordProviderDispatch(
        instanceId: String,
        result: VirtualProviderOperationResult
    ): Boolean

    fun queryProviderRuntimeState(instanceId: String): VirtualProviderRuntimeState

    fun grantUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult

    fun revokeUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult

    fun checkUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult
}
interface VirtualServiceService : VirtualRuntimeBoundSubsystemService {
    fun planService(
        instanceId: String,
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceDispatchPlan

    fun recordServiceDispatch(
        instanceId: String,
        result: VirtualServiceOperationResult
    ): Boolean

    fun queryServiceRuntimeState(instanceId: String): VirtualServiceRuntimeState
}
interface VirtualBroadcastService : VirtualRuntimeBoundSubsystemService {
    fun planBroadcast(
        instanceId: String,
        request: VirtualBroadcastDispatchPlanRequest
    ): VirtualBroadcastDispatchPlan

    fun recordBroadcastDispatch(
        instanceId: String,
        result: VirtualBroadcastOperationResult
    ): Boolean

    fun queryBroadcastRuntimeState(instanceId: String): VirtualBroadcastRuntimeState
}
interface VirtualStorageService : VirtualRuntimeBoundSubsystemService
interface VirtualNativeService : VirtualRuntimeBoundSubsystemService

interface VirtualEvidenceService : VirtualEngineSubsystemService {
    fun exportReport(instanceId: String): EngineEvidenceReport?
}

enum class VirtualPackageComponentType {
    ACTIVITY,
    SERVICE,
    RECEIVER,
    PROVIDER
}

data class VirtualPackageIdentity(
    val instanceId: String,
    val hostPackageName: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val applicationLabel: String,
    val versionCode: Long,
    val versionName: String
) {
    companion object {
        fun from(runtime: VirtualInstanceRuntime): VirtualPackageIdentity {
            val snapshot = runtime.packageSnapshot
            return VirtualPackageIdentity(
                instanceId = snapshot.instanceId,
                hostPackageName = runtime.hostPackageName,
                originPackageName = snapshot.originPackageName,
                virtualPackageName = snapshot.virtualPackageName,
                applicationLabel = snapshot.applicationLabel,
                versionCode = snapshot.versionCode,
                versionName = snapshot.versionName
            )
        }
    }
}

data class VirtualSubsystemRuntimeBinding(
    val instanceId: String,
    val subsystem: EngineSubsystem,
    val verdict: EngineResultStatus,
    val hostPackageName: String? = null,
    val originPackageName: String? = null,
    val virtualPackageName: String? = null,
    val processSlot: String? = null,
    val proxySlot: String? = null,
    val runtimeEpoch: Long? = null,
    val processId: Int? = null,
    val processName: String? = null,
    val state: VirtualRuntimeState? = null,
    val supportedOperations: Set<String> = emptySet(),
    val unsupportedOperations: Set<String> = emptySet(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(supportedOperations.none { it.isBlank() }) { "supportedOperations must not contain blank entries" }
        require(unsupportedOperations.none { it.isBlank() }) { "unsupportedOperations must not contain blank entries" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualActivityTaskState(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val taskCount: Int = 0,
    val activityCount: Int = 0,
    val topTaskId: Int? = null,
    val topActivityClassName: String? = null,
    val topActivityState: VirtualActivityState? = null,
    val tasks: List<VirtualTaskRecord> = emptyList(),
    val supportedOperations: Set<String> = emptySet(),
    val unsupportedOperations: Set<String> = emptySet(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(taskCount >= 0) { "taskCount must not be negative" }
        require(activityCount >= 0) { "activityCount must not be negative" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualActivityOperationResult(
    val instanceId: String,
    val operation: String,
    val verdict: EngineResultStatus,
    val token: String? = null,
    val activityId: String? = null,
    val activityClassName: String? = null,
    val state: VirtualActivityState? = null,
    val activity: VirtualActivityRecord? = null,
    val requestCode: Int = -1,
    val resultCode: Int? = null,
    val dataIntent: VirtualIntentSnapshot? = null,
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(requestCode >= -1) { "requestCode must be -1 or greater" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualActivityDispatchPlanRequest(
    val action: String? = null,
    val activityClassName: String? = null,
    val targetPackageName: String? = null,
    val categories: Set<String> = emptySet(),
    val dataScheme: String? = null,
    val dataMimeType: String? = null,
    val dataAuthority: String? = null,
    val dataPath: String? = null,
    val launchFlags: Int = 0
) {
    init {
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(activityClassName == null || activityClassName.isNotBlank()) {
            "activityClassName must not be blank"
        }
        require(targetPackageName == null || targetPackageName.isNotBlank()) {
            "targetPackageName must not be blank"
        }
        require(categories.none { it.isBlank() }) { "categories must not contain blank entries" }
        require(dataScheme == null || dataScheme.isNotBlank()) { "dataScheme must not be blank" }
        require(dataMimeType == null || dataMimeType.isNotBlank()) { "dataMimeType must not be blank" }
        require(dataAuthority == null || dataAuthority.isNotBlank()) { "dataAuthority must not be blank" }
        require(dataPath == null || dataPath.isNotBlank()) { "dataPath must not be blank" }
    }
}

data class VirtualActivityDispatchTarget(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val activityClassName: String,
    val action: String?,
    val reason: String,
    val processSlot: String,
    val processName: String? = null,
    val launchMode: String? = null,
    val taskAffinity: String? = null,
    val priority: Int = 0
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(activityClassName.isNotBlank()) { "activityClassName must not be blank" }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(processName == null || processName.isNotBlank()) { "processName must not be blank" }
        require(launchMode == null || launchMode.isNotBlank()) { "launchMode must not be blank" }
        require(taskAffinity == null || taskAffinity.isNotBlank()) { "taskAffinity must not be blank" }
    }
}

data class VirtualActivityDispatchPlan(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val action: String? = null,
    val targets: List<VirtualActivityDispatchTarget> = emptyList(),
    val supportedOperations: Set<String> = emptySet(),
    val unsupportedOperations: Set<String> = emptySet(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(supportedOperations.none { it.isBlank() }) { "supportedOperations must not contain blank entries" }
        require(unsupportedOperations.none { it.isBlank() }) { "unsupportedOperations must not contain blank entries" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualActivityDispatchResult(
    val instanceId: String,
    val activityClassName: String?,
    val action: String?,
    val verdict: EngineResultStatus,
    val reason: String,
    val remapped: Boolean = false,
    val proxyActivityClassName: String? = null,
    val launchFlags: Int = 0,
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(activityClassName == null || activityClassName.isNotBlank()) {
            "activityClassName must not be blank"
        }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(proxyActivityClassName == null || proxyActivityClassName.isNotBlank()) {
            "proxyActivityClassName must not be blank"
        }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

enum class VirtualProviderAccessType {
    NONE,
    READ,
    WRITE
}

data class VirtualProviderDispatchPlanRequest(
    val operation: EngineProviderOperation,
    val guestAuthority: String,
    val proxyAuthority: String? = null,
    val processSlot: String? = null,
    val routeTokenPresent: Boolean = false,
    val routeTokenVerified: Boolean = false,
    val callerInstanceId: String? = null,
    val targetInstanceId: String? = null,
    val callingUid: Int = -1,
    val callingPid: Int = -1,
    val hostUid: Int = -1,
    val callerProcessSlot: String? = null,
    val accessMode: String? = null,
    val encodedPath: String = "/",
    val uriGrantPresent: Boolean = false,
    val engineCallingUid: Int = -1,
    val engineCallingPid: Int = -1
) {
    init {
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(proxyAuthority == null || proxyAuthority.isNotBlank()) { "proxyAuthority must not be blank" }
        require(processSlot == null || processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(callerInstanceId == null || callerInstanceId.isNotBlank()) {
            "callerInstanceId must not be blank"
        }
        require(targetInstanceId == null || targetInstanceId.isNotBlank()) {
            "targetInstanceId must not be blank"
        }
        require(callingUid >= -1) { "callingUid must be -1 or non-negative" }
        require(callingPid >= -1) { "callingPid must be -1 or non-negative" }
        require(hostUid >= -1) { "hostUid must be -1 or non-negative" }
        require(callerProcessSlot == null || callerProcessSlot.isNotBlank()) {
            "callerProcessSlot must not be blank"
        }
        require(accessMode == null || accessMode.isNotBlank()) { "accessMode must not be blank" }
        require(encodedPath.startsWith('/')) { "encodedPath must be absolute" }
        require(engineCallingUid >= -1) { "engineCallingUid must be -1 or non-negative" }
        require(engineCallingPid >= -1) { "engineCallingPid must be -1 or non-negative" }
    }
}

data class VirtualProviderAuthorityResolveRequest(
    val guestAuthority: String,
    val operation: EngineProviderOperation,
    val encodedPath: String = "/",
    val accessMode: String? = null
) {
    init {
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(encodedPath.startsWith('/')) { "encodedPath must be absolute" }
        require(accessMode == null || accessMode.isNotBlank()) { "accessMode must not be blank" }
    }
}

data class VirtualProviderAuthorityResolveResult(
    val callerInstanceId: String,
    val guestAuthority: String,
    val verdict: EngineResultStatus,
    val virtualAuthority: Boolean,
    val targetInstanceId: String? = null,
    val message: String
)

object EngineProviderUriGrantModes {
    const val READ = 0x00000001
    const val WRITE = 0x00000002
    const val PERSISTABLE = 0x00000040
    const val PREFIX = 0x00000080
    const val ACCESS_MASK = READ or WRITE
    const val SUPPORTED_REQUEST_MASK = ACCESS_MASK or PERSISTABLE or PREFIX
}

data class VirtualProviderUriGrantRequest(
    val guestAuthority: String,
    val encodedPath: String = "/",
    val modeFlags: Int,
    val ownerInstanceId: String? = null,
    val targetInstanceId: String? = null,
    val targetPackageName: String? = null,
    val callingUid: Int = -1,
    val callingPid: Int = -1,
    val hostUid: Int = -1
) {
    init {
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(encodedPath.startsWith('/')) { "encodedPath must be absolute" }
        require(modeFlags >= 0) { "modeFlags must not be negative" }
        require(ownerInstanceId == null || ownerInstanceId.isNotBlank()) {
            "ownerInstanceId must not be blank"
        }
        require(targetInstanceId == null || targetInstanceId.isNotBlank()) {
            "targetInstanceId must not be blank"
        }
        require(targetPackageName == null || targetPackageName.isNotBlank()) {
            "targetPackageName must not be blank"
        }
        require(callingUid >= -1) { "callingUid must be -1 or non-negative" }
        require(callingPid >= -1) { "callingPid must be -1 or non-negative" }
        require(hostUid >= -1) { "hostUid must be -1 or non-negative" }
    }
}

data class VirtualProviderUriGrantResult(
    val ownerInstanceId: String?,
    val targetInstanceId: String?,
    val guestAuthority: String,
    val encodedPath: String,
    val modeFlags: Int,
    val verdict: EngineResultStatus,
    val granted: Boolean,
    val affectedGrantCount: Int = 0,
    val message: String
)

data class VirtualProviderDispatchTarget(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val guestAuthority: String,
    val proxyAuthority: String?,
    val providerClassName: String,
    val operation: EngineProviderOperation,
    val processSlot: String,
    val processName: String? = null,
    val exported: Boolean,
    val permission: String?,
    val readPermission: String?,
    val writePermission: String?,
    val grantUriPermissions: Boolean
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(proxyAuthority == null || proxyAuthority.isNotBlank()) { "proxyAuthority must not be blank" }
        require(providerClassName.isNotBlank()) { "providerClassName must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(processName == null || processName.isNotBlank()) { "processName must not be blank" }
        require(permission == null || permission.isNotBlank()) { "permission must not be blank" }
        require(readPermission == null || readPermission.isNotBlank()) { "readPermission must not be blank" }
        require(writePermission == null || writePermission.isNotBlank()) { "writePermission must not be blank" }
    }
}

data class VirtualProviderDispatchPlan(
    val instanceId: String,
    val operation: EngineProviderOperation,
    val verdict: EngineResultStatus,
    val guestAuthority: String,
    val targets: List<VirtualProviderDispatchTarget> = emptyList(),
    val supportedOperations: Set<String> = emptySet(),
    val unsupportedOperations: Set<String> = emptySet(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(supportedOperations.none { it.isBlank() }) { "supportedOperations must not contain blank entries" }
        require(unsupportedOperations.none { it.isBlank() }) { "unsupportedOperations must not contain blank entries" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualProviderOperationResult(
    val instanceId: String,
    val operation: EngineProviderOperation,
    val guestAuthority: String?,
    val proxyAuthority: String?,
    val providerClassName: String?,
    val verdict: EngineResultStatus,
    val reason: String,
    val ready: Boolean = false,
    val cached: Boolean = false,
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(guestAuthority == null || guestAuthority.isNotBlank()) { "guestAuthority must not be blank" }
        require(proxyAuthority == null || proxyAuthority.isNotBlank()) { "proxyAuthority must not be blank" }
        require(providerClassName == null || providerClassName.isNotBlank()) { "providerClassName must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualProviderRuntimeState(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val records: List<EngineProviderRuntimeRecord> = emptyList(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

enum class VirtualServiceOperation {
    START,
    START_FOREGROUND,
    STOP,
    BIND,
    UNBIND
}

data class VirtualServiceDispatchPlanRequest(
    val operation: VirtualServiceOperation,
    val action: String? = null,
    val serviceClassName: String? = null,
    val targetPackageName: String? = null,
    val categories: Set<String> = emptySet(),
    val dataScheme: String? = null,
    val dataMimeType: String? = null,
    val dataAuthority: String? = null,
    val dataPath: String? = null,
    val requestedForegroundServiceTypes: Set<String> = emptySet(),
    val stickyRestartRequested: Boolean = false
) {
    init {
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(serviceClassName == null || serviceClassName.isNotBlank()) {
            "serviceClassName must not be blank"
        }
        require(targetPackageName == null || targetPackageName.isNotBlank()) {
            "targetPackageName must not be blank"
        }
        require(categories.none { it.isBlank() }) { "categories must not contain blank entries" }
        require(dataScheme == null || dataScheme.isNotBlank()) { "dataScheme must not be blank" }
        require(dataMimeType == null || dataMimeType.isNotBlank()) { "dataMimeType must not be blank" }
        require(dataAuthority == null || dataAuthority.isNotBlank()) { "dataAuthority must not be blank" }
        require(dataPath == null || dataPath.isNotBlank()) { "dataPath must not be blank" }
        require(requestedForegroundServiceTypes.none { it.isBlank() }) {
            "requestedForegroundServiceTypes must not contain blank entries"
        }
    }
}

data class VirtualServiceDispatchTarget(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val serviceClassName: String,
    val action: String?,
    val reason: String,
    val operation: VirtualServiceOperation,
    val processSlot: String,
    val processName: String? = null,
    val foreground: Boolean = false,
    val priority: Int = 0
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(serviceClassName.isNotBlank()) { "serviceClassName must not be blank" }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(processName == null || processName.isNotBlank()) { "processName must not be blank" }
    }
}

data class VirtualServiceDispatchPlan(
    val instanceId: String,
    val operation: VirtualServiceOperation,
    val verdict: EngineResultStatus,
    val action: String? = null,
    val targets: List<VirtualServiceDispatchTarget> = emptyList(),
    val supportedOperations: Set<String> = emptySet(),
    val unsupportedOperations: Set<String> = emptySet(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(supportedOperations.none { it.isBlank() }) { "supportedOperations must not contain blank entries" }
        require(unsupportedOperations.none { it.isBlank() }) { "unsupportedOperations must not contain blank entries" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualServiceOperationResult(
    val instanceId: String,
    val operation: VirtualServiceOperation,
    val serviceClassName: String?,
    val action: String?,
    val verdict: EngineResultStatus,
    val reason: String,
    val started: Boolean = false,
    val stopped: Boolean = false,
    val bound: Boolean = false,
    val unbound: Boolean = false,
    val foreground: Boolean = false,
    val startCommandResult: Int? = null,
    val processSlot: String? = null,
    val activeStartCount: Int = 0,
    val activeBindCount: Int = 0,
    val cached: Boolean = false,
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(serviceClassName == null || serviceClassName.isNotBlank()) {
            "serviceClassName must not be blank"
        }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(processSlot == null || processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(activeStartCount >= 0) { "activeStartCount must not be negative" }
        require(activeBindCount >= 0) { "activeBindCount must not be negative" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualServiceRuntimeState(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val records: List<EngineServiceRuntimeRecord> = emptyList(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualBroadcastDispatchPlanRequest(
    val action: String? = null,
    val receiverClassName: String? = null,
    val targetPackageName: String? = null,
    val categories: Set<String> = emptySet(),
    val dataScheme: String? = null,
    val dataMimeType: String? = null,
    val dataAuthority: String? = null,
    val dataPath: String? = null,
    val ordered: Boolean = false,
    val sticky: Boolean = false,
    val expectsResultReceiver: Boolean = false,
    val abortSupportedRequested: Boolean = false,
    val receiverPermissions: Set<String> = emptySet(),
    val receiverAppOp: String? = null,
    val asUserRequested: Boolean = false,
    val platformOptionsPresent: Boolean = false
) {
    init {
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(receiverClassName == null || receiverClassName.isNotBlank()) {
            "receiverClassName must not be blank"
        }
        require(targetPackageName == null || targetPackageName.isNotBlank()) {
            "targetPackageName must not be blank"
        }
        require(categories.none { it.isBlank() }) { "categories must not contain blank entries" }
        require(dataScheme == null || dataScheme.isNotBlank()) { "dataScheme must not be blank" }
        require(dataMimeType == null || dataMimeType.isNotBlank()) { "dataMimeType must not be blank" }
        require(dataAuthority == null || dataAuthority.isNotBlank()) { "dataAuthority must not be blank" }
        require(dataPath == null || dataPath.isNotBlank()) { "dataPath must not be blank" }
        require(receiverPermissions.none { it.isBlank() }) {
            "receiverPermissions must not contain blank entries"
        }
        require(receiverAppOp == null || receiverAppOp.isNotBlank()) { "receiverAppOp must not be blank" }
    }
}

data class VirtualBroadcastDispatchTarget(
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val receiverClassName: String,
    val action: String?,
    val reason: String,
    val processSlot: String,
    val processName: String? = null,
    val priority: Int = 0
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(virtualPackageName.isNotBlank()) { "virtualPackageName must not be blank" }
        require(receiverClassName.isNotBlank()) { "receiverClassName must not be blank" }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(processName == null || processName.isNotBlank()) { "processName must not be blank" }
    }
}

data class VirtualBroadcastDispatchPlan(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val action: String? = null,
    val targets: List<VirtualBroadcastDispatchTarget> = emptyList(),
    val supportedOperations: Set<String> = emptySet(),
    val unsupportedOperations: Set<String> = emptySet(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(supportedOperations.none { it.isBlank() }) { "supportedOperations must not contain blank entries" }
        require(unsupportedOperations.none { it.isBlank() }) { "unsupportedOperations must not contain blank entries" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualBroadcastOperationResult(
    val instanceId: String,
    val receiverClassName: String?,
    val action: String?,
    val verdict: EngineResultStatus,
    val reason: String,
    val delivered: Boolean = false,
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(receiverClassName == null || receiverClassName.isNotBlank()) {
            "receiverClassName must not be blank"
        }
        require(action == null || action.isNotBlank()) { "action must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

data class VirtualBroadcastRuntimeState(
    val instanceId: String,
    val verdict: EngineResultStatus,
    val records: List<EngineBroadcastRuntimeRecord> = emptyList(),
    val message: String
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
    }
}

class RegistryBackedVirtualRuntimeService(
    private val registry: EngineRuntimeRegistry
) : VirtualRuntimeService {
    override fun register(runtime: VirtualInstanceRuntime): VirtualInstanceRuntime =
        registry.register(runtime)

    override fun get(instanceId: String): VirtualInstanceRuntime? =
        registry.get(instanceId)

    override fun list(): List<VirtualInstanceRuntime> = registry.list()

    override fun stop(instanceId: String): Boolean =
        registry.stop(instanceId)

    override fun evidence(instanceId: String): EngineEvidenceReport =
        registry.evidence(instanceId)

    override fun registerOperationEvidence(instanceId: String, evidence: EngineOperationEvidence): Boolean =
        registry.registerOperationEvidence(instanceId, evidence)
}

class RegistryBackedVirtualPackageService(
    private val runtimeService: VirtualRuntimeService
) : VirtualPackageService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PACKAGE

    override fun queryPackageSnapshot(instanceId: String): VirtualPackageSnapshot? =
        runtimeService.get(instanceId)?.packageSnapshot

    override fun queryPackageIdentity(instanceId: String): Result<VirtualPackageIdentity> {
        val runtime = runtimeService.get(instanceId)
            ?: return Result.failure(IllegalStateException("runtime_not_found:$instanceId"))
        return Result.success(VirtualPackageIdentity.from(runtime))
    }

    override fun queryComponent(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): ResolvedComponent? {
        if (className.isBlank()) return null
        return queryPackageSnapshot(instanceId)
            ?.components(type)
            ?.firstOrNull { component ->
                component.name == className || component.targetActivityName == className
            }
    }

    override fun queryProviderByAuthority(instanceId: String, authority: String): ResolvedComponent? {
        if (authority.isBlank()) return null
        return queryPackageSnapshot(instanceId)
            ?.providers
            ?.firstOrNull { provider -> authority in provider.authorities }
    }

    override fun resolveIntent(
        instanceId: String,
        type: VirtualPackageComponentType,
        action: String,
        categories: Set<String>,
        dataScheme: String?,
        dataMimeType: String?,
        dataAuthority: String?,
        dataPath: String?
    ): List<ResolvedComponent> {
        if (action.isBlank()) return emptyList()
        return queryPackageSnapshot(instanceId)
            ?.components(type)
            ?.mapNotNull { component ->
                val matchingPriority = component.resolvedIntentFilters
                    .filter { filter ->
                        filter.matches(
                            action = action,
                            categories = categories,
                            dataScheme = dataScheme,
                            dataMimeType = dataMimeType,
                            dataAuthority = dataAuthority,
                            dataPath = dataPath
                        )
                    }
                    .maxOfOrNull { filter -> filter.priority }
                    ?: return@mapNotNull null
                component to matchingPriority
            }
            ?.sortedWith(compareByDescending<Pair<ResolvedComponent, Int>> { it.second }.thenBy { it.first.name })
            ?.map { (component, _) -> component }
            .orEmpty()
    }
}

class DefaultVirtualSystemServer(
    registry: EngineRuntimeRegistry,
    activityTaskStateStore: EngineActivityTaskStateStore = InMemoryEngineActivityTaskStateStore(),
    activityRecordManager: VirtualActivityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager,
    serviceRuntimeStateStore: EngineServiceRuntimeStateStore = InMemoryEngineServiceRuntimeStateStore(),
    providerRuntimeStateStore: EngineProviderRuntimeStateStore = InMemoryEngineProviderRuntimeStateStore(),
    providerUriGrantStore: EngineProviderUriGrantStore = InMemoryEngineProviderUriGrantStore(),
    appOpsStateStore: EngineAppOpsStateStore = InMemoryEngineAppOpsStateStore(),
    broadcastRuntimeStateStore: EngineBroadcastRuntimeStateStore = InMemoryEngineBroadcastRuntimeStateStore()
) : VirtualSystemServer {
    override val runtimeService: VirtualRuntimeService = RegistryBackedVirtualRuntimeService(registry)
    override val packageService: VirtualPackageService = RegistryBackedVirtualPackageService(runtimeService)
    override val activityService: VirtualActivityService = RegistryBackedVirtualActivityService(
        runtimeService,
        packageService,
        activityTaskStateStore,
        activityRecordManager
    )
    override val providerService: VirtualProviderService = RegistryBackedVirtualProviderService(
        runtimeService,
        packageService,
        providerRuntimeStateStore,
        providerUriGrantStore
    )
    override val appOpsService: VirtualAppOpsService = RegistryBackedVirtualAppOpsService(
        runtimeService,
        appOpsStateStore
    )
    override val serviceService: VirtualServiceService = RegistryBackedVirtualServiceService(
        runtimeService,
        packageService,
        serviceRuntimeStateStore
    )
    override val broadcastService: VirtualBroadcastService = RegistryBackedVirtualBroadcastService(
        runtimeService,
        packageService,
        broadcastRuntimeStateStore
    )
    override val storageService: VirtualStorageService = RegistryBackedVirtualStorageService(runtimeService)
    override val nativeService: VirtualNativeService = RegistryBackedVirtualNativeService(runtimeService)
    override val evidenceService: VirtualEvidenceService = RegistryBackedVirtualEvidenceService(
        runtimeService,
        activityService,
        serviceService,
        providerService,
        broadcastService,
        appOpsService
    )
}

object DefaultVirtualPackageService : VirtualPackageService {
    override val subsystem: EngineSubsystem = EngineSubsystem.PACKAGE

    override fun queryPackageSnapshot(instanceId: String): VirtualPackageSnapshot? = null

    override fun queryPackageIdentity(instanceId: String): Result<VirtualPackageIdentity> =
        Result.failure(IllegalStateException("runtime_not_found:$instanceId"))

    override fun queryComponent(
        instanceId: String,
        type: VirtualPackageComponentType,
        className: String
    ): ResolvedComponent? = null

    override fun queryProviderByAuthority(instanceId: String, authority: String): ResolvedComponent? = null

    override fun resolveIntent(
        instanceId: String,
        type: VirtualPackageComponentType,
        action: String,
        categories: Set<String>,
        dataScheme: String?,
        dataMimeType: String?,
        dataAuthority: String?,
        dataPath: String?
    ): List<ResolvedComponent> = emptyList()
}

internal class RegistryBackedVirtualActivityService(
    private val runtimeService: VirtualRuntimeService,
    private val packageService: VirtualPackageService,
    private val activityTaskStateStore: EngineActivityTaskStateStore = InMemoryEngineActivityTaskStateStore(),
    private val activityRecordManager: VirtualActivityRecordManager = EngineHostedProcessRuntimeDefaults.activityRecordManager
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.ACTIVITY,
    supportedOperations = setOf(
        "launch",
        "proxy-slot",
        "process-slot",
        "launch-mode-slot",
        "proxy-process-death-recovery-evidence",
        "task-state-persistence",
        "lifecycle-state-persistence",
        "finish-record",
        "result-record",
        "on-new-intent-record",
        "back-stack-state"
    ),
    unsupportedOperations = setOf("result-delivery", "finish-result-delivery", "recents-device-proof")
), VirtualActivityService {
    override fun planActivity(
        instanceId: String,
        request: VirtualActivityDispatchPlanRequest
    ): VirtualActivityDispatchPlan {
        if (instanceId.isBlank()) {
            return VirtualActivityDispatchPlan(
                instanceId = "invalid",
                verdict = EngineResultStatus.FAIL,
                action = request.action,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualActivityDispatchPlan(
                instanceId = instanceId,
                verdict = EngineResultStatus.FAIL,
                action = request.action,
                message = "runtime_not_found:$instanceId"
            )
        val targets = if (request.activityClassName != null) {
            explicitTargets(runtime, request)
        } else {
            implicitTargets(runtime, request)
        }
        val plan = VirtualActivityDispatchPlan(
            instanceId = runtime.instanceId,
            verdict = if (targets.isEmpty()) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL,
            action = request.action,
            targets = targets,
            supportedOperations = queryRuntimeBinding(instanceId).supportedOperations,
            unsupportedOperations = queryRuntimeBinding(instanceId).unsupportedOperations,
            message = when {
                targets.isEmpty() && request.activityClassName != null -> "explicit_activity_not_found"
                targets.isEmpty() -> "no_manifest_activity_match"
                request.activityClassName != null -> "explicit_activity_route_planned"
                else -> "implicit_activity_route_planned"
            }
        )
        recordActivityPlan(plan, runtime, request)
        return plan
    }

    override fun recordActivityDispatch(
        instanceId: String,
        result: VirtualActivityDispatchResult
    ): Boolean {
        if (instanceId.isBlank() || instanceId != result.instanceId) return false
        return runtimeService.registerOperationEvidence(
            instanceId = instanceId,
            evidence = EngineOperationEvidence(
                component = "activity",
                operation = "dispatch",
                verdict = result.verdict,
                entries = linkedMapOf(
                    "instanceId" to result.instanceId,
                    "activityClassName" to result.activityClassName.orEmpty(),
                    "action" to result.action.orEmpty(),
                    "reason" to result.reason,
                    "remapped" to result.remapped.toString(),
                    "proxyActivityClassName" to result.proxyActivityClassName.orEmpty(),
                    "launchFlags" to result.launchFlags.toString(),
                    "message" to result.message
                )
            )
        )
    }

    override fun syncActivityTaskState(
        instanceId: String,
        reason: String,
        tasks: List<VirtualTaskRecord>?
    ): VirtualActivityOperationResult {
        if (instanceId.isBlank()) {
            return operationFailure(
                instanceId = "invalid",
                token = null,
                operation = "sync-task-state",
                message = "instanceId must not be blank"
            )
        }
        require(reason.isNotBlank()) { "reason must not be blank" }
        val runtime = runtimeService.get(instanceId)
            ?: return operationFailure(
                instanceId = instanceId,
                token = null,
                operation = "sync-task-state",
                message = "runtime_not_found:$instanceId"
            )
        val sourceTasks = tasks ?: activityRecordManager.exportTasks()
        val instanceActivities = sourceTasks
            .asSequence()
            .flatMap { it.activities.asSequence() }
            .filter { it.instanceId == runtime.instanceId }
            .toList()
        if (instanceActivities.isEmpty()) {
            return operationFailure(
                instanceId = runtime.instanceId,
                token = null,
                operation = "sync-task-state",
                message = "activity_task_state_empty:$reason"
            )
        }
        activityTaskStateStore.mergeInstance(
            runtime.instanceId,
            EngineActivityTaskStateSnapshot(sourceTasks)
        )
        val topActivity = instanceActivities.last()
        return VirtualActivityOperationResult(
            instanceId = runtime.instanceId,
            operation = "sync-task-state",
            verdict = EngineResultStatus.PASS,
            token = topActivity.token,
            activityId = topActivity.activityId,
            activityClassName = topActivity.guestActivityClassName,
            state = topActivity.state,
            activity = topActivity,
            message = "activity_task_state_synced:$reason"
        )
    }

    override fun queryTaskState(instanceId: String): VirtualActivityTaskState {
        if (instanceId.isBlank()) {
            return VirtualActivityTaskState(
                instanceId = "invalid",
                verdict = EngineResultStatus.FAIL,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualActivityTaskState(
                instanceId = instanceId,
                verdict = EngineResultStatus.FAIL,
                message = "runtime_not_found:$instanceId"
            )
        val tasks = activityTaskStateStore.load()
            .tasks
            .mapNotNull { task ->
                val activities = task.activities.filter { activity ->
                    activity.instanceId == runtime.instanceId
                }
                if (activities.isEmpty()) {
                    null
                } else {
                    task.copy(activities = activities)
                }
            }
        val topTask = tasks.lastOrNull()
        val topActivity = topTask?.topActivity
        return VirtualActivityTaskState(
            instanceId = runtime.instanceId,
            verdict = EngineResultStatus.PARTIAL,
            taskCount = tasks.size,
            activityCount = tasks.sumOf { it.activities.size },
            topTaskId = topTask?.taskId,
            topActivityClassName = topActivity?.guestActivityClassName,
            topActivityState = topActivity?.state,
            tasks = tasks,
            supportedOperations = queryRuntimeBinding(instanceId).supportedOperations,
            unsupportedOperations = queryRuntimeBinding(instanceId).unsupportedOperations,
            message = if (tasks.isEmpty()) {
                "runtime_bound_but_no_activity_task_state"
            } else {
                "runtime_bound_with_activity_task_state"
            }
        )
    }

    private fun explicitTargets(
        runtime: VirtualInstanceRuntime,
        request: VirtualActivityDispatchPlanRequest
    ): List<VirtualActivityDispatchTarget> {
        if (!runtime.matchesActivityTargetPackage(request.targetPackageName)) return emptyList()
        val activityClassName = normalizeComponentClassName(
            runtime.originPackageName,
            request.activityClassName ?: return emptyList()
        )
        val activity = packageService.queryComponent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            className = activityClassName
        ) ?: return emptyList()
        return listOf(activity.toActivityTarget(runtime, request, reason = "explicit"))
    }

    private fun implicitTargets(
        runtime: VirtualInstanceRuntime,
        request: VirtualActivityDispatchPlanRequest
    ): List<VirtualActivityDispatchTarget> {
        if (request.action.isNullOrBlank()) return emptyList()
        if (!runtime.matchesActivityTargetPackage(request.targetPackageName)) return emptyList()
        return packageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.ACTIVITY,
            action = request.action,
            categories = request.categories,
            dataScheme = request.dataScheme,
            dataMimeType = request.dataMimeType,
            dataAuthority = request.dataAuthority,
            dataPath = request.dataPath
        ).take(1).map { activity ->
            activity.toActivityTarget(runtime, request, reason = "implicit")
        }
    }

    private fun recordActivityPlan(
        plan: VirtualActivityDispatchPlan,
        runtime: VirtualInstanceRuntime,
        request: VirtualActivityDispatchPlanRequest
    ) {
        runtimeService.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = EngineOperationEvidence(
                component = "activity",
                operation = "plan",
                verdict = plan.verdict,
                entries = linkedMapOf(
                    "activityPlanVerdict" to plan.verdict.name,
                    "activityPlanMessage" to plan.message,
                    "action" to request.action.orEmpty(),
                    "activityClassName" to request.activityClassName.orEmpty(),
                    "targetPackageName" to request.targetPackageName.orEmpty(),
                    "targetCount" to plan.targets.size.toString(),
                    "targetActivities" to plan.targets.joinToString(",") { it.activityClassName },
                    "processSlot" to runtime.processSlot,
                    "launchFlags" to request.launchFlags.toString(),
                    "supportedOperations" to plan.supportedOperations.sorted().joinToString(","),
                    "unsupportedOperations" to plan.unsupportedOperations.sorted().joinToString(",")
                )
            )
        )
    }

    private fun VirtualInstanceRuntime.matchesActivityTargetPackage(targetPackageName: String?): Boolean {
        val target = targetPackageName ?: return true
        return target == originPackageName || target == virtualPackageName || target == hostPackageName
    }

    private fun ResolvedComponent.toActivityTarget(
        runtime: VirtualInstanceRuntime,
        request: VirtualActivityDispatchPlanRequest,
        reason: String
    ): VirtualActivityDispatchTarget =
        VirtualActivityDispatchTarget(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            virtualPackageName = runtime.virtualPackageName,
            activityClassName = targetActivityName ?: name,
            action = request.action,
            reason = reason,
            processSlot = runtime.processSlot,
            processName = processName ?: runtime.processName,
            launchMode = launchMode,
            taskAffinity = taskAffinity,
            priority = resolvedIntentFilters.maxOfOrNull { it.priority } ?: 0
        )

    override fun markActivityState(
        instanceId: String,
        token: String,
        state: VirtualActivityState
    ): VirtualActivityOperationResult =
        mutateActivityRecord(instanceId, token, "mark-state") { manager ->
            manager.updateState(token, state)
        }

    override fun finishActivity(instanceId: String, token: String): VirtualActivityOperationResult =
        mutateActivityRecord(instanceId, token, "finish") { manager ->
            manager.finish(token)
        }

    override fun recordActivityResultForFinish(
        instanceId: String,
        token: String,
        resultCode: Int,
        dataIntent: VirtualIntentSnapshot?
    ): VirtualActivityOperationResult {
        val operation = "record-finish-result"
        if (instanceId.isBlank()) {
            return operationFailure("invalid", token, operation, "instanceId must not be blank")
        }
        val runtime = runtimeService.get(instanceId)
            ?: return operationFailure(
                instanceId,
                token,
                operation,
                "runtime_not_found:$instanceId"
            )
        if (token.isBlank()) {
            return operationFailure(runtime.instanceId, token, operation, "token must not be blank")
        }
        val manager = prepareManager(token)
        val finishingRecord = manager.resolve(token)
            ?: return operationFailure(
                runtime.instanceId,
                token,
                operation,
                "activity_record_not_found:$token"
            )
        if (finishingRecord.instanceId != runtime.instanceId) {
            return operationFailure(
                runtime.instanceId,
                token,
                operation,
                "activity_record_instance_mismatch:$token"
            )
        }
        val sourceToken = finishingRecord.resultToToken
            ?: return operationFailure(
                runtime.instanceId,
                token,
                operation,
                "activity_result_route_missing:$token"
            )
        if (finishingRecord.resultRequestCode < 0) {
            return operationFailure(
                runtime.instanceId,
                token,
                operation,
                "activity_result_request_code_missing:$token"
            )
        }
        val sourceRecord = manager.resolve(sourceToken)
            ?: return operationFailure(
                runtime.instanceId,
                sourceToken,
                operation,
                "activity_result_target_not_found:$sourceToken"
            )
        if (sourceRecord.instanceId != runtime.instanceId) {
            return operationFailure(
                runtime.instanceId,
                sourceToken,
                operation,
                "activity_result_target_instance_mismatch:$sourceToken"
            )
        }
        val updated = manager.setResult(
            token = sourceToken,
            resultCode = resultCode,
            dataIntent = dataIntent,
            requestCode = finishingRecord.resultRequestCode
        ) ?: return operationFailure(
            runtime.instanceId,
            sourceToken,
            operation,
            "activity_result_target_update_failed:$sourceToken"
        )
        persist(manager, runtime.instanceId)
        return VirtualActivityOperationResult(
            instanceId = runtime.instanceId,
            operation = operation,
            verdict = EngineResultStatus.PASS,
            token = updated.token,
            activityId = updated.activityId,
            activityClassName = updated.guestActivityClassName,
            state = updated.state,
            activity = updated,
            requestCode = finishingRecord.resultRequestCode,
            resultCode = resultCode,
            dataIntent = dataIntent,
            message = "activity_finish_result_persisted"
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
    ): VirtualActivityOperationResult =
        mutateActivityRecord(instanceId, token, "set-result") { manager ->
            manager.setResult(
                token = token,
                resultCode = resultCode,
                dataIntent = dataIntent,
                requestCode = requestCode,
                resultWho = resultWho,
                frameworkDispatchAttempted = frameworkDispatchAttempted,
                frameworkDispatchInvoked = frameworkDispatchInvoked
            )
        }

    override fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult? {
        val runtime = runtimeService.get(instanceId) ?: return null
        if (token.isBlank()) return null
        val manager = prepareManager(token)
        val record = manager.resolve(token)?.takeIf { it.instanceId == runtime.instanceId } ?: return null
        val result = manager.consumeResult(record.token) ?: return null
        persist(manager, runtime.instanceId)
        return result
    }

    override fun consumeActivityResultForResumeFallback(instanceId: String, token: String): VirtualActivityResult? {
        val runtime = runtimeService.get(instanceId) ?: return null
        if (token.isBlank()) return null
        val manager = prepareManager(token)
        val record = manager.resolve(token)?.takeIf { it.instanceId == runtime.instanceId } ?: return null
        val result = manager.consumeResultForResumeFallback(record.token) ?: return null
        persist(manager, runtime.instanceId)
        return result
    }

    override fun markActivityResultDispatchState(
        instanceId: String,
        token: String,
        frameworkDispatchAttempted: Boolean,
        frameworkDispatchInvoked: Boolean
    ): VirtualActivityOperationResult =
        mutateActivityRecord(instanceId, token, "mark-result-dispatch") { manager ->
            manager.markResultDispatchState(
                token = token,
                frameworkDispatchAttempted = frameworkDispatchAttempted,
                frameworkDispatchInvoked = frameworkDispatchInvoked
            )
        }

    override fun consumePendingNewIntent(instanceId: String, token: String): VirtualActivityPendingNewIntent? {
        val runtime = runtimeService.get(instanceId) ?: return null
        if (token.isBlank()) return null
        val manager = prepareManager(token)
        val record = manager.resolve(token)?.takeIf { it.instanceId == runtime.instanceId } ?: return null
        val pending = manager.consumePendingNewIntent(record.token) ?: return null
        persist(manager, runtime.instanceId)
        return pending
    }

    private fun mutateActivityRecord(
        instanceId: String,
        token: String,
        operation: String,
        mutate: (VirtualActivityRecordManager) -> VirtualActivityRecord?
    ): VirtualActivityOperationResult {
        if (instanceId.isBlank()) {
            return operationFailure(
                instanceId = "invalid",
                token = token,
                operation = operation,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return operationFailure(
                instanceId = instanceId,
                token = token,
                operation = operation,
                message = "runtime_not_found:$instanceId"
            )
        if (token.isBlank()) {
            return operationFailure(
                instanceId = runtime.instanceId,
                token = token,
                operation = operation,
                message = "token must not be blank"
            )
        }
        val manager = prepareManager(token)
        val existing = manager.resolve(token)
            ?: return operationFailure(
                instanceId = runtime.instanceId,
                token = token,
                operation = operation,
                message = "activity_record_not_found:$token"
            )
        if (existing.instanceId != runtime.instanceId) {
            return operationFailure(
                instanceId = runtime.instanceId,
                token = token,
                operation = operation,
                message = "activity_record_instance_mismatch:$token"
            )
        }
        val updated = mutate(manager)
            ?: return operationFailure(
                instanceId = runtime.instanceId,
                token = token,
                operation = operation,
                message = "activity_record_operation_failed:$token"
            )
        persist(manager, runtime.instanceId)
        return VirtualActivityOperationResult(
            instanceId = runtime.instanceId,
            operation = operation,
            verdict = EngineResultStatus.PASS,
            token = updated.token,
            activityId = updated.activityId,
            activityClassName = updated.guestActivityClassName,
            state = updated.state,
            activity = updated,
            message = "activity_record_${operation.replace('-', '_')}_persisted"
        )
    }

    private fun prepareManager(token: String): VirtualActivityRecordManager =
        activityRecordManager.also { manager ->
            val records = EngineActivityTaskRecords(manager, activityTaskStateStore)
            if (manager.resolve(token) == null) {
                val restoredIfEmpty = records.restorePersistedIfEmpty()
                if (restoredIfEmpty == 0 && manager.resolve(token) == null) {
                    records.restorePersisted()
                }
            }
        }

    private fun persist(manager: VirtualActivityRecordManager, instanceId: String) {
        activityTaskStateStore.mergeInstance(
            instanceId,
            EngineActivityTaskStateSnapshot(manager.exportTasks())
        )
    }

    private fun operationFailure(
        instanceId: String,
        token: String?,
        operation: String,
        message: String
    ): VirtualActivityOperationResult = VirtualActivityOperationResult(
        instanceId = instanceId,
        operation = operation,
        verdict = EngineResultStatus.FAIL,
        token = token?.takeIf { it.isNotBlank() },
        message = message
    )
}

internal class RegistryBackedVirtualProviderService(
    private val runtimeService: VirtualRuntimeService,
    private val packageService: VirtualPackageService,
    private val stateStore: EngineProviderRuntimeStateStore = InMemoryEngineProviderRuntimeStateStore(),
    private val uriGrantStore: EngineProviderUriGrantStore = InMemoryEngineProviderUriGrantStore()
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.PROVIDER,
    supportedOperations = setOf(
        "route-token",
        "same-process-preinstall",
        "authority-lookup",
        "operation-route-plan",
        "uri-grant-record",
        "uri-grant-check",
        "uri-grant-revoke"
    ),
    unsupportedOperations = setOf(
        "persisted-uri-grant-take-release",
        "external-uri-grant",
        "custom-process-provider"
    )
), VirtualProviderService {
    private val supportedProviderOperations = setOf(
        "route-token",
        "same-process-preinstall",
        "authority-lookup",
        "operation-route-plan",
        "uri-grant-record",
        "uri-grant-check",
        "uri-grant-revoke"
    )
    private val unsupportedProviderOperations = setOf(
        "persisted-uri-grant-take-release",
        "external-uri-grant",
        "custom-process-provider"
    )

    private data class ProviderAuthorizationDecision(
        val verdict: EngineResultStatus,
        val message: String,
        val accessType: VirtualProviderAccessType,
        val requiredPermission: String? = null,
        val callerIdentityVerdict: String,
        val permissionVerdict: String,
        val appOpsVerdict: String
    ) {
        val allowed: Boolean
            get() = verdict == EngineResultStatus.PASS || verdict == EngineResultStatus.PARTIAL
    }

    override fun resolveProviderAuthority(
        callerInstanceId: String,
        request: VirtualProviderAuthorityResolveRequest
    ): VirtualProviderAuthorityResolveResult {
        val caller = runtimeService.get(callerInstanceId)
            ?: return authorityResult(
                callerInstanceId,
                request,
                EngineResultStatus.FAIL,
                virtualAuthority = false,
                message = "runtime_not_found:$callerInstanceId"
            )
        val owners = runtimeService.list().filter { runtime ->
            packageService.queryProviderByAuthority(runtime.instanceId, request.guestAuthority) != null
        }
        if (owners.isEmpty()) {
            return authorityResult(
                caller.instanceId,
                request,
                EngineResultStatus.FAIL,
                virtualAuthority = false,
                message = "provider_authority_not_virtual:${request.guestAuthority}"
            )
        }
        owners.firstOrNull { it.instanceId == caller.instanceId }?.let { self ->
            return authorityResult(
                caller.instanceId,
                request,
                EngineResultStatus.PASS,
                virtualAuthority = true,
                targetInstanceId = self.instanceId,
                message = "provider_authority_resolved_self"
            )
        }
        val grantMode = when (
            VirtualProviderDispatchPlanRequest(
                operation = request.operation,
                guestAuthority = request.guestAuthority,
                encodedPath = request.encodedPath,
                accessMode = request.accessMode
            ).accessType()
        ) {
            VirtualProviderAccessType.READ -> EngineProviderUriGrantModes.READ
            VirtualProviderAccessType.WRITE -> EngineProviderUriGrantModes.WRITE
            VirtualProviderAccessType.NONE -> 0
        }
        val grantedOwners = if (grantMode == 0) {
            emptyList()
        } else {
            owners.filter { owner ->
                uriGrantStore.findGrant(
                    ownerInstanceId = owner.instanceId,
                    targetInstanceId = caller.instanceId,
                    guestAuthority = request.guestAuthority,
                    encodedPath = request.encodedPath,
                    requiredModeFlags = grantMode
                ) != null
            }
        }
        if (grantedOwners.size == 1) {
            return authorityResult(
                caller.instanceId,
                request,
                EngineResultStatus.PARTIAL,
                virtualAuthority = true,
                targetInstanceId = grantedOwners.single().instanceId,
                message = "provider_authority_resolved_by_uri_grant"
            )
        }
        if (grantedOwners.size > 1) {
            return authorityResult(
                caller.instanceId,
                request,
                EngineResultStatus.UNSUPPORTED,
                virtualAuthority = true,
                message = "provider_authority_grant_ambiguous:${request.guestAuthority}"
            )
        }
        return if (owners.size == 1) {
            authorityResult(
                caller.instanceId,
                request,
                EngineResultStatus.PARTIAL,
                virtualAuthority = true,
                targetInstanceId = owners.single().instanceId,
                message = "provider_authority_resolved_unique_owner"
            )
        } else {
            authorityResult(
                caller.instanceId,
                request,
                EngineResultStatus.UNSUPPORTED,
                virtualAuthority = true,
                message = "provider_authority_owner_ambiguous:${request.guestAuthority}"
            )
        }
    }

    private fun authorityResult(
        callerInstanceId: String,
        request: VirtualProviderAuthorityResolveRequest,
        verdict: EngineResultStatus,
        virtualAuthority: Boolean,
        targetInstanceId: String? = null,
        message: String
    ) = VirtualProviderAuthorityResolveResult(
        callerInstanceId = callerInstanceId.ifBlank { "invalid" },
        guestAuthority = request.guestAuthority,
        verdict = verdict,
        virtualAuthority = virtualAuthority,
        targetInstanceId = targetInstanceId,
        message = message
    )

    override fun planProvider(
        instanceId: String,
        request: VirtualProviderDispatchPlanRequest
    ): VirtualProviderDispatchPlan {
        if (instanceId.isBlank()) {
            return VirtualProviderDispatchPlan(
                instanceId = "invalid",
                operation = request.operation,
                verdict = EngineResultStatus.FAIL,
                guestAuthority = request.guestAuthority,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualProviderDispatchPlan(
                instanceId = instanceId,
                operation = request.operation,
                verdict = EngineResultStatus.FAIL,
                guestAuthority = request.guestAuthority,
                message = "runtime_not_found:$instanceId"
            )
        if (request.processSlot != null && request.processSlot != runtime.processSlot) {
            val plan = VirtualProviderDispatchPlan(
                instanceId = runtime.instanceId,
                operation = request.operation,
                verdict = EngineResultStatus.FAIL,
                guestAuthority = request.guestAuthority,
                supportedOperations = supportedProviderOperations,
                unsupportedOperations = unsupportedProviderOperations,
                message = "provider_process_slot_mismatch:expected=${request.processSlot}:actual=${runtime.processSlot}"
            )
            recordPlanEvidence(plan, runtime, request)
            return plan
        }
        if (request.operation == EngineProviderOperation.UNKNOWN) {
            val plan = VirtualProviderDispatchPlan(
                instanceId = runtime.instanceId,
                operation = request.operation,
                verdict = EngineResultStatus.UNSUPPORTED,
                guestAuthority = request.guestAuthority,
                supportedOperations = supportedProviderOperations,
                unsupportedOperations = setOf("unknown-operation"),
                message = "provider_operation_unsupported:UNKNOWN"
            )
            recordPlanEvidence(plan, runtime, request)
            return plan
        }
        request.operation.unsupportedControlSemantic()?.let { unsupportedSemantic ->
            val plan = VirtualProviderDispatchPlan(
                instanceId = runtime.instanceId,
                operation = request.operation,
                verdict = EngineResultStatus.UNSUPPORTED,
                guestAuthority = request.guestAuthority,
                supportedOperations = supportedProviderOperations,
                unsupportedOperations = setOf(unsupportedSemantic),
                message = "provider_semantics_unsupported:$unsupportedSemantic"
            )
            recordPlanEvidence(plan, runtime, request)
            return plan
        }
        val provider = packageService.queryProviderByAuthority(runtime.instanceId, request.guestAuthority)
        val target = provider?.toProviderTarget(runtime, request)
        val authorization = if (provider != null && target != null) {
            authorizeProvider(runtime, target, provider, request)
        } else {
            null
        }
        val plan = VirtualProviderDispatchPlan(
            instanceId = runtime.instanceId,
            operation = request.operation,
            verdict = authorization?.verdict ?: EngineResultStatus.FAIL,
            guestAuthority = request.guestAuthority,
            targets = if (authorization?.allowed == true) listOfNotNull(target) else emptyList(),
            supportedOperations = supportedProviderOperations,
            unsupportedOperations = request.unsupportedOperationsForPlan(),
            message = if (target == null) {
                "provider_not_found:${request.guestAuthority}"
            } else {
                authorization?.message ?: "provider_authorization_missing"
            }
        )
        recordPlanEvidence(plan, runtime, request, authorization)
        return plan
    }

    override fun grantUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult {
        val owner = runtimeService.get(ownerInstanceId)
            ?: return uriGrantFailure(ownerInstanceId, request, "runtime_not_found:$ownerInstanceId")
        validateUriGrantCaller(owner, request)?.let { return it }
        validateUriGrantModes(owner.instanceId, request)?.let { return it }
        val provider = packageService.queryProviderByAuthority(owner.instanceId, request.guestAuthority)
            ?: return uriGrantFailure(
                owner.instanceId,
                request,
                "provider_not_found:${request.guestAuthority}"
            )
        val targetResolution = resolveUriGrantTarget(owner, request)
        val target = targetResolution.runtime ?: return uriGrantFailure(
            owner.instanceId,
            request,
            targetResolution.message,
            verdict = targetResolution.verdict
        )
        if (target.instanceId == owner.instanceId) {
            return uriGrantResult(
                owner = owner,
                target = target,
                request = request,
                verdict = EngineResultStatus.PASS,
                granted = true,
                affectedGrantCount = 0,
                message = "provider_uri_grant_self_access"
            ).also(::recordUriGrantEvidence)
        }
        if (!provider.canGrantUriPermission(request.encodedPath)) {
            return uriGrantFailure(
                owner.instanceId,
                request,
                "provider_uri_grant_not_allowed",
                target.instanceId
            ).also(::recordUriGrantEvidence)
        }
        val accessModes = request.modeFlags and EngineProviderUriGrantModes.ACCESS_MASK
        val now = System.currentTimeMillis()
        val record = uriGrantStore.grant(
            EngineProviderUriGrantRecord(
                ownerInstanceId = owner.instanceId,
                targetInstanceId = target.instanceId,
                targetPackageName = request.targetPackageName ?: target.virtualPackageName,
                guestAuthority = request.guestAuthority,
                encodedPath = request.encodedPath,
                modeFlags = accessModes,
                prefix = request.modeFlags and EngineProviderUriGrantModes.PREFIX != 0,
                persistable = request.modeFlags and EngineProviderUriGrantModes.PERSISTABLE != 0,
                createdAtMs = now,
                updatedAtMs = now
            )
        )
        return uriGrantResult(
            owner = owner,
            target = target,
            request = request,
            verdict = EngineResultStatus.PARTIAL,
            granted = true,
            affectedGrantCount = 1,
            message = "provider_uri_grant_recorded:modes=${record.modeFlags}"
        ).also(::recordUriGrantEvidence)
    }

    override fun revokeUriPermission(
        ownerInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult {
        val owner = runtimeService.get(ownerInstanceId)
            ?: return uriGrantFailure(ownerInstanceId, request, "runtime_not_found:$ownerInstanceId")
        validateUriGrantCaller(owner, request)?.let { return it }
        validateUriGrantModes(owner.instanceId, request)?.let { return it }
        if (packageService.queryProviderByAuthority(owner.instanceId, request.guestAuthority) == null) {
            return uriGrantFailure(
                owner.instanceId,
                request,
                "provider_not_found:${request.guestAuthority}"
            )
        }
        val explicitTarget = if (request.targetInstanceId != null || request.targetPackageName != null) {
            val resolution = resolveUriGrantTarget(owner, request)
            resolution.runtime ?: return uriGrantFailure(
                owner.instanceId,
                request,
                resolution.message,
                verdict = resolution.verdict
            ).also(::recordUriGrantEvidence)
        } else {
            null
        }
        val changed = uriGrantStore.revoke(
            ownerInstanceId = owner.instanceId,
            targetInstanceId = explicitTarget?.instanceId,
            guestAuthority = request.guestAuthority,
            encodedPath = request.encodedPath,
            modeFlags = request.modeFlags and EngineProviderUriGrantModes.ACCESS_MASK
        )
        return uriGrantResult(
            owner = owner,
            target = explicitTarget,
            request = request,
            verdict = EngineResultStatus.PARTIAL,
            granted = false,
            affectedGrantCount = changed,
            message = "provider_uri_grant_revoked:count=$changed"
        ).also(::recordUriGrantEvidence)
    }

    override fun checkUriPermission(
        targetInstanceId: String,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult {
        val target = runtimeService.get(targetInstanceId)
            ?: return uriGrantFailure(
                request.ownerInstanceId,
                request,
                "runtime_not_found:$targetInstanceId",
                targetInstanceId
            )
        validateUriGrantCaller(target, request)?.let { return it }
        validateUriGrantModes(request.ownerInstanceId, request)?.let { return it }
        val owners = request.ownerInstanceId?.let { listOfNotNull(runtimeService.get(it)) }
            ?: runtimeService.list().filter { runtime ->
                packageService.queryProviderByAuthority(runtime.instanceId, request.guestAuthority) != null
            }
        val owner = when {
            owners.isEmpty() -> return uriGrantFailure(
                null,
                request,
                "provider_uri_grant_owner_not_found:${request.guestAuthority}",
                target.instanceId
            )
            owners.size > 1 -> return uriGrantFailure(
                null,
                request,
                "provider_uri_grant_owner_ambiguous:${request.guestAuthority}",
                target.instanceId,
                EngineResultStatus.UNSUPPORTED
            )
            else -> owners.single()
        }
        if (owner.instanceId == target.instanceId) {
            return uriGrantResult(
                owner,
                target,
                request,
                EngineResultStatus.PASS,
                granted = true,
                affectedGrantCount = 0,
                message = "provider_uri_grant_self_access"
            ).also(::recordUriGrantEvidence)
        }
        val accessModes = request.modeFlags and EngineProviderUriGrantModes.ACCESS_MASK
        val grant = uriGrantStore.findGrant(
            ownerInstanceId = owner.instanceId,
            targetInstanceId = target.instanceId,
            guestAuthority = request.guestAuthority,
            encodedPath = request.encodedPath,
            requiredModeFlags = accessModes
        )
        return uriGrantResult(
            owner,
            target,
            request,
            verdict = if (grant == null) EngineResultStatus.FAIL else EngineResultStatus.PASS,
            granted = grant != null,
            affectedGrantCount = if (grant == null) 0 else 1,
            message = if (grant == null) {
                "provider_uri_grant_denied"
            } else {
                "provider_uri_grant_confirmed"
            }
        ).also(::recordUriGrantEvidence)
    }

    override fun recordProviderDispatch(
        instanceId: String,
        result: VirtualProviderOperationResult
    ): Boolean {
        if (instanceId.isBlank() || instanceId != result.instanceId) return false
        val runtime = runtimeService.get(instanceId) ?: return false
        updateRuntimeState(runtime, result)
        return runtimeService.registerOperationEvidence(
            instanceId = instanceId,
            evidence = EngineOperationEvidence(
                component = "provider",
                operation = "dispatch",
                verdict = result.verdict,
                entries = linkedMapOf(
                    "instanceId" to result.instanceId,
                    "operation" to result.operation.name,
                    "guestAuthority" to result.guestAuthority.orEmpty(),
                    "proxyAuthority" to result.proxyAuthority.orEmpty(),
                    "providerClassName" to result.providerClassName.orEmpty(),
                    "reason" to result.reason,
                    "ready" to result.ready.toString(),
                    "cached" to result.cached.toString(),
                    "processSlot" to runtime.processSlot,
                    "runtimeEpoch" to runtime.runtimeEpoch.toString(),
                    "message" to result.message
                )
            )
        )
    }

    override fun queryProviderRuntimeState(instanceId: String): VirtualProviderRuntimeState {
        if (instanceId.isBlank()) {
            return VirtualProviderRuntimeState(
                instanceId = "invalid",
                verdict = EngineResultStatus.FAIL,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualProviderRuntimeState(
                instanceId = instanceId,
                verdict = EngineResultStatus.FAIL,
                message = "runtime_not_found:$instanceId"
            )
        val records = stateStore.list(runtime.instanceId)
            .filter { it.runtimeEpoch == runtime.runtimeEpoch }
        return VirtualProviderRuntimeState(
            instanceId = runtime.instanceId,
            verdict = EngineResultStatus.PARTIAL,
            records = records,
            message = if (records.isEmpty()) {
                "runtime_bound_but_no_provider_state"
            } else {
                "runtime_bound_with_provider_state"
            }
        )
    }

    private fun updateRuntimeState(
        runtime: VirtualInstanceRuntime,
        result: VirtualProviderOperationResult
    ) {
        if (!result.ready || result.verdict != EngineResultStatus.PASS) return
        val guestAuthority = result.guestAuthority ?: return
        val providerClassName = result.providerClassName ?: return
        val existing = stateStore.list(runtime.instanceId)
            .firstOrNull {
                it.guestAuthority == guestAuthority && it.runtimeEpoch == runtime.runtimeEpoch
            }
        stateStore.upsert(
            EngineProviderRuntimeRecord(
                instanceId = runtime.instanceId,
                guestAuthority = guestAuthority,
                providerClassName = providerClassName,
                processSlot = runtime.processSlot,
                runtimeEpoch = runtime.runtimeEpoch,
                cached = result.cached,
                lastOperation = result.operation,
                operationCount = (existing?.operationCount ?: 0L) + 1L
            )
        )
    }

    private data class UriGrantTargetResolution(
        val runtime: VirtualInstanceRuntime?,
        val verdict: EngineResultStatus,
        val message: String
    )

    private fun resolveUriGrantTarget(
        owner: VirtualInstanceRuntime,
        request: VirtualProviderUriGrantRequest
    ): UriGrantTargetResolution {
        request.targetInstanceId?.let { targetInstanceId ->
            val target = runtimeService.get(targetInstanceId)
                ?: return UriGrantTargetResolution(
                    null,
                    EngineResultStatus.FAIL,
                    "provider_uri_grant_target_runtime_not_found:$targetInstanceId"
                )
            if (
                request.targetPackageName != null &&
                !target.matchesUriGrantPackage(request.targetPackageName)
            ) {
                return UriGrantTargetResolution(
                    null,
                    EngineResultStatus.FAIL,
                    "provider_uri_grant_target_package_mismatch:${request.targetPackageName}"
                )
            }
            return UriGrantTargetResolution(target, EngineResultStatus.PASS, "target_resolved_by_instance")
        }
        val targetPackageName = request.targetPackageName
            ?: return UriGrantTargetResolution(
                null,
                EngineResultStatus.FAIL,
                "provider_uri_grant_target_missing"
            )
        if (owner.matchesUriGrantPackage(targetPackageName)) {
            return UriGrantTargetResolution(owner, EngineResultStatus.PASS, "target_resolved_as_self")
        }
        val candidates = runtimeService.list().filter { it.matchesUriGrantPackage(targetPackageName) }
        return when (candidates.size) {
            0 -> UriGrantTargetResolution(
                null,
                EngineResultStatus.UNSUPPORTED,
                "provider_uri_grant_external_target_unsupported:$targetPackageName"
            )
            1 -> UriGrantTargetResolution(
                candidates.single(),
                EngineResultStatus.PASS,
                "target_resolved_by_package"
            )
            else -> UriGrantTargetResolution(
                null,
                EngineResultStatus.UNSUPPORTED,
                "provider_uri_grant_target_ambiguous:$targetPackageName"
            )
        }
    }

    private fun validateUriGrantCaller(
        runtime: VirtualInstanceRuntime,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        if (request.hostUid < 0 || request.callingUid < 0) {
            return uriGrantFailure(
                runtime.instanceId,
                request,
                "provider_uri_grant_caller_uid_missing",
                request.targetInstanceId
            )
        }
        if (request.callingUid != request.hostUid) {
            return uriGrantFailure(
                runtime.instanceId,
                request,
                "provider_uri_grant_caller_uid_mismatch:expected=${request.hostUid}:actual=${request.callingUid}",
                request.targetInstanceId
            )
        }
        if (request.callingPid <= 0) {
            return uriGrantFailure(
                runtime.instanceId,
                request,
                "provider_uri_grant_caller_pid_missing",
                request.targetInstanceId
            )
        }
        runtime.processId?.let { expectedPid ->
            if (expectedPid != request.callingPid) {
                return uriGrantFailure(
                    runtime.instanceId,
                    request,
                    "provider_uri_grant_caller_pid_mismatch:expected=$expectedPid:actual=${request.callingPid}",
                    request.targetInstanceId
                )
            }
        }
        return null
    }

    private fun validateUriGrantModes(
        ownerInstanceId: String?,
        request: VirtualProviderUriGrantRequest
    ): VirtualProviderUriGrantResult? {
        if (request.modeFlags and EngineProviderUriGrantModes.ACCESS_MASK == 0) {
            return uriGrantFailure(
                ownerInstanceId,
                request,
                "provider_uri_grant_access_mode_missing",
                request.targetInstanceId
            )
        }
        val unsupported = request.modeFlags and EngineProviderUriGrantModes.SUPPORTED_REQUEST_MASK.inv()
        if (unsupported != 0) {
            return uriGrantFailure(
                ownerInstanceId,
                request,
                "provider_uri_grant_flags_unsupported:$unsupported",
                request.targetInstanceId,
                EngineResultStatus.UNSUPPORTED
            )
        }
        return null
    }

    private fun VirtualInstanceRuntime.matchesUriGrantPackage(packageName: String): Boolean =
        packageName == originPackageName || packageName == virtualPackageName

    private fun uriGrantFailure(
        ownerInstanceId: String?,
        request: VirtualProviderUriGrantRequest,
        message: String,
        targetInstanceId: String? = request.targetInstanceId,
        verdict: EngineResultStatus = EngineResultStatus.FAIL
    ): VirtualProviderUriGrantResult = VirtualProviderUriGrantResult(
        ownerInstanceId = ownerInstanceId,
        targetInstanceId = targetInstanceId,
        guestAuthority = request.guestAuthority,
        encodedPath = request.encodedPath,
        modeFlags = request.modeFlags,
        verdict = verdict,
        granted = false,
        message = message
    )

    private fun uriGrantResult(
        owner: VirtualInstanceRuntime,
        target: VirtualInstanceRuntime?,
        request: VirtualProviderUriGrantRequest,
        verdict: EngineResultStatus,
        granted: Boolean,
        affectedGrantCount: Int,
        message: String
    ): VirtualProviderUriGrantResult = VirtualProviderUriGrantResult(
        ownerInstanceId = owner.instanceId,
        targetInstanceId = target?.instanceId ?: request.targetInstanceId,
        guestAuthority = request.guestAuthority,
        encodedPath = request.encodedPath,
        modeFlags = request.modeFlags,
        verdict = verdict,
        granted = granted,
        affectedGrantCount = affectedGrantCount,
        message = message
    )

    private fun recordUriGrantEvidence(result: VirtualProviderUriGrantResult) {
        val evidenceInstanceId = result.ownerInstanceId ?: result.targetInstanceId ?: return
        runtimeService.registerOperationEvidence(
            evidenceInstanceId,
            EngineOperationEvidence(
                component = "provider",
                operation = when {
                    result.message.startsWith("provider_uri_grant_revoked") -> "revoke-uri-permission"
                    result.message.contains("confirmed") || result.message.contains("denied") ->
                        "check-uri-permission"
                    else -> "grant-uri-permission"
                },
                verdict = result.verdict,
                entries = linkedMapOf(
                    "ownerInstanceId" to result.ownerInstanceId.orEmpty(),
                    "targetInstanceId" to result.targetInstanceId.orEmpty(),
                    "guestAuthority" to result.guestAuthority,
                    "encodedPath" to result.encodedPath,
                    "modeFlags" to result.modeFlags.toString(),
                    "granted" to result.granted.toString(),
                    "affectedGrantCount" to result.affectedGrantCount.toString(),
                    "message" to result.message
                )
            )
        )
    }

    private fun recordPlanEvidence(
        plan: VirtualProviderDispatchPlan,
        runtime: VirtualInstanceRuntime,
        request: VirtualProviderDispatchPlanRequest,
        authorization: ProviderAuthorizationDecision? = null
    ) {
        runtimeService.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = EngineOperationEvidence(
                component = "provider",
                operation = "plan",
                verdict = plan.verdict,
                entries = linkedMapOf(
                    "providerPlanVerdict" to plan.verdict.name,
                    "providerPlanMessage" to plan.message,
                    "operation" to request.operation.name,
                    "guestAuthority" to request.guestAuthority,
                    "proxyAuthority" to request.proxyAuthority.orEmpty(),
                    "processSlot" to (request.processSlot ?: runtime.processSlot),
                    "routeTokenPresent" to request.routeTokenPresent.toString(),
                    "routeTokenVerified" to request.routeTokenVerified.toString(),
                    "callerInstanceId" to request.callerInstanceId.orEmpty(),
                    "targetInstanceId" to request.targetInstanceId.orEmpty(),
                    "callingUid" to request.callingUid.toString(),
                    "callingPid" to request.callingPid.toString(),
                    "hostUid" to request.hostUid.toString(),
                    "callerProcessSlot" to request.callerProcessSlot.orEmpty(),
                    "engineCallingUid" to request.engineCallingUid.toString(),
                    "engineCallingPid" to request.engineCallingPid.toString(),
                    "providerAccessType" to (authorization?.accessType?.name ?: request.accessType().name),
                    "providerRequiredPermission" to authorization?.requiredPermission.orEmpty(),
                    "providerCallerIdentityVerdict" to
                        (authorization?.callerIdentityVerdict ?: "NOT_EVALUATED"),
                    "providerPermissionVerdict" to
                        (authorization?.permissionVerdict ?: "NOT_EVALUATED"),
                    "providerAppOpsVerdict" to
                        (authorization?.appOpsVerdict ?: "NOT_EVALUATED"),
                    "providerAuthorizationVerdict" to
                        (authorization?.verdict?.name ?: "NOT_EVALUATED"),
                    "targetCount" to plan.targets.size.toString(),
                    "targetProviders" to plan.targets.joinToString(",") { it.providerClassName },
                    "supportedOperations" to plan.supportedOperations.sorted().joinToString(","),
                    "unsupportedOperations" to plan.unsupportedOperations.sorted().joinToString(",")
                )
            )
        )
    }

    private fun authorizeProvider(
        targetRuntime: VirtualInstanceRuntime,
        target: VirtualProviderDispatchTarget,
        provider: ResolvedComponent,
        request: VirtualProviderDispatchPlanRequest
    ): ProviderAuthorizationDecision {
        val accessType = request.accessType()
        val requiredPermission = provider.requiredPermission(
            request.operation,
            accessType,
            request.encodedPath
        )

        fun reject(
            message: String,
            verdict: EngineResultStatus = EngineResultStatus.FAIL,
            callerIdentityVerdict: String = "REJECTED",
            permissionVerdict: String = "NOT_EVALUATED",
            appOpsVerdict: String = "NOT_EVALUATED"
        ) = ProviderAuthorizationDecision(
            verdict = verdict,
            message = message,
            accessType = accessType,
            requiredPermission = requiredPermission,
            callerIdentityVerdict = callerIdentityVerdict,
            permissionVerdict = permissionVerdict,
            appOpsVerdict = appOpsVerdict
        )

        if (!request.routeTokenPresent) {
            return reject("provider_route_token_missing", callerIdentityVerdict = "TOKEN_MISSING")
        }
        if (!request.routeTokenVerified) {
            return reject("provider_route_token_unverified", callerIdentityVerdict = "TOKEN_UNVERIFIED")
        }
        val targetInstanceId = request.targetInstanceId
            ?: return reject("provider_target_instance_missing", callerIdentityVerdict = "TARGET_MISSING")
        if (targetInstanceId != targetRuntime.instanceId || target.instanceId != targetInstanceId) {
            return reject(
                "provider_target_instance_mismatch:expected=${targetRuntime.instanceId}:actual=$targetInstanceId",
                callerIdentityVerdict = "TARGET_MISMATCH"
            )
        }
        val callerInstanceId = request.callerInstanceId
            ?: return reject("provider_caller_instance_missing", callerIdentityVerdict = "CALLER_MISSING")
        val callerRuntime = runtimeService.get(callerInstanceId)
            ?: return reject(
                "provider_caller_runtime_not_found:$callerInstanceId",
                callerIdentityVerdict = "CALLER_RUNTIME_MISSING"
            )
        if (request.hostUid < 0 || request.callingUid < 0) {
            return reject("provider_caller_uid_missing", callerIdentityVerdict = "UID_MISSING")
        }
        if (request.callingUid != request.hostUid) {
            return reject(
                "provider_caller_uid_mismatch:expected=${request.hostUid}:actual=${request.callingUid}",
                callerIdentityVerdict = "UID_MISMATCH"
            )
        }
        if (request.engineCallingUid >= 0 && request.engineCallingUid != request.hostUid) {
            return reject(
                "provider_engine_caller_uid_mismatch:expected=${request.hostUid}:actual=${request.engineCallingUid}",
                callerIdentityVerdict = "ENGINE_UID_MISMATCH"
            )
        }
        if (request.callingPid <= 0) {
            return reject("provider_caller_pid_missing", callerIdentityVerdict = "PID_MISSING")
        }
        request.callerProcessSlot?.let { callerProcessSlot ->
            if (callerProcessSlot != callerRuntime.processSlot) {
                return reject(
                    "provider_caller_process_slot_mismatch:expected=${callerRuntime.processSlot}:actual=$callerProcessSlot",
                    callerIdentityVerdict = "CALLER_PROCESS_SLOT_MISMATCH"
                )
            }
        }
        callerRuntime.processId?.let { expectedPid ->
            if (request.callingPid != expectedPid) {
                return reject(
                    "provider_caller_pid_mismatch:expected=$expectedPid:actual=${request.callingPid}",
                    callerIdentityVerdict = "PID_MISMATCH"
                )
            }
        }

        val selfAccess = callerInstanceId == targetRuntime.instanceId
        if (selfAccess) {
            return ProviderAuthorizationDecision(
                verdict = EngineResultStatus.PARTIAL,
                message = "provider_route_planned",
                accessType = accessType,
                requiredPermission = requiredPermission,
                callerIdentityVerdict = if (callerRuntime.processId == null) {
                    "PASS_PID_UNBOUND"
                } else {
                    "PASS"
                },
                permissionVerdict = "SELF_INSTANCE_BYPASS",
                appOpsVerdict = "SELF_INSTANCE_BYPASS"
            )
        }
        if (callerRuntime.processId == null) {
            return reject(
                message = "provider_cross_instance_caller_pid_unbound",
                verdict = EngineResultStatus.UNSUPPORTED,
                callerIdentityVerdict = "PID_UNBOUND",
                permissionVerdict = "NOT_EVALUATED",
                appOpsVerdict = "NOT_EVALUATED"
            )
        }
        val requiredGrantModes = when (accessType) {
            VirtualProviderAccessType.READ -> EngineProviderUriGrantModes.READ
            VirtualProviderAccessType.WRITE -> EngineProviderUriGrantModes.WRITE
            VirtualProviderAccessType.NONE -> 0
        }
        val uriGrant = if (requiredGrantModes != 0 && provider.canGrantUriPermission(request.encodedPath)) {
            uriGrantStore.findGrant(
                ownerInstanceId = targetRuntime.instanceId,
                targetInstanceId = callerRuntime.instanceId,
                guestAuthority = request.guestAuthority,
                encodedPath = request.encodedPath,
                requiredModeFlags = requiredGrantModes
            )
        } else {
            null
        }
        if (uriGrant != null) {
            return ProviderAuthorizationDecision(
                verdict = EngineResultStatus.PARTIAL,
                message = "provider_route_planned_with_uri_grant",
                accessType = accessType,
                requiredPermission = requiredPermission,
                callerIdentityVerdict = "PASS",
                permissionVerdict = if (uriGrant.prefix) "URI_PREFIX_GRANT" else "URI_EXACT_GRANT",
                appOpsVerdict = "URI_GRANT_BYPASS"
            )
        }
        if (request.uriGrantPresent) {
            return reject(
                message = "provider_uri_grant_claim_unverified",
                callerIdentityVerdict = "PASS",
                permissionVerdict = "URI_GRANT_NOT_FOUND",
                appOpsVerdict = "DENIED"
            )
        }
        if (!target.exported) {
            return reject(
                "provider_cross_instance_not_exported",
                callerIdentityVerdict = "PASS",
                permissionVerdict = "NOT_EXPORTED",
                appOpsVerdict = "DENIED_BEFORE_APPOPS"
            )
        }
        if (requiredPermission != null) {
            return reject(
                message = "provider_permission_service_unsupported:$requiredPermission:access=${accessType.name}",
                verdict = EngineResultStatus.UNSUPPORTED,
                callerIdentityVerdict = "PASS",
                permissionVerdict = "VIRTUAL_PERMISSION_SERVICE_UNAVAILABLE",
                appOpsVerdict = "VIRTUAL_APPOPS_SERVICE_UNAVAILABLE"
            )
        }
        return ProviderAuthorizationDecision(
            verdict = EngineResultStatus.PARTIAL,
            message = "provider_route_planned",
            accessType = accessType,
            requiredPermission = null,
            callerIdentityVerdict = "PASS",
            permissionVerdict = "EXPORTED_UNGUARDED",
            appOpsVerdict = "NOT_REQUIRED"
        )
    }

    private fun VirtualProviderDispatchPlanRequest.accessType(): VirtualProviderAccessType = when (operation) {
        EngineProviderOperation.QUERY,
        EngineProviderOperation.CANONICALIZE,
        EngineProviderOperation.UNCANONICALIZE,
        EngineProviderOperation.REFRESH,
        EngineProviderOperation.OPEN_TYPED_ASSET_FILE -> VirtualProviderAccessType.READ
        EngineProviderOperation.INSERT,
        EngineProviderOperation.DELETE,
        EngineProviderOperation.UPDATE,
        EngineProviderOperation.BULK_INSERT -> VirtualProviderAccessType.WRITE
        EngineProviderOperation.APPLY_BATCH -> if (accessMode?.contains('w', ignoreCase = true) == true) {
            VirtualProviderAccessType.WRITE
        } else {
            VirtualProviderAccessType.READ
        }
        EngineProviderOperation.OPEN_FILE,
        EngineProviderOperation.OPEN_ASSET_FILE -> if (accessMode?.contains('w', ignoreCase = true) == true) {
            VirtualProviderAccessType.WRITE
        } else {
            VirtualProviderAccessType.READ
        }
        else -> VirtualProviderAccessType.NONE
    }

    private fun ResolvedComponent.requiredPermission(
        requestedOperation: EngineProviderOperation,
        accessType: VirtualProviderAccessType,
        encodedPath: String
    ): String? {
        val decodedPath = encodedPath.decodedProviderPath()
        val pathPermission = pathPermissions.asSequence()
            .filter { it.pattern.matchesProviderPath(decodedPath) }
            .mapNotNull { pathPolicy ->
                when (accessType) {
                    VirtualProviderAccessType.READ -> pathPolicy.readPermission
                    VirtualProviderAccessType.WRITE -> pathPolicy.writePermission
                    VirtualProviderAccessType.NONE -> null
                }
            }
            .firstOrNull()
        if (pathPermission != null) return pathPermission
        return when (accessType) {
        VirtualProviderAccessType.READ -> readPermission ?: permission
        VirtualProviderAccessType.WRITE -> writePermission ?: permission
        VirtualProviderAccessType.NONE -> when (requestedOperation) {
            EngineProviderOperation.CALL,
            EngineProviderOperation.ACQUIRE_PROVIDER -> permission ?: readPermission ?: writePermission
            else -> null
        }
    }
    }

    private fun ResolvedComponent.canGrantUriPermission(encodedPath: String): Boolean =
        grantUriPermissions || uriPermissionPatterns.any {
            it.matchesProviderPath(encodedPath.decodedProviderPath())
        }

    private fun VirtualProviderPathPattern.matchesProviderPath(candidatePath: String): Boolean = when (type) {
        VirtualProviderPathPatternType.LITERAL -> candidatePath == path
        VirtualProviderPathPatternType.PREFIX -> candidatePath.startsWith(path)
        VirtualProviderPathPatternType.SUFFIX -> candidatePath.endsWith(path)
        VirtualProviderPathPatternType.SIMPLE_GLOB,
        VirtualProviderPathPatternType.ADVANCED_GLOB -> runCatching {
            PatternMatcher(path, type.toAndroidPatternType()).match(candidatePath)
        }.getOrDefault(false)
    }

    private fun VirtualProviderPathPatternType.toAndroidPatternType(): Int = when (this) {
        VirtualProviderPathPatternType.LITERAL -> PatternMatcher.PATTERN_LITERAL
        VirtualProviderPathPatternType.PREFIX -> PatternMatcher.PATTERN_PREFIX
        VirtualProviderPathPatternType.SIMPLE_GLOB -> PatternMatcher.PATTERN_SIMPLE_GLOB
        VirtualProviderPathPatternType.ADVANCED_GLOB -> PatternMatcher.PATTERN_ADVANCED_GLOB
        VirtualProviderPathPatternType.SUFFIX -> PatternMatcher.PATTERN_SUFFIX
    }

    private fun String.decodedProviderPath(): String = runCatching { Uri.decode(this) }.getOrDefault(this)

    private fun VirtualProviderDispatchPlanRequest.unsupportedOperationsForPlan(): Set<String> =
        buildSet {
            addAll(unsupportedProviderOperations)
            if (operation == EngineProviderOperation.NOTIFY_CHANGE) {
                add("notify-change")
            }
        }

    private fun EngineProviderOperation.unsupportedControlSemantic(): String? = when (this) {
        EngineProviderOperation.NOTIFY_CHANGE -> "notify-change"
        EngineProviderOperation.REGISTER_CONTENT_OBSERVER,
        EngineProviderOperation.UNREGISTER_CONTENT_OBSERVER -> "content-observer"
        EngineProviderOperation.GRANT_URI_PERMISSION,
        EngineProviderOperation.REVOKE_URI_PERMISSION -> "uri-grant"
        else -> null
    }

    private fun ResolvedComponent.toProviderTarget(
        runtime: VirtualInstanceRuntime,
        request: VirtualProviderDispatchPlanRequest
    ): VirtualProviderDispatchTarget =
        VirtualProviderDispatchTarget(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            virtualPackageName = runtime.virtualPackageName,
            guestAuthority = request.guestAuthority,
            proxyAuthority = request.proxyAuthority,
            providerClassName = name,
            operation = request.operation,
            processSlot = request.processSlot ?: runtime.processSlot,
            processName = processName ?: runtime.processName,
            exported = exported,
            permission = permission,
            readPermission = readPermission ?: permission,
            writePermission = writePermission ?: permission,
            grantUriPermissions = grantUriPermissions || uriPermissionPatterns.isNotEmpty()
        )
}

internal class RegistryBackedVirtualServiceService(
    private val runtimeService: VirtualRuntimeService,
    private val packageService: VirtualPackageService,
    private val stateStore: EngineServiceRuntimeStateStore = InMemoryEngineServiceRuntimeStateStore()
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.SERVICE,
    supportedOperations = setOf(
        "manifest-route-plan",
        "explicit-service-route",
        "implicit-service-route",
        "start-service-dispatch",
        "stop-service-route",
        "on-start-command-result",
        "process-slot-service-stub"
    ),
    unsupportedOperations = setOf("bind-service", "foreground-service-type", "sticky-restart")
), VirtualServiceService {
    private val supportedServiceOperations = setOf(
        "manifest-route-plan",
        "explicit-service-route",
        "implicit-service-route",
        "start-service-dispatch",
        "stop-service-route",
        "on-start-command-result",
        "process-slot-service-stub"
    )
    private val unsupportedServiceOperations = setOf(
        "bind-service",
        "foreground-service-type",
        "sticky-restart"
    )

    override fun planService(
        instanceId: String,
        request: VirtualServiceDispatchPlanRequest
    ): VirtualServiceDispatchPlan {
        if (instanceId.isBlank()) {
            return VirtualServiceDispatchPlan(
                instanceId = "invalid",
                operation = request.operation,
                verdict = EngineResultStatus.FAIL,
                action = request.action,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualServiceDispatchPlan(
                instanceId = instanceId,
                operation = request.operation,
                verdict = EngineResultStatus.FAIL,
                action = request.action,
                message = "runtime_not_found:$instanceId"
            )
        val unsupportedSemantics = request.unsupportedSemantics()
        if (unsupportedSemantics.isNotEmpty()) {
            val plan = VirtualServiceDispatchPlan(
                instanceId = runtime.instanceId,
                operation = request.operation,
                verdict = EngineResultStatus.UNSUPPORTED,
                action = request.action,
                supportedOperations = supportedServiceOperations,
                unsupportedOperations = unsupportedSemantics,
                message = "service_semantics_unsupported:${unsupportedSemantics.sorted().joinToString(",")}"
            )
            recordPlanEvidence(plan, runtime, request)
            return plan
        }

        val targets = if (request.serviceClassName != null) {
            explicitTargets(runtime, request)
        } else {
            implicitTargets(runtime, request)
        }
        val plan = VirtualServiceDispatchPlan(
            instanceId = runtime.instanceId,
            operation = request.operation,
            verdict = if (targets.isEmpty()) EngineResultStatus.FAIL else EngineResultStatus.PARTIAL,
            action = request.action,
            targets = targets,
            supportedOperations = supportedServiceOperations,
            unsupportedOperations = request.partialUnsupportedOperations(),
            message = when {
                targets.isEmpty() && request.serviceClassName != null -> "explicit_service_not_found"
                targets.isEmpty() -> "no_manifest_service_match"
                request.serviceClassName != null && request.operation == VirtualServiceOperation.STOP -> {
                    "explicit_service_stop_route_planned"
                }
                request.serviceClassName != null -> "explicit_service_route_planned"
                else -> "implicit_service_route_planned"
            }
        )
        recordPlanEvidence(plan, runtime, request)
        return plan
    }

    override fun recordServiceDispatch(
        instanceId: String,
        result: VirtualServiceOperationResult
    ): Boolean {
        if (instanceId.isBlank() || instanceId != result.instanceId) return false
        val runtime = runtimeService.get(instanceId) ?: return false
        updateRuntimeState(runtime, result)
        return runtimeService.registerOperationEvidence(
            instanceId = instanceId,
            evidence = EngineOperationEvidence(
                component = "service",
                operation = "dispatch",
                verdict = result.verdict,
                entries = linkedMapOf(
                    "instanceId" to result.instanceId,
                    "operation" to result.operation.name,
                    "serviceClassName" to result.serviceClassName.orEmpty(),
                    "action" to result.action.orEmpty(),
                    "reason" to result.reason,
                    "started" to result.started.toString(),
                    "stopped" to result.stopped.toString(),
                    "bound" to result.bound.toString(),
                    "unbound" to result.unbound.toString(),
                    "foreground" to result.foreground.toString(),
                    "startCommandResult" to (result.startCommandResult?.toString() ?: ""),
                    "processSlot" to (result.processSlot ?: runtime.processSlot),
                    "activeStartCount" to result.activeStartCount.toString(),
                    "activeBindCount" to result.activeBindCount.toString(),
                    "cached" to result.cached.toString(),
                    "message" to result.message
                )
            )
        )
    }

    override fun queryServiceRuntimeState(instanceId: String): VirtualServiceRuntimeState {
        if (instanceId.isBlank()) {
            return VirtualServiceRuntimeState(
                instanceId = "invalid",
                verdict = EngineResultStatus.FAIL,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualServiceRuntimeState(
                instanceId = instanceId,
                verdict = EngineResultStatus.FAIL,
                message = "runtime_not_found:$instanceId"
            )
        val records = stateStore.list(runtime.instanceId)
            .filter { it.runtimeEpoch == runtime.runtimeEpoch }
        return VirtualServiceRuntimeState(
            instanceId = runtime.instanceId,
            verdict = EngineResultStatus.PARTIAL,
            records = records,
            message = if (records.isEmpty()) {
                "runtime_bound_but_no_service_state"
            } else {
                "runtime_bound_with_service_state"
            }
        )
    }

    private fun updateRuntimeState(
        runtime: VirtualInstanceRuntime,
        result: VirtualServiceOperationResult
    ) {
        val serviceClassName = result.serviceClassName ?: return
        if (result.verdict != EngineResultStatus.PASS && result.verdict != EngineResultStatus.PARTIAL) return
        val existing = stateStore.list(runtime.instanceId)
            .firstOrNull { it.serviceClassName == serviceClassName }
        val record = when (result.operation) {
            VirtualServiceOperation.START,
            VirtualServiceOperation.START_FOREGROUND -> {
                if (!result.started) return
                EngineServiceRuntimeRecord(
                    instanceId = runtime.instanceId,
                    serviceClassName = serviceClassName,
                    processSlot = result.processSlot ?: runtime.processSlot,
                    runtimeEpoch = runtime.runtimeEpoch,
                    state = if (result.foreground) {
                        EngineServiceLifecycleState.FOREGROUND
                    } else {
                        EngineServiceLifecycleState.STARTED
                    },
                    activeStartCount = result.activeStartCount.takeIf { it > 0 }
                        ?: ((existing?.activeStartCount ?: 0) + if (result.cached) 0 else 1),
                    activeBindCount = result.activeBindCount,
                    cached = result.cached,
                    startCommandResult = result.startCommandResult
                )
            }
            VirtualServiceOperation.STOP -> {
                if (!result.stopped) return
                EngineServiceRuntimeRecord(
                    instanceId = runtime.instanceId,
                    serviceClassName = serviceClassName,
                    processSlot = result.processSlot ?: existing?.processSlot ?: runtime.processSlot,
                    runtimeEpoch = runtime.runtimeEpoch,
                    state = EngineServiceLifecycleState.STOPPED,
                    activeStartCount = 0,
                    activeBindCount = existing?.activeBindCount ?: 0,
                    cached = existing?.cached ?: false,
                    startCommandResult = existing?.startCommandResult
                )
            }
            VirtualServiceOperation.BIND,
            VirtualServiceOperation.UNBIND -> return
        }
        stateStore.upsert(record)
    }

    private fun explicitTargets(
        runtime: VirtualInstanceRuntime,
        request: VirtualServiceDispatchPlanRequest
    ): List<VirtualServiceDispatchTarget> {
        if (!runtime.matchesServiceTargetPackage(request.targetPackageName)) return emptyList()
        val serviceClassName = normalizeComponentClassName(
            runtime.originPackageName,
            request.serviceClassName ?: return emptyList()
        )
        val service = packageService.queryComponent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.SERVICE,
            className = serviceClassName
        ) ?: return emptyList()
        return listOf(service.toServiceTarget(runtime, request, reason = request.explicitReason()))
    }

    private fun implicitTargets(
        runtime: VirtualInstanceRuntime,
        request: VirtualServiceDispatchPlanRequest
    ): List<VirtualServiceDispatchTarget> {
        if (request.action.isNullOrBlank()) return emptyList()
        if (!runtime.matchesServiceTargetPackage(request.targetPackageName)) return emptyList()
        return packageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.SERVICE,
            action = request.action,
            categories = request.categories,
            dataScheme = request.dataScheme,
            dataMimeType = request.dataMimeType,
            dataAuthority = request.dataAuthority,
            dataPath = request.dataPath
        ).take(1).map { service ->
            service.toServiceTarget(runtime, request, reason = request.implicitReason())
        }
    }

    private fun recordPlanEvidence(
        plan: VirtualServiceDispatchPlan,
        runtime: VirtualInstanceRuntime,
        request: VirtualServiceDispatchPlanRequest
    ) {
        runtimeService.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = EngineOperationEvidence(
                component = "service",
                operation = "plan",
                verdict = plan.verdict,
                entries = linkedMapOf(
                    "servicePlanVerdict" to plan.verdict.name,
                    "servicePlanMessage" to plan.message,
                    "operation" to request.operation.name,
                    "action" to request.action.orEmpty(),
                    "serviceClassName" to request.serviceClassName.orEmpty(),
                    "targetPackageName" to request.targetPackageName.orEmpty(),
                    "targetCount" to plan.targets.size.toString(),
                    "targetServices" to plan.targets.joinToString(",") { it.serviceClassName },
                    "processSlot" to runtime.processSlot,
                    "foreground" to (request.operation == VirtualServiceOperation.START_FOREGROUND).toString(),
                    "requestedForegroundServiceTypes" to request.requestedForegroundServiceTypes.sorted().joinToString(","),
                    "supportedOperations" to plan.supportedOperations.sorted().joinToString(","),
                    "unsupportedOperations" to plan.unsupportedOperations.sorted().joinToString(",")
                )
            )
        )
    }

    private fun VirtualServiceDispatchPlanRequest.unsupportedSemantics(): Set<String> =
        buildSet {
            if (operation == VirtualServiceOperation.BIND || operation == VirtualServiceOperation.UNBIND) {
                add("bind-service")
            }
            if (requestedForegroundServiceTypes.isNotEmpty()) {
                add("foreground-service-type")
            }
            if (stickyRestartRequested) {
                add("sticky-restart")
            }
        }

    private fun VirtualServiceDispatchPlanRequest.partialUnsupportedOperations(): Set<String> =
        if (operation == VirtualServiceOperation.START_FOREGROUND) {
            unsupportedServiceOperations
        } else {
            unsupportedServiceOperations - "foreground-service-type"
        }

    private fun VirtualInstanceRuntime.matchesServiceTargetPackage(targetPackageName: String?): Boolean {
        val target = targetPackageName ?: return true
        return target == originPackageName || target == virtualPackageName || target == hostPackageName
    }

    private fun ResolvedComponent.toServiceTarget(
        runtime: VirtualInstanceRuntime,
        request: VirtualServiceDispatchPlanRequest,
        reason: String
    ): VirtualServiceDispatchTarget =
        VirtualServiceDispatchTarget(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            virtualPackageName = runtime.virtualPackageName,
            serviceClassName = name,
            action = request.action,
            reason = reason,
            operation = request.operation,
            processSlot = runtime.processSlot,
            processName = processName ?: runtime.processName,
            foreground = request.operation == VirtualServiceOperation.START_FOREGROUND,
            priority = resolvedIntentFilters.maxOfOrNull { it.priority } ?: 0
        )

    private fun VirtualServiceDispatchPlanRequest.explicitReason(): String =
        when (operation) {
            VirtualServiceOperation.START -> "explicit"
            VirtualServiceOperation.START_FOREGROUND -> "explicitForeground"
            VirtualServiceOperation.STOP -> "explicitStop"
            VirtualServiceOperation.BIND -> "explicitBind"
            VirtualServiceOperation.UNBIND -> "explicitUnbind"
        }

    private fun VirtualServiceDispatchPlanRequest.implicitReason(): String =
        when (operation) {
            VirtualServiceOperation.START -> "implicit"
            VirtualServiceOperation.START_FOREGROUND -> "implicitForeground"
            VirtualServiceOperation.STOP -> "implicitStop"
            VirtualServiceOperation.BIND -> "implicitBind"
            VirtualServiceOperation.UNBIND -> "implicitUnbind"
        }
}

internal class RegistryBackedVirtualBroadcastService(
    private val runtimeService: VirtualRuntimeService,
    private val packageService: VirtualPackageService,
    private val stateStore: EngineBroadcastRuntimeStateStore
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.BROADCAST,
    supportedOperations = setOf("runtime-record-evidence", "manifest-route-plan", "explicit-receiver-route", "implicit-receiver-route"),
    unsupportedOperations = setOf(
        "ordered",
        "sticky",
        "result-receiver",
        "abort",
        "receiver-permission",
        "receiver-app-op",
        "as-user",
        "broadcast-options",
        "cross-process-route"
    )
), VirtualBroadcastService {
    private val supportedBroadcastOperations = setOf(
        "runtime-record-evidence",
        "manifest-route-plan",
        "explicit-receiver-route",
        "implicit-receiver-route"
    )
    private val unsupportedBroadcastOperations = setOf(
        "ordered",
        "sticky",
        "result-receiver",
        "abort",
        "receiver-permission",
        "receiver-app-op",
        "as-user",
        "broadcast-options",
        "cross-process-route"
    )

    override fun planBroadcast(
        instanceId: String,
        request: VirtualBroadcastDispatchPlanRequest
    ): VirtualBroadcastDispatchPlan {
        if (instanceId.isBlank()) {
            return VirtualBroadcastDispatchPlan(
                instanceId = "invalid",
                verdict = EngineResultStatus.FAIL,
                action = request.action,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualBroadcastDispatchPlan(
                instanceId = instanceId,
                verdict = EngineResultStatus.FAIL,
                action = request.action,
                message = "runtime_not_found:$instanceId"
            )
        val unsupportedSemantics = request.unsupportedSemantics()
        if (unsupportedSemantics.isNotEmpty()) {
            val plan = VirtualBroadcastDispatchPlan(
                instanceId = runtime.instanceId,
                verdict = EngineResultStatus.UNSUPPORTED,
                action = request.action,
                supportedOperations = supportedBroadcastOperations,
                unsupportedOperations = unsupportedSemantics,
                message = "broadcast_semantics_unsupported:${unsupportedSemantics.sorted().joinToString(",")}"
            )
            recordPlanEvidence(plan, runtime, request)
            return plan
        }

        val targets = if (request.receiverClassName != null) {
            explicitTargets(runtime, request)
        } else {
            implicitTargets(runtime, request)
        }
        val verdict = if (request.receiverClassName != null && targets.isEmpty()) {
            EngineResultStatus.FAIL
        } else {
            EngineResultStatus.PARTIAL
        }
        val message = when {
            request.receiverClassName != null && targets.isEmpty() -> "explicit_receiver_not_found"
            targets.isEmpty() -> "no_manifest_receiver_match"
            request.receiverClassName != null -> "explicit_broadcast_route_planned"
            else -> "implicit_broadcast_route_planned"
        }
        val plan = VirtualBroadcastDispatchPlan(
            instanceId = runtime.instanceId,
            verdict = verdict,
            action = request.action,
            targets = targets,
            supportedOperations = supportedBroadcastOperations,
            unsupportedOperations = unsupportedBroadcastOperations,
            message = message
        )
        recordPlanEvidence(plan, runtime, request)
        return plan
    }

    override fun recordBroadcastDispatch(
        instanceId: String,
        result: VirtualBroadcastOperationResult
    ): Boolean {
        if (instanceId.isBlank() || instanceId != result.instanceId) return false
        val runtime = runtimeService.get(instanceId) ?: return false
        updateRuntimeState(runtime, result)
        return runtimeService.registerOperationEvidence(
            instanceId = instanceId,
            evidence = EngineOperationEvidence(
                component = "broadcast",
                operation = "dispatch",
                verdict = result.verdict,
                entries = linkedMapOf(
                    "instanceId" to result.instanceId,
                    "receiverClassName" to result.receiverClassName.orEmpty(),
                    "action" to result.action.orEmpty(),
                    "reason" to result.reason,
                    "delivered" to result.delivered.toString(),
                    "processSlot" to runtime.processSlot,
                    "runtimeEpoch" to runtime.runtimeEpoch.toString(),
                    "message" to result.message
                )
            )
        )
    }

    override fun queryBroadcastRuntimeState(instanceId: String): VirtualBroadcastRuntimeState {
        if (instanceId.isBlank()) {
            return VirtualBroadcastRuntimeState(
                instanceId = "invalid",
                verdict = EngineResultStatus.FAIL,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualBroadcastRuntimeState(
                instanceId = instanceId,
                verdict = EngineResultStatus.FAIL,
                message = "runtime_not_found:$instanceId"
            )
        val records = stateStore.list(runtime.instanceId)
            .filter { it.runtimeEpoch == runtime.runtimeEpoch }
        return VirtualBroadcastRuntimeState(
            instanceId = runtime.instanceId,
            verdict = EngineResultStatus.PARTIAL,
            records = records,
            message = if (records.isEmpty()) {
                "runtime_bound_but_no_broadcast_state"
            } else {
                "runtime_bound_with_broadcast_state"
            }
        )
    }

    private fun updateRuntimeState(
        runtime: VirtualInstanceRuntime,
        result: VirtualBroadcastOperationResult
    ) {
        if (result.receiverClassName == null && result.action == null) return
        val state = when {
            result.delivered -> EngineBroadcastDeliveryState.DELIVERED
            result.verdict == EngineResultStatus.UNSUPPORTED -> EngineBroadcastDeliveryState.BLOCKED
            else -> EngineBroadcastDeliveryState.FAILED
        }
        stateStore.update(
            instanceId = runtime.instanceId,
            receiverClassName = result.receiverClassName,
            action = result.action
        ) { stored ->
            val existing = stored?.takeIf { it.runtimeEpoch == runtime.runtimeEpoch }
            EngineBroadcastRuntimeRecord(
                instanceId = runtime.instanceId,
                receiverClassName = result.receiverClassName,
                action = result.action,
                processSlot = runtime.processSlot,
                runtimeEpoch = runtime.runtimeEpoch,
                state = state,
                lastVerdict = result.verdict,
                lastReason = result.reason,
                dispatchCount = (existing?.dispatchCount ?: 0L) + 1L,
                deliveredCount = (existing?.deliveredCount ?: 0L) + if (state == EngineBroadcastDeliveryState.DELIVERED) 1L else 0L,
                blockedCount = (existing?.blockedCount ?: 0L) + if (state == EngineBroadcastDeliveryState.BLOCKED) 1L else 0L,
                failureCount = (existing?.failureCount ?: 0L) + if (state == EngineBroadcastDeliveryState.FAILED) 1L else 0L
            )
        }
    }

    private fun explicitTargets(
        runtime: VirtualInstanceRuntime,
        request: VirtualBroadcastDispatchPlanRequest
    ): List<VirtualBroadcastDispatchTarget> {
        if (!runtime.matchesTargetPackage(request.targetPackageName)) return emptyList()
        val receiverClassName = normalizeComponentClassName(
            runtime.originPackageName,
            request.receiverClassName ?: return emptyList()
        )
        val receiver = packageService.queryComponent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.RECEIVER,
            className = receiverClassName
        ) ?: return emptyList()
        return listOf(receiver.toBroadcastTarget(runtime, request.action, reason = "explicit"))
    }

    private fun implicitTargets(
        runtime: VirtualInstanceRuntime,
        request: VirtualBroadcastDispatchPlanRequest
    ): List<VirtualBroadcastDispatchTarget> {
        if (request.action.isNullOrBlank()) return emptyList()
        if (!runtime.matchesTargetPackage(request.targetPackageName)) return emptyList()
        return packageService.resolveIntent(
            instanceId = runtime.instanceId,
            type = VirtualPackageComponentType.RECEIVER,
            action = request.action,
            categories = request.categories,
            dataScheme = request.dataScheme,
            dataMimeType = request.dataMimeType,
            dataAuthority = request.dataAuthority,
            dataPath = request.dataPath
        ).map { receiver ->
            receiver.toBroadcastTarget(runtime, request.action, reason = "implicit")
        }
    }

    private fun recordPlanEvidence(
        plan: VirtualBroadcastDispatchPlan,
        runtime: VirtualInstanceRuntime,
        request: VirtualBroadcastDispatchPlanRequest
    ) {
        runtimeService.registerOperationEvidence(
            instanceId = runtime.instanceId,
            evidence = EngineOperationEvidence(
                component = "broadcast",
                operation = "plan",
                verdict = plan.verdict,
                entries = linkedMapOf(
                    "broadcastPlanVerdict" to plan.verdict.name,
                    "broadcastPlanMessage" to plan.message,
                    "action" to request.action.orEmpty(),
                    "receiverClassName" to request.receiverClassName.orEmpty(),
                    "targetPackageName" to request.targetPackageName.orEmpty(),
                    "targetCount" to plan.targets.size.toString(),
                    "targetReceivers" to plan.targets.joinToString(",") { it.receiverClassName },
                    "processSlot" to runtime.processSlot,
                    "runtimeEpoch" to runtime.runtimeEpoch.toString(),
                    "ordered" to request.ordered.toString(),
                    "sticky" to request.sticky.toString(),
                    "expectsResultReceiver" to request.expectsResultReceiver.toString(),
                    "receiverPermissions" to request.receiverPermissions.sorted().joinToString(","),
                    "receiverAppOp" to request.receiverAppOp.orEmpty(),
                    "asUserRequested" to request.asUserRequested.toString(),
                    "platformOptionsPresent" to request.platformOptionsPresent.toString(),
                    "supportedOperations" to plan.supportedOperations.sorted().joinToString(","),
                    "unsupportedOperations" to plan.unsupportedOperations.sorted().joinToString(",")
                )
            )
        )
    }

    private fun VirtualBroadcastDispatchPlanRequest.unsupportedSemantics(): Set<String> =
        buildSet {
            if (ordered) add("ordered")
            if (sticky) add("sticky")
            if (expectsResultReceiver) add("result-receiver")
            if (abortSupportedRequested) add("abort")
            if (receiverPermissions.isNotEmpty()) add("receiver-permission")
            if (receiverAppOp != null) add("receiver-app-op")
            if (asUserRequested) add("as-user")
            if (platformOptionsPresent) add("broadcast-options")
        }

    private fun VirtualInstanceRuntime.matchesTargetPackage(targetPackageName: String?): Boolean {
        val target = targetPackageName ?: return true
        return target == originPackageName || target == virtualPackageName || target == hostPackageName
    }

    private fun ResolvedComponent.toBroadcastTarget(
        runtime: VirtualInstanceRuntime,
        action: String?,
        reason: String
    ): VirtualBroadcastDispatchTarget =
        VirtualBroadcastDispatchTarget(
            instanceId = runtime.instanceId,
            originPackageName = runtime.originPackageName,
            virtualPackageName = runtime.virtualPackageName,
            receiverClassName = name,
            action = action,
            reason = reason,
            processSlot = runtime.processSlot,
            processName = processName ?: runtime.processName,
            priority = resolvedIntentFilters.maxOfOrNull { it.priority } ?: 0
        )
}

internal class RegistryBackedVirtualStorageService(
    runtimeService: VirtualRuntimeService
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.STORAGE,
    supportedOperations = setOf("java-private-path", "process-slot-native-binding", "canonical-containment"),
    unsupportedOperations = setOf("external-storage-policy", "media-provider-isolation")
), VirtualStorageService

internal class RegistryBackedVirtualNativeService(
    runtimeService: VirtualRuntimeService
) : RegistryBackedRuntimeBoundSubsystemService(
    runtimeService = runtimeService,
    subsystem = EngineSubsystem.NATIVE,
    supportedOperations = setOf("private-path-redirect", "path-containment", "process-slot-binding"),
    unsupportedOperations = setOf("linker-namespace", "runtime-native-load", "register-natives-verdict")
), VirtualNativeService

internal class RegistryBackedVirtualEvidenceService(
    private val runtimeService: VirtualRuntimeService,
    private val activityService: VirtualActivityService? = null,
    private val serviceService: VirtualServiceService? = null,
    private val providerService: VirtualProviderService? = null,
    private val broadcastService: VirtualBroadcastService? = null,
    private val appOpsService: VirtualAppOpsService? = null
) : VirtualEvidenceService {
    override val subsystem: EngineSubsystem = EngineSubsystem.EVIDENCE

    override fun exportReport(instanceId: String): EngineEvidenceReport? {
        if (runtimeService.get(instanceId) == null) return null
        var report = runtimeService.evidence(instanceId)
        activityService?.queryTaskState(instanceId)?.let { taskState ->
            report = report.withOperationEvidence(
                EngineOperationEvidence(
                    component = "activity",
                    operation = "task-state",
                    verdict = EngineResultStatus.PASS,
                    entries = linkedMapOf(
                        "taskStateVerdict" to taskState.verdict.name,
                        "taskStateMessage" to taskState.message,
                        "taskCount" to taskState.taskCount.toString(),
                        "activityCount" to taskState.activityCount.toString(),
                        "topTaskId" to (taskState.topTaskId?.toString() ?: ""),
                        "topActivityClassName" to taskState.topActivityClassName.orEmpty(),
                        "topActivityState" to (taskState.topActivityState?.name ?: ""),
                        "supportedOperations" to taskState.supportedOperations.sorted().joinToString(","),
                        "unsupportedOperations" to taskState.unsupportedOperations.sorted().joinToString(",")
                    )
                )
            )
        }
        serviceService?.queryServiceRuntimeState(instanceId)?.let { serviceState ->
            report = report.withOperationEvidence(
                EngineOperationEvidence(
                    component = "service",
                    operation = "runtime-state",
                    verdict = serviceState.verdict,
                    entries = linkedMapOf(
                        "serviceStateVerdict" to serviceState.verdict.name,
                        "serviceStateMessage" to serviceState.message,
                        "serviceRecordCount" to serviceState.records.size.toString(),
                        "serviceRecords" to serviceState.records.joinToString(",") { record ->
                            "${record.serviceClassName}:${record.state.name}:${record.activeStartCount}:${record.activeBindCount}"
                        }
                    )
                )
            )
        }
        providerService?.queryProviderRuntimeState(instanceId)?.let { providerState ->
            report = report.withOperationEvidence(
                EngineOperationEvidence(
                    component = "provider",
                    operation = "runtime-state",
                    verdict = providerState.verdict,
                    entries = linkedMapOf(
                        "providerStateVerdict" to providerState.verdict.name,
                        "providerStateMessage" to providerState.message,
                        "providerRecordCount" to providerState.records.size.toString(),
                        "providerRecords" to providerState.records.joinToString(",") { record ->
                            "${record.guestAuthority}:${record.state.name}:${record.lastOperation.name}:${record.operationCount}"
                        }
                    )
                )
            )
        }
        broadcastService?.queryBroadcastRuntimeState(instanceId)?.let { broadcastState ->
            report = report.withOperationEvidence(
                EngineOperationEvidence(
                    component = "broadcast",
                    operation = "runtime-state",
                    verdict = broadcastState.verdict,
                    entries = linkedMapOf(
                        "broadcastStateVerdict" to broadcastState.verdict.name,
                        "broadcastStateMessage" to broadcastState.message,
                        "broadcastRecordCount" to broadcastState.records.size.toString(),
                        "broadcastRecords" to broadcastState.records.joinToString(",") { record ->
                            "${record.receiverClassName.orEmpty()}:${record.action.orEmpty()}:${record.state.name}:" +
                                "${record.dispatchCount}:${record.deliveredCount}:${record.blockedCount}:${record.failureCount}"
                        }
                    )
                )
            )
        }
        appOpsService?.queryRuntimeState(instanceId)?.let { appOpsState ->
            report = report.withOperationEvidence(
                EngineOperationEvidence(
                    component = "app-ops",
                    operation = "runtime-state",
                    verdict = appOpsState.verdict,
                    entries = linkedMapOf(
                        "appOpsStateVerdict" to appOpsState.verdict.name,
                        "appOpsStateMessage" to appOpsState.message,
                        "appOpsModeCount" to appOpsState.records.size.toString(),
                        "appOpsModes" to appOpsState.records.joinToString(",") { record ->
                            "${record.opCode}:${record.mode}"
                        }
                    )
                )
            )
        }
        return report
    }
}

internal abstract class RegistryBackedRuntimeBoundSubsystemService(
    private val runtimeService: VirtualRuntimeService,
    override val subsystem: EngineSubsystem,
    private val supportedOperations: Set<String>,
    private val unsupportedOperations: Set<String>
) : VirtualRuntimeBoundSubsystemService {
    override fun queryRuntimeBinding(instanceId: String): VirtualSubsystemRuntimeBinding {
        if (instanceId.isBlank()) {
            return VirtualSubsystemRuntimeBinding(
                instanceId = "invalid",
                subsystem = subsystem,
                verdict = EngineResultStatus.FAIL,
                message = "instanceId must not be blank"
            )
        }
        val runtime = runtimeService.get(instanceId)
            ?: return VirtualSubsystemRuntimeBinding(
                instanceId = instanceId,
                subsystem = subsystem,
                verdict = EngineResultStatus.FAIL,
                message = "runtime_not_found:$instanceId"
            )
        return VirtualSubsystemRuntimeBinding(
            instanceId = runtime.instanceId,
            subsystem = subsystem,
            verdict = EngineResultStatus.PARTIAL,
            hostPackageName = runtime.hostPackageName,
            originPackageName = runtime.originPackageName,
            virtualPackageName = runtime.virtualPackageName,
            processSlot = runtime.processSlot,
            proxySlot = runtime.proxySlot,
            runtimeEpoch = runtime.runtimeEpoch,
            processId = runtime.processId,
            processName = runtime.processName,
            state = runtime.state,
            supportedOperations = supportedOperations,
            unsupportedOperations = unsupportedOperations,
            message = "runtime_bound_but_${subsystem.name.lowercase()}_semantics_partial"
        )
    }
}

private fun VirtualPackageSnapshot.components(type: VirtualPackageComponentType): List<ResolvedComponent> =
    when (type) {
        VirtualPackageComponentType.ACTIVITY -> activities
        VirtualPackageComponentType.SERVICE -> services
        VirtualPackageComponentType.RECEIVER -> receivers
        VirtualPackageComponentType.PROVIDER -> providers
    }

private fun ResolvedIntentFilter.matches(
    action: String,
    categories: Set<String>,
    dataScheme: String?,
    dataMimeType: String?,
    dataAuthority: String?,
    dataPath: String?
): Boolean {
    val actionMatches = actions.isEmpty() || action in actions
    if (!actionMatches) return false

    val categoriesMatch = categories.all { category -> category in this.categories }
    if (!categoriesMatch) return false

    val schemeMatches = dataScheme == null || dataSchemes.isEmpty() || dataScheme in dataSchemes
    if (!schemeMatches) return false

    val mimeMatches = dataMimeType == null ||
        dataMimeTypes.isEmpty() ||
        dataMimeType in dataMimeTypes ||
        dataMimeTypes.any { filterType -> filterType.endsWith("/*") && dataMimeType.startsWith(filterType.removeSuffix("*")) }
    if (!mimeMatches) return false

    val authorityMatches = dataAuthority == null || dataAuthorities.isEmpty() || dataAuthority in dataAuthorities
    if (!authorityMatches) return false

    return dataPath == null || dataPaths.isEmpty() || dataPath in dataPaths
}

private fun normalizeComponentClassName(packageName: String, className: String): String =
    when {
        className.startsWith(".") -> packageName + className
        '.' !in className -> "$packageName.$className"
        else -> className
    }
