package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineAppOpsServiceTest {
    @Test
    fun `virtual AppOps mode is persistent per instance and otherwise delegates system`() {
        val registry = EngineRuntimeRegistry()
        val server = DefaultVirtualSystemServer(
            registry = registry,
            appOpsStateStore = InMemoryEngineAppOpsStateStore()
        )
        val first = server.runtimeService.register(runtime("instance-1", 5001))
        val second = server.runtimeService.register(runtime("instance-2", 5002))

        val before = server.appOpsService.queryMode(first.instanceId, query(first, 5001))
        val persisted = server.appOpsService.setMode(first.instanceId, 26, EngineAppOpModes.IGNORED)
        val after = server.appOpsService.queryMode(first.instanceId, query(first, 5001))
        val other = server.appOpsService.queryMode(second.instanceId, query(second, 5002))

        assertEquals(EngineResultStatus.PARTIAL, before.verdict)
        assertFalse(before.intercept)
        assertEquals(EngineResultStatus.PASS, persisted.verdict)
        assertEquals(EngineAppOpModes.IGNORED, after.mode)
        assertTrue(after.explicitMode)
        assertTrue(after.intercept)
        assertEquals(EngineResultStatus.PARTIAL, other.verdict)
        assertFalse(other.intercept)
    }

    @Test
    fun `guest AppOps mutation and mismatched process identity fail closed`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(runtime("instance-1", 5101))

        val mutation = server.appOpsService.queryMode(
            runtime.instanceId,
            query(runtime, 5101).copy(methodName = "setMode")
        )
        val pidMismatch = server.appOpsService.queryMode(
            runtime.instanceId,
            query(runtime, 5999)
        )

        assertEquals(EngineResultStatus.FAIL, mutation.verdict)
        assertTrue(mutation.blockSystemCall)
        assertEquals("guest_app_ops_mutation_blocked:setMode", mutation.message)
        assertEquals(EngineResultStatus.FAIL, pidMismatch.verdict)
        assertTrue(pidMismatch.blockSystemCall)
        assertTrue(pidMismatch.message.startsWith("app_ops_pid_mismatch:"))
    }

    @Test
    fun `connected IPC authority owns AppOps query and fails closed on invalid response`() {
        val fallback = mockk<VirtualAppOpsService>(relaxed = true)
        val request = VirtualAppOpsQueryRequest(
            methodName = "checkOperation",
            opCode = 26,
            uid = 1000,
            packageName = "com.test.app"
        )
        val remote = VirtualAppOpsQueryResult(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PASS,
            mode = EngineAppOpModes.IGNORED,
            explicitMode = true,
            intercept = true,
            message = "remote_mode"
        )
        val connected = IpcBackedVirtualAppOpsService(
            fallback = fallback,
            remoteQuery = { _, _ -> remote },
            authorityConnected = { true }
        )
        val invalid = IpcBackedVirtualAppOpsService(
            fallback = fallback,
            remoteQuery = { _, _ -> null },
            authorityConnected = { true }
        )

        assertSame(remote, connected.queryMode("instance-1", request))
        val invalidResult = invalid.queryMode("instance-1", request)
        assertEquals(EngineResultStatus.FAIL, invalidResult.verdict)
        assertTrue(invalidResult.blockSystemCall)
        verify(exactly = 0) { fallback.queryMode(any(), any()) }
    }

    @Test
    fun `unavailable AppOps authority fails closed without local query or mutation fallback`() {
        val fallback = mockk<VirtualAppOpsService>(relaxed = true)
        val request = VirtualAppOpsQueryRequest(
            methodName = "checkOperation",
            opCode = 26,
            uid = 1000,
            packageName = "com.test.app"
        )
        val service = IpcBackedVirtualAppOpsService(
            fallback = fallback,
            remoteQuery = { _, _ -> null },
            authorityConnected = { false }
        )

        val query = service.queryMode("instance-1", request)
        val set = service.setMode("instance-1", 26, EngineAppOpModes.IGNORED)
        val reset = service.resetModes("instance-1", 26)

        assertEquals(EngineResultStatus.FAIL, query.verdict)
        assertTrue(query.blockSystemCall)
        assertEquals("engine_app_ops_authority_unavailable:query", query.message)
        assertEquals(EngineResultStatus.UNSUPPORTED, set.verdict)
        assertEquals(EngineResultStatus.UNSUPPORTED, reset.verdict)
        verify(exactly = 0) { fallback.queryMode(any(), any()) }
        verify(exactly = 0) { fallback.setMode(any(), any(), any()) }
        verify(exactly = 0) { fallback.resetModes(any(), any()) }
    }

    @Test
    fun `unavailable AppOps authority exposes explicit read only snapshots as partial`() {
        val fallback = mockk<VirtualAppOpsService>(relaxed = true)
        val state = VirtualAppOpsRuntimeState(
            instanceId = "instance-1",
            verdict = EngineResultStatus.PASS,
            records = listOf(EngineAppOpModeRecord("instance-1", 26, EngineAppOpModes.IGNORED)),
            message = "durable_app_ops_snapshot"
        )
        val binding = VirtualSubsystemRuntimeBinding(
            instanceId = "instance-1",
            subsystem = com.multiapp.core.model.engine.EngineSubsystem.APP_OPS,
            verdict = EngineResultStatus.PASS,
            message = "durable_app_ops_binding"
        )
        val service = IpcBackedVirtualAppOpsService(
            fallback = fallback,
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
    fun `mismatched AppOps response identity fails closed`() {
        val fallback = mockk<VirtualAppOpsService>(relaxed = true)
        val request = VirtualAppOpsQueryRequest(methodName = "checkOperation")
        val service = IpcBackedVirtualAppOpsService(
            fallback = fallback,
            remoteQuery = { _, _ ->
                VirtualAppOpsQueryResult(
                    instanceId = "instance-2",
                    verdict = EngineResultStatus.PASS,
                    mode = EngineAppOpModes.ALLOWED,
                    message = "forged"
                )
            },
            authorityConnected = { true }
        )

        val actual = service.queryMode("instance-1", request)

        assertEquals(EngineResultStatus.FAIL, actual.verdict)
        assertTrue(actual.blockSystemCall)
        verify(exactly = 0) { fallback.queryMode(any(), any()) }
    }

    private fun query(runtime: VirtualInstanceRuntime, pid: Int) = VirtualAppOpsQueryRequest(
        methodName = "checkOperation",
        opCode = 26,
        uid = 1000,
        packageName = runtime.originPackageName,
        hostUid = 1000,
        callingPid = pid
    )

    private fun runtime(instanceId: String, pid: Int) = VirtualInstanceRuntime(
        instanceId = instanceId,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.$instanceId",
        dataRoot = "/data/$instanceId",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = instanceId,
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.$instanceId",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 35,
            minSdk = 28,
            sourceDir = "/data/$instanceId/base.apk",
            dataDir = "/data/$instanceId"
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v$instanceId",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-$instanceId",
        runtimeEpoch = 42L,
        engineSessionId = "engine-$instanceId",
        processId = pid,
        processName = "com.multiapp.app:v$instanceId",
        state = VirtualRuntimeState.RUNNING
    )
}
