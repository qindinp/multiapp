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
        "getType" to "provider-get-type"
    )

    fun forOperation(operationName: String): String {
        return operationComponents[operationName.substringBefore(':')] ?: "provider-method-unknown"
    }
}
