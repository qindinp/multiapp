package com.multiapp.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class EngineActivityLaunchCommitIpcContractTest {
    @Test
    fun `runtime Binder contract exposes engine-owned launch preparation before Android dispatch`() {
        val aidl = File(
            repoRoot(),
            "core/engine/src/main/aidl/com/multiapp/core/engine/ipc/IEngineRuntimeService.aidl"
        ).readText()

        assertTrue(
            aidl.contains("Bundle prepareActivityLaunch(String instanceId, in Bundle request);"),
            "Activity launch preparation must be engine-owned before the Proxy Intent is dispatched"
        )
    }

    @Test
    fun `runtime Binder contract exposes a single-record Activity launch commit operation`() {
        val aidl = File(
            repoRoot(),
            "core/engine/src/main/aidl/com/multiapp/core/engine/ipc/IEngineRuntimeService.aidl"
        ).readText()

        assertTrue(
            aidl.contains("Bundle commitActivityLaunch(String instanceId, in Bundle request);"),
            "Activity launch commit must use a Bundle schema instead of a mutable task snapshot"
        )
    }

    @Test
    fun `guest start commits the engine activity record before Android receives the proxy intent`() {
        val source = File(
            repoRoot(),
            "core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt"
        ).readText()

        val commit = source.indexOf("allocationProvider.commit(")
        val dispatchIntent = source.indexOf("manager.createProxyIntent(")
        assertTrue(commit >= 0, "Guest Activity remap must commit its record to the engine")
        assertTrue(
            commit < dispatchIntent,
            "Engine Activity record must exist before the Proxy Intent can reach Android"
        )
    }

    @Test
    fun `framework ActivityResultItem is observed without synthetic ActivityThread result dispatch`() {
        val instrumentation = File(
            repoRoot(),
            "core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt"
        ).readText()
        val callbackInstaller = File(
            repoRoot(),
            "core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadLaunchCallbackInstaller.kt"
        ).readText()

        assertTrue(
            !instrumentation.contains("dispatchActivityResultThroughActivityThread(recorded, result)"),
            "The host framework already owns result delivery; the container must not enqueue a duplicate"
        )
        assertTrue(
            !instrumentation.contains("dispatchPendingActivityResultBeforeResume(activity)"),
            "A resume fallback must not consume a result before the ActivityResultItem observer"
        )
        assertTrue(
            callbackInstaller.contains("captureActivityResults(msg.obj)") &&
                callbackInstaller.contains("completeActivityResultDelivery"),
            "Activity result evidence must follow the real ActivityResultItem transaction"
        )
    }

    @Test
    fun `activity result baseline is written before guest onCreate can finish synchronously`() {
        val source = File(
            repoRoot(),
            "core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt"
        ).readText()
        val createStart = source.indexOf("override fun callActivityOnCreate(activity: Activity, icicle: Bundle?)")
        val createEnd = source.indexOf("override fun callActivityOnCreate(", createStart + 1)
        val createBody = source.substring(createStart, createEnd)

        assertTrue(
            createBody.indexOf("ensureActivityResultBaselineEvidence(activity)") <
                createBody.indexOf("base.callActivityOnCreate(activity, icicle)"),
            "A guest that finishes inside onCreate must append to, not be overwritten by, baseline evidence"
        )
    }

    private fun repoRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir is unavailable")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile?.absoluteFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Unable to locate repository root")
    }
}
