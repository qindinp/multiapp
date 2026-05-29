package com.multiapp.core.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider interface for engine-specific crash context.
 *
 * Implementations live in the virtualization module and are registered
 * with [CrashReporter.setContextProvider] during engine initialization.
 * This avoids circular dependencies between core/common and core/virtualization.
 */
interface CrashContextProvider {
    fun getEngineState(): String
    fun getRunningApps(): List<String>
    fun getInstalledApps(): List<String>
    fun getHookStatus(): Map<String, Boolean>
}

/**
 * CrashReporter — Unified crash and error collection for multiapp.
 *
 * Responsibilities:
 * - Global [Thread.UncaughtExceptionHandler] for host-process crashes
 * - Per-instance crash/error logging for virtual app failures
 * - Persistent storage as JSON files under the app's internal directory
 * - Thread-safe: all maps use [ConcurrentHashMap]
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash_reports"

    private val reports = ConcurrentHashMap<String, MutableList<CrashReport>>()
    private var reportsDir: File? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    @Volatile private var contextProvider: CrashContextProvider? = null

    // ================================================================
    // Initialization
    // ================================================================

    /**
     * Initialize the crash reporter. Must be called once, typically in [Application.onCreate].
     *
     * - Installs a global [Thread.UncaughtExceptionHandler] that persists the crash
     *   before delegating to the previous handler (so the system still kills the process).
     * - Loads any previously persisted reports from disk.
     */
    fun init(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.mkdirs()
        reportsDir = dir
        appContext = context.applicationContext

        loadFromDisk()

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashContext = collectCrashContext()
                val report = CrashReport(
                    instanceId = "__host__",
                    tag = "UncaughtException",
                    message = "Uncaught exception on ${thread.name}",
                    throwable = throwable,
                    isCrash = true,
                    crashContext = crashContext
                )
                persistReport("__host__", report)
            } catch (_: Exception) {
                // Best-effort; don't mask the original crash
            }
            // Forward to the previous handler so Android can terminate normally
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Timber.tag(TAG).i("CrashReporter initialized — ${dir.absolutePath}")
    }

    /**
     * Register a [CrashContextProvider] to supply engine-specific diagnostics
     * (engine state, running apps, hook status) at crash time.
     *
     * Called from the virtualization module after engine initialization.
     */
    fun setContextProvider(provider: CrashContextProvider) {
        contextProvider = provider
        Timber.tag(TAG).i("CrashContextProvider registered")
    }

    // ================================================================
    // Reporting
    // ================================================================

    /**
     * Record a crash for a virtual app instance.
     */
    fun reportCrash(instanceId: String, throwable: Throwable) {
        val report = CrashReport(
            instanceId = instanceId,
            tag = "VirtualAppCrash",
            message = throwable.message ?: "Unknown crash",
            throwable = throwable,
            isCrash = true
        )
        addReport(instanceId, report)
    }

    /**
     * Record a non-fatal error for a virtual app instance.
     */
    fun reportError(
        instanceId: String,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val report = CrashReport(
            instanceId = instanceId,
            tag = tag,
            message = message,
            throwable = throwable,
            isCrash = false
        )
        addReport(instanceId, report)
    }

    // ================================================================
    // Query
    // ================================================================

    /**
     * Get all crash reports grouped by instance ID.
     */
    fun getCrashReports(): Map<String, List<CrashReport>> =
        reports.mapValues { (_, list) -> synchronized(list) { list.toList() } }

    /**
     * Get crash reports for a specific instance.
     */
    fun getCrashReport(instanceId: String): List<CrashReport> {
        val list = reports[instanceId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /**
     * Get the total count of all reports across all instances.
     */
    fun getTotalReportCount(): Int = reports.values.sumOf { list ->
        synchronized(list) { list.size }
    }

    /**
     * Clear all reports from memory and disk.
     */
    fun clearReports() {
        reports.clear()
        val dir = reportsDir ?: return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                file.listFiles()?.forEach { it.delete() }
                file.delete()
            } else {
                file.delete()
            }
        }
        Timber.tag(TAG).i("All crash reports cleared")
    }

    // ================================================================
    // Export
    // ================================================================

    /**
     * Export all reports as a single human-readable text block.
     */
    fun exportAsText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== MULTIAPP Crash Report Export ===")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("Total instances: ${reports.size}")
        sb.appendLine()

        reports.forEach { (instanceId, reportList) ->
            val snapshot = synchronized(reportList) { reportList.toList() }
            sb.appendLine("--- Instance: $instanceId (${snapshot.size} reports) ---")
            snapshot.forEach { report ->
                sb.appendLine("  [${report.severity}] ${report.tag}")
                sb.appendLine("  Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(report.timestamp))}")
                sb.appendLine("  Message: ${report.message}")
                if (report.stackTrace.isNotEmpty()) {
                    sb.appendLine("  Stack trace:")
                    report.stackTrace.lineSequence().forEach { line ->
                        sb.appendLine("    $line")
                    }
                }
                report.crashContext?.let { ctx ->
                    sb.appendLine("  Crash context:")
                    sb.appendLine("    Engine state: ${ctx.engineState}")
                    sb.appendLine("    Running apps: ${ctx.runningApps.joinToString()}")
                    sb.appendLine("    Installed apps: ${ctx.installedApps.joinToString()}")
                    if (ctx.hookStatus.isNotEmpty()) {
                        sb.appendLine("    Hook status:")
                        ctx.hookStatus.forEach { (name, installed) ->
                            sb.appendLine("      $name: ${if (installed) "installed" else "missing"}")
                        }
                    }
                    sb.appendLine("    Device: ${ctx.deviceInfo.manufacturer} ${ctx.deviceInfo.model} (Android ${ctx.deviceInfo.androidVersion}, SDK ${ctx.deviceInfo.sdkVersion})")
                    sb.appendLine("    ABI: ${ctx.deviceInfo.abi}")
                    sb.appendLine("    Memory (system): ${ctx.deviceInfo.totalMemory / (1024 * 1024)}MB total, ${ctx.deviceInfo.availableMemory / (1024 * 1024)}MB available")
                    sb.appendLine("    Memory (runtime): max=${ctx.memoryInfo.runtimeMaxMemory / (1024 * 1024)}MB, total=${ctx.memoryInfo.runtimeTotalMemory / (1024 * 1024)}MB, free=${ctx.memoryInfo.runtimeFreeMemory / (1024 * 1024)}MB")
                }
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    // ================================================================
    // Crash Context Collection
    // ================================================================

    /**
     * Collect comprehensive crash context at the moment of failure.
     *
     * Every sub-collection is individually wrapped in try-catch so that a
     * failure in one area (e.g., ActivityManager unavailable) does not
     * prevent other diagnostics from being captured.
     */
    fun collectCrashContext(): CrashContext {
        var engineState = "unknown"
        var runningApps = emptyList<String>()
        var installedApps = emptyList<String>()
        var hookStatus = emptyMap<String, Boolean>()

        try {
            contextProvider?.let { provider ->
                engineState = try { provider.getEngineState() } catch (_: Exception) { "error" }
                runningApps = try { provider.getRunningApps() } catch (_: Exception) { emptyList() }
                installedApps = try { provider.getInstalledApps() } catch (_: Exception) { emptyList() }
                hookStatus = try { provider.getHookStatus() } catch (_: Exception) { emptyMap() }
            }
        } catch (_: Exception) { /* provider access failed */ }

        val deviceInfo = collectDeviceInfo()
        val memoryInfo = collectMemoryInfo()

        return CrashContext(
            engineState = engineState,
            runningApps = runningApps,
            installedApps = installedApps,
            hookStatus = hookStatus,
            deviceInfo = deviceInfo,
            memoryInfo = memoryInfo,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun collectDeviceInfo(): DeviceInfo {
        return try {
            DeviceInfo(
                model = Build.MODEL ?: "unknown",
                manufacturer = Build.MANUFACTURER ?: "unknown",
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                sdkVersion = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI ?: "unknown",
                totalMemory = getTotalMemoryBytes(),
                availableMemory = getAvailableMemoryBytes()
            )
        } catch (_: Exception) {
            DeviceInfo("unknown", "unknown", "unknown", 0, "unknown", 0L, 0L)
        }
    }

    private fun collectMemoryInfo(): MemoryInfo {
        return try {
            val runtime = Runtime.getRuntime()
            val systemMemory = getSystemMemoryInfo()
            MemoryInfo(
                runtimeMaxMemory = runtime.maxMemory(),
                runtimeTotalMemory = runtime.totalMemory(),
                runtimeFreeMemory = runtime.freeMemory(),
                systemTotalMemory = systemMemory.first,
                systemAvailableMemory = systemMemory.second
            )
        } catch (_: Exception) {
            MemoryInfo(0L, 0L, 0L, 0L, 0L)
        }
    }

    private fun getTotalMemoryBytes(): Long {
        return try {
            val ctx = appContext ?: return 0L
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.totalMem
        } catch (_: Exception) { 0L }
    }

    private fun getAvailableMemoryBytes(): Long {
        return try {
            val ctx = appContext ?: return 0L
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.availMem
        } catch (_: Exception) { 0L }
    }

    private fun getSystemMemoryInfo(): Pair<Long, Long> {
        return try {
            val ctx = appContext ?: return Pair(0L, 0L)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return Pair(0L, 0L)
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            Pair(memInfo.totalMem, memInfo.availMem)
        } catch (_: Exception) { Pair(0L, 0L) }
    }

    // ================================================================
    // Internal
    // ================================================================

    private fun addReport(instanceId: String, report: CrashReport) {
        val list = reports.getOrPut(instanceId) { mutableListOf() }
        synchronized(list) {
            list.add(report)
            // Cap at 100 reports per instance to prevent unbounded growth
            if (list.size > 100) {
                list.removeAt(0)
            }
        }
        persistReport(instanceId, report)

        val level = if (report.isCrash) "CRASH" else "ERROR"
        Timber.tag(TAG).w("[$level] $instanceId / ${report.tag}: ${report.message}")
    }

    private fun persistReport(instanceId: String, report: CrashReport) {
        val dir = reportsDir ?: return
        try {
            val instanceDir = File(dir, instanceId.sanitizeFileName())
            instanceDir.mkdirs()

            val fileName = "${report.timestamp}_${report.tag.sanitizeFileName()}.json"
            File(instanceDir, fileName).writeText(report.toJson().toString(2))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to persist crash report for $instanceId")
        }
    }

    private fun loadFromDisk() {
        val dir = reportsDir ?: return
        try {
            dir.listFiles()?.forEach { instanceDir ->
                if (!instanceDir.isDirectory) return@forEach
                val instanceId = instanceDir.name
                val list = reports.getOrPut(instanceId) { mutableListOf() }
                instanceDir.listFiles { file -> file.extension == "json" }
                    ?.sortedBy { it.lastModified() }
                    ?.forEach { file ->
                        try {
                            val json = JSONObject(file.readText())
                            list.add(CrashReport.fromJson(json))
                        } catch (_: Exception) {
                            // Skip malformed files
                        }
                    }
            }
            val total = reports.values.sumOf { it.size }
            if (total > 0) {
                Timber.tag(TAG).i("Loaded $total crash reports from disk")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load crash reports from disk")
        }
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
}

// ================================================================
// Data model
// ================================================================

/**
 * A single crash/error report.
 */
data class CrashReport(
    val instanceId: String,
    val tag: String,
    val message: String,
    val throwable: Throwable?,
    val isCrash: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val crashContext: CrashContext? = null
) {
    val severity: String get() = if (isCrash) "CRASH" else "ERROR"

    val stackTrace: String
        get() {
            if (throwable == null) return ""
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            return sw.toString()
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("instanceId", instanceId)
        put("tag", tag)
        put("message", message)
        put("isCrash", isCrash)
        put("timestamp", timestamp)
        if (stackTrace.isNotEmpty()) {
            put("stackTrace", stackTrace)
        }
        if (crashContext != null) {
            put("crashContext", crashContext.toJson())
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CrashReport {
            val ctxJson = json.optJSONObject("crashContext")
            return CrashReport(
                instanceId = json.optString("instanceId", ""),
                tag = json.optString("tag", ""),
                message = json.optString("message", ""),
                throwable = null,
                isCrash = json.optBoolean("isCrash", false),
                timestamp = json.optLong("timestamp", 0L),
                crashContext = ctxJson?.let { CrashContext.fromJson(it) }
            )
        }
    }
}

// ================================================================
// Crash context data model
// ================================================================

/**
 * Rich diagnostic snapshot captured at the moment of a crash.
 *
 * Contains engine state, running/installed app lists, hook installation
 * status, device identity, and memory pressure — all collected defensively
 * so that a failure in any one area does not block the rest.
 */
data class CrashContext(
    val engineState: String,
    val runningApps: List<String>,
    val installedApps: List<String>,
    val hookStatus: Map<String, Boolean>,
    val deviceInfo: DeviceInfo,
    val memoryInfo: MemoryInfo,
    val timestamp: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("engineState", engineState)
        put("runningApps", JSONArray(runningApps))
        put("installedApps", JSONArray(installedApps))
        put("hookStatus", JSONObject().apply {
            hookStatus.forEach { (key, value) -> put(key, value) }
        })
        put("deviceInfo", deviceInfo.toJson())
        put("memoryInfo", memoryInfo.toJson())
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): CrashContext {
            val runningArr = json.optJSONArray("runningApps")
            val running = (0 until (runningArr?.length() ?: 0)).mapNotNull {
                runningArr?.optString(it)
            }
            val installedArr = json.optJSONArray("installedApps")
            val installed = (0 until (installedArr?.length() ?: 0)).mapNotNull {
                installedArr?.optString(it)
            }
            val hookObj = json.optJSONObject("hookStatus")
            val hooks = mutableMapOf<String, Boolean>()
            hookObj?.keys()?.forEach { key ->
                hooks[key] = hookObj.optBoolean(key, false)
            }
            return CrashContext(
                engineState = json.optString("engineState", "unknown"),
                runningApps = running,
                installedApps = installed,
                hookStatus = hooks,
                deviceInfo = json.optJSONObject("deviceInfo")?.let { DeviceInfo.fromJson(it) }
                    ?: DeviceInfo("unknown", "unknown", "unknown", 0, "unknown", 0L, 0L),
                memoryInfo = json.optJSONObject("memoryInfo")?.let { MemoryInfo.fromJson(it) }
                    ?: MemoryInfo(0L, 0L, 0L, 0L, 0L),
                timestamp = json.optLong("timestamp", 0L)
            )
        }
    }
}

/**
 * Device identity captured at crash time.
 */
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val abi: String,
    val totalMemory: Long,
    val availableMemory: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("model", model)
        put("manufacturer", manufacturer)
        put("androidVersion", androidVersion)
        put("sdkVersion", sdkVersion)
        put("abi", abi)
        put("totalMemory", totalMemory)
        put("availableMemory", availableMemory)
    }

    companion object {
        fun fromJson(json: JSONObject): DeviceInfo = DeviceInfo(
            model = json.optString("model", "unknown"),
            manufacturer = json.optString("manufacturer", "unknown"),
            androidVersion = json.optString("androidVersion", "unknown"),
            sdkVersion = json.optInt("sdkVersion", 0),
            abi = json.optString("abi", "unknown"),
            totalMemory = json.optLong("totalMemory", 0L),
            availableMemory = json.optLong("availableMemory", 0L)
        )
    }
}

/**
 * Memory pressure snapshot captured at crash time.
 *
 * Includes both the JVM runtime heap (max/total/free) and the system-wide
 * physical memory reported by [ActivityManager.MemoryInfo].
 */
data class MemoryInfo(
    val runtimeMaxMemory: Long,
    val runtimeTotalMemory: Long,
    val runtimeFreeMemory: Long,
    val systemTotalMemory: Long,
    val systemAvailableMemory: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("runtimeMaxMemory", runtimeMaxMemory)
        put("runtimeTotalMemory", runtimeTotalMemory)
        put("runtimeFreeMemory", runtimeFreeMemory)
        put("systemTotalMemory", systemTotalMemory)
        put("systemAvailableMemory", systemAvailableMemory)
    }

    companion object {
        fun fromJson(json: JSONObject): MemoryInfo = MemoryInfo(
            runtimeMaxMemory = json.optLong("runtimeMaxMemory", 0L),
            runtimeTotalMemory = json.optLong("runtimeTotalMemory", 0L),
            runtimeFreeMemory = json.optLong("runtimeFreeMemory", 0L),
            systemTotalMemory = json.optLong("systemTotalMemory", 0L),
            systemAvailableMemory = json.optLong("systemAvailableMemory", 0L)
        )
    }
}
