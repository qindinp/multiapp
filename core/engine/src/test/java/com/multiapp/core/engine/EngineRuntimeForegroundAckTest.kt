package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRuntimeForegroundAckTest {
    @Test
    fun `matching guest resume advances PREWARMED runtime to RUNNING`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())

        val result = registry.acknowledgeActivityResumed(
            instanceId = INSTANCE_ID,
            runtimeEpoch = EPOCH,
            engineSessionId = SESSION_ID,
            processSlot = PROCESS_SLOT,
            callingPid = PROCESS_ID
        )

        assertTrue(result.accepted)
        assertFalse(result.idempotent)
        assertEquals(VirtualRuntimeState.RUNNING, result.state)
        assertEquals(VirtualRuntimeState.RUNNING, registry.get(INSTANCE_ID)?.state)
    }

    @Test
    fun `repeated matching guest resume is idempotent`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())
        registry.acknowledgeActivityResumed(
            INSTANCE_ID,
            EPOCH,
            SESSION_ID,
            PROCESS_SLOT,
            PROCESS_ID
        )

        val repeated = registry.acknowledgeActivityResumed(
            INSTANCE_ID,
            EPOCH,
            SESSION_ID,
            PROCESS_SLOT,
            PROCESS_ID
        )

        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals("already_running", repeated.reason)
    }

    @Test
    fun `stale session and wrong pid cannot mark runtime RUNNING`() {
        val registry = EngineRuntimeRegistry()
        registry.register(runtime())

        val stale = registry.acknowledgeActivityResumed(
            INSTANCE_ID,
            EPOCH,
            "old-session",
            PROCESS_SLOT,
            PROCESS_ID
        )
        val wrongPid = registry.acknowledgeActivityResumed(
            INSTANCE_ID,
            EPOCH,
            SESSION_ID,
            PROCESS_SLOT,
            PROCESS_ID + 1
        )

        assertFalse(stale.accepted)
        assertEquals("runtime_identity_mismatch", stale.reason)
        assertFalse(wrongPid.accepted)
        assertEquals("process_id_mismatch", wrongPid.reason)
        assertEquals(VirtualRuntimeState.PREWARMED, registry.get(INSTANCE_ID)?.state)
    }

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = HOST_PACKAGE,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = VIRTUAL_PACKAGE,
        dataRoot = "build/tmp/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/$INSTANCE_ID"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = PROCESS_SLOT,
        proxySlot = "$HOST_PACKAGE.container.ProxyActivity0",
        evidenceSessionId = "evidence-42",
        runtimeEpoch = EPOCH,
        engineSessionId = SESSION_ID,
        processId = PROCESS_ID,
        processName = PROCESS_SLOT,
        state = VirtualRuntimeState.PREWARMED
    )

    private companion object {
        const val INSTANCE_ID = "instance-foreground"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-foreground"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v0"
        const val PROCESS_ID = 4200
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
    }
}
