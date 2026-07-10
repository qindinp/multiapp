package com.multiapp.core.loader

import android.content.pm.ProviderInfo
import com.multiapp.core.model.virtual.ResolvedComponent

data class VirtualProviderPolicy(
    val exported: Boolean,
    val permission: String?,
    val readPermission: String?,
    val writePermission: String?,
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
        "providerReadPermission" to readPermission.orEmpty(),
        "providerWritePermission" to writePermission.orEmpty(),
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
            val readPermission = providerInfo.readPermission?.takeIf { it.isNotBlank() }
            val writePermission = providerInfo.writePermission?.takeIf { it.isNotBlank() }
            val permission = readPermission.takeIf { it != null && it == writePermission }
            return fromValues(
                exported = providerInfo.exported,
                permission = permission,
                readPermission = readPermission,
                writePermission = writePermission,
                grantUriPermissions = providerInfo.grantUriPermissions
            )
        }

        fun fromComponent(component: ResolvedComponent): VirtualProviderPolicy = fromValues(
            exported = component.exported,
            permission = component.permission,
            readPermission = component.readPermission ?: component.permission,
            writePermission = component.writePermission ?: component.permission,
            grantUriPermissions = component.grantUriPermissions || component.uriPermissionPatterns.isNotEmpty()
        )

        private fun fromValues(
            exported: Boolean,
            permission: String?,
            readPermission: String?,
            writePermission: String?,
            grantUriPermissions: Boolean
        ): VirtualProviderPolicy {
            val normalizedPermission = permission?.takeIf { it.isNotBlank() }
            val normalizedReadPermission = readPermission?.takeIf { it.isNotBlank() }
            val normalizedWritePermission = writePermission?.takeIf { it.isNotBlank() }
            val status = when {
                !exported -> "INTERNAL_ONLY"
                normalizedPermission != null || normalizedReadPermission != null || normalizedWritePermission != null ->
                    "EXPORTED_PERMISSION_GATED"
                else -> "EXPORTED_UNGUARDED"
            }
            val reason = buildString {
                append("exported=")
                append(exported)
                append(";permission=")
                append(normalizedPermission ?: "")
                append(";readPermission=")
                append(normalizedReadPermission ?: "")
                append(";writePermission=")
                append(normalizedWritePermission ?: "")
                append(";grantUriPermissions=")
                append(grantUriPermissions)
            }
            return VirtualProviderPolicy(
                exported = exported,
                permission = normalizedPermission,
                readPermission = normalizedReadPermission,
                writePermission = normalizedWritePermission,
                grantUriPermissions = grantUriPermissions,
                status = status,
                reason = reason
            )
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
                providerPermissionCount = policies.count {
                    !it.permission.isNullOrBlank() ||
                        !it.readPermission.isNullOrBlank() ||
                        !it.writePermission.isNullOrBlank()
                },
                providerGrantUriPermissionCount = policies.count { it.grantUriPermissions },
                providerExportedCount = policies.count { it.exported },
                providerUnguardedExportedCount = policies.count { it.status == "EXPORTED_UNGUARDED" },
                providerPolicyStatuses = policies.map { it.status }.distinct()
            )
        }
    }
}
