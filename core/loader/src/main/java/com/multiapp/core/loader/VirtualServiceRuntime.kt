package com.multiapp.core.loader

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.VirtualContextConfig

/** Runtime cache and lifecycle entry for explicit guest Service starts. */
class VirtualServiceRuntime(
    private val serviceFactory: ServiceFactory = ReflectionServiceFactory,
    private val serviceAttacher: ServiceAttacher = DefaultServiceAttacher,
    private val recordManager: VirtualServiceRecordManager = VirtualServiceRecordManager.global,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun start(request: VirtualServiceRuntimeStartRequest): VirtualServiceRuntimeResult {
        val existing = recordManager.get(request.startRequest.instanceId, request.startRequest.guestServiceClassName)
        if (existing != null) {
            return startExisting(request, existing, cached = true)
        }

        val service = try {
            serviceFactory.create(
                classLoader = request.guestClassLoader,
                className = request.startRequest.guestServiceClassName
            )
        } catch (error: Throwable) {
            return VirtualServiceRuntimeResult.CreateFailed(request.startRequest, error)
        }

        val attachResult = runCatching {
            serviceAttacher.attach(
                service = service,
                context = request.guestContext,
                className = request.startRequest.guestServiceClassName,
                application = request.guestApplication
            )
        }
        if (attachResult.isFailure) {
            return VirtualServiceRuntimeResult.AttachFailed(
                startRequest = request.startRequest,
                service = service,
                error = attachResult.exceptionOrNull()
                    ?: IllegalStateException("attach failed without throwable")
            )
        }

        val createResult = runCatching { service.onCreate() }
        if (createResult.isFailure) {
            return VirtualServiceRuntimeResult.OnCreateFailed(
                startRequest = request.startRequest,
                service = service,
                error = createResult.exceptionOrNull()
                    ?: IllegalStateException("onCreate failed without throwable")
            )
        }

        val record = recordManager.put(
            VirtualServiceRecord(
                instanceId = request.startRequest.instanceId,
                originPackageName = request.startRequest.originPackageName,
                guestServiceClassName = request.startRequest.guestServiceClassName,
                service = service,
                createdAtMs = clock()
            )
        )
        return startExisting(request, record, cached = false)
    }

    fun get(instanceId: String, guestServiceClassName: String): Service? =
        recordManager.get(instanceId, guestServiceClassName)?.service

    fun stop(request: VirtualServiceStopRequest): VirtualServiceRuntimeStopResult {
        val record = recordManager.get(request.instanceId, request.guestServiceClassName)
            ?: return VirtualServiceRuntimeStopResult.NotFound(request)

        val destroyResult = runCatching { record.service.onDestroy() }
        if (destroyResult.isFailure) {
            return VirtualServiceRuntimeStopResult.OnDestroyFailed(
                stopRequest = request,
                service = record.service,
                error = destroyResult.exceptionOrNull()
                    ?: IllegalStateException("onDestroy failed without throwable")
            )
        }

        recordManager.remove(request.instanceId, request.guestServiceClassName)
        return VirtualServiceRuntimeStopResult.Stopped(
            stopRequest = request,
            service = record.service
        )
    }

    fun clear() {
        recordManager.clear()
    }

    private fun startExisting(
        request: VirtualServiceRuntimeStartRequest,
        record: VirtualServiceRecord,
        cached: Boolean
    ): VirtualServiceRuntimeResult {
        val result = runCatching {
            record.service.onStartCommand(
                request.startRequest.sourceIntent,
                request.flags,
                request.startId
            )
        }
        if (result.isFailure) {
            return VirtualServiceRuntimeResult.OnStartCommandFailed(
                startRequest = request.startRequest,
                service = record.service,
                cached = cached,
                error = result.exceptionOrNull()
                    ?: IllegalStateException("onStartCommand failed without throwable")
            )
        }

        val startCommandResult = result.getOrThrow()
        val updated = recordManager.updateStart(
            instanceId = request.startRequest.instanceId,
            guestServiceClassName = request.startRequest.guestServiceClassName,
            startId = request.startId,
            lastStartCommandResult = startCommandResult
        ) ?: record

        return if (cached) {
            VirtualServiceRuntimeResult.StartedCached(
                startRequest = request.startRequest,
                service = updated.service,
                startCommandResult = startCommandResult
            )
        } else {
            VirtualServiceRuntimeResult.CreatedAndStarted(
                startRequest = request.startRequest,
                service = updated.service,
                startCommandResult = startCommandResult
            )
        }
    }

    companion object {
        val global: VirtualServiceRuntime = VirtualServiceRuntime()
    }
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

sealed class VirtualServiceRuntimeResult {
    abstract val startRequest: VirtualServiceStartRequest

    data class CreatedAndStarted(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val startCommandResult: Int
    ) : VirtualServiceRuntimeResult()

    data class StartedCached(
        override val startRequest: VirtualServiceStartRequest,
        val service: Service,
        val startCommandResult: Int
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

sealed class VirtualServiceRuntimeStopResult {
    abstract val stopRequest: VirtualServiceStopRequest

    data class Stopped(
        override val stopRequest: VirtualServiceStopRequest,
        val service: Service
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
        attach.invoke(service, context, activityThread, className, null, application, null)
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
