package com.multiapp.core.hook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime Inspector — Real-time monitoring of virtual app behavior.
 *
 * Intercepts and records:
 * - Method calls via LSPlant ART hooks (before/after callbacks)
 * - System call patterns via /proc tracking
 * - Binder transaction logging via proxy integration
 * - Network connection tracking (DNS, TCP, HTTP)
 *
 * Uses per-instance ring buffers to avoid unbounded memory growth.
 * Provides a StateFlow for UI observation and query APIs for filtering.
 *
 * Unlike AppXRayMonitor which logs high-level events, RuntimeInspector
 * operates at the method-hook level for deeper visibility.
 */
@Singleton
class RuntimeInspector @Inject constructor(
    private val hookEngine: HookEngine
) {

    companion object {
        private const val TAG = "RuntimeInspector"
        private const val MAX_ENTRIES_PER_INSTANCE = 2000
        private const val MAX_TRACED_METHODS = 200
    }

    // Per-instance trace buffers
    private val buffers = ConcurrentHashMap<String, TraceRingBuffer>()

    // Method hooks: key = "className.methodName", value = hook state
    private val hookedMethods = ConcurrentHashMap<String, MethodHookState>()

    // Observable stats for UI
    private val _stats = MutableStateFlow<Map<String, TraceStats>>(emptyMap())
    val stats: StateFlow<Map<String, TraceStats>> = _stats.asStateFlow()

    // Active tracing state per instance
    private val activeInstances = ConcurrentHashMap.newKeySet<String>()

    // Thread-local for method timing
    private val methodStartTime = ThreadLocal<Long>()

    /**
     * Start runtime inspection for a virtual app instance.
     *
     * @param instanceId The virtual app instance to inspect
     */
    fun startInspection(instanceId: String) {
        activeInstances.add(instanceId)
        buffers.getOrPut(instanceId) { TraceRingBuffer(MAX_ENTRIES_PER_INSTANCE) }
        Timber.tag(TAG).i("Inspection started for instance: $instanceId")
    }

    /**
     * Stop runtime inspection for an instance.
     */
    fun stopInspection(instanceId: String) {
        activeInstances.remove(instanceId)
        Timber.tag(TAG).i("Inspection stopped for instance: $instanceId")
    }

    /**
     * Check if inspection is active for an instance.
     */
    fun isActive(instanceId: String): Boolean = activeInstances.contains(instanceId)

    /**
     * Hook a specific method for tracing via LSPlant.
     *
     * @param className Fully qualified class name
     * @param methodName Method name to hook
     * @param paramTypes Parameter types for overload resolution
     * @return true if hook was installed successfully
     */
    fun traceMethod(
        className: String,
        methodName: String,
        vararg paramTypes: Class<*>
    ): Boolean {
        val key = "$className.$methodName"
        if (hookedMethods.size >= MAX_TRACED_METHODS) {
            Timber.tag(TAG).w("Max traced methods reached ($MAX_TRACED_METHODS)")
            return false
        }
        if (hookedMethods.containsKey(key)) return true

        return try {
            val clazz = Class.forName(className)
            val method = if (paramTypes.isEmpty()) {
                clazz.getDeclaredMethod(methodName)
            } else {
                clazz.getDeclaredMethod(methodName, *paramTypes)
            }
            method.isAccessible = true

            val success = hookEngine.hookMethod(
                method = method,
                beforeCallback = { receiver, args ->
                    onMethodBefore(key, receiver, args)
                    null
                },
                afterCallback = { receiver, args, result ->
                    onMethodAfter(key, receiver, args, result)
                    null
                }
            )

            if (success) {
                hookedMethods[key] = MethodHookState(className, methodName, System.currentTimeMillis())
                Timber.tag(TAG).d("Tracing: $key")
            }
            success
        } catch (e: ClassNotFoundException) {
            Timber.tag(TAG).w("Class not found: $className")
            false
        } catch (e: NoSuchMethodException) {
            Timber.tag(TAG).w("Method not found: $className.$methodName")
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to trace: $key")
            false
        }
    }

    /**
     * Stop tracing a specific method.
     */
    fun untraceMethod(className: String, methodName: String) {
        val key = "$className.$methodName"
        hookedMethods.remove(key)
        Timber.tag(TAG).d("Untraced: $key")
    }

    /**
     * Log a network connection event.
     */
    fun logNetworkConnection(
        instanceId: String,
        host: String,
        port: Int,
        protocol: String,
        success: Boolean
    ) {
        appendEntry(instanceId, TraceEntry(
            timestamp = System.currentTimeMillis(),
            type = TraceType.NETWORK,
            detail = "$protocol://$host:$port ${if (success) "OK" else "FAILED"}",
            className = "",
            methodName = "",
            durationNs = 0
        ))
    }

    /**
     * Log a Binder transaction.
     */
    fun logBinderTransaction(
        instanceId: String,
        interfaceName: String,
        method: String,
        durationNs: Long
    ) {
        appendEntry(instanceId, TraceEntry(
            timestamp = System.currentTimeMillis(),
            type = TraceType.BINDER,
            detail = "$interfaceName.$method",
            className = interfaceName,
            methodName = method,
            durationNs = durationNs
        ))
    }

    /**
     * Log a system call pattern.
     */
    fun logSyscall(instanceId: String, syscall: String, args: String) {
        appendEntry(instanceId, TraceEntry(
            timestamp = System.currentTimeMillis(),
            type = TraceType.SYSCALL,
            detail = "$syscall($args)",
            className = "",
            methodName = syscall,
            durationNs = 0
        ))
    }

    /**
     * Get trace entries for an instance, optionally filtered by type.
     */
    fun getEntries(
        instanceId: String,
        type: TraceType? = null,
        limit: Int = 100
    ): List<TraceEntry> {
        val buffer = buffers[instanceId] ?: return emptyList()
        val snapshot = buffer.snapshot()
        val filtered = if (type != null) snapshot.filter { it.type == type } else snapshot
        return filtered.take(limit)
    }

    /**
     * Get current trace stats for all active instances.
     */
    fun getStats(): Map<String, TraceStats> {
        return buffers.mapValues { (_, buffer) ->
            val snapshot = buffer.snapshot()
            TraceStats(
                methodCallCount = snapshot.count { it.type == TraceType.METHOD },
                networkCount = snapshot.count { it.type == TraceType.NETWORK },
                binderCount = snapshot.count { it.type == TraceType.BINDER },
                syscallCount = snapshot.count { it.type == TraceType.SYSCALL },
                totalCount = snapshot.size,
                tracedMethodCount = hookedMethods.size
            )
        }
    }

    /**
     * Get list of currently traced methods.
     */
    fun getTracedMethods(): List<MethodHookState> = hookedMethods.values.toList()

    /**
     * Clear all entries for an instance.
     */
    fun clearEntries(instanceId: String) {
        buffers.remove(instanceId)
        updateStats()
    }

    /**
     * Clear all data and remove all hooks.
     */
    fun clearAll() {
        buffers.clear()
        hookedMethods.clear()
        activeInstances.clear()
        _stats.value = emptyMap()
        Timber.tag(TAG).d("All inspection data cleared")
    }

    // ─── Internal ───────────────────────────────────────────────────

    private fun onMethodBefore(key: String, receiver: Any?, args: Array<Any?>) {
        methodStartTime.set(System.nanoTime())
    }

    private fun onMethodAfter(key: String, receiver: Any?, args: Array<Any?>, result: Any?) {
        val startTime = methodStartTime.get()
        val durationNs = if (startTime > 0) System.nanoTime() - startTime else 0L
        methodStartTime.remove()

        val hookState = hookedMethods[key] ?: return
        val instanceId = activeInstances.firstOrNull() ?: return

        appendEntry(instanceId, TraceEntry(
            timestamp = System.currentTimeMillis(),
            type = TraceType.METHOD,
            detail = buildString {
                append(key)
                if (args.isNotEmpty()) {
                    append("(")
                    append(args.take(3).joinToString(", ") { summarizeValue(it) })
                    if (args.size > 3) append(", ...")
                    append(")")
                }
                if (result != null) {
                    append(" -> ")
                    append(summarizeValue(result))
                }
            },
            className = hookState.className,
            methodName = hookState.methodName,
            durationNs = durationNs
        ))
    }

    private fun appendEntry(instanceId: String, entry: TraceEntry) {
        val buffer = buffers.getOrPut(instanceId) { TraceRingBuffer(MAX_ENTRIES_PER_INSTANCE) }
        buffer.add(entry)
        if (buffer.size() % 20 == 0) updateStats()
    }

    private fun updateStats() {
        _stats.value = getStats()
    }

    private fun summarizeValue(value: Any?): String {
        if (value == null) return "null"
        val str = value.toString()
        return if (str.length > 80) str.take(80) + "..." else str
    }
}

// ─── Data classes ───────────────────────────────────────────────────

enum class TraceType {
    METHOD,
    NETWORK,
    BINDER,
    SYSCALL
}

data class TraceEntry(
    val timestamp: Long,
    val type: TraceType,
    val detail: String,
    val className: String,
    val methodName: String,
    val durationNs: Long
) {
    val durationMs: Double get() = durationNs / 1_000_000.0
}

data class MethodHookState(
    val className: String,
    val methodName: String,
    val hookedAt: Long
)

data class TraceStats(
    val methodCallCount: Int = 0,
    val networkCount: Int = 0,
    val binderCount: Int = 0,
    val syscallCount: Int = 0,
    val totalCount: Int = 0,
    val tracedMethodCount: Int = 0
)

// ─── Ring buffer ────────────────────────────────────────────────────

internal class TraceRingBuffer(private val capacity: Int) {
    private val buffer = arrayOfNulls<TraceEntry>(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    fun add(entry: TraceEntry) {
        synchronized(lock) {
            buffer[head] = entry
            head = (head + 1) % capacity
            if (count < capacity) count++
        }
    }

    fun size(): Int = synchronized(lock) { count }

    fun snapshot(): List<TraceEntry> {
        synchronized(lock) {
            if (count == 0) return emptyList()
            val result = mutableListOf<TraceEntry>()
            var pos = (head - 1 + capacity) % capacity
            for (i in 0 until count) {
                buffer[pos]?.let { result.add(it) }
                pos = (pos - 1 + capacity) % capacity
            }
            return result
        }
    }
}
