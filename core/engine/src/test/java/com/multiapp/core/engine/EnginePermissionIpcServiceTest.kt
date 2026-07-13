package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualPermissionCheckRequest
import com.multiapp.core.model.engine.EngineResultStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `malformed and unavailable permission authority fail closed without fallback`() {
        val fallback = mockk<VirtualPermissionService>(relaxed = true)
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

        val invalid = connected.checkPermission("instance-1", PERMISSION)
        val disconnected = unavailable.checkPermission("instance-1", PERMISSION)

        assertFalse(invalid.granted)
        assertEquals("engine_permission_ipc_check_invalid", invalid.message)
        assertFalse(disconnected.granted)
        assertEquals("engine_permission_authority_unavailable:check", disconnected.message)
        verify(exactly = 0) { fallback.checkPermission(any(), any()) }
    }

    @Test
    fun `permission mutations are unsupported and never use local fallback`() {
        val fallback = mockk<VirtualPermissionService>(relaxed = true)
        val service = IpcBackedVirtualPermissionService(
            fallback = fallback,
            authorityConnected = { false }
        )

        val set = service.setPermissionGrant(
            "instance-1",
            PERMISSION,
            true,
            EnginePermissionGrantSource.USER_DECISION
        )
        val cleared = service.clearPermissionGrant("instance-1", PERMISSION)

        assertEquals(EngineResultStatus.UNSUPPORTED, set.verdict)
        assertEquals("engine_permission_remote_mutation_unsupported:set-grant", set.message)
        assertEquals(0, cleared)
        verify(exactly = 0) { fallback.setPermissionGrant(any(), any(), any(), any()) }
        verify(exactly = 0) { fallback.clearPermissionGrant(any(), any()) }
    }

    @Test
    fun `unavailable authority exposes explicit read only permission snapshots as partial`() {
        val fallback = mockk<VirtualPermissionService>(relaxed = true)
        val state = VirtualPermissionRuntimeState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PASS,
            message = "durable_permission_snapshot"
        )
        val binding = VirtualSubsystemRuntimeBinding(
            instanceId = "instance-1",
            subsystem = com.multiapp.core.model.engine.EngineSubsystem.PERMISSION,
            verdict = EngineResultStatus.PASS,
            message = "durable_permission_binding"
        )
        val service = IpcBackedVirtualPermissionService(
            fallback = fallback,
            remoteState = { null },
            readOnlyRuntimeStateSnapshot = { state },
            readOnlyRuntimeBindingSnapshot = { binding },
            authorityConnected = { false }
        )

        assertEquals(EngineResultStatus.PARTIAL, service.queryRuntimeState("instance-1").verdict)
        assertEquals(EngineResultStatus.PARTIAL, service.queryRuntimeBinding("instance-1").verdict)
        verify(exactly = 0) { fallback.queryRuntimeState(any()) }
        verify(exactly = 0) { fallback.queryRuntimeBinding(any()) }
    }

    @Test
    fun `mismatched permission response identity fails closed`() {
        val fallback = mockk<VirtualPermissionService>(relaxed = true)
        val service = IpcBackedVirtualPermissionService(
            fallback = fallback,
            remoteCheck = { _, _ -> result(granted = true).copy(instanceId = "instance-2") },
            authorityConnected = { true }
        )

        val actual = service.checkPermission("instance-1", PERMISSION)

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertFalse(actual.granted)
        verify(exactly = 0) { fallback.checkPermission(any(), any()) }
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
