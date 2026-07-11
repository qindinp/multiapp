package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualPermissionCheckDispatchResult
import com.multiapp.core.loader.VirtualPermissionCheckDispatcher
import com.multiapp.core.loader.VirtualPermissionCheckRequest

internal class EngineVirtualPermissionDispatcher(
    private val service: VirtualPermissionService
) : VirtualPermissionCheckDispatcher {
    override fun dispatch(request: VirtualPermissionCheckRequest): VirtualPermissionCheckDispatchResult {
        val runtime = service.queryRuntimeBinding(request.instanceId)
        if (
            runtime.originPackageName != request.packageName &&
            runtime.virtualPackageName != request.packageName
        ) {
            return VirtualPermissionCheckDispatchResult(
                handled = true,
                granted = false,
                reason = "permission_package_mismatch:${request.packageName}"
            )
        }
        val result = service.checkPermission(request.instanceId, request.permissionName)
        return VirtualPermissionCheckDispatchResult(
            handled = true,
            granted = result.granted,
            reason = result.message
        )
    }
}
