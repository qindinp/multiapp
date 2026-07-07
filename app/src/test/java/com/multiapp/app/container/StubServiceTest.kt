package com.multiapp.app.container

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StubServiceTest {

    @Test
    fun `service runtime bind is async only for cache miss with instance id`() {
        assertTrue(StubService.shouldBindRuntimeAsync("inst-001", hasReusableRuntime = false))
        assertFalse(StubService.shouldBindRuntimeAsync("inst-001", hasReusableRuntime = true))
        assertFalse(StubService.shouldBindRuntimeAsync("", hasReusableRuntime = false))
    }
}
