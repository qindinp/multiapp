package com.multiapp.core.hook.antidetection

import android.os.Build
import com.multiapp.core.common.removeFinalModifier
import com.multiapp.core.hook.NativeHookBridge
import timber.log.Timber

/**
 * Emulator detection bypass -- spoofs Build properties,
 * hides qemu files, and neutralises emulator-specific indicators.
 */
class EmulatorDetectionBypass(
    private val nativeHookBridge: NativeHookBridge
) {
    companion object {
        private const val TAG = "AntiDetect"

        // Emulator-related paths
        internal val EMULATOR_PATHS = setOf(
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props",
            "/dev/socket/genyd",
            "/dev/socket/baseband_genyd",
            "/dev/goldfish_pipe"
        )

        // Emulator-related Build values
        internal val EMULATOR_BUILD_VALUES = setOf(
            "goldfish", "ranchu", "generic", "sdk", "vbox", "genymotion",
            "nox", "bluestacks", "andy", "ttVM_Hdragon", "google_sdk",
            "Droid4X", "sdk_phone", "sdk_gphone"
        )
    }

    /**
     * Hide emulator-related indicators:
     * - Build fields (goldfish, ranchu, sdk, etc.)
     * - /dev/qemu_pipe, /dev/socket/qemud -> hidden
     * - Emulator-specific system properties -> spoofed
     */
    fun hookEmulatorChecks() {
        Timber.tag(TAG).d("Installing emulator detection bypass...")

        // 1. Hide emulator-specific files
        for (path in EMULATOR_PATHS) {
            nativeHookBridge.hidePath(path)
        }

        // 2. Spoof emulator-related system properties
        nativeHookBridge.spoofSystemProperty("ro.hardware.chipname", "exynos990")
        nativeHookBridge.spoofSystemProperty("ro.kernel.qemu", "0")
        nativeHookBridge.spoofSystemProperty("ro.product.device", "beyond1")
        nativeHookBridge.spoofSystemProperty("ro.hardware", "qcom")
        nativeHookBridge.spoofSystemProperty("init.svc.qemud", "")
        nativeHookBridge.spoofSystemProperty("ro.kernel.android.qemud", "")
        nativeHookBridge.spoofSystemProperty("qemu.hw.mainkeys", "")
        nativeHookBridge.spoofSystemProperty("qemu.sf.lcd_density", "")
        nativeHookBridge.spoofSystemProperty("ro.bootloader", "unknown")
        nativeHookBridge.spoofSystemProperty("ro.bootimage.build.fingerprint", Build.FINGERPRINT ?: "unknown")

        // 3. Ensure Build fields don't contain emulator indicators
        val buildClass = Build::class.java
        verifyBuildFieldNotEmulator(buildClass, "HARDWARE")
        verifyBuildFieldNotEmulator(buildClass, "PRODUCT")
        verifyBuildFieldNotEmulator(buildClass, "MODEL")
        verifyBuildFieldNotEmulator(buildClass, "MANUFACTURER")
        verifyBuildFieldNotEmulator(buildClass, "BRAND")
        verifyBuildFieldNotEmulator(buildClass, "DEVICE")
        verifyBuildFieldNotEmulator(buildClass, "FINGERPRINT")
        verifyBuildFieldNotEmulator(buildClass, "BOARD")

        // 4. Hide /proc/tty/drivers (Genymotion detection)
        nativeHookBridge.hidePath("/proc/tty/drivers")

        // 5. Spoof telephony-related properties
        nativeHookBridge.spoofSystemProperty("gsm.version.ril-impl", "qualcomm-ril 1.0")
        nativeHookBridge.spoofSystemProperty("ro.radio.use-ppp", "no")

        Timber.tag(TAG).d("Emulator detection bypass installed: ${EMULATOR_PATHS.size} paths hidden")
    }

    internal fun verifyBuildFieldNotEmulator(buildClass: Class<*>, fieldName: String) {
        try {
            val field = buildClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val value = field.get(null) as? String ?: return

            val isEmulatorValue = EMULATOR_BUILD_VALUES.any { indicator ->
                value.contains(indicator, ignoreCase = true)
            }

            if (isEmulatorValue) {
                Timber.tag(TAG).w("Build.$fieldName contains emulator indicator: $value")
                field.isAccessible = true
                removeFinalModifier(field)
                // Keep existing value -- IdentitySpoofingEngine handles replacement
            }
        } catch (_: Exception) { /* Field access failed */ }
    }
}
