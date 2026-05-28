package com.multiapp.core.hook

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TimePrisonManager -- Per-instance time isolation for virtual apps.
 *
 * Each virtual app instance can have its own independent time flow,
 * preventing apps from detecting the virtual environment through time
 * discrepancy checks. Common detection vectors neutralized:
 *
 * - System.currentTimeMillis() vs network time (NTP) mismatch
 * - SystemClock.elapsedRealtime() / uptimeMillis() anomalies
 * - Date / Calendar returning host time
 * - Monotonic clock gaps between successive calls
 *
 * Time strategies:
 * - SYNC:       Real time passes through unmodified (default)
 * - OFFSET:     Fixed offset from real time (e.g. -30 days)
 * - FREEZE:     Time frozen at a specific timestamp
 * - ACCELERATED: Time passes faster than real (testing / speed-up)
 *
 * All time math is deterministic: the same instanceId always produces
 * the same spoofed timestamp for the same real timestamp.
 */
@Singleton
class TimePrisonManager @Inject constructor(
    private val hookEngine: HookEngine
) {
    companion object {
        private const val TAG = "TimePrison"
    }

    // ─── Per-instance state ────────────────────────────────────────

    /** Active time prison configurations keyed by instanceId. */
    private val configs = ConcurrentHashMap<String, TimePrisonConfig>()

    /**
     * Monotonic bookkeeping: maps instanceId to the wall-clock timestamp
     * (real) at which the prison was activated. Used by FREEZE mode to
     * anchor the frozen timestamp and by ACCELERATED mode to compute
     * the elapsed multiplier.
     */
    private val activatedAt = ConcurrentHashMap<String, Long>()

    /** Track whether time hooks have been installed (shared across instances). */
    @Volatile
    private var hooksInstalled = false

    // ─── Public API ────────────────────────────────────────────────

    /**
     * Configure a time prison for a virtual app instance.
     *
     * @param instanceId The virtual app instance ID
     * @param config     The time strategy and parameters
     */
    fun configureTimePrison(instanceId: String, config: TimePrisonConfig) {
        configs[instanceId] = config
        activatedAt[instanceId] = System.currentTimeMillis()

        Timber.tag(TAG).i(
            "Time prison configured for $instanceId: " +
                "mode=${config.mode}, offset=${config.offsetMs}ms, " +
                "frozenAt=${config.frozenTimestampMs}, " +
                "multiplier=${config.accelerationMultiplier}"
        )

        // Install LSPlant hooks on first config
        if (!hooksInstalled) {
            installTimeHooks()
        }
    }

    /**
     * Set a simple fixed offset for an instance.
     * Positive = future, Negative = past.
     */
    fun setTimeOffset(instanceId: String, offsetMs: Long) {
        val existing = configs[instanceId]
        val config = (existing ?: TimePrisonConfig()).copy(
            mode = TimePrisonMode.OFFSET,
            offsetMs = offsetMs
        )
        configureTimePrison(instanceId, config)
    }

    /**
     * Freeze time for an instance at a specific timestamp.
     * If [frozenTimestampMs] is 0, freezes at the current real time.
     */
    fun freezeTime(instanceId: String, frozenTimestampMs: Long = 0L) {
        val ts = if (frozenTimestampMs > 0) frozenTimestampMs else System.currentTimeMillis()
        val config = TimePrisonConfig(
            mode = TimePrisonMode.FREEZE,
            frozenTimestampMs = ts
        )
        configureTimePrison(instanceId, config)
    }

    /**
     * Set time acceleration multiplier for an instance.
     * 2.0 = time passes twice as fast as real time.
     */
    fun setAcceleration(instanceId: String, multiplier: Double) {
        val config = TimePrisonConfig(
            mode = TimePrisonMode.ACCELERATED,
            accelerationMultiplier = multiplier
        )
        configureTimePrison(instanceId, config)
    }

    /**
     * Get the spoofed wall-clock time for an instance.
     *
     * @param instanceId The virtual app instance ID
     * @param realTime   The real System.currentTimeMillis() value
     * @return The spoofed timestamp in milliseconds
     */
    fun getSpoofedTime(instanceId: String, realTime: Long): Long {
        val config = configs[instanceId] ?: return realTime

        return when (config.mode) {
            TimePrisonMode.SYNC -> realTime

            TimePrisonMode.OFFSET -> realTime + config.offsetMs

            TimePrisonMode.FREEZE -> config.frozenTimestampMs

            TimePrisonMode.ACCELERATED -> {
                val startReal = activatedAt[instanceId] ?: realTime
                val elapsedReal = realTime - startReal
                val elapsedVirtual = (elapsedReal * config.accelerationMultiplier).toLong()
                startReal + elapsedVirtual
            }
        }
    }

    /**
     * Get the spoofed elapsed realtime (uptime) for an instance.
     *
     * For SYNC/OFFSET modes this returns the real elapsed time.
     * For FREEZE mode the elapsed time is frozen.
     * For ACCELERATED mode the elapsed time is scaled.
     */
    fun getSpoofedElapsedRealtime(instanceId: String, realElapsed: Long): Long {
        val config = configs[instanceId] ?: return realElapsed

        return when (config.mode) {
            TimePrisonMode.SYNC -> realElapsed
            TimePrisonMode.OFFSET -> realElapsed + config.offsetMs
            TimePrisonMode.FREEZE -> {
                // Freeze the uptime at the moment the prison was activated
                val startElapsed = activatedAt[instanceId]?.let {
                    realElapsed - (System.currentTimeMillis() - it)
                } ?: realElapsed
                startElapsed
            }
            TimePrisonMode.ACCELERATED -> {
                (realElapsed * config.accelerationMultiplier).toLong()
            }
        }
    }

    /**
     * Get the current config for an instance.
     */
    fun getConfig(instanceId: String): TimePrisonConfig? = configs[instanceId]

    /**
     * Check if a time prison is active for an instance.
     */
    fun isActive(instanceId: String): Boolean = configs.containsKey(instanceId)

    /**
     * Remove the time prison for an instance.
     */
    fun removeTimePrison(instanceId: String) {
        configs.remove(instanceId)
        activatedAt.remove(instanceId)
        Timber.tag(TAG).d("Time prison removed for $instanceId")
    }

    /**
     * Remove all time prisons.
     */
    fun removeAll() {
        configs.clear()
        activatedAt.clear()
        Timber.tag(TAG).i("All time prisons removed")
    }

    /**
     * Get count of active time prisons.
     */
    fun getActiveCount(): Int = configs.size

    // ─── LSPlant ART method hooks ──────────────────────────────────

    /**
     * Install LSPlant hooks for all time-related APIs.
     * These hooks intercept at the ART runtime level, covering both
     * Java and JNI implementations.
     *
     * Hooks are global (apply to all instances). The hook callback
     * looks up the calling instance's config to compute the spoofed value.
     * When multiple instances are active, the first instance's config is used
     * (since ART hooks are per-method, not per-caller-context).
     */
    private fun installTimeHooks() {
        if (hooksInstalled) return

        // System.currentTimeMillis()
        tryHookStatic(
            "java.lang.System", "currentTimeMillis"
        ) { _, _, result ->
            if (result is Long) applySpoofedTime(result) else result
        }

        // SystemClock.elapsedRealtime()
        tryHookStatic(
            "android.os.SystemClock", "elapsedRealtime"
        ) { _, _, result ->
            if (result is Long) applySpoofedElapsed(result) else result
        }

        // SystemClock.uptimeMillis()
        tryHookStatic(
            "android.os.SystemClock", "uptimeMillis"
        ) { _, _, result ->
            if (result is Long) applySpoofedElapsed(result) else result
        }

        // SystemClock.elapsedRealtimeNanos()
        tryHookStatic(
            "android.os.SystemClock", "elapsedRealtimeNanos"
        ) { _, _, result ->
            if (result is Long) applySpoofedElapsed(result / 1_000_000L) * 1_000_000L else result
        }

        // Date.getTime() -- return spoofed time
        tryHookInstance(
            "java.util.Date", "getTime"
        ) { _, _, result ->
            if (result is Long) applySpoofedTime(result) else result
        }

        // Calendar.getTimeInMillis() -- return spoofed time
        tryHookInstance(
            "java.util.Calendar", "getTimeInMillis"
        ) { _, _, result ->
            if (result is Long) applySpoofedTime(result) else result
        }

        hooksInstalled = true
        Timber.tag(TAG).i("Time LSPlant hooks installed")
    }

    /**
     * Apply spoofed time across all active instances.
     * Uses the first active non-SYNC config.
     */
    private fun applySpoofedTime(realTime: Long): Long {
        for ((instanceId, config) in configs) {
            if (config.mode != TimePrisonMode.SYNC) {
                return getSpoofedTime(instanceId, realTime)
            }
        }
        return realTime
    }

    /**
     * Apply spoofed elapsed time across all active instances.
     * Uses the first active non-SYNC config.
     */
    private fun applySpoofedElapsed(realElapsed: Long): Long {
        for ((instanceId, config) in configs) {
            if (config.mode != TimePrisonMode.SYNC) {
                return getSpoofedElapsedRealtime(instanceId, realElapsed)
            }
        }
        return realElapsed
    }

    /**
     * Try to hook a static method via LSPlant.
     */
    private fun tryHookStatic(
        className: String,
        methodName: String,
        afterCallback: (receiver: Any?, args: Array<Any?>, result: Any?) -> Any?
    ) {
        try {
            val clazz = Class.forName(className)
            val method = clazz.getMethod(methodName)
            hookEngine.hookMethod(method, beforeCallback = null, afterCallback = afterCallback)
            Timber.tag(TAG).d("Hooked static: $className.$methodName")
        } catch (_: NoSuchMethodException) {
            // Method may not exist on this Android version
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to hook $className.$methodName: ${e.message}")
        }
    }

    /**
     * Try to hook an instance method via LSPlant.
     */
    private fun tryHookInstance(
        className: String,
        methodName: String,
        afterCallback: (receiver: Any?, args: Array<Any?>, result: Any?) -> Any?
    ) {
        try {
            val clazz = Class.forName(className)
            val method = clazz.getMethod(methodName)
            hookEngine.hookMethod(method, beforeCallback = null, afterCallback = afterCallback)
            Timber.tag(TAG).d("Hooked instance: $className.$methodName")
        } catch (_: NoSuchMethodException) {
            // Method may not exist on this Android version
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to hook $className.$methodName: ${e.message}")
        }
    }
}

/**
 * Time prison operating mode.
 */
enum class TimePrisonMode {
    /** Real time passes through unmodified. */
    SYNC,
    /** Fixed offset from real time. */
    OFFSET,
    /** Time frozen at a specific timestamp. */
    FREEZE,
    /** Time passes at an accelerated rate. */
    ACCELERATED;

    companion object {
        fun fromString(value: String): TimePrisonMode = when (value.uppercase()) {
            "SYNC" -> SYNC
            "OFFSET" -> OFFSET
            "FREEZE" -> FREEZE
            "ACCELERATED" -> ACCELERATED
            else -> SYNC
        }
    }
}

/**
 * Configuration for a per-instance time prison.
 *
 * @param mode                    The time strategy
 * @param offsetMs                Fixed offset in milliseconds (OFFSET mode)
 * @param frozenTimestampMs       Frozen timestamp in epoch ms (FREEZE mode)
 * @param accelerationMultiplier  Time speed multiplier (ACCELERATED mode, e.g. 2.0)
 * @param timezoneId              Optional timezone override (e.g. "Asia/Shanghai")
 * @param bootTimeOffsetMs        Offset applied to reported boot time
 */
data class TimePrisonConfig(
    val mode: TimePrisonMode = TimePrisonMode.SYNC,
    val offsetMs: Long = 0L,
    val frozenTimestampMs: Long = 0L,
    val accelerationMultiplier: Double = 1.0,
    val timezoneId: String = "",
    val bootTimeOffsetMs: Long = 0L
)
