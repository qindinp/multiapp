package com.multiapp.core.engine

import android.os.Bundle
import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.IdentityHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineComponentProcessEndpointSecurityTest {
    private lateinit var bundles: BundleHarness

    @BeforeTest
    fun setUp() {
        bundles = BundleHarness().also(BundleHarness::installConstructorMock)
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(Bundle::class)
    }

    @Test
    fun `same UID process without runtime authority cannot prepare or query another instance`() {
        val authority = mockk<EngineComponentProcessAuthority>(relaxed = true)
        val endpoint = endpoint(
            authority = authority,
            processDecision = EngineProcessAuthorityDecision(
                allowed = false,
                identity = null,
                reason = "runtime_process_id_mismatch"
            )
        )

        val prepare = endpoint.prepareComponentProcess(INSTANCE_ID, GUEST_PROCESS_NAME)
        val query = endpoint.queryComponentProcessClient(INSTANCE_ID, GUEST_PROCESS_NAME)

        assertFalse(prepare.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(
            "component_process_prepare_caller_unauthorized",
            prepare.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
        assertFalse(query.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        verify(exactly = 0) { authority.prepare(any(), any()) }
        verify(exactly = 0) { authority.query(any(), any()) }
    }

    @Test
    fun `attach rejects malformed opaque capabilities`() {
        val authority = mockk<EngineComponentProcessAuthority>(relaxed = true)
        val endpoint = endpoint(authority, allowedDecision())
        val token = liveBinder()

        val malformedResult = endpoint.attachComponentProcessClient("short", token)
            .toComponentProcessOperationResultOrNull()
        val overlongResult = endpoint.attachComponentProcessClient(
            "x".repeat(EngineRuntimeIpcContract.MAX_COMPONENT_PROCESS_TEXT_LENGTH + 1),
            token
        )
            .toComponentProcessOperationResultOrNull()

        assertEquals("invalid_component_process_attach_capability", malformedResult?.reason)
        assertEquals("invalid_component_process_attach_capability", overlongResult?.reason)
        verify(exactly = 0) { authority.attach(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `valid ticket reaches authority with Binder PID and proc process name`() {
        val authority = mockk<EngineComponentProcessAuthority>()
        val token = liveBinder()
        val ticket = launchTicket()
        val state = EngineComponentProcessState(
            instanceId = INSTANCE_ID,
            effectiveGuestProcessName = GUEST_PROCESS_NAME,
            processSlot = PROCESS_SLOT,
            processId = CALLING_PID,
            processEpoch = 1L,
            live = true
        )
        every {
            authority.attach(
                ticket.attachCapability,
                token,
                CALLING_PID,
                PROCESS_SLOT,
                PROCESS_START_TICKS
            )
        } returns EngineComponentProcessOperationResult(
            operation = COMPONENT_PROCESS_ATTACH_OPERATION,
            instanceId = INSTANCE_ID,
            accepted = true,
            idempotent = false,
            alreadyRunning = false,
            launchTicket = null,
            processState = state,
            reason = "component_process_client_attached"
        )
        val endpoint = endpoint(authority, allowedDecision())

        val result = endpoint.attachComponentProcessClient(
            ticket.attachCapability,
            token
        ).toComponentProcessOperationResultOrNull()

        assertTrue(result?.accepted == true)
        assertEquals(state, result?.processState)
        verify(exactly = 1) {
            authority.attach(
                ticket.attachCapability,
                token,
                CALLING_PID,
                PROCESS_SLOT,
                PROCESS_START_TICKS
            )
        }
    }

    @Test
    fun `calling component query derives only the attached Binder caller identity`() {
        val authority = mockk<EngineComponentProcessAuthority>(relaxed = true)
        val identity = componentIdentity()
        val endpoint = endpoint(
            authority = authority,
            processDecision = EngineProcessAuthorityDecision(
                allowed = false,
                identity = null,
                reason = "runtime_process_id_mismatch"
            ),
            componentIdentity = identity
        )

        val accepted = endpoint.queryCallingComponentProcess(INSTANCE_ID)
            .toComponentProcessOperationResultOrNull()
        val wrongInstance = endpoint.queryCallingComponentProcess("instance-other")
            .toComponentProcessOperationResultOrNull()
        val broadControlQuery = endpoint.queryComponentProcessClient(INSTANCE_ID, GUEST_PROCESS_NAME)

        assertTrue(accepted?.accepted == true)
        assertEquals(identity.toPublicComponentProcessState(), accepted?.processState)
        assertFalse(wrongInstance?.accepted ?: true)
        assertEquals("component_process_caller_unauthorized", wrongInstance?.reason)
        assertFalse(broadControlQuery.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals(
            "runtime_process_id_mismatch",
            broadControlQuery.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
        verify(exactly = 1) {
            authority.authorizeCaller(
                INSTANCE_ID,
                CALLING_PID,
                PROCESS_SLOT,
                PROCESS_START_TICKS
            )
        }
    }

    private fun endpoint(
        authority: EngineComponentProcessAuthority,
        processDecision: EngineProcessAuthorityDecision,
        componentIdentity: EngineComponentProcessClientIdentity? = null
    ): EngineRuntimeBinderEndpoint {
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(INSTANCE_ID, CALLING_PID) } returns processDecision
        every {
            authority.authorizeCaller(any(), CALLING_PID, PROCESS_SLOT, PROCESS_START_TICKS)
        } answers {
            componentIdentity?.takeIf { identity -> identity.instanceId == firstArg<String>() }
        }
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null)
        }
        val endpoint = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, EngineRuntimeBinderEndpoint::class.java) as EngineRuntimeBinderEndpoint
        setEndpointField(endpoint, "hostUid", HOST_UID)
        setEndpointField(endpoint, "callingUid", { HOST_UID })
        setEndpointField(endpoint, "callingPid", { CALLING_PID })
        setEndpointField(endpoint, "callingProcessName", { _: Int -> PROCESS_SLOT })
        setEndpointField(endpoint, "callingProcessStartTicks", { _: Int -> PROCESS_START_TICKS })
        setEndpointField(endpoint, "processControlPlane", controlPlane)
        setEndpointField(endpoint, "componentProcessAuthority", authority)
        setEndpointField(endpoint, "ipcBundleFactory", bundles::create)
        return endpoint
    }

    private fun setEndpointField(endpoint: EngineRuntimeBinderEndpoint, name: String, value: Any?) {
        EngineRuntimeBinderEndpoint::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(endpoint, value)
        }
    }

    private fun allowedDecision() = EngineProcessAuthorityDecision(
        allowed = true,
        identity = EngineProcessClientIdentity(
            instanceId = INSTANCE_ID,
            runtimeEpoch = RUNTIME_EPOCH,
            engineSessionId = ENGINE_SESSION_ID,
            processSlot = PRIMARY_PROCESS_SLOT,
            processId = CALLING_PID
        ),
        reason = "live_runtime_authority_confirmed"
    )

    private fun launchTicket() = EngineComponentProcessLaunchTicket(
        instanceId = INSTANCE_ID,
        effectiveGuestProcessName = GUEST_PROCESS_NAME,
        processSlot = PROCESS_SLOT,
        attachCapability = "component-attach-capability-${"x".repeat(32)}"
    )

    private fun componentIdentity() = EngineComponentProcessClientIdentity(
        instanceId = INSTANCE_ID,
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processEpoch = 3L,
        clientSessionId = "component-client-session-3",
        effectiveGuestProcessName = GUEST_PROCESS_NAME,
        processSlot = PROCESS_SLOT,
        processId = CALLING_PID,
        processStartTicks = PROCESS_START_TICKS
    )

    private fun liveBinder(): IBinder = mockk {
        every { isBinderAlive } returns true
    }

    private class BundleHarness {
        private val values = IdentityHashMap<Bundle, MutableMap<String, Any?>>()

        fun installConstructorMock() {
            mockkConstructor(Bundle::class)
            every { anyConstructed<Bundle>().putString(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<String?>()
            }
            every { anyConstructed<Bundle>().getString(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? String
            }
            every { anyConstructed<Bundle>().putBoolean(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { anyConstructed<Bundle>().getBoolean(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Boolean ?: false
            }
            every { anyConstructed<Bundle>().getBoolean(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Boolean ?: secondArg<Boolean>()
            }
            every { anyConstructed<Bundle>().putInt(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Int>()
            }
            every { anyConstructed<Bundle>().getInt(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Int ?: 0
            }
            every { anyConstructed<Bundle>().putLong(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Long>()
            }
            every { anyConstructed<Bundle>().getLong(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Long ?: 0L
            }
            every { anyConstructed<Bundle>().putBundle(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<Bundle?>()
            }
            every { anyConstructed<Bundle>().getBundle(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? Bundle
            }
            every { anyConstructed<Bundle>().putBinder(any(), any()) } answers {
                valuesFor(self as Bundle)[firstArg()] = secondArg<IBinder?>()
            }
            every { anyConstructed<Bundle>().getBinder(any()) } answers {
                valuesFor(self as Bundle)[firstArg()] as? IBinder
            }
            every { anyConstructed<Bundle>().get(any()) } answers {
                valuesFor(self as Bundle)[firstArg()]
            }
            every { anyConstructed<Bundle>().keySet() } answers { valuesFor(self as Bundle).keys }
            every { anyConstructed<Bundle>().containsKey(any()) } answers {
                valuesFor(self as Bundle).containsKey(firstArg())
            }
        }

        fun create(): Bundle {
            val bundle = mockk<Bundle>()
            every { bundle.putString(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<String?>()
            }
            every { bundle.getString(any()) } answers { valuesFor(bundle)[firstArg()] as? String }
            every { bundle.putBoolean(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { bundle.getBoolean(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Boolean ?: false
            }
            every { bundle.getBoolean(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] as? Boolean ?: secondArg<Boolean>()
            }
            every { bundle.putInt(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Int>()
            }
            every { bundle.getInt(any()) } answers { valuesFor(bundle)[firstArg()] as? Int ?: 0 }
            every { bundle.putLong(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Long>()
            }
            every { bundle.getLong(any()) } answers { valuesFor(bundle)[firstArg()] as? Long ?: 0L }
            every { bundle.putBundle(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Bundle?>()
            }
            every { bundle.getBundle(any()) } answers { valuesFor(bundle)[firstArg()] as? Bundle }
            every { bundle.putBinder(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<IBinder?>()
            }
            every { bundle.getBinder(any()) } answers { valuesFor(bundle)[firstArg()] as? IBinder }
            every { bundle.get(any()) } answers { valuesFor(bundle)[firstArg()] }
            every { bundle.keySet() } answers { valuesFor(bundle).keys }
            every { bundle.containsKey(any()) } answers { valuesFor(bundle).containsKey(firstArg()) }
            return bundle
        }

        private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
            values.getOrPut(bundle) { linkedMapOf() }
    }

    private companion object {
        const val INSTANCE_ID = "instance-component-endpoint"
        const val RUNTIME_EPOCH = 42L
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val PRIMARY_PROCESS_SLOT = "com.multiapp.app:v0"
        const val PROCESS_SLOT = "com.multiapp.app:v1"
        const val GUEST_PROCESS_NAME = "com.test:remote"
        const val HOST_UID = 10123
        const val CALLING_PID = 4242
        const val PROCESS_START_TICKS = 424_200L
    }


    @Test
    fun `by-slot attach rejects blank instance or slot without reaching authority`() {
        val authority = mockk<EngineComponentProcessAuthority>(relaxed = true)
        val endpoint = endpoint(authority, allowedDecision())
        val token = liveBinder()

        val blankInstance = endpoint.attachComponentProcessBySlot("", PROCESS_SLOT, token)
        val blankSlot = endpoint.attachComponentProcessBySlot(INSTANCE_ID, "", token)
            .toComponentProcessOperationResultOrNull()

        // 空 instanceId 无法编码进合法 result shape（instanceId 必须非空），直接判空 bundle 拒绝
        assertFalse(blankInstance.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals("invalid_component_process_slot_attach", blankSlot?.reason)
        verify(exactly = 0) { authority.attachBySlot(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `by-slot attach forwards Binder caller identity to authority`() {
        val authority = mockk<EngineComponentProcessAuthority>()
        val token = liveBinder()
        val state = EngineComponentProcessState(
            instanceId = INSTANCE_ID,
            effectiveGuestProcessName = GUEST_PROCESS_NAME,
            processSlot = PROCESS_SLOT,
            processId = CALLING_PID,
            processEpoch = 4L,
            live = true
        )
        every {
            authority.attachBySlot(
                INSTANCE_ID,
                PROCESS_SLOT,
                token,
                CALLING_PID,
                PROCESS_SLOT,
                PROCESS_START_TICKS
            )
        } returns EngineComponentProcessOperationResult(
            operation = COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId = INSTANCE_ID,
            accepted = true,
            idempotent = false,
            alreadyRunning = false,
            launchTicket = null,
            processState = state,
            reason = "component_process_client_attached_by_slot"
        )
        val endpoint = endpoint(authority, allowedDecision())

        val result = endpoint.attachComponentProcessBySlot(INSTANCE_ID, PROCESS_SLOT, token)
            .toComponentProcessOperationResultOrNull()

        assertTrue(result?.accepted == true)
        assertEquals(state, result?.processState)
        verify(exactly = 1) {
            authority.attachBySlot(
                INSTANCE_ID,
                PROCESS_SLOT,
                token,
                CALLING_PID,
                PROCESS_SLOT,
                PROCESS_START_TICKS
            )
        }
    }
}


