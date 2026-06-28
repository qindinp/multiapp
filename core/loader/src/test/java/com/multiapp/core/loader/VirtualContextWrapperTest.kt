package com.multiapp.core.loader

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualContextWrapperTest {

    @Test
    fun `VirtualContextConfig carries instance identity`() {
        val config = VirtualContextConfig(
            instanceId = "inst-001",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.abc",
            dataDir = "/data/user/0/com.multiapp/app_instance/inst-001",
            sourceDir = "/data/user/0/com.multiapp/app_instance/inst-001/base.apk",
            nativeLibraryDir = "/data/user/0/com.multiapp/app_instance/inst-001/lib",
            classLoader = ClassLoader.getSystemClassLoader(),
            applicationLabel = "Example App"
        )

        assertEquals("inst-001", config.instanceId)
        assertEquals("com.example.app", config.originPackageName)
        assertEquals("com.multiapp.instance.abc", config.virtualPackageName)
        assertEquals("/data/user/0/com.multiapp/app_instance/inst-001", config.dataDir)
        assertEquals("/data/user/0/com.multiapp/app_instance/inst-001/base.apk", config.sourceDir)
        assertEquals("/data/user/0/com.multiapp/app_instance/inst-001/lib", config.nativeLibraryDir)
        assertEquals("Example App", config.applicationLabel)
    }

    @Test
    fun `VirtualContextConfig nativeLibraryDir can be null`() {
        val config = VirtualContextConfig(
            instanceId = "inst-002",
            originPackageName = "com.example.app",
            virtualPackageName = "com.multiapp.instance.def",
            dataDir = "/tmp/test",
            sourceDir = "/tmp/test/base.apk",
            nativeLibraryDir = null,
            classLoader = ClassLoader.getSystemClassLoader()
        )

        assertNull(config.nativeLibraryDir)
    }

    @Test
    fun `VirtualContextConfig equality by data class`() {
        val a = VirtualContextConfig("i1", "o1", "v1", "/d1", "/s1", null, ClassLoader.getSystemClassLoader())
        val b = VirtualContextConfig("i1", "o1", "v1", "/d1", "/s1", null, ClassLoader.getSystemClassLoader())
        assertEquals(a, b)
    }

    @Test
    fun `startService remaps explicit guest service and records evidence without changing return value`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val startedIntent = slot<Intent>()
        val proxyIntent = mockk<Intent>(relaxed = true)
        val returnedComponent = mockk<ComponentName>(relaxed = true)
        every { base.startService(capture(startedIntent)) } returns returnedComponent
        val wrapper = wrapper(
            base = base,
            serviceProxyIntentFactory = { _, _ -> proxyIntent }
        )

        val result = wrapper.startService(sourceIntent)

        assertSame(returnedComponent, result)
        val evidence = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(
            wrapper.lastStartServiceMappingResult()
        )
        assertSame(sourceIntent, evidence.sourceIntent)
        assertSame(proxyIntent, evidence.proxyIntent)
        assertSame(evidence.proxyIntent, startedIntent.captured)
        assertFalse(evidence.foreground)
        assertFalse(evidence.startRequest.foreground)
        assertEquals("explicit", evidence.startRequest.reason)
        assertEquals("com.test.minimal.SyncService", evidence.startRequest.guestServiceClassName)
    }

    @Test
    fun `startForegroundService remaps explicit guest service with foreground evidence`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val startedIntent = slot<Intent>()
        val proxyIntent = mockk<Intent>(relaxed = true)
        every { base.startForegroundService(capture(startedIntent)) } returns mockk(relaxed = true)
        val wrapper = wrapper(
            base = base,
            serviceProxyIntentFactory = { _, _ -> proxyIntent }
        )

        wrapper.startForegroundService(sourceIntent)

        val evidence = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(
            wrapper.lastStartServiceMappingResult()
        )
        assertSame(sourceIntent, evidence.sourceIntent)
        assertSame(proxyIntent, evidence.proxyIntent)
        assertSame(evidence.proxyIntent, startedIntent.captured)
        assertTrue(evidence.foreground)
        assertTrue(evidence.startRequest.foreground)
        assertEquals("explicitForeground", evidence.startRequest.reason)
    }

    @Test
    fun `startService records fallback evidence for implicit unsupported and missing snapshot`() {
        val base = baseContext()
        val startedIntent = slot<Intent>()
        every { base.startService(capture(startedIntent)) } returns mockk(relaxed = true)
        val wrapper = wrapper(base = base)

        val implicit = implicitIntent()
        wrapper.startService(implicit)
        assertFallbackEvidence(
            wrapper.lastStartServiceMappingResult(),
            sourceIntent = implicit,
            reason = "implicitServiceIntent",
            foreground = false
        )
        assertSame(implicit, startedIntent.captured)

        val unsupported = explicitServiceIntent("com.test.minimal.MissingService")
        wrapper.startService(unsupported)
        assertFallbackEvidence(
            wrapper.lastStartServiceMappingResult(),
            sourceIntent = unsupported,
            reason = "unsupportedServiceIntent",
            foreground = false
        )
        assertSame(unsupported, startedIntent.captured)

        val noSnapshotWrapper = wrapper(base = base, snapshot = null)
        val noSnapshotIntent = explicitServiceIntent("com.test.minimal.SyncService")
        noSnapshotWrapper.startService(noSnapshotIntent)
        assertFallbackEvidence(
            noSnapshotWrapper.lastStartServiceMappingResult(),
            sourceIntent = noSnapshotIntent,
            reason = "missingPackageSnapshot",
            foreground = false
        )
        assertSame(noSnapshotIntent, startedIntent.captured)
    }

    @Test
    fun `start and stop evidence do not overwrite each other`() {
        val base = baseContext()
        val proxyIntent = mockk<Intent>(relaxed = true)
        every { base.startService(any()) } returns mockk(relaxed = true)
        val registry = VirtualPackageRegistry().apply { register(snapshot()) }
        val records = VirtualServiceRecordManager()
        val service = FakeService(failOnDestroy = true)
        records.put(serviceRecord(service))
        val wrapper = wrapper(
            base = base,
            registry = registry,
            serviceRuntime = VirtualServiceRuntime(recordManager = records),
            serviceProxyIntentFactory = { _, _ -> proxyIntent }
        )

        wrapper.startService(explicitServiceIntent("com.test.minimal.SyncService"))
        val startEvidence = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(
            wrapper.lastStartServiceMappingResult()
        )

        assertFalse(wrapper.stopService(explicitServiceIntent("com.test.minimal.SyncService")))

        assertSame(startEvidence, wrapper.lastStartServiceMappingResult())
        val stopFailure = assertIs<VirtualServiceStopDispatchResult.ServiceOnDestroyFailed>(
            wrapper.lastStopServiceDispatchResult()
        )

        wrapper.startService(explicitServiceIntent("com.test.minimal.MissingService"))

        assertIs<VirtualContextWrapper.StartServiceMappingResult.Fallback>(wrapper.lastStartServiceMappingResult())
        assertSame(stopFailure, wrapper.lastStopServiceDispatchResult())
    }

    @Test
    fun `stopService stops explicit guest service and returns true`() {
        val registry = VirtualPackageRegistry().apply { register(snapshot()) }
        val records = VirtualServiceRecordManager()
        val service = FakeService()
        records.put(serviceRecord(service))
        val wrapper = wrapper(registry = registry, serviceRuntime = VirtualServiceRuntime(recordManager = records))

        val result = wrapper.stopService(explicitServiceIntent("com.test.minimal.SyncService"))

        assertTrue(result)
        assertEquals(1, service.onDestroyCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
        assertIs<VirtualServiceStopDispatchResult.ServiceStopped>(wrapper.lastStopServiceDispatchResult())
    }

    @Test
    fun `stopService returns false when explicit guest service record is missing`() {
        val registry = VirtualPackageRegistry().apply { register(snapshot()) }
        val records = VirtualServiceRecordManager()
        val wrapper = wrapper(registry = registry, serviceRuntime = VirtualServiceRuntime(recordManager = records))

        val result = wrapper.stopService(explicitServiceIntent("com.test.minimal.SyncService"))

        assertFalse(result)
        assertIs<VirtualServiceStopDispatchResult.ServiceNotFound>(wrapper.lastStopServiceDispatchResult())
    }

    @Test
    fun `stopService returns false for implicit or unsupported service intents`() {
        val registry = VirtualPackageRegistry().apply { register(snapshot()) }
        val wrapper = wrapper(registry = registry, serviceRuntime = VirtualServiceRuntime(recordManager = VirtualServiceRecordManager()))

        assertFalse(wrapper.stopService(implicitIntent()))
        assertNull(wrapper.lastStopServiceDispatchResult())

        assertFalse(wrapper.stopService(explicitServiceIntent("com.test.minimal.MissingService")))
        assertNull(wrapper.lastStopServiceDispatchResult())

        assertFalse(wrapper.stopService(explicitServiceIntent("com.other.Service", packageName = "com.other")))
        assertNull(wrapper.lastStopServiceDispatchResult())
    }

    @Test
    fun `stopService returns false and keeps failure evidence when onDestroy fails`() {
        val registry = VirtualPackageRegistry().apply { register(snapshot()) }
        val records = VirtualServiceRecordManager()
        val service = FakeService(failOnDestroy = true)
        records.put(serviceRecord(service))
        val wrapper = wrapper(registry = registry, serviceRuntime = VirtualServiceRuntime(recordManager = records))

        val result = wrapper.stopService(explicitServiceIntent("com.test.minimal.SyncService"))

        assertFalse(result)
        val failed = assertIs<VirtualServiceStopDispatchResult.ServiceOnDestroyFailed>(wrapper.lastStopServiceDispatchResult())
        assertEquals("destroy failed", failed.error.message)
        assertSame(service, records.get("inst-001", "com.test.minimal.SyncService")?.service)
    }

    @Test
    fun `sendBroadcast delivers explicit guest receiver without calling base`() {
        val base = baseContext()
        val receiver = RecordingReceiver()
        val runtime = VirtualReceiverRuntime(
            receiverFactory = ReceiverFactory { _, _ -> receiver }
        )
        val wrapper = wrapper(
            base = base,
            broadcastManager = VirtualBroadcastManager(runtime = runtime)
        )
        val intent = explicitReceiverIntent("com.test.minimal.BootReceiver")

        wrapper.sendBroadcast(intent)

        val delivered = assertIs<VirtualBroadcastResult.Delivered>(wrapper.lastBroadcastDispatchResult())
        assertEquals(VirtualBroadcastResultCode.Delivered, delivered.record.result)
        assertSame(wrapper, receiver.context)
        assertSame(intent, receiver.intent)
        verify(exactly = 0) { base.sendBroadcast(any()) }
    }

    @Test
    fun `sendBroadcast falls back for implicit broadcast and keeps unsupported evidence`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)
        val intent = implicitIntent()
        every { intent.action } returns "com.test.ACTION_IMPLICIT"

        wrapper.sendBroadcast(intent)

        val unsupported = assertIs<VirtualBroadcastResult.UnsupportedImplicit>(wrapper.lastBroadcastDispatchResult())
        assertEquals(VirtualBroadcastResultCode.UnsupportedImplicit, unsupported.record.result)
        assertEquals("com.test.ACTION_IMPLICIT", unsupported.record.action)
        verify(exactly = 1) { base.sendBroadcast(intent) }
    }

    @Test
    fun `sendBroadcast falls back without package snapshot and keeps clear evidence`() {
        val base = baseContext()
        val wrapper = wrapper(base = base, snapshot = null)
        val intent = explicitReceiverIntent("com.test.minimal.BootReceiver")

        wrapper.sendBroadcast(intent)

        val noSnapshot = assertIs<VirtualBroadcastResult.NoPackageSnapshot>(wrapper.lastBroadcastDispatchResult())
        assertEquals(VirtualBroadcastResultCode.NoPackageSnapshot, noSnapshot.record.result)
        assertEquals("com.test.minimal.BootReceiver", noSnapshot.record.receiverClassName)
        verify(exactly = 1) { base.sendBroadcast(intent) }
    }

    @Test
    fun `registered dynamic receiver handles matching broadcast without base fallback`() {
        val base = baseContext()
        val registry = VirtualDynamicReceiverRegistry()
        val wrapper = wrapper(
            base = base,
            dynamicReceiverRegistry = registry,
            broadcastManager = VirtualBroadcastManager(dynamicReceiverRegistry = registry)
        )
        val receiver = RecordingReceiver()
        val filter = mockk<IntentFilter>(relaxed = true) {
            every { countActions() } returns 1
            every { getAction(0) } returns "com.test.ACTION_DYNAMIC"
            every { countCategories() } returns 0
            every { countDataSchemes() } returns 0
        }
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { action } returns "com.test.ACTION_DYNAMIC"
            every { categories } returns emptySet()
            every { data } returns null
        }

        wrapper.registerReceiver(receiver, filter)
        wrapper.sendBroadcast(intent)

        val registration = assertIs<VirtualContextWrapper.BroadcastReceiverRegistrationResult.Registered>(
            wrapper.lastBroadcastReceiverRegistrationResult()
        )
        assertSame(receiver, registration.receiver)
        assertEquals("inst-001", registration.instanceId)
        assertEquals(setOf("com.test.ACTION_DYNAMIC"), registration.filter.actions)
        val delivered = assertIs<VirtualBroadcastResult.Delivered>(wrapper.lastBroadcastDispatchResult())
        assertEquals("dynamic", delivered.request.reason)
        assertSame(wrapper, receiver.context)
        assertSame(intent, receiver.intent)
        verify(exactly = 0) { base.sendBroadcast(any()) }
    }

    @Test
    fun `unregistered dynamic receiver no longer handles broadcast`() {
        val base = baseContext()
        val registry = VirtualDynamicReceiverRegistry()
        val wrapper = wrapper(
            base = base,
            dynamicReceiverRegistry = registry,
            broadcastManager = VirtualBroadcastManager(dynamicReceiverRegistry = registry)
        )
        val receiver = RecordingReceiver()
        val filter = mockk<IntentFilter>(relaxed = true) {
            every { countActions() } returns 1
            every { getAction(0) } returns "com.test.ACTION_DYNAMIC"
            every { countCategories() } returns 0
            every { countDataSchemes() } returns 0
        }
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { action } returns "com.test.ACTION_DYNAMIC"
            every { categories } returns emptySet()
            every { data } returns null
        }

        wrapper.registerReceiver(receiver, filter)
        wrapper.unregisterReceiver(receiver)
        wrapper.sendBroadcast(intent)

        assertIs<VirtualContextWrapper.BroadcastReceiverRegistrationResult.Unregistered>(
            wrapper.lastBroadcastReceiverRegistrationResult()
        )
        assertIs<VirtualBroadcastResult.UnsupportedImplicit>(wrapper.lastBroadcastDispatchResult())
        verify(exactly = 1) { base.sendBroadcast(intent) }
    }

    @Test
    fun `registerReceiver with permission falls back and records unsupported evidence`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)
        val receiver = RecordingReceiver()
        val filter = mockk<IntentFilter>(relaxed = true)

        wrapper.registerReceiver(receiver, filter, "com.test.PERMISSION", null)

        val fallback = assertIs<VirtualContextWrapper.BroadcastReceiverRegistrationResult.Fallback>(
            wrapper.lastBroadcastReceiverRegistrationResult()
        )
        assertSame(receiver, fallback.receiver)
        assertEquals("permissionOrSchedulerUnsupported", fallback.reason)
        verify(exactly = 1) { base.registerReceiver(receiver, filter, "com.test.PERMISSION", null) }
    }

    private fun wrapper(
        base: Context = baseContext(),
        snapshot: VirtualPackageSnapshot? = snapshot(),
        registry: VirtualPackageRegistry = VirtualPackageRegistry().apply { snapshot?.let { register(it) } },
        serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime(recordManager = VirtualServiceRecordManager()),
        broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
        dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry(),
        serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { manager, request ->
            manager.createProxyIntent(request)
        }
    ): VirtualContextWrapper {
        return VirtualContextWrapper(
            base = base,
            config = config(snapshot),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            servicePackageRegistry = registry,
            serviceRuntime = serviceRuntime,
            broadcastManager = broadcastManager,
            dynamicReceiverRegistry = dynamicReceiverRegistry,
            serviceProxyIntentFactory = serviceProxyIntentFactory
        )
    }

    private fun baseContext(): Context {
        val base = mockk<Context>(relaxed = true)
        every { base.packageName } returns "com.multiapp.app"
        return base
    }

    private fun explicitServiceIntent(
        className: String,
        packageName: String = "com.test.minimal"
    ): Intent {
        val component = mockk<ComponentName>(relaxed = true)
        every { component.packageName } returns packageName
        every { component.className } returns className
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns component
        return intent
    }

    private fun implicitIntent(): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns null
        return intent
    }

    private fun explicitReceiverIntent(
        className: String,
        packageName: String = "com.test.minimal"
    ): Intent {
        val component = mockk<ComponentName>(relaxed = true)
        every { component.packageName } returns packageName
        every { component.className } returns className
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns component
        every { intent.action } returns "com.test.ACTION_BOOT"
        return intent
    }

    private fun config(snapshot: VirtualPackageSnapshot?) = VirtualContextConfig(
        instanceId = snapshot?.instanceId ?: "inst-001",
        originPackageName = snapshot?.originPackageName ?: "com.test.minimal",
        virtualPackageName = snapshot?.virtualPackageName ?: "com.multiapp.instance.abc",
        dataDir = snapshot?.dataDir ?: "/data/inst",
        sourceDir = snapshot?.sourceDir ?: "/data/minimal.apk",
        nativeLibraryDir = snapshot?.nativeLibraryDir,
        classLoader = ClassLoader.getSystemClassLoader(),
        applicationLabel = snapshot?.applicationLabel,
        packageSnapshot = snapshot
    )

    private fun assertFallbackEvidence(
        actual: VirtualContextWrapper.StartServiceMappingResult?,
        sourceIntent: Intent,
        reason: String,
        foreground: Boolean
    ) {
        val fallback = assertIs<VirtualContextWrapper.StartServiceMappingResult.Fallback>(actual)
        assertSame(sourceIntent, fallback.sourceIntent)
        assertEquals(reason, fallback.reason)
        assertEquals(foreground, fallback.foreground)
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

    private class FakeService(
        private val failOnDestroy: Boolean = false
    ) : Service() {
        var onDestroyCalls = 0

        override fun onDestroy() {
            onDestroyCalls += 1
            if (failOnDestroy) error("destroy failed")
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
