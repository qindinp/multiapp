package com.multiapp.core.loader

data class VirtualActivityLaunchAllocationRequest(
    val instanceId: String,
    val originPackageName: String,
    val guestActivityClassName: String,
    val processSlot: String,
    val launchMode: String? = null,
    val taskAffinity: String? = null
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        require(originPackageName.isNotBlank()) { "originPackageName must not be blank" }
        require(guestActivityClassName.isNotBlank()) { "guestActivityClassName must not be blank" }
        require(processSlot.isNotBlank()) { "processSlot must not be blank" }
        require(launchMode == null || launchMode.isNotBlank()) { "launchMode must not be blank" }
        require(taskAffinity == null || taskAffinity.isNotBlank()) { "taskAffinity must not be blank" }
    }
}

data class VirtualActivityLaunchAllocation(
    val accepted: Boolean,
    val request: VirtualActivityLaunchAllocationRequest,
    val proxyActivityClassName: String? = null,
    val launchIdentity: VirtualActivityLaunchIdentity? = null,
    val reason: String
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(
            !accepted ||
                proxyActivityClassName?.isNotBlank() == true && launchIdentity != null &&
                launchIdentity.instanceId == request.instanceId &&
                launchIdentity.processSlot == request.processSlot &&
                launchIdentity.proxyActivityClassName == proxyActivityClassName &&
                launchIdentity.guestActivityClassName == request.guestActivityClassName
        ) { "accepted allocation must contain a matching launch identity" }
    }
}

interface VirtualActivityLaunchAllocationProvider {
    fun allocate(request: VirtualActivityLaunchAllocationRequest): VirtualActivityLaunchAllocation
    fun release(allocation: VirtualActivityLaunchAllocation): Boolean
}

object VirtualActivityLaunchAllocationProviders {
    @Volatile
    private var provider: VirtualActivityLaunchAllocationProvider? = null

    fun install(provider: VirtualActivityLaunchAllocationProvider) {
        this.provider = provider
    }

    fun requireProvider(): VirtualActivityLaunchAllocationProvider = provider
        ?: throw IllegalStateException("VirtualActivityLaunchAllocationProvider is not installed")

    internal fun clearForTest() {
        provider = null
    }
}
