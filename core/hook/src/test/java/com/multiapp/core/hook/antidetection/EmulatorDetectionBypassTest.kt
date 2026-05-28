package com.multiapp.core.hook.antidetection

import com.multiapp.core.hook.NativeHookBridge
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmulatorDetectionBypassTest {

    private lateinit var nativeHookBridge: NativeHookBridge
    private lateinit var bypass: EmulatorDetectionBypass

    @BeforeEach
    fun setUp() {
        nativeHookBridge = mockk(relaxed = true)
        bypass = EmulatorDetectionBypass(nativeHookBridge)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ===== EMULATOR_PATHS tests =====

    @Test
    fun `EMULATOR_PATHS contains qemu device paths`() {
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/dev/socket/qemud"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/dev/qemu_pipe"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/dev/goldfish_pipe"))
    }

    @Test
    fun `EMULATOR_PATHS contains qemu system paths`() {
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/system/lib/libc_malloc_debug_qemu.so"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/sys/qemu_trace"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/system/bin/qemu-props"))
    }

    @Test
    fun `EMULATOR_PATHS contains Genymotion paths`() {
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/dev/socket/genyd"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_PATHS.contains("/dev/socket/baseband_genyd"))
    }

    @Test
    fun `EMULATOR_PATHS has expected size`() {
        assertEquals(8, EmulatorDetectionBypass.EMULATOR_PATHS.size)
    }

    // ===== EMULATOR_BUILD_VALUES tests =====

    @Test
    fun `EMULATOR_BUILD_VALUES contains standard emulator values`() {
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("goldfish"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("ranchu"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("generic"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("ttVM_Hdragon"))
    }

    @Test
    fun `EMULATOR_BUILD_VALUES contains third-party emulator values`() {
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("nox"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("bluestacks"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("andy"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("genymotion"))
    }

    @Test
    fun `EMULATOR_BUILD_VALUES contains Google SDK values`() {
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("google_sdk"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("sdk_phone"))
        assertTrue(EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.contains("sdk_gphone"))
    }

    @Test
    fun `EMULATOR_BUILD_VALUES has expected size`() {
        assertEquals(14, EmulatorDetectionBypass.EMULATOR_BUILD_VALUES.size)
    }

    // ===== hookEmulatorChecks tests =====

    @Test
    fun `hookEmulatorChecks hides all emulator paths`() {
        bypass.hookEmulatorChecks()

        EmulatorDetectionBypass.EMULATOR_PATHS.forEach { path ->
            verify { nativeHookBridge.hidePath(path) }
        }
    }

    @Test
    fun `hookEmulatorChecks spoofs system properties`() {
        bypass.hookEmulatorChecks()

        verify { nativeHookBridge.spoofSystemProperty("ro.hardware.chipname", "exynos990") }
        verify { nativeHookBridge.spoofSystemProperty("ro.kernel.qemu", "0") }
        verify { nativeHookBridge.spoofSystemProperty("ro.product.device", "beyond1") }
        verify { nativeHookBridge.spoofSystemProperty("ro.hardware", "qcom") }
        verify { nativeHookBridge.spoofSystemProperty("ro.bootloader", "unknown") }
    }

    @Test
    fun `hookEmulatorChecks spoofs telephony properties`() {
        bypass.hookEmulatorChecks()

        verify { nativeHookBridge.spoofSystemProperty("gsm.version.ril-impl", "qualcomm-ril 1.0") }
        verify { nativeHookBridge.spoofSystemProperty("ro.radio.use-ppp", "no") }
    }

    @Test
    fun `hookEmulatorChecks does not crash on multiple invocations`() {
        bypass.hookEmulatorChecks()
        bypass.hookEmulatorChecks()

        verify(exactly = 2) { nativeHookBridge.hidePath("/dev/qemu_pipe") }
    }

    // ===== verifyBuildFieldNotEmulator tests =====

    @Test
    fun `verifyBuildFieldNotEmulator handles nonexistent field gracefully`() {
        // Should not throw for nonexistent field
        bypass.verifyBuildFieldNotEmulator(String::class.java, "NONEXISTENT_FIELD_XYZ")
    }
}
