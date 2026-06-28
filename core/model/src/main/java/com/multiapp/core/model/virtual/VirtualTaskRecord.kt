package com.multiapp.core.model.virtual

data class VirtualTaskRecord(
    val taskId: Int,
    val affinity: String,
    val activities: List<VirtualActivityRecord> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(taskId > 0) { "taskId must be positive" }
        require(affinity.isNotBlank()) { "affinity must not be blank" }
    }

    val topActivity: VirtualActivityRecord?
        get() = activities.lastOrNull()
}
