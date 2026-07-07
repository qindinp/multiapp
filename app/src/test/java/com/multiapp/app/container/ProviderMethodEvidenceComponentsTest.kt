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
        ).forEach { (operationName, component) ->
            assertEquals(component, ProviderMethodEvidenceComponents.forOperation(operationName))
        }
    }

    @Test
    fun `unknown provider method maps to safe fallback component`() {
        assertEquals("provider-method-unknown", ProviderMethodEvidenceComponents.forOperation("refresh"))
    }
}
