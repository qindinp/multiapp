package com.multiapp.core.loader

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualAmsComponentDispatcherTest {

    @Test
    fun `resolveStartActivityIntent maps explicit guest activity to host proxy`() {
        val recordManager = VirtualActivityRecordManager()
        val dispatcher = dispatcher(activityRecordManager = recordManager)
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.MainActivity")

        val result = dispatcher.resolveStartActivityIntent(intent)

        val remapped = assertIs<VirtualContextWrapper.StartActivityMappingResult.Remapped>(result)
        val proxyIntent = remapped.proxyIntent
        assertEquals(
            "com.multiapp.app.container.ProxyActivity0",
            proxyIntent.component?.className
        )
        assertEquals(
            "inst-001",
            proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID)
        )
        assertEquals(
            "com.test.minimal",
            proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME)
        )
        assertEquals(
            "com.test.minimal.MainActivity",
            proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME)
        )
        assertNotNull(proxyIntent.getParcelableExtra<Intent>(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT))
        assertEquals("com.test.minimal.MainActivity", recordManager.lastLaunchResult()?.activity?.guestActivityClassName)
    }

    @Test
    fun `resolveStartActivityIntent seeds existing records before allocating proxy slot`() {
        val recordManager = VirtualActivityRecordManager()
        val existingRegistry = ProxyActivityRegistry(
            listOf(
                "com.multiapp.app.container.ProxyActivity0",
                "com.multiapp.app.container.ProxyActivity1"
            )
        )
        val existing = existingRegistry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.ExistingActivity"
        )
        recordManager.register(existing)
        val freshRegistry = ProxyActivityRegistry(
            listOf(
                "com.multiapp.app.container.ProxyActivity0",
                "com.multiapp.app.container.ProxyActivity1"
            )
        )
        val dispatcher = dispatcher(
            activityRecordManager = recordManager,
            proxyActivityRegistry = freshRegistry
        )
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.MainActivity")

        val result = dispatcher.resolveStartActivityIntent(intent)

        val remapped = assertIs<VirtualContextWrapper.StartActivityMappingResult.Remapped>(result)
        val proxyIntent = remapped.proxyIntent
        assertEquals(
            "com.multiapp.app.container.ProxyActivity1",
            proxyIntent.component?.className
        )
    }

    @Test
    fun `resolveStartActivityIntent blocks unsupported external activity`() {
        val dispatcher = dispatcher()
        val intent = explicitIntent("com.android.settings", "com.android.settings.Settings")

        val result = dispatcher.resolveStartActivityIntent(intent)

        val blocked = assertIs<VirtualContextWrapper.StartActivityMappingResult.Blocked>(result)
        assertSame(intent, blocked.sourceIntent)
        assertEquals("unsupportedActivityIntent", blocked.reason)
    }

    @Test
    fun `resolveStartServiceIntent remaps explicit service and records evidence`() {
        val dispatcher = dispatcher()
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.SyncService")

        val result = dispatcher.resolveStartServiceIntent(intent, foreground = false)

        val remapped = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(result)
        assertSame(intent, remapped.sourceIntent)
        assertEquals(false, remapped.foreground)
        assertEquals("inst-001", remapped.startRequest.instanceId)
        assertEquals("com.test.minimal.SyncService", remapped.startRequest.guestServiceClassName)
        assertEquals("explicit", remapped.startRequest.reason)
        assertEquals(
            "com.multiapp.app.container.StubService",
            remapped.proxyIntent.component?.className
        )
    }

    @Test
    fun `resolveStartServiceIntent records fallback for implicit service`() {
        val dispatcher = dispatcher()
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
        }

        val result = dispatcher.resolveStartServiceIntent(intent, foreground = false)

        val blocked = assertIs<VirtualContextWrapper.StartServiceMappingResult.Blocked>(result)
        assertSame(intent, blocked.sourceIntent)
        assertEquals("implicitServiceIntent", blocked.reason)
        assertEquals(false, blocked.foreground)
    }

    @Test
    fun `dispatchStopService returns stop dispatch result`() {
        val records = VirtualServiceRecordManager()
        val service = FakeService()
        records.put(serviceRecord(service))
        val dispatcher = dispatcher(
            serviceRuntime = VirtualServiceRuntime(recordManager = records)
        )
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.SyncService")

        val result = dispatcher.dispatchStopService(intent)

        val stopped = assertIs<VirtualServiceStopDispatchResult.ServiceStopped>(result)
        assertEquals("com.test.minimal.SyncService", stopped.stopRequest.guestServiceClassName)
        assertEquals(1, service.onDestroyCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
    }

    @Test
    fun `dispatchBroadcast delivers explicit receiver`() {
        val receiver = RecordingReceiver()
        val runtime = VirtualReceiverRuntime(
            receiverFactory = ReceiverFactory { _, _ -> receiver }
        )
        val broadcastManager = VirtualBroadcastManager(runtime = runtime)
        val virtualContext = mockk<Context>(relaxed = true)
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.BootReceiver")
        every { intent.action } returns "com.test.ACTION_BOOT"
        val dispatcher = dispatcher(broadcastManager = broadcastManager)

        val result = dispatcher.dispatchBroadcast(
            intent = intent,
            virtualContext = virtualContext,
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val delivered = assertIs<VirtualBroadcastResult.Delivered>(result)
        assertEquals(VirtualBroadcastResultCode.Delivered, delivered.record.result)
        assertSame(virtualContext, receiver.context)
        assertSame(intent, receiver.intent)
    }

    private fun dispatcher(
        activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager(),
        serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime(recordManager = VirtualServiceRecordManager()),
        broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
        proxyActivityRegistry: ProxyActivityRegistry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))
    ): VirtualAmsComponentDispatcher {
        return DefaultVirtualAmsComponentDispatcher(
            hostPackageName = "com.multiapp.app",
            packageSnapshot = snapshot(),
            activityRecordManager = activityRecordManager,
            proxyActivityRegistry = proxyActivityRegistry,
            servicePackageRegistry = VirtualPackageRegistry().apply { register(snapshot()) },
            serviceRuntime = serviceRuntime,
            broadcastManager = broadcastManager,
            serviceProxyIntentFactory = { _, request -> serviceProxyIntent(request) },
            activityProxyIntentFactory = { record, sourceIntent -> activityProxyIntent(record, sourceIntent) }
        )
    }

    private fun activityProxyIntent(
        record: com.multiapp.core.model.virtual.VirtualActivityRecord,
        sourceIntent: Intent
    ): Intent {
        val component = mockk<ComponentName>(relaxed = true)
        every { component.className } returns record.proxyActivityClassName
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns component
        every { intent.getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID) } returns record.instanceId
        every { intent.getStringExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns record.originPackageName
        every { intent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME) } returns record.guestActivityClassName
        every { intent.getParcelableExtra<Intent>(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT) } returns sourceIntent
        return intent
    }

    private fun serviceProxyIntent(request: VirtualServiceStartRequest): Intent {
        val component = mockk<ComponentName>(relaxed = true)
        every { component.className } returns "com.multiapp.app.container.StubService"
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns component
        every { intent.getStringExtra(VirtualServiceManager.EXTRA_INSTANCE_ID) } returns request.instanceId
        every { intent.getStringExtra(VirtualServiceManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns request.originPackageName
        every { intent.getStringExtra(VirtualServiceManager.EXTRA_GUEST_SERVICE_CLASS_NAME) } returns request.guestServiceClassName
        return intent
    }

    private fun explicitIntent(packageName: String, className: String): Intent {
        val component = mockk<ComponentName>(relaxed = true)
        every { component.packageName } returns packageName
        every { component.className } returns className
        return mockk(relaxed = true) {
            every { this@mockk.component } returns component
            every { this@mockk.`package` } returns null
            every { this@mockk.flags } returns 0
            every { this@mockk.dataString } returns null
            every { this@mockk.action } returns null
            every { this@mockk.categories } returns emptySet()
            every { this@mockk.extras } returns null
        }
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
        activities = listOf(
            ResolvedComponent(name = "com.test.minimal.MainActivity", exported = true)
        ),
        services = listOf(
            ResolvedComponent(name = "com.test.minimal.SyncService", exported = false)
        ),
        receivers = listOf(
            ResolvedComponent(name = "com.test.minimal.BootReceiver", exported = false)
        )
    )

    private fun serviceRecord(service: Service) = VirtualServiceRecord(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestServiceClassName = "com.test.minimal.SyncService",
        service = service,
        createdAtMs = 100L
    )

    private class FakeService : Service() {
        var onDestroyCalls = 0

        override fun onDestroy() {
            onDestroyCalls += 1
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }

    private class RecordingReceiver : BroadcastReceiver() {
        var context: Context? = null
        var intent: Intent? = null

        override fun onReceive(context: Context, intent: Intent) {
            this.context = context
            this.intent = intent
        }
    }
}
