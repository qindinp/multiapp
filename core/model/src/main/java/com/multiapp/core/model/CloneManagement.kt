package com.multiapp.core.model

interface InstalledAppCatalog {
    fun listInstalledApps(forceRefresh: Boolean = false): List<VirtualApp>
}

fun VirtualApp.isCloneCandidate(): Boolean = mainActivity != null && !isSystemApp

data class CloneCreateResult(
    val instanceId: String,
    val createLatencyMs: Long,
    val cleanupStatus: String
)

data class CloneCreateAttempt(
    val creationRequestId: String,
    val payloadFingerprint: String,
    val displayName: String
) {
    init {
        require(creationRequestId.isNotBlank()) { "creationRequestId must not be blank" }
        require(payloadFingerprint.isNotBlank()) { "payloadFingerprint must not be blank" }
        require(displayName.isNotBlank() && displayName == displayName.trim()) {
            "displayName must be non-blank and trimmed"
        }
    }
}

class CloneCreateFailureException(
    val failureCode: String,
    val userMessage: String,
    val technicalReason: String?,
    val cleanupStatus: String,
    cause: Throwable,
    val shouldRetainCreationRequestId: Boolean = false
) : RuntimeException(technicalReason ?: userMessage, cause)

interface CloneCreationCoordinator {
    fun suggestedDisplayName(app: VirtualApp): String

    fun prepareAttempt(
        app: VirtualApp,
        displayName: String? = null,
        pendingAttempt: CloneCreateAttempt? = null
    ): CloneCreateAttempt

    fun create(app: VirtualApp, attempt: CloneCreateAttempt): Result<CloneCreateResult>
}
