package com.multiapp.core.loader

import com.multiapp.core.hook.NativeHookBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
    fun `bridge installer records unsupported when native library is unavailable`(@TempDir dataRoot: File) {
        val installer = NativePrivatePathRedirectInstallers.bridge(bridgeProvider = { bridge })

        val result = installer.install(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            dataRoot = dataRoot.absolutePath,
            processSlot = "com.multiapp.app:v0",
            hostContext = null
        )

        assertFalse(result.hookInstalled)
        assertEquals(2, result.ruleCount)
        assertEquals("UNSUPPORTED", result.verdict)
        assertEquals(
            File(dataRoot, "files/config.json").canonicalPath,
            File(bridge.translatePath("/data/data/com.example.app/files/config.json")).canonicalPath
        )
        val evidence = result.evidence().associate { it.key to it.value }
        assertEquals("inst-001", evidence["nativeRedirectInstanceId"])
        assertEquals("com.multiapp.app:v0", evidence["nativeRedirectProcessSlot"])
        assertEquals(dataRoot.canonicalPath, evidence["nativeRedirectDataRoot"])
        assertEquals("true", evidence["nativeRedirectProcessSlotBound"])
        assertEquals("/proc/self/maps", bridge.translatePath("/proc/self/maps"))
        assertFalse(bridge.hasFakeContent("/proc/self/cmdline"))
        assertFalse(bridge.hasFakeContent("/proc/self/status"))
    }

    @Test
    fun `same origin instances bind private redirects to distinct process slots`(@TempDir tempDir: File) {
        val installer = NativePrivatePathRedirectInstallers.bridge(bridgeProvider = { bridge })
        val originPackageName = "com.example.app"
        val firstRoot = File(tempDir, "inst-a").also { it.mkdirs() }
        val secondRoot = File(tempDir, "inst-b").also { it.mkdirs() }

        installer.install(
            instanceId = "inst-a",
            originPackageName = originPackageName,
            dataRoot = firstRoot.absolutePath,
            processSlot = "com.multiapp.app:v0",
            hostContext = null
        )
        installer.install(
            instanceId = "inst-b",
            originPackageName = originPackageName,
            dataRoot = secondRoot.absolutePath,
            processSlot = "com.multiapp.app:v1",
            hostContext = null
        )

        bridge.setNativeRedirectScope("com.multiapp.app:v0", "inst-a")
        assertEquals(
            File(firstRoot, "files/config.json").canonicalPath,
            File(bridge.translatePath("/data/data/com.example.app/files/config.json")).canonicalPath
        )

        bridge.setNativeRedirectScope("com.multiapp.app:v1", "inst-b")
        assertEquals(
            File(secondRoot, "files/config.json").canonicalPath,
            File(bridge.translatePath("/data/data/com.example.app/files/config.json")).canonicalPath
        )
    }

    @Test
    fun `bridge installer fails when input is incomplete`() {
        val installer = NativePrivatePathRedirectInstallers.bridge(bridgeProvider = { bridge })

        val result = installer.install(
            instanceId = "inst-001",
            originPackageName = "",
            dataRoot = "/sandbox/example",
            processSlot = "com.multiapp.app:v0",
            hostContext = null
        )

        assertFalse(result.hookInstalled)
        assertEquals(0, result.ruleCount)
        assertEquals("FAIL", result.verdict)
        assertEquals("PRIVATE_PATH_REDIRECT_INPUT_INCOMPLETE", result.reason)
    }

    @Test
    fun `bridge installer rejects traversal in data root`() {
        val installer = NativePrivatePathRedirectInstallers.bridge(bridgeProvider = { bridge })

        val result = installer.install(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            dataRoot = "/sandbox/../escape",
            processSlot = "com.multiapp.app:v0",
            hostContext = null
        )

        assertFalse(result.hookInstalled)
        assertEquals(0, result.ruleCount)
        assertEquals("FAIL", result.verdict)
        assertEquals("PRIVATE_PATH_REDIRECT_UNSAFE_TRAVERSAL", result.reason)
    }

    @Test
    fun `evidence marks realpath partial until device io probe verifies redirect`() {
        val result = NativePrivatePathRedirectInstallResult(
            hookInstalled = true,
            ruleCount = 2,
            reason = "PATH_HOOK_INSTALLED_NEEDS_DEVICE_IO_PROBE"
        )

        val evidence = result.evidence().associate { it.key to it.value }

        assertEquals("PARTIAL", evidence["nativePrivatePathRedirectVerdict"])
        assertEquals("PARTIAL", evidence["nativeIoRedirectVerdict"])
        assertEquals("GUEST_PRIVATE_PATHS_ONLY", evidence["nativeRedirectScope"])
        assertEquals("PARTIAL", evidence["nativeRealpathRedirectVerdict"])
        assertEquals("PATH_HOOK_INSTALLED_NEEDS_DEVICE_IO_PROBE", evidence["nativeRealpathRedirectVerdictReason"])
        assertEquals("false", evidence["procMapsSpoofEnabled"])
        assertEquals("false", evidence["procStatusSpoofEnabled"])
    }
}
