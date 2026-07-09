package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresApi
import com.multiapp.core.model.virtual.VirtualContextConfig

@RequiresApi(36)
class VirtualContextWrapperApi36(
    base: Context,
    config: VirtualContextConfig,
    guestClassLoader: ClassLoader,
    activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    servicePackageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime.global,
    processRuntime: VirtualProcessRuntime = VirtualProcessRuntime.global,
    broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
    dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry.global,
    serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { manager, request ->
        manager.createProxyIntent(request)
    },
    amsDispatcher: VirtualAmsComponentDispatcher? = null
) : VirtualContextWrapperApi34(
    base = base,
    config = config,
    guestClassLoader = guestClassLoader,
    activityRecordManager = activityRecordManager,
    servicePackageRegistry = servicePackageRegistry,
    serviceRuntime = serviceRuntime,
    processRuntime = processRuntime,
    broadcastManager = broadcastManager,
    dynamicReceiverRegistry = dynamicReceiverRegistry,
    serviceProxyIntentFactory = serviceProxyIntentFactory,
    amsDispatcher = amsDispatcher
) {
    override fun sendBroadcastWithMultiplePermissions(intent: Intent, receiverPermissions: Array<String>) {
        dispatchBroadcastIntent(intent)
    }
}
