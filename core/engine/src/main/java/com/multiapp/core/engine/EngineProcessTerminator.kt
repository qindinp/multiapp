package com.multiapp.core.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.multiapp.core.model.engine.EngineProcessSlotContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

data class EngineProcessTerminationResult(
    val confirmed: Boolean,
    val status: String,
    val processId: Int?,
    val message: String
)

fun interface EngineProcessTerminator {
    fun terminateAndAwait(
        instanceId: String,
        processSlot: String,
        expectedProcessId: Int?
    ): EngineProcessTerminationResult

    companion object {
        internal val TEST_NO_OP = EngineProcessTerminator { _, _, processId ->
            EngineProcessTerminationResult(
                confirmed = true,
                status = "TEST_NO_OP",
                processId = processId,
                message = "test process termination bypass"
            )
        }
    }
}

internal data class EngineRunningProcess(
    val processName: String,
    val uid: Int,
    val processId: Int
)

internal data class EngineProcessProbe(
    val exists: Boolean,
    val commandLine: String? = null,
    val failure: String? = null
)

class AndroidEngineProcessTerminator internal constructor(
    private val hostPackageName: String,
    private val hostUid: Int,
    private val hostProcessId: () -> Int,
    private val runningProcesses: () -> List<EngineRunningProcess>,
    private val processProbe: (Int) -> EngineProcessProbe,
    private val processKiller: (Int) -> Unit,
    private val nanoClock: () -> Long,
    private val sleeper: (Long) -> Unit,
    private val terminationTimeoutMs: Long = DEFAULT_TERMINATION_TIMEOUT_MS,
    private val terminationPollMs: Long = DEFAULT_TERMINATION_POLL_MS
) : EngineProcessTerminator {

    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        hostPackageName = processHostContext(context).packageName,
        hostUid = processHostContext(context).applicationInfo.uid,
        hostProcessId = Process::myPid,
        runningProcesses = AndroidRunningProcessSource(processHostContext(context)),
        processProbe = ProcCommandLineProbe,
        processKiller = Process::killProcess,
        nanoClock = System::nanoTime,
        sleeper = Thread::sleep
    )

    init {
        require(hostPackageName.isNotBlank()) { "hostPackageName must not be blank" }
        require(hostUid >= 0) { "hostUid must not be negative" }
        require(terminationTimeoutMs >= 0L) { "terminationTimeoutMs must not be negative" }
        require(terminationPollMs > 0L) { "terminationPollMs must be positive" }
    }

    override fun terminateAndAwait(
        instanceId: String,
        processSlot: String,
        expectedProcessId: Int?
    ): EngineProcessTerminationResult {
        if (instanceId.isBlank() || !isGuestProcessSlot(processSlot)) {
            return rejected("INVALID_GUEST_PROCESS_SLOT", expectedProcessId)
        }
        if (expectedProcessId != null && expectedProcessId <= 0) {
            return rejected("INVALID_PROCESS_ID", expectedProcessId)
        }

        val matches = runCatching { runningProcesses() }
            .getOrElse { error ->
                return rejected(
                    status = "PROCESS_LIST_UNAVAILABLE",
                    processId = expectedProcessId,
                    message = error.describe()
                )
            }
            .filter { process ->
                process.processName == processSlot && process.uid == hostUid && process.processId > 0
            }
        if (matches.size > 1) return rejected("AMBIGUOUS_PROCESS_SLOT", expectedProcessId)

        val listedProcessId = matches.singleOrNull()?.processId
        if (listedProcessId != null && expectedProcessId != null && listedProcessId != expectedProcessId) {
            return rejected("PROCESS_ID_MISMATCH", listedProcessId)
        }
        val processId = listedProcessId ?: expectedProcessId
            ?: return notRunning(null)
        if (processId == hostProcessId()) return rejected("HOST_PROCESS_REJECTED", processId)

        when (val identity = targetIdentity(processId, processSlot)) {
            TargetIdentity.MATCH -> Unit
            TargetIdentity.ABSENT -> return notRunning(processId)
            TargetIdentity.DIFFERENT -> {
                if (listedProcessId == null) return notRunning(processId)
                return rejected("PROCESS_SLOT_MISMATCH", processId)
            }
            TargetIdentity.UNVERIFIED -> {
                return rejected("PROCESS_IDENTITY_UNVERIFIED", processId)
            }
        }

        val killError = runCatching { processKiller(processId) }.exceptionOrNull()
        if (killError != null) {
            return rejected(
                status = "KILL_FAILED",
                processId = processId,
                message = killError.describe()
            )
        }

        val timeoutNanos = terminationTimeoutMs * NANOS_PER_MILLISECOND
        val startedAt = nanoClock()
        while (nanoClock() - startedAt <= timeoutNanos) {
            when (targetIdentity(processId, processSlot)) {
                TargetIdentity.ABSENT,
                TargetIdentity.DIFFERENT -> {
                    return EngineProcessTerminationResult(
                        confirmed = true,
                        status = "TERMINATED",
                        processId = processId,
                        message = "guest process termination confirmed"
                    )
                }
                TargetIdentity.MATCH,
                TargetIdentity.UNVERIFIED -> Unit
            }
            try {
                sleeper(terminationPollMs)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return rejected(
                    status = "TERMINATION_INTERRUPTED",
                    processId = processId,
                    message = error.describe()
                )
            }
        }
        return rejected("TERMINATION_TIMEOUT", processId)
    }

    private fun isGuestProcessSlot(processSlot: String): Boolean =
        EngineProcessSlotContract.isCanonicalProcessSlot(hostPackageName, processSlot)

    private fun targetIdentity(processId: Int, processSlot: String): TargetIdentity {
        val probe = runCatching { processProbe(processId) }
            .getOrElse { return TargetIdentity.UNVERIFIED }
        if (!probe.exists) return TargetIdentity.ABSENT
        if (probe.failure != null || probe.commandLine.isNullOrBlank()) {
            return TargetIdentity.UNVERIFIED
        }
        return if (probe.commandLine == processSlot) TargetIdentity.MATCH else TargetIdentity.DIFFERENT
    }

    private fun notRunning(processId: Int?) = EngineProcessTerminationResult(
        confirmed = true,
        status = "NOT_RUNNING",
        processId = processId,
        message = "guest process is not running"
    )

    private fun rejected(
        status: String,
        processId: Int?,
        message: String = status.lowercase()
    ) = EngineProcessTerminationResult(
        confirmed = false,
        status = status,
        processId = processId,
        message = message
    )

    private fun Throwable.describe(): String =
        "${javaClass.name}:${message.orEmpty()}"

    private enum class TargetIdentity {
        MATCH,
        ABSENT,
        DIFFERENT,
        UNVERIFIED
    }

    private companion object {
        const val DEFAULT_TERMINATION_TIMEOUT_MS = 2_000L
        const val DEFAULT_TERMINATION_POLL_MS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L

        fun processHostContext(context: Context): Context = context.applicationContext ?: context
    }
}

private class AndroidRunningProcessSource(
    context: Context
) : () -> List<EngineRunningProcess> {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun invoke(): List<EngineRunningProcess> {
        val manager = activityManager ?: error("ActivityManager unavailable")
        return manager.runningAppProcesses.orEmpty().map { process ->
            EngineRunningProcess(
                processName = process.processName.orEmpty(),
                uid = process.uid,
                processId = process.pid
            )
        }
    }
}

private object ProcCommandLineProbe : (Int) -> EngineProcessProbe {
    override fun invoke(processId: Int): EngineProcessProbe {
        val file = File("/proc/$processId/cmdline")
        if (!file.exists()) return EngineProcessProbe(exists = false)
        return runCatching {
            EngineProcessProbe(
                exists = true,
                commandLine = file.readText().substringBefore('\u0000').trim()
            )
        }.getOrElse { error ->
            EngineProcessProbe(
                exists = true,
                failure = "${error.javaClass.name}:${error.message.orEmpty()}"
            )
        }
    }
}
