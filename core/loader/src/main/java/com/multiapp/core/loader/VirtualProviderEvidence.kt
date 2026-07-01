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
        QUERY,
        GET_TYPE,
        INSERT,
        DELETE,
        UPDATE,
        CALL,
        NOTIFY_CHANGE,
        UNKNOWN
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

        fun methodDispatch(
            acquireResult: VirtualProviderDispatchResult?,
            operationName: String
        ): VirtualProviderEvidence {
            val operation = operationName.toProviderOperation()
            return when (acquireResult) {
                is VirtualProviderDispatchResult.ProviderReady -> fromResolution(
                    resolution = acquireResult.resolution,
                    operation = operation,
                    success = true,
                    reason = if (acquireResult.cached) "PROVIDER_CACHED" else "PROVIDER_CREATED"
                )
                is VirtualProviderDispatchResult.RuntimeNotBound -> fromResolution(
                    resolution = acquireResult.resolution,
                    operation = operation,
                    success = false,
                    reason = acquireResult.evidence.reason ?: "RUNTIME_NOT_BOUND"
                )
                is VirtualProviderDispatchResult.RuntimeIncomplete -> fromResolution(
                    resolution = acquireResult.resolution,
                    operation = operation,
                    success = false,
                    reason = acquireResult.reason
                )
                is VirtualProviderDispatchResult.ProviderCreateFailed -> fromResolution(
                    resolution = acquireResult.resolution,
                    operation = operation,
                    success = false,
                    reason = acquireResult.error.message ?: acquireResult.error.javaClass.name
                )
                is VirtualProviderDispatchResult.ProviderAttachFailed -> fromResolution(
                    resolution = acquireResult.resolution,
                    operation = operation,
                    success = false,
                    reason = acquireResult.error.message ?: acquireResult.error.javaClass.name
                )
                is VirtualProviderDispatchResult.ProviderNotFound -> VirtualProviderEvidence(
                    instanceId = acquireResult.instanceId,
                    guestAuthority = acquireResult.guestAuthority,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = operation,
                    success = false,
                    reason = acquireResult.evidence.reason ?: "PROVIDER_NOT_FOUND"
                )
                is VirtualProviderDispatchResult.InstanceNotFound -> VirtualProviderEvidence(
                    instanceId = acquireResult.instanceId,
                    guestAuthority = null,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = operation,
                    success = false,
                    reason = "INSTANCE_NOT_FOUND"
                )
                is VirtualProviderDispatchResult.InvalidProxyUri -> VirtualProviderEvidence(
                    instanceId = null,
                    guestAuthority = null,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = operation,
                    success = false,
                    reason = acquireResult.reason
                )
                null -> VirtualProviderEvidence(
                    instanceId = null,
                    guestAuthority = null,
                    proxyAuthority = null,
                    providerClassName = null,
                    operation = operation,
                    success = false,
                    reason = "missing uri"
                )
            }
        }

        private fun String.toProviderOperation(): Operation = when (substringBefore(':')) {
            "query" -> Operation.QUERY
            "getType" -> Operation.GET_TYPE
            "insert" -> Operation.INSERT
            "delete" -> Operation.DELETE
            "update" -> Operation.UPDATE
            "call" -> Operation.CALL
            else -> Operation.UNKNOWN
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
