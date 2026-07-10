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
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.virtual.VirtualContextConfig

internal data class EngineUriPermissionBackend(
    val providerService: VirtualProviderService,
    val runtimes: () -> List<VirtualInstanceRuntime>
)

class EngineVirtualUriPermissionDispatcherFactory internal constructor(
    private val backendFactory: (Context) -> EngineUriPermissionBackend = { context ->
        val handle = EngineRuntimeInstallers.fileBackedSystemServer(context)
        EngineUriPermissionBackend(
            providerService = IpcBackedVirtualProviderService(handle.server.providerService),
            runtimes = handle.server.runtimeService::list
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
            runtimes = backend.runtimes,
            hostUid = uidProvider(request.hostContext),
            processId = pidProvider()
        )
    }
}

internal class EngineVirtualUriPermissionDispatcher(
    private val config: VirtualContextConfig,
    private val providerService: VirtualProviderService,
    private val runtimes: () -> List<VirtualInstanceRuntime>,
    private val hostUid: Int,
    private val processId: Int
) : VirtualUriPermissionDispatcher {
    override fun dispatch(request: VirtualUriPermissionRequest): VirtualUriPermissionResult {
        val authority = request.uri.authority?.takeIf { it.isNotBlank() }
            ?: return VirtualUriPermissionResult.notHandled("uri_authority_missing")
        if (authority !in guestAuthorities()) {
            return VirtualUriPermissionResult.notHandled("uri_authority_not_owned_by_guest")
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
            VirtualUriPermissionOperation.CHECK -> checkUriPermission(request, authority)
                ?: return VirtualUriPermissionResult(
                    handled = true,
                    success = false,
                    granted = false,
                    reason = "provider_uri_grant_target_process_unresolved:pid=${request.pid}:uid=${request.uid}"
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
        authority: String
    ): VirtualProviderUriGrantResult? {
        if (request.uid != hostUid || request.pid <= 0) return null
        val candidates = runtimes().filter { it.processId == request.pid }
        val targetInstanceId = when {
            candidates.size == 1 -> candidates.single().instanceId
            candidates.isEmpty() && request.pid == processId -> config.instanceId
            else -> return null
        }
        return providerService.checkUriPermission(
            targetInstanceId = targetInstanceId,
            request = request.toEngineRequest(
                authority = authority,
                ownerInstanceId = config.instanceId,
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
