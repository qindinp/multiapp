package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualPermissionCheckRequest
import com.multiapp.core.model.engine.EngineResultStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame

class EnginePermissionIpcServiceTest {
    @Test
    fun `connected authority owns permission checks`() {
        val fallback = mockk<VirtualPermissionService>(relaxed = true)
        val remote = result(granted = true)
        val service = IpcBackedVirtualPermissionService(
            fallback = fallback,
            remoteCheck = { _, _ -> remote },
            authorityConnected = { true }
        )

        assertSame(service.checkPermission("instance-1", PERMISSION), remote)
        verify(exactly = 0) { fallback.checkPermission(any(), any()) }
    }

    @Test
    fun `connected malformed response fails closed and unavailable authority uses fallback`() {
        val fallback = mockk<VirtualPermissionService>()
        val local = result(granted = true)
        every { fallback.checkPermission("instance-1", PERMISSION) } returns local
        val connected = IpcBackedVirtualPermissionService(
            fallback = fallback,
            remoteCheck = { _, _ -> null },
            authorityConnected = { true }
        )
        val unavailable = IpcBackedVirtualPermissionService(
            fallback = fallback,
            remoteCheck = { _, _ -> null },
            authorityConnected = { false }
        )

        assertFalse(connected.checkPermission("instance-1", PERMISSION).granted)
        assertSame(local, unavailable.checkPermission("instance-1", PERMISSION))
    }

    @Test
    fun `loader permission dispatcher rejects package identity mismatch`() {
        val service = mockk<VirtualPermissionService>(relaxed = true)
        every { service.queryRuntimeBinding("instance-1") } returns VirtualSubsystemRuntimeBinding(
            instanceId = "instance-1",
            subsystem = com.multiapp.core.model.engine.EngineSubsystem.PERMISSION,
            verdict = EngineResultStatus.PARTIAL,
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.instance1",
            message = "bound"
        )
        val dispatcher = EngineVirtualPermissionDispatcher(service)

        val result = dispatcher.dispatch(
            VirtualPermissionCheckRequest(
                instanceId = "instance-1",
                packageName = "com.forged.app",
                permissionName = PERMISSION
            )
        )

        assertFalse(result.granted)
        verify(exactly = 0) { service.checkPermission(any(), any()) }
    }

    private fun result(granted: Boolean) = VirtualPermissionCheckResult(
        instanceId = "instance-1",
        permissionName = PERMISSION,
        verdict = if (granted) EngineResultStatus.PASS else EngineResultStatus.FAIL,
        requested = true,
        granted = granted,
        explicit = true,
        source = EnginePermissionGrantSource.USER_DECISION,
        message = if (granted) "permission_granted" else "permission_denied"
    )

    private companion object {
        const val PERMISSION = "com.test.permission.READ"
    }
}
