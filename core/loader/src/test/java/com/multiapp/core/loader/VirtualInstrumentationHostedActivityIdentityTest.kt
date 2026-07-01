package com.multiapp.core.loader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VirtualInstrumentationHostedActivityIdentityTest {

    @Test
    fun `hosted identity ignores host activity with only instance id`() {
        val identity = resolveHostedActivityIdentity(
            intentWithHostedExtras(instanceId = "inst-001")
        )

        assertNull(identity)
    }

    @Test
    fun `hosted identity ignores guest class without virtual activity token`() {
        val identity = resolveHostedActivityIdentity(
            intentWithHostedExtras(
                instanceId = "inst-001",
                guestActivityClassName = "com.test.minimal.MainActivity"
            )
        )

        assertNull(identity)
    }

    @Test
    fun `hosted identity resolves complete guest proxy metadata`() {
        val identity = assertNotNull(
            resolveHostedActivityIdentity(
                intentWithHostedExtras(
                    instanceId = "inst-001",
                    guestActivityClassName = "com.test.minimal.MainActivity",
                    token = "token-001"
                )
            )
        )

        assertEquals("inst-001", readStringField(identity, "instanceId"))
        assertEquals("com.test.minimal.MainActivity", readStringField(identity, "guestActivityClassName"))
        assertEquals("token-001", readStringField(identity, "token"))
    }

    private fun resolveHostedActivityIdentity(intent: Intent): Any? {
        val virtualInstrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val activity = mockk<Activity>(relaxed = true) {
            every { this@mockk.intent } returns intent
        }
        val method = VirtualInstrumentation::class.java.getDeclaredMethod("hostedActivityIdentity", Activity::class.java)
        method.isAccessible = true
        return method.invoke(virtualInstrumentation, activity)
    }

    private fun intentWithHostedExtras(
        instanceId: String? = null,
        guestActivityClassName: String? = null,
        token: String? = null
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra("multiapp.instanceId") } returns instanceId
        every { getStringExtra("multiapp.guestActivityClassName") } returns guestActivityClassName
        every { getStringExtra("multiapp.virtualActivityToken") } returns token
    }

    private fun readStringField(target: Any, fieldName: String): String {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as String
    }
}
