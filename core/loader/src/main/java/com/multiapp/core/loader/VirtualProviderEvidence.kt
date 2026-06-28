package com.multiapp.core.loader

data class VirtualProviderEvidence(
    val instanceId: String?,
    val guestAuthority: String?,
    val proxyAuthority: String?,
    val providerClassName: String?,
    val operation: Operation,
    val success: Boolean,
    val reason: String? = null
) {
    enum class Operation {
        ACQUIRE_PROVIDER,
        NOTIFY_CHANGE
    }

    companion object {
        fun acquisition(result: VirtualProviderOpenResult): VirtualProviderEvidence {
            return when (result) {
                is VirtualProviderOpenResult.Resolved -> fromResolution(
                    resolution = result.resolution,
                    operation = Operation.ACQUIRE_PROVIDER,
                    success = true,
                    reason = null
                )
                is VirtualProviderOpenResult.NotFound -> VirtualProviderEvidence(
                    instanceId = null,
                    guestAuthority = result.authority,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = Operation.ACQUIRE_PROVIDER,
                    success = false,
                    reason = "PROVIDER_NOT_FOUND"
                )
            }
        }

        fun acquisition(
            resolution: VirtualProviderResolution,
            success: Boolean,
            reason: String? = null
        ): VirtualProviderEvidence = fromResolution(
            resolution = resolution,
            operation = Operation.ACQUIRE_PROVIDER,
            success = success,
            reason = reason
        )

        fun acquisitionNotFound(
            instanceId: String?,
            guestAuthority: String
        ): VirtualProviderEvidence = VirtualProviderEvidence(
            instanceId = instanceId,
            guestAuthority = guestAuthority,
            proxyAuthority = null,
            providerClassName = null,
            operation = Operation.ACQUIRE_PROVIDER,
            success = false,
            reason = "PROVIDER_NOT_FOUND"
        )

        fun notifyChange(
            rewrite: VirtualProviderUriRewrite?,
            originalAuthority: String?
        ): VirtualProviderEvidence {
            if (rewrite == null) {
                return VirtualProviderEvidence(
                    instanceId = null,
                    guestAuthority = originalAuthority,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = Operation.NOTIFY_CHANGE,
                    success = false,
                    reason = "PROVIDER_NOT_FOUND"
                )
            }
            return fromResolution(
                resolution = rewrite.resolution,
                operation = Operation.NOTIFY_CHANGE,
                success = true,
                reason = null
            )
        }

        private fun fromResolution(
            resolution: VirtualProviderResolution,
            operation: Operation,
            success: Boolean,
            reason: String?
        ): VirtualProviderEvidence = VirtualProviderEvidence(
            instanceId = resolution.instanceId,
            guestAuthority = resolution.guestAuthority,
            proxyAuthority = resolution.proxyAuthority,
            providerClassName = resolution.providerClassName,
            operation = operation,
            success = success,
            reason = reason
        )
    }
}
