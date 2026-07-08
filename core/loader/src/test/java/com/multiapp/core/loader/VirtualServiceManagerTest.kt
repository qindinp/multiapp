package com.multiapp.core.loader

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        assertTrue(spec.token.isNotBlank())
    }

    @Test
    fun `create proxy spec targets process slot StubService when process slot is present`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val request = manager.resolveExplicitService(
            snapshot = snapshot(),
            packageName = "com.test.minimal",
            className = "com.test.minimal.SyncService",
            sourceIntent = mockk(relaxed = true)
        )!!.copy(processSlot = "com.multiapp.app:v3")

        val spec = manager.createProxySpec(request)

        assertEquals("com.multiapp.app.container.StubServiceV3", spec.stubServiceClassName)
        assertEquals("com.multiapp.app:v3", spec.processSlot)
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
    fun `resolve implicit guest Service intent from snapshot filters`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns null
        every { intent.`package` } returns null
        every { intent.action } returns "com.test.SYNC"
        every { intent.categories } returns emptySet()
        every { intent.scheme } returns null

        val request = manager.resolveStartService(
            snapshot = snapshot(),
            intent = intent
        )

        assertNotNull(request)
        assertFalse(request.foreground)
        assertEquals("implicit", request.reason)
        assertEquals("com.test.minimal.SyncService", request.guestServiceClassName)
    }

    @Test
    fun `resolve implicit foreground Service intent marks foreground request`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns null
        every { intent.`package` } returns null
        every { intent.action } returns "com.test.SYNC"
        every { intent.categories } returns emptySet()
        every { intent.scheme } returns null

        val request = manager.resolveStartForegroundService(
            snapshot = snapshot(),
            intent = intent
        )

        assertNotNull(request)
        assertTrue(request.foreground)
        assertEquals("implicitForeground", request.reason)
        assertEquals("com.test.minimal.SyncService", request.guestServiceClassName)
    }

    @Test
    fun `ignore unresolved implicit guest Service intent`() {
        val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns null
        every { intent.`package` } returns null
        every { intent.action } returns "com.test.MISSING"
        every { intent.categories } returns emptySet()
        every { intent.scheme } returns null

        assertNull(manager.resolveStartService(snapshot(), intent))
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
        )!!.copy(processSlot = "com.multiapp.app:v3")
        val proxyIntent = mockk<Intent>(relaxed = true)
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns request.instanceId
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns request.originPackageName
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns request.guestServiceClassName
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON) } returns request.reason
        every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_PROCESS_SLOT) } returns request.processSlot
        every { proxyIntent.getParcelableExtra<Intent>(VirtualServiceManager.EXTRA_ORIGINAL_GUEST_INTENT) } returns sourceIntent
        every { proxyIntent.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) } returns false

        val decoded = manager.requestFromProxyIntent(proxyIntent)

        assertNotNull(decoded)
        assertEquals(request.instanceId, decoded.instanceId)
        assertEquals(request.originPackageName, decoded.originPackageName)
        assertEquals(request.guestServiceClassName, decoded.guestServiceClassName)
        assertEquals(request.reason, decoded.reason)
        assertEquals("com.multiapp.app:v3", decoded.processSlot)
        assertFalse(decoded.foreground)
    }

    @Test
    fun `proxy intent stores original service intent by token instead of parcelable extra`() {
        VirtualServiceIntentStore.clearAll()
        VirtualServiceIntentStore.setIntentCopierForTest { it }
        try {
            val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
            val token = "service-token-001"
            val sourceIntent = mockk<Intent>(relaxed = true)
            every { sourceIntent.action } returns "com.test.SYNC"
            VirtualServiceIntentStore.remember(token, sourceIntent)
            val proxyIntent = mockk<Intent>(relaxed = true)
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_VIRTUAL_SERVICE_TOKEN) } returns token
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns "inst-001"
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns "com.test.minimal"
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns "com.test.minimal.SyncService"
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON) } returns "explicit"
            every { proxyIntent.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) } returns false

            val decoded = manager.requestFromProxyIntent(proxyIntent)

            assertNotNull(decoded)
            assertEquals("com.test.SYNC", decoded.sourceIntent.action)
            assertEquals(1, VirtualServiceIntentStore.size())
            verify(exactly = 0) {
                proxyIntent.getParcelableExtra<Intent>(VirtualServiceManager.EXTRA_ORIGINAL_GUEST_INTENT)
            }
        } finally {
            VirtualServiceIntentStore.clearAll()
            VirtualServiceIntentStore.resetIntentCopierForTest()
        }
    }

    @Test
    fun `proxy intent can decode original service intent more than once`() {
        VirtualServiceIntentStore.clearAll()
        VirtualServiceIntentStore.setIntentCopierForTest { it }
        try {
            val manager = VirtualServiceManager(hostPackageName = "com.multiapp.app")
            val token = "service-token-repeat"
            val sourceIntent = mockk<Intent>(relaxed = true)
            every { sourceIntent.action } returns "com.test.REPEAT"
            VirtualServiceIntentStore.remember(token, sourceIntent)
            val proxyIntent = mockk<Intent>(relaxed = true)
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_VIRTUAL_SERVICE_TOKEN) } returns token
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns "inst-001"
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns "com.test.minimal"
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns "com.test.minimal.SyncService"
            every { proxyIntent.getStringExtra(VirtualServiceManager.EXTRA_SERVICE_START_REASON) } returns "explicit"
            every { proxyIntent.getBooleanExtra(VirtualServiceManager.EXTRA_FOREGROUND_SERVICE, false) } returns false

            val first = manager.requestFromProxyIntent(proxyIntent)
            val second = manager.requestFromProxyIntent(proxyIntent)

            assertNotNull(first)
            assertNotNull(second)
            assertEquals("com.test.REPEAT", first.sourceIntent.action)
            assertEquals("com.test.REPEAT", second.sourceIntent.action)
        } finally {
            VirtualServiceIntentStore.clearAll()
            VirtualServiceIntentStore.resetIntentCopierForTest()
        }
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
            ResolvedComponent(
                name = "com.test.minimal.SyncService",
                exported = false,
                resolvedIntentFilters = listOf(
                    ResolvedIntentFilter(actions = listOf("com.test.SYNC"))
                )
            )
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
