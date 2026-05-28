package com.multiapp.core.common

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber

/**
 * Android version compatibility helper.
 * Based on Android 16 (API 36) frameworks/base analysis.
 *
 * Each version introduces restrictions that multiapp must handle:
 * - API 28: Hidden API restrictions
 * - API 29: Scoped storage
 * - API 30: Package visibility, meta-reflection blocked
 * - API 31: Exported components requirement
 * - API 33: Notification permission
 * - API 34: Foreground service types, intent restrictions
 * - API 35: 16KB page size, DataSync FGS timeout
 * - API 36: Play Integrity V2, REQUIRE_SECURE_ENV, signing scheme V4
 */
object AndroidCompat {

    val isAtLeastO = Build.VERSION.SDK_INT >= 26   // Android 8.0
    val isAtLeastP = Build.VERSION.SDK_INT >= 28   // Android 9
    val isAtLeastQ = Build.VERSION.SDK_INT >= 29   // Android 10
    val isAtLeastR = Build.VERSION.SDK_INT >= 30   // Android 11
    val isAtLeastS = Build.VERSION.SDK_INT >= 31   // Android 12
    val isAtLeastT = Build.VERSION.SDK_INT >= 33   // Android 13
    val isAtLeastU = Build.VERSION.SDK_INT >= 34   // Android 14
    val isAtLeastV = Build.VERSION.SDK_INT >= 35   // Android 15
    val isAtLeastW = Build.VERSION.SDK_INT >= 36   // Android 16 (Baklava)

    /**
     * Get the correct field name for the IActivityManager singleton.
     * Changed from "gDefault" to "IActivityManagerSingleton" in Android 8.0.
     */
    fun getIActivityManagerSingletonFieldName(): String {
        return if (isAtLeastO) "IActivityManagerSingleton" else "gDefault"
    }

    /**
     * Get the Handler message code for activity transactions.
     * Android 9+ uses EXECUTE_TRANSACTION (159) instead of LAUNCH_ACTIVITY (100).
     */
    fun getActivityTransactionMessageCode(): Int {
        return if (isAtLeastP) 159 else 100
    }

    /**
     * Whether hidden API bypass is required.
     */
    fun needsHiddenApiBypass(): Boolean = isAtLeastP

    /**
     * Whether package visibility restrictions apply.
     */
    fun needsQueryAllPackages(): Boolean = isAtLeastR

    /**
     * Whether foreground service type must be declared.
     */
    fun needsForegroundServiceType(): Boolean = isAtLeastU

    /**
     * Whether POST_NOTIFICATIONS permission is required.
     */
    fun needsNotificationPermission(): Boolean = isAtLeastT

    /**
     * Whether Android 16 Play Integrity V2 API changes apply.
     * Android 16 modifies IntegrityManager behavior and response format.
     */
    fun needsIntegrityV2(): Boolean = isAtLeastW

    /**
     * Whether Android 16 strict signing scheme enforcement is active.
     * Android 16 requires APK Signature Scheme V4 for certain operations.
     */
    fun needsSigningSchemeV4(): Boolean = isAtLeastW

    /**
     * Whether REQUIRE_SECURE_ENV flag is available (Android 16+).
     * Apps declaring this flag cannot run in virtualized environments.
     */
    fun supportsRequireSecureEnv(): Boolean = isAtLeastW

    /**
     * Bypass Android Hidden API restrictions.
     * MUST be called BEFORE any reflection on hidden APIs.
     *
     * Primary: LSPosed HiddenApiBypass library (works on Android 9-15+).
     * Fallback 1: VMRuntime.setHiddenApiExemptions (pre-Android 15).
     * Fallback 2: Unsafe memory manipulation.
     */
    fun bypassHiddenApis(): Boolean {
        if (!needsHiddenApiBypass()) {
            Timber.tag("AndroidCompat").d("Hidden API bypass not needed (API < 28)")
            return true
        }

        // Primary: LSPosed HiddenApiBypass (works on all Android 9-15+)
        if (bypassViaLSPosed()) return true

        // Fallback 1: VMRuntime reflection (may work on older Android)
        if (bypassViaVMRuntime()) return true

        // Fallback 2: Unsafe memory approach
        if (bypassViaUnsafe()) return true

        Timber.tag("AndroidCompat").e("ALL hidden API bypass methods failed!")
        return false
    }

    /**
     * LSPosed HiddenApiBypass - works on Android 9 through 15+.
     * Uses Unsafe memory operations to bypass restrictions without
     * needing to call any hidden API itself.
     */
    private fun bypassViaLSPosed(): Boolean {
        return try {
            val result = HiddenApiBypass.addHiddenApiExemptions("")
            if (result) {
                Timber.tag("AndroidCompat").i("Hidden API bypass via LSPosed successful")
            } else {
                Timber.tag("AndroidCompat").w("LSPosed HiddenApiBypass returned false")
            }
            result
        } catch (e: Exception) {
            Timber.tag("AndroidCompat").w(e, "LSPosed HiddenApiBypass failed, trying VMRuntime fallback...")
            false
        }
    }

    /**
     * VMRuntime.setHiddenApiExemptions fallback.
     * Works on Android 9-14 but BLOCKED on Android 15+.
     */
    private fun bypassViaVMRuntime(): Boolean {
        return try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null)

            val setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            setHiddenApiExemptions.isAccessible = true
            setHiddenApiExemptions.invoke(runtime, arrayOf("L") as Any)

            Timber.tag("AndroidCompat").i("Hidden API bypass via VMRuntime successful")
            true
        } catch (e: Exception) {
            Timber.tag("AndroidCompat").w(e, "VMRuntime bypass failed, trying Unsafe fallback...")
            false
        }
    }

    /**
     * Unsafe.putInt fallback for hidden API bypass.
     * Directly writes to VMRuntime's hidden API enforcement policy field.
     */
    private fun bypassViaUnsafe(): Boolean {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafe.isAccessible = true
            val unsafe = theUnsafe.get(null)

            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null)

            val objectFieldOffset = unsafeClass.getDeclaredMethod(
                "objectFieldOffset",
                java.lang.reflect.Field::class.java
            )

            val fields = vmRuntimeClass.declaredFields
            for (field in fields) {
                if (field.name.contains("hiddenApi", ignoreCase = true) ||
                    field.name.contains("enforcement", ignoreCase = true)
                ) {
                    field.isAccessible = true
                    val offset = objectFieldOffset.invoke(unsafe, field)
                    val putInt = unsafeClass.getDeclaredMethod(
                        "putInt",
                        Any::class.java,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    putInt.invoke(unsafe, runtime, offset as Long, 0)
                }
            }

            Timber.tag("AndroidCompat").i("Hidden API bypass via Unsafe successful")
            true
        } catch (e: Exception) {
            Timber.tag("AndroidCompat").e(e, "Unsafe bypass also failed")
            false
        }
    }
}
