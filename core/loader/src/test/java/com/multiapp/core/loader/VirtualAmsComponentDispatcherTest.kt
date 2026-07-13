package com.multiapp.core.loader

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.ProxyActivitySlotAssignmentStore
import com.multiapp.core.model.virtual.ProxyActivitySlotKey
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualAmsComponentDispatcherTest {

    @AfterTest
    fun tearDown() {
        VirtualActivityIntentStore.clearAll()
        VirtualActivityIntentStore.resetIntentCopierForTest()
    }

    @Test
    fun `resolveStartActivityIntent maps explicit guest activity to host proxy`() {
        VirtualActivityIntentStore.clearAll()
        VirtualActivityIntentStore.setIntentCopierForTest { it }
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
        val activity = recordManager.lastLaunchResult()?.activity
        assertNotNull(VirtualActivityIntentStore.find(activity?.token))
        assertEquals("com.test.minimal.MainActivity", activity?.guestActivityClassName)
        assertEquals("com.test.minimal:inst-001", activity?.taskAffinity)
        assertEquals(
            "com.test.minimal:inst-001",
            proxyIntent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_TASK_AFFINITY)
        )
    }

    @Test
    fun `resolveStartActivityIntents rolls back partial slot allocation through find and CAS`() {
        val recordManager = VirtualActivityRecordManager()
        val store = FailSecondReserveStore()
        val proxyClassName = "com.multiapp.app.container.ProxyActivity0"
        val registry = ProxyActivityRegistry(listOf(proxyClassName), slotAssignmentStore = store)
        val dispatcher = dispatcher(
            activityRecordManager = recordManager,
            proxyActivityRegistry = registry,
            proxyActivitySlotAssignmentStore = store
        )
        val intents = listOf(
            explicitIntent("com.test.minimal", "com.test.minimal.MainActivity"),
            explicitIntent("com.test.minimal", "com.test.minimal.MainActivity")
        )

        assertFailsWith<IllegalStateException> {
            dispatcher.resolveStartActivityIntents(intents)
        }

        val key = ProxyActivitySlotKey("inst-001", null, "com.test.minimal:inst-001")
        assertTrue(recordManager.list().isEmpty())
        assertTrue(registry.listRecords().isEmpty())
        assertNull(store.find(key))
        assertTrue(store.findCalls.count { it == key } >= 2)
        assertEquals(
            listOf(
                Triple<ProxyActivitySlotKey, String?, String?>(key, proxyClassName, null)
            ),
            store.compareAndSetCalls
        )
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
    fun `resolveStartServiceIntent remaps explicit foreground service to host proxy`() {
        val dispatcher = dispatcher()
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.SyncService")

        val result = dispatcher.resolveStartServiceIntent(intent, foreground = true)

        val remapped = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(result)
        assertSame(intent, remapped.sourceIntent)
        assertEquals(true, remapped.foreground)
        assertEquals(true, remapped.startRequest.foreground)
        assertEquals("explicitForeground", remapped.startRequest.reason)
        assertEquals("com.test.minimal.SyncService", remapped.startRequest.guestServiceClassName)
    }

    @Test
    fun `resolveStartServiceIntent uses injected process runtime slot`() {
        val processRuntime = VirtualProcessRuntime()
        processRuntime.bindApplication("inst-001") {
            hostedResult(processSlot = "com.multiapp.app:v3")
        }
        val dispatcher = dispatcher(processRuntime = processRuntime)
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.SyncService")

        val result = dispatcher.resolveStartServiceIntent(intent, foreground = false)

        val remapped = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(result)
        assertEquals("com.multiapp.app:v3", remapped.startRequest.processSlot)
    }

    @Test
    fun `resolveStartServiceIntent remaps implicit service through snapshot resolver`() {
        val dispatcher = dispatcher()
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { `package` } returns null
            every { action } returns "com.test.SYNC"
            every { categories } returns emptySet()
            every { scheme } returns null
        }

        val result = dispatcher.resolveStartServiceIntent(intent, foreground = false)

        val remapped = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(result)
        assertSame(intent, remapped.sourceIntent)
        assertEquals(false, remapped.foreground)
        assertEquals("implicit", remapped.startRequest.reason)
        assertEquals("com.test.minimal.SyncService", remapped.startRequest.guestServiceClassName)
    }

    @Test
    fun `resolveStartServiceIntent blocks unresolved implicit service`() {
        val dispatcher = dispatcher()
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { `package` } returns null
            every { action } returns "com.test.MISSING"
            every { categories } returns emptySet()
            every { scheme } returns null
        }

        val result = dispatcher.resolveStartServiceIntent(intent, foreground = false)

        val blocked = assertIs<VirtualContextWrapper.StartServiceMappingResult.Blocked>(result)
        assertSame(intent, blocked.sourceIntent)
        assertEquals("unsupportedServiceIntent", blocked.reason)
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
    fun `dispatchBindService reuses existing connection without rebinding runtime`() {
        val binder = mockk<IBinder>(relaxed = true)
        val service = FakeService(binder = binder)
        val records = VirtualServiceRecordManager()
        val dispatcher = dispatcher(
            serviceRuntime = VirtualServiceRuntime(
                serviceFactory = ServiceFactory { _, _ -> service },
                serviceAttacher = ServiceAttacher { _, _, _, _ -> },
                recordManager = records
            )
        )
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        val first = dispatcher.dispatchBindService(
            intent = intent,
            virtualContext = context,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            connection = connection,
            flags = Context.BIND_AUTO_CREATE,
            executor = null
        )
        val second = dispatcher.dispatchBindService(
            intent = intent,
            virtualContext = context,
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            connection = connection,
            flags = Context.BIND_AUTO_CREATE,
            executor = null
        )
        dispatcher.dispatchUnbindService(connection)

        val firstBound = assertIs<VirtualServiceBindDispatchResult.Bound>(first)
        val secondBound = assertIs<VirtualServiceBindDispatchResult.Bound>(second)
        assertFalse(firstBound.connectionReused)
        assertTrue(secondBound.connectionReused)
        assertEquals(1, service.onBindCalls)
        assertEquals(1, service.onUnbindCalls)
        verify(exactly = 2) {
            connection.onServiceConnected(any<ComponentName>(), binder)
        }
    }

    @Test
    fun `dispatchBindService reports null binding without service connected callback`() {
        val service = FakeService(binder = null)
        val dispatcher = dispatcher(
            serviceRuntime = VirtualServiceRuntime(
                serviceFactory = ServiceFactory { _, _ -> service },
                serviceAttacher = ServiceAttacher { _, _, _, _ -> },
                recordManager = VirtualServiceRecordManager()
            )
        )
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)

        val result = dispatcher.dispatchBindService(
            intent = intent,
            virtualContext = mockk(relaxed = true),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            connection = connection,
            flags = Context.BIND_AUTO_CREATE,
            executor = null
        )

        val bound = assertIs<VirtualServiceBindDispatchResult.Bound>(result)
        assertTrue(bound.nullBinding)
        assertNull(bound.binder)
        verify(exactly = 1) { connection.onNullBinding(any<ComponentName>()) }
        verify(exactly = 0) { connection.onServiceConnected(any<ComponentName>(), any()) }
    }

    @Test
    fun `dispatchBindService blocks bind-only start without auto create`() {
        val service = FakeService()
        val records = VirtualServiceRecordManager()
        val dispatcher = dispatcher(
            serviceRuntime = VirtualServiceRuntime(
                serviceFactory = ServiceFactory { _, _ -> service },
                serviceAttacher = ServiceAttacher { _, _, _, _ -> },
                recordManager = records
            )
        )
        val intent = explicitIntent("com.test.minimal", "com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)

        val result = dispatcher.dispatchBindService(
            intent = intent,
            virtualContext = mockk(relaxed = true),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            connection = connection,
            flags = 0,
            executor = null
        )

        val blocked = assertIs<VirtualServiceBindDispatchResult.Blocked>(result)
        assertEquals("bindAutoCreateNotRequested", blocked.reason)
        assertTrue(blocked.serviceResolved)
        assertEquals(0, blocked.flags)
        assertEquals(false, blocked.autoCreate)
        assertEquals(false, blocked.serviceAlreadyRunning)
        assertEquals(0, service.onCreateCalls)
        assertEquals(0, service.onBindCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
        verify(exactly = 0) { connection.onServiceConnected(any<ComponentName>(), any()) }
        verify(exactly = 0) { connection.onNullBinding(any<ComponentName>()) }
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
        processRuntime: VirtualProcessRuntime = VirtualProcessRuntime(),
        broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
        proxyActivityRegistry: ProxyActivityRegistry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0")),
        proxyActivitySlotAssignmentStore: ProxyActivitySlotAssignmentStore? = null
    ): VirtualAmsComponentDispatcher {
        return DefaultVirtualAmsComponentDispatcher(
            hostPackageName = "com.multiapp.app",
            packageSnapshot = snapshot(),
            activityRecordManager = activityRecordManager,
            proxyActivityRegistry = proxyActivityRegistry,
            proxyActivitySlotAssignmentStore = proxyActivitySlotAssignmentStore,
            servicePackageRegistry = VirtualPackageRegistry().apply { register(snapshot()) },
            serviceRuntime = serviceRuntime,
            processRuntime = processRuntime,
            broadcastManager = broadcastManager,
            serviceProxyIntentFactory = { _, request -> serviceProxyIntent(request) },
            activityProxyIntentFactory = { record, sourceIntent -> activityProxyIntent(record, sourceIntent) }
        )
    }

    private fun activityProxyIntent(
        record: com.multiapp.core.model.virtual.VirtualActivityRecord,
        sourceIntent: Intent
    ): Intent {
        VirtualActivityIntentStore.remember(record.token, sourceIntent)
        val component = mockk<ComponentName>(relaxed = true)
        every { component.className } returns record.proxyActivityClassName
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns component
        every { intent.getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID) } returns record.instanceId
        every { intent.getStringExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns record.originPackageName
        every { intent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME) } returns record.guestActivityClassName
        every { intent.getStringExtra(VirtualActivityManager.EXTRA_GUEST_TASK_AFFINITY) } returns record.taskAffinity
        every { intent.getParcelableExtra<Intent>(VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT) } returns null
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
            ResolvedComponent(
                name = "com.test.minimal.SyncService",
                exported = false,
                intentFilters = listOf("com.test.SYNC")
            )
        ),
        receivers = listOf(
            ResolvedComponent(name = "com.test.minimal.BootReceiver", exported = false)
        )
    )

    private class FailSecondReserveStore : TestProxyActivitySlotAssignmentStore() {
        val findCalls = mutableListOf<ProxyActivitySlotKey>()
        val compareAndSetCalls = mutableListOf<Triple<ProxyActivitySlotKey, String?, String?>>()
        private var reserveCalls = 0

        override fun find(key: ProxyActivitySlotKey): String? {
            findCalls += key
            return super.find(key)
        }

        override fun reserve(
            key: ProxyActivitySlotKey,
            candidateProxyActivityClassNames: List<String>
        ): String? {
            reserveCalls += 1
            if (reserveCalls == 2) error("forced second reserve failure")
            return super.reserve(key, candidateProxyActivityClassNames)
        }

        override fun compareAndSet(
            key: ProxyActivitySlotKey,
            expectedProxyActivityClassName: String?,
            newProxyActivityClassName: String?
        ): Boolean {
            compareAndSetCalls += Triple(
                key,
                expectedProxyActivityClassName,
                newProxyActivityClassName
            )
            return super.compareAndSet(
                key,
                expectedProxyActivityClassName,
                newProxyActivityClassName
            )
        }
    }

    private fun hostedResult(processSlot: String?): HostedBootstrapResult =
        HostedBootstrapResult(
            instanceId = "inst-001",
            installId = "com.test.minimal",
            originPackageName = "com.test.minimal",
            virtualPackageName = "com.multiapp.instance.abc",
            processSlot = processSlot,
            originApkPath = "/data/minimal.apk",
            dataRoot = "/data/inst",
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            guestApplication = mockk<Application>(relaxed = true),
            installRecord = null,
            packageSnapshot = snapshot(),
            launcherActivityClassName = null,
            stageResults = emptyList(),
            summary = emptyList<BootstrapResult>().toSummary(),
            success = true,
            diagnostics = null
        )

    private fun serviceRecord(service: Service) = VirtualServiceRecord(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestServiceClassName = "com.test.minimal.SyncService",
        service = service,
        createdAtMs = 100L
    )

    private class FakeService(
        private val binder: IBinder? = null
    ) : Service() {
        var onCreateCalls = 0
        var onBindCalls = 0
        var onUnbindCalls = 0
        var onDestroyCalls = 0

        override fun onCreate() {
            onCreateCalls += 1
        }

        override fun onDestroy() {
            onDestroyCalls += 1
        }

        override fun onBind(intent: Intent?): IBinder? {
            onBindCalls += 1
            return binder
        }

        override fun onUnbind(intent: Intent?): Boolean {
            onUnbindCalls += 1
            return false
        }
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
