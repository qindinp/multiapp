package com.multiapp.core.loader

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.ServiceConnection
import android.content.AttributionSource
import android.os.Build
import android.os.UserHandle
import androidx.annotation.RequiresApi
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.lang.reflect.Modifier
import java.util.concurrent.Executor

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
open class VirtualContextWrapperApi34(
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
    amsDispatcher: VirtualAmsComponentDispatcher? = null,
    private val bindServiceFlagsReader: (Context.BindServiceFlags) -> Int = { flags ->
        flags.toBindServiceFlagsIntValue()
    }
) : VirtualContextWrapper(
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
    override fun createContext(contextParams: ContextParams): Context = this

    override fun getAttributionSource(): AttributionSource {
        return AttributionSource.Builder(super.getAttributionSource())
            .setPackageName(opPackageName)
            .build()
    }

    override fun bindService(
        service: Intent,
        flags: Context.BindServiceFlags,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindServiceIntent(
            service,
            conn,
            flags = bindServiceFlagsReader(flags),
            executor = executor,
            api = "bindService:flags-executor",
            systemBind = { systemDispatchContext().bindService(service, flags, executor, conn) }
        )
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Context.BindServiceFlags): Boolean {
        return dispatchBindServiceIntent(
            service,
            conn,
            flags = bindServiceFlagsReader(flags),
            executor = null,
            api = "bindService:flags",
            systemBind = { systemDispatchContext().bindService(service, conn, flags) }
        )
    }

    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: Context.BindServiceFlags,
        user: UserHandle
    ): Boolean {
        return dispatchBindServiceIntent(
            service,
            conn,
            flags = bindServiceFlagsReader(flags),
            executor = null,
            api = "bindServiceAsUser:flags",
            systemBind = { systemDispatchContext().bindServiceAsUser(service, conn, flags, user) }
        )
    }

    override fun bindIsolatedService(
        service: Intent,
        flags: Context.BindServiceFlags,
        instanceName: String,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        return dispatchBindServiceIntent(
            service,
            conn,
            flags = bindServiceFlagsReader(flags),
            executor = executor,
            api = "bindIsolatedService:flags",
            systemBind = {
                systemDispatchContext().bindIsolatedService(service, flags, instanceName, executor, conn)
            }
        )
    }
}

private fun Context.BindServiceFlags.toBindServiceFlagsIntValue(): Int =
    readValueByMethod()
        ?: readValueByField()
        ?: 0

private fun Context.BindServiceFlags.readValueByMethod(): Int? =
    runCatching {
        val method = (
            javaClass.methods.asSequence() + javaClass.declaredMethods.asSequence()
            )
            .firstOrNull { method -> method.name == "getValue" && method.parameterTypes.isEmpty() }
            ?: return@runCatching null
        method.isAccessible = true
        method.invoke(this).toIntValue()
    }.getOrNull()

private fun Context.BindServiceFlags.readValueByField(): Int? =
    runCatching {
        javaClass.declaredFields.firstNotNullOfOrNull { field ->
            if (Modifier.isStatic(field.modifiers)) {
                null
            } else {
                field.isAccessible = true
                field.get(this).toIntValue()
            }
        }
    }.getOrNull()

private fun Any?.toIntValue(): Int? = when (this) {
    is Long -> toInt()
    is Int -> this
    else -> null
}
