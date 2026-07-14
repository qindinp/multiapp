package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class HostedRuntimeEngineProcessViewTest {

    @Test
    fun `primary runtime keeps authoritative slot and resolves application guest process`() {
        val runtime = runtime()

        val view = runtime.deriveHostedRuntimeView(PRIMARY_SLOT, null)

        assertSame(runtime, view?.runtime)
        assertEquals("com.example.app:main", view?.effectiveGuestProcessName)
        assertNull(runtime.deriveHostedRuntimeView(REMOTE_SLOT, null))
    }

    @Test
    fun `declared custom process derives isolated physical slot view`() {
        val runtime = runtime()

        val view = runtime.deriveHostedRuntimeView(REMOTE_SLOT, ":remote")

        assertEquals(REMOTE_SLOT, view?.runtime?.processSlot)
        assertEquals(REMOTE_SLOT, view?.runtime?.processName)
        assertNull(view?.runtime?.processId)
        assertSame(runtime.packageSnapshot, view?.runtime?.packageSnapshot)
        assertEquals("com.example.app:remote", view?.effectiveGuestProcessName)
        assertEquals(PRIMARY_SLOT, runtime.processSlot)
        assertEquals(101, runtime.processId)
    }

    @Test
    fun `undeclared or primary-slot custom process is rejected`() {
        val runtime = runtime()

        assertNull(runtime.deriveHostedRuntimeView(REMOTE_SLOT, "com.example.app:missing"))
        assertNull(runtime.deriveHostedRuntimeView(PRIMARY_SLOT, "com.example.app:remote"))
        assertNull(runtime.deriveHostedRuntimeView(null, "com.example.app:remote"))
    }

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = "inst-001",
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.inst001",
        dataRoot = "/data/user/0/com.multiapp.app/files/instances/inst-001/data",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.inst001",
            applicationLabel = "Example",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 36,
            minSdk = 28,
            sourceDir = "/data/apks/example.apk",
            dataDir = "/data/user/0/com.multiapp.app/files/instances/inst-001/data",
            processName = ":main",
            activities = listOf(
                ResolvedComponent(
                    name = "com.example.app.RemoteActivity",
                    processName = ":remote"
                )
            ),
            services = listOf(
                ResolvedComponent(
                    name = "com.example.app.SyncService",
                    processName = "com.example.app:sync"
                )
            )
        ),
        profile = EngineProfile.BASELINE,
        processSlot = PRIMARY_SLOT,
        proxySlot = "proxy-0",
        evidenceSessionId = "evidence-001",
        runtimeEpoch = 3L,
        engineSessionId = "engine-session-001",
        processId = 101,
        processName = PRIMARY_SLOT,
        state = VirtualRuntimeState.RUNNING
    )

    private companion object {
        const val PRIMARY_SLOT = "com.multiapp.app:v0"
        const val REMOTE_SLOT = "com.multiapp.app:v1"
    }
}
