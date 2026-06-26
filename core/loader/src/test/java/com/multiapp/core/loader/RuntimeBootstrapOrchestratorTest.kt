package com.multiapp.core.loader

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeBootstrapOrchestratorTest {

    private fun ev(key: String) = BootstrapEvidence(key, "true")

    private fun context(extras: Map<String, String> = emptyMap()) = RuntimeBootstrapContext(
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
        guestClassLoaderName = "dalvik.system.PathClassLoader",
        extras = extras
    )

    private class CapturingEvidenceSink : EvidenceSink {
        val results = mutableListOf<BootstrapResult>()
        override fun emit(result: BootstrapResult): BootstrapResult {
            results.add(result)
            return result
        }
    }

    // ── begin() ───────────────────────────────────────────────────────

    @Test
    fun `begin creates session with correct context and plan`() {
        val ctx = context(extras = mapOf("slot" to "0"))
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible(createdAtMs = 10L)
        val bootstrap = RuntimeBootstrap(plan = plan, evidenceSink = NoopEvidenceSink, clock = { 99L })
        val session = bootstrap.begin(ctx)
        assertEquals(ctx, session.context)
        assertEquals(plan, session.plan)
        assertEquals(null, session.currentStage)
        assertEquals("main", session.context.threadName)
        assertEquals(mapOf("slot" to "0"), session.context.extras)
    }

    // ── session enter ─────────────────────────────────────────────────

    @Test
    fun `session enter returns a new session with current stage`() {
        val session = RuntimeBootstrap(
            plan = RuntimeBootstrapPlan.loaderFactoryCompatible(),
            evidenceSink = NoopEvidenceSink,
            clock = { 1L }
        ).begin(context())
        val entered = session.enter(RuntimeStage.CONFIG)
        assertNotSame(session, entered)
        assertEquals(null, session.currentStage)
        assertEquals(RuntimeStage.CONFIG, entered.currentStage)
    }

    // ── record() ──────────────────────────────────────────────────────

    @Test
    fun `record emits explicit result to evidence sink`() {
        val sink = CapturingEvidenceSink()
        val result = BootstrapResult.degraded(RuntimeStage.NATIVE_LIBS, "fallback", evidence = listOf(ev("lib")), durationMs = 5L)
        val bootstrap = RuntimeBootstrap(RuntimeBootstrapPlan.loaderFactoryCompatible(), sink, clock = { 1L })
        val emitted = bootstrap.record(result)
        assertSame(result, emitted)
        assertEquals(listOf(result), sink.results)
    }

    // ── runStage success ──────────────────────────────────────────────

    @Test
    fun `runStage returns block value and records success with message evidence and duration`() {
        val sink = CapturingEvidenceSink()
        var fakeMs = 1000L
        val clock = { fakeMs }
        val bootstrap = RuntimeBootstrap(RuntimeBootstrapPlan.loaderFactoryCompatible(), sink, clock = clock)
        fakeMs = 1000L
        val value = bootstrap.runStage(RuntimeStage.CONFIG, "config loaded", listOf(ev("asset"))) {
            fakeMs = 1050L
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(1, sink.results.size)
        assertEquals(BootstrapStatus.SUCCESS, sink.results[0].status)
        assertEquals(RuntimeStage.CONFIG, sink.results[0].stage)
        assertEquals("config loaded", sink.results[0].message)
        assertEquals(50L, sink.results[0].durationMs)
    }

    // ── runStage failure ──────────────────────────────────────────────

    @Test
    fun `runStage records failed with exception message then rethrows original exception`() {
        val sink = CapturingEvidenceSink()
        var fakeMs = 2000L
        val clock = { fakeMs }
        val bootstrap = RuntimeBootstrap(RuntimeBootstrapPlan.loaderFactoryCompatible(), sink, clock = clock)
        val failure = IllegalStateException("origin missing")
        fakeMs = 2000L
        val thrown = assertFailsWith<IllegalStateException> {
            bootstrap.runStage(RuntimeStage.ORIGIN_APK, "extract origin", listOf(ev("assets/origin.apk"))) {
                fakeMs = 2075L
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertEquals(1, sink.results.size)
        assertEquals(BootstrapStatus.FAILED, sink.results[0].status)
        assertEquals(RuntimeStage.ORIGIN_APK, sink.results[0].stage)
        assertEquals("extract origin", sink.results[0].message)
        assertEquals(75L, sink.results[0].durationMs)
    }

    // ── session helper methods ────────────────────────────────────────

    @Test
    fun `session helper methods emit status results for current stage`() {
        val sink = CapturingEvidenceSink()
        val session = RuntimeBootstrap(RuntimeBootstrapPlan.loaderFactoryCompatible(), sink, clock = { 11L })
            .begin(context())
            .enter(RuntimeStage.RESOURCES)
        session.success("resources ready", listOf(ev("res.apk")))
        session.failed("resources missing", listOf(ev("res.apk")))
        session.degraded("using origin resources", listOf(ev("origin.apk")))
        session.skipped("not applicable")
        assertEquals(
            listOf(
                BootstrapResult.success(RuntimeStage.RESOURCES, "resources ready", listOf(ev("res.apk")), 11L),
                BootstrapResult.failed(RuntimeStage.RESOURCES, "resources missing", evidence = listOf(ev("res.apk")), durationMs = 11L),
                BootstrapResult.degraded(RuntimeStage.RESOURCES, "using origin resources", evidence = listOf(ev("origin.apk")), durationMs = 11L),
                BootstrapResult.skipped(RuntimeStage.RESOURCES, "not applicable")
            ),
            sink.results
        )
    }

    // ── NoopEvidenceSink integration ──────────────────────────────────

    @Test
    fun `orchestrator works with NoopEvidenceSink`() {
        val bootstrap = RuntimeBootstrap(RuntimeBootstrapPlan.loaderFactoryCompatible(), NoopEvidenceSink)
        val result = bootstrap.runStage(RuntimeStage.CONFIG, "test") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `orchestrator with NoopEvidenceSink still rethrows exceptions`() {
        val bootstrap = RuntimeBootstrap(RuntimeBootstrapPlan.loaderFactoryCompatible(), NoopEvidenceSink)
        assertFailsWith<RuntimeException> {
            bootstrap.runStage(RuntimeStage.NATIVE_LIBS, "fail") {
                throw RuntimeException("boom")
            }
        }
    }

    // ── plan access ───────────────────────────────────────────────────

    @Test
    fun `orchestrator exposes plan`() {
        val plan = RuntimeBootstrapPlan.loaderFactoryCompatible()
        val bootstrap = RuntimeBootstrap(plan, NoopEvidenceSink)
        assertSame(plan, bootstrap.plan)
    }
}
