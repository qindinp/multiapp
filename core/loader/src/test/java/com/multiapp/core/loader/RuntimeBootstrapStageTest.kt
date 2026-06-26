package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuntimeBootstrapStageTest {

    private fun ev(key: String) = BootstrapEvidence(key, "true")

    private fun context() = RuntimeBootstrapContext(
        entryClassLoader = ClassLoader.getSystemClassLoader(),
        processName = "com.example.app",
        threadName = "main",
        startedAtMs = 1L,
        stubApkPath = "/data/app/stub/base.apk",
        dataDir = "/data/user/0/stub",
        stubPackageName = "com.example.stub",
        originalPackageName = "com.example.app",
        cloneProfile = "default",
        originApkPath = "/data/user/0/stub/cache/origin/base.apk",
        originalApkPath = "/data/app/original/base.apk",
        resourceApkPath = "/data/user/0/stub/cache/res/base.apk",
        originNativeLibDir = "/data/user/0/stub/lib",
        guestClassLoaderName = "dalvik.system.PathClassLoader"
    )

    private class CapturingEvidenceSink : EvidenceSink {
        val results = mutableListOf<BootstrapResult>()
        override fun emit(result: BootstrapResult): BootstrapResult {
            results.add(result)
            return result
        }
    }

    @Test
    fun `session enter updates current stage`() {
        val sink = CapturingEvidenceSink()
        val session = RuntimeBootstrap(
            plan = RuntimeBootstrapPlan.loaderFactoryCompatible(),
            evidenceSink = sink,
            clock = { 100L }
        ).begin(context())

        assertNull(session.currentStage)

        val configSession = session.enter(RuntimeStage.CONFIG)
        assertEquals(RuntimeStage.CONFIG, configSession.currentStage)

        val guestSession = configSession.enter(RuntimeStage.GUEST_CONTEXT)
        assertEquals(RuntimeStage.GUEST_CONTEXT, guestSession.currentStage)
    }

    @Test
    fun `session success emits SUCCESS for current stage`() {
        val sink = CapturingEvidenceSink()
        val session = RuntimeBootstrap(
            plan = RuntimeBootstrapPlan.loaderFactoryCompatible(),
            evidenceSink = sink,
            clock = { 50L }
        ).begin(context()).enter(RuntimeStage.CONFIG)

        session.success("config loaded", listOf(ev("key1")))

        assertEquals(1, sink.results.size)
        val result = sink.results[0]
        assertEquals(BootstrapStatus.SUCCESS, result.status)
        assertEquals(RuntimeStage.CONFIG, result.stage)
        assertEquals("config loaded", result.message)
        assertEquals(1, result.evidence.size)
    }

    @Test
    fun `session failed emits FAILED with error details`() {
        val sink = CapturingEvidenceSink()
        val session = RuntimeBootstrap(
            plan = RuntimeBootstrapPlan.loaderFactoryCompatible(),
            evidenceSink = sink,
            clock = { 50L }
        ).begin(context()).enter(RuntimeStage.ORIGIN_APK)

        val error = IllegalStateException("origin missing")
        session.failed("extraction failed", listOf(ev("path")), error, "rollback note")

        assertEquals(1, sink.results.size)
        val result = sink.results[0]
        assertEquals(BootstrapStatus.FAILED, result.status)
        assertEquals(RuntimeStage.ORIGIN_APK, result.stage)
        assertEquals("extraction failed", result.message)
        assertEquals(IllegalStateException::class.java.name, result.errorClass)
        assertEquals("origin missing", result.errorMessage)
        assertEquals("rollback note", result.rollbackNote)
    }

    @Test
    fun `session degraded emits DEGRADED for current stage`() {
        val sink = CapturingEvidenceSink()
        val session = RuntimeBootstrap(
            plan = RuntimeBootstrapPlan.loaderFactoryCompatible(),
            evidenceSink = sink,
            clock = { 50L }
        ).begin(context()).enter(RuntimeStage.RESOURCES)

        val error = IllegalArgumentException("fallback")
        session.degraded("using fallback", listOf(ev("res")), error)

        assertEquals(1, sink.results.size)
        val result = sink.results[0]
        assertEquals(BootstrapStatus.DEGRADED, result.status)
        assertEquals(RuntimeStage.RESOURCES, result.stage)
        assertEquals("using fallback", result.message)
        assertEquals(IllegalArgumentException::class.java.name, result.errorClass)
    }

    @Test
    fun `session success without current stage throws`() {
        val session = RuntimeBootstrap(
            plan = RuntimeBootstrapPlan.loaderFactoryCompatible(),
            evidenceSink = NoopEvidenceSink,
            clock = { 50L }
        ).begin(context())

        assertFailsWith<IllegalArgumentException> {
            session.success("should fail")
        }
    }

    @Test
    fun `session failed without current stage throws`() {
        val session = RuntimeBootstrap(
            plan = RuntimeBootstrapPlan.loaderFactoryCompatible(),
            evidenceSink = NoopEvidenceSink,
            clock = { 50L }
        ).begin(context())

        assertFailsWith<IllegalArgumentException> {
            session.failed("should fail")
        }
    }
}
