package com.multiapp.core.loader

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

    @Synchronized
    fun bindApplication(
        instanceId: String,
        bootstrap: () -> HostedBootstrapResult
    ): HostedBootstrapResult {
        records[instanceId]?.let { existing ->
            if (existing.result.isReusableRuntime()) {
                return existing.result
            }
        }

        val result = bootstrap()
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
        return result
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
    }

    companion object {
        val global: VirtualProcessRuntime = VirtualProcessRuntime()
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
