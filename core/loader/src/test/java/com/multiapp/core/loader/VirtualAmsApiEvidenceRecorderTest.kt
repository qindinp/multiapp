package com.multiapp.core.loader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VirtualAmsApiEvidenceRecorderTest {

    @AfterTest
    fun tearDown() {
        VirtualAmsApiEvidenceRecorders.reset()
    }

    @Test
    fun `global recorder delegates installed AMS API evidence records`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }

        GlobalVirtualAmsApiEvidenceRecorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                virtualPackageName = "com.multiapp.instance.inst001",
                api = "registerReceiver",
                status = "DYNAMIC_RECEIVER_REGISTERED",
                hostFallback = false,
                fields = linkedMapOf("registered" to true)
            )
        )

        assertEquals(1, records.size)
        assertEquals(VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER, records.single().component)
        assertEquals("inst-001", records.single().instanceId)
        assertEquals("registered", records.single().fields.keys.single())
    }

    @Test
    fun `reset restores no-op AMS API evidence recorder`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        VirtualAmsApiEvidenceRecorders.reset()

        GlobalVirtualAmsApiEvidenceRecorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                virtualPackageName = "com.multiapp.instance.inst001",
                api = "bindService:int",
                status = "BIND_BLOCKED",
                hostFallback = false,
                fields = linkedMapOf("returnValue" to false)
            )
        )

        assertEquals(emptyList<VirtualAmsApiEvidenceRecord>(), records)
    }
}
