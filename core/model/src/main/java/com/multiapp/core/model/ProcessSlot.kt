package com.multiapp.core.model

/**
 * Represents a process slot in the virtual engine.
 * Each slot corresponds to one guest app process (:p0, :p1, etc.)
 */
data class ProcessSlot(
    val slotIndex: Int,
    val processName: String,
    val assignedInstanceId: String? = null,
    val isOccupied: Boolean = false,
    val pid: Int = -1
)

/**
 * Stub component mapping for a process slot.
 * Each slot has pre-declared stub Activities, Services, Providers, Receivers.
 */
data class StubMapping(
    val processSlot: Int,
    val stubActivityStandard: List<String>,
    val stubActivitySingleTop: List<String>,
    val stubActivitySingleTask: List<String>,
    val stubActivitySingleInstance: List<String>,
    val stubServices: List<String>,
    val stubProviders: List<String>,
    val stubReceivers: List<String>
) {
    /** Get all stub activity class names for this slot */
    fun allActivities(): List<String> =
        stubActivityStandard + stubActivitySingleTop + stubActivitySingleTask + stubActivitySingleInstance

    /** Get all stub component class names for this slot */
    fun allComponents(): List<String> =
        allActivities() + stubServices + stubProviders + stubReceivers
}

/**
 * Intent extras keys used for stub-to-guest mapping.
 */
object VirtualIntentExtras {
    const val TARGET_PACKAGE = "_multiapp_target_pkg"
    const val TARGET_ACTIVITY = "_multiapp_target_activity"
    const val TARGET_SERVICE = "_multiapp_target_service"
    const val INSTANCE_ID = "_multiapp_instance_id"
    const val APK_PATH = "_multiapp_apk_path"
    const val PROCESS_SLOT = "_multiapp_process_slot"
    const val LAUNCH_MODE = "_multiapp_launch_mode"
}

/**
 * GMS service routing interface.
 * Implemented by VirtualGmsManager, used by ActivityManagerProxy
 * to route GMS bindService/startService calls through the Hybrid GMS bridge.
 *
 * This interface lives in core:model so both core:binder and core:services
 * can reference it without circular dependencies.
 */
interface GmsServiceRouter {
    /** Check if this intent targets a GMS or Play Store service */
    fun isGmsServiceIntent(intent: android.content.Intent): Boolean

    /** Route a GMS bind intent and return a proxied IBinder, or null if not handled */
    fun routeGmsBindIntent(
        intent: android.content.Intent,
        instanceId: String,
        guestPackageName: String
    ): android.os.IBinder?

    /** Intercept and route a GMS auth intent, returns modified intent or null */
    fun interceptAuthIntent(
        intent: android.content.Intent,
        instanceId: String
    ): android.content.Intent?

    /** Check if a package name belongs to GMS ecosystem */
    fun isGmsPackage(packageName: String): Boolean

    /** Get synthesized PackageInfo for a GMS package (gms, gsf, vending) */
    fun synthesizeGmsPackageInfo(packageName: String): android.content.pm.PackageInfo?

    /**
     * Get the isolated Google accounts for a specific virtual instance.
     * Returns null if the instance has no virtual account yet (guest hasn't signed in).
     * Returns an empty list to mean "no accounts" (hide host accounts from guest).
     *
     * Used by AccountManagerProxy to enforce per-instance account isolation:
     * - If the instance has signed in -> return only that instance's account
     * - If not yet signed in -> return null (fall back to host accounts for sign-in flow)
     */
    fun getVirtualGoogleAccounts(instanceId: String): List<android.accounts.Account>?
}

/**
 * Constants for the virtual engine.
 */
object VirtualConstants {
    /** Maximum number of simultaneous virtual app processes */
    const val MAX_PROCESS_SLOTS = 10

    /** Number of standard stub Activities per process slot */
    const val STUB_ACTIVITIES_STANDARD = 5

    /** Number of singleTop stub Activities per process slot */
    const val STUB_ACTIVITIES_SINGLE_TOP = 2

    /** Number of singleTask stub Activities per process slot */
    const val STUB_ACTIVITIES_SINGLE_TASK = 2

    /** Number of singleInstance stub Activities per process slot */
    const val STUB_ACTIVITIES_SINGLE_INSTANCE = 1

    /** Number of stub Services per process slot */
    const val STUB_SERVICES = 5

    /** Number of stub ContentProviders per process slot */
    const val STUB_PROVIDERS = 3

    /** Number of stub BroadcastReceivers per process slot */
    const val STUB_RECEIVERS = 2

    /** Base directory name for virtual storage */
    const val VIRTUAL_DIR = "virtual"

    /** APK storage subdirectory */
    const val VIRTUAL_APKS_DIR = "apks"

    /** Per-app data subdirectory */
    const val VIRTUAL_DATA_DIR = "data"

    /** Snapshots subdirectory */
    const val VIRTUAL_SNAPSHOTS_DIR = "snapshots"

    /** Host app package name */
    const val HOST_PACKAGE = "com.multiapp.app"

    /** Stub class prefix */
    const val STUB_PREFIX = "com.multiapp.app.stub"
}
