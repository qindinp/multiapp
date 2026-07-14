package com.multiapp.core.engine

import com.multiapp.core.loader.VirtualPackageEnabledStateOperation
import com.multiapp.core.loader.VirtualPackageEnabledStateRequest
import com.multiapp.core.loader.VirtualPackageEnabledStateTarget
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualInstanceRuntime
import com.multiapp.core.model.engine.VirtualRuntimeState
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentAuthority
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.ResolvedIntentPathPattern
import com.multiapp.core.model.virtual.ResolvedIntentPathPatternType
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnginePackageEnabledStateIpcTest {
    @Test
    fun `loader enabled-state dispatcher uses authoritative engine package service`() {
        val runtime = runtime()
        val identity = runtime.toProcessIdentity()
        val service = IpcBackedVirtualPackageService(
            remoteRuntime = { runtime },
            remoteQueryApplication = {
                VirtualPackageEnabledStateResult(
                    instanceId = INSTANCE_ID,
                    target = EnginePackageEnabledStateTarget.APPLICATION,
                    enabledState = EnginePackageEnabledStates.DEFAULT,
                    verdict = EngineResultStatus.PASS,
                    found = true,
                    authorityIdentity = identity,
                    message = "application_enabled_state_queried"
                )
            },
            authorityConnected = { true }
        )
        val dispatcher = EngineVirtualPackageEnabledStateDispatcher(service)

        val result = dispatcher.dispatch(
            VirtualPackageEnabledStateRequest(
                instanceId = INSTANCE_ID,
                packageName = runtime.originPackageName,
                operation = VirtualPackageEnabledStateOperation.QUERY,
                target = VirtualPackageEnabledStateTarget.APPLICATION
            )
        )

        assertTrue(result.authoritative)
        assertTrue(result.found)
        assertEquals(EnginePackageEnabledStates.DEFAULT, result.enabledState)
    }

    @Test
    fun `unavailable package authority fails closed without local fallback`() {
        val fallback = mockk<VirtualPackageService>(relaxed = true)
        val service = IpcBackedVirtualPackageService(
            fallback = fallback,
            remoteRuntime = { runtime() },
            remoteQueryApplication = { null },
            remoteSetComponent = { _, _, _, _ -> null },
            authorityConnected = { false }
        )

        val application = service.queryApplicationEnabledState(INSTANCE_ID)
        val component = service.setComponentEnabledState(
            INSTANCE_ID,
            VirtualPackageComponentType.ACTIVITY,
            ".MainActivity",
            EnginePackageEnabledStates.DISABLED
        )

        assertEquals(EngineResultStatus.FAIL, application.verdict)
        assertFalse(application.found)
        assertEquals("engine_package_authority_unavailable:query-application", application.message)
        assertEquals(EngineResultStatus.FAIL, component.verdict)
        assertEquals("engine_package_authority_unavailable:set-component", component.message)
        verify(exactly = 0) { fallback.queryApplicationEnabledState(any()) }
        verify(exactly = 0) { fallback.setComponentEnabledState(any(), any(), any(), any()) }
    }

    @Test
    fun `mismatched package response identity fails closed`() {
        val runtime = runtime()
        val currentIdentity = runtime.toProcessIdentity()
        val staleIdentity = currentIdentity.copy(runtimeEpoch = currentIdentity.runtimeEpoch + 1L)
        val service = IpcBackedVirtualPackageService(
            remoteRuntime = { runtime },
            remoteQueryApplication = {
                VirtualPackageEnabledStateResult(
                    instanceId = INSTANCE_ID,
                    target = EnginePackageEnabledStateTarget.APPLICATION,
                    enabledState = EnginePackageEnabledStates.ENABLED,
                    verdict = EngineResultStatus.PASS,
                    found = true,
                    authorityIdentity = staleIdentity,
                    message = "forged"
                )
            },
            authorityConnected = { true }
        )

        val result = service.queryApplicationEnabledState(INSTANCE_ID)

        assertEquals(EngineResultStatus.FAIL, result.verdict)
        assertEquals("engine_package_ipc_invalid:query-application", result.message)
    }

    @Test
    fun `ipc backed package resolver uses authoritative matcher and rejects mismatched data`() {
        val service = IpcBackedVirtualPackageService(
            remoteRuntime = { runtime() },
            authorityConnected = { true }
        )

        val matched = service.resolveIntent(
            instanceId = INSTANCE_ID,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.VIEW",
            categories = setOf("android.intent.category.DEFAULT"),
            dataScheme = "https",
            dataMimeType = "image/png",
            dataAuthority = "secure.example.com:8443",
            dataPath = "/secure/item"
        )
        val wrongPort = service.resolveIntent(
            instanceId = INSTANCE_ID,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.VIEW",
            categories = setOf("android.intent.category.DEFAULT"),
            dataScheme = "https",
            dataMimeType = "image/png",
            dataAuthority = "secure.example.com:443",
            dataPath = "/secure/item"
        )
        val missingData = service.resolveIntent(
            instanceId = INSTANCE_ID,
            type = VirtualPackageComponentType.ACTIVITY,
            action = "android.intent.action.VIEW",
            categories = setOf("android.intent.category.DEFAULT")
        )

        assertEquals(listOf("com.test.app.MainActivity"), matched.map { it.name })
        assertEquals(emptyList(), wrongPort)
        assertEquals(emptyList(), missingData)
    }

    private fun runtime() = VirtualInstanceRuntime(
        instanceId = INSTANCE_ID,
        hostPackageName = "com.multiapp.app",
        originPackageName = "com.test.app",
        virtualPackageName = "com.multiapp.virtual.instance_ipc",
        dataRoot = "build/tmp/$INSTANCE_ID",
        packageSnapshot = VirtualPackageSnapshot(
            instanceId = INSTANCE_ID,
            originPackageName = "com.test.app",
            virtualPackageName = "com.multiapp.virtual.instance_ipc",
            applicationLabel = "Test",
            versionCode = 1L,
            versionName = "1.0",
            targetSdk = 36,
            minSdk = 28,
            sourceDir = "build/tmp/base.apk",
            sourceSha256 = "1".repeat(64),
            dataDir = "build/tmp/$INSTANCE_ID",
            activities = listOf(
                ResolvedComponent(
                    name = "com.test.app.MainActivity",
                    exported = true,
                    resolvedIntentFilters = listOf(
                        ResolvedIntentFilter(
                            actions = listOf("android.intent.action.VIEW"),
                            categories = listOf("android.intent.category.DEFAULT"),
                            dataSchemes = listOf("https"),
                            dataMimeTypes = listOf("image/*"),
                            authorityEntries = listOf(
                                ResolvedIntentAuthority("secure.example.com", 8443)
                            ),
                            pathPatterns = listOf(
                                ResolvedIntentPathPattern(
                                    "/secure",
                                    ResolvedIntentPathPatternType.PREFIX
                                )
                            )
                        )
                    )
                )
            )
        ),
        profile = EngineProfile.BASELINE,
        processSlot = "com.multiapp.app:v0",
        proxySlot = "com.multiapp.app.container.ProxyActivity0",
        evidenceSessionId = "evidence-ipc",
        runtimeEpoch = 9L,
        engineSessionId = "engine-ipc",
        processId = 4321,
        processName = "com.multiapp.app:v0",
        state = VirtualRuntimeState.RUNNING
    )

    private fun VirtualInstanceRuntime.toProcessIdentity() = EngineProcessClientIdentity(
        instanceId = instanceId,
        runtimeEpoch = runtimeEpoch,
        engineSessionId = engineSessionId,
        processSlot = processSlot,
        processId = requireNotNull(processId)
    )

    private companion object {
        const val INSTANCE_ID = "instance-ipc"
    }
}
