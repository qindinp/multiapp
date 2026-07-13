package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRuntimeRegistryDeathTest {
    @Test
    fun `server restart invalidates persisted live process state`() {
        val store = InMemoryEngineRuntimeStateStore()
        val registry = EngineRuntimeRegistry(store)
        val running = runtime(
            instanceId = "instance-running",
            runtimeEpoch = 7L,
            engineSessionId = "session-running",
            processId = 4100
        ).copy(state = VirtualRuntimeState.RUNNING)
        val dead = runtime(
            instanceId = "instance-dead",
            runtimeEpoch = 8L,
            engineSessionId = "session-dead",
            processId = 4200
        ).copy(state = VirtualRuntimeState.DEAD, processId = null)
        registry.register(running)
        registry.register(dead)

        assertEquals(1, registry.invalidateEphemeralProcessStates("test_server_restart"))

        val invalidated = registry.get(running.instanceId)
        assertEquals(VirtualRuntimeState.DEAD, invalidated?.state)
        assertEquals(null, invalidated?.processId)
        assertEquals(VirtualRuntimeState.DEAD, registry.get(dead.instanceId)?.state)
        assertEquals(
            "test_server_restart",
            registry.evidence(running.instanceId)
                .flattenedOperationEvidence()
                .last { it.operation == "server-restart-invalidation" }
                .entries["reason"]
        )
    }


    @Test
    fun `matching epoch and session mark current runtime dead`() {
        val registry = EngineRuntimeRegistry()
        val runtime = runtime(
            runtimeEpoch = 41L,
            engineSessionId = "engine-session-41",
            processId = 4100
        )
        registry.register(runtime)

        val markedDead = registry.markDeadIfCurrent(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId
        )

        assertTrue(markedDead)
        assertEquals(
            runtime.copy(processId = null, state = VirtualRuntimeState.DEAD),
            registry.get(runtime.instanceId)
        )
    }

    @Test
    fun `stale epoch and session cannot mark replacement runtime dead`() {
        val registry = EngineRuntimeRegistry()
        val staleRuntime = runtime(
            runtimeEpoch = 41L,
            engineSessionId = "engine-session-41",
            evidenceSessionId = "evidence-41",
            processId = 4100
        )
        val replacementRuntime = runtime(
            runtimeEpoch = 42L,
            engineSessionId = "engine-session-42",
            evidenceSessionId = "evidence-42",
            processId = 4200
        )
        registry.register(staleRuntime)
        registry.register(replacementRuntime)

        val markedDead = registry.markDeadIfCurrent(
            instanceId = staleRuntime.instanceId,
            runtimeEpoch = staleRuntime.runtimeEpoch,
            engineSessionId = staleRuntime.engineSessionId
        )

        assertFalse(markedDead)
        assertEquals(replacementRuntime, registry.get(replacementRuntime.instanceId))
    }

    @Test
    fun `repeated death notification is idempotent`() {
        val registry = EngineRuntimeRegistry()
        val runtime = runtime(
            runtimeEpoch = 41L,
            engineSessionId = "engine-session-41",
            processId = 4100
        )
        registry.register(runtime)

        val firstMarkedDead = registry.markDeadIfCurrent(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId
        )
        val deadRuntime = registry.get(runtime.instanceId)
        val secondMarkedDead = registry.markDeadIfCurrent(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId
        )

        assertTrue(firstMarkedDead)
        assertFalse(secondMarkedDead)
        assertEquals(deadRuntime, registry.get(runtime.instanceId))
        assertEquals(VirtualRuntimeState.DEAD, deadRuntime?.state)
        assertEquals(null, deadRuntime?.processId)
    }

    private fun runtime(
        instanceId: String = INSTANCE_ID,
        runtimeEpoch: Long,
        engineSessionId: String,
        evidenceSessionId: String = "evidence-41",
        processId: Int
    ) = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = HOST_PACKAGE,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = VIRTUAL_PACKAGE,
        dataRoot = "build/tmp/$instanceId",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = ORIGIN_PACKAGE,
            virtualPackageName = VIRTUAL_PACKAGE,
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "build/tmp/test.apk",
            dataDir = "build/tmp/$instanceId"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "$HOST_PACKAGE:v0",
        proxySlot = "$HOST_PACKAGE.container.ProxyActivity0",
        evidenceSessionId = evidenceSessionId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processId = processId,
        processName = "$HOST_PACKAGE:v0",
        state = VirtualRuntimeState.PREWARMED
    )

    private companion object {
        const val INSTANCE_ID = "instance-death"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-death"
    }
}
