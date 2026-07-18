package com.multiapp.core.hook

import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class NativeHookStartupPolicyBoundaryTest {

    @Test
    fun `JNI OnLoad cannot initialize LSPlant outside the engine profile gate`() {
        val source = File(repoRoot(), NATIVE_HOOK_SOURCE).readText()
        val jniOnLoad = JNI_ON_LOAD.find(source)?.value

        assertNotNull(jniOnLoad, "JNI_OnLoad implementation must remain visible to the policy check")
        assertFalse(
            jniOnLoad.contains("nativeInitLsplant") || jniOnLoad.contains("lsplant::Init"),
            "JNI_OnLoad must not initialize LSPlant before the engine profile is known"
        )
    }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile?.absoluteFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root from $userDir")
    }

    private companion object {
        const val NATIVE_HOOK_SOURCE = "core/hook/src/main/cpp/native-hook.cpp"
        val JNI_ON_LOAD = Regex(
            """JNIEXPORT\s+jint\s+JNI_OnLoad\s*\([^)]*\)\s*\{.*?return\s+JNI_VERSION_1_6\s*;\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}
