package com.multiapp.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiAppProcessRolesTest {
    @Test
    fun `process roles require exact host engine or declared guest process names`() {
        assertEquals(MultiAppProcessRole.HOST, MultiAppProcessRoles.resolve(HOST, HOST))
        assertEquals(
            MultiAppProcessRole.ENGINE_SERVER,
            MultiAppProcessRoles.resolve(HOST, "$HOST:engine")
        )
        assertEquals(MultiAppProcessRole.GUEST, MultiAppProcessRoles.resolve(HOST, "$HOST:v0"))
        assertEquals(MultiAppProcessRole.GUEST, MultiAppProcessRoles.resolve(HOST, "$HOST:v7"))
        assertEquals(MultiAppProcessRole.GUEST, MultiAppProcessRoles.resolve(HOST, "$HOST:v8"))
        assertEquals(MultiAppProcessRole.GUEST, MultiAppProcessRoles.resolve(HOST, "$HOST:v23"))

        listOf(
            "",
            "$HOST:engine2",
            "$HOST:v",
            "$HOST:v00",
            "$HOST:v24",
            "$HOST:v-1",
            "other.package:engine"
        ).forEach { processName ->
            assertEquals(
                MultiAppProcessRole.UNKNOWN,
                MultiAppProcessRoles.resolve(HOST, processName),
                processName
            )
        }
    }

    @Test
    fun `engine server never installs its own client or guest hooks`() {
        val policy = MultiAppProcessRoles.startupPolicy(MultiAppProcessRole.ENGINE_SERVER)

        assertFalse(policy.connectEngineClient)
        assertFalse(policy.installGuestRuntime)
    }

    @Test
    fun `host connects only to engine while guest installs hosted runtime`() {
        val host = MultiAppProcessRoles.startupPolicy(MultiAppProcessRole.HOST)
        val guest = MultiAppProcessRoles.startupPolicy(MultiAppProcessRole.GUEST)
        val unknown = MultiAppProcessRoles.startupPolicy(MultiAppProcessRole.UNKNOWN)

        assertTrue(host.connectEngineClient)
        assertFalse(host.installGuestRuntime)
        assertTrue(guest.connectEngineClient)
        assertTrue(guest.installGuestRuntime)
        assertFalse(unknown.connectEngineClient)
        assertFalse(unknown.installGuestRuntime)
    }

    private companion object {
        const val HOST = "com.multiapp.app"
    }
}
