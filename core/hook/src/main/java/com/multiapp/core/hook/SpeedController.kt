package com.multiapp.core.hook

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SpeedController -- Per-instance speed control for virtual apps.
 *
 * Controls the flow of time within each virtual app instance by hooking
 * Android's SystemClock APIs. This enables:
 *
 * - **Slow motion** (0.5x, 0.25x): Analyze app behavior, frame-by-frame debugging
 * - **Normal** (1.0x): Default real-time flow
 * - **Fast forward** (2x, 4x): Game acceleration, skip wait timers
 *
 * Unlike TimePrisonManager which manipulates wall-clock timestamps,
 * SpeedController scales the *rate* at which time passes. The app perceives
 * time flowing faster or slower, but wall-clock anchors remain consistent.
 *
 * How it works:
 * - Hooks SystemClock methods (elapsedRealtime, uptimeMillis, currentTimeMillis)
 * - Applies a per-instance speed multiplier to elapsed time deltas
 * - Each instance maintains its own time accumulator so independent instances
 *   can run at different speeds simultaneously
 *
 * Thread safety: ConcurrentHashMap for per-instance state.
 * Hook installation is lazy (first configure call).
 */
@Singleton
class SpeedController @Inject constructor(
    private val hookEngine: HookEngine
) {

    companion object {
        private const val TAG = "SpeedCtrl"
    }

    // Lock for synchronized operations on speed configs
    private val lock = Any()

    // ─── Per-instance state ────────────────────────────────────────

    /** Active speed configurations keyed by instanceId. */
    private val configs = ConcurrentHashMap<String, SpeedConfig>()

    /**
     * Real-time anchor per instance: maps instanceId to the real
     * System.currentTimeMillis() at which the speed config was activated.
     * Used to compute virtual elapsed time from real elapsed time.
     */
    private val anchorRealTime = ConcurrentHashMap<String, Long>()

    /**
     * Virtual elapsed time accumulator per instance.
     * Tracks how much virtual time has passed so that switching speed
     * mid-session doesn't cause a time jump.
     */
    private val virtualElapsedAccumulator = ConcurrentHashMap<String, Long>()

    /** Track whether speed hooks have been installed (shared across instances). */
    @Volatile
    private var hooksInstalled = false

    // ─── Preset speed constants ────────────────────────────────────

    /** Predefined speed levels for convenience. */
    enum class SpeedLevel(val multiplier: Double, val label: String) {
        SLOW_0_25X(0.25, "0.25x Slow"),
        SLOW_0_5X(0.5, "0.5x Slow"),
        NORMAL(1.0, "1x Normal"),
        FAST_2X(2.0, "2x Fast"),
        FAST_4X(4.0, "4x Fast"),
        FAST_8X(8.0, "8x Fast"),
        FAST_16X(16.0, "16x Fast");
    }

    // ─── Public API ────────────────────────────────────────────────

    /**
     * Set a predefined speed level for an instance.
     *
     * @param instanceId The virtual app instance ID
     * @param level The speed level preset
     */
    fun setSpeed(instanceId: String, level: SpeedLevel) {
        setSpeedMultiplier(instanceId, level.multiplier)
    }

    /**
     * Set a custom speed multiplier for an instance.
     *
     * @param instanceId The virtual app instance ID
     * @param multiplier Speed multiplier: <1.0 = slower, >1.0 = faster, 1.0 = normal
     * @throws IllegalArgumentException if multiplier is not positive
     */
    fun setSpeedMultiplier(instanceId: String, multiplier: Double) {
        require(multiplier > 0.0) { "Speed multiplier must be positive, got $multiplier" }

        synchronized(lock) {
            val now = System.currentTimeMillis()
            val oldConfig = configs[instanceId]

            // If changing speed mid-session, snapshot the accumulated virtual elapsed
            if (oldConfig != null && oldConfig.multiplier != multiplier) {
                val oldAnchor = anchorRealTime[instanceId] ?: now
                val realElapsed = now - oldAnchor
                val oldVirtualElapsed = (realElapsed * oldConfig.multiplier).toLong()
                val currentAccumulated = virtualElapsedAccumulator[instanceId] ?: 0L
                virtualElapsedAccumulator[instanceId] = currentAccumulated + oldVirtualElapsed
            }

            val config = SpeedConfig(multiplier = multiplier)
            configs[instanceId] = config
            anchorRealTime[instanceId] = now

            if (oldConfig == null) {
                virtualElapsedAccumulator.putIfAbsent(instanceId, 0L)
            }

            Timber.tag(TAG).i(
                "Speed configured for $instanceId: " +
                    "multiplier=${multiplier}x (${config.levelLabel})"
            )

            // Install hooks on first config
            if (!hooksInstalled) {
                installSpeedHooks()
            }
        }
    }

    /**
     * Get the current speed multiplier for an instance.
     * Returns 1.0 if no speed config exists (normal speed).
     */
    fun getSpeedMultiplier(instanceId: String): Double {
        return configs[instanceId]?.multiplier ?: 1.0
    }

    /**
     * Get the current speed config for an instance.
     */
    fun getConfig(instanceId: String): SpeedConfig? = configs[instanceId]

    /**
     * Check if speed control is active for an instance (non-normal speed).
     */
    fun isActive(instanceId: String): Boolean {
        val config = configs[instanceId] ?: return false
        return config.multiplier != 1.0
    }

    /**
     * Reset an instance to normal speed (1.0x).
     */
    fun resetToNormal(instanceId: String) {
        removeSpeedConfig(instanceId)
    }

    /**
     * Remove the speed configuration for an instance.
     */
    fun removeSpeedConfig(instanceId: String) {
        configs.remove(instanceId)
        anchorRealTime.remove(instanceId)
        virtualElapsedAccumulator.remove(instanceId)
        Timber.tag(TAG).d("Speed config removed for $instanceId")
    }

    /**
     * Remove all speed configurations.
     */
    fun removeAll() {
        configs.clear()
        anchorRealTime.clear()
        virtualElapsedAccumulator.clear()
        Timber.tag(TAG).i("All speed configs removed")
    }

    /**
     * Get count of active (non-normal) speed configurations.
     */
    fun getActiveCount(): Int = configs.count { it.value.multiplier != 1.0 }

    /**
     * Get all active speed configurations.
     */
    fun getAllConfigs(): Map<String, SpeedConfig> = configs.toMap()

    // ─── Time transformation (called from hook callbacks) ─────────

    /**
     * Transform a real elapsed time value to virtual elapsed time
     * for the first active non-normal-speed instance.
     *
     * @param realElapsed The real elapsed time from SystemClock
     * @return The scaled elapsed time
     */
    fun transformElapsed(realElapsed: Long): Long {
        for ((instanceId, config) in configs) {
            if (config.multiplier != 1.0) {
                return transformElapsedForInstance(instanceId, realElapsed)
            }
        }
        return realElapsed
    }

    /**
     * Transform a real elapsed time value for a specific instance.
     */
    fun transformElapsedForInstance(instanceId: String, realElapsed: Long): Long {
        val config = configs[instanceId] ?: return realElapsed
        if (config.multiplier == 1.0) return realElapsed

        val anchor = anchorRealTime[instanceId] ?: return realElapsed
        val accumulated = virtualElapsedAccumulator[instanceId] ?: 0L

        // Compute real elapsed since anchor
        val realElapsedSinceAnchor = realElapsed - anchor
        if (realElapsedSinceAnchor < 0) return realElapsed

        // Scale by multiplier and add accumulated from previous speed segments
        val virtualElapsedSinceAnchor = (realElapsedSinceAnchor * config.multiplier).toLong()
        return anchor + accumulated + virtualElapsedSinceAnchor
    }

    /**
     * Transform a wall-clock timestamp.
     * For speed control, we scale the delta from anchor time.
     */
    fun transformWallClock(realTime: Long): Long {
        for ((instanceId, config) in configs) {
            if (config.multiplier != 1.0) {
                return transformWallClockForInstance(instanceId, realTime)
            }
        }
        return realTime
    }

    /**
     * Transform a wall-clock timestamp for a specific instance.
     */
    fun transformWallClockForInstance(instanceId: String, realTime: Long): Long {
        val config = configs[instanceId] ?: return realTime
        if (config.multiplier == 1.0) return realTime

        val anchor = anchorRealTime[instanceId] ?: return realTime
        val accumulated = virtualElapsedAccumulator[instanceId] ?: 0L

        val delta = realTime - anchor
        if (delta < 0) return realTime

        val scaledDelta = (delta * config.multiplier).toLong()
        return anchor + accumulated + scaledDelta
    }

    // ─── LSPlant ART method hooks ──────────────────────────────────

    /**
     * Install LSPlant hooks for time-related APIs.
     * These hooks intercept at the ART runtime level.
     */
    private fun installSpeedHooks() {
        if (hooksInstalled) return

        // SystemClock.elapsedRealtime()
        tryHookStatic(
            "android.os.SystemClock", "elapsedRealtime"
        ) { _, _, result ->
            if (result is Long) transformElapsed(result) else result
        }

        // SystemClock.uptimeMillis()
        tryHookStatic(
            "android.os.SystemClock", "uptimeMillis"
        ) { _, _, result ->
            if (result is Long) transformElapsed(result) else result
        }

        // SystemClock.elapsedRealtimeNanos()
        tryHookStatic(
            "android.os.SystemClock", "elapsedRealtimeNanos"
        ) { _, _, result ->
            if (result is Long) transformElapsed(result / 1_000_000L) * 1_000_000L else result
        }

        // System.currentTimeMillis() -- scale deltas from anchor
        tryHookStatic(
            "java.lang.System", "currentTimeMillis"
        ) { _, _, result ->
            if (result is Long) transformWallClock(result) else result
        }

        // Thread.sleep() -- scale sleep duration
        tryHookStatic(
            "java.lang.Thread", "sleep",
            beforeCallback = { _, args ->
                // Scale the sleep duration by inverse multiplier
                // 2x speed = sleep half as long; 0.5x = sleep twice as long
                if (args.isNotEmpty() && args[0] is Long) {
                    val originalDuration = args[0] as Long
                    val scaledDuration = scaleInverse(originalDuration)
                    arrayOf<Any?>(scaledDuration)
                } else {
                    args
                }
            }
        )

        hooksInstalled = true
        Timber.tag(TAG).i("Speed LSPlant hooks installed")
    }

    /**
     * Scale a duration inversely by the current speed multiplier.
     * Used for sleep/wait: at 2x speed, sleep(1000) becomes sleep(500).
     */
    private fun scaleInverse(durationMs: Long): Long {
        for ((_, config) in configs) {
            if (config.multiplier != 1.0) {
                return (durationMs / config.multiplier).toLong().coerceAtLeast(1L)
            }
        }
        return durationMs
    }

    /**
     * Try to hook a static method via LSPlant.
     */
    private fun tryHookStatic(
        className: String,
        methodName: String,
        beforeCallback: ((receiver: Any?, args: Array<Any?>) -> Array<Any?>?)? = null,
        afterCallback: ((receiver: Any?, args: Array<Any?>, result: Any?) -> Any?)? = null
    ) {
        try {
            val clazz = Class.forName(className)
            val method = if (methodName == "sleep") {
                clazz.getMethod(methodName, Long::class.javaPrimitiveType)
            } else {
                clazz.getMethod(methodName)
            }
            hookEngine.hookMethod(
                method,
                beforeCallback = beforeCallback,
                afterCallback = afterCallback
            )
            Timber.tag(TAG).d("Hooked: $className.$methodName")
        } catch (_: NoSuchMethodException) {
            // Method may not exist on this Android version
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to hook $className.$methodName: ${e.message}")
        }
    }
}

/**
 * Speed control configuration for a per-instance speed setting.
 *
 * @param multiplier Speed multiplier: <1.0 = slower, >1.0 = faster, 1.0 = normal
 */
data class SpeedConfig(
    val multiplier: Double = 1.0
) {
    /** Human-readable label for the current speed. */
    val levelLabel: String
        get() = when {
            multiplier <= 0.25 -> "0.25x Slow"
            multiplier <= 0.5 -> "0.5x Slow"
            multiplier == 1.0 -> "1x Normal"
            multiplier <= 2.0 -> "2x Fast"
            multiplier <= 4.0 -> "4x Fast"
            multiplier <= 8.0 -> "8x Fast"
            else -> "${multiplier}x Fast"
        }

    /** Whether this is a slow-motion configuration. */
    val isSlowMotion: Boolean get() = multiplier < 1.0

    /** Whether this is a fast-forward configuration. */
    val isFastForward: Boolean get() = multiplier > 1.0

    /** Whether this is normal speed. */
    val isNormal: Boolean get() = multiplier == 1.0
}
