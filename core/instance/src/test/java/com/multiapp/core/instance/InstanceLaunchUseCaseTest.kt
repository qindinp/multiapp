package com.multiapp.core.instance

import android.content.Context
import android.content.Intent
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InstanceLaunchUseCaseTest {

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        every { context.startActivity(any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `launch starts ContainerActivity with instance id`() {
        val intentSlot = slot<Intent>()
        var factoryPackageName: String? = null
        var factoryInstanceId: String? = null
        val intent = mockk<Intent>(relaxed = true)
        val useCase = InstanceLaunchUseCase(context) { packageName, instanceId ->
            factoryPackageName = packageName
            factoryInstanceId = instanceId
            intent
        }

        val result = useCase.launch("instance-1")

        assertTrue(result.isSuccess)
        verify { context.startActivity(capture(intentSlot)) }
        assertEquals(intent, intentSlot.captured)
        assertEquals("com.multiapp.app", factoryPackageName)
        assertEquals("instance-1", factoryInstanceId)
    }
}
