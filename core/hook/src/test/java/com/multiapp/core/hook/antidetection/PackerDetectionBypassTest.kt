package com.multiapp.core.hook.antidetection

import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PackerDetectionBypassTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * 验证 "multiapp" 不在 HOOK_INDICATORS 中。
     * 如果 "multiapp" 被列为 hook 指示器，stub 自身的类加载会被拦截，
     * 导致 ClassNotFoundException 崩溃。
     */
    @Test
    fun `HOOK_INDICATORS must not contain multiapp`() {
        val field = PackerDetectionBypass::class.java.getDeclaredField("HOOK_INDICATORS")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val indicators = field.get(PackerDetectionBypass) as Set<String>

        assertFalse(indicators.any { it.contains("multiapp") },
            "HOOK_INDICATORS 不应包含 'multiapp'，否则 stub 自身的类加载会被拦截")
    }

    /**
     * 验证 HOOK_INDICATORS 包含已知的 hook 框架名称
     */
    @Test
    fun `HOOK_INDICATORS contains known hook framework names`() {
        val field = PackerDetectionBypass::class.java.getDeclaredField("HOOK_INDICATORS")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val indicators = field.get(PackerDetectionBypass) as Set<String>

        val expected = setOf("xposed", "lsplant", "lspatch", "substrate", "frida")
        for (name in expected) {
            assertTrue(indicators.contains(name),
                "HOOK_INDICATORS 应包含: $name")
        }
    }

    /**
     * 验证 containsHookIndicator 对 multiapp 相关类名返回 false
     */
    @Test
    fun `containsHookIndicator returns false for multiapp class names`() {
        val method = PackerDetectionBypass::class.java.getDeclaredMethod(
            "containsHookIndicator", String::class.java
        )
        method.isAccessible = true

        val multiappClasses = listOf(
            "com.multiapp.core.loader.LoaderFactory",
            "com.multiapp.core.hook.HookEngine",
            "com.multiapp.core.manifest.ManifestParser",
            "com.multiapp.app.MainActivity"
        )

        for (className in multiappClasses) {
            val result = method.invoke(PackerDetectionBypass, className) as Boolean
            assertFalse(result, "containsHookIndicator 应对 '$className' 返回 false")
        }
    }

    /**
     * 验证 containsHookIndicator 对真正的 hook 框架类名返回 true
     */
    @Test
    fun `containsHookIndicator returns true for hook framework class names`() {
        val method = PackerDetectionBypass::class.java.getDeclaredMethod(
            "containsHookIndicator", String::class.java
        )
        method.isAccessible = true

        val hookClasses = listOf(
            "de.robv.android.xposed.XposedBridge",
            "com.android.internal.os.LSPlant",
            "com.lspatch.LSPatch",
            "com.saurik.substrate.MS",
            "com.example.frida.Agent"
        )

        for (className in hookClasses) {
            val result = method.invoke(PackerDetectionBypass, className) as Boolean
            assertTrue(result, "containsHookIndicator 应对 '$className' 返回 true")
        }
    }
}
