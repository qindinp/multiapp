package com.multiapp.core.model

data class VirtualInstanceRecord(
    val instanceId: String,
    val packageName: String,
    val displayName: String = packageName,
    val stubPackageName: String = "$packageName.stub",
    val userPartitionName: String = "owner",
    val dataRoot: InstanceDataRoot = InstanceDataRoot.forUser(
        instanceId = instanceId,
        originalPackageName = packageName,
        stubPackageName = stubPackageName
    ),
    val cloneProfile: CloneProfile = CloneProfile.NORMAL,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.DEFAULT,
    val createdAtMillis: Long = 0,
    val lastLaunchedAtMillis: Long? = null,
    val lastLaunchedAt: Long? = lastLaunchedAtMillis,
    val processSlot: ProcessSlot? = null,
    val enabled: Boolean = true,
    val suspended: Boolean = false,
    val state: VirtualInstanceState = VirtualInstanceState.STOPPED
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(stubPackageName.isNotBlank()) { "stubPackageName must not be blank" }
        require(userPartitionName.isNotBlank()) { "userPartitionName must not be blank" }
        require(createdAtMillis >= 0) { "createdAtMillis must be non-negative" }
    }

    val protectedBaselineEnabled: Boolean
        get() = compatibilityMode.protectedAppBaseline

    val isHookFree: Boolean
        get() = compatibilityMode.isHookFree

    fun canLaunch(): Boolean =
        enabled && !suspended

    fun withLaunchState(processSlot: ProcessSlot, launchedAtMillis: Long): VirtualInstanceRecord =
        copy(
            state = VirtualInstanceState.RUNNING,
            processSlot = processSlot,
            lastLaunchedAt = launchedAtMillis,
            lastLaunchedAtMillis = launchedAtMillis
        )
}

enum class VirtualInstanceState {
    STOPPED,
    RUNNING,
    SUSPENDED
}
