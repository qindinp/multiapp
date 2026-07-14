package com.multiapp.core.engine

import android.net.Uri
import com.multiapp.core.loader.VirtualUriPermissionOperation
import com.multiapp.core.loader.VirtualUriPermissionRequest
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineVirtualUriPermissionDispatcherTest {
    @Test
    fun `guest URI grant carries instance path and host caller identity to engine`() {
        val service = mockk<VirtualProviderService>(relaxed = true)
        val requestSlot = slot<VirtualProviderUriGrantRequest>()
        every { service.grantUriPermission("owner", capture(requestSlot)) } returns result(
            ownerInstanceId = "owner",
            targetInstanceId = "target",
            granted = true,
            message = "provider_uri_grant_recorded"
        )
        val dispatcher = dispatcher(service)

        val actual = dispatcher.dispatch(
            VirtualUriPermissionRequest(
                operation = VirtualUriPermissionOperation.GRANT,
                uri = uri("com.test.provider", "/books/7"),
                modeFlags = EngineProviderUriGrantModes.READ,
                targetPackageName = "com.multiapp.virtual.target"
            )
        )

        assertTrue(actual.handled)
        assertTrue(actual.success)
        assertTrue(actual.granted)
        assertEquals("/books/7", requestSlot.captured.encodedPath)
        assertEquals("com.multiapp.virtual.target", requestSlot.captured.targetPackageName)
        assertEquals(1000, requestSlot.captured.callingUid)
        assertEquals(3001, requestSlot.captured.callingPid)
        assertEquals(1000, requestSlot.captured.hostUid)
    }

    @Test
    fun `guest URI check resolves target instance by durable process id`() {
        val service = mockk<VirtualProviderService>(relaxed = true)
        val requestSlot = slot<VirtualProviderUriGrantRequest>()
        every { service.checkUriPermission("target", capture(requestSlot)) } returns result(
            ownerInstanceId = "owner",
            targetInstanceId = "target",
            granted = true,
            message = "provider_uri_grant_confirmed"
        )
        val dispatcher = dispatcher(
            service = service,
            runtimesByPid = mapOf(3002 to runtimeSnapshot("target", 3002))
        )

        val actual = dispatcher.dispatch(
            VirtualUriPermissionRequest(
                operation = VirtualUriPermissionOperation.CHECK,
                uri = uri("com.test.provider", "/books/7"),
                modeFlags = EngineProviderUriGrantModes.READ,
                pid = 3002,
                uid = 1000
            )
        )

        assertTrue(actual.handled)
        assertTrue(actual.success)
        assertTrue(actual.granted)
        assertEquals("owner", requestSlot.captured.ownerInstanceId)
        assertEquals("target", requestSlot.captured.targetInstanceId)
        assertEquals(3002, requestSlot.captured.callingPid)
    }

    @Test
    fun `system authority is not claimed by virtual URI permission dispatcher`() {
        val service = mockk<VirtualProviderService>(relaxed = true)
        every {
            service.resolveProviderAuthority("owner", any())
        } returns VirtualProviderAuthorityResolveResult(
            callerInstanceId = "owner",
            guestAuthority = "settings",
            verdict = EngineResultStatus.FAIL,
            virtualAuthority = false,
            message = "provider_authority_not_virtual"
        )
        val dispatcher = dispatcher(service)

        val actual = dispatcher.dispatch(
            VirtualUriPermissionRequest(
                operation = VirtualUriPermissionOperation.CHECK,
                uri = uri("settings", "/system"),
                modeFlags = EngineProviderUriGrantModes.READ,
                pid = 3001,
                uid = 1000
            )
        )

        assertFalse(actual.handled)
        verify(exactly = 0) { service.checkUriPermission(any(), any()) }
    }

    @Test
    fun `persistable take for another virtual provider routes as target instance`() {
        val service = mockk<VirtualProviderService>(relaxed = true)
        val requestSlot = slot<VirtualProviderUriGrantRequest>()
        every {
            service.takePersistableUriPermission("owner", capture(requestSlot))
        } returns result(
            ownerInstanceId = "provider-owner",
            targetInstanceId = "owner",
            granted = true,
            message = "provider_persistable_uri_grant_taken:modes=1"
        )
        every {
            service.resolveProviderAuthority("owner", any())
        } returns VirtualProviderAuthorityResolveResult(
            callerInstanceId = "owner",
            guestAuthority = "com.external.provider",
            verdict = EngineResultStatus.PASS,
            virtualAuthority = true,
            targetInstanceId = "provider-owner",
            message = "provider_authority_resolved"
        )
        val dispatcher = dispatcher(
            service = service
        )

        val actual = dispatcher.dispatch(
            VirtualUriPermissionRequest(
                operation = VirtualUriPermissionOperation.TAKE_PERSISTABLE,
                uri = uri("com.external.provider", "/documents/7"),
                modeFlags = EngineProviderUriGrantModes.READ
            )
        )

        assertTrue(actual.handled)
        assertTrue(actual.success)
        assertEquals(null, requestSlot.captured.ownerInstanceId)
        assertEquals("owner", requestSlot.captured.targetInstanceId)
        assertEquals(3001, requestSlot.captured.callingPid)
    }

    private fun dispatcher(
        service: VirtualProviderService,
        runtimesByPid: Map<Int, EngineRuntimeIpcSnapshot> = emptyMap()
    ) = EngineVirtualUriPermissionDispatcher(
        config = config(),
        providerService = service,
        runtimeByProcessId = runtimesByPid::get,
        hostUid = 1000,
        processId = 3001
    )

    private fun uri(authority: String, encodedPath: String): Uri = mockk<Uri>().also { uri ->
        every { uri.authority } returns authority
        every { uri.encodedPath } returns encodedPath
    }

    private fun result(
        ownerInstanceId: String,
        targetInstanceId: String,
        granted: Boolean,
        message: String
    ) = VirtualProviderUriGrantResult(
        ownerInstanceId = ownerInstanceId,
        targetInstanceId = targetInstanceId,
        guestAuthority = "com.test.provider",
        encodedPath = "/books/7",
        modeFlags = EngineProviderUriGrantModes.READ,
        verdict = EngineResultStatus.PASS,
        granted = granted,
        message = message
    )

    private fun config() = VirtualContextConfig(
        instanceId = "owner",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.owner",
        dataDir = "/data/owner",
        sourceDir = "/data/owner/base.apk",
        nativeLibraryDir = null,
        classLoader = ClassLoader.getSystemClassLoader(),
        packageSnapshot = snapshot("owner", "com.test.app", "com.multiapp.virtual.owner")
    )

    private fun runtimeSnapshot(
        instanceId: String,
        processId: Int
    ) = EngineRuntimeIpcSnapshot(
        found = true,
        instanceId = instanceId,
        processSlot = "com.multiapp.app:v2",
        proxySlot = "com.multiapp.app.container.ProxyActivity2",
        runtimeEpoch = 42L,
        engineSessionId = "engine-$instanceId",
        evidenceSessionId = "evidence-$instanceId",
        runtimeState = "RUNNING",
        processId = processId,
        processName = "com.multiapp.app:v2",
        reason = "live_runtime_authority_confirmed",
        liveAuthority = true
    )

    private fun snapshot(
        instanceId: String,
        origin: String,
        virtual: String,
        authority: String = "com.test.provider"
    ) = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = origin,
        virtualPackageName = virtual,
        applicationLabel = "Test",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        sourceDir = "/data/$instanceId/base.apk",
        dataDir = "/data/$instanceId",
        providers = listOf(
            ResolvedComponent(
                name = "$origin.Provider",
                authorities = listOf(authority),
                grantUriPermissions = true
            )
        )
    )
}
