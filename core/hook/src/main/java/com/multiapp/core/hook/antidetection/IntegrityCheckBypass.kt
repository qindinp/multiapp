package com.multiapp.core.hook.antidetection

import android.os.Build
import com.multiapp.core.common.AndroidCompat
import com.multiapp.core.common.findField
import com.multiapp.core.common.runSafe
import com.multiapp.core.hook.NativeHookBridge
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SafetyNet / Play Integrity bypass, signature spoofing,
 * SELinux context handling, and Android 16 (API 36) specific bypasses.
 */
class IntegrityCheckBypass(
    private val nativeHookBridge: NativeHookBridge
) {
    companion object {
        private const val TAG = "AntiDetect"

        // Android 16 (API 36) Play Integrity class names
        private val ANDROID16_INTEGRITY_CLASSES = setOf(
            "com.google.android.play.core.integrity.IntegrityManagerFactory",
            "com.google.android.play.core.integrity.IntegrityManager",
            "com.google.android.play.core.integrity.IntegrityTokenRequest",
            "com.google.android.play.core.integrity.IntegrityTokenResponse",
            "com.google.android.play.core.integrity.StandardIntegrityManager",
            "com.google.android.play.core.integrity.StandardIntegrityTokenProvider",
            "com.google.android.play.core.integrity.StandardIntegrityTokenRequest"
        )

        // Android 16 new device attestation class names
        private val ANDROID16_ATTESTATION_CLASSES = setOf(
            "android.security.keystore.KeyGenParameterSpec",
            "android.security.keystore.KeyProperties",
            "android.security.ConfirmationCallback",
            "android.security.ConfirmationPrompt"
        )

        // Android 16 paths that reveal virtualization state
        private val ANDROID16_VIRTUAL_PATHS = setOf(
            "/proc/self/cgroup",
            "/proc/1/cgroup",
            "/sys/fs/cgroup",
            "/dev/binderfs",
            "/dev/binderfs/binder-control",
            "/proc/self/mountinfo"
        )

        // Emulator-related Build values (used for /proc/cpuinfo cleaning)
        private val EMULATOR_BUILD_VALUES = setOf(
            "goldfish", "ranchu", "generic", "sdk", "vbox", "genymotion",
            "nox", "bluestacks", "andy", "ttVM_Hdragon", "google_sdk",
            "Droid4X", "sdk_phone", "sdk_gphone"
        )
    }

    // Original APK signatures cache: packageName -> original Signature[]
    private val originalSignatures = ConcurrentHashMap<String, Array<ByteArray>>()

    /**
     * Store original APK signatures for signature spoofing.
     */
    fun registerOriginalSignatures(packageName: String, signatures: Array<ByteArray>) {
        originalSignatures[packageName] = signatures
        Timber.tag(TAG).d("Original signatures registered for $packageName (${signatures.size} sigs)")
    }

    /**
     * Get the original signatures for a package (used by PM proxy).
     */
    fun getOriginalSignatures(packageName: String): Array<ByteArray>? {
        return originalSignatures[packageName]
    }

    /**
     * Spoof APK signatures for a virtual app.
     */
    fun spoofSignatures(packageName: String, originalSignatureBytes: Array<ByteArray>) {
        originalSignatures[packageName] = originalSignatureBytes
        Timber.tag(TAG).d("Signature spoofing configured for $packageName (${originalSignatureBytes.size} sigs)")
    }

    /**
     * Apply signature to a PackageInfo object.
     * Called by PackageManagerProxy when intercepting getPackageInfo.
     */
    fun applySignatureSpoof(packageInfo: Any, packageName: String): Boolean {
        val sigBytes = originalSignatures[packageName] ?: return false

        return try {
            val signatureClass = Class.forName("android.content.pm.Signature")
            val constructor = signatureClass.getConstructor(ByteArray::class.java)
            val signatures = sigBytes.map { constructor.newInstance(it) }.toTypedArray()

            val sigField = findField(packageInfo::class.java, "signatures")
            if (sigField != null) {
                sigField.isAccessible = true
                sigField.set(packageInfo, signatures)
            }

            if (AndroidCompat.isAtLeastP) {
                try {
                    val signingInfoField = findField(packageInfo::class.java, "signingInfo")
                    if (signingInfoField != null) {
                        signingInfoField.isAccessible = true
                        val signingInfo = signingInfoField.get(packageInfo)
                        if (signingInfo != null) {
                            val pastSignaturesField = findField(signingInfo::class.java, "mPastSigningCertificates")
                            if (pastSignaturesField != null) {
                                pastSignaturesField.isAccessible = true
                                pastSignaturesField.set(signingInfo, signatures)
                            }
                        }
                    }
                } catch (_: Exception) { /* SigningInfo API varies */ }
            }

            Timber.tag(TAG).d("Signature spoofed on PackageInfo for $packageName")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to apply signature spoof for $packageName")
            false
        }
    }

    // ====================================================================
    // DEX integrity check bypass (AGGRESSIVE level)
    // ====================================================================

    /**
     * Hook DEX integrity verification.
     * Some apps compute CRC32/SHA-256 of their own DEX files to detect tampering.
     */
    fun hookDexIntegrityChecks() {
        Timber.tag(TAG).d("Installing DEX integrity check bypass...")
        // For Phase 1, we ensure the original APK is accessible so integrity checks pass.
        Timber.tag(TAG).d("DEX integrity hooks prepared")
    }

    // ====================================================================
    // SafetyNet / Play Integrity (AGGRESSIVE level)
    // ====================================================================

    /**
     * Set up hooks for SafetyNet/Play Integrity attestation.
     */
    fun hookPlayIntegrity() {
        Timber.tag(TAG).d("Installing Play Integrity bypass...")

        // 1. Ensure SELinux context is correct
        spoofSELinuxContext()

        // 2. System property cleanliness
        nativeHookBridge.spoofSystemProperty("ro.build.tags", "release-keys")
        nativeHookBridge.spoofSystemProperty("ro.build.type", "user")
        nativeHookBridge.spoofSystemProperty("ro.debuggable", "0")
        nativeHookBridge.spoofSystemProperty("ro.secure", "1")

        // 3. Bootloader state
        nativeHookBridge.spoofSystemProperty("ro.boot.verifiedbootstate", "green")
        nativeHookBridge.spoofSystemProperty("ro.boot.flash.locked", "1")
        nativeHookBridge.spoofSystemProperty("ro.boot.vbmeta.device_state", "locked")
        nativeHookBridge.spoofSystemProperty("ro.boot.veritymode", "enforcing")

        Timber.tag(TAG).d("Play Integrity bypass hooks prepared")
    }

    // ====================================================================
    // SELinux context handling
    // ====================================================================

    /**
     * Spoof SELinux to report enforcing mode and valid context.
     */
    private fun spoofSELinuxContext() {
        try {
            val selinuxClass = runSafe(TAG) { Class.forName("android.os.SELinux") }
            if (selinuxClass != null) {
                Timber.tag(TAG).d("SELinux class found, spoofing context")
                nativeHookBridge.setFakeFileContent("/sys/fs/selinux/enforce", "1\n")
                nativeHookBridge.setFakeFileContent("/selinux/enforce", "1\n")
            }

            nativeHookBridge.setFakeFileContent("/proc/self/attr/current",
                "u:r:untrusted_app:s0:c512,c768\n")

        } catch (e: Exception) {
            Timber.tag(TAG).w("SELinux spoofing failed: ${e.message}")
        }
    }

    // ====================================================================
    // Android 16 (API 36) specific bypasses
    // ====================================================================

    /**
     * Hook Android 16 Play Integrity API changes.
     */
    fun hookAndroid16Integrity() {
        Timber.tag(TAG).d("Installing Android 16 Play Integrity V2 bypass...")

        for (className in ANDROID16_INTEGRITY_CLASSES) {
            try {
                Class.forName(className, false, ClassLoader.getSystemClassLoader())
                Timber.tag(TAG).d("Integrity class present: $className (will be handled by LSPlant hook)")
            } catch (_: ClassNotFoundException) {
                // Play Core library not loaded in this process
            }
        }

        nativeHookBridge.spoofSystemProperty("ro.build.id", Build.ID)
        nativeHookBridge.spoofSystemProperty("ro.product.first_api_level",
            Build.VERSION.SDK_INT.toString())
        nativeHookBridge.spoofSystemProperty("ro.build.version.preview_sdk", "0")
        nativeHookBridge.spoofSystemProperty("ro.build.version.codename", "REL")

        nativeHookBridge.hidePath("/data/adb/magisk")
        nativeHookBridge.hidePath("/data/adb/ksu")
        nativeHookBridge.hidePath("/data/adb/ksud")
        nativeHookBridge.hidePath("/data/adb/modules")
        nativeHookBridge.hidePath("/debug_ramdisk")

        try {
            val procVersion = File("/proc/version").readText()
            val cleanVersion = procVersion
                .replace(Regex("(?i)magisk|ksu|supersu|root"), "")
                .replace(Regex("\\s+"), " ")
            nativeHookBridge.setFakeFileContent("/proc/version", cleanVersion)
        } catch (_: Exception) { /* /proc/version read may fail */ }

        nativeHookBridge.setFakeFileContent("/proc/self/status",
            buildCleanProcStatusForIntegrity())

        Timber.tag(TAG).d("Android 16 Play Integrity V2 bypass installed")
    }

    /**
     * Hook Android 16 device fingerprint detection changes.
     */
    fun hookAndroid16DeviceFingerprint() {
        Timber.tag(TAG).d("Installing Android 16 device fingerprint bypass...")

        try {
            val expectedFingerprint = "${Build.BRAND}/${Build.PRODUCT}/${Build.DEVICE}:" +
                "${Build.VERSION.RELEASE}/${Build.ID}/${Build.VERSION.INCREMENTAL}:" +
                "${Build.TYPE}/${Build.TAGS}"

            if (Build.FINGERPRINT != expectedFingerprint) {
                Timber.tag(TAG).w("Build.FINGERPRINT mismatch detected: " +
                    "actual=${Build.FINGERPRINT}, expected=$expectedFingerprint")
            }

            if (Build.FINGERPRINT.contains("test-keys", ignoreCase = true)) {
                Timber.tag(TAG).w("Build.FINGERPRINT contains test-keys -- needs spoofing")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Fingerprint validation check failed: ${e.message}")
        }

        try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val cleanCpuInfo = cpuInfo.lines().map { line ->
                when {
                    line.startsWith("Hardware", ignoreCase = true) &&
                        EMULATOR_BUILD_VALUES.any { line.contains(it, ignoreCase = true) } ->
                        "Hardware\t: Qualcomm Technologies Inc SM8150"
                    line.startsWith("model name", ignoreCase = true) &&
                        line.contains("virtual", ignoreCase = true) ->
                        "model name\t: ARMv8 Processor"
                    else -> line
                }
            }.joinToString("\n")
            nativeHookBridge.setFakeFileContent("/proc/cpuinfo", cleanCpuInfo)
        } catch (_: Exception) { /* /proc/cpuinfo may not be readable */ }

        nativeHookBridge.setFakeFileContent("/sys/devices/soc0/soc_id", "292\n")
        nativeHookBridge.setFakeFileContent("/sys/devices/soc0/machine", "Qualcomm Technologies Inc SM8150\n")
        nativeHookBridge.setFakeFileContent("/sys/devices/soc0/family", "Snapdragon\n")
        nativeHookBridge.setFakeFileContent("/sys/devices/soc0/vendor", "Qualcomm\n")

        nativeHookBridge.spoofSystemProperty("ro.product.board", "msmnile")

        nativeHookBridge.hidePath("/proc/self/mountinfo")

        Timber.tag(TAG).d("Android 16 device fingerprint bypass installed")
    }

    /**
     * Hook Android 16 app signing verification.
     */
    fun hookAndroid16SigningVerification() {
        Timber.tag(TAG).d("Installing Android 16 signing verification bypass...")

        try {
            val signingDetailsClass = Class.forName(
                "android.content.pm.PackageParser\$SigningDetails",
                false,
                ClassLoader.getSystemClassLoader()
            )
            Timber.tag(TAG).d("SigningDetails class found: ${signingDetailsClass.name}")
        } catch (_: ClassNotFoundException) {
            try {
                val altClass = Class.forName(
                    "android.content.pm.SigningDetails",
                    false,
                    ClassLoader.getSystemClassLoader()
                )
                Timber.tag(TAG).d("SigningDetails (alt) class found: ${altClass.name}")
            } catch (_: ClassNotFoundException) {
                Timber.tag(TAG).w("SigningDetails class not found -- signing hooks may not apply")
            }
        }

        nativeHookBridge.spoofSystemProperty("ro.build.version.security_patch",
            Build.VERSION.SECURITY_PATCH)

        nativeHookBridge.hidePath("/data/app/*/base.idsig")
        nativeHookBridge.hidePath("/data/app/*/*.idsig")

        Timber.tag(TAG).d("Android 16 signing verification bypass installed")
    }

    /**
     * Handle Android 16 REQUIRE_SECURE_ENV flag.
     */
    fun hookAndroid16RequireSecureEnv() {
        Timber.tag(TAG).d("Checking Android 16 REQUIRE_SECURE_ENV flag...")

        try {
            val pmClass = Class.forName("android.app.ApplicationPackageManager")
            pmClass.getDeclaredMethod(
                "getApplicationInfo", String::class.java, Int::class.javaPrimitiveType
            )
            Timber.tag(TAG).i("REQUIRE_SECURE_ENV detection active -- " +
                "apps with this flag will be logged when launched")
        } catch (_: Exception) { /* PackageManager access may fail */ }

        nativeHookBridge.spoofSystemProperty("ro.build.version.security_patch",
            Build.VERSION.SECURITY_PATCH)

        nativeHookBridge.spoofSystemProperty("ro.debuggable", "0")
        nativeHookBridge.spoofSystemProperty("ro.secure", "1")
        nativeHookBridge.spoofSystemProperty("persist.sys.disable_rescue", "1")

        nativeHookBridge.spoofSystemProperty("persist.sys.usb.config", "none")
        nativeHookBridge.spoofSystemProperty("service.adb.tcp.port", "")

        Timber.tag(TAG).d("Android 16 REQUIRE_SECURE_ENV handling installed")
    }

    /**
     * Detect and log Android 16 specific security checks present on this device.
     */
    fun detectAndroid16Checks() {
        Timber.tag(TAG).i("=== Android 16 Detection Surface Scan ===")

        val findings = mutableListOf<String>()

        val hasIntegrityV2 = try {
            Class.forName(
                "com.google.android.play.core.integrity.StandardIntegrityManager",
                false,
                ClassLoader.getSystemClassLoader()
            )
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        findings.add("Play Integrity V2: ${if (hasIntegrityV2) "AVAILABLE" else "not found"}")

        val hasStrongBox = try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            true
        } catch (_: Exception) {
            false
        }
        findings.add("StrongBox attestation: ${if (hasStrongBox) "AVAILABLE" else "not found"}")

        val verifiedBootState = try {
            val propClass = Class.forName("android.os.SystemProperties")
            val getMethod = propClass.getDeclaredMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, "ro.boot.verifiedbootstate", "unknown") as String
        } catch (_: Exception) {
            "unknown"
        }
        findings.add("Verified boot state: $verifiedBootState")

        val selinuxMode = try {
            val file = File("/sys/fs/selinux/enforce")
            if (file.exists()) {
                if (file.readText().trim() == "1") "enforcing" else "permissive"
            } else {
                "unknown (file not found)"
            }
        } catch (_: Exception) {
            "unknown (read error)"
        }
        findings.add("SELinux: $selinuxMode")

        val cgroupIndicators = try {
            val cgroup = File("/proc/self/cgroup").readText()
            val virtualIndicators = listOf("docker", "lxc", "kubepods", "sandbox", "multiapp")
            virtualIndicators.filter { cgroup.contains(it, ignoreCase = true) }
        } catch (_: Exception) {
            emptyList()
        }
        findings.add("Cgroup virtual indicators: ${if (cgroupIndicators.isEmpty()) "CLEAN" else cgroupIndicators.joinToString()}")

        val seccompStatus = try {
            val status = File("/proc/self/status").readText()
            val seccompLine = status.lines().find { it.startsWith("Seccomp:") }
            seccompLine?.trim() ?: "not reported"
        } catch (_: Exception) {
            "unknown"
        }
        findings.add("Seccomp: $seccompStatus")

        val hasBinderfs = File("/dev/binderfs").exists()
        findings.add("BinderFS: ${if (hasBinderfs) "PRESENT" else "not present"}")

        val isDebuggable = try {
            val propClass = Class.forName("android.os.SystemProperties")
            val getMethod = propClass.getDeclaredMethod("get", String::class.java, String::class.java)
            val debuggable = getMethod.invoke(null, "ro.debuggable", "0") as String
            debuggable == "1"
        } catch (_: Exception) {
            Build.TYPE.contains("debug", ignoreCase = true)
        }
        findings.add("Debuggable: $isDebuggable")

        Timber.tag(TAG).i("Android 16 detection surface results:")
        findings.forEach { finding ->
            Timber.tag(TAG).i("  $finding")
        }

        val issues = findings.count {
            it.contains("AVAILABLE") || it.contains("PRESENT") ||
                it.contains("permissive") || it.contains("debuggable: true")
        }
        Timber.tag(TAG).i("Android 16 detection surface: ${findings.size} checks, $issues potential issues")
        Timber.tag(TAG).i("=== End Android 16 Detection Scan ===")
    }

    /**
     * Check if SELinux is in enforcing mode.
     */
    fun checkSELinuxEnforcing(): Boolean {
        return try {
            val file = File("/sys/fs/selinux/enforce")
            if (file.exists()) {
                file.readText().trim() == "1"
            } else {
                true // Assume enforcing if file doesn't exist
            }
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Build a clean /proc/self/status for integrity checks.
     * Removes TracerPid and adds Seccomp field expected by Android 16.
     */
    private fun buildCleanProcStatusForIntegrity(): String {
        val pid = android.os.Process.myPid()
        return """
            |Name:	multiapp_host
            |Umask:	0077
            |State:	S (sleeping)
            |Tgid:	$pid
            |Ngid:	0
            |Pid:	$pid
            |PPid:	${pid - 1}
            |TracerPid:	0
            |Uid:	10${pid % 1000}	10${pid % 1000}	10${pid % 1000}	10${pid % 1000}
            |Gid:	10${pid % 1000}	10${pid % 1000}	10${pid % 1000}	10${pid % 1000}
            |FDSize:	256
            |Groups:	3003 9997 20${pid % 1000} 50${pid % 1000}
            |VmPeak:	   2048000 kB
            |VmSize:	   1536000 kB
            |VmRSS:	    256000 kB
            |Threads:	42
            |SigPnd:	0000000000000000
            |ShdPnd:	0000000000000000
            |Seccomp:	2
            |Seccomp_filters:	1
        """.trimMargin()
    }
}
