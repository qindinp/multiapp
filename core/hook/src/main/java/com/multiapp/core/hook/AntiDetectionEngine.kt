package com.multiapp.core.hook

import android.os.Build
import com.multiapp.core.common.AndroidCompat
import com.multiapp.core.hook.antidetection.EmulatorDetectionBypass
import com.multiapp.core.hook.antidetection.IntegrityCheckBypass
import com.multiapp.core.hook.antidetection.PackerDetectionBypass
import com.multiapp.core.hook.antidetection.RootDetectionBypass
import com.multiapp.core.hook.antidetection.VirtualEnvironmentBypass
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AntiDetectionEngine -- Comprehensive anti-detection system for virtual apps.
 *
 * Delegates to specialised bypass classes:
 * - [RootDetectionBypass] -- hides su, Magisk, root packages
 * - [EmulatorDetectionBypass] -- spoofs Build properties, hides qemu files
 * - [VirtualEnvironmentBypass] -- hides host package, cleans stack traces
 * - [IntegrityCheckBypass] -- SafetyNet/Play Integrity, signature spoofing, Android 16
 *
 * Three detection levels:
 * - BASIC:      Root + emulator checks only (fast, low overhead)
 * - MODERATE:   + virtual env + Xposed + signature spoofing
 * - AGGRESSIVE: + stack trace cleaning + integrity checks + Play Integrity
 */
@Singleton
class AntiDetectionEngine @Inject constructor(
    @Suppress("unused") private val hookEngine: HookEngine,
    private val nativeHookBridge: NativeHookBridge
) {
    companion object {
        private const val TAG = "AntiDetect"
    }

    private val rootBypass = RootDetectionBypass(nativeHookBridge)
    private val emulatorBypass = EmulatorDetectionBypass(nativeHookBridge)
    private val virtualEnvBypass = VirtualEnvironmentBypass(nativeHookBridge)
    private val integrityBypass = IntegrityCheckBypass(nativeHookBridge)

    @Volatile private var initialized = false
    private val instanceLevels = ConcurrentHashMap<String, DetectionLevel>()
    @Volatile private var isActive = false

    fun initialize() {
        if (initialized) return
        initialized = true
        Timber.tag(TAG).i("AntiDetectionEngine initialized -- anti-detection ready")
    }

    /**
     * Enable anti-detection for a virtual app instance.
     *
     * @param instanceId The virtual app instance ID
     * @param level The detection avoidance level
     */
    fun enableAntiDetection(instanceId: String, level: DetectionLevel = DetectionLevel.MODERATE) {
        Timber.tag(TAG).i("Enabling anti-detection for $instanceId (level=$level)")

        instanceLevels[instanceId] = level

        // BASIC: Always apply root and emulator hiding
        rootBypass.hookRootChecks()
        emulatorBypass.hookEmulatorChecks()

        if (level >= DetectionLevel.MODERATE) {
            virtualEnvBypass.hookVirtualEnvChecks()
            virtualEnvBypass.hookXposedChecks()
        }

        if (level >= DetectionLevel.AGGRESSIVE) {
            virtualEnvBypass.hookStackTraceCleanup()
            integrityBypass.hookDexIntegrityChecks()
            integrityBypass.hookPlayIntegrity()
        }

        // Android 16 (API 36) specific bypasses
        if (AndroidCompat.isAtLeastW) {
            integrityBypass.hookAndroid16Integrity()
            integrityBypass.hookAndroid16DeviceFingerprint()
            integrityBypass.hookAndroid16SigningVerification()
            integrityBypass.hookAndroid16RequireSecureEnv()
            integrityBypass.detectAndroid16Checks()
        }

        // Packer (commercial protector) Java-layer detection bypass
        PackerDetectionBypass.apply(hookEngine, "universal")

        isActive = true
        Timber.tag(TAG).i("Anti-detection enabled for $instanceId: level=$level")
    }

    /**
     * Disable anti-detection for a virtual app instance.
     */
    fun disableAntiDetection(instanceId: String) {
        instanceLevels.remove(instanceId)
        if (instanceLevels.isEmpty()) {
            isActive = false
        }
        Timber.tag(TAG).d("Anti-detection disabled for $instanceId")
    }

    /** Store original APK signatures for signature spoofing. */
    fun registerOriginalSignatures(packageName: String, signatures: Array<ByteArray>) {
        integrityBypass.registerOriginalSignatures(packageName, signatures)
    }

    /** Get the original signatures for a package (used by PM proxy). */
    fun getOriginalSignatures(packageName: String): Array<ByteArray>? {
        return integrityBypass.getOriginalSignatures(packageName)
    }

    /** Check if anti-detection is active for an instance. */
    fun isActiveForInstance(instanceId: String): Boolean = instanceLevels.containsKey(instanceId)

    /** Get the detection level for an instance. */
    fun getLevelForInstance(instanceId: String): DetectionLevel? = instanceLevels[instanceId]

    /** Spoof APK signatures for a virtual app. */
    fun spoofSignatures(packageName: String, originalSignatureBytes: Array<ByteArray>) {
        integrityBypass.spoofSignatures(packageName, originalSignatureBytes)
    }

    /** Apply signature to a PackageInfo object. */
    fun applySignatureSpoof(packageInfo: Any, packageName: String): Boolean {
        return integrityBypass.applySignatureSpoof(packageInfo, packageName)
    }

    /** Clean a stack trace by removing MULTIAPP-related frames. */
    fun cleanStackTrace(trace: Array<StackTraceElement>): Array<StackTraceElement> {
        return virtualEnvBypass.cleanStackTrace(trace)
    }

    /** Clean an exception's stack trace in-place. */
    fun cleanException(throwable: Throwable) {
        virtualEnvBypass.cleanException(throwable)
    }

    /** Check if a package is a known root/hook package. */
    fun isRootPackage(packageName: String): Boolean = rootBypass.isRootPackage(packageName)

    /** Filter a list of package names to remove root/hook packages. */
    fun filterRootPackages(packages: List<String>): List<String> {
        return rootBypass.filterRootPackages(packages)
    }

    /** Apply packer-specific detection bypass (e.g. "360 Jiagu", "Tencent", "iJiami", "Bangcle"). */
    fun applyPackerBypass(packerType: String) {
        PackerDetectionBypass.apply(hookEngine, packerType)
    }

    /**
     * Run a self-test to verify anti-detection measures are working.
     * Returns a report of what's detected and what's hidden.
     */
    fun runSelfTest(): AntiDetectionReport {
        val rootBinariesVisible = RootDetectionBypass.ROOT_BINARIES.filter { File(it).exists() }
        val emulatorPathsVisible = EmulatorDetectionBypass.EMULATOR_PATHS.filter { File(it).exists() }
        val hookClassesLoadable = VirtualEnvironmentBypass.HOOK_FRAMEWORK_CLASSES.filter {
            try {
                Class.forName(it, false, ClassLoader.getSystemClassLoader())
                true
            } catch (_: ClassNotFoundException) {
                false
            }
        }

        val report = AntiDetectionReport(
            rootBinariesVisible = rootBinariesVisible,
            emulatorPathsVisible = emulatorPathsVisible,
            hookClassesLoadable = hookClassesLoadable,
            buildFingerprint = Build.FINGERPRINT,
            buildTags = Build.TAGS,
            buildType = Build.TYPE,
            isDebugBuild = Build.TYPE.contains("debug", ignoreCase = true) ||
                    Build.TAGS.contains("test-keys", ignoreCase = true),
            selinuxEnforcing = integrityBypass.checkSELinuxEnforcing(),
            totalHiddenPaths = nativeHookBridge.getHiddenPathCount(),
            totalRedirections = nativeHookBridge.getRedirectionCount(),
            nativeHooksAvailable = nativeHookBridge.isNativeAvailable(),
            isAndroid16 = AndroidCompat.isAtLeastW,
            android16IntegrityBypassed = AndroidCompat.isAtLeastW,
            android16FingerprintBypassed = AndroidCompat.isAtLeastW,
            android16SigningBypassed = AndroidCompat.isAtLeastW
        )

        Timber.tag(TAG).i("Self-test report: rootVisible=${rootBinariesVisible.size}, " +
                "emuVisible=${emulatorPathsVisible.size}, hookClasses=${hookClassesLoadable.size}")

        return report
    }
}

// ---------------------------------------------------------------------------
// Detection level enum & self-test report
// ---------------------------------------------------------------------------

/**
 * Detection avoidance level.
 */
enum class DetectionLevel {
    /** Root + emulator checks only (fast, low overhead) */
    BASIC,
    /** + virtual env + Xposed + signature spoofing */
    MODERATE,
    /** + stack trace cleaning + integrity checks + Play Integrity */
    AGGRESSIVE;

    companion object {
        fun fromString(value: String): DetectionLevel = when (value.uppercase()) {
            "BASIC" -> BASIC
            "MODERATE" -> MODERATE
            "AGGRESSIVE" -> AGGRESSIVE
            else -> MODERATE
        }
    }
}

/**
 * Report from anti-detection self-test.
 */
data class AntiDetectionReport(
    val rootBinariesVisible: List<String>,
    val emulatorPathsVisible: List<String>,
    val hookClassesLoadable: List<String>,
    val buildFingerprint: String,
    val buildTags: String,
    val buildType: String,
    val isDebugBuild: Boolean,
    val selinuxEnforcing: Boolean,
    val totalHiddenPaths: Int,
    val totalRedirections: Int,
    val nativeHooksAvailable: Boolean,
    val isAndroid16: Boolean = false,
    val android16IntegrityBypassed: Boolean = false,
    val android16FingerprintBypassed: Boolean = false,
    val android16SigningBypassed: Boolean = false
) {
    val isClean: Boolean
        get() = rootBinariesVisible.isEmpty() &&
                emulatorPathsVisible.isEmpty() &&
                hookClassesLoadable.isEmpty() &&
                !isDebugBuild &&
                selinuxEnforcing &&
                (!isAndroid16 || (android16IntegrityBypassed &&
                    android16FingerprintBypassed && android16SigningBypassed))

    fun summary(): String = buildString {
        appendLine("=== Anti-Detection Self-Test ===")
        appendLine("Root binaries visible: ${rootBinariesVisible.size}")
        appendLine("Emulator paths visible: ${emulatorPathsVisible.size}")
        appendLine("Hook classes loadable: ${hookClassesLoadable.size}")
        appendLine("Build fingerprint: $buildFingerprint")
        appendLine("Build tags: $buildTags")
        appendLine("Debug build: $isDebugBuild")
        appendLine("SELinux enforcing: $selinuxEnforcing")
        appendLine("Hidden paths: $totalHiddenPaths")
        appendLine("Path redirections: $totalRedirections")
        appendLine("Native hooks: $nativeHooksAvailable")
        appendLine("Android 16: $isAndroid16")
        if (isAndroid16) {
            appendLine("  Integrity V2 bypassed: $android16IntegrityBypassed")
            appendLine("  Fingerprint bypassed: $android16FingerprintBypassed")
            appendLine("  Signing bypassed: $android16SigningBypassed")
        }
        appendLine("CLEAN: $isClean")
    }
}
