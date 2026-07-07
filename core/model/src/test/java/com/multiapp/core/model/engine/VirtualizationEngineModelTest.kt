package com.multiapp.core.model.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualizationEngineModelTest {

    @Test
    fun `pass and partial results are successful`() {
        assertTrue(EngineResult.pass(operation = "launch").success)
        assertTrue(EngineResult.partial(operation = "launch", message = "evidence incomplete").success)
        assertFalse(EngineResult.fail(operation = "launch", message = "failed").success)
        assertFalse(EngineResult.unsupported(operation = "bindService", message = "not implemented").success)
    }

    @Test
    fun `launch request defaults to baseline profile`() {
        val request = LaunchInstanceRequest(instanceId = "instance-1")

        assertEquals(EngineProfile.BASELINE, request.profile)
        assertEquals("user", request.reason)
    }
}
