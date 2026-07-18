package com.multiapp.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppModuleProviderAuthorityTest {
    @Test
    fun `provider authorities preserve all manifest aliases`() {
        assertEquals(
            listOf("com.example.primary", "com.example.legacy"),
            AppModule.providerAuthorities(" com.example.primary ; com.example.legacy ; com.example.primary ")
        )
    }

    @Test
    fun `missing provider authority stays empty`() {
        assertEquals(emptyList<String>(), AppModule.providerAuthorities(null))
        assertEquals(emptyList<String>(), AppModule.providerAuthorities(" ; "))
    }
}
