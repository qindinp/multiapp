package com.multiapp.core.loader

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.multiapp.core.model.virtual.VirtualContextConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Runtime cache and lifecycle entry for explicit guest Service starts. */
class VirtualServiceRuntime(
    private val serviceFactory: ServiceFactory = ReflectionServiceFactory,
    private val serviceAttacher: ServiceAttacher = DefaultServiceAttacher,
    private val recordManager: VirtualServiceRecordManager = VirtualServiceRecordManager.global,
    private val hostServiceIdleStopper: HostServiceIdleStopper = HostServiceIdleStopper.NoOp,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun start(request: VirtualServiceRuntimeStartRequest): VirtualServiceRuntimeResult {
        val existing = recordManager.get(request.startRequest.instanceId, request.startRequest.guestServiceClassName)
        if (existing != null) {
            return startExisting(request, existing, cached = true)
        }

        val record = when (val created = createRecord(request.toCreateRequest())) {
            is ServiceCreateRecordResult.Created -> created.record
            is ServiceCreateRecordResult.CreateFailed -> return VirtualServiceRuntimeResult.CreateFailed(
                request.startRequest,
                created.error
            )
            is ServiceCreateRecordResult.AttachFailed -> return VirtualServiceRuntimeResult.AttachFailed(
                request.startRequest,
                created.service,
                created.error
            )
            is ServiceCreateRecordResult.OnCreateFailed -> return VirtualServiceRuntimeResult.OnCreateFailed(
                request.startRequest,
                created.service,
                created.error
            )
        }
        return startExisting(request, record, cached = false)
    }

    fun bind(request: VirtualServiceRuntimeBindRequest): VirtualServiceRuntimeBindResult {
        val existing = recordManager.get(request.startRequest.instanceId, request.startRequest.guestServiceClassName)
        val autoCreate = request.flags and Context.BIND_AUTO_CREATE != 0
        if (existing == null && !autoCreate) {
            return VirtualServiceRuntimeBindResult.NotCreated(
                startRequest = request.startRequest,
                flags = request.flags,
                serviceAlreadyRunning = false,
                reason = "bindAutoCreateNotRequested"
            )
        }
        val record = if (existing != null) {
            existing
        } else {
            when (val created = createRecord(request.toCreateRequest())) {
                is ServiceCreateRecordResult.Created -> created.record
                is ServiceCreateRecordResult.CreateFailed -> return VirtualServiceRuntimeBindResult.CreateFailed(
                    request.startRequest,
                    created.error
                )
                is ServiceCreateRecordResult.AttachFailed -> return VirtualServiceRuntimeBindResult.AttachFailed(
                    request.startRequest,
                    created.service,
                    created.error
                )
                is ServiceCreateRecordResult.OnCreateFailed -> return VirtualServiceRuntimeBindResult.OnCreateFailed(
                    request.startRequest,
                    created.service,
                    created.error
                )
            }
        }

        val bindKey = request.startRequest.sourceIntent.toBindKey()
        val existingBinding = recordManager.getBinding(
            instanceId = request.startRequest.instanceId,
            guestServiceClassName = request.startRequest.guestServiceClassName,
            bindKey = bindKey
        )
        if (existingBinding != null && existingBinding.activeConnectionCount > 0) {
            val updated = recordManager.updateBind(
                instanceId = request.startRequest.instanceId,
                guestServiceClassName = request.startRequest.guestServiceClassName,
                bindKey = bindKey,
                binder = existingBinding.binder,
                flags = request.flags,
                rebindPending = false
            )
            return VirtualServiceRuntimeBindResult.Bound(
                startRequest = request.startRequest,
                service = record.service,
                binder = existingBinding.binder,
                cached = existing != null,
                bindKey = bindKey,
                flags = request.flags,
                bindCount = updated?.bindings?.get(bindKey)?.bindCount ?: existingBinding.bindCount + 1,
                activeConnectionCount = updated?.bindings?.get(bindKey)?.activeConnectionCount
                    ?: existingBinding.activeConnectionCount + 1,
                reusedBinder = true,
                rebindDelivered = false
            )
        }
        if (existingBinding?.rebindPending == true) {
            val rebindResult = runCatching { record.service.onRebind(request.startRequest.sourceIntent) }
            if (rebindResult.isFailure) {
                return VirtualServiceRuntimeBindResult.OnBindFailed(
                    startRequest = request.startRequest,
                    service = record.service,
                    cached = existing != null,
                    bindKey = bindKey,
                    error = rebindResult.exceptionOrNull()
                        ?: IllegalStateException("onRebind failed without throwable")
                )
            }
            val updated = recordManager.updateBind(
                instanceId = request.startRequest.instanceId,
                guestServiceClassName = request.startRequest.guestServiceClassName,
                bindKey = bindKey,
                binder = existingBinding.binder,
                flags = request.flags,
                rebindPending = false
            )
            return VirtualServiceRuntimeBindResult.Bound(
                startRequest = request.startRequest,
                service = record.service,
                binder = existingBinding.binder,
                cached = existing != null,
                bindKey = bindKey,
                flags = request.flags,
                bindCount = updated?.bindings?.get(bindKey)?.bindCount ?: existingBinding.bindCount + 1,
                activeConnectionCount = updated?.bindings?.get(bindKey)?.activeConnectionCount ?: 1,
                reusedBinder = true,
                rebindDelivered = true
            )
        }

        val bindResult = runCatching { record.service.onBind(request.startRequest.sourceIntent) }
        if (bindResult.isFailure) {
            return VirtualServiceRuntimeBindResult.OnBindFailed(
                startRequest = request.startRequest,
                service = record.service,
                cached = existing != null,
                bindKey = bindKey,
                error = bindResult.exceptionOrNull()
                    ?: IllegalStateException("onBind failed without throwable")
            )
        }

        val binder = bindResult.getOrNull()
        val updated = recordManager.updateBind(
            instanceId = request.startRequest.instanceId,
            guestServiceClassName = request.startRequest.guestServiceClassName,
            bindKey = bindKey,
            binder = binder,
            flags = request.flags
        )
        return VirtualServiceRuntimeBindResult.Bound(
            startRequest = request.startRequest,
            service = record.service,
            binder = binder,
            cached = existing != null,
            bindKey = bindKey,
            flags = request.flags,
            bindCount = updated?.bindings?.get(bindKey)?.bindCount ?: 1,
            activeConnectionCount = updated?.bindings?.get(bindKey)?.activeConnectionCount ?: 1,
            reusedBinder = false,
            rebindDelivered = false
        )
    }

    fun get(instanceId: String, guestServiceClassName: String): Service? =
        recordManager.get(instanceId, guestServiceClassName)?.service

    fun unbind(request: VirtualServiceRuntimeUnbindRequest): VirtualServiceRuntimeUnbindResult {
        val record = recordManager.get(
            request.startRequest.instanceId,
            request.startRequest.guestServiceClassName
        ) ?: return VirtualServiceRuntimeUnbindResult.NotFound(request.startRequest)

        val bindKey = request.startRequest.sourceIntent.toBindKey()
        val binding = record.bindings[bindKey]
        val shouldCallOnUnbind = binding == null || binding.activeConnectionCount <= 1
        val unbindResult = if (shouldCallOnUnbind) {
            runCatching { record.service.onUnbind(request.startRequest.sourceIntent) }
        } else {
            Result.success(false)
        }
        if (unbindResult.isFailure) {
            return VirtualServiceRuntimeUnbindResult.OnUnbindFailed(
                startRequest = request.startRequest,
                service = record.service,
                bindKey = bindKey,
                error = unbindResult.exceptionOrNull()
                    ?: IllegalStateException("onUnbind failed without throwable")
            )
        }

        val onUnbindReturned = if (shouldCallOnUnbind) unbindResult.getOrDefault(false) else false
        val updated = recordManager.updateUnbind(
            instanceId = request.startRequest.instanceId,
            guestServiceClassName = request.startRequest.guestServiceClassName,
            bindKey = bindKey,
            lastUnbindReturned = onUnbindReturned
        ) ?: record

        if (updated.activeBindCount == 0 && !updated.started) {
            val destroyResult = runCatching { updated.service.onDestroy() }
            if (destroyResult.isFailure) {
                return VirtualServiceRuntimeUnbindResult.OnDestroyFailed(
                    startRequest = request.startRequest,
                    service = updated.service,
                    bindKey = bindKey,
                    onUnbindResult = onUnbindReturned,
                    error = destroyResult.exceptionOrNull()
                        ?: IllegalStateException("onDestroy failed without throwable")
                )
            }
            recordManager.remove(request.startRequest.instanceId, request.startRequest.guestServiceClassName)
            val idleStop = requestHostIdleStop(request.startRequest, "unbindDestroyed")
            return VirtualServiceRuntimeUnbindResult.Unbound(
                startRequest = request.startRequest,
                service = updated.service,
                bindKey = bindKey,
                onUnbindResult = onUnbindReturned,
                onUnbindCalled = shouldCallOnUnbind,
                destroyed = true,
                activeConnectionCount = updated.bindings[bindKey]?.activeConnectionCount ?: 0,
                activeBindCount = updated.activeBindCount,
                idleStopResult = idleStop
            )
        }

        return VirtualServiceRuntimeUnbindResult.Unbound(
            startRequest = request.startRequest,
            service = updated.service,
            bindKey = bindKey,
            onUnbindResult = onUnbindReturned,
            onUnbindCalled = shouldCallOnUnbind,
            destroyed = false,
            activeConnectionCount = updated.bindings[bindKey]?.activeConnectionCount ?: 0,
            activeBindCount = updated.activeBindCount
        )
    }

    fun stop(request: VirtualServiceStopRequest): VirtualServiceRuntimeStopResult {
        val record = recordManager.get(request.instanceId, request.guestServiceClassName)
            ?: return VirtualServiceRuntimeStopResult.NotFound(request)
        val updated = recordManager.markStartedStopped(request.instanceId, request.guestServiceClassName)
            ?: record
        if (updated.activeBindCount > 0) {
            return VirtualServiceRuntimeStopResult.Stopped(
                stopRequest = request,
                service = updated.service,
                destroyed = false,
                activeBindCount = updated.activeBindCount
            )
        }

        val destroyResult = runCatching { updated.service.onDestroy() }
        if (destroyResult.isFailure) {
            return VirtualServiceRuntimeStopResult.OnDestroyFailed(
                stopRequest = request,
                service = updated.service,
                error = destroyResult.exceptionOrNull()
                    ?: IllegalStateException("onDestroy failed without throwable")
            )
        }

        recordManager.remove(request.instanceId, request.guestServiceClassName)
        val idleStop = requestHostIdleStop(request.toStartRequest(), "stopServiceDestroyed")
        return VirtualServiceRuntimeStopResult.Stopped(
            stopRequest = request,
            service = updated.service,
            destroyed = true,
            activeBindCount = 0,
            idleStopResult = idleStop
        )
    }

    internal fun stopServiceToken(token: IBinder, startId: Int): Boolean {
        val record = recordManager.getByToken(token) ?: return false
        val latestStartId = record.lastStartId
        val stopMatches = startId < 0 || latestStartId == null || latestStartId == startId
        if (!stopMatches) return false
        val updated = recordManager.markStartedStopped(token) ?: return false
        if (updated.activeBindCount > 0) return true
        val destroyResult = runCatching { updated.service.onDestroy() }
        if (destroyResult.isFailure) return false
        recordManager.remove(updated.instanceId, updated.guestServiceClassName)
        requestHostIdleStop(updated.toStartRequest(), "stopServiceTokenDestroyed")
        return true
    }

    internal fun setServiceForegroundToken(
        token: IBinder,
        notificationId: Int,
        notification: Notification?,
        foregroundServiceType: Int
    ) {
        val foreground = notificationId > 0 && notification != null
        recordManager.updateForeground(
            token = token,
            foreground = foreground,
            notificationId = notificationId.takeIf { foreground },
            foregroundServiceType = foregroundServiceType.takeIf { foreground } ?: 0
        )
    }

    internal fun foregroundServiceTypeForToken(token: IBinder): Int =
        recordManager.getByToken(token)?.foregroundServiceType ?: 0

    fun clear() {
        recordManager.clear()
    }

    private fun startExisting(
        request: VirtualServiceRuntimeStartRequest,
        record: VirtualServiceRecord,
        cached: Boolean
    ): VirtualServiceRuntimeResult {
        val delivered = recordManager.beginStart(
            instanceId = request.startRequest.instanceId,
            guestServiceClassName = request.startRequest.guestServiceClassName,
            startId = request.startId
        ) ?: record
        val result = runCatching {
            delivered.service.onStartCommand(
                request.startRequest.sourceIntent,
                request.flags,
                request.startId
            )
        }
        if (result.isFailure) {
            return VirtualServiceRuntimeResult.OnStartCommandFailed(
                startRequest = request.startRequest,
                service = delivered.service,
                cached = cached,
                error = result.exceptionOrNull()
                    ?: IllegalStateException("onStartCommand failed without throwable")
            )
        }

        val startCommandResult = result.getOrThrow()
        val updated = recordManager.completeStart(
            instanceId = request.startRequest.instanceId,
            guestServiceClassName = request.startRequest.guestServiceClassName,
            lastStartCommandResult = startCommandResult
        ) ?: delivered.copy(started = false)

        return if (cached) {
            VirtualServiceRuntimeResult.StartedCached(
                startRequest = request.startRequest,
                service = updated.service,
                startCommandResult = startCommandResult,
                activeStartCount = updated.activeStartCount,
                activeBindCount = updated.activeBindCount,
                foreground = updated.foreground,
                foregroundNotificationId = updated.foregroundNotificationId,
                foregroundServiceType = updated.foregroundServiceType
            )
        } else {
            VirtualServiceRuntimeResult.CreatedAndStarted(
                startRequest = request.startRequest,
                service = updated.service,
                startCommandResult = startCommandResult,
                activeStartCount = updated.activeStartCount,
                activeBindCount = updated.activeBindCount,
                foreground = updated.foreground,
                foregroundNotificationId = updated.foregroundNotificationId,
                foregroundServiceType = updated.foregroundServiceType
            )
        }
    }

    private fun createRecord(request: VirtualServiceRuntimeCreateRequest): ServiceCreateRecordResult {
        val service = try {
            serviceFactory.create(
                classLoader = request.guestClassLoader,
                className = request.startRequest.guestServiceClassName
            )
        } catch (error: Throwable) {
            return ServiceCreateRecordResult.CreateFailed(error)
        }

        val token = Binder()
        val attachArguments = VirtualServiceAttachArguments(
            token = token,
            activityManager = if (serviceAttacher === DefaultServiceAttacher) {
                VirtualServiceActivityManagerProxy.create(this)
            } else {
                null
            }
        )
        val attachResult = runCatching {
            VirtualServiceAttachContext.with(attachArguments) {
                serviceAttacher.attach(
                    service = service,
                    context = request.guestContext,
                    className = request.startRequest.guestServiceClassName,
                    application = request.guestApplication
                )
            }
        }
        if (attachResult.isFailure) {
            return ServiceCreateRecordResult.AttachFailed(
                service = service,
                error = attachResult.exceptionOrNull()
                    ?: IllegalStateException("attach failed without throwable")
            )
        }

        val initialRecord = recordManager.put(
            VirtualServiceRecord(
                instanceId = request.startRequest.instanceId,
                originPackageName = request.startRequest.originPackageName,
                guestServiceClassName = request.startRequest.guestServiceClassName,
                service = service,
                createdAtMs = clock(),
                token = token
            )
        )
        val createResult = runCatching { service.onCreate() }
        if (createResult.isFailure) {
            recordManager.remove(request.startRequest.instanceId, request.startRequest.guestServiceClassName)
            return ServiceCreateRecordResult.OnCreateFailed(
                service = service,
                error = createResult.exceptionOrNull()
                    ?: IllegalStateException("onCreate failed without throwable")
            )
        }

        return ServiceCreateRecordResult.Created(
            recordManager.get(request.startRequest.instanceId, request.startRequest.guestServiceClassName)
                ?: initialRecord
        )
    }

    companion object {
        val global: VirtualServiceRuntime = VirtualServiceRuntime(
            hostServiceIdleStopper = HostServiceIdleStopper.AndroidApplication
        )
    }

    private fun requestHostIdleStop(
        startRequest: VirtualServiceStartRequest,
        reason: String
    ): HostServiceIdleStopResult = hostServiceIdleStopper.requestIdleStop(startRequest, reason)
}

fun interface HostServiceIdleStopper {
    fun requestIdleStop(
        startRequest: VirtualServiceStartRequest,
        reason: String
    ): HostServiceIdleStopResult

    companion object {
        val NoOp = HostServiceIdleStopper { _, reason ->
            HostServiceIdleStopResult(
                idleStopRequested = false,
                idleStopReason = reason,
                hostStopServiceReturnValue = null,
                detail = "hostServiceIdleStopperNotConfigured"
            )
        }

        val AndroidApplication = HostServiceIdleStopper { startRequest, reason ->
            runCatching {
                val application = ActivityThreadCompat.currentApplication()
                val stopIntent = VirtualServiceManager(application.packageName).createProxyIntent(startRequest)
                HostServiceIdleStopResult(
                    idleStopRequested = true,
                    idleStopReason = reason,
                    hostStopServiceReturnValue = application.stopService(stopIntent),
                    detail = "hostStopServiceRequested"
                )
            }.getOrElse { error ->
                HostServiceIdleStopResult(
                    idleStopRequested = true,
                    idleStopReason = reason,
                    hostStopServiceReturnValue = null,
                    errorClassName = error.javaClass.name,
                    errorMessage = error.message,
                    detail = "hostStopServiceFailed"
                )
            }
        }
    }
}

data class HostServiceIdleStopResult(
    val idleStopRequested: Boolean,
    val idleStopReason: String,
    val hostStopServiceReturnValue: Boolean? = null,
    val errorClassName: String? = null,
    val errorMessage: String? = null,
    val detail: String = ""
) {
    companion object {
        fun notRequested(reason: String): HostServiceIdleStopResult = HostServiceIdleStopResult(
            idleStopRequested = false,
            idleStopReason = reason,
            hostStopServiceReturnValue = null
        )
    }
}

private data class VirtualServiceRuntimeCreateRequest(
    val startRequest: VirtualServiceStartRequest,
    val guestContext: Context,
    val guestClassLoader: ClassLoader,
    val guestApplication: Application?
)

private fun VirtualServiceRuntimeStartRequest.toCreateRequest(): VirtualServiceRuntimeCreateRequest =
    VirtualServiceRuntimeCreateRequest(
        startRequest = startRequest,
        guestContext = guestContext,
        guestClassLoader = guestClassLoader,
        guestApplication = guestApplication
    )

private fun VirtualServiceRuntimeBindRequest.toCreateRequest(): VirtualServiceRuntimeCreateRequest =
    VirtualServiceRuntimeCreateRequest(
        startRequest = startRequest,
        guestContext = guestContext,
        guestClassLoader = guestClassLoader,
        guestApplication = guestApplication
    )

private fun VirtualServiceStopRequest.toStartRequest(): VirtualServiceStartRequest =
    VirtualServiceStartRequest(
        instanceId = instanceId,
        originPackageName = originPackageName,
        guestServiceClassName = guestServiceClassName,
        sourceIntent = sourceIntent,
        reason = reason
    )

private fun VirtualServiceRecord.toStartRequest(): VirtualServiceStartRequest =
    VirtualServiceStartRequest(
        instanceId = instanceId,
        originPackageName = originPackageName,
        guestServiceClassName = guestServiceClassName,
        sourceIntent = Intent(),
        reason = "serviceRecord"
    )

private sealed class ServiceCreateRecordResult {
    data class Created(val record: VirtualServiceRecord) : ServiceCreateRecordResult()
    data class CreateFailed(val error: Throwable) : ServiceCreateRecordResult()
    data class AttachFailed(val service: Service, val error: Throwable) : ServiceCreateRecordResult()
    data class OnCreateFailed(val service: Service, val error: Throwable) : ServiceCreateRecordResult()
}

data class VirtualServiceRuntimeStartRequest(
    val startRequest: VirtualServiceStartRequest,
    val guestContext: Context,
    val guestClassLoader: ClassLoader,
    val guestApplication: Application?,
    val config: VirtualContextConfig,
    val flags: Int,
    val startId: Int
)

data class VirtualServiceRuntimeBindRequest(
    val startRequest: VirtualServiceStartRequest,
    val guestContext: Context,
    val guestClassLoader: ClassLoader,
    val guestApplication: Application?,
    val config: VirtualContextConfig,
    val flags: Int = 0
)

data class VirtualServiceRuntimeUnbindRequest(
    val startRequest: VirtualServiceStartRequest
)

sealed class VirtualServiceRuntimeResult {
    abstract val startRequest: VirtualServiceStartRequest

    data class CreatedAndStarted(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val startCommandResult: Int,
        val activeStartCount: Int,
        val activeBindCount: Int,
        val foreground: Boolean = false,
        val foregroundNotificationId: Int? = null,
        val foregroundServiceType: Int = 0
    ) : VirtualServiceRuntimeResult()

    data class StartedCached(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val startCommandResult: Int,
        val activeStartCount: Int,
        val activeBindCount: Int,
        val foreground: Boolean = false,
        val foregroundNotificationId: Int? = null,
        val foregroundServiceType: Int = 0
    ) : VirtualServiceRuntimeResult()

    data class CreateFailed(
        override val startRequest: VirtualServiceStartRequest,
        val error: Throwable
    ) : VirtualServiceRuntimeResult()

    data class AttachFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val error: Throwable
    ) : VirtualServiceRuntimeResult()

    data class OnCreateFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val error: Throwable
    ) : VirtualServiceRuntimeResult()

    data class OnStartCommandFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val cached: Boolean,
        val error: Throwable
    ) : VirtualServiceRuntimeResult()
}

sealed class VirtualServiceRuntimeBindResult {
    abstract val startRequest: VirtualServiceStartRequest

    data class NotCreated(
        override val startRequest: VirtualServiceStartRequest,
        val flags: Int,
        val serviceAlreadyRunning: Boolean,
        val reason: String
    ) : VirtualServiceRuntimeBindResult()

    data class Bound(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val binder: IBinder?,
        val cached: Boolean,
        val bindKey: String,
        val flags: Int,
        val bindCount: Int,
        val activeConnectionCount: Int,
        val reusedBinder: Boolean,
        val rebindDelivered: Boolean
    ) : VirtualServiceRuntimeBindResult()

    data class CreateFailed(
        override val startRequest: VirtualServiceStartRequest,
        val error: Throwable
    ) : VirtualServiceRuntimeBindResult()

    data class AttachFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val error: Throwable
    ) : VirtualServiceRuntimeBindResult()

    data class OnCreateFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val error: Throwable
    ) : VirtualServiceRuntimeBindResult()

    data class OnBindFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val cached: Boolean,
        val bindKey: String,
        val error: Throwable
    ) : VirtualServiceRuntimeBindResult()
}

sealed class VirtualServiceRuntimeUnbindResult {
    abstract val startRequest: VirtualServiceStartRequest

    data class Unbound(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val bindKey: String,
        val onUnbindResult: Boolean,
        val onUnbindCalled: Boolean,
        val destroyed: Boolean,
        val activeConnectionCount: Int,
        val activeBindCount: Int,
        val idleStopResult: HostServiceIdleStopResult = HostServiceIdleStopResult.notRequested("serviceStillActive")
    ) : VirtualServiceRuntimeUnbindResult()

    data class NotFound(
        override val startRequest: VirtualServiceStartRequest
    ) : VirtualServiceRuntimeUnbindResult()

    data class OnUnbindFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val bindKey: String,
        val error: Throwable
    ) : VirtualServiceRuntimeUnbindResult()

    data class OnDestroyFailed(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val bindKey: String,
        val onUnbindResult: Boolean,
        val error: Throwable
    ) : VirtualServiceRuntimeUnbindResult()
}

private fun Intent.toBindKey(): String {
    val component = runCatching { component }.getOrNull()
    val categories = runCatching { categories?.toList()?.sorted().orEmpty() }.getOrDefault(emptyList())
    return listOf(
        "component=${component?.packageName.orEmpty()}/${component?.className.orEmpty()}",
        "package=${runCatching { getPackage() }.getOrNull().orEmpty()}",
        "action=${runCatching { action }.getOrNull().orEmpty()}",
        "data=${runCatching { dataString }.getOrNull().orEmpty()}",
        "type=${runCatching { type }.getOrNull().orEmpty()}",
        "categories=${categories.joinToString(",")}"
    ).joinToString("|")
}

sealed class VirtualServiceRuntimeStopResult {
    abstract val stopRequest: VirtualServiceStopRequest

    data class Stopped(
        override val stopRequest: VirtualServiceStopRequest,
        val service: Service,
        val destroyed: Boolean = true,
        val activeBindCount: Int = 0,
        val idleStopResult: HostServiceIdleStopResult = HostServiceIdleStopResult.notRequested("serviceStillActive")
    ) : VirtualServiceRuntimeStopResult()

    data class NotFound(
        override val stopRequest: VirtualServiceStopRequest
    ) : VirtualServiceRuntimeStopResult()

    data class OnDestroyFailed(
        override val stopRequest: VirtualServiceStopRequest,
        val service: Service,
        val error: Throwable
    ) : VirtualServiceRuntimeStopResult()
}

private data class VirtualServiceAttachArguments(
    val token: IBinder,
    val activityManager: Any?
)

private object VirtualServiceAttachContext {
    private val local = ThreadLocal<VirtualServiceAttachArguments>()

    fun <T> with(arguments: VirtualServiceAttachArguments, block: () -> T): T {
        val previous = local.get()
        local.set(arguments)
        return try {
            block()
        } finally {
            if (previous == null) {
                local.remove()
            } else {
                local.set(previous)
            }
        }
    }

    fun current(): VirtualServiceAttachArguments? = local.get()
}

private object VirtualServiceActivityManagerProxy {
    fun create(runtime: VirtualServiceRuntime): Any? {
        val activityManagerClass = runCatching { Class.forName("android.app.IActivityManager") }.getOrNull()
            ?: return null
        return Proxy.newProxyInstance(
            activityManagerClass.classLoader,
            arrayOf(activityManagerClass),
            Handler(runtime)
        )
    }

    private class Handler(
        private val runtime: VirtualServiceRuntime
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            return when (method.name) {
                "stopServiceToken" -> {
                    val token = args?.getOrNull(1) as? IBinder
                    val startId = args?.getOrNull(2) as? Int ?: -1
                    token != null && runtime.stopServiceToken(token, startId)
                }
                "setServiceForeground" -> {
                    val token = args?.getOrNull(1) as? IBinder
                    val notificationId = args?.getOrNull(2) as? Int ?: 0
                    val notification = args?.getOrNull(3) as? Notification
                    val foregroundServiceType = args?.lastOrNull { it is Int } as? Int ?: 0
                    if (token != null) {
                        runtime.setServiceForegroundToken(
                            token = token,
                            notificationId = notificationId,
                            notification = notification,
                            foregroundServiceType = foregroundServiceType
                        )
                    }
                    defaultReturnValue(method.returnType)
                }
                "getForegroundServiceType" -> {
                    val token = args?.getOrNull(1) as? IBinder
                    if (token != null) runtime.foregroundServiceTypeForToken(token) else 0
                }
                "shouldServiceTimeOut",
                "hasServiceTimeLimitExceeded" -> false
                "toString" -> "MultiAppVirtualServiceActivityManager"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultReturnValue(method.returnType)
            }
        }

        private fun defaultReturnValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Void.TYPE -> null
            else -> null
        }
    }
}

fun interface ServiceFactory {
    fun create(classLoader: ClassLoader, className: String): Service
}

fun interface ServiceAttacher {
    fun attach(service: Service, context: Context, className: String, application: Application?)
}

object ReflectionServiceFactory : ServiceFactory {
    override fun create(classLoader: ClassLoader, className: String): Service {
        val clazz = classLoader.loadClass(className)
        return clazz.getDeclaredConstructor().newInstance() as Service
    }
}

object DefaultServiceAttacher : ServiceAttacher {
    override fun attach(service: Service, context: Context, className: String, application: Application?) {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)
            ?: throw UnsupportedOperationException("RuntimeNotBound: missing ActivityThread")
        val attach = findAttachMethod(activityThreadClass).apply { isAccessible = true }
        val attachArguments = VirtualServiceAttachContext.current()
        attach.invoke(
            service,
            context,
            activityThread,
            className,
            attachArguments?.token,
            application,
            attachArguments?.activityManager
        )
    }

    internal fun findAttachMethod(activityThreadClass: Class<*>): java.lang.reflect.Method {
        val candidates = mutableListOf<Array<Class<*>>>()
        candidates += arrayOf(
            Context::class.java,
            activityThreadClass,
            String::class.java,
            IBinder::class.java,
            Application::class.java,
            Object::class.java
        )
        runCatching { Class.forName("android.app.IActivityManager") }.getOrNull()?.let { activityManagerClass ->
            candidates += arrayOf(
                Context::class.java,
                activityThreadClass,
                String::class.java,
                IBinder::class.java,
                Application::class.java,
                activityManagerClass
            )
        }

        for (parameterTypes in candidates) {
            runCatching { return Service::class.java.getDeclaredMethod("attach", *parameterTypes) }
        }

        return Service::class.java.declaredMethods.firstOrNull { method ->
            method.name == "attach" &&
                method.parameterTypes.size == 6 &&
                method.parameterTypes[0].isAssignableFrom(Context::class.java) &&
                method.parameterTypes[1].isAssignableFrom(activityThreadClass) &&
                method.parameterTypes[2].isAssignableFrom(String::class.java) &&
                method.parameterTypes[3].isAssignableFrom(IBinder::class.java) &&
                method.parameterTypes[4].isAssignableFrom(Application::class.java)
        } ?: throw NoSuchMethodException(
            "android.app.Service.attach compatible signature not found; candidates=" +
                candidates.joinToString { parameterTypes ->
                    parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                }
        )
    }
}
