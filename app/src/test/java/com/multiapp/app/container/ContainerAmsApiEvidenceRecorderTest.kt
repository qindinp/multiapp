package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.engine.EngineAmsApiEvidenceComponent
import com.multiapp.core.engine.EngineAmsApiEvidenceRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContainerAmsApiEvidenceRecorderTest {
    @Test
    fun `writes AMS API evidence file with shared and specific fields`(@TempDir filesDir: File) {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val recorder = ContainerAmsApiEvidenceRecorder(context)

        recorder.record(
            EngineAmsApiEvidenceRecord(
                component = EngineAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                virtualPackageName = "com.multiapp.instance.inst001",
                api = "bindService:int",
                status = "BIND_BLOCKED",
                hostFallback = false,
                fields = linkedMapOf(
                    "returnValue" to false,
                    "serviceResolved" to true,
                    "reason" to "explicit"
                )
            )
        )

        val file = File(filesDir, "hosted_launch_evidence/inst-001.ams-bind-service-overload.properties")
        assertTrue(file.isFile)
        val lines = file.readLines().map { it.trim() }
        assertEquals(
            listOf(
                "status=BIND_BLOCKED",
                "stage=AMS_API_OVERLOAD",
                "instanceId=inst-001",
                "originPackageName=com.test.minimal",
                "virtualPackageName=com.multiapp.instance.inst001",
                "api=bindService:int",
                "hostFallback=false",
                "returnValue=false",
                "serviceResolved=true",
                "reason=explicit"
            ),
            lines
        )
    }

    @Test
    fun `shared fields override colliding record fields and custom field is preserved`(@TempDir filesDir: File) {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val recorder = ContainerAmsApiEvidenceRecorder(context)

        recorder.record(
            EngineAmsApiEvidenceRecord(
                component = EngineAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                virtualPackageName = "com.multiapp.instance.inst001",
                api = "bindService:int",
                status = "BIND_BLOCKED",
                hostFallback = false,
                fields = linkedMapOf(
                    "status" to "OVERRIDDEN_STATUS",
                    "stage" to "OVERRIDDEN_STAGE",
                    "instanceId" to "../evil",
                    "originPackageName" to "evil.origin",
                    "virtualPackageName" to "evil.virtual",
                    "api" to "evilApi",
                    "hostFallback" to true,
                    "customEvidence" to "kept"
                )
            )
        )

        val file = File(filesDir, "hosted_launch_evidence/inst-001.ams-bind-service-overload.properties")
        assertTrue(file.isFile)
        val lines = file.readLines().map { it.trim() }
        assertEquals(
            listOf(
                "status=BIND_BLOCKED",
                "stage=AMS_API_OVERLOAD",
                "instanceId=inst-001",
                "originPackageName=com.test.minimal",
                "virtualPackageName=com.multiapp.instance.inst001",
                "api=bindService:int",
                "hostFallback=false",
                "customEvidence=kept"
            ),
            lines
        )
    }

    @Test
    fun `record swallows runtime evidence path failures`(@TempDir filesDir: File) {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        val recorder = ContainerAmsApiEvidenceRecorder(context)

        mockkStatic(Log::class)
        try {
            every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

            assertDoesNotThrow {
                recorder.record(
                    EngineAmsApiEvidenceRecord(
                        component = EngineAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
                        instanceId = "../inst",
                        originPackageName = "com.test.minimal",
                        virtualPackageName = "com.multiapp.instance.inst001",
                        api = "bindService:int",
                        status = "BIND_BLOCKED",
                        hostFallback = false
                    )
                )
            }

            assertFalse(
                File(filesDir, "hosted_launch_evidence/../inst.ams-bind-service-overload.properties")
                    .canonicalFile
                    .exists()
            )
        } finally {
            unmockkStatic(Log::class)
        }
    }
}
