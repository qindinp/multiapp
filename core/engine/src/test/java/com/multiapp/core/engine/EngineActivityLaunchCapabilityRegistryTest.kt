package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineActivityLaunchCapabilityRegistryTest {
    @Test
    fun `capability binds complete launch identity and target pid and rejects replay`() {
        var tokenIndex = 0
        val registry = EngineActivityLaunchCapabilityRegistry(
            clockNanos = { 100L },
            tokenFactory = { "capability-${++tokenIndex}" }
        )
        val identity = registry.issue(runtime(), PROCESS_ID, PROXY_ACTIVITY, GUEST_ACTIVITY)

        assertFalse(registry.authorize(identity.copy(guestActivityClassName = "com.test.Other"), PROCESS_ID).accepted)
        assertFalse(registry.authorize(identity, PROCESS_ID + 1).accepted)

        val first = registry.authorize(identity, PROCESS_ID)
        val repeated = registry.authorize(identity, PROCESS_ID)
        assertTrue(first.accepted)
        assertFalse(first.idempotent)
        assertFalse(repeated.accepted)
        assertFalse(repeated.idempotent)
        assertEquals("launch_capability_replayed", repeated.reason)
    }

    @Test
    fun `resume requires prior launch authorization and completion is idempotent`() {
        val registry = EngineActivityLaunchCapabilityRegistry(tokenFactory = { "capability-resume" })
        val identity = registry.issue(runtime(), PROCESS_ID, PROXY_ACTIVITY, GUEST_ACTIVITY)

        assertFalse(registry.validateResume(identity.capabilityToken, INSTANCE_ID, EPOCH, SESSION_ID, PROCESS_SLOT, PROCESS_ID).accepted)
        assertTrue(registry.authorize(identity, PROCESS_ID).accepted)
        assertTrue(registry.validateResume(identity.capabilityToken, INSTANCE_ID, EPOCH, SESSION_ID, PROCESS_SLOT, PROCESS_ID).accepted)
        assertTrue(registry.complete(identity.capabilityToken))
        val repeated = registry.validateResume(
            identity.capabilityToken,
            INSTANCE_ID,
            EPOCH,
            SESSION_ID,
            PROCESS_SLOT,
            PROCESS_ID
        )
        assertTrue(repeated.accepted)
        assertTrue(repeated.idempotent)
        assertEquals(1, registry.size())
    }

    @Test
    fun `new generation revokes old capability and expired capability fails closed`() {
        var now = 0L
        var tokenIndex = 0
        val registry = EngineActivityLaunchCapabilityRegistry(
            clockNanos = { now },
            tokenFactory = { "capability-${++tokenIndex}" },
            ttlNanos = TimeUnit.SECONDS.toNanos(1)
        )
        val oldIdentity = registry.issue(runtime(), PROCESS_ID, PROXY_ACTIVITY, GUEST_ACTIVITY)
        val newIdentity = registry.issue(
            runtime().copy(runtimeEpoch = EPOCH + 1, engineSessionId = "engine-session-43"),
            PROCESS_ID,
            PROXY_ACTIVITY,
            GUEST_ACTIVITY
        )

        assertFalse(registry.authorize(oldIdentity, PROCESS_ID).accepted)
        assertTrue(registry.authorize(newIdentity, PROCESS_ID).accepted)
        now = TimeUnit.SECONDS.toNanos(2)
        assertFalse(registry.authorize(newIdentity, PROCESS_ID).accepted)
        assertEquals(0, registry.size())
    }

    @Test
    fun `generation cannot roll back or change process binding without an epoch advance`() {
        var tokenIndex = 0
        val registry = EngineActivityLaunchCapabilityRegistry(tokenFactory = { "capability-${++tokenIndex}" })
        val oldIdentity = registry.issue(runtime(), PROCESS_ID, PROXY_ACTIVITY, GUEST_ACTIVITY)
        val newRuntime = runtime().copy(
            runtimeEpoch = EPOCH + 1,
            engineSessionId = "engine-session-43",
            processSlot = "$HOST_PACKAGE:v1",
            processId = PROCESS_ID + 1,
            processName = "$HOST_PACKAGE:v1"
        )
        val newIdentity = registry.issue(newRuntime, PROCESS_ID + 1, PROXY_ACTIVITY, GUEST_ACTIVITY)

        assertFalse(registry.authorize(oldIdentity, PROCESS_ID).accepted)
        assertFailsWith<IllegalStateException> {
            registry.issue(runtime(), PROCESS_ID, PROXY_ACTIVITY, GUEST_ACTIVITY)
        }
        assertFailsWith<IllegalStateException> {
            registry.issue(
                newRuntime.copy(processSlot = "$HOST_PACKAGE:v2", processId = PROCESS_ID + 2),
                PROCESS_ID + 2,
                PROXY_ACTIVITY,
                GUEST_ACTIVITY
            )
        }
        assertTrue(registry.authorize(newIdentity, PROCESS_ID + 1).accepted)
    }

    @Test
    fun `issue rejects pid outside runtime binding and complete fails closed`() {
        var now = 0L
        val registry = EngineActivityLaunchCapabilityRegistry(
            clockNanos = { now },
            tokenFactory = { "capability-strict" },
            ttlNanos = 10L
        )
        assertFailsWith<IllegalArgumentException> {
            registry.issue(runtime(), PROCESS_ID + 1, PROXY_ACTIVITY, GUEST_ACTIVITY)
        }
        val identity = registry.issue(runtime(), PROCESS_ID, PROXY_ACTIVITY, GUEST_ACTIVITY)

        assertFalse(registry.complete(identity.capabilityToken))
        assertTrue(registry.authorize(identity, PROCESS_ID).accepted)
        now = 10L
        assertFalse(registry.complete(identity.capabilityToken))
        assertEquals(0, registry.size())
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
        proxySlot = PROXY_ACTIVITY,
        evidenceSessionId = "evidence-42",
        runtimeEpoch = EPOCH,
        engineSessionId = SESSION_ID,
        processId = PROCESS_ID,
        processName = PROCESS_SLOT,
        state = VirtualRuntimeState.PREWARMED
    )

    private companion object {
        const val INSTANCE_ID = "instance-launch-capability"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-launch-capability"
        const val PROCESS_SLOT = "$HOST_PACKAGE:v0"
        const val PROXY_ACTIVITY = "$HOST_PACKAGE.container.ProxyActivity0"
        const val GUEST_ACTIVITY = "$ORIGIN_PACKAGE.MainActivity"
        const val PROCESS_ID = 4200
        const val EPOCH = 42L
        const val SESSION_ID = "engine-session-42"
    }
}
