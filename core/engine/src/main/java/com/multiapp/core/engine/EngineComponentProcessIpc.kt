package com.multiapp.core.engine

import android.os.Bundle
import android.os.IBinder

data class EngineComponentProcessLaunchTicket(
    val instanceId: String,
    val effectiveGuestProcessName: String,
    val processSlot: String,
    val attachCapability: String
)

data class EngineComponentProcessState(
    val instanceId: String,
    val effectiveGuestProcessName: String,
    val processSlot: String,
    val processId: Int,
    val processEpoch: Long,
    val live: Boolean
)

data class EngineComponentProcessOperationResult(
    val operation: String,
    val instanceId: String,
    val accepted: Boolean,
    val idempotent: Boolean,
    val alreadyRunning: Boolean,
    val launchTicket: EngineComponentProcessLaunchTicket?,
    val processState: EngineComponentProcessState?,
    val reason: String
)

interface EngineComponentProcessAuthority {
    fun prepare(instanceId: String, guestProcessName: String): EngineComponentProcessOperationResult

    fun attach(
        attachCapability: String,
        clientToken: IBinder,
        callingPid: Int,
        callingProcessName: String?,
        callingProcessStartTicks: Long?
    ): EngineComponentProcessOperationResult

    fun query(instanceId: String, guestProcessName: String): EngineComponentProcessOperationResult

    fun authorizeCaller(
        instanceId: String,
        callingPid: Int,
        callingProcessName: String?,
        callingProcessStartTicks: Long?
    ): EngineComponentProcessClientIdentity?
}

internal fun EngineComponentProcessLaunchTicket.toComponentProcessLaunchTicketIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_EFFECTIVE_GUEST_PROCESS_NAME, effectiveGuestProcessName)
        putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
        putString(EngineRuntimeIpcContract.KEY_ATTACH_CAPABILITY, attachCapability)
    }

internal fun Bundle.toComponentProcessLaunchTicketOrNull(): EngineComponentProcessLaunchTicket? =
    runCatching {
        if (keySet() != COMPONENT_PROCESS_LAUNCH_TICKET_FIELDS) return@runCatching null
        EngineComponentProcessLaunchTicket(
            instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
            effectiveGuestProcessName = getString(
                EngineRuntimeIpcContract.KEY_EFFECTIVE_GUEST_PROCESS_NAME
            ).orEmpty(),
            processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
            attachCapability = getString(EngineRuntimeIpcContract.KEY_ATTACH_CAPABILITY).orEmpty()
        ).takeIf { ticket -> ticket.hasValidComponentProcessTicketShape() }
    }.getOrNull()

internal fun EngineComponentProcessState.toComponentProcessStateIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putString(EngineRuntimeIpcContract.KEY_EFFECTIVE_GUEST_PROCESS_NAME, effectiveGuestProcessName)
        putString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT, processSlot)
        putInt(EngineRuntimeIpcContract.KEY_PROCESS_ID, processId)
        putLong(EngineRuntimeIpcContract.KEY_PROCESS_EPOCH, processEpoch)
        putBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY, live)
    }

internal fun Bundle.toComponentProcessStateOrNull(): EngineComponentProcessState? = runCatching {
    if (keySet() != COMPONENT_PROCESS_STATE_FIELDS) return@runCatching null
    if (!hasStrictComponentProcessBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY)) {
        return@runCatching null
    }
    EngineComponentProcessState(
        instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
        effectiveGuestProcessName = getString(
            EngineRuntimeIpcContract.KEY_EFFECTIVE_GUEST_PROCESS_NAME
        ).orEmpty(),
        processSlot = getString(EngineRuntimeIpcContract.KEY_PROCESS_SLOT).orEmpty(),
        processId = getInt(EngineRuntimeIpcContract.KEY_PROCESS_ID),
        processEpoch = getLong(EngineRuntimeIpcContract.KEY_PROCESS_EPOCH),
        live = getBoolean(EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY)
    ).takeIf { state -> state.hasValidComponentProcessStateShape() }
}.getOrNull()

internal fun EngineComponentProcessOperationResult.toComponentProcessIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
        putString(EngineRuntimeIpcContract.KEY_OPERATION, operation)
        putString(EngineRuntimeIpcContract.KEY_INSTANCE_ID, instanceId)
        putBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED, accepted)
        putBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT, idempotent)
        putBoolean(EngineRuntimeIpcContract.KEY_ALREADY_RUNNING, alreadyRunning)
        putBundle(
            EngineRuntimeIpcContract.KEY_COMPONENT_PROCESS_LAUNCH_TICKET,
            launchTicket?.toComponentProcessLaunchTicketIpcBundle(bundleFactory)
        )
        putBundle(
            EngineRuntimeIpcContract.KEY_COMPONENT_PROCESS_STATE,
            processState?.toComponentProcessStateIpcBundle(bundleFactory)
        )
        putString(EngineRuntimeIpcContract.KEY_REASON, reason)
    }

internal fun Bundle.toComponentProcessOperationResultOrNull(): EngineComponentProcessOperationResult? =
    runCatching {
        if (keySet() != COMPONENT_PROCESS_RESULT_FIELDS) return@runCatching null
        if (
            !hasStrictComponentProcessBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED) ||
            !hasStrictComponentProcessBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT) ||
            !hasStrictComponentProcessBoolean(EngineRuntimeIpcContract.KEY_ALREADY_RUNNING)
        ) {
            return@runCatching null
        }
        val ticketBundle = getBundle(EngineRuntimeIpcContract.KEY_COMPONENT_PROCESS_LAUNCH_TICKET)
        val stateBundle = getBundle(EngineRuntimeIpcContract.KEY_COMPONENT_PROCESS_STATE)
        val result = EngineComponentProcessOperationResult(
            operation = getString(EngineRuntimeIpcContract.KEY_OPERATION).orEmpty(),
            instanceId = getString(EngineRuntimeIpcContract.KEY_INSTANCE_ID).orEmpty(),
            accepted = getBoolean(EngineRuntimeIpcContract.KEY_ACCEPTED),
            idempotent = getBoolean(EngineRuntimeIpcContract.KEY_IDEMPOTENT),
            alreadyRunning = getBoolean(EngineRuntimeIpcContract.KEY_ALREADY_RUNNING),
            launchTicket = ticketBundle?.toComponentProcessLaunchTicketOrNull()
                ?: if (ticketBundle == null) null else return@runCatching null,
            processState = stateBundle?.toComponentProcessStateOrNull()
                ?: if (stateBundle == null) null else return@runCatching null,
            reason = getString(EngineRuntimeIpcContract.KEY_REASON).orEmpty()
        )
        result.takeIf(EngineComponentProcessOperationResult::hasValidComponentProcessResultShape)
    }.getOrNull()

private fun Bundle.hasStrictComponentProcessBoolean(key: String): Boolean =
    containsKey(key) && getBoolean(key, false) == getBoolean(key, true)

internal fun EngineComponentProcessClientIdentity.toPublicComponentProcessState() =
    EngineComponentProcessState(
        instanceId = instanceId,
        effectiveGuestProcessName = effectiveGuestProcessName,
        processSlot = processSlot,
        processId = processId,
        processEpoch = processEpoch,
        live = true
    )

private fun EngineComponentProcessLaunchTicket.hasValidComponentProcessTicketShape(): Boolean =
    instanceId.isValidComponentProcessText() &&
        effectiveGuestProcessName.isValidComponentProcessText() &&
        processSlot.isValidComponentProcessText() &&
        attachCapability.length in MIN_COMPONENT_PROCESS_CAPABILITY_LENGTH..
            EngineRuntimeIpcContract.MAX_COMPONENT_PROCESS_TEXT_LENGTH &&
        attachCapability == attachCapability.trim()

private fun EngineComponentProcessState.hasValidComponentProcessStateShape(): Boolean =
    instanceId.isValidComponentProcessText() &&
        effectiveGuestProcessName.isValidComponentProcessText() &&
        processSlot.isValidComponentProcessText() &&
        processId > 0 && processEpoch > 0L && live

private fun EngineComponentProcessOperationResult.hasValidComponentProcessResultShape(): Boolean {
    if (operation !in COMPONENT_PROCESS_OPERATIONS) return false
    if (!instanceId.isValidComponentProcessText()) return false
    if (reason.isBlank() || reason.length > EngineRuntimeIpcContract.MAX_COMPONENT_PROCESS_TEXT_LENGTH) {
        return false
    }
    if (launchTicket?.instanceId?.let { it != instanceId } == true) return false
    if (processState?.instanceId?.let { it != instanceId } == true) return false
    if (!accepted) {
        return !idempotent && !alreadyRunning && launchTicket == null && processState == null
    }
    return when (operation) {
        COMPONENT_PROCESS_PREPARE_OPERATION -> when {
            alreadyRunning -> launchTicket == null && processState?.live == true
            else -> processState == null && launchTicket != null
        }
        COMPONENT_PROCESS_ATTACH_OPERATION -> !alreadyRunning && launchTicket == null &&
            processState?.live == true
        COMPONENT_PROCESS_QUERY_OPERATION -> !idempotent && alreadyRunning && launchTicket == null &&
            processState?.live == true
        else -> false
    }
}

private fun String.isValidComponentProcessText(): Boolean =
    isNotBlank() && this == trim() && length <= EngineRuntimeIpcContract.MAX_COMPONENT_PROCESS_TEXT_LENGTH

private val COMPONENT_PROCESS_LAUNCH_TICKET_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_EFFECTIVE_GUEST_PROCESS_NAME,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_ATTACH_CAPABILITY
)

private val COMPONENT_PROCESS_STATE_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_EFFECTIVE_GUEST_PROCESS_NAME,
    EngineRuntimeIpcContract.KEY_PROCESS_SLOT,
    EngineRuntimeIpcContract.KEY_PROCESS_ID,
    EngineRuntimeIpcContract.KEY_PROCESS_EPOCH,
    EngineRuntimeIpcContract.KEY_LIVE_AUTHORITY
)

private val COMPONENT_PROCESS_RESULT_FIELDS = setOf(
    EngineRuntimeIpcContract.KEY_OPERATION,
    EngineRuntimeIpcContract.KEY_INSTANCE_ID,
    EngineRuntimeIpcContract.KEY_ACCEPTED,
    EngineRuntimeIpcContract.KEY_IDEMPOTENT,
    EngineRuntimeIpcContract.KEY_ALREADY_RUNNING,
    EngineRuntimeIpcContract.KEY_COMPONENT_PROCESS_LAUNCH_TICKET,
    EngineRuntimeIpcContract.KEY_COMPONENT_PROCESS_STATE,
    EngineRuntimeIpcContract.KEY_REASON
)

internal const val COMPONENT_PROCESS_PREPARE_OPERATION = "prepareComponentProcess"
internal const val COMPONENT_PROCESS_ATTACH_OPERATION = "attachComponentProcessClient"
internal const val COMPONENT_PROCESS_QUERY_OPERATION = "queryComponentProcessClient"

internal const val MIN_COMPONENT_PROCESS_CAPABILITY_LENGTH = 32
internal const val UNKNOWN_COMPONENT_PROCESS_INSTANCE = "unknown"

private val COMPONENT_PROCESS_OPERATIONS = setOf(
    COMPONENT_PROCESS_PREPARE_OPERATION,
    COMPONENT_PROCESS_ATTACH_OPERATION,
    COMPONENT_PROCESS_QUERY_OPERATION
)
