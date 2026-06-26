package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Jiagu360ProfileTest {

    private val profile = Jiagu360Profile()

    // ===== detect =====

    @Test
    fun `detect matches jiagu native lib and stub class`() {
        val result = profile.detect(
            Jiagu360DetectContext(
                packageName = "com.qq.reader",
                nativeLibPaths = listOf("lib/arm64-v8a/libjiagu_vip.so"),
                classNames = setOf("com.stub.StubApp")
            )
        )

        assertEquals(Jiagu360ProfileStatus.MATCH, result.status)
        assertTrue(result.matched)
        assertTrue(result.evidence.any { it.key == "nativeLib" })
        assertTrue(result.evidence.any { it.key == "shellClass" })
    }

    @Test
    fun `detect reports missing evidence when no jiagu signal exists`() {
        val result = profile.detect(
            Jiagu360DetectContext(
                nativeLibPaths = listOf("lib/arm64-v8a/libfoo.so"),
                classNames = setOf("com.example.App")
            )
        )

        assertEquals(Jiagu360ProfileStatus.NOT_MATCH, result.status)
        assertFalse(result.matched)
        assertTrue(result.missing.contains("libjiagu*.so"))
    }

    // ===== verify with explicit originalShellPath (backward compat) =====

    @Test
    fun `verify succeeds only with loaded lib and original shell register natives`() {
        val result = profile.verify(
            Jiagu360VerifyContext(
                loadedLibPaths = listOf("/data/data/stub/cache/origin/lib/arm64-v8a/libjiagu_vip.so"),
                registerNativesEvents = listOf(
                    RegisterNativesEvidence.withExplicitOriginalShellPath(
                        className = "com.stub.StubApp",
                        methodCount = 10,
                        result = 0,
                        source = "RegisterNatives",
                        originalShellPath = true
                    )
                ),
                sourceDir = "/data/app/com.qq.reader/base.apk",
                nativeLibraryDir = "/data/data/stub/cache/origin/lib/arm64-v8a",
                dataDir = "/data/data/com.qq.reader"
            )
        )

        assertEquals(Jiagu360ProfileStatus.VERIFIED, result.status)
        assertTrue(result.verified)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun `verify rejects fallback register natives evidence`() {
        val result = profile.verify(
            Jiagu360VerifyContext(
                loadedLibPaths = listOf("/data/data/stub/cache/origin/lib/arm64-v8a/libjiagu_vip.so"),
                registerNativesEvents = listOf(
                    RegisterNativesEvidence.withExplicitOriginalShellPath(
                        className = "com.stub.StubApp",
                        methodCount = 10,
                        result = 0,
                        source = "MultiApp fallback",
                        originalShellPath = false
                    )
                )
            )
        )

        assertEquals(Jiagu360ProfileStatus.INCOMPLETE, result.status)
        assertFalse(result.verified)
        assertTrue(result.missing.any { it.contains("original shell StubApp RegisterNatives") })
    }

    @Test
    fun `verify treats interface20 unsatisfied link as incomplete`() {
        val result = profile.verify(
            Jiagu360VerifyContext(
                loadedLibPaths = listOf("/data/data/stub/cache/origin/lib/arm64-v8a/libjiagu_vip.so"),
                registerNativesEvents = listOf(
                    RegisterNativesEvidence.withExplicitOriginalShellPath(
                        className = "com.stub.StubApp",
                        methodCount = 10,
                        result = 0,
                        source = "RegisterNatives",
                        originalShellPath = true
                    )
                ),
                errors = listOf(
                    "java.lang.UnsatisfiedLinkError: No implementation found for boolean com.stub.StubApp.interface20()"
                )
            )
        )

        assertEquals(Jiagu360ProfileStatus.INCOMPLETE, result.status)
        assertTrue(result.missing.any { it.contains("interface20") })
    }

    // ===== verify with computed originalShellPath =====

    @Test
    fun `verify accepts qihoo StubApp with computed originalShellPath`() {
        val result = profile.verify(
            Jiagu360VerifyContext(
                loadedLibPaths = listOf("/data/data/stub/lib/arm64-v8a/libjiagu_vip.so"),
                registerNativesEvents = listOf(
                    RegisterNativesEvidence(
                        className = "com.qihoo.util.StubApp",
                        methodCount = 15,
                        result = 0,
                        source = "native:RegisterNatives",
                        callerIsJiagu = true,
                        hasInterface11 = true,
                        hasInterface20 = true
                    )
                )
            )
        )

        assertEquals(Jiagu360ProfileStatus.VERIFIED, result.status)
        assertTrue(result.verified)
    }

    @Test
    fun `verify rejects jiagu caller when interface11 missing`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 15,
            result = 0,
            callerIsJiagu = true,
            hasInterface11 = false,
            hasInterface20 = true
        )
        assertFalse(evidence.originalShellPath)
    }

    @Test
    fun `verify rejects jiagu caller when interface20 missing`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 15,
            result = 0,
            callerIsJiagu = true,
            hasInterface11 = true,
            hasInterface20 = false
        )
        assertFalse(evidence.originalShellPath)
    }

    @Test
    fun `verify rejects non zero RegisterNatives result`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 15,
            result = -1,
            callerIsJiagu = true,
            hasInterface11 = true,
            hasInterface20 = true
        )
        assertFalse(evidence.originalShellPath)
    }

    @Test
    fun `verify rejects small method count`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 5,
            result = 0,
            callerIsJiagu = true,
            hasInterface11 = true,
            hasInterface20 = true
        )
        assertFalse(evidence.originalShellPath)
    }

    @Test
    fun `verify rejects all multiapp methods even if method count is enough`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 15,
            result = 0,
            callerIsJiagu = true,
            allMultiAppMethods = true,
            hasInterface11 = true,
            hasInterface20 = true
        )
        assertFalse(evidence.originalShellPath)
    }

    @Test
    fun `originalShellPath true when all conditions met`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 15,
            result = 0,
            callerIsJiagu = true,
            allMultiAppMethods = false,
            hasInterface11 = true,
            hasInterface20 = true
        )
        assertTrue(evidence.originalShellPath)
    }

    @Test
    fun `originalShellPath true when methodCount exactly 10`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 10,
            result = 0,
            callerIsJiagu = true,
            hasInterface11 = true,
            hasInterface20 = true
        )
        assertTrue(evidence.originalShellPath)
    }

    @Test
    fun `originalShellPath false when callerIsJiagu is false`() {
        val evidence = RegisterNativesEvidence(
            className = "com.stub.StubApp",
            methodCount = 15,
            result = 0,
            callerIsJiagu = false,
            hasInterface11 = true,
            hasInterface20 = true
        )
        assertFalse(evidence.originalShellPath)
    }

    @Test
    fun `defaults all booleans to false`() {
        val evidence = RegisterNativesEvidence(
            className = "com/test/Class",
            methodCount = 5,
            result = 0
        )
        assertFalse(evidence.callerIsJiagu)
        assertFalse(evidence.allMultiAppMethods)
        assertFalse(evidence.hasInterface11)
        assertFalse(evidence.hasInterface20)
        assertFalse(evidence.jiaguComplete)
        assertFalse(evidence.originalShellPath)
        assertEquals("", evidence.source)
    }
}
