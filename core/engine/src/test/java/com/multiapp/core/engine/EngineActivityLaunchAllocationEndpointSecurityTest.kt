package com.multiapp.core.engine

import android.os.Bundle
import com.multiapp.core.loader.VirtualActivityLaunchAllocationRequest
import com.multiapp.core.loader.VirtualActivityLaunchIdentity
import com.multiapp.core.model.engine.EngineResultStatus
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

class EngineActivityLaunchAllocationEndpointSecurityTest {
    private lateinit var bundles: BundleHarness

    @BeforeTest
    fun setUp() {
        bundles = BundleHarness().also { it.installConstructorMock() }
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(Bundle::class)
    }

    @Test
    fun `same UID caller without matching runtime PID cannot allocate`() {
        val allocator = mockk<EngineActivityLaunchAllocator>()
        val endpoint = endpoint(
            allocator = allocator,
            authority = EngineProcessAuthorityDecision(
                allowed = false,
                identity = null,
                reason = "runtime_process_id_mismatch"
            )
        )

        val response = endpoint.allocateActivityLaunch(INSTANCE_ID, allocationRequestBundle())

        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertEquals("runtime_process_id_mismatch", response.getString(EngineRuntimeIpcContract.KEY_REASON))
        verify(exactly = 0) { allocator.allocate(any(), any()) }
    }

    @Test
    fun `allocation endpoint rejects malformed and extra request Bundles`() {
        val allocator = mockk<EngineActivityLaunchAllocator>()
        val endpoint = endpoint(allocator, allowedAuthority())
        val malformed = bundles.create().apply {
            putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, INSTANCE_ID)
        }
        val extra = allocationRequestBundle().apply {
            putString("unexpected", "forged")
        }

        val malformedResponse = endpoint.allocateActivityLaunch(INSTANCE_ID, malformed)
        val extraResponse = endpoint.allocateActivityLaunch(INSTANCE_ID, extra)

        assertInvalidAllocationRequest(malformedResponse)
        assertInvalidAllocationRequest(extraResponse)
        verify(exactly = 0) { allocator.allocate(any(), any()) }
    }

    @Test
    fun `release endpoint rejects mismatched and extra allocation identity`() {
        val allocator = mockk<EngineActivityLaunchAllocator>()
        val endpoint = endpoint(allocator, allowedAuthority())
        val mismatchedIdentity = allocationBundle(identityInstanceId = "instance-forged")
        val extraIdentity = allocationBundle().apply {
            getBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_LAUNCH_IDENTITY)
                ?.putString("unexpected", "forged")
        }
        val extraAllocation = allocationBundle().apply {
            putString("unexpected", "forged")
        }

        assertFalse(endpoint.releaseActivityLaunch(INSTANCE_ID, mismatchedIdentity))
        assertFalse(endpoint.releaseActivityLaunch(INSTANCE_ID, extraIdentity))
        assertFalse(endpoint.releaseActivityLaunch(INSTANCE_ID, extraAllocation))
        verify(exactly = 0) { allocator.release(any(), any()) }
    }

    @Test
    fun `strict allocation identity reaches release with Binder calling PID`() {
        val allocator = mockk<EngineActivityLaunchAllocator>()
        every { allocator.release(any(), CALLING_PID) } returns true
        val endpoint = endpoint(allocator, allowedAuthority())

        val released = endpoint.releaseActivityLaunch(INSTANCE_ID, allocationBundle())

        assertTrue(released)
        verify(exactly = 1) {
            allocator.release(
                match { allocation ->
                    allocation.request == allocationRequest() &&
                        allocation.launchIdentity == launchIdentity()
                },
                CALLING_PID
            )
        }
    }

    private fun endpoint(
        allocator: EngineActivityLaunchAllocator,
        authority: EngineProcessAuthorityDecision
    ): EngineRuntimeBinderEndpoint {
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(INSTANCE_ID, CALLING_PID) } returns authority

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
        setEndpointField(endpoint, "processControlPlane", controlPlane)
        setEndpointField(endpoint, "activityLaunchAllocator", allocator)
        return endpoint
    }

    private fun setEndpointField(endpoint: EngineRuntimeBinderEndpoint, name: String, value: Any?) {
        EngineRuntimeBinderEndpoint::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(endpoint, value)
        }
    }

    private fun allowedAuthority() = EngineProcessAuthorityDecision(
        allowed = true,
        identity = EngineProcessClientIdentity(
            instanceId = INSTANCE_ID,
            runtimeEpoch = RUNTIME_EPOCH,
            engineSessionId = ENGINE_SESSION_ID,
            processSlot = PROCESS_SLOT,
            processId = CALLING_PID
        ),
        reason = "live_runtime_authority_confirmed"
    )

    private fun allocationRequest() = VirtualActivityLaunchAllocationRequest(
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        guestActivityClassName = GUEST_ACTIVITY,
        processSlot = PROCESS_SLOT,
        launchMode = null,
        taskAffinity = TASK_AFFINITY
    )

    private fun launchIdentity(instanceId: String = INSTANCE_ID) = VirtualActivityLaunchIdentity(
        capabilityToken = CAPABILITY_TOKEN,
        instanceId = instanceId,
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processSlot = PROCESS_SLOT,
        proxyActivityClassName = PROXY_ACTIVITY,
        guestActivityClassName = GUEST_ACTIVITY
    )

    private fun allocationRequestBundle() = bundles.create().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, INSTANCE_ID)
        putString(EngineRuntimeIpcContract.KEY_ORIGIN_PACKAGE_NAME, ORIGIN_PACKAGE)
        putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, GUEST_ACTIVITY)
        putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, PROCESS_SLOT)
        putString(EngineRuntimeIpcContract.KEY_LAUNCH_MODE, null)
        putString(EngineRuntimeIpcContract.KEY_TASK_AFFINITY, TASK_AFFINITY)
    }

    private fun identityBundle(instanceId: String = INSTANCE_ID) = bundles.create().apply {
        putString(EngineRuntimeIpcContract.KEY_LAUNCH_CAPABILITY_TOKEN, CAPABILITY_TOKEN)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, RUNTIME_EPOCH)
        putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, ENGINE_SESSION_ID)
        putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, PROCESS_SLOT)
        putString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME, PROXY_ACTIVITY)
        putString(EngineRuntimeIpcContract.KEY_ACTIVITY_CLASS_NAME, GUEST_ACTIVITY)
    }

    private fun allocationBundle(identityInstanceId: String = INSTANCE_ID) = bundles.create().apply {
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, true)
        putBundle(EngineRuntimeIpcContract.KEY_ACTIVITY_ALLOCATION_REQUEST, allocationRequestBundle())
        putString(EngineRuntimeIpcContract.KEY_PROXY_ACTIVITY_CLASS_NAME, PROXY_ACTIVITY)
        putBundle(
            EngineRuntimeIpcContract.KEY_ACTIVITY_LAUNCH_IDENTITY,
            identityBundle(identityInstanceId)
        )
        putString(EngineRuntimeIpcContract.KEY_REASON, "activity_allocation_authorized")
    }

    private fun assertInvalidAllocationRequest(response: Bundle) {
        assertEquals(EngineResultStatus.FAIL.name, response.getString(EngineRuntimeIpcContract.KEY_VERDICT))
        assertEquals(
            "invalid_activity_allocation_request",
            response.getString(EngineRuntimeIpcContract.KEY_REASON)
        )
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
            every { anyConstructed<Bundle>().keySet() } answers {
                valuesFor(self as Bundle).keys
            }
        }

        fun create(): Bundle = mockk<Bundle>().also { bundle ->
            every { bundle.putString(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<String?>()
            }
            every { bundle.getString(any()) } answers {
                valuesFor(bundle)[firstArg()] as? String
            }
            every { bundle.putBoolean(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Boolean>()
            }
            every { bundle.getBoolean(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Boolean ?: false
            }
            every { bundle.putLong(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Long>()
            }
            every { bundle.getLong(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Long ?: 0L
            }
            every { bundle.putBundle(any(), any()) } answers {
                valuesFor(bundle)[firstArg()] = secondArg<Bundle?>()
            }
            every { bundle.getBundle(any()) } answers {
                valuesFor(bundle)[firstArg()] as? Bundle
            }
            every { bundle.keySet() } answers { valuesFor(bundle).keys }
        }

        private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
            values.getOrPut(bundle) { linkedMapOf() }
    }

    private companion object {
        const val INSTANCE_ID = "instance-activity-allocation"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val GUEST_ACTIVITY = "$ORIGIN_PACKAGE.MainActivity"
        const val PROCESS_SLOT = "com.multiapp.app:v0"
        const val PROXY_ACTIVITY = "com.multiapp.app.container.ProxyActivity0"
        const val TASK_AFFINITY = "$ORIGIN_PACKAGE:$INSTANCE_ID"
        const val CAPABILITY_TOKEN = "allocation-capability"
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val RUNTIME_EPOCH = 42L
        const val HOST_UID = 10123
        const val CALLING_PID = 4200
    }
}
