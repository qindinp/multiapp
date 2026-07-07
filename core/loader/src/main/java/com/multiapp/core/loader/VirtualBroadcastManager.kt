package com.multiapp.core.loader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot

data class VirtualBroadcastDispatchRequest(
    val instanceId: String,
    val originPackageName: String,
    val receiverClassName: String,
    val sourceIntent: Intent,
    val action: String?,
    val reason: String
)

data class VirtualBroadcastRecord(
    val instanceId: String?,
    val receiverClassName: String?,
    val action: String?,
    val result: VirtualBroadcastResultCode
)

enum class VirtualBroadcastResultCode {
    Delivered,
    NoPackageSnapshot,
    UnsupportedImplicit,
    ReceiverNotFound,
    ReceiverClassNotFound,
    ReceiverCreateFailed,
    OnReceiveFailed
}

fun interface VirtualBroadcastRecorder {
    fun record(record: VirtualBroadcastRecord)
}

object VirtualBroadcastRecorders {
    private val noOp = VirtualBroadcastRecorder { }

    @Volatile
    private var delegate: VirtualBroadcastRecorder = noOp

    fun install(recorder: VirtualBroadcastRecorder) {
        delegate = recorder
    }

    fun reset() {
        delegate = noOp
    }

    internal fun current(): VirtualBroadcastRecorder = delegate
}

object GlobalVirtualBroadcastRecorder : VirtualBroadcastRecorder {
    override fun record(record: VirtualBroadcastRecord) {
        VirtualBroadcastRecorders.current().record(record)
    }
}

class InMemoryVirtualBroadcastRecorder : VirtualBroadcastRecorder {
    private val records = mutableListOf<VirtualBroadcastRecord>()

    override fun record(record: VirtualBroadcastRecord) {
        records += record
    }

    fun records(): List<VirtualBroadcastRecord> = records.toList()

    fun clear() {
        records.clear()
    }
}

sealed class VirtualBroadcastResult {
    abstract val record: VirtualBroadcastRecord

    data class Batch(
        val results: List<VirtualBroadcastResult>,
        override val record: VirtualBroadcastRecord = results.first().record
    ) : VirtualBroadcastResult() {
        init {
            require(results.isNotEmpty()) { "broadcast batch must not be empty" }
        }
    }

    data class Delivered(
        val request: VirtualBroadcastDispatchRequest,
        val receiver: android.content.BroadcastReceiver,
        override val record: VirtualBroadcastRecord
    ) : VirtualBroadcastResult()

    data class UnsupportedImplicit(
        val sourceIntent: Intent,
        override val record: VirtualBroadcastRecord
    ) : VirtualBroadcastResult()

    data class NoPackageSnapshot(
        val sourceIntent: Intent,
        override val record: VirtualBroadcastRecord
    ) : VirtualBroadcastResult()

    data class ReceiverNotFound(
        val sourceIntent: Intent,
        override val record: VirtualBroadcastRecord
    ) : VirtualBroadcastResult()

    data class ReceiverClassNotFound(
        val request: VirtualBroadcastDispatchRequest,
        val error: Throwable,
        override val record: VirtualBroadcastRecord
    ) : VirtualBroadcastResult()

    data class ReceiverCreateFailed(
        val request: VirtualBroadcastDispatchRequest,
        val error: Throwable,
        override val record: VirtualBroadcastRecord
    ) : VirtualBroadcastResult()

    data class OnReceiveFailed(
        val request: VirtualBroadcastDispatchRequest,
        val receiver: android.content.BroadcastReceiver,
        val error: Throwable,
        override val record: VirtualBroadcastRecord
    ) : VirtualBroadcastResult()
}

/** In-process router for guest BroadcastReceiver intents. */
class VirtualBroadcastManager(
    private val runtime: VirtualReceiverRuntime = VirtualReceiverRuntime.global,
    private val recorder: VirtualBroadcastRecorder = GlobalVirtualBroadcastRecorder,
    private val dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry.global
) {
    fun createDispatchRequest(
        snapshot: VirtualPackageSnapshot,
        intent: Intent
    ): VirtualBroadcastDispatchRequest? {
        val component = intent.component ?: return null
        return createExplicitDispatchRequest(snapshot, component, intent)
    }

    fun dispatchExplicit(
        snapshot: VirtualPackageSnapshot,
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader
    ): VirtualBroadcastResult {
        val component = intent.component ?: return unsupportedImplicit(intent)
        val request = createExplicitDispatchRequest(snapshot, component, intent)
            ?: return receiverNotFound(snapshot, intent)

        return runtime.dispatch(
            VirtualReceiverRuntimeRequest(
                dispatchRequest = request,
                virtualContext = virtualContext,
                receiverClassLoader = receiverClassLoader
            )
        )
    }

    fun dispatch(
        instanceId: String,
        snapshot: VirtualPackageSnapshot?,
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader
    ): VirtualBroadcastResult {
        val dynamicResult = dispatchDynamic(instanceId, intent, virtualContext)
        if (dynamicResult != null && intent.component != null) return dynamicResult
        if (snapshot == null) return dynamicResult ?: noPackageSnapshot(instanceId, intent)
        if (intent.component == null) {
            val implicitResult = dispatchImplicit(snapshot, intent, virtualContext, receiverClassLoader)
            return combineResults(dynamicResult, implicitResult) ?: unsupportedImplicit(intent)
        }
        return dispatchExplicit(snapshot, intent, virtualContext, receiverClassLoader)
    }

    fun dispatchDynamic(
        instanceId: String,
        intent: Intent,
        virtualContext: Context
    ): VirtualBroadcastResult? {
        val records = dynamicReceiverRegistry.query(instanceId, intent)
        if (records.isEmpty()) return null
        val results = records.map { record ->
            dispatchDynamicRecord(record, intent, virtualContext)
        }
        return singleOrBatch(results)
    }

    private fun dispatchDynamicRecord(
        record: VirtualDynamicReceiverRecord,
        intent: Intent,
        virtualContext: Context
    ): VirtualBroadcastResult {
        val request = VirtualBroadcastDispatchRequest(
            instanceId = record.instanceId,
            originPackageName = virtualContext.packageName,
            receiverClassName = record.receiver.javaClass.name,
            sourceIntent = intent,
            action = intent.action,
            reason = "dynamic"
        )
        val receiveResult = runCatching { record.receiver.onReceive(virtualContext, intent) }
        if (receiveResult.isFailure) {
            val failureRecord = record(request, VirtualBroadcastResultCode.OnReceiveFailed)
            return VirtualBroadcastResult.OnReceiveFailed(
                request = request,
                receiver = record.receiver,
                error = receiveResult.exceptionOrNull()
                    ?: IllegalStateException("dynamic receiver failed without throwable"),
                record = failureRecord
            )
        }
        val deliveredRecord = record(request, VirtualBroadcastResultCode.Delivered)
        return VirtualBroadcastResult.Delivered(
            request = request,
            receiver = record.receiver,
            record = deliveredRecord
        )
    }

    private fun dispatchImplicit(
        snapshot: VirtualPackageSnapshot,
        intent: Intent,
        virtualContext: Context,
        receiverClassLoader: ClassLoader
    ): VirtualBroadcastResult? {
        val requests = createImplicitDispatchRequests(snapshot, intent)
        if (requests.isEmpty()) return null
        val results = requests.map { request ->
            runtime.dispatch(
                VirtualReceiverRuntimeRequest(
                    dispatchRequest = request,
                    virtualContext = virtualContext,
                    receiverClassLoader = receiverClassLoader
                )
            )
        }
        return singleOrBatch(results)
    }

    internal fun createImplicitDispatchRequests(
        snapshot: VirtualPackageSnapshot,
        sourceIntent: Intent
    ): List<VirtualBroadcastDispatchRequest> {
        val packageName = runCatching { sourceIntent.`package` }.getOrNull()
        if (packageName != null && !snapshot.matchesPackageName(packageName)) return emptyList()
        return snapshot.receivers
            .filter { receiver -> receiver.matchesImplicitBroadcast(sourceIntent) }
            .map { receiver ->
                VirtualBroadcastDispatchRequest(
                    instanceId = snapshot.instanceId,
                    originPackageName = snapshot.originPackageName,
                    receiverClassName = receiver.name,
                    sourceIntent = sourceIntent,
                    action = sourceIntent.action,
                    reason = "implicit"
                )
            }
    }

    internal fun createExplicitDispatchRequest(
        snapshot: VirtualPackageSnapshot,
        component: ComponentName,
        sourceIntent: Intent
    ): VirtualBroadcastDispatchRequest? {
        if (!snapshot.matchesPackageName(component.packageName)) return null
        val normalizedClassName = normalizeReceiverClassName(snapshot.originPackageName, component.className)
        val receiver: ResolvedComponent = snapshot.receivers.firstOrNull { it.name == normalizedClassName }
            ?: return null
        return VirtualBroadcastDispatchRequest(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            receiverClassName = receiver.name,
            sourceIntent = sourceIntent,
            action = sourceIntent.action,
            reason = "explicit"
        )
    }

    private fun unsupportedImplicit(intent: Intent): VirtualBroadcastResult.UnsupportedImplicit {
        val record = VirtualBroadcastRecord(
            instanceId = null,
            receiverClassName = null,
            action = intent.action,
            result = VirtualBroadcastResultCode.UnsupportedImplicit
        )
        recorder.record(record)
        return VirtualBroadcastResult.UnsupportedImplicit(intent, record)
    }

    private fun receiverNotFound(
        snapshot: VirtualPackageSnapshot,
        intent: Intent
    ): VirtualBroadcastResult.ReceiverNotFound {
        val record = VirtualBroadcastRecord(
            instanceId = snapshot.instanceId,
            receiverClassName = intent.component?.className,
            action = intent.action,
            result = VirtualBroadcastResultCode.ReceiverNotFound
        )
        recorder.record(record)
        return VirtualBroadcastResult.ReceiverNotFound(intent, record)
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

    private fun noPackageSnapshot(
        instanceId: String,
        intent: Intent
    ): VirtualBroadcastResult.NoPackageSnapshot {
        val record = VirtualBroadcastRecord(
            instanceId = instanceId,
            receiverClassName = intent.component?.className,
            action = intent.action,
            result = VirtualBroadcastResultCode.NoPackageSnapshot
        )
        recorder.record(record)
        return VirtualBroadcastResult.NoPackageSnapshot(
            sourceIntent = intent,
            record = record
        )
    }

    companion object {
        fun noPackageSnapshot(intent: Intent): VirtualBroadcastResult.NoPackageSnapshot {
            return VirtualBroadcastResult.NoPackageSnapshot(
                sourceIntent = intent,
                record = VirtualBroadcastRecord(
                    instanceId = null,
                    receiverClassName = intent.component?.className,
                    action = intent.action,
                    result = VirtualBroadcastResultCode.NoPackageSnapshot
                )
            )
        }
    }

    private fun normalizeReceiverClassName(packageName: String, className: String): String = when {
        className.startsWith(".") -> packageName + className
        '.' !in className -> "$packageName.$className"
        else -> className
    }

    private fun singleOrBatch(results: List<VirtualBroadcastResult>): VirtualBroadcastResult =
        if (results.size == 1) results.single() else VirtualBroadcastResult.Batch(results)

    private fun combineResults(
        first: VirtualBroadcastResult?,
        second: VirtualBroadcastResult?
    ): VirtualBroadcastResult? {
        val results = buildList {
            first?.flattenInto(this)
            second?.flattenInto(this)
        }
        return when (results.size) {
            0 -> null
            1 -> results.single()
            else -> VirtualBroadcastResult.Batch(results)
        }
    }

    private fun VirtualBroadcastResult.flattenInto(results: MutableList<VirtualBroadcastResult>) {
        if (this is VirtualBroadcastResult.Batch) {
            results.addAll(this.results)
        } else {
            results += this
        }
    }

    private fun ResolvedComponent.matchesImplicitBroadcast(intent: Intent): Boolean {
        val action = runCatching { intent.action }.getOrNull()
        val categories = runCatching { intent.categories.orEmpty() }.getOrDefault(emptySet())
        val scheme = runCatching { intent.data?.scheme }.getOrNull()

        if (resolvedIntentFilters.isNotEmpty()) {
            return resolvedIntentFilters.any { filter ->
                val actionMatches = filter.actions.isNotEmpty() && action in filter.actions
                val categoriesMatch = filter.categories.containsAll(categories)
                val schemeMatches = filter.dataSchemes.isEmpty() || scheme in filter.dataSchemes
                actionMatches && categoriesMatch && schemeMatches
            }
        }

        return intentFilters.isNotEmpty() && action in intentFilters
    }
}
