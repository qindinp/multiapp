package com.multiapp.core.loader

import android.net.Uri
import android.os.IBinder
import android.os.IInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VirtualUriGrantsServiceProxyTest {
    @Test
    fun `binder proxy handles virtual take and delegates system URI`() {
        val baseBinder = mockk<IBinder>(relaxed = true)
        val baseService = mockk<FakeUriGrantsManager>(relaxed = true)
        val virtualUri = uri("com.virtual.documents")
        val systemUri = uri("settings")
        val requests = mutableListOf<VirtualUriPermissionRequest>()
        val binder = requireNotNull(
            VirtualUriGrantsServiceProxy.createBinderProxy(
                baseBinder = baseBinder,
                baseService = baseService,
                serviceInterface = FakeUriGrantsManager::class.java,
                dispatcher = { request ->
                    requests += request
                    if (request.uri === virtualUri) {
                        VirtualUriPermissionResult(
                            handled = true,
                            success = true,
                            granted = true,
                            reason = "persisted"
                        )
                    } else {
                        VirtualUriPermissionResult.notHandled("system_uri")
                    }
                }
            )
        )
        val service = binder.queryLocalInterface(DESCRIPTOR) as FakeUriGrantsManager

        service.takePersistableUriPermission(virtualUri, 1, null, 0)
        service.takePersistableUriPermission(systemUri, 1, null, 0)

        assertEquals(VirtualUriPermissionOperation.TAKE_PERSISTABLE, requests.first().operation)
        verify(exactly = 0) { baseService.takePersistableUriPermission(virtualUri, any(), any(), any()) }
        verify(exactly = 1) { baseService.takePersistableUriPermission(systemUri, 1, null, 0) }
    }

    @Test
    fun `binder proxy fails closed when virtual release is denied`() {
        val baseBinder = mockk<IBinder>(relaxed = true)
        val baseService = mockk<FakeUriGrantsManager>(relaxed = true)
        val virtualUri = uri("com.virtual.documents")
        val binder = requireNotNull(
            VirtualUriGrantsServiceProxy.createBinderProxy(
                baseBinder = baseBinder,
                baseService = baseService,
                serviceInterface = FakeUriGrantsManager::class.java,
                dispatcher = {
                    VirtualUriPermissionResult(
                        handled = true,
                        success = false,
                        granted = false,
                        reason = "persistable_grant_missing"
                    )
                }
            )
        )
        val service = binder.queryLocalInterface(DESCRIPTOR) as FakeUriGrantsManager

        val error = assertFailsWith<SecurityException> {
            service.releasePersistableUriPermission(virtualUri, 1, null, 0)
        }

        assertEquals("persistable_grant_missing", error.message)
        verify(exactly = 0) { baseService.releasePersistableUriPermission(any(), any(), any(), any()) }
    }

    private fun uri(authority: String): Uri = mockk<Uri>().also { uri ->
        every { uri.authority } returns authority
    }

    private interface FakeUriGrantsManager : IInterface {
        fun takePersistableUriPermission(uri: Uri, modeFlags: Int, toPackage: String?, userId: Int)
        fun releasePersistableUriPermission(uri: Uri, modeFlags: Int, toPackage: String?, userId: Int)
    }

    private companion object {
        const val DESCRIPTOR = "android.app.IUriGrantsManager"
    }
}
