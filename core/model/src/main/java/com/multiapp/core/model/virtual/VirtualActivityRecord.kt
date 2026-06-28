package com.multiapp.core.model.virtual

data class VirtualActivityRecord(
    val token: String,
    val activityId: String = token,
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val proxyActivityClassName: String,
    val launchMode: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val taskId: Int = 0,
    val intentFlags: Int = 0,
    val state: VirtualActivityState = VirtualActivityState.CREATED,
    val taskAffinity: String? = null,
    val pendingNewIntents: List<VirtualActivityPendingNewIntent> = emptyList(),
    val result: VirtualActivityResult? = null
) {
    init {
        require(token.isNotBlank()) { "token must not be blank" }
        require(activityId.isNotBlank()) { "activityId must not be blank" }
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(guestActivityClassName.isNotBlank()) { "guestActivityClassName must not be blank" }
        require(proxyActivityClassName.isNotBlank()) { "proxyActivityClassName must not be blank" }
        require(taskId >= 0) { "taskId must not be negative" }
        require(taskAffinity == null || taskAffinity.isNotBlank()) { "taskAffinity must not be blank" }
    }
}

enum class VirtualActivityState {
    CREATED,
    RESUMED,
    PAUSED,
    STOPPED,
    FINISHED,
    DESTROYED
}

data class VirtualActivityPendingNewIntent(
    val eventId: Long,
    val sourceToken: String,
    val intentFlags: Int,
    val dataIntent: VirtualIntentSnapshot? = null,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(eventId > 0) { "eventId must be positive" }
        require(sourceToken.isNotBlank()) { "sourceToken must not be blank" }
    }
}

data class VirtualActivityResult(
    val resultCode: Int,
    val dataIntent: VirtualIntentSnapshot? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class VirtualIntentSnapshot(
    val flags: Int = 0,
    val action: String? = null,
    val dataUri: String? = null,
    val categories: Set<String> = emptySet(),
    val extras: Map<String, String> = emptyMap()
)
