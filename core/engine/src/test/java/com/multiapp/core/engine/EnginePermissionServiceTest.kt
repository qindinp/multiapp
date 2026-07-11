package com.multiapp.core.engine

import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnginePermissionServiceTest {
    @Test
    fun `requested permission remains unknown until engine records explicit decision`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(
            testRuntime(
                instanceId = "instance-1",
                permissions = listOf(PERMISSION)
            )
        )

        val unknown = server.permissionService.checkPermission(runtime.instanceId, PERMISSION)
        val granted = server.permissionService.setPermissionGrant(
            runtime.instanceId,
            PERMISSION,
            granted = true,
            source = EnginePermissionGrantSource.USER_DECISION
        )
        val restored = server.permissionService.checkPermission(runtime.instanceId, PERMISSION)

        assertEquals(EngineResultStatus.UNSUPPORTED, unknown.verdict)
        assertFalse(unknown.granted)
        assertEquals(EngineResultStatus.PASS, granted.verdict)
        assertTrue(granted.granted)
        assertTrue(restored.explicit)
        assertEquals(EnginePermissionGrantSource.USER_DECISION, restored.source)
    }

    @Test
    fun `permission not requested cannot be granted`() {
        val server = DefaultVirtualSystemServer(EngineRuntimeRegistry())
        val runtime = server.runtimeService.register(testRuntime(instanceId = "instance-1"))

        val result = server.permissionService.setPermissionGrant(
            runtime.instanceId,
            PERMISSION,
            granted = true,
            source = EnginePermissionGrantSource.ENGINE_POLICY
        )

        assertEquals(EngineResultStatus.FAIL, result.verdict)
        assertFalse(result.requested)
        assertFalse(result.granted)
        assertTrue(server.permissionService.queryRuntimeState(runtime.instanceId).records.isEmpty())
    }

    private fun testRuntime(
        instanceId: String,
        permissions: List<String> = emptyList()
    ) = VirtualInstanceRuntime(
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
            dataDir = "/data/$instanceId",
            permissions = permissions
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v1",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-$instanceId",
        runtimeEpoch = 42L,
        engineSessionId = "engine-$instanceId",
        processId = 4101,
        processName = "com.multiapp.app:v1",
        state = VirtualRuntimeState.RUNNING
    )

    private companion object {
        const val PERMISSION = "com.example.permission.READ_PRIVATE"
    }
}
