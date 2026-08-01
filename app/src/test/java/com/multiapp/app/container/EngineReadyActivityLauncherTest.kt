package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.engine.EngineActivityProxyLauncher
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EngineReadyActivityLauncherTest {

    @Test
    fun `foreground host launch writes runtime launch evidence after proxy launch`(@TempDir filesDir: File) {
        val proxyLauncher = mockk<EngineActivityProxyLauncher>()
        every { proxyLauncher.launchGuestLauncher(any()) } returns Result.success(launchedActivity())
        val launcher = EngineReadyActivityLauncher(hostContext(filesDir), proxyLauncher)

        launcher.launchFromHost(launchRequest())

        val evidenceFile = ContainerRuntimePaths.hostedRuntimeEvidenceFile(
            filesDir = filesDir,
            instanceId = INSTANCE_ID,
            component = "launch"
        )
        assertTrue(evidenceFile.isFile, "engine foreground launch did not write launch evidence")
        val lines = evidenceFile.readLines()
        assertEquals("status=PROXY_LAUNCHED", lines[0])
        assertTrue(lines.contains("stage=ACTIVITY_PROXY"))
        assertTrue(lines.contains("launchPath=ENGINE_FOREGROUND"))
        assertTrue(lines.contains("instanceId=$INSTANCE_ID"))
        assertTrue(lines.contains("processSlot=com.multiapp.app:v0"))
        assertTrue(lines.contains("proxySlot=com.multiapp.app.container.ProxyActivity0"))
    }

    @Test
    fun `foreground host launch remains successful when launch evidence write fails`(@TempDir filesDir: File) {
        val proxyLauncher = mockk<EngineActivityProxyLauncher>()
        every { proxyLauncher.launchGuestLauncher(any()) } returns Result.success(launchedActivity())
        val launcher = EngineReadyActivityLauncher(hostContext(filesDir), proxyLauncher)
        mockkObject(ContainerRuntimeEvidenceWriter)
        every {
            ContainerRuntimeEvidenceWriter.write(any<Context>(), INSTANCE_ID, "launch", any())
        } throws IllegalStateException("evidence storage unavailable")

        try {
            assertDoesNotThrow { launcher.launchFromHost(launchRequest()) }
        } finally {
            unmockkObject(ContainerRuntimeEvidenceWriter)
        }
    }

    private fun hostContext(filesDir: File): Context = mockk(relaxed = true) {
        every { applicationContext } returns this
        every { packageName } returns "com.multiapp.app"
        every { this@mockk.filesDir } returns filesDir
    }

    private fun launchRequest() = EngineForegroundLaunchRequest(
        instanceId = INSTANCE_ID,
        originPackageName = "com.test.minimal",
        guestActivityClassName = "com.test.minimal.MainActivity",
        launchMode = null,
        taskAffinity = "com.test.minimal",
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        runtimeEpoch = 1L,
        engineSessionId = "engine-session-1",
        launchCapabilityToken = "launch-capability-1"
    )

    private fun launchedActivity() = VirtualActivityRecord(
        token = "activity-token-1",
        instanceId = INSTANCE_ID,
        originPackageName = "com.test.minimal",
        guestActivityClassName = "com.test.minimal.MainActivity",
        proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
        state = VirtualActivityState.RESUMED
    )

    private companion object {
        const val INSTANCE_ID = "inst-001"
    }
}
