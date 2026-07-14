package com.multiapp.core.engine

import android.os.Bundle
import android.os.IBinder
import com.multiapp.core.engine.ipc.IEngineRuntimeService
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class EngineRuntimeBinderHandshakeTest {
    @Test
    fun `handshake accepts only a live endpoint from the dedicated engine generation`() {
        val endpoint = endpoint(GENERATION)
        val response = response(endpoint, GENERATION, ENGINE_PROCESS)

        val candidate = response.toEngineRuntimeServiceCandidateOrNull(
            expectedProcessName = ENGINE_PROCESS,
            clientProcessId = 1357
        )

        assertSame(endpoint.service, candidate?.service)
        assertEquals(GENERATION, candidate?.serverGenerationId)
        assertEquals(2468, candidate?.serverProcessId)
        assertEquals(ENGINE_PROCESS, candidate?.serverProcessName)
    }

    @Test
    fun `handshake rejects missing wrong-process dead and generation-mismatched endpoints`() {
        val endpoint = endpoint(GENERATION)

        assertNull(mockk<Bundle>(relaxed = true).toEngineRuntimeServiceCandidateOrNull(ENGINE_PROCESS))
        assertNull(
            response(endpoint, GENERATION, "com.multiapp.app")
                .toEngineRuntimeServiceCandidateOrNull(ENGINE_PROCESS)
        )
        assertNull(
            response(endpoint, GENERATION, ENGINE_PROCESS)
                .toEngineRuntimeServiceCandidateOrNull(
                    expectedProcessName = ENGINE_PROCESS,
                    clientProcessId = 2468
                )
        )

        endpoint.alive.value = false
        assertNull(
            response(endpoint, GENERATION, ENGINE_PROCESS)
                .toEngineRuntimeServiceCandidateOrNull(ENGINE_PROCESS)
        )
        endpoint.alive.value = true

        assertNull(
            response(endpoint, "stale-provider-generation", ENGINE_PROCESS)
                .toEngineRuntimeServiceCandidateOrNull(ENGINE_PROCESS)
        )
    }

    private fun endpoint(generationId: String): Endpoint {
        val alive = MutableBoolean(true)
        val binder = mockk<IBinder> {
            every { isBinderAlive } answers { alive.value }
        }
        val service = mockk<IEngineRuntimeService> {
            every { asBinder() } returns binder
            every { getServerGenerationId() } returns generationId
        }
        every { binder.queryLocalInterface(any()) } returns service
        return Endpoint(service, binder, alive)
    }

    private fun response(
        endpoint: Endpoint,
        advertisedGenerationId: String,
        processName: String
    ): Bundle = mockk {
        every { getString(EngineRuntimeIpcContract.KEY_SERVER_GENERATION_ID) } returns
            advertisedGenerationId
        every { getInt(EngineRuntimeIpcContract.KEY_SERVER_PROCESS_ID) } returns 2468
        every { getString(EngineRuntimeIpcContract.KEY_SERVER_PROCESS_NAME) } returns processName
        every { getBinder(EngineRuntimeIpcContract.KEY_BINDER) } returns endpoint.binder
    }

    private data class Endpoint(
        val service: IEngineRuntimeService,
        val binder: IBinder,
        val alive: MutableBoolean
    )

    private data class MutableBoolean(var value: Boolean)

    private companion object {
        const val GENERATION = "server-generation-1"
        const val ENGINE_PROCESS = "com.multiapp.app:engine"
    }
}
