package com.multiapp.core.loader

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Process-local runtime registry for hosted virtual app instances.
 *
 * A binding may publish a provisional runtime before Application.onCreate so
 * initialization code can re-enter the virtual runtime. That provisional
 * runtime is visible only to the initialization thread. Other callers wait for
 * the binding to reach READY and never reuse the provisional result.
 */
class VirtualProcessRuntime(
    private val clock: () -> Long = System::currentTimeMillis,
    private val bindingTimeoutMs: Long = DEFAULT_BINDING_TIMEOUT_MS,
    private val monotonicClockNanos: () -> Long = System::nanoTime
) {
    private val records = linkedMapOf<String, VirtualProcessRuntimeRecord>()
    private val bindings = linkedMapOf<String, InFlightBinding>()
    private val states = linkedMapOf<String, VirtualProcessRuntimeState>()

    init {
        require(bindingTimeoutMs > 0L) { "bindingTimeoutMs must be positive" }
    }

    fun bindApplication(
        instanceId: String,
        bootstrap: () -> HostedBootstrapResult
    ): HostedBootstrapResult = bindApplication(instanceId, null, bootstrap)

    fun bindApplication(
        instanceId: String,
        bindingFingerprint: HostedRuntimeBindingFingerprint?,
        bootstrap: () -> HostedBootstrapResult
    ): HostedBootstrapResult {
        requireFingerprintInstance(instanceId, bindingFingerprint)
        var binding: InFlightBinding? = null
        var shouldBootstrap = false
        synchronized(this) {
            readyRecordLocked(instanceId)?.let { ready ->
                if (bindingFingerprint == null || ready.bindingFingerprint == bindingFingerprint) {
                    return ready.result
                }
                throw bindingFingerprintMismatch(instanceId)
            }

            val current = bindings[instanceId]
            if (current != null) {
                expireBindingIfNeededLocked(instanceId, current)
                current.completedOutcome()?.let { return it.getOrThrow() }
                current.requireFingerprint(bindingFingerprint)
                if (current.isInitializationThread(Thread.currentThread())) {
                    return current.provisionalRecord?.result ?: throw IllegalStateException(
                        "Reentrant virtual process bind before provisional runtime publication: " +
                            "instanceId=$instanceId"
                    )
                }
                binding = current
            } else {
                if (states[instanceId] == VirtualProcessRuntimeState.TIMED_OUT) {
                    throw bindingTimeout(instanceId)
                }
                val created = InFlightBinding(
                    ownerThread = Thread.currentThread(),
                    deadlineNanos = monotonicClockNanos() +
                        TimeUnit.MILLISECONDS.toNanos(bindingTimeoutMs),
                    bindingFingerprint = bindingFingerprint
                )
                binding = created
                bindings[instanceId] = created
                records.remove(instanceId)
                states[instanceId] = VirtualProcessRuntimeState.BINDING
                shouldBootstrap = true
            }
        }
        val inFlight = binding
            ?: error("Virtual process bind was not initialized for instanceId=$instanceId")

        if (!shouldBootstrap) {
            return awaitBinding(instanceId, inFlight).getOrThrow()
        }

        val bootstrapOutcome = runCatching { bootstrap() }
        val finalOutcome = synchronized(this) {
            finishBindingLocked(instanceId, inFlight, bootstrapOutcome)
        }
        return finalOutcome.getOrThrow()
    }

    @Synchronized
    fun reusableResult(instanceId: String): HostedBootstrapResult? {
        reusableResultLocked(instanceId)?.let { return it }
        val binding = bindings[instanceId] ?: return null
        expireBindingIfNeededLocked(instanceId, binding)
        if (binding.completedOutcome() != null) return null
        return binding.provisionalRecord
            ?.takeIf { binding.isInitializationThread(Thread.currentThread()) }
            ?.result
    }

    @Synchronized
    fun reusableResult(
        instanceId: String,
        bindingFingerprint: HostedRuntimeBindingFingerprint
    ): HostedBootstrapResult? {
        requireFingerprintInstance(instanceId, bindingFingerprint)
        readyRecordLocked(instanceId)?.let { ready ->
            return ready.takeIf { it.bindingFingerprint == bindingFingerprint }?.result
        }
        val binding = bindings[instanceId] ?: return null
        expireBindingIfNeededLocked(instanceId, binding)
        if (binding.completedOutcome() != null || !binding.matchesFingerprint(bindingFingerprint)) return null
        return binding.provisionalRecord
            ?.takeIf { binding.isInitializationThread(Thread.currentThread()) }
            ?.result
    }

    @Synchronized
    fun rememberApplication(instanceId: String, result: HostedBootstrapResult) {
        rememberApplication(instanceId, result, null)
    }

    @Synchronized
    fun rememberApplication(
        instanceId: String,
        result: HostedBootstrapResult,
        bindingFingerprint: HostedRuntimeBindingFingerprint?
    ) {
        requireMatchingInstance(instanceId, result)
        requireFingerprintInstance(instanceId, bindingFingerprint)
        val binding = bindings[instanceId]
        if (binding == null) {
            when (states[instanceId]) {
                VirtualProcessRuntimeState.TIMED_OUT -> throw bindingTimeout(instanceId)
                VirtualProcessRuntimeState.BINDING -> error(
                    "Virtual process is BINDING without an active binding: instanceId=$instanceId"
                )
                VirtualProcessRuntimeState.FAILED -> error(
                    "Virtual process FAILED must be rebound before publishing Application: " +
                        "instanceId=$instanceId"
                )
                VirtualProcessRuntimeState.READY -> {
                    val current = records[instanceId] ?: error(
                        "Virtual process is READY without a runtime record: instanceId=$instanceId"
                    )
                    check(result.isReusableRuntime()) {
                        "Cannot replace READY virtual process with a non-reusable runtime: " +
                            "instanceId=$instanceId"
                    }
                    check(current.result.hasSameRuntimeIdentity(result)) {
                        "Cannot replace READY virtual process with a different ClassLoader/Application: " +
                            "instanceId=$instanceId"
                    }
                    check(bindingFingerprint == null || current.bindingFingerprint == bindingFingerprint) {
                        "Cannot replace READY virtual process with a different binding fingerprint: " +
                            "instanceId=$instanceId"
                    }
                }
                null -> Unit
            }
            rememberApplicationLocked(instanceId, result, bindingFingerprint)
            return
        }

        expireBindingIfNeededLocked(instanceId, binding)
        binding.completedOutcome()?.getOrThrow()
        binding.requireFingerprint(bindingFingerprint)
        if (!result.isReusableRuntime()) {
            binding.provisionalRecord = null
            return
        }
        binding.publishProvisional(
            record = runtimeRecord(
                instanceId,
                result,
                VirtualProcessRuntimeState.BINDING,
                binding.bindingFingerprint
            ),
            thread = Thread.currentThread()
        )
        records.remove(instanceId)
        states[instanceId] = VirtualProcessRuntimeState.BINDING
    }

    @Synchronized
    fun get(instanceId: String): VirtualProcessRuntimeRecord? {
        records[instanceId]?.let { return it }
        val binding = bindings[instanceId] ?: return null
        expireBindingIfNeededLocked(instanceId, binding)
        if (binding.completedOutcome() != null) return null
        return binding.provisionalRecord
            ?.takeIf { binding.isInitializationThread(Thread.currentThread()) }
    }

    @Synchronized
    fun state(instanceId: String): VirtualProcessRuntimeState? {
        bindings[instanceId]?.let { expireBindingIfNeededLocked(instanceId, it) }
        return states[instanceId]
    }

    @Synchronized
    fun list(): List<VirtualProcessRuntimeRecord> = records.values.toList()

    @Synchronized
    fun clear(instanceId: String): Boolean {
        val recordRemoved = records.remove(instanceId) != null
        val stateRemoved = states.remove(instanceId) != null
        val binding = bindings.remove(instanceId)
        binding?.apply {
            provisionalRecord = null
            complete(
                Result.failure(
                    CancellationException("Virtual process binding cleared: instanceId=$instanceId")
                )
            )
        }
        return recordRemoved || stateRemoved || binding != null
    }

    @Synchronized
    fun clearAll() {
        bindings.forEach { (instanceId, binding) ->
            binding.provisionalRecord = null
            binding.complete(
                Result.failure(
                    CancellationException("Virtual process binding cleared: instanceId=$instanceId")
                )
            )
        }
        records.clear()
        bindings.clear()
        states.clear()
    }

    companion object {
        const val DEFAULT_BINDING_TIMEOUT_MS: Long = 30_000L

        val global: VirtualProcessRuntime = VirtualProcessRuntime()
    }

    private fun awaitBinding(
        instanceId: String,
        binding: InFlightBinding
    ): Result<HostedBootstrapResult> = when (
        val awaited = binding.await(monotonicClockNanos())
    ) {
        is BindingAwaitResult.Completed -> awaited.outcome
        BindingAwaitResult.DeadlineReached -> synchronized(this) {
            if (bindings[instanceId] === binding) {
                timeoutBindingLocked(instanceId, binding)
            }
            binding.completedOutcome()
                ?: Result.failure(
                    IllegalStateException(
                        "Virtual process binding ended without an outcome: instanceId=$instanceId"
                    )
                )
        }
    }

    private fun finishBindingLocked(
        instanceId: String,
        binding: InFlightBinding,
        bootstrapOutcome: Result<HostedBootstrapResult>
    ): Result<HostedBootstrapResult> {
        if (bindings[instanceId] !== binding) {
            return binding.completedOutcome() ?: Result.failure(
                CancellationException("Virtual process binding replaced: instanceId=$instanceId")
            )
        }

        binding.completedOutcome()?.let { timedOut ->
            binding.provisionalRecord = null
            bindings.remove(instanceId)
            return timedOut
        }
        if (binding.hasReachedDeadline(monotonicClockNanos())) {
            val timedOut = timeoutBindingLocked(instanceId, binding)
            bindings.remove(instanceId)
            return timedOut
        }

        val finalOutcome = bootstrapOutcome.mapCatching { result ->
            requireMatchingInstance(instanceId, result)
            if (result.isReusableRuntime()) {
                binding.requireCompatibleReadyResult(result)
            }
            result
        }
        val result = finalOutcome.getOrNull()
        if (result != null && result.isReusableRuntime()) {
            rememberApplicationLocked(instanceId, result, binding.bindingFingerprint)
        } else {
            records.remove(instanceId)
            states[instanceId] = VirtualProcessRuntimeState.FAILED
        }
        binding.provisionalRecord = null
        binding.complete(finalOutcome)
        bindings.remove(instanceId)
        return finalOutcome
    }

    private fun expireBindingIfNeededLocked(instanceId: String, binding: InFlightBinding) {
        if (
            binding.completedOutcome() == null &&
            binding.hasReachedDeadline(monotonicClockNanos())
        ) {
            timeoutBindingLocked(instanceId, binding)
        }
    }

    private fun timeoutBindingLocked(
        instanceId: String,
        binding: InFlightBinding
    ): Result<HostedBootstrapResult> {
        binding.completedOutcome()?.let { return it }
        records.remove(instanceId)
        states[instanceId] = VirtualProcessRuntimeState.TIMED_OUT
        binding.provisionalRecord = null
        return Result.failure<HostedBootstrapResult>(bindingTimeout(instanceId)).also(binding::complete)
    }

    private fun bindingTimeout(instanceId: String): TimeoutException = TimeoutException(
        "Timed out after ${bindingTimeoutMs}ms waiting for virtual process READY: " +
            "instanceId=$instanceId"
    )

    private fun reusableResultLocked(instanceId: String): HostedBootstrapResult? =
        readyRecordLocked(instanceId)?.result

    private fun readyRecordLocked(instanceId: String): VirtualProcessRuntimeRecord? =
        records[instanceId]?.takeIf { it.state == VirtualProcessRuntimeState.READY }

    private fun rememberApplicationLocked(
        instanceId: String,
        result: HostedBootstrapResult,
        bindingFingerprint: HostedRuntimeBindingFingerprint?
    ) {
        if (result.isReusableRuntime()) {
            records[instanceId] = runtimeRecord(
                instanceId = instanceId,
                result = result,
                state = VirtualProcessRuntimeState.READY,
                bindingFingerprint = bindingFingerprint
            )
            states[instanceId] = VirtualProcessRuntimeState.READY
        } else {
            records.remove(instanceId)
            states[instanceId] = VirtualProcessRuntimeState.FAILED
        }
    }

    private fun runtimeRecord(
        instanceId: String,
        result: HostedBootstrapResult,
        state: VirtualProcessRuntimeState,
        bindingFingerprint: HostedRuntimeBindingFingerprint?
    ): VirtualProcessRuntimeRecord = VirtualProcessRuntimeRecord(
        instanceId = instanceId,
        originPackageName = result.originPackageName,
        virtualPackageName = result.virtualPackageName,
        processName = result.processSlot,
        boundAtMs = clock(),
        result = result,
        state = state,
        bindingFingerprint = bindingFingerprint
    )

    private fun requireMatchingInstance(instanceId: String, result: HostedBootstrapResult) {
        require(result.instanceId == instanceId) {
            "Virtual process result instance mismatch: expected=$instanceId,actual=${result.instanceId}"
        }
    }

    private fun requireFingerprintInstance(
        instanceId: String,
        bindingFingerprint: HostedRuntimeBindingFingerprint?
    ) {
        require(bindingFingerprint == null || bindingFingerprint.instanceId == instanceId) {
            "Virtual process fingerprint instance mismatch: expected=$instanceId," +
                "actual=${bindingFingerprint?.instanceId}"
        }
    }

    private fun bindingFingerprintMismatch(instanceId: String): IllegalStateException =
        IllegalStateException(
            "Virtual process is already bound with a different runtime fingerprint: instanceId=$instanceId"
        )
}

private class InFlightBinding(
    ownerThread: Thread,
    private val deadlineNanos: Long,
    val bindingFingerprint: HostedRuntimeBindingFingerprint?
) {
    private val latch = CountDownLatch(1)

    @Volatile
    private var outcome: Result<HostedBootstrapResult>? = null

    private val initializationThreads: MutableSet<Thread> =
        Collections.newSetFromMap(IdentityHashMap<Thread, Boolean>()).apply {
            add(ownerThread)
        }

    var provisionalRecord: VirtualProcessRuntimeRecord? = null

    fun completedOutcome(): Result<HostedBootstrapResult>? = outcome

    fun complete(completed: Result<HostedBootstrapResult>) {
        if (outcome != null) return
        outcome = completed
        latch.countDown()
    }

    fun publishProvisional(record: VirtualProcessRuntimeRecord, thread: Thread) {
        val existing = provisionalRecord
        check(existing == null || isInitializationThread(thread)) {
            "Provisional virtual runtime may only be updated by its initialization thread"
        }
        check(existing == null || existing.result.hasSameRuntimeIdentity(record.result)) {
            "Provisional virtual runtime ClassLoader/Application changed during initialization: " +
                "instanceId=${record.instanceId}"
        }
        initializationThreads += thread
        provisionalRecord = record
    }

    fun requireCompatibleReadyResult(result: HostedBootstrapResult) {
        val provisional = provisionalRecord?.result ?: return
        check(provisional.canTransitionToReadyResult(result)) {
            "READY virtual runtime does not match the provisional ClassLoader/Application: " +
                "instanceId=${result.instanceId}"
        }
    }

    fun matchesFingerprint(candidate: HostedRuntimeBindingFingerprint?): Boolean =
        candidate == null || bindingFingerprint == candidate

    fun requireFingerprint(candidate: HostedRuntimeBindingFingerprint?) {
        check(matchesFingerprint(candidate)) {
            "Concurrent virtual process bind used a different runtime fingerprint"
        }
    }

    fun isInitializationThread(thread: Thread): Boolean = initializationThreads.contains(thread)

    fun hasReachedDeadline(nowNanos: Long): Boolean = deadlineNanos - nowNanos <= 0L

    fun await(nowNanos: Long): BindingAwaitResult {
        outcome?.let { return BindingAwaitResult.Completed(it) }
        val remainingNanos = deadlineNanos - nowNanos
        if (remainingNanos <= 0L) return BindingAwaitResult.DeadlineReached
        return try {
            if (latch.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                BindingAwaitResult.Completed(
                    outcome ?: Result.failure(
                        IllegalStateException("Virtual process bind completed without a result")
                    )
                )
            } else {
                outcome?.let(BindingAwaitResult::Completed)
                    ?: BindingAwaitResult.DeadlineReached
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            BindingAwaitResult.Completed(Result.failure(error))
        }
    }
}

private sealed class BindingAwaitResult {
    data class Completed(val outcome: Result<HostedBootstrapResult>) : BindingAwaitResult()
    data object DeadlineReached : BindingAwaitResult()
}

enum class VirtualProcessRuntimeState {
    BINDING,
    READY,
    FAILED,
    TIMED_OUT
}

data class VirtualProcessRuntimeRecord(
    val instanceId: String,
    val originPackageName: String?,
    val virtualPackageName: String?,
    val processName: String?,
    val boundAtMs: Long,
    val result: HostedBootstrapResult,
    val state: VirtualProcessRuntimeState = VirtualProcessRuntimeState.READY,
    val bindingFingerprint: HostedRuntimeBindingFingerprint? = null
)

private fun HostedBootstrapResult.isReusableRuntime(): Boolean =
    success &&
        guestClassLoader != null &&
        guestApplication != null &&
        hasReadyLoadedApkApplication()

private fun HostedBootstrapResult.hasReadyLoadedApkApplication(): Boolean {
    val applicationStage = stageResults.lastOrNull { it.stage == RuntimeStage.APPLICATION }
        ?: return true // Provisional and synthetic results do not carry final stage evidence.
    if (applicationStage.status != BootstrapStatus.SUCCESS) return false
    return applicationStage.evidence.lastOrNull {
        it.key == "loadedApkApplicationCreatorStatus"
    }?.value == "PASS"
}

internal fun HostedBootstrapResult.hasSameRuntimeIdentity(other: HostedBootstrapResult): Boolean =
    instanceId == other.instanceId &&
        originPackageName == other.originPackageName &&
        virtualPackageName == other.virtualPackageName &&
        processSlot == other.processSlot &&
        guestClassLoader === other.guestClassLoader &&
        guestApplication === other.guestApplication

private fun HostedBootstrapResult.canTransitionToReadyResult(
    ready: HostedBootstrapResult
): Boolean {
    val sameBaseIdentity = instanceId == ready.instanceId &&
        originPackageName == ready.originPackageName &&
        virtualPackageName == ready.virtualPackageName &&
        processSlot == ready.processSlot &&
        guestClassLoader === ready.guestClassLoader
    if (!sameBaseIdentity) return false
    if (guestApplication === ready.guestApplication) return true

    // Some hot-fix frameworks replace LoadedApk.mApplication during onCreate.
    // ApplicationStage accepts that delegate only after proving that its
    // ContextImpl still points at the same guest LoadedApk.
    val applicationStage = ready.stageResults.lastOrNull {
        it.stage == RuntimeStage.APPLICATION && it.status == BootstrapStatus.SUCCESS
    } ?: return false
    val evidence = applicationStage.evidence.associate { it.key to it.value }
    return evidence["loadedApkFinalApplicationStatus"] == "PASS" &&
        evidence["loadedApkFinalApplicationSource"] == "DELEGATE"
}
