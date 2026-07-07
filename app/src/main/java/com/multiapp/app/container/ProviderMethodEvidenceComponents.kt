package com.multiapp.app.container

object ProviderMethodEvidenceComponents {
    private val operationComponents = mapOf(
        "query" to "provider-query",
        "insert" to "provider-insert",
        "update" to "provider-update",
        "delete" to "provider-delete",
        "call" to "provider-call",
        "openFile" to "provider-open-file",
        "openAssetFile" to "provider-open-asset-file",
        "openTypedAssetFile" to "provider-open-typed-asset-file",
        "bulkInsert" to "provider-bulk-insert",
        "getType" to "provider-get-type",
        "openFileDescriptor" to "provider-open-file-descriptor",
        "openAssetFileDescriptor" to "provider-open-asset-file-descriptor",
        "openTypedAssetFileDescriptor" to "provider-open-typed-asset-file-descriptor",
        "notifyChange" to "provider-notify-change",
        "registerContentObserver" to "provider-register-content-observer",
        "unregisterContentObserver" to "provider-unregister-content-observer",
        "ContentObserver" to "provider-register-content-observer",
        "grantUriPermission" to "provider-grant-uri-permission",
        "revokeUriPermission" to "provider-revoke-uri-permission",
        "canonicalize" to "provider-canonicalize",
        "uncanonicalize" to "provider-uncanonicalize"
    )

    fun forOperation(operationName: String): String {
        return operationComponents[operationName.substringBefore(':')] ?: "provider-method-unknown"
    }
}
