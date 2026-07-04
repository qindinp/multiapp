package com.multiapp.core.loader

import android.content.pm.ProviderInfo
import com.multiapp.core.model.virtual.ResolvedComponent

data class VirtualProviderPolicy(
    val exported: Boolean,
    val permission: String?,
    val grantUriPermissions: Boolean,
    val status: String,
    val reason: String,
    val routingScope: String = ROUTING_SCOPE_INSTANCE,
    val processWideProviderHook: Boolean = false,
    val authorityRewriteEntry: String = AUTHORITY_REWRITE_ENTRY
) {
    fun toEvidenceFields(): Map<String, Any?> = linkedMapOf(
        "providerExported" to exported,
        "providerPermission" to permission.orEmpty(),
        "providerGrantUriPermissions" to grantUriPermissions,
        "providerPolicyStatus" to status,
        "providerPolicyReason" to reason,
        "providerRoutingScope" to routingScope,
        "processWideProviderHook" to processWideProviderHook,
        "authorityRewriteEntry" to authorityRewriteEntry
    )

    companion object {
        const val ROUTING_SCOPE_INSTANCE = "INSTANCE"
        const val AUTHORITY_REWRITE_ENTRY = "VirtualContentResolver"

        fun fromProviderInfo(providerInfo: ProviderInfo): VirtualProviderPolicy {
            val permission = providerInfo.providerPermission()
            return fromValues(
                exported = providerInfo.exported,
                permission = permission,
                grantUriPermissions = providerInfo.grantUriPermissions
            )
        }

        fun fromComponent(component: ResolvedComponent): VirtualProviderPolicy = fromValues(
            exported = component.exported,
            permission = component.permission,
            grantUriPermissions = component.grantUriPermissions
        )

        private fun fromValues(
            exported: Boolean,
            permission: String?,
            grantUriPermissions: Boolean
        ): VirtualProviderPolicy {
            val normalizedPermission = permission?.takeIf { it.isNotBlank() }
            val status = when {
                !exported -> "INTERNAL_ONLY"
                normalizedPermission != null -> "EXPORTED_PERMISSION_GATED"
                else -> "EXPORTED_UNGUARDED"
            }
            val reason = buildString {
                append("exported=")
                append(exported)
                append(";permission=")
                append(normalizedPermission ?: "")
                append(";grantUriPermissions=")
                append(grantUriPermissions)
            }
            return VirtualProviderPolicy(
                exported = exported,
                permission = normalizedPermission,
                grantUriPermissions = grantUriPermissions,
                status = status,
                reason = reason
            )
        }

        private fun ProviderInfo.providerPermission(): String? {
            val readPermission = readPermission?.takeIf { it.isNotBlank() }
            val writePermission = writePermission?.takeIf { it.isNotBlank() }
            return when {
                readPermission == null && writePermission == null -> null
                readPermission == writePermission -> readPermission
                else -> listOfNotNull(
                    readPermission?.let { "read=$it" },
                    writePermission?.let { "write=$it" }
                ).joinToString(";")
            }
        }
    }
}

data class VirtualProviderPolicySummary(
    val providerPermissionCount: Int,
    val providerGrantUriPermissionCount: Int,
    val providerExportedCount: Int,
    val providerUnguardedExportedCount: Int,
    val providerPolicyStatuses: List<String>
) {
    fun toEvidence(): List<BootstrapEvidence> = listOf(
        BootstrapEvidence("providerRoutingScope", VirtualProviderPolicy.ROUTING_SCOPE_INSTANCE, SOURCE),
        BootstrapEvidence("processWideProviderHook", "false", SOURCE),
        BootstrapEvidence("authorityRewriteEntry", VirtualProviderPolicy.AUTHORITY_REWRITE_ENTRY, SOURCE),
        BootstrapEvidence("providerPolicyPermissionCount", providerPermissionCount.toString(), SOURCE),
        BootstrapEvidence("providerPolicyGrantUriPermissionCount", providerGrantUriPermissionCount.toString(), SOURCE),
        BootstrapEvidence("providerPolicyExportedCount", providerExportedCount.toString(), SOURCE),
        BootstrapEvidence("providerPolicyUnguardedExportedCount", providerUnguardedExportedCount.toString(), SOURCE),
        BootstrapEvidence("providerPolicyStatuses", providerPolicyStatuses.joinToString(","), SOURCE)
    )

    companion object {
        val EMPTY = VirtualProviderPolicySummary(
            providerPermissionCount = 0,
            providerGrantUriPermissionCount = 0,
            providerExportedCount = 0,
            providerUnguardedExportedCount = 0,
            providerPolicyStatuses = emptyList()
        )

        private const val SOURCE = "VirtualProviderPolicy"

        fun fromProviders(providers: List<ResolvedComponent>): VirtualProviderPolicySummary {
            val policies = providers.map { VirtualProviderPolicy.fromComponent(it) }
            return VirtualProviderPolicySummary(
                providerPermissionCount = policies.count { !it.permission.isNullOrBlank() },
                providerGrantUriPermissionCount = policies.count { it.grantUriPermissions },
                providerExportedCount = policies.count { it.exported },
                providerUnguardedExportedCount = policies.count { it.status == "EXPORTED_UNGUARDED" },
                providerPolicyStatuses = policies.map { it.status }.distinct()
            )
        }
    }
}
