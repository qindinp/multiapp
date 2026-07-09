package com.multiapp.core.engine

import android.content.Intent
import android.content.Context
import android.net.Uri
import com.multiapp.core.loader.BootstrapResult
import com.multiapp.core.loader.HostedBootstrapResult
import com.multiapp.core.loader.VirtualProcessRuntime
import com.multiapp.core.loader.VirtualServiceIntentStore
import com.multiapp.core.loader.VirtualServiceManager
import com.multiapp.core.loader.toSummary
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineContainerDispatchersTest {

    @Test
    fun `provider dispatch request rejects blank host package`() {
        assertFailsWith<IllegalArgumentException> {
            EngineProviderDispatchRequest(
                hostPackageName = "",
                hostContext = mockk<Context>(relaxed = true),
                proxyUri = mockk<Uri>(relaxed = true)
            )
        }
    }

    @Test
    fun `provider route slots map process slot to stub authority`() {
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub",
            EngineProviderRouteSlots.stubAuthority("com.multiapp.app", processSlot = null)
        )
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub.v3",
            EngineProviderRouteSlots.stubAuthority("com.multiapp.app", "com.multiapp.app:v3")
        )
        assertEquals(
            "com.multiapp.app.multiapp.provider.stub",
            EngineProviderRouteSlots.stubAuthority("com.multiapp.app", "com.other:v3")
        )
    }

    @Test
    fun `service route rejects blank identity`() {
        assertFailsWith<IllegalArgumentException> {
            EngineServiceStartRoute.create(
                instanceId = "",
                originPackageName = "com.example.app",
                guestServiceClassName = "com.example.app.SyncService"
            )
        }
    }

    @Test
    fun `service router decodes route from proxy intent`() {
        val proxyToken = "token-001"
        val sourceIntent = mockk<Intent>(relaxed = true)
        VirtualServiceIntentStore.remember(proxyToken, sourceIntent)
        val intent = proxyIntent(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            guestServiceClassName = "com.example.app.SyncService",
            reason = "explicit",
            foreground = true,
            processSlot = "com.multiapp.app:v2",
            proxyToken = proxyToken
        )
        val router = DefaultEngineServiceRouter(
            processRuntime = DefaultEngineHostedProcessRuntime(VirtualProcessRuntime())
        )

        val route = try {
            assertNotNull(router.routeFromProxyIntent("com.multiapp.app", intent))
        } finally {
            VirtualServiceIntentStore.clear(proxyToken)
        }

        assertEquals("inst-001", route.instanceId)
        assertEquals("com.example.app", route.originPackageName)
        assertEquals("com.example.app.SyncService", route.guestServiceClassName)
        assertEquals("explicit", route.reason)
        assertTrue(route.foreground)
        assertEquals("com.multiapp.app:v2", route.processSlot)
    }

    @Test
    fun `service router builds launch info from extras when route is missing`() {
        val intent = proxyIntent(
            instanceId = "inst-002",
            originPackageName = "com.example.other",
            guestServiceClassName = "com.example.other.SyncService",
            reason = "legacy"
        )

        val info = DefaultEngineServiceRouter(
            processRuntime = DefaultEngineHostedProcessRuntime(VirtualProcessRuntime())
        ).launchInfo(intent, route = null)

        assertEquals("inst-002", info.instanceId)
        assertEquals("com.example.other", info.originPackageName)
        assertEquals("com.example.other.SyncService", info.guestServiceClassName)
        assertEquals("legacy", info.reason)
        assertFalse(info.foreground)
    }

    @Test
    fun `service router checks reusable runtime through injected runtime`() {
        val runtime = VirtualProcessRuntime()
        val router = DefaultEngineServiceRouter(
            processRuntime = DefaultEngineHostedProcessRuntime(runtime)
        )

        assertFalse(router.hasReusableRuntime("inst-001"))

        runtime.bindApplication("inst-001") { hostedResult("inst-001") }

        assertTrue(router.hasReusableRuntime("inst-001"))
    }

    @Test
    fun `service dispatch result exports evidence and keeps active service running`() {
        val result = EngineServiceDispatchResult.ServiceStarted(
            startRequest = serviceStartSnapshot(),
            cached = false,
            startCommandResult = 1,
            lifecycleEvidence = EngineServiceLifecycleEvidence(
                instanceId = "inst-001",
                guestServiceClassName = "com.example.app.SyncService",
                event = "CREATED_AND_STARTED",
                success = true,
                startCommandResult = 1,
                activeStartCount = 1
            )
        )

        val evidence = result.toEngineEvidenceFields(
            fallbackInstanceId = "",
            fallbackOriginPackageName = "",
            fallbackGuestServiceClassName = "",
            fallbackReason = "",
            fallbackForeground = false,
            foregroundStatus = "SKIPPED",
            runtimeBindEvidence = EngineServiceRuntimeBindEvidence(
                status = "BOUND",
                detail = "reusedRuntime",
                processSlot = "com.multiapp.app:v1",
                errorClassName = null,
                errorMessage = null
            ),
            startId = 42,
            defaultHostStartCommandResult = 2,
            undecidedHostReturnMode = "UNDECIDED"
        )
        val stopDecision = evidence.stubStopDecision(foregroundStartedStatus = "STARTED")

        assertEquals("STARTED", evidence.status)
        assertEquals("inst-001", evidence.instanceId)
        assertEquals("com.multiapp.app:v1", evidence.runtimeBindProcessSlot)
        assertEquals(1, evidence.startCommandResult)
        assertFalse(stopDecision.stop)
        assertEquals("KEEP_GUEST_SERVICE_ACTIVE", stopDecision.reason)
    }

    @Test
    fun `invalid service dispatch result uses fallback identity and stops stub`() {
        val result = EngineServiceDispatchResult.InvalidProxyIntent("missing service extras")

        val evidence = result.toEngineEvidenceFields(
            fallbackInstanceId = "fallback-inst",
            fallbackOriginPackageName = "com.example.app",
            fallbackGuestServiceClassName = "com.example.app.SyncService",
            fallbackReason = "legacy",
            fallbackForeground = false,
            foregroundStatus = "SKIPPED",
            runtimeBindEvidence = EngineServiceRuntimeBindEvidence(
                status = "NOT_REQUESTED",
                detail = "missing route",
                processSlot = null,
                errorClassName = null,
                errorMessage = null
            ),
            startId = 7,
            defaultHostStartCommandResult = 2,
            undecidedHostReturnMode = "UNDECIDED"
        )
        val stopDecision = evidence.stubStopDecision(foregroundStartedStatus = "STARTED")

        assertEquals("INVALID_PROXY_INTENT", evidence.status)
        assertEquals("fallback-inst", evidence.instanceId)
        assertEquals("missing service extras", evidence.detail)
        assertTrue(stopDecision.stop)
        assertEquals("STOP_DISPATCH_INVALID_PROXY_INTENT", stopDecision.reason)
    }

    private fun hostedResult(instanceId: String): HostedBootstrapResult = HostedBootstrapResult(
        instanceId = instanceId,
        installId = "com.example.app",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.example",
        originApkPath = "/tmp/base.apk",
        dataRoot = "/tmp/$instanceId",
        guestClassLoader = ClassLoader.getSystemClassLoader(),
        guestApplication = null,
        installRecord = null,
        packageSnapshot = null,
        launcherActivityClassName = "com.example.app.MainActivity",
        stageResults = emptyList(),
        summary = emptyList<BootstrapResult>().toSummary(),
        success = true,
        diagnostics = null
    )

    private fun serviceStartSnapshot(): EngineServiceStartRequestSnapshot = EngineServiceStartRequestSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        guestServiceClassName = "com.example.app.SyncService",
        reason = "explicit",
        foreground = false,
        proxyToken = "token-001",
        processSlot = "com.multiapp.app:v1"
    )

    private fun proxyIntent(
        instanceId: String,
        originPackageName: String,
        guestServiceClassName: String,
        reason: String,
        foreground: Boolean = false,
        processSlot: String? = null,
        proxyToken: String? = null
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns instanceId
        every { getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns originPackageName
        every { getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns guestServiceClassName
        every { getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON) } returns reason
        every { getStringExtra(VirtualServiceManager.EXTRA_PROCESS_SLOT) } returns processSlot
        every { getStringExtra(VirtualServiceManager.EXTRA_VIRTUAL_SERVICE_TOKEN) } returns proxyToken
        every { getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) } returns foreground
    }
}
