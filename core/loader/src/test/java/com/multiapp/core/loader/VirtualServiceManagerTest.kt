package com.multiapp.core.loader

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualServiceManagerTest {
    @Test
    fun `resolve explicit guest Service intent`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")

        val request = manager.resolveExplicitService(
            snapshot = snapshot(),
            packageName = "com.test.minimal",
            className = "com.test.minimal.SyncService",
            sourceIntent = mockk(relaxed = true)
        )

        assertNotNull(request)
        assertEquals("inst-001", request.instanceId)
        assertEquals("com.test.minimal", request.originPackageName)
        assertEquals("com.test.minimal.SyncService", request.guestServiceClassName)
        assertEquals("explicit", request.reason)
    }

    @Test
    fun `ignore Service intent outside virtual package`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")

        assertNull(
            manager.resolveExplicitService(
                snapshot = snapshot(),
                packageName = "com.android.settings",
                className = "com.android.settings.SettingsService",
                sourceIntent = mockk(relaxed = true)
            )
        )
    }

    @Test
    fun `create proxy spec targets host StubService`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val request = manager.resolveExplicitService(
            snapshot = snapshot(),
            packageName = "com.test.minimal",
            className = "com.test.minimal.SyncService",
            sourceIntent = mockk(relaxed = true)
        )!!

        val spec = manager.createProxySpec(request)

        assertEquals("com.multiapp.app", spec.hostPackageName)
        assertEquals("com.multiapp.app.container.StubService", spec.stubServiceClassName)
        assertEquals("inst-001", spec.instanceId)
        assertEquals("com.test.minimal", spec.originPackageName)
        assertEquals("com.test.minimal.SyncService", spec.guestServiceClassName)
        assertEquals("explicit", spec.reason)
    }

    @Test
    fun `resolve explicit foreground Service intent marks foreground request`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val intent = mockk<Intent>(relaxed = true)
        val component = mockk<android.content.ComponentName>(relaxed = true)
        every { component.packageName } returns "com.test.minimal"
        every { component.className } returns "com.test.minimal.SyncService"
        every { intent.component } returns component

        val request = manager.resolveStartForegroundService(
            snapshot = snapshot(),
            intent = intent
        )

        assertNotNull(request)
        assertTrue(request.foreground)
        assertEquals("explicitForeground", request.reason)
    }

    @Test
    fun `resolve explicit guest Service stop intent`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")

        val request = manager.resolveExplicitStopService(
            snapshot = snapshot(),
            packageName = "com.test.minimal",
            className = "com.test.minimal.SyncService",
            sourceIntent = mockk(relaxed = true)
        )

        assertNotNull(request)
        assertEquals("inst-001", request.instanceId)
        assertEquals("com.test.minimal", request.originPackageName)
        assertEquals("com.test.minimal.SyncService", request.guestServiceClassName)
        assertEquals("explicitStop", request.reason)
    }

    @Test
    fun `proxy intent round trips service start request`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val sourceIntent = mockk<Intent>(relaxed = true)
        val request = manager.resolveExplicitService(
            snapshot = snapshot(),
            packageName = "com.test.minimal",
            className = "com.test.minimal.SyncService",
            sourceIntent = sourceIntent
        )!!
        val proxyIntent = mockk<Intent>(relaxed = true)
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns request.instanceId
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns request.originPackageName
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns request.guestServiceClassName
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON) } returns request.reason
        every { proxyIntent.getParcelableExtra<Intent>(VirtualServiceManager.EXTRA_ORIGINAL_GUEST_INTENT) } returns sourceIntent
        every { proxyIntent.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) } returns false

        val decoded = manager.requestFromProxyIntent(proxyIntent)

        assertNotNull(decoded)
        assertEquals(request.instanceId, decoded.instanceId)
        assertEquals(request.originPackageName, decoded.originPackageName)
        assertEquals(request.guestServiceClassName, decoded.guestServiceClassName)
        assertEquals(request.reason, decoded.reason)
        assertFalse(decoded.foreground)
    }

    @Test
    fun `dispatcher returns runtime not bound instead of silent success`() {
        val registry = VirtualPackageRegistry()
        registry.register(snapshot())
        val dispatcher = VirtualServiceDispatcher(
            hostContext = null,
            packageRegistry = registry,
            processRuntime = VirtualProcessRuntime(),
            serviceManager = VirtualServiceManager(hostPackageName = "com.multiapp.app"),
            serviceRuntime = VirtualServiceRuntime(
                serviceFactory = ServiceFactory { _, _ -> error("should not create") },
                serviceAttacher = ServiceAttacher { _, _, _, _ -> }
            )
        )
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val sourceIntent = mockk<Intent>(relaxed = true)
        val request = manager.resolveExplicitService(
            snapshot = snapshot(),
            packageName = "com.test.minimal",
            className = "com.test.minimal.SyncService",
            sourceIntent = sourceIntent
        )!!
        val proxyIntent = mockk<Intent>(relaxed = true)
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns request.instanceId
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns request.originPackageName
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns request.guestServiceClassName
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON) } returns request.reason
        every { proxyIntent.getParcelableExtra<Intent>(VirtualServiceManager.EXTRA_ORIGINAL_GUEST_INTENT) } returns sourceIntent
        every { proxyIntent.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) } returns false

        val result = dispatcher.dispatch(proxyIntent, flags = 0, startId = 7)

        val notBound = assertIs<VirtualServiceDispatchResult.RuntimeNotBound>(result)
        assertEquals("inst-001", notBound.startRequest.instanceId)
    }

    @Test
    fun `dispatcher stop removes existing service record`() {
        val registry = VirtualPackageRegistry()
        registry.register(snapshot())
        val records = VirtualServiceRecordManager()
        val service = FakeService()
        records.put(
            VirtualServiceRecord(
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                guestServiceClassName = "com.test.minimal.SyncService",
                service = service,
                createdAtMs = 100L
            )
        )
        val dispatcher = VirtualServiceDispatcher(
            hostContext = null,
            packageRegistry = registry,
            serviceRuntime = VirtualServiceRuntime(recordManager = records)
        )

        val result = dispatcher.dispatchStop(stopRequest())

        val stopped = assertIs<VirtualServiceStopDispatchResult.ServiceStopped>(result)
        assertEquals(VirtualServiceLifecycleEvidence.Event.STOPPED, stopped.lifecycleEvidence.event)
        assertTrue(stopped.lifecycleEvidence.success)
        assertEquals(1, service.onDestroyCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
    }

    @Test
    fun `dispatcher stop returns not found when service record is missing`() {
        val registry = VirtualPackageRegistry()
        registry.register(snapshot())
        val dispatcher = VirtualServiceDispatcher(
            hostContext = null,
            packageRegistry = registry,
            serviceRuntime = VirtualServiceRuntime(recordManager = VirtualServiceRecordManager())
        )

        val result = dispatcher.dispatchStop(stopRequest())

        val notFound = assertIs<VirtualServiceStopDispatchResult.ServiceNotFound>(result)
        assertEquals(VirtualServiceLifecycleEvidence.Event.STOP_NOT_FOUND, notFound.lifecycleEvidence.event)
        assertFalse(notFound.lifecycleEvidence.success)
    }

    private fun snapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/minimal.apk",
        dataDir = "/data/inst",
        services = listOf(
            ResolvedComponent(name = "com.test.minimal.SyncService", exported = false)
        )
    )

    private fun stopRequest(): VirtualServiceStopRequest = VirtualServiceStopRequest(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestServiceClassName = "com.test.minimal.SyncService",
        sourceIntent = mockk(relaxed = true),
        reason = "explicitStop"
    )

    private class FakeService : Service() {
        var onDestroyCalls = 0

        override fun onDestroy() {
            onDestroyCalls += 1
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }
}
