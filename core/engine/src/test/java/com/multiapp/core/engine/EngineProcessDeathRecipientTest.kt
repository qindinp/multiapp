package com.multiapp.core.engine

import android.os.IBinder
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceManager
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.installer.VirtualInstallService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineProcessDeathRecipientTest {

    @Test
    fun `matching READY client token death marks its runtime dead`() {
        val token = LinkedToken()
        val fixture = fixture(listOf(token.binder))
        val launchedRuntime = fixture.launch()

        token.recipient.captured.binderDied()

        val deadRuntime = assertNotNull(fixture.registry.get(INSTANCE_ID))
        assertEquals(VirtualRuntimeState.DEAD, deadRuntime.state)
        assertEquals(null, deadRuntime.processId)
        val deathEvidence = fixture.registry.evidence(INSTANCE_ID)
            .operationEntries("runtime", "process-death")
            .single()
        assertEquals(launchedRuntime.runtimeEpoch.toString(), deathEvidence.entries["runtimeEpoch"])
        assertEquals(launchedRuntime.engineSessionId, deathEvidence.entries["engineSessionId"])
        assertEquals("bootstrap_client_binder_died", deathEvidence.entries["reason"])
    }

    @Test
    fun `old client token death cannot kill replacement runtime`() {
        val oldToken = LinkedToken()
        val currentToken = LinkedToken()
        val fixture = fixture(listOf(oldToken.binder, currentToken.binder))
        val oldRuntime = fixture.launch()
        val currentRuntime = fixture.launch()

        oldToken.recipient.captured.binderDied()

        assertTrue(oldRuntime.runtimeEpoch < currentRuntime.runtimeEpoch)
        assertEquals(currentRuntime, fixture.registry.get(INSTANCE_ID))
        assertEquals(VirtualRuntimeState.PREWARMED, fixture.registry.get(INSTANCE_ID)?.state)
        assertTrue(
            fixture.registry.evidence(INSTANCE_ID)
                .operationEntries("runtime", "process-death")
                .isEmpty()
        )
    }

    @Test
    fun `repeated client token death callback records one transition and one evidence`() {
        val token = LinkedToken()
        val fixture = fixture(listOf(token.binder))
        fixture.launch()

        token.recipient.captured.binderDied()
        val deadRuntime = fixture.registry.get(INSTANCE_ID)
        token.recipient.captured.binderDied()

        assertEquals(deadRuntime, fixture.registry.get(INSTANCE_ID))
        assertEquals(VirtualRuntimeState.DEAD, deadRuntime?.state)
        assertEquals(
            1,
            fixture.registry.evidence(INSTANCE_ID)
                .operationEntries("runtime", "process-death")
                .size
        )
    }

    private fun fixture(tokens: List<IBinder>): Fixture {
        val instance = instance()
        val instanceManager = mockk<InstanceManager>()
        every { instanceManager.getInstance(INSTANCE_ID) } returns instance
        every { instanceManager.listInstances() } returns listOf(instance)
        every { instanceManager.updateLaunchState(INSTANCE_ID) } returns instance

        val installService = mockk<VirtualInstallService>()
        every { installService.getInstallRecord(ORIGIN_PACKAGE) } returns installRecord()

        val registry = EngineRuntimeRegistry()
        var bootstrapIndex = 0
        var evidenceIndex = 0
        var epochIndex = 0
        val runtimeEpochs = listOf(41L, 42L)
        val engine = DefaultVirtualizationEngineCore(
            hostPackageName = HOST_PACKAGE,
            instanceManager = instanceManager,
            virtualInstallService = installService,
            activityLauncher = EngineActivityLauncher { },
            processBootstrapper = EngineProcessBootstrapper { request ->
                val index = bootstrapIndex++
                EngineProcessBootstrapResult(
                    state = EngineProcessBootstrapState.READY,
                    verdict = EngineResultStatus.PASS,
                    instanceId = request.runtime.instanceId,
                    runtimeEpoch = request.runtime.runtimeEpoch,
                    engineSessionId = request.runtime.engineSessionId,
                    clientToken = tokens[index],
                    processId = 4100 + index,
                    processName = request.runtime.processSlot,
                    launcherActivityClassName = MAIN_ACTIVITY,
                    message = "target process reported READY"
                )
            },
            runtimeRegistry = registry,
            evidenceSessionFactory = { "evidence-${++evidenceIndex}" },
            runtimeEpochFactory = { runtimeEpochs[epochIndex++] }
        )
        return Fixture(engine, registry)
    }

    private fun instance() = VirtualInstanceRecord(
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = VIRTUAL_PACKAGE,
        displayName = "Test",
        dataRoot = File("build/tmp/$INSTANCE_ID").absolutePath,
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun installRecord() = InstallRecord(
        packageName = ORIGIN_PACKAGE,
        originApkPath = File("build/tmp/test.apk").absolutePath,
        originApkSha256 = "sha256",
        originCertSha256 = "cert",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        packageLabel = "Test",
        activities = listOf(ComponentInfo(MAIN_ACTIVITY, exported = true)),
        installTimeMs = 1L
    )

    private class LinkedToken {
        val recipient = slot<IBinder.DeathRecipient>()
        val binder = mockk<IBinder>()

        init {
            every { binder.isBinderAlive } returns true
            every { binder.linkToDeath(capture(recipient), 0) } returns Unit
            every { binder.unlinkToDeath(any(), 0) } returns true
        }
    }

    private data class Fixture(
        val engine: DefaultVirtualizationEngineCore,
        val registry: EngineRuntimeRegistry
    ) {
        fun launch(): VirtualInstanceRuntime {
            val result = engine.launchInstance(LaunchInstanceRequest(instanceId = INSTANCE_ID))
            assertEquals(EngineResultStatus.PASS, result.status)
            return assertNotNull(result.runtime)
        }
    }

    private companion object {
        const val INSTANCE_ID = "instance-death"
        const val HOST_PACKAGE = "com.multiapp.app"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val VIRTUAL_PACKAGE = "com.multiapp.virtual.instance-death"
        const val MAIN_ACTIVITY = "com.test.app.MainActivity"
    }
}
