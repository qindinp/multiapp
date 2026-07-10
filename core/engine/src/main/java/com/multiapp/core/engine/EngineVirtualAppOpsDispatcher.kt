package com.multiapp.core.engine

import android.os.Process
import com.multiapp.core.loader.VirtualAppOpsDispatchRequest
import com.multiapp.core.loader.VirtualAppOpsDispatchResult
import com.multiapp.core.loader.VirtualAppOpsDispatcher

class EngineVirtualAppOpsDispatcher(
    private val service: VirtualAppOpsService,
    private val hostUid: Int,
    private val processIdProvider: () -> Int = Process::myPid
) : VirtualAppOpsDispatcher {
    override fun dispatch(request: VirtualAppOpsDispatchRequest): VirtualAppOpsDispatchResult {
        val result = service.queryMode(
            request.instanceId,
            VirtualAppOpsQueryRequest(
                methodName = request.methodName,
                opCode = request.opCode,
                uid = hostUid,
                packageName = request.packageName,
                hostUid = hostUid,
                callingPid = processIdProvider()
            )
        )
        return if (result.intercept || result.blockSystemCall) {
            VirtualAppOpsDispatchResult(
                handled = true,
                mode = result.mode,
                blockSystemCall = result.blockSystemCall,
                reason = result.message
            )
        } else {
            VirtualAppOpsDispatchResult.passthrough(result.message)
        }
    }
}
