package com.multiapp.core.engine

import android.os.Bundle
import android.os.IBinder

data class EngineProviderRouteTokenIssueRequest(
    val targetInstanceId: String,
    val guestAuthority: String,
    val operation: String,
    val requestedProcessSlot: String? = null
) {
    init {
        requireProviderRouteText("targetInstanceId", targetInstanceId)
        requireProviderRouteText("guestAuthority", guestAuthority)
        requireProviderRouteText("operation", operation)
        requestedProcessSlot?.let { requireProviderRouteText("requestedProcessSlot", it) }
    }
}

data class EngineProviderRouteTokenConsumeRequest(
    val token: String,
    val targetInstanceId: String,
    val guestAuthority: String,
    val operation: String,
    val expectedProcessSlot: String?,
    val providerCallingUid: Int,
    val providerCallingPid: Int,
    val targetProcessToken: IBinder
) {
    init {
        require(token.length in MIN_PROVIDER_ROUTE_TOKEN_LENGTH..MAX_PROVIDER_ROUTE_TEXT_LENGTH) {
            "token has invalid length"
        }
        require(token == token.trim()) { "token must be normalized" }
        requireProviderRouteText("targetInstanceId", targetInstanceId)
        requireProviderRouteText("guestAuthority", guestAuthority)
        requireProviderRouteText("operation", operation)
        expectedProcessSlot?.let { requireProviderRouteText("expectedProcessSlot", it) }
        require(providerCallingUid > 0) { "providerCallingUid must be positive" }
        require(providerCallingPid > 0) { "providerCallingPid must be positive" }
        require(targetProcessToken.isBinderAlive) { "targetProcessToken must be alive" }
    }
}

data class EngineProviderRouteTokenAuthorityResult(
    val status: String,
    val route: EngineProviderRouteToken?,
    val message: String
) {
    init {
        requireProviderRouteText("status", status)
        requireProviderRouteText("message", message)
    }

    val accepted: Boolean
        get() = route != null && status in ACCEPTED_PROVIDER_ROUTE_TOKEN_STATUSES
}

internal fun EngineProviderRouteTokenIssueRequest.toProviderRouteTokenIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putString(PROVIDER_ROUTE_TARGET_INSTANCE_ID, targetInstanceId)
    putString(PROVIDER_ROUTE_GUEST_AUTHORITY, guestAuthority)
    putString(PROVIDER_ROUTE_OPERATION, operation)
    putString(PROVIDER_ROUTE_PROCESS_SLOT, requestedProcessSlot)
}

internal fun Bundle.toProviderRouteTokenIssueRequestOrNull(): EngineProviderRouteTokenIssueRequest? =
    runCatching {
        if (keySet() != PROVIDER_ROUTE_TOKEN_ISSUE_FIELDS) return@runCatching null
        EngineProviderRouteTokenIssueRequest(
            targetInstanceId = getString(PROVIDER_ROUTE_TARGET_INSTANCE_ID).orEmpty(),
            guestAuthority = getString(PROVIDER_ROUTE_GUEST_AUTHORITY).orEmpty(),
            operation = getString(PROVIDER_ROUTE_OPERATION).orEmpty(),
            requestedProcessSlot = getString(PROVIDER_ROUTE_PROCESS_SLOT)
        )
    }.getOrNull()

internal fun EngineProviderRouteTokenConsumeRequest.toProviderRouteTokenIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putString(PROVIDER_ROUTE_TOKEN, token)
    putString(PROVIDER_ROUTE_TARGET_INSTANCE_ID, targetInstanceId)
    putString(PROVIDER_ROUTE_GUEST_AUTHORITY, guestAuthority)
    putString(PROVIDER_ROUTE_OPERATION, operation)
    putString(PROVIDER_ROUTE_PROCESS_SLOT, expectedProcessSlot)
    putInt(PROVIDER_ROUTE_CALLING_UID, providerCallingUid)
    putInt(PROVIDER_ROUTE_CALLING_PID, providerCallingPid)
    putBinder(PROVIDER_ROUTE_TARGET_PROCESS_TOKEN, targetProcessToken)
}

internal fun Bundle.toProviderRouteTokenConsumeRequestOrNull(): EngineProviderRouteTokenConsumeRequest? =
    runCatching {
        if (keySet() != PROVIDER_ROUTE_TOKEN_CONSUME_FIELDS) return@runCatching null
        EngineProviderRouteTokenConsumeRequest(
            token = getString(PROVIDER_ROUTE_TOKEN).orEmpty(),
            targetInstanceId = getString(PROVIDER_ROUTE_TARGET_INSTANCE_ID).orEmpty(),
            guestAuthority = getString(PROVIDER_ROUTE_GUEST_AUTHORITY).orEmpty(),
            operation = getString(PROVIDER_ROUTE_OPERATION).orEmpty(),
            expectedProcessSlot = getString(PROVIDER_ROUTE_PROCESS_SLOT),
            providerCallingUid = getInt(PROVIDER_ROUTE_CALLING_UID),
            providerCallingPid = getInt(PROVIDER_ROUTE_CALLING_PID),
            targetProcessToken = getBinder(PROVIDER_ROUTE_TARGET_PROCESS_TOKEN)
                ?: return@runCatching null
        )
    }.getOrNull()

internal fun EngineProviderRouteTokenAuthorityResult.toProviderRouteTokenIpcBundle(
    bundleFactory: () -> Bundle = ::Bundle
): Bundle = bundleFactory().apply {
    putString(PROVIDER_ROUTE_STATUS, status)
    putString(PROVIDER_ROUTE_MESSAGE, message)
    putBundle(PROVIDER_ROUTE, route?.toProviderRouteIpcBundle(bundleFactory))
}

internal fun Bundle.toProviderRouteTokenAuthorityResultOrNull(): EngineProviderRouteTokenAuthorityResult? =
    runCatching {
        if (keySet() != PROVIDER_ROUTE_TOKEN_RESULT_FIELDS) return@runCatching null
        EngineProviderRouteTokenAuthorityResult(
            status = getString(PROVIDER_ROUTE_STATUS).orEmpty(),
            route = getBundle(PROVIDER_ROUTE)?.toProviderRouteOrNull(),
            message = getString(PROVIDER_ROUTE_MESSAGE).orEmpty()
        ).takeIf { result ->
            (result.status in ACCEPTED_PROVIDER_ROUTE_TOKEN_STATUSES) == (result.route != null)
        }
    }.getOrNull()

private fun EngineProviderRouteToken.toProviderRouteIpcBundle(
    bundleFactory: () -> Bundle
): Bundle = bundleFactory().apply {
    putString(PROVIDER_ROUTE_TOKEN, token)
    putString(PROVIDER_ROUTE_CALLER_INSTANCE_ID, callerInstanceId)
    putString(PROVIDER_ROUTE_TARGET_INSTANCE_ID, targetInstanceId)
    putString(PROVIDER_ROUTE_GUEST_AUTHORITY, authority)
    putString(PROVIDER_ROUTE_OPERATION, operation)
    putLong(PROVIDER_ROUTE_EXPIRES_AT_MILLIS, expiresAtMillis)
    putString(PROVIDER_ROUTE_PROCESS_SLOT, processSlot)
    putString(PROVIDER_ROUTE_CALLER_PROCESS_SLOT, callerProcessSlot)
    putInt(PROVIDER_ROUTE_CALLER_PROCESS_ID, callerProcessId ?: -1)
}

private fun Bundle.toProviderRouteOrNull(): EngineProviderRouteToken? = runCatching {
    if (keySet() != PROVIDER_ROUTE_FIELDS) return@runCatching null
    EngineProviderRouteToken(
        token = getString(PROVIDER_ROUTE_TOKEN).orEmpty(),
        callerInstanceId = getString(PROVIDER_ROUTE_CALLER_INSTANCE_ID).orEmpty(),
        targetInstanceId = getString(PROVIDER_ROUTE_TARGET_INSTANCE_ID).orEmpty(),
        authority = getString(PROVIDER_ROUTE_GUEST_AUTHORITY).orEmpty(),
        operation = getString(PROVIDER_ROUTE_OPERATION).orEmpty(),
        expiresAtMillis = getLong(PROVIDER_ROUTE_EXPIRES_AT_MILLIS),
        processSlot = getString(PROVIDER_ROUTE_PROCESS_SLOT),
        callerProcessSlot = getString(PROVIDER_ROUTE_CALLER_PROCESS_SLOT),
        callerProcessId = if (containsKey(PROVIDER_ROUTE_CALLER_PROCESS_ID)) {
            getInt(PROVIDER_ROUTE_CALLER_PROCESS_ID).takeIf { it > 0 }
        } else {
            null
        }
    ).takeIf { route ->
        route.token.length in MIN_PROVIDER_ROUTE_TOKEN_LENGTH..MAX_PROVIDER_ROUTE_TEXT_LENGTH &&
            route.token == route.token.trim() &&
            route.callerInstanceId.isProviderRouteText() &&
            route.targetInstanceId.isProviderRouteText() &&
            route.authority.isProviderRouteText() &&
            route.operation.isProviderRouteText() &&
            route.expiresAtMillis > 0L &&
            (route.processSlot == null || route.processSlot.isProviderRouteText()) &&
            (route.callerProcessSlot == null || route.callerProcessSlot.isProviderRouteText()) &&
            (route.callerProcessId == null || route.callerProcessId > 0)
    }
}.getOrNull()

private fun requireProviderRouteText(name: String, value: String) {
    require(value.isProviderRouteText()) { "$name must be normalized non-blank text" }
}

private fun String.isProviderRouteText(): Boolean =
    isNotBlank() && this == trim() && length <= MAX_PROVIDER_ROUTE_TEXT_LENGTH

private const val PROVIDER_ROUTE_TOKEN = "providerRouteToken"
private const val PROVIDER_ROUTE_CALLER_INSTANCE_ID = "providerRouteCallerInstanceId"
private const val PROVIDER_ROUTE_TARGET_INSTANCE_ID = "providerRouteTargetInstanceId"
private const val PROVIDER_ROUTE_GUEST_AUTHORITY = "providerRouteGuestAuthority"
private const val PROVIDER_ROUTE_OPERATION = "providerRouteOperation"
private const val PROVIDER_ROUTE_PROCESS_SLOT = "providerRouteProcessSlot"
private const val PROVIDER_ROUTE_CALLER_PROCESS_SLOT = "providerRouteCallerProcessSlot"
private const val PROVIDER_ROUTE_CALLER_PROCESS_ID = "providerRouteCallerProcessId"
private const val PROVIDER_ROUTE_CALLING_UID = "providerRouteCallingUid"
private const val PROVIDER_ROUTE_CALLING_PID = "providerRouteCallingPid"
private const val PROVIDER_ROUTE_TARGET_PROCESS_TOKEN = "providerRouteTargetProcessToken"
private const val PROVIDER_ROUTE_EXPIRES_AT_MILLIS = "providerRouteExpiresAtMillis"
private const val PROVIDER_ROUTE_STATUS = "providerRouteStatus"
private const val PROVIDER_ROUTE_MESSAGE = "providerRouteMessage"
private const val PROVIDER_ROUTE = "providerRoute"

private const val MIN_PROVIDER_ROUTE_TOKEN_LENGTH = 40
private const val MAX_PROVIDER_ROUTE_TEXT_LENGTH = 4096

private val ACCEPTED_PROVIDER_ROUTE_TOKEN_STATUSES = setOf("ISSUED", "VALID")

private val PROVIDER_ROUTE_TOKEN_ISSUE_FIELDS = setOf(
    PROVIDER_ROUTE_TARGET_INSTANCE_ID,
    PROVIDER_ROUTE_GUEST_AUTHORITY,
    PROVIDER_ROUTE_OPERATION,
    PROVIDER_ROUTE_PROCESS_SLOT
)

private val PROVIDER_ROUTE_TOKEN_CONSUME_FIELDS = setOf(
    PROVIDER_ROUTE_TOKEN,
    PROVIDER_ROUTE_TARGET_INSTANCE_ID,
    PROVIDER_ROUTE_GUEST_AUTHORITY,
    PROVIDER_ROUTE_OPERATION,
    PROVIDER_ROUTE_PROCESS_SLOT,
    PROVIDER_ROUTE_CALLING_UID,
    PROVIDER_ROUTE_CALLING_PID,
    PROVIDER_ROUTE_TARGET_PROCESS_TOKEN
)

private val PROVIDER_ROUTE_TOKEN_RESULT_FIELDS = setOf(
    PROVIDER_ROUTE_STATUS,
    PROVIDER_ROUTE_MESSAGE,
    PROVIDER_ROUTE
)

private val PROVIDER_ROUTE_FIELDS = setOf(
    PROVIDER_ROUTE_TOKEN,
    PROVIDER_ROUTE_CALLER_INSTANCE_ID,
    PROVIDER_ROUTE_TARGET_INSTANCE_ID,
    PROVIDER_ROUTE_GUEST_AUTHORITY,
    PROVIDER_ROUTE_OPERATION,
    PROVIDER_ROUTE_EXPIRES_AT_MILLIS,
    PROVIDER_ROUTE_PROCESS_SLOT,
    PROVIDER_ROUTE_CALLER_PROCESS_SLOT,
    PROVIDER_ROUTE_CALLER_PROCESS_ID
)
