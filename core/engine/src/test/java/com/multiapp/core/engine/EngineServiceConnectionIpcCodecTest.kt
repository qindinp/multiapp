package com.multiapp.core.engine

import android.os.Bundle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.IdentityHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineServiceConnectionIpcCodecTest {
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
    fun `binding and operation result survive strict Bundle round trip`() {
        val first = binding(component = FIRST_SERVICE)
        val second = binding(component = SECOND_SERVICE)
        val queryResult = result(
            operation = QUERY_OPERATION,
            accepted = true,
            bindings = listOf(first, second),
            reason = "authoritative_service_connection_found"
        )
        val missingResult = result(
            operation = QUERY_OPERATION,
            accepted = false,
            bindings = emptyList(),
            reason = "service_connection_not_found"
        )
        val encodedBinding = first.toServiceConnectionIpcBundle(bundles::create)
        val encodedQuery = queryResult.toServiceConnectionIpcBundle(bundles::create)

        assertEquals(INSTANCE_ID, encodedBinding.getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID))
        assertEquals(RUNTIME_EPOCH, encodedBinding.getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH))
        assertEquals(CALLING_PID, encodedBinding.getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID))
        assertEquals(6, encodedBinding.keySet().size)
        assertEquals(first, encodedBinding.toServiceConnectionBindingOrNull())
        assertEquals(true, encodedQuery.getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED))
        assertEquals(
            true,
            encodedQuery.getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, false)
        )
        assertEquals(
            2,
            encodedQuery.getParcelableArrayList<Bundle>(
                EngineRuntimeIpcContract.KEY_SERVICE_CONNECTION_BINDINGS
            )?.size
        )
        assertEquals(
            queryResult,
            encodedQuery.toServiceConnectionOperationResultOrNull()
        )
        assertEquals(
            missingResult,
            missingResult.toServiceConnectionIpcBundle(bundles::create)
                .toServiceConnectionOperationResultOrNull()
        )
    }

    @Test
    fun `codec rejects extra and missing fields at both schema levels`() {
        val extraBinding = binding().toServiceConnectionIpcBundle(bundles::create).apply {
            putString("unexpected", "forged")
        }
        val missingBinding = binding().toServiceConnectionIpcBundle(bundles::create).apply {
            remove(EngineRuntimeIpcContract.KEY_COMPONENT)
        }
        val extraResult = acceptedQueryResult().toServiceConnectionIpcBundle(bundles::create).apply {
            putString("unexpected", "forged")
        }
        val missingResult = acceptedQueryResult().toServiceConnectionIpcBundle(bundles::create).apply {
            remove(EngineRuntimeIpcContract.KEY_REASON)
        }
        val extraNestedBinding = acceptedQueryResult().toServiceConnectionIpcBundle(bundles::create).apply {
            getParcelableArrayList<Bundle>(
                EngineRuntimeIpcContract.KEY_SERVICE_CONNECTION_BINDINGS
            )?.single()?.putString("unexpected", "forged")
        }

        assertNull(extraBinding.toServiceConnectionBindingOrNull())
        assertNull(missingBinding.toServiceConnectionBindingOrNull())
        assertNull(extraResult.toServiceConnectionOperationResultOrNull())
        assertNull(missingResult.toServiceConnectionOperationResultOrNull())
        assertNull(extraNestedBinding.toServiceConnectionOperationResultOrNull())
    }

    @Test
    fun `codec rejects invalid primitive types and domain values`() {
        val invalidEpochType = binding().toServiceConnectionIpcBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, "not-a-long")
        }
        val blankComponent = binding().toServiceConnectionIpcBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_COMPONENT, "")
        }
        val invalidAcceptedType = rejectedQueryResult().toServiceConnectionIpcBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_ACCEPTED, "false")
        }
        val invalidIdempotentType = result(
            operation = REGISTER_OPERATION,
            accepted = true,
            idempotent = false,
            bindings = listOf(binding()),
            reason = "service_connection_bound"
        ).toServiceConnectionIpcBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_IDEMPOTENT, "false")
        }
        val unknownOperation = acceptedQueryResult().toServiceConnectionIpcBundle(bundles::create).apply {
            putString(EngineRuntimeIpcContract.KEY_OPERATION, "forgedServiceConnectionOperation")
        }
        val invalidNestedBinding = acceptedQueryResult().toServiceConnectionIpcBundle(bundles::create).apply {
            getParcelableArrayList<Bundle>(
                EngineRuntimeIpcContract.KEY_SERVICE_CONNECTION_BINDINGS
            )?.single()?.putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, 0)
        }

        assertNull(invalidEpochType.toServiceConnectionBindingOrNull())
        assertNull(blankComponent.toServiceConnectionBindingOrNull())
        assertNull(invalidAcceptedType.toServiceConnectionOperationResultOrNull())
        assertNull(invalidIdempotentType.toServiceConnectionOperationResultOrNull())
        assertNull(unknownOperation.toServiceConnectionOperationResultOrNull())
        assertNull(invalidNestedBinding.toServiceConnectionOperationResultOrNull())
    }

    @Test
    fun `codec rejects identity reason and binding count budget overflow`() {
        val overlongIdentity = binding(
            component = "x".repeat(EngineRuntimeIpcContract.MAX_SERVICE_CONNECTION_IDENTITY_LENGTH + 1)
        ).toServiceConnectionIpcBundle(bundles::create)
        val overlongReason = result(
            operation = QUERY_OPERATION,
            accepted = false,
            bindings = emptyList(),
            reason = "x".repeat(EngineRuntimeIpcContract.MAX_SERVICE_CONNECTION_REASON_LENGTH + 1)
        ).toServiceConnectionIpcBundle(bundles::create)
        val tooManyBindings = result(
            operation = QUERY_OPERATION,
            accepted = true,
            bindings = List(EngineRuntimeIpcContract.MAX_SERVICE_CONNECTION_BINDING_COUNT + 1) { index ->
                binding(component = "$ORIGIN_PACKAGE.Service$index")
            },
            reason = "authoritative_service_connection_found"
        ).toServiceConnectionIpcBundle(bundles::create)

        assertNull(overlongIdentity.toServiceConnectionBindingOrNull())
        assertNull(overlongReason.toServiceConnectionOperationResultOrNull())
        assertNull(tooManyBindings.toServiceConnectionOperationResultOrNull())
    }

    @Test
    fun `codec rejects contradictory duplicate mixed-owner and cardinality invariants`() {
        val first = binding(component = FIRST_SERVICE)
        val second = binding(component = SECOND_SERVICE)
        val foreignOwner = binding(
            runtimeEpoch = RUNTIME_EPOCH + 1,
            engineSessionId = "$ENGINE_SESSION_ID-new",
            component = SECOND_SERVICE
        )
        val invalidResults = listOf(
            result(REGISTER_OPERATION, accepted = false, bindings = listOf(first)),
            result(QUERY_OPERATION, accepted = true, bindings = emptyList()),
            result(QUERY_OPERATION, accepted = true, idempotent = true, bindings = listOf(first)),
            result(QUERY_OPERATION, accepted = true, bindings = listOf(first, first)),
            result(QUERY_OPERATION, accepted = true, bindings = listOf(first, foreignOwner)),
            result(REGISTER_OPERATION, accepted = true, bindings = listOf(first, second)),
            result(REMOVE_BINDING_OPERATION, accepted = true, bindings = listOf(first, second))
        )

        invalidResults.forEach { invalid ->
            assertNull(
                invalid.toServiceConnectionIpcBundle(bundles::create)
                    .toServiceConnectionOperationResultOrNull()
            )
        }
    }

    private fun acceptedQueryResult() = result(
        operation = QUERY_OPERATION,
        accepted = true,
        bindings = listOf(binding()),
        reason = "authoritative_service_connection_found"
    )

    private fun rejectedQueryResult() = result(
        operation = QUERY_OPERATION,
        accepted = false,
        bindings = emptyList(),
        reason = "service_connection_not_found"
    )

    private fun result(
        operation: String,
        accepted: Boolean,
        idempotent: Boolean = false,
        bindings: List<EngineServiceConnectionBindingRecord>,
        reason: String = "test-reason"
    ) = EngineServiceConnectionOperationResult(
        operation = operation,
        accepted = accepted,
        idempotent = idempotent,
        bindings = bindings,
        reason = reason
    )

    private fun binding(
        instanceId: String = INSTANCE_ID,
        runtimeEpoch: Long = RUNTIME_EPOCH,
        engineSessionId: String = ENGINE_SESSION_ID,
        processSlot: String = PROCESS_SLOT,
        processId: Int = CALLING_PID,
        component: String = FIRST_SERVICE
    ) = EngineServiceConnectionBindingRecord(
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = processSlot,
        processId = processId,
        component = component
    )

    private companion object {
        const val INSTANCE_ID = "instance-service-connection-ipc"
        const val ORIGIN_PACKAGE = "com.test.app"
        const val FIRST_SERVICE = "$ORIGIN_PACKAGE.FirstService"
        const val SECOND_SERVICE = "$ORIGIN_PACKAGE.SecondService"
        const val PROCESS_SLOT = "com.multiapp.app:v2"
        const val ENGINE_SESSION_ID = "engine-session-42"
        const val RUNTIME_EPOCH = 42L
        const val CALLING_PID = 4200
        const val REGISTER_OPERATION = "registerServiceConnection"
        const val QUERY_OPERATION = "queryServiceConnection"
        const val REMOVE_BINDING_OPERATION = "removeServiceConnectionBinding"
    }
}

internal class ServiceConnectionBundleHarness {
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
        every {
            anyConstructed<Bundle>().putParcelableArrayList(any(), any())
        } answers {
            valuesFor(self as Bundle)[firstArg()] = secondArg<ArrayList<Bundle>?>()
        }
        every {
            anyConstructed<Bundle>().getParcelableArrayList<Bundle>(any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            valuesFor(self as Bundle)[firstArg()] as? ArrayList<Bundle>
        }
        every { anyConstructed<Bundle>().containsKey(any()) } answers {
            valuesFor(self as Bundle).containsKey(firstArg())
        }
        every { anyConstructed<Bundle>().get(any()) } answers {
            valuesFor(self as Bundle)[firstArg()]
        }
        every { anyConstructed<Bundle>().remove(any()) } answers {
            valuesFor(self as Bundle).remove(firstArg<String>())
            Unit
        }
        every { anyConstructed<Bundle>().keySet() } answers {
            valuesFor(self as Bundle).keys
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
        every { bundle.putParcelableArrayList(any(), any()) } answers {
            valuesFor(bundle)[firstArg()] = secondArg<ArrayList<Bundle>?>()
        }
        every { bundle.getParcelableArrayList<Bundle>(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            valuesFor(bundle)[firstArg()] as? ArrayList<Bundle>
        }
        every { bundle.containsKey(any()) } answers { valuesFor(bundle).containsKey(firstArg()) }
        every { bundle.get(any()) } answers { valuesFor(bundle)[firstArg()] }
        every { bundle.remove(any()) } answers {
            valuesFor(bundle).remove(firstArg<String>())
            Unit
        }
        every { bundle.keySet() } answers { valuesFor(bundle).keys }
        return bundle
    }

    private fun valuesFor(bundle: Bundle): MutableMap<String, Any?> =
        values.getOrPut(bundle) { linkedMapOf() }
}
