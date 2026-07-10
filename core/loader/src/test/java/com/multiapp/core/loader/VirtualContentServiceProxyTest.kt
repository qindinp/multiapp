package com.multiapp.core.loader

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualContentServiceProxyTest {
    @Test
    fun `stable observer route removes tokens and matches register with notify`() {
        val route = route()
        val registered = VirtualContentServiceRoutes.rewriteEncodedQuery(
            "bookId=7&multiapp_routeToken=register-token",
            route,
            "com.example.provider"
        )
        val notified = VirtualContentServiceRoutes.rewriteEncodedQuery(
            "bookId=7&multiapp_routeToken=notify-token",
            route,
            "com.example.provider"
        )

        assertEquals(registered, notified)
        assertTrue("multiapp_routeToken" !in registered)
        assertTrue("multiapp_instanceId=inst-001" in registered)
        assertTrue("multiapp_processSlot=com.multiapp.app:v1" in registered)
    }

    @Test
    fun `content service proxy rewrites observer uris and records framework dispatch`() {
        val route = route()
        VirtualContentServiceRoutes.install(route)
        val records = mutableListOf<VirtualContentServiceOperationRecord>()
        VirtualContentServiceOperationRecorders.install { records += it }
        try {
            val guestUri = mockk<Uri>(relaxed = true)
            val proxyUri = mockk<Uri>(relaxed = true)
            val builder = mockk<Uri.Builder>(relaxed = true)
            every { guestUri.authority } returns "com.example.provider"
            every { guestUri.encodedQuery } returns "bookId=7"
            every { guestUri.buildUpon() } returns builder
            every { builder.encodedAuthority("com.multiapp.app.multiapp.provider.stub.v1") } returns builder
            every { builder.encodedQuery(any()) } returns builder
            every { builder.build() } returns proxyUri
            every { proxyUri.authority } returns "com.multiapp.app.multiapp.provider.stub.v1"

            val base = FakeContentServiceImpl()
            val proxy = Proxy.newProxyInstance(
                FakeContentService::class.java.classLoader,
                arrayOf(FakeContentService::class.java),
                VirtualContentServiceHandler(base)
            ) as FakeContentService

            assertEquals("registered", proxy.registerContentObserver(guestUri))
            assertSame(proxyUri, base.lastUri)
            assertEquals("notified", proxy.notifyChange(listOf(guestUri)))
            assertSame(proxyUri, base.lastUris.single())
            assertEquals("unregistered", proxy.unregisterContentObserver("observer-1"))

            assertEquals(3, records.size)
            assertEquals("registerContentObserver", records[0].operation)
            assertEquals(1, records[0].routedUriCount)
            assertEquals("notifyChange", records[1].operation)
            assertEquals(1, records[1].routedUriCount)
            assertEquals("unregisterContentObserver", records[2].operation)
            assertEquals(0, records[2].routedUriCount)
            assertTrue(records.all { it.success })
        } finally {
            VirtualContentServiceOperationRecorders.install(null)
            VirtualContentServiceRoutes.reset()
        }
    }

    private fun route() = VirtualContentServiceRoute(
        instanceId = "inst-001",
        authorityMap = mapOf(
            "com.example.provider" to "com.multiapp.app.multiapp.provider.stub.v1"
        ),
        processSlot = "com.multiapp.app:v1"
    )

    private interface FakeContentService {
        fun registerContentObserver(uri: Uri): String
        fun notifyChange(uris: Collection<Uri>): String
        fun unregisterContentObserver(observer: String): String
    }

    private class FakeContentServiceImpl : FakeContentService {
        var lastUri: Uri? = null
        var lastUris: Collection<Uri> = emptyList()

        override fun registerContentObserver(uri: Uri): String {
            lastUri = uri
            return "registered"
        }

        override fun notifyChange(uris: Collection<Uri>): String {
            lastUris = uris
            return "notified"
        }

        override fun unregisterContentObserver(observer: String): String = "unregistered"
    }
}
