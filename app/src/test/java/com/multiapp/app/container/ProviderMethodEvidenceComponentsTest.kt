package com.multiapp.app.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProviderMethodEvidenceComponentsTest {

    @Test
    fun `provider method operation names map to stable evidence components`() {
        mapOf(
            "query" to "provider-query",
            "insert" to "provider-insert",
            "update" to "provider-update",
            "delete" to "provider-delete",
            "call:probeCall" to "provider-call",
            "openFile:r" to "provider-open-file",
            "openAssetFile:r" to "provider-open-asset-file",
            "openTypedAssetFile:*/*" to "provider-open-typed-asset-file",
            "bulkInsert" to "provider-bulk-insert",
            "getType" to "provider-get-type"
        ).forEach { (operationName, component) ->
            assertEquals(component, ProviderMethodEvidenceComponents.forOperation(operationName))
        }
    }

    @Test
    fun `unknown provider method maps to safe fallback component`() {
        assertEquals("provider-method-unknown", ProviderMethodEvidenceComponents.forOperation("refresh"))
    }
}
