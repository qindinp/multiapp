package com.multiapp.core.engine

import android.os.Bundle
import android.os.IBinder

data class EngineServiceConnectionOperationResult(
    val operation: String,
    val accepted: Boolean,
    val idempotent: Boolean,
    val bindings: List<EngineServiceConnectionBindingRecord>,
    val reason: String
) {
    init {
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

interface EngineServiceConnectionAuthority {
    fun register(
        instanceId: String,
        operationLease: EngineServiceOperationLeaseIdentity,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult?

    fun query(
        instanceId: String,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult?

    fun removeBinding(
        instanceId: String,
        binding: EngineServiceConnectionBindingRecord,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult?

    fun remove(
        instanceId: String,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult?
}

object IpcEngineServiceConnectionAuthority : EngineServiceConnectionAuthority {
    override fun register(
        instanceId: String,
        operationLease: EngineServiceOperationLeaseIdentity,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? =
        EngineRuntimeIpcClients.registerServiceConnection(instanceId, operationLease, connectionToken)

    override fun query(
        instanceId: String,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? =
        EngineRuntimeIpcClients.queryServiceConnection(instanceId, connectionToken)

    override fun removeBinding(
        instanceId: String,
        binding: EngineServiceConnectionBindingRecord,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? =
        EngineRuntimeIpcClients.removeServiceConnectionBinding(instanceId, binding, connectionToken)

    override fun remove(
        instanceId: String,
        connectionToken: IBinder
    ): EngineServiceConnectionOperationResult? =
        EngineRuntimeIpcClients.removeServiceConnection(instanceId, connectionToken)
}

internal fun EngineServiceConnectionBindingRecord.toServiceConnectionIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH, runtimeEpoch)
        putString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID, engineSessionId)
        putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
        putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, processId)
        putString(EngineRuntimeIpcContract.KEY_COMPONENT, component)
    }

internal fun Bundle.toServiceConnectionBindingOrNull(): EngineServiceConnectionBindingRecord? =
    runCatching {
        if (keySet() != SERVICE_CONNECTION_BINDING_FIELDS) return@runCatching null
        EngineServiceConnectionBindingRecord(
            instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
            runtimeEpoch = getLong(EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH),
            engineSessionId = getString(EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID).orEmpty(),
            processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
            processId = getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID),
            component = getString(EngineRuntimeIpcContract.KEY_COMPONENT).orEmpty()
        ).takeIf(EngineServiceConnectionBindingRecord::fitsServiceConnectionIpcBudget)
    }.getOrNull()

internal fun EngineServiceConnectionOperationResult.toServiceConnectionIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
        putString(EngineRuntimeIpcContract.KEY_OPERATION, operation)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, idempotent)
        putParcelableArrayList(
            EngineRuntimeIpcContract.KEY_SERVICE_CONNECTION_BINDINGS,
            ArrayList(bindings.map { binding -> binding.toServiceConnectionIpcBundle(bundleFactory) })
        )
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

internal fun Bundle.toServiceConnectionOperationResultOrNull(): EngineServiceConnectionOperationResult? =
    runCatching {
        if (keySet() != SERVICE_CONNECTION_RESULT_FIELDS) return@runCatching null
        if (
            !hasStrictBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED) ||
            !hasStrictBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT)
        ) {
            return@runCatching null
        }
        val bindingBundles = getParcelableArrayList<Bundle>(
            EngineRuntimeIpcContract.KEY_SERVICE_CONNECTION_BINDINGS
        ) ?: return@runCatching null
        if (bindingBundles.size > EngineRuntimeIpcContract.MAX_SERVICE_CONNECTION_BINDING_COUNT) {
            return@runCatching null
        }
        val bindings = bindingBundles.map { binding ->
            binding.toServiceConnectionBindingOrNull() ?: return@runCatching null
        }
        val result = EngineServiceConnectionOperationResult(
            operation = getString(EngineRuntimeIpcContract.KEY_OPERATION).orEmpty(),
            accepted = getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED),
            idempotent = getBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT),
            bindings = bindings,
            reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty()
        )
        result.takeIf(EngineServiceConnectionOperationResult::isValidServiceConnectionIpcResult)
    }.getOrNull()

private fun Bundle.hasStrictBoolean(key: String): Boolean =
    containsKey(key) && getBoolean(key, false) == getBoolean(key, true)

private fun EngineServiceConnectionBindingRecord.fitsServiceConnectionIpcBudget(): Boolean =
    listOf(instanceId, engineSessionId, processSlot, component).all { value ->
        value.length <= EngineRuntimeIpcContract.MAX_SERVICE_CONNECTION_IDENTITY_LENGTH
    }

private fun EngineServiceConnectionOperationResult.isValidServiceConnectionIpcResult(): Boolean {
    if (operation !in SERVICE_CONNECTION_OPERATIONS) return false
    if (operation.length > EngineRuntimeIpcContract.MAX_SERVICE_CONNECTION_IDENTITY_LENGTH) return false
    if (reason.length > EngineRuntimeIpcContract.MAX_SERVICE_CONNECTION_REASON_LENGTH) return false
    if (bindings.distinct().size != bindings.size) return false
    if (accepted != bindings.isNotEmpty()) return false
    if (idempotent && (!accepted || operation != SERVICE_CONNECTION_REGISTER_OPERATION)) return false
    if (bindings.isNotEmpty()) {
        val owner = bindings.first()
        if (bindings.any { binding -> !binding.hasSameServiceConnectionOwner(owner) }) return false
    }
    return when (operation) {
        SERVICE_CONNECTION_REGISTER_OPERATION,
        SERVICE_CONNECTION_REMOVE_BINDING_OPERATION -> bindings.size <= 1
        SERVICE_CONNECTION_QUERY_OPERATION,
        SERVICE_CONNECTION_REMOVE_OPERATION -> true
        else -> false
    }
}

private fun EngineServiceConnectionBindingRecord.hasSameServiceConnectionOwner(
    other: EngineServiceConnectionBindingRecord
): Boolean = instanceId == other.instanceId &&
    runtimeEpoch == other.runtimeEpoch &&
    engineSessionId == other.engineSessionId &&
    processSlot == other.processSlot &&
    processId == other.processId

private val SERVICE_CONNECTION_BINDING_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_RUNTIME_EPOCH,
    EngineRuntimeIpcContract.KEY_ENGINE_SESSION_ID,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_PROCESS_ID,
    EngineRuntimeIpcContract.KEY_COMPONENT
)

private val SERVICE_CONNECTION_RESULT_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_OPERATION,
    EngineRuntimeIpcContract.KEY_ACCEPTED,
    EngineRuntimeIpcContract.KEY_IDEMPOTENT,
    EngineRuntimeIpcContract.KEY_SERVICE_CONNECTION_BINDINGS,
    EngineRuntimeIpcContract.KEY_REASON
)

private val SERVICE_CONNECTION_OPERATIONS = setOf(
    SERVICE_CONNECTION_REGISTER_OPERATION,
    SERVICE_CONNECTION_QUERY_OPERATION,
    SERVICE_CONNECTION_REMOVE_BINDING_OPERATION,
    SERVICE_CONNECTION_REMOVE_OPERATION
)
