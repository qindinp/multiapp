package com.multiapp.core.loader

import java.util.concurrent.CountDownLatch

/**
 * Process-local runtime registry for hosted virtual app instances.
 *
 * VirtualApp/BlackBox-style containers keep a process runtime layer between
 * install/instance records and component launch. MultiApp v2 uses this class
 * as that boundary: binding an instance creates the guest ClassLoader and
 * Application once, then later Activity launches reuse the same runtime state.
 */
class VirtualProcessRuntime(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val records = linkedMapOf<String, VirtualProcessRuntimeRecord>()
    private val bindings = linkedMapOf<String, InFlightBinding>()

    fun bindApplication(
        instanceId: String,
        bootstrap: () -> HostedBootstrapResult
    ): HostedBootstrapResult {
        var binding: InFlightBinding? = null
        var shouldBootstrap = false
        synchronized(this) {
            reusableResultLocked(instanceId)?.let { return it }

            val current = bindings[instanceId]
            if (current != null) {
                binding = current
            } else {
                val created = InFlightBinding()
                binding = created
                bindings[instanceId] = created
                shouldBootstrap = true
            }
        }
        val inFlight = binding
            ?: error("Virtual process bind was not initialized for instanceId=$instanceId")

        if (!shouldBootstrap) {
            return inFlight.await().getOrThrow()
        }

        val outcome = runCatching { bootstrap() }
        synchronized(this) {
            outcome.getOrNull()?.let { rememberApplicationLocked(instanceId, it) }
            bindings.remove(instanceId)
        }
        inFlight.complete(outcome)
        return outcome.getOrThrow()
    }

    @Synchronized
    fun reusableResult(instanceId: String): HostedBootstrapResult? =
        reusableResultLocked(instanceId)

    @Synchronized
    fun rememberApplication(instanceId: String, result: HostedBootstrapResult) {
        rememberApplicationLocked(instanceId, result)
    }

    @Synchronized
    fun get(instanceId: String): VirtualProcessRuntimeRecord? = records[instanceId]

    @Synchronized
    fun list(): List<VirtualProcessRuntimeRecord> = records.values.toList()

    @Synchronized
    fun clear(instanceId: String): Boolean = records.remove(instanceId) != null

    @Synchronized
    fun clearAll() {
        records.clear()
        bindings.clear()
    }

    companion object {
        val global: VirtualProcessRuntime = VirtualProcessRuntime()
    }

    private fun reusableResultLocked(instanceId: String): HostedBootstrapResult? =
        records[instanceId]?.result?.takeIf { it.isReusableRuntime() }

    private fun rememberApplicationLocked(instanceId: String, result: HostedBootstrapResult) {
        if (result.isReusableRuntime()) {
            records[instanceId] = VirtualProcessRuntimeRecord(
                instanceId = instanceId,
                originPackageName = result.originPackageName,
                virtualPackageName = result.virtualPackageName,
                processName = result.originPackageName,
                boundAtMs = clock(),
                result = result
            )
        } else {
            records.remove(instanceId)
        }
    }
}

private class InFlightBinding {
    private val latch = CountDownLatch(1)

    @Volatile
    private var outcome: Result<HostedBootstrapResult>? = null

    fun complete(outcome: Result<HostedBootstrapResult>) {
        this.outcome = outcome
        latch.countDown()
    }

    fun await(): Result<HostedBootstrapResult> {
        try {
            latch.await()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.failure(error)
        }
        return outcome ?: Result.failure(
            IllegalStateException("Virtual process bind completed without a result")
        )
    }
}

data class VirtualProcessRuntimeRecord(
    val instanceId: String,
    val originPackageName: String?,
    val virtualPackageName: String?,
    val processName: String?,
    val boundAtMs: Long,
    val result: HostedBootstrapResult
)

private fun HostedBootstrapResult.isReusableRuntime(): Boolean =
    success && guestClassLoader != null
