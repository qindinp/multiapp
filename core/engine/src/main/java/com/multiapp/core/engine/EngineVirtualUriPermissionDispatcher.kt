package com.multiapp.core.engine

import android.content.Context
import android.os.Process
import com.multiapp.core.loader.VirtualUriPermissionDispatcher
import com.multiapp.core.loader.VirtualUriPermissionDispatcherFactory
import com.multiapp.core.loader.VirtualUriPermissionDispatcherFactoryRequest
import com.multiapp.core.loader.VirtualUriPermissionOperation
import com.multiapp.core.loader.VirtualUriPermissionRequest
import com.multiapp.core.loader.VirtualUriPermissionResult
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.VirtualContextConfig

internal data class EngineUriPermissionBackend(
    val providerService: VirtualProviderService,
    val uriPermissionCheckTarget: (String, Int) -> EngineRuntimeIpcSnapshot?,
    val uriPermissionChecker: (
        String,
        String,
        VirtualProviderUriGrantRequest
    ) -> VirtualProviderUriGrantResult?
)

class EngineVirtualUriPermissionDispatcherFactory internal constructor(
    private val backendFactory: (Context) -> EngineUriPermissionBackend = { context ->
        @Suppress("UNUSED_VARIABLE")
        val ignored = context
        EngineUriPermissionBackend(
            providerService = IpcBackedVirtualProviderService(),
            uriPermissionCheckTarget = EngineRuntimeIpcClients::resolveUriPermissionCheckTarget,
            uriPermissionChecker = EngineRuntimeIpcClients::checkProviderUriPermissionForCaller
        )
    },
    private val uidProvider: (Context) -> Int = { it.applicationInfo.uid },
    private val pidProvider: () -> Int = Process::myPid
) : VirtualUriPermissionDispatcherFactory {
    override fun create(
        request: VirtualUriPermissionDispatcherFactoryRequest
    ): VirtualUriPermissionDispatcher? {
        if (request.config.packageSnapshot == null) return null
        val backend = backendFactory(request.hostContext)
        return EngineVirtualUriPermissionDispatcher(
            config = request.config,
            providerService = backend.providerService,
            uriPermissionCheckTarget = backend.uriPermissionCheckTarget,
            uriPermissionChecker = backend.uriPermissionChecker,
            hostUid = uidProvider(request.hostContext),
            processId = pidProvider()
        )
    }
}

internal class EngineVirtualUriPermissionDispatcher(
    private val config: VirtualContextConfig,
    private val providerService: VirtualProviderService,
    private val uriPermissionCheckTarget: (String, Int) -> EngineRuntimeIpcSnapshot?,
    private val uriPermissionChecker: (
        String,
        String,
        VirtualProviderUriGrantRequest
    ) -> VirtualProviderUriGrantResult?,
    private val hostUid: Int,
    private val processId: Int
) : VirtualUriPermissionDispatcher {
    override fun dispatch(request: VirtualUriPermissionRequest): VirtualUriPermissionResult {
        val authority = request.uri.authority?.takeIf { it.isNotBlank() }
            ?: return VirtualUriPermissionResult.notHandled("uri_authority_missing")
        val guestOwned = authority in guestAuthorities()
        val authorityResolution = if (guestOwned) null else providerService.resolveProviderAuthority(
            config.instanceId,
            VirtualProviderAuthorityResolveRequest(
                guestAuthority = authority,
                operation = request.operation.toProviderOperation(),
                encodedPath = normalizeProviderGrantPath(request.uri.encodedPath),
                accessMode = request.modeFlags.toProviderAccessMode()
            )
        )
        if (!guestOwned && authorityResolution?.virtualAuthority != true) {
            return VirtualUriPermissionResult.notHandled("uri_authority_not_virtual")
        }
        if (!guestOwned && authorityResolution?.verdict == EngineResultStatus.FAIL) {
            return VirtualUriPermissionResult(
                handled = true,
                success = false,
                granted = false,
                reason = authorityResolution.message
            )
        }
        if (
            request.operation == VirtualUriPermissionOperation.GRANT ||
            request.operation == VirtualUriPermissionOperation.REVOKE
        ) {
            if (!guestOwned) {
                return VirtualUriPermissionResult.notHandled("uri_authority_not_owned_by_guest")
            }
        }
        val engineResult = when (request.operation) {
            VirtualUriPermissionOperation.GRANT -> providerService.grantUriPermission(
                ownerInstanceId = config.instanceId,
                request = request.toEngineRequest(
                    authority = authority,
                    callingUid = hostUid,
                    callingPid = processId
                )
            )
            VirtualUriPermissionOperation.REVOKE -> providerService.revokeUriPermission(
                ownerInstanceId = config.instanceId,
                request = request.toEngineRequest(
                    authority = authority,
                    callingUid = hostUid,
                    callingPid = processId
                )
            )
            VirtualUriPermissionOperation.CHECK -> checkUriPermission(
                request,
                authority,
                config.instanceId.takeIf { guestOwned }
            )
                ?: return VirtualUriPermissionResult(
                    handled = true,
                    success = false,
                    granted = false,
                    reason = "provider_uri_grant_target_process_unresolved:pid=${request.pid}:uid=${request.uid}"
                )
            VirtualUriPermissionOperation.TAKE_PERSISTABLE ->
                providerService.takePersistableUriPermission(
                    targetInstanceId = config.instanceId,
                    request = request.toEngineRequest(
                        authority = authority,
                        ownerInstanceId = null,
                        targetInstanceId = config.instanceId,
                        callingUid = hostUid,
                        callingPid = processId
                    )
                )
            VirtualUriPermissionOperation.RELEASE_PERSISTABLE ->
                providerService.releasePersistableUriPermission(
                    targetInstanceId = config.instanceId,
                    request = request.toEngineRequest(
                        authority = authority,
                        ownerInstanceId = null,
                        targetInstanceId = config.instanceId,
                        callingUid = hostUid,
                        callingPid = processId
                    )
                )
        }
        val successful = engineResult.verdict == EngineResultStatus.PASS ||
            engineResult.verdict == EngineResultStatus.PARTIAL
        return VirtualUriPermissionResult(
            handled = true,
            success = successful,
            granted = engineResult.granted,
            reason = engineResult.message
        )
    }

    private fun checkUriPermission(
        request: VirtualUriPermissionRequest,
        authority: String,
        ownerInstanceId: String?
    ): VirtualProviderUriGrantResult? {
        if (request.uid != hostUid || request.pid <= 0) return null
        val targetRuntime = uriPermissionCheckTarget(config.instanceId, request.pid)
            ?.takeIf { runtime ->
                runtime.found && runtime.liveAuthority && runtime.processId == request.pid &&
                    !runtime.instanceId.isBlank()
            }
            ?: return null
        val targetInstanceId = targetRuntime.instanceId
        return uriPermissionChecker(
            config.instanceId,
            targetInstanceId,
            request.toEngineRequest(
                authority = authority,
                ownerInstanceId = ownerInstanceId,
                targetInstanceId = targetInstanceId,
                callingUid = request.uid,
                callingPid = request.pid
            )
        )
    }

    private fun VirtualUriPermissionRequest.toEngineRequest(
        authority: String,
        ownerInstanceId: String? = config.instanceId,
        targetInstanceId: String? = null,
        callingUid: Int,
        callingPid: Int
    ): VirtualProviderUriGrantRequest = VirtualProviderUriGrantRequest(
        guestAuthority = authority,
        encodedPath = normalizeProviderGrantPath(uri.encodedPath),
        modeFlags = modeFlags,
        ownerInstanceId = ownerInstanceId,
        targetInstanceId = targetInstanceId,
        targetPackageName = targetPackageName,
        callingUid = callingUid,
        callingPid = callingPid,
        hostUid = hostUid
    )

    private fun guestAuthorities(): Set<String> = config.packageSnapshot
        ?.providers
        .orEmpty()
        .flatMapTo(linkedSetOf()) { it.authorities }
}

private fun VirtualUriPermissionOperation.toProviderOperation(): EngineProviderOperation = when (this) {
    VirtualUriPermissionOperation.REVOKE,
    VirtualUriPermissionOperation.RELEASE_PERSISTABLE -> EngineProviderOperation.REVOKE_URI_PERMISSION
    VirtualUriPermissionOperation.GRANT,
    VirtualUriPermissionOperation.CHECK,
    VirtualUriPermissionOperation.TAKE_PERSISTABLE -> EngineProviderOperation.GRANT_URI_PERMISSION
}

private fun Int.toProviderAccessMode(): String? = when (this and 0x3) {
    0x1 -> "r"
    0x2 -> "w"
    0x3 -> "rw"
    else -> null
}
