package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import android.os.Build
import com.multiapp.core.model.virtual.VirtualContextConfig

object VirtualContextWrappers {
    fun create(
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
    ): VirtualContextWrapper {
        return when {
            Build.VERSION.SDK_INT >= 36 -> VirtualContextWrapperApi36(
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
            )
            Build.VERSION.SDK_INT >= 34 -> VirtualContextWrapperApi34(
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
            )
            else -> VirtualContextWrapper(
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
            )
        }
    }
}
