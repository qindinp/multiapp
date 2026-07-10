package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualAppOpsDispatchRequest
import com.multiapp.core.model.engine.EngineResultStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineVirtualAppOpsDispatcherTest {
    @Test
    fun `dispatcher translates guest AppOps check to host process identity`() {
        val service = mockk<VirtualAppOpsService>()
        val requestSlot = slot<VirtualAppOpsQueryRequest>()
        every { service.queryMode("instance-1", capture(requestSlot)) } returns VirtualAppOpsQueryResult(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PASS,
            mode = EngineAppOpModes.IGNORED,
            explicitMode = true,
            intercept = true,
            message = "virtual_mode"
        )
        val dispatcher = EngineVirtualAppOpsDispatcher(
            service = service,
            hostUid = 10466,
            processIdProvider = { 5201 }
        )

        val actual = dispatcher.dispatch(
            VirtualAppOpsDispatchRequest(
                instanceId = "instance-1",
                methodName = "checkOperation",
                opCode = 26,
                uid = 1000,
                packageName = "com.test.app"
            )
        )

        assertTrue(actual.handled)
        assertEquals(EngineAppOpModes.IGNORED, actual.mode)
        assertFalse(actual.blockSystemCall)
        assertEquals(10466, requestSlot.captured.uid)
        assertEquals(10466, requestSlot.captured.hostUid)
        assertEquals(5201, requestSlot.captured.callingPid)
        assertEquals("com.test.app", requestSlot.captured.packageName)
    }
}
