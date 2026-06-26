package com.multiapp.core.hook

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Jiagu360ProfileTest {

    private val profile = Jiagu360Profile()

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

    @Test
    fun `verify succeeds only with loaded lib and original shell register natives`() {
        val result = profile.verify(
            Jiagu360VerifyContext(
                loadedLibPaths = listOf("/data/data/stub/cache/origin/lib/arm64-v8a/libjiagu_vip.so"),
                registerNativesEvents = listOf(
                    RegisterNativesEvidence(
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
                    RegisterNativesEvidence(
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
        assertTrue(result.missing.any { it.contains("original StubApp RegisterNatives") })
    }

    @Test
    fun `verify treats interface20 unsatisfied link as incomplete`() {
        val result = profile.verify(
            Jiagu360VerifyContext(
                loadedLibPaths = listOf("/data/data/stub/cache/origin/lib/arm64-v8a/libjiagu_vip.so"),
                registerNativesEvents = listOf(
                    RegisterNativesEvidence(
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
}
