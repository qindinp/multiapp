package com.multiapp.core.engine

import android.os.Bundle
import android.os.IBinder
import com.multiapp.core.model.engine.EngineResultStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineServiceConnectionEndpointSecurityTest {
    private lateinit var bundles: ServiceConnectionBundleHarness

    @BeforeTest
    fun setUp() {
        bundles = ServiceConnectionBundleHarness().also { it.installConstructorMock() }
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(Bundle::class)
    }

    @Test
    fun `same UID caller without authoritative PID cannot register`() {
        val fixture = endpoint(
            authority = EngineProcessAuthorityDecision(
                allowed = false,
                identity = null,
                reason = "runtime_process_id_mismatch"
            )
        )
        val response = fixture.endpoint.registerServiceConnection(
            INSTANCE_ID,
            lease().toIpcBundle(),
            liveConnectionToken()
        )

        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_FOUND))
        assertFalse(response.getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY))
        assertEquals("runtime_process_id_mismatch", response.getString(EngineRuntimeIpcContract.KEY_REASON))
        assertEquals(0, fixture.connections.activeBindingCount())
        verify(exactly = 0) { fixture.leases.claimForConnection(any(), any()) }
    }

    @Test
    fun `register rejects lease instance and authoritative process binding mismatches`() {
        val fixture = endpoint()
        val token = liveConnectionToken()

        val instanceMismatch = fixture.endpoint.registerServiceConnection(
            INSTANCE_ID,
            lease(instanceId = OTHER_INSTANCE_ID).toIpcBundle(),
            token
        ).requireServiceConnectionResult()
        val processBindingMismatch = fixture.endpoint.registerServiceConnection(
            INSTANCE_ID,
            lease(
                runtimeEpoch = RUNTIME_EPOCH + 1,
                engineSessionId = "$ENGINE_SESSION_ID-forged"
            ).toIpcBundle(),
            token
        ).requireServiceConnectionResult()

        assertRejected(instanceMismatch, REGISTER_OPERATION, "service_connection_lease_mismatch")
        assertRejected(
            processBindingMismatch,
            REGISTER_OPERATION,
            "service_connection_process_binding_mismatch"
        )
        assertEquals(0, fixture.connections.activeBindingCount())
        verify(exactly = 0) { fixture.leases.claimForConnection(any(), any()) }
    }

    @Test
    fun `register rejects lease denied by server-side authority`() {
        val deniedLease = EngineServiceOperationLeaseDecision(
            accepted = false,
            idempotent = false,
            state = EngineServiceOperationLeaseState.REJECTED,
            reason = "service_operation_lease_identity_mismatch"
        )
        val fixture = endpoint(leaseDecision = deniedLease)
        val operationLease = lease()

        val response = fixture.endpoint.registerServiceConnection(
            INSTANCE_ID,
            operationLease.toIpcBundle(),
            liveConnectionToken()
        ).requireServiceConnectionResult()

        assertRejected(
            response,
            REGISTER_OPERATION,
            "service_connection_lease_unclaimable:${deniedLease.reason}"
        )
        assertEquals(0, fixture.connections.activeBindingCount())
        verify(exactly = 1) { fixture.leases.claimForConnection(operationLease, CALLING_PID) }
    }

    @Test
    fun `query and remove reject a connection owned by another runtime identity`() {
        val connections = EngineServiceConnectionRegistry()
        val token = liveConnectionToken()
        val foreignBinding = binding(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "$ENGINE_SESSION_ID-foreign",
            processId = CALLING_PID + 1
        )
        assertTrue(connections.register(foreignBinding, token).accepted)
        val fixture = endpoint(connections = connections)

        val queried = fixture.endpoint.queryServiceConnection(
            INSTANCE_ID,
            token
        ).requireServiceConnectionResult()
        val removedBinding = fixture.endpoint.removeServiceConnectionBinding(
            INSTANCE_ID,
            foreignBinding.toServiceConnectionIpcBundle(bundles::create),
            token
        ).requireServiceConnectionResult()
        val removedAll = fixture.endpoint.removeServiceConnection(
            INSTANCE_ID,
            token
        ).requireServiceConnectionResult()

        assertRejected(queried, QUERY_OPERATION, "service_connection_owner_mismatch")
        assertRejected(
            removedBinding,
            REMOVE_BINDING_OPERATION,
            "service_connection_owner_mismatch"
        )
        assertRejected(removedAll, REMOVE_OPERATION, "service_connection_owner_mismatch")
        assertEquals(listOf(foreignBinding), connections.query(token).bindings)
    }

    @Test
    fun `authoritative caller can register query and remove its connection`() {
        val fixture = endpoint()
        val token = liveConnectionToken()
        val operationLease = lease()
        val expectedBinding = binding()

        val registered = fixture.endpoint.registerServiceConnection(
            INSTANCE_ID,
            operationLease.toIpcBundle(),
            token
        ).requireServiceConnectionResult()
        val queried = fixture.endpoint.queryServiceConnection(
            INSTANCE_ID,
            token
        ).requireServiceConnectionResult()
        val removed = fixture.endpoint.removeServiceConnection(
            INSTANCE_ID,
            token
        ).requireServiceConnectionResult()
        val afterRemoval = fixture.endpoint.queryServiceConnection(
            INSTANCE_ID,
            token
        ).requireServiceConnectionResult()

        assertAccepted(registered, REGISTER_OPERATION, listOf(expectedBinding))
        assertAccepted(queried, QUERY_OPERATION, listOf(expectedBinding))
        assertAccepted(removed, REMOVE_OPERATION, listOf(expectedBinding))
        assertRejected(afterRemoval, QUERY_OPERATION, "service_connection_not_found")
        assertEquals(0, fixture.connections.activeBindingCount())
        verify(exactly = 1) { fixture.leases.claimForConnection(operationLease, CALLING_PID) }
    }

    @Test
    fun `connection authority death reconciles engine Service bind state`() {
        val fixture = endpoint()
        val token = liveConnectionToken()

        val registered = fixture.endpoint.registerServiceConnection(
            INSTANCE_ID,
            lease().toIpcBundle(),
            token
        ).requireServiceConnectionResult()
        val removedCount = fixture.connections.handleBinderDeath(token)

        assertTrue(registered.accepted)
        assertEquals(1, removedCount)
        verify(exactly = 1) {
            fixture.serviceService.recordServiceDispatch(
                INSTANCE_ID,
                match { result ->
                    result.operation == VirtualServiceOperation.UNBIND &&
                        result.serviceClassName == SERVICE_COMPONENT &&
                        result.unbound &&
                        result.activeBindCount == 0 &&
                        result.reason == "service_connection_authority_released"
                }
            )
        }
    }

    @Test
    fun `failed bind dispatch aborts the claimed connection lease`() {
        val failedCommit = EngineServiceOperationLeaseDecision(
            accepted = false,
            idempotent = false,
            state = EngineServiceOperationLeaseState.CLAIMED,
            reason = "service_operation_lease_commit_action_failed"
        )
        val fixture = endpoint(commitDecision = failedCommit)
        val operationLease = lease()

        val accepted = fixture.endpoint.recordServiceDispatch(
            INSTANCE_ID,
            bindDispatchBundle(operationLease)
        )

        assertFalse(accepted)
        verify(exactly = 1) { fixture.leases.abort(operationLease, CALLING_PID) }
    }

    private fun endpoint(
        authority: EngineProcessAuthorityDecision = allowedAuthority(),
        connections: EngineServiceConnectionRegistry = EngineServiceConnectionRegistry(),
        leaseDecision: EngineServiceOperationLeaseDecision = EngineServiceOperationLeaseDecision(
            accepted = true,
            idempotent = false,
            state = EngineServiceOperationLeaseState.CLAIMED,
            reason = "service_operation_lease_connection_claimed"
        ),
        commitDecision: EngineServiceOperationLeaseDecision = EngineServiceOperationLeaseDecision(
            accepted = true,
            idempotent = false,
            state = EngineServiceOperationLeaseState.COMMITTED,
            reason = "service_operation_lease_committed"
        )
    ): EndpointFixture {
        val controlPlane = mockk<EngineProcessControlPlane>()
        every { controlPlane.authorize(INSTANCE_ID, CALLING_PID) } returns authority
        val leases = mockk<EngineServiceOperationLeaseCoordinator>()
        every { leases.claimForConnection(any(), CALLING_PID) } returns leaseDecision
        every { leases.commit(any(), CALLING_PID, any()) } returns commitDecision
        every { leases.abort(any(), CALLING_PID) } returns EngineServiceOperationLeaseDecision(
            accepted = true,
            idempotent = false,
            state = EngineServiceOperationLeaseState.ABORTED,
            reason = "service_operation_lease_aborted"
        )
        val registry = mockk<EngineRuntimeRegistry>(relaxed = true)
        val runtime = mockk<com.multiapp.core.model.engine.VirtualInstanceRuntime>()
        every { runtime.runtimeEpoch } returns RUNTIME_EPOCH
        every { runtime.engineSessionId } returns ENGINE_SESSION_ID
        every { registry.get(INSTANCE_ID) } returns runtime
        val serviceService = mockk<VirtualServiceService>()
        every { serviceService.recordServiceDispatch(any(), any()) } returns true

        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null)
        }
        val binderEndpoint = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, EngineRuntimeBinderEndpoint::class.java) as EngineRuntimeBinderEndpoint
        setEndpointField(binderEndpoint, "hostUid", HOST_UID)
        setEndpointField(binderEndpoint, "callingUid", { HOST_UID })
        setEndpointField(binderEndpoint, "callingPid", { CALLING_PID })
        setEndpointField(binderEndpoint, "registry", registry)
        setEndpointField(binderEndpoint, "processControlPlane", controlPlane)
        setEndpointField(binderEndpoint, "serviceOperationLeases", leases)
        setEndpointField(binderEndpoint, "serviceConnections", connections)
        setEndpointField(binderEndpoint, "serviceService", serviceService)
        setEndpointField(binderEndpoint, "ipcBundleFactory", bundles::create)
        return EndpointFixture(binderEndpoint, leases, connections, serviceService)
    }

    private fun bindDispatchBundle(operationLease: EngineServiceOperationLeaseIdentity): Bundle =
        bundles.create().apply {
            putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, INSTANCE_ID)
            putString(EngineRuntimeIpcContract.KEY_SERVICE_OPERATION, VirtualServiceOperation.BIND.name)
            putString(EngineRuntimeIpcContract.KEY_SERVICE_CLASS_NAME, SERVICE_COMPONENT)
            putString(EngineRuntimeIpcContract.KEY_VERDICT, EngineResultStatus.PASS.name)
            putString(EngineRuntimeIpcContract.KEY_REASON, "implicitBind")
            putBoolean(EngineRuntimeIpcContract.KEY_BOUND, true)
            putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, PROCESS_SLOT)
            putInt(EngineRuntimeIpcContract.KEY_ACTIVE_BIND_COUNT, 1)
            putBundle(
                EngineRuntimeIpcContract.KEY_SERVICE_OPERATION_LEASE,
                operationLease.toIpcBundle()
            )
            putString(EngineRuntimeIpcContract.KEY_MESSAGE, "loader_service_bound")
        }

    private fun setEndpointField(endpoint: EngineRuntimeBinderEndpoint, name: String, value: Any?) {
        EngineRuntimeBinderEndpoint::class.java.getDeclaredField(name).run {
            isAccessible = true
            set(endpoint, value)
        }
    }

    private fun allowedAuthority() = EngineProcessAuthorityDecision(
        allowed = true,
        identity = processIdentity(),
        reason = "live_runtime_authority_confirmed"
    )

    private fun processIdentity() = EngineProcessClientIdentity(
        instanceId = INSTANCE_ID,
        runtimeEpoch = RUNTIME_EPOCH,
        engineSessionId = ENGINE_SESSION_ID,
        processSlot = PROCESS_SLOT,
        processId = CALLING_PID
    )

    private fun lease(
        instanceId: String = INSTANCE_ID,
        runtimeEpoch: Long = RUNTIME_EPOCH,
        engineSessionId: String = ENGINE_SESSION_ID,
        processSlot: String = PROCESS_SLOT,
        processId: Int = CALLING_PID
    ) = EngineServiceOperationLeaseIdentity(
        leaseToken = "lease-token",
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = processSlot,
        processId = processId,
        operation = VirtualServiceOperation.BIND.name,
        component = SERVICE_COMPONENT,
        issuedAtNanos = 1L,
        expiresAtNanos = 2L
    )

    private fun binding(
        runtimeEpoch: Long = RUNTIME_EPOCH,
        engineSessionId: String = ENGINE_SESSION_ID,
        processId: Int = CALLING_PID
    ) = EngineServiceConnectionBindingRecord(
        instanceId = INSTANCE_ID,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = PROCESS_SLOT,
        processId = processId,
        component = SERVICE_COMPONENT
    )

    private fun liveConnectionToken(): IBinder = mockk(relaxed = true) {
        every { isBinderAlive } returns true
    }

    private fun Bundle.requireServiceConnectionResult(): EngineServiceConnectionOperationResult =
        assertNotNull(toServiceConnectionOperationResultOrNull())

    private fun assertAccepted(
        result: EngineServiceConnectionOperationResult,
        operation: String,
        bindings: List<EngineServiceConnectionBindingRecord>
    ) {
        assertEquals(operation, result.operation)
        assertTrue(result.accepted)
        assertFalse(result.idempotent)
        assertEquals(bindings, result.bindings)
    }

    private fun assertRejected(
        result: EngineServiceConnectionOperationResult,
        operation: String,
        reason: String
    ) {
        assertEquals(operation, result.operation)
        assertFalse(result.accepted)
        assertFalse(result.idempotent)
        assertEquals(emptyList(), result.bindings)
        assertEquals(reason, result.reason)
    }

    private data class EndpointFixture(
        val endpoint: EngineRuntimeBinderEndpoint,
        val leases: EngineServiceOperationLeaseCoordinator,
        val connections: EngineServiceConnectionRegistry,
        val serviceService: VirtualServiceService
    )

    private companion object {
        const val INSTANCE_ID = "instance-service-connection-endpoint"
        const val OTHER_INSTANCE_ID = "instance-service-connection-forged"
        const val PROCESS_SLOT = "com.multiapp.app:v2"
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val SERVICE_COMPONENT = "com.test.app.BoundService"
        const val RUNTIME_EPOCH = 42L
        const val HOST_UID = 10123
        const val CALLING_PID = 4200
        const val REGISTER_OPERATION = "registerServiceConnection"
        const val QUERY_OPERATION = "queryServiceConnection"
        const val REMOVE_BINDING_OPERATION = "removeServiceConnectionBinding"
        const val REMOVE_OPERATION = "removeServiceConnection"
    }
}
