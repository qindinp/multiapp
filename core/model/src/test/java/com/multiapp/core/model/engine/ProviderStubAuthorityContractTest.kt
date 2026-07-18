package com.multiapp.core.model.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderStubAuthorityContractTest {

    @Test
    fun `valid process slots map to indexed stub authorities`() {
        repeat(8) { index ->
            assertEquals(
                "com.multiapp.app.multiapp.provider.stub.v$index",
                ProviderStubAuthorityContract.stubAuthority(
                    hostPackageName = "com.multiapp.app",
                    processSlot = "com.multiapp.app:v$index"
                )
            )
        }
    }

    @Test
    fun `missing foreign malformed and out of range slots use base authority`() {
        listOf(
            null,
            "",
            " ",
            "com.other:v3",
            "com.multiapp.app:vx",
            "com.multiapp.app:v-1",
            "com.multiapp.app:v8"
        ).forEach { processSlot ->
            assertEquals(
                "com.multiapp.app.multiapp.provider.stub",
                ProviderStubAuthorityContract.stubAuthority("com.multiapp.app", processSlot)
            )
        }
    }

    @Test
    fun `numeric process slot is returned in canonical form`() {
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub.v3",
            ProviderStubAuthorityContract.stubAuthority("com.multiapp.app", "com.multiapp.app:v03")
        )
    }

    @Test
    fun `base and slot stub authorities expose host package name`() {
        assertEquals(
            "com.multiapp.app",
            ProviderStubAuthorityContract.hostPackageNameOrNull(
                "com.multiapp.app.multiapp.provider.stub"
            )
        )
        assertEquals(
            "com.multiapp.app",
            ProviderStubAuthorityContract.hostPackageNameOrNull(
                "com.multiapp.app.multiapp.provider.stub.v7"
            )
        )
    }

    @Test
    fun `non stub and non canonical authorities are rejected`() {
        listOf(
            null,
            "",
            "com.example.guest.provider",
            ".multiapp.provider.stub",
            "com.multiapp.app.multiapp.provider.stub.extra",
            "com.multiapp.app.multiapp.provider.stub.v03",
            "com.multiapp.app.multiapp.provider.stub.v8",
            "com.multiapp.app.multiapp.provider.stub.vx"
        ).forEach { authority ->
            assertEquals(null, ProviderStubAuthorityContract.hostPackageNameOrNull(authority))
            assertEquals(
                null,
                ProviderStubAuthorityContract.reselectProcessSlot(
                    authority,
                    processSlot = "com.multiapp.app:v3"
                )
            )
        }
    }

    @Test
    fun `base and slot stub authorities can reselect process slot`() {
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub.v4",
            ProviderStubAuthorityContract.reselectProcessSlot(
                "com.multiapp.app.multiapp.provider.stub",
                "com.multiapp.app:v4"
            )
        )
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub.v6",
            ProviderStubAuthorityContract.reselectProcessSlot(
                "com.multiapp.app.multiapp.provider.stub.v2",
                "com.multiapp.app:v6"
            )
        )
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub",
            ProviderStubAuthorityContract.reselectProcessSlot(
                "com.multiapp.app.multiapp.provider.stub.v2",
                processSlot = null
            )
        )
    }
}
