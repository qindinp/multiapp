package com.multiapp.core.loader

import com.multiapp.core.hook.NativeHookBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativePrivatePathRedirectInstallerTest {

    private lateinit var bridge: NativeHookBridge

    @BeforeEach
    fun setUp() {
        bridge = NativeHookBridge()
    }

    @AfterEach
    fun tearDown() {
        bridge.cleanup()
    }

    @Test
    fun `bridge installer records unsupported when native library is unavailable`() {
        val installer = NativePrivatePathRedirectInstallers.bridge(bridgeProvider = { bridge })

        val result = installer.install(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            dataRoot = "/sandbox/example",
            hostContext = null
        )

        assertFalse(result.hookInstalled)
        assertEquals(2, result.ruleCount)
        assertEquals("UNSUPPORTED", result.verdict)
        assertEquals(
            "/sandbox/example/files/config.json",
            bridge.translatePath("/data/data/com.example.app/files/config.json")
        )
        assertEquals("/proc/self/maps", bridge.translatePath("/proc/self/maps"))
        assertFalse(bridge.hasFakeContent("/proc/self/cmdline"))
        assertFalse(bridge.hasFakeContent("/proc/self/status"))
    }

    @Test
    fun `bridge installer fails when input is incomplete`() {
        val installer = NativePrivatePathRedirectInstallers.bridge(bridgeProvider = { bridge })

        val result = installer.install(
            instanceId = "inst-001",
            originPackageName = "",
            dataRoot = "/sandbox/example",
            hostContext = null
        )

        assertFalse(result.hookInstalled)
        assertEquals(0, result.ruleCount)
        assertEquals("FAIL", result.verdict)
        assertEquals("PRIVATE_PATH_REDIRECT_INPUT_INCOMPLETE", result.reason)
    }

    @Test
    fun `evidence keeps realpath unsupported until native realpath hook exists`() {
        val result = NativePrivatePathRedirectInstallResult(
            hookInstalled = true,
            ruleCount = 2,
            reason = "PATH_HOOK_INSTALLED_NEEDS_DEVICE_IO_PROBE"
        )

        val evidence = result.evidence().associate { it.key to it.value }

        assertEquals("PARTIAL", evidence["nativePrivatePathRedirectVerdict"])
        assertEquals("PARTIAL", evidence["nativeIoRedirectVerdict"])
        assertEquals("GUEST_PRIVATE_PATHS_ONLY", evidence["nativeRedirectScope"])
        assertEquals("UNSUPPORTED", evidence["nativeRealpathRedirectVerdict"])
        assertEquals("false", evidence["procMapsSpoofEnabled"])
        assertEquals("false", evidence["procStatusSpoofEnabled"])
    }
}
