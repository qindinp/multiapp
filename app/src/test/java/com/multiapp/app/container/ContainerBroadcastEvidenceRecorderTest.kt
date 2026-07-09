package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.engine.EngineBroadcastRecord
import com.multiapp.core.engine.EngineBroadcastResultCode
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContainerBroadcastEvidenceRecorderTest {
    @Test
    fun `recorder writes component scoped broadcast evidence`(@TempDir filesDir: File) {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val recorder = ContainerBroadcastEvidenceRecorder(context)

        recorder.record(
            EngineBroadcastRecord(
                instanceId = "inst-001",
                receiverClassName = "com.test.minimal.BootReceiver",
                action = "com.test.ACTION_BOOT",
                result = EngineBroadcastResultCode.Delivered
            )
        )

        val file = File(filesDir, "hosted_launch_evidence/inst-001.broadcast.properties")
        assertTrue(file.isFile)
        val text = file.readText()
        assertTrue("status=Delivered" in text)
        assertTrue("stage=BROADCAST_RUNTIME" in text)
        assertTrue("receiverClassName=com.test.minimal.BootReceiver" in text)
    }

    @Test
    fun `recorder ignores records without instance id`(@TempDir filesDir: File) {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val recorder = ContainerBroadcastEvidenceRecorder(context)

        recorder.record(
            EngineBroadcastRecord(
                instanceId = null,
                receiverClassName = null,
                action = "com.test.ACTION_IMPLICIT",
                result = EngineBroadcastResultCode.UnsupportedImplicit
            )
        )

        assertTrue(!File(filesDir, "hosted_launch_evidence").exists())
    }
}
