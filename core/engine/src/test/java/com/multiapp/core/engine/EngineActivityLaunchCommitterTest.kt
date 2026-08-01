package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualActivityRecordManager
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineActivityLaunchCommitterTest {
    @Test
    fun `authoritative singleTop commit queues a pending intent for the canonical root token`() {
        val manager = VirtualActivityRecordManager()
        val committer = EngineActivityLaunchCommitter(
            activityRecordManager = manager,
            validator = EngineActivityLaunchCommitValidator { identity, callingPid ->
                EngineActivityLaunchCommitValidation(
                    accepted = identity.instanceId == INSTANCE_ID &&
                        identity.runtimeEpoch == RUNTIME_EPOCH &&
                        identity.engineSessionId == ENGINE_SESSION_ID &&
                        identity.processSlot == PROCESS_SLOT &&
                        identity.proxyActivityClassName == PROXY_ACTIVITY &&
                        identity.guestActivityClassName == GUEST_ACTIVITY &&
                        callingPid == CALLING_PID,
                    reason = "test_validation"
                )
            }
        )

        val first = committer.commit(
            request = request(
                identity = rootIdentity(),
                record = record(token = "root-token"),
                action = "com.test.minimal.ROOT"
            ),
            callingPid = CALLING_PID
        )
        val second = committer.commit(
            request = request(
                identity = secondIdentity(),
                record = record(token = "second-token"),
                action = "com.test.minimal.NEW_INTENT_PROBE"
            ),
            callingPid = CALLING_PID
        )

        assertTrue(first.accepted, first.reason)
        assertTrue(second.accepted, second.reason)
        assertEquals("root-token", first.activity?.token)
        assertEquals("root-token", second.activity?.token)
        assertTrue(second.launchReused)

        val pending = manager.consumePendingNewIntent("root-token")
        assertNotNull(pending)
        assertEquals("second-token", pending.sourceToken)
        assertEquals("com.test.minimal.NEW_INTENT_PROBE", pending.dataIntent?.action)
        assertNull(manager.consumePendingNewIntent("root-token"))
    }

    private fun request(
        identity: EngineActivityLaunchIdentity,
        record: VirtualActivityRecord,
        action: String
    ) = EngineActivityLaunchCommitRequest(
        identity = identity,
        record = record,
        intentFlags = 0,
        dataIntent = VirtualIntentSnapshot(action = action)
    )

    private fun record(token: String) = VirtualActivityRecord(
        token = token,
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        guestActivityClassName = GUEST_ACTIVITY,
        proxyActivityClassName = PROXY_ACTIVITY,
        launchMode = "singleTop",
        taskAffinity = TASK_AFFINITY
    )

    private fun rootIdentity() = identity("capability-root")

    private fun secondIdentity() = identity("capability-second")

    private fun identity(capabilityToken: String) = EngineActivityLaunchIdentity(
        capabilityToken = capabilityToken,
        instanceId = INSTANCE_ID,
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processSlot = PROCESS_SLOT,
        proxyActivityClassName = PROXY_ACTIVITY,
        guestActivityClassName = GUEST_ACTIVITY
    )

    private companion object {
        const val INSTANCE_ID = "instance-1"
        const val ORIGIN_PACKAGE = "com.test.minimal"
        const val GUEST_ACTIVITY = "com.test.minimal.MainActivity"
        const val PROXY_ACTIVITY = "com.multiapp.app.container.ProxyActivitySingleTop5"
        const val TASK_AFFINITY = "com.test.minimal:instance-1"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "session-42"
        const val PROCESS_SLOT = "com.multiapp.app:v5"
        const val CALLING_PID = 4242
    }
}
