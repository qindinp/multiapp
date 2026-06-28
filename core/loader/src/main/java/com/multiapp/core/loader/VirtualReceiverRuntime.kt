package com.multiapp.core.loader

import android.content.BroadcastReceiver
import android.content.Context

data class VirtualReceiverRuntimeRequest(
    val dispatchRequest: VirtualBroadcastDispatchRequest,
    val virtualContext: Context,
    val receiverClassLoader: ClassLoader
)

/** Instantiates and invokes explicit in-process guest BroadcastReceivers. */
class VirtualReceiverRuntime(
    private val receiverFactory: ReceiverFactory = ReflectionReceiverFactory,
    private val recorder: VirtualBroadcastRecorder = GlobalVirtualBroadcastRecorder
) {
    fun dispatch(request: VirtualReceiverRuntimeRequest): VirtualBroadcastResult {
        val receiver = try {
            receiverFactory.create(
                classLoader = request.receiverClassLoader,
                className = request.dispatchRequest.receiverClassName
            )
        } catch (error: ClassNotFoundException) {
            return classNotFound(request.dispatchRequest, error)
        } catch (error: NoClassDefFoundError) {
            return classNotFound(request.dispatchRequest, error)
        } catch (error: Throwable) {
            return createFailed(request.dispatchRequest, error)
        }

        val receiveResult = runCatching {
            receiver.onReceive(request.virtualContext, request.dispatchRequest.sourceIntent)
        }
        if (receiveResult.isFailure) {
            val error = receiveResult.exceptionOrNull()
                ?: IllegalStateException("onReceive failed without throwable")
            val record = record(request.dispatchRequest, VirtualBroadcastResultCode.OnReceiveFailed)
            return VirtualBroadcastResult.OnReceiveFailed(
                request = request.dispatchRequest,
                receiver = receiver,
                error = error,
                record = record
            )
        }

        val record = record(request.dispatchRequest, VirtualBroadcastResultCode.Delivered)
        return VirtualBroadcastResult.Delivered(
            request = request.dispatchRequest,
            receiver = receiver,
            record = record
        )
    }

    private fun classNotFound(
        request: VirtualBroadcastDispatchRequest,
        error: Throwable
    ): VirtualBroadcastResult.ReceiverClassNotFound {
        val record = record(request, VirtualBroadcastResultCode.ReceiverClassNotFound)
        return VirtualBroadcastResult.ReceiverClassNotFound(request, error, record)
    }

    private fun createFailed(
        request: VirtualBroadcastDispatchRequest,
        error: Throwable
    ): VirtualBroadcastResult.ReceiverCreateFailed {
        val record = record(request, VirtualBroadcastResultCode.ReceiverCreateFailed)
        return VirtualBroadcastResult.ReceiverCreateFailed(request, error, record)
    }

    private fun record(
        request: VirtualBroadcastDispatchRequest,
        result: VirtualBroadcastResultCode
    ): VirtualBroadcastRecord {
        val record = VirtualBroadcastRecord(
            instanceId = request.instanceId,
            receiverClassName = request.receiverClassName,
            action = request.action,
            result = result
        )
        recorder.record(record)
        return record
    }

    companion object {
        val global: VirtualReceiverRuntime = VirtualReceiverRuntime()
    }
}

fun interface ReceiverFactory {
    fun create(classLoader: ClassLoader, className: String): BroadcastReceiver
}

object ReflectionReceiverFactory : ReceiverFactory {
    override fun create(classLoader: ClassLoader, className: String): BroadcastReceiver {
        val clazz = classLoader.loadClass(className)
        return clazz.getDeclaredConstructor().newInstance() as BroadcastReceiver
    }
}
