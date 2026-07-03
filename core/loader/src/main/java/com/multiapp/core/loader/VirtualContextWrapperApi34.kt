package com.multiapp.core.loader

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.UserHandle
import androidx.annotation.RequiresApi
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.util.concurrent.Executor

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
open class VirtualContextWrapperApi34(
    base: Context,
    config: VirtualContextConfig,
    guestClassLoader: ClassLoader,
    activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager.global,
    servicePackageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime.global,
    broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
    dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry.global,
    serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { manager, request ->
        manager.createProxyIntent(request)
    },
    amsDispatcher: VirtualAmsComponentDispatcher? = null
) : VirtualContextWrapper(
    base = base,
    config = config,
    guestClassLoader = guestClassLoader,
    activityRecordManager = activityRecordManager,
    servicePackageRegistry = servicePackageRegistry,
    serviceRuntime = serviceRuntime,
    broadcastManager = broadcastManager,
    dynamicReceiverRegistry = dynamicReceiverRegistry,
    serviceProxyIntentFactory = serviceProxyIntentFactory,
    amsDispatcher = amsDispatcher
) {
    override fun createContext(contextParams: ContextParams): Context = this

    override fun bindService(
        service: Intent,
        flags: Context.BindServiceFlags,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindServiceIntent(service, api = "bindService:flags-executor")
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Context.BindServiceFlags): Boolean {
        return dispatchBindServiceIntent(service, api = "bindService:flags")
    }

    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: Context.BindServiceFlags,
        user: UserHandle
    ): Boolean {
        return dispatchBindServiceIntent(service, api = "bindServiceAsUser:flags")
    }

    override fun bindIsolatedService(
        service: Intent,
        flags: Context.BindServiceFlags,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindServiceIntent(service, api = "bindIsolatedService:flags")
    }
}
