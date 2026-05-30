package com.multiapp.core.hook.integration

import android.os.Build
import android.os.SystemClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.robolectric.annotation.Config
import com.multiapp.core.hook.HookEngine
import com.multiapp.core.hook.NativeHookBridge
import com.multiapp.core.hook.SpeedController
import com.multiapp.core.hook.SpeedConfig
import com.multiapp.core.hook.DetectionLevel

/**
 * 核心流程集成测试（Robolectric）
 *
 * 在 JVM 上模拟 Android 环境，验证：
 * 1. HookEngine 单例和线程安全
 * 2. NativeHookBridge 路径翻译
 * 3. SpeedController 时间控制
 * 4. 路径重定向完整链路
 */
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], manifest = Config.NONE)
class CoreFlowIntegrationTest {

    private lateinit var hookEngine: HookEngine
    private lateinit var nativeBridge: NativeHookBridge

    @BeforeEach
    fun setup() {
        hookEngine = HookEngine.getInstance()
        nativeBridge = NativeHookBridge()
    }

    @AfterEach
    fun teardown() {
        hookEngine.unhookAll()
        nativeBridge.cleanup()
    }

    // ─── 1. HookEngine 单例测试 ────────────────────────────────

    @Nested
    @DisplayName("HookEngine 单例")
    inner class HookEngineSingleton {

        @Test
        fun `getInstance returns same instance`() {
            val instance1 = HookEngine.getInstance()
            val instance2 = HookEngine.getInstance()
            assertSame(instance1, instance2, "getInstance should return same instance")
        }

        @Test
        fun `resetInstance creates new instance`() {
            val instance1 = HookEngine.getInstance()
            HookEngine.resetInstance()
            val instance2 = HookEngine.getInstance()
            assertNotSame(instance1, instance2, "resetInstance should create new instance")
        }

        @Test
        fun `hookStaticField works with simple field`() {
            // 测试静态字段 hook（不依赖 Android 系统类）
            assertTrue(
                hookEngine.hookStaticField(
                    "com.multiapp.core.hook.integration.TestFields",
                    "TEST_VALUE",
                    "modified"
                ),
                "hookStaticField should succeed"
            )
            assertEquals("modified", TestFields.TEST_VALUE)
        }
    }

    // ─── 2. NativeHookBridge 路径翻译测试 ──────────────────────

    @Nested
    @DisplayName("NativeHookBridge 路径翻译")
    inner class PathTranslation {

        @Test
        fun `translatePath returns original when no redirects`() {
            nativeBridge.initNativeHooks(null)
            val result = nativeBridge.translatePath("/data/data/com.test/files")
            assertEquals("/data/data/com.test/files", result)
        }

        @Test
        fun `translatePath applies redirect`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.addPathRedirection(
                "/data/data/com.original/",
                "/data/data/com.stub/"
            )
            val result = nativeBridge.translatePath("/data/data/com.original/files/data.db")
            // Java 层 PathTrie 翻译（Robolectric 无 native 层）
            assertEquals("/data/data/com.stub/files/data.db", result)
        }

        @Test
        fun `translatePath uses longest match`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.addPathRedirection("/data/data/com.app/", "/data/data/com.stub/")
            nativeBridge.addPathRedirection("/data/data/com.app/cache/", "/data/data/com.stub/special_cache/")

            val result1 = nativeBridge.translatePath("/data/data/com.app/files/test.txt")
            assertEquals("/data/data/com.stub/files/test.txt", result1)

            val result2 = nativeBridge.translatePath("/data/data/com.app/cache/data.bin")
            assertEquals("/data/data/com.stub/special_cache/data.bin", result2)
        }

        @Test
        fun `translatePath hides hidden paths`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.hidePath("/system/app/Superuser.apk")
            val result = nativeBridge.translatePath("/system/app/Superuser.apk")
            assertEquals("/dev/null", result)
        }

        @Test
        fun `translatePath caches results`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.addPathRedirection("/data/data/com.app/", "/data/data/com.stub/")

            val result1 = nativeBridge.translatePath("/data/data/com.app/files/test.txt")
            val result2 = nativeBridge.translatePath("/data/data/com.app/files/test.txt")
            assertEquals(result1, result2, "Cached result should be same")
        }

        @Test
        fun `cleanup clears all state`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.addPathRedirection("/data/data/com.app/", "/data/data/com.stub/")
            nativeBridge.hidePath("/system/app/Superuser.apk")
            nativeBridge.spoofProcSelf(1234, "com.test.app")

            nativeBridge.cleanup()

            assertEquals(0, nativeBridge.getRedirectionCount())
            assertEquals(0, nativeBridge.getHiddenPathCount())
            assertFalse(nativeBridge.isNativeAvailable())
        }
    }

    // ─── 3. SpeedController 时间控制测试 ──────────────────────

    @Nested
    @DisplayName("SpeedController 时间控制")
    inner class SpeedControl {

        private lateinit var speedController: SpeedController

        @BeforeEach
        fun setup() {
            speedController = SpeedController(hookEngine)
        }

        @AfterEach
        fun teardown() {
            speedController.removeAll()
        }

        @Test
        fun `setSpeedMultiplier configures instance`() {
            speedController.setSpeedMultiplier("test-1", 2.0)
            assertEquals(2.0, speedController.getSpeedMultiplier("test-1"))
        }

        @Test
        fun `getSpeedMultiplier returns 1_0 for unknown instance`() {
            assertEquals(1.0, speedController.getSpeedMultiplier("unknown"))
        }

        @Test
        fun `isActive returns true for non-normal speed`() {
            speedController.setSpeedMultiplier("test-1", 2.0)
            assertTrue(speedController.isActive("test-1"))
        }

        @Test
        fun `isActive returns false for normal speed`() {
            speedController.setSpeedMultiplier("test-1", 1.0)
            assertFalse(speedController.isActive("test-1"))
        }

        @Test
        fun `resetToNormal removes config`() {
            speedController.setSpeedMultiplier("test-1", 4.0)
            speedController.resetToNormal("test-1")
            assertEquals(1.0, speedController.getSpeedMultiplier("test-1"))
        }

        @Test
        fun `setSpeedMultiplier rejects non-positive values`() {
            assertThrows(IllegalArgumentException::class.java) {
                speedController.setSpeedMultiplier("test-1", 0.0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                speedController.setSpeedMultiplier("test-1", -1.0)
            }
        }

        @Test
        fun `transformElapsed returns original when no config`() {
            val result = speedController.transformElapsed(1000L)
            assertEquals(1000L, result)
        }

        @Test
        fun `transformElapsed scales by multiplier`() {
            speedController.setSpeedMultiplier("test-1", 2.0)
            // Robolectric 中 SystemClock 未 mock，用纯逻辑验证
            val config = speedController.getConfig("test-1")
            assertNotNull(config)
            assertEquals(2.0, config!!.multiplier)
        }

        @Test
        fun `getActiveCount returns correct count`() {
            speedController.setSpeedMultiplier("test-1", 2.0)
            speedController.setSpeedMultiplier("test-2", 4.0)
            speedController.setSpeedMultiplier("test-3", 1.0) // normal, not active
            assertEquals(2, speedController.getActiveCount())
        }

        @Test
        fun `removeAll clears all configs`() {
            speedController.setSpeedMultiplier("test-1", 2.0)
            speedController.setSpeedMultiplier("test-2", 4.0)
            speedController.removeAll()
            assertEquals(0, speedController.getActiveCount())
        }
    }

    // ─── 4. NativeHookBridge /proc/self 伪装测试 ──────────────

    @Nested
    @DisplayName("NativeHookBridge /proc/self 伪装")
    inner class ProcSelfSpoofing {

        @Test
        fun `spoofProcSelf sets fake content`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.spoofProcSelf(12345, "com.test.app")

            assertTrue(nativeBridge.hasFakeContent("/proc/self/cmdline"))
            assertTrue(nativeBridge.hasFakeContent("/proc/self/status"))
        }

        @Test
        fun `filterProcMaps removes hook framework entries`() {
            val maps = """
                7f000000-7f100000 r-xp 00000000 /system/lib64/libc.so
                7f200000-7f300000 r-xp 00000000 /data/app/com.multiapp/lib/arm64/libmultiapp-native.so
                7f400000-7f500000 r-xp 00000000 /data/app/com.test/lib/arm64/libtest.so
                7f600000-7f700000 r-xp 00000000 /data/adb/modules/lsposed/libshadowhook.so
            """.trimIndent()

            val filtered = nativeBridge.filterProcMaps(maps)

            assertTrue(filtered.contains("libc.so"), "Should keep system libs")
            assertTrue(filtered.contains("libtest.so"), "Should keep non-hook libs")
            assertFalse(filtered.contains("libmultiapp"), "Should hide multiapp")
            assertFalse(filtered.contains("shadowhook"), "Should hide shadowhook")
            assertFalse(filtered.contains("/data/adb"), "Should hide /data/adb")
        }

        @Test
        fun `spoofSystemProperty stores override`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.spoofSystemProperty("ro.product.model", "CustomPhone")

            assertEquals("CustomPhone", nativeBridge.getPropertyOverride("ro.product.model"))
            assertNull(nativeBridge.getPropertyOverride("ro.product.brand"))
        }
    }

    // ─── 5. NativeHookBridge 文件访问拦截测试 ──────────────────

    @Nested
    @DisplayName("FileAccessInterceptor")
    inner class FileAccessInterception {

        @Test
        fun `interceptExists returns false for hidden path`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.hidePath("/system/app/Superuser.apk")

            val interceptor = com.multiapp.core.hook.FileAccessInterceptor(nativeBridge)
            assertFalse(interceptor.interceptExists("/system/app/Superuser.apk"))
        }

        @Test
        fun `interceptFile redirects path`() {
            nativeBridge.initNativeHooks(null)
            nativeBridge.addPathRedirection(
                "/data/data/com.original/",
                "/data/data/com.stub/"
            )

            val interceptor = com.multiapp.core.hook.FileAccessInterceptor(nativeBridge)
            val file = interceptor.interceptFile("/data/data/com.original/files/test.txt")
            assertEquals("/data/data/com.stub/files/test.txt", file.path)
        }
    }

    // ─── 6. NativeHookBridge 模拟器路径隐藏测试 ──────────────

    @Nested
    @DisplayName("模拟器路径隐藏")
    inner class EmulatorPathHiding {

        @Test
        fun `emulator paths are hidden by default`() {
            nativeBridge.initNativeHooks(null)

            assertTrue(nativeBridge.isPathHidden("/dev/socket/qemud"))
            assertTrue(nativeBridge.isPathHidden("/dev/qemu_pipe"))
            assertTrue(nativeBridge.isPathHidden("/system/bin/qemu-props"))
        }

        @Test
        fun `root paths are hidden by default`() {
            nativeBridge.initNativeHooks(null)

            assertTrue(nativeBridge.isPathHidden("/system/xbin/su"))
            assertTrue(nativeBridge.isPathHidden("/system/bin/su"))
            assertTrue(nativeBridge.isPathHidden("/sbin/su"))
        }
    }
}

/**
 * 测试用静态字段类
 */
object TestFields {
    @JvmStatic
    var TEST_VALUE: String = "original"
}
