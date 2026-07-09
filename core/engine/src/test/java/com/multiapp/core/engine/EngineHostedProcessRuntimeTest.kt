package com.multiapp.core.engine

import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.toSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineHostedProcessRuntimeTest {

    @Test
    fun `reusable result wraps loader runtime result`() {
        val loaderRuntime = VirtualProcessRuntime(clock = { 1000L })
        val runtime = DefaultEngineHostedProcessRuntime(loaderRuntime)
        loaderRuntime.rememberApplication("inst-001", hostedResult("inst-001"))

        val result = runtime.reusableResult("inst-001")

        assertNotNull(result)
        assertEquals("inst-001", result.instanceId)
        assertEquals("com.example.app", result.originPackageName)
        assertEquals("com.multiapp.instance.abc123", result.virtualPackageName)
    }

    @Test
    fun `bind application runs bootstrap once and reuses cached runtime`() {
        val runtime = DefaultEngineHostedProcessRuntime(VirtualProcessRuntime(clock = { 1000L }))
        var bootstrapCount = 0

        val first = runtime.bindApplication("inst-001") {
            bootstrapCount += 1
            EngineHostedBootstrapResult.fromLoader(hostedResult("inst-001"))
        }
        val second = runtime.bindApplication("inst-001") {
            bootstrapCount += 1
            EngineHostedBootstrapResult.fromLoader(hostedResult("inst-001"))
        }

        assertTrue(first.ranBootstrapOnThisThread)
        assertFalse(second.ranBootstrapOnThisThread)
        assertEquals(1, bootstrapCount)
        assertEquals("inst-001", second.result.instanceId)
    }

    @Test
    fun `remember application stores engine result as reusable runtime`() {
        val runtime = DefaultEngineHostedProcessRuntime(VirtualProcessRuntime(clock = { 1000L }))

        runtime.rememberApplication(
            "inst-001",
            EngineHostedBootstrapResult.fromLoader(hostedResult("inst-001"))
        )

        assertNotNull(runtime.reusableResult("inst-001"))
    }

    private fun hostedResult(instanceId: String): HostedBootstrapResult =
        HostedBootstrapResult(
            instanceId = instanceId,
            installId = "com.example.app",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc123",
            originApkPath = "/tmp/base.apk",
            dataRoot = "/tmp/$instanceId",
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            guestApplication = null,
            installRecord = null,
            packageSnapshot = null,
            launcherActivityClassName = "com.example.app.MainActivity",
            stageResults = emptyList(),
            summary = emptyList<BootstrapResult>().toSummary(),
            success = true,
            diagnostics = null
        )
}
