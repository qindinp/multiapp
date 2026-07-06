package com.multiapp.core.loader

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.UserHandle
import android.view.LayoutInflater
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executor
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
    fun `context escape hatches return virtual context`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)

        assertSame(wrapper, wrapper.applicationContext)
        assertSame(wrapper, wrapper.baseContext)
        assertSame(wrapper, wrapper.createContextForSplit("feature"))
        assertSame(wrapper, wrapper.createPackageContext("com.any", 0))
        assertSame(wrapper, wrapper.createConfigurationContext(mockk(relaxed = true)))
        assertSame(wrapper, wrapper.createDisplayContext(mockk(relaxed = true)))
        assertSame(wrapper, wrapper.createDeviceProtectedStorageContext())
        assertSame(wrapper, wrapper.createAttributionContext("guest"))
        assertSame(wrapper, wrapper.createWindowContext(mockk(relaxed = true), 1, null))
        assertSame(wrapper, wrapper.createWindowContext(1, null))
        assertSame(wrapper, wrapper.createDeviceContext(0))
    }

    @Test
    fun `api34 context escape hatch returns virtual context`() {
        val wrapper = api34Wrapper()

        assertSame(wrapper, wrapper.createContext(mockk<ContextParams>(relaxed = true)))
    }

    @Test
    fun `component calls through application context stay virtual`() {
        val base = baseContext()
        val sourceActivity = mockk<Intent>(relaxed = true)
        val proxyActivity = mockk<Intent>(relaxed = true)
        val sourceBroadcast = explicitReceiverIntent("com.test.minimal.BootReceiver")
        val delivered = deliveredBroadcastResult(sourceBroadcast)
        val dispatcher = FakeAmsDispatcher(
            startActivityIntent = proxyActivity,
            broadcastResult = delivered
        )
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)
        val applicationContext = wrapper.applicationContext

        applicationContext.startActivity(sourceActivity)
        applicationContext.sendBroadcast(sourceBroadcast)

        assertSame(sourceActivity, dispatcher.lastStartActivityIntent)
        assertSame(sourceBroadcast, dispatcher.lastBroadcastIntent)
        assertSame(delivered, wrapper.lastBroadcastDispatchResult())
        verify(exactly = 1) { base.startActivity(proxyActivity) }
        verify(exactly = 0) { base.startActivity(sourceActivity) }
        verify(exactly = 0) { base.sendBroadcast(any()) }
    }

    @Test
    fun `layout inflater service is cloned into virtual context`() {
        val base = baseContext()
        val hostInflater = mockk<LayoutInflater>(relaxed = true)
        val virtualInflater = mockk<LayoutInflater>(relaxed = true)
        val wrapper = wrapper(base = base)
        every { base.getSystemService(Context.LAYOUT_INFLATER_SERVICE) } returns hostInflater
        every { hostInflater.cloneInContext(wrapper) } returns virtualInflater
        every { virtualInflater.context } returns wrapper

        val service = wrapper.getSystemService(Context.LAYOUT_INFLATER_SERVICE)

        assertSame(virtualInflater, service)
        assertSame(wrapper, (service as LayoutInflater).context)
        verify(exactly = 1) { hostInflater.cloneInContext(wrapper) }
    }

    @Test
    fun `storage api records redirect evidence`(@TempDir dataRoot: File) {
        val wrapper = wrapper(
            snapshot = snapshot().copy(
                dataDir = dataRoot.absolutePath,
                nativeLibraryDir = File(dataRoot, "lib").absolutePath
            )
        )

        val path = wrapper.getFileStreamPath("nested_name.txt")

        val evidence = wrapper.lastStorageEvidence()
        assertEquals(File(dataRoot, "files/nested_name.txt").canonicalFile, path)
        assertEquals(StorageOperation.FILE_STREAM_PATH, evidence?.operation)
        assertEquals("nested_name.txt", evidence?.logicalName)
        assertEquals(path.canonicalPath, evidence?.redirectedPath?.let { File(it).canonicalPath })
        assertTrue(evidence?.withinDataRoot == true)
        assertTrue(evidence?.nativeLibraryRedirected == true)
    }

    @Test
    fun `startActivity delegates remap to VirtualAmsComponentDispatcher`() {
        val base = baseContext()
        val sourceIntent = mockk<Intent>(relaxed = true)
        val proxyIntent = mockk<Intent>(relaxed = true)
        val dispatcher = FakeAmsDispatcher(startActivityIntent = proxyIntent)
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

        wrapper.startActivity(sourceIntent)

        assertSame(sourceIntent, dispatcher.lastStartActivityIntent)
        assertIs<VirtualContextWrapper.StartActivityMappingResult.Remapped>(wrapper.lastStartActivityMappingResult())
        verify(exactly = 1) { base.startActivity(proxyIntent) }
        verify(exactly = 0) { base.startActivity(sourceIntent) }
    }

    @Test
    fun `startActivity blocks unsupported guest intent without host fallback`() {
        val base = baseContext()
        val sourceIntent = mockk<Intent>(relaxed = true)
        val dispatcher = FakeAmsDispatcher(startActivityIntent = null)
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

        wrapper.startActivity(sourceIntent)

        val blocked = assertIs<VirtualContextWrapper.StartActivityMappingResult.Blocked>(
            wrapper.lastStartActivityMappingResult()
        )
        assertSame(sourceIntent, blocked.sourceIntent)
        assertEquals("fakeBlocked", blocked.reason)
        verify(exactly = 0) { base.startActivity(any()) }
    }

    @Test
    fun `startActivities blocks whole batch when any guest intent is unsupported`() {
        val base = baseContext()
        val first = mockk<Intent>(relaxed = true)
        val second = mockk<Intent>(relaxed = true)
        val proxyIntent = mockk<Intent>(relaxed = true)
        val dispatcher = SequencedAmsDispatcher(
            activityResults = listOf(
                VirtualContextWrapper.StartActivityMappingResult.Remapped(first, proxyIntent),
                VirtualContextWrapper.StartActivityMappingResult.Blocked(second, "fakeBlocked")
            )
        )
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

        wrapper.startActivities(arrayOf(first, second))

        val blocked = assertIs<VirtualContextWrapper.StartActivityMappingResult.Blocked>(
            wrapper.lastStartActivityMappingResult()
        )
        assertSame(second, blocked.sourceIntent)
        assertSame(first, dispatcher.resolvedActivityIntents[0])
        assertSame(second, dispatcher.resolvedActivityIntents[1])
        verify(exactly = 0) { base.startActivities(any<Array<Intent>>(), any()) }
        verify(exactly = 0) { base.startActivity(any<Intent>()) }
        verify(exactly = 0) { base.startActivity(any<Intent>(), any()) }
    }

    @Test
    fun `startActivities remaps supported guest batch atomically`() {
        val base = baseContext()
        val first = mockk<Intent>(relaxed = true)
        val second = mockk<Intent>(relaxed = true)
        val proxyFirst = mockk<Intent>(relaxed = true)
        val proxySecond = mockk<Intent>(relaxed = true)
        val options = mockk<Bundle>(relaxed = true)
        val dispatcher = SequencedAmsDispatcher(
            activityResults = listOf(
                VirtualContextWrapper.StartActivityMappingResult.Remapped(first, proxyFirst),
                VirtualContextWrapper.StartActivityMappingResult.Remapped(second, proxySecond)
            )
        )
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

        wrapper.startActivities(arrayOf(first, second), options)

        val launchedIntents = slot<Array<Intent>>()
        verify(exactly = 1) { base.startActivities(capture(launchedIntents), options) }
        assertSame(proxyFirst, launchedIntents.captured[0])
        assertSame(proxySecond, launchedIntents.captured[1])
        assertSame(first, dispatcher.resolvedActivityIntents[0])
        assertSame(second, dispatcher.resolvedActivityIntents[1])
        assertIs<VirtualContextWrapper.StartActivityMappingResult.Remapped>(wrapper.lastStartActivityMappingResult())
        verify(exactly = 0) { base.startActivity(any<Intent>()) }
        verify(exactly = 0) { base.startActivity(any<Intent>(), any()) }
    }

    @Test
    fun `startActivities does not allocate partial batch when later guest intent is unsupported`() {
        val base = baseContext()
        val recordManager = VirtualActivityRecordManager()
        val options = mockk<Bundle>(relaxed = true)
        val wrapper = wrapper(
            base = base,
            snapshot = snapshot().copy(
                activities = listOf(ResolvedComponent(name = "com.test.minimal.MainActivity", exported = true))
            ),
            activityRecordManager = recordManager
        )
        val first = explicitActivityIntent("com.test.minimal.MainActivity")
        val second = explicitActivityIntent("com.test.minimal.MissingActivity")

        wrapper.startActivities(arrayOf(first, second), options)

        val blocked = assertIs<VirtualContextWrapper.StartActivityMappingResult.Blocked>(
            wrapper.lastStartActivityMappingResult()
        )
        assertSame(second, blocked.sourceIntent)
        assertEquals("unsupportedActivityIntent", blocked.reason)
        assertNull(recordManager.lastLaunchResult())
        assertTrue(recordManager.list().isEmpty())
        verify(exactly = 0) { base.startActivities(any<Array<Intent>>(), any()) }
        verify(exactly = 0) { base.startActivity(any<Intent>()) }
        verify(exactly = 0) { base.startActivity(any<Intent>(), any()) }
    }

    @Test
    fun `startService delegates mapping to VirtualAmsComponentDispatcher`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val proxyIntent = mockk<Intent>(relaxed = true)
        val returnedComponent = mockk<ComponentName>(relaxed = true)
        every { base.startService(proxyIntent) } returns returnedComponent
        val mapping = VirtualContextWrapper.StartServiceMappingResult.Remapped(
            sourceIntent = sourceIntent,
            foreground = false,
            startRequest = serviceStartRequest(sourceIntent),
            proxyIntent = proxyIntent
        )
        val dispatcher = FakeAmsDispatcher(startServiceResult = mapping)
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

        val result = wrapper.startService(sourceIntent)

        assertSame(returnedComponent, result)
        assertSame(sourceIntent, dispatcher.lastStartServiceIntent)
        assertEquals(false, dispatcher.lastStartServiceForeground)
        assertSame(mapping, wrapper.lastStartServiceMappingResult())
        verify(exactly = 1) { base.startService(proxyIntent) }
    }

    @Test
    fun `sendBroadcast delegates dispatch to VirtualAmsComponentDispatcher`() {
        val base = baseContext()
        val sourceIntent = explicitReceiverIntent("com.test.minimal.BootReceiver")
        val delivered = deliveredBroadcastResult(sourceIntent)
        val dispatcher = FakeAmsDispatcher(broadcastResult = delivered)
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

        wrapper.sendBroadcast(sourceIntent)

        assertSame(sourceIntent, dispatcher.lastBroadcastIntent)
        assertSame(wrapper, dispatcher.lastBroadcastContext)
        assertSame(delivered, wrapper.lastBroadcastDispatchResult())
        verify(exactly = 0) { base.sendBroadcast(any()) }
    }

    @Test
    fun `sendStickyOrderedBroadcast dispatches virtually without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitReceiverIntent("com.test.minimal.BootReceiver")
        val delivered = deliveredBroadcastResult(sourceIntent)
        val dispatcher = FakeAmsDispatcher(broadcastResult = delivered)
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)
        val resultReceiver = mockk<BroadcastReceiver>(relaxed = true)
        val scheduler = mockk<Handler>(relaxed = true)
        val initialExtras = mockk<Bundle>(relaxed = true)

        wrapper.sendStickyOrderedBroadcast(sourceIntent, resultReceiver, scheduler, 200, "ok", initialExtras)

        assertSame(sourceIntent, dispatcher.lastBroadcastIntent)
        assertSame(wrapper, dispatcher.lastBroadcastContext)
        assertSame(delivered, wrapper.lastBroadcastDispatchResult())
        verify(exactly = 0) {
            base.sendStickyOrderedBroadcast(sourceIntent, resultReceiver, scheduler, 200, "ok", initialExtras)
        }
        verify(exactly = 0) { base.sendBroadcast(any()) }
    }

    @Test
    fun `sendStickyOrderedBroadcastAsUser dispatches virtually without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitReceiverIntent("com.test.minimal.BootReceiver")
        val delivered = deliveredBroadcastResult(sourceIntent)
        val dispatcher = FakeAmsDispatcher(broadcastResult = delivered)
        val wrapper = wrapper(base = base, amsDispatcher = dispatcher)
        val user = mockk<UserHandle>(relaxed = true)
        val resultReceiver = mockk<BroadcastReceiver>(relaxed = true)
        val scheduler = mockk<Handler>(relaxed = true)
        val initialExtras = mockk<Bundle>(relaxed = true)

        wrapper.sendStickyOrderedBroadcastAsUser(sourceIntent, user, resultReceiver, scheduler, 201, "ok-user", initialExtras)

        assertSame(sourceIntent, dispatcher.lastBroadcastIntent)
        assertSame(wrapper, dispatcher.lastBroadcastContext)
        assertSame(delivered, wrapper.lastBroadcastDispatchResult())
        verify(exactly = 0) {
            base.sendStickyOrderedBroadcastAsUser(sourceIntent, user, resultReceiver, scheduler, 201, "ok-user", initialExtras)
        }
    }

    @Test
    fun `sendBroadcastWithMultiplePermissions dispatches virtually without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitReceiverIntent("com.test.minimal.BootReceiver")
        val delivered = deliveredBroadcastResult(sourceIntent)
        val dispatcher = FakeAmsDispatcher(broadcastResult = delivered)
        val wrapper = api36Wrapper(base = base, amsDispatcher = dispatcher)
        val permissions = arrayOf("com.test.PERMISSION_ONE", "com.test.PERMISSION_TWO")

        wrapper.sendBroadcastWithMultiplePermissions(sourceIntent, permissions)

        assertSame(sourceIntent, dispatcher.lastBroadcastIntent)
        assertSame(wrapper, dispatcher.lastBroadcastContext)
        assertSame(delivered, wrapper.lastBroadcastDispatchResult())
        verify(exactly = 0) { base.sendBroadcastWithMultiplePermissions(sourceIntent, permissions) }
    }

    @Test
    fun `startIntentSender overloads block without host fallback`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)
        val sender = mockk<IntentSender>(relaxed = true)
        val fillInIntent = explicitActivityIntent("com.test.minimal.MainActivity")
        val options = mockk<Bundle>(relaxed = true)

        wrapper.startIntentSender(sender, fillInIntent, 1, 2, 3)
        val blocked = assertIs<VirtualContextWrapper.StartActivityMappingResult.Blocked>(
            wrapper.lastStartActivityMappingResult()
        )
        assertSame(fillInIntent, blocked.sourceIntent)
        assertEquals("intentSenderLaunchUnsupported", blocked.reason)

        wrapper.startIntentSender(sender, fillInIntent, 4, 5, 6, options)
        val blockedWithOptions = assertIs<VirtualContextWrapper.StartActivityMappingResult.Blocked>(
            wrapper.lastStartActivityMappingResult()
        )
        assertSame(fillInIntent, blockedWithOptions.sourceIntent)
        assertEquals("intentSenderLaunchUnsupported", blockedWithOptions.reason)
        verify(exactly = 0) { base.startIntentSender(sender, fillInIntent, 1, 2, 3) }
        verify(exactly = 0) { base.startIntentSender(sender, fillInIntent, 4, 5, 6, options) }
    }

    @Test
    fun `bindService explicit guest service is blocked after virtual resolution without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val wrapper = wrapper(base = base)

        val result = wrapper.bindService(sourceIntent, connection, Context.BIND_AUTO_CREATE)

        assertFalse(result)
        val evidence = assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(
            wrapper.lastStartServiceMappingResult()
        )
        assertSame(sourceIntent, evidence.sourceIntent)
        assertEquals("com.test.minimal.SyncService", evidence.startRequest.guestServiceClassName)
        verify(exactly = 0) { base.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
    }

    @Test
    fun `bindService executor overload blocks explicit guest service without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val executor = directExecutor()
        val wrapper = wrapper(base = base)

        val result = wrapper.bindService(sourceIntent, Context.BIND_AUTO_CREATE, executor, connection)

        assertFalse(result)
        assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(wrapper.lastStartServiceMappingResult())
        verify(exactly = 0) { base.bindService(any<Intent>(), any<Int>(), any<Executor>(), any<ServiceConnection>()) }
    }

    @Test
    fun `bindService BindServiceFlags overload blocks explicit guest service without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val flags = bindServiceFlags()
        val wrapper = api34Wrapper(base = base)

        val result = wrapper.bindService(sourceIntent, connection, flags)

        assertFalse(result)
        assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(wrapper.lastStartServiceMappingResult())
        verify(exactly = 0) { base.bindService(any<Intent>(), any<ServiceConnection>(), any<Context.BindServiceFlags>()) }
    }

    @Test
    fun `bindService BindServiceFlags executor overload blocks explicit guest service without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val flags = bindServiceFlags()
        val executor = directExecutor()
        val wrapper = api34Wrapper(base = base)

        val result = wrapper.bindService(sourceIntent, flags, executor, connection)

        assertFalse(result)
        assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(wrapper.lastStartServiceMappingResult())
        verify(exactly = 0) {
            base.bindService(any<Intent>(), any<Context.BindServiceFlags>(), any<Executor>(), any<ServiceConnection>())
        }
    }

    @Test
    fun `bindServiceAsUser overloads block explicit guest service without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val user = mockk<UserHandle>(relaxed = true)
        val flags = bindServiceFlags()
        val wrapper = api34Wrapper(base = base)

        assertFalse(wrapper.bindServiceAsUser(sourceIntent, connection, Context.BIND_AUTO_CREATE, user))
        assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(wrapper.lastStartServiceMappingResult())
        assertFalse(wrapper.bindServiceAsUser(sourceIntent, connection, flags, user))
        assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(wrapper.lastStartServiceMappingResult())
        verify(exactly = 0) { base.bindServiceAsUser(any<Intent>(), any<ServiceConnection>(), any<Int>(), any<UserHandle>()) }
        verify(exactly = 0) {
            base.bindServiceAsUser(any<Intent>(), any<ServiceConnection>(), any<Context.BindServiceFlags>(), any<UserHandle>())
        }
    }

    @Test
    fun `bindIsolatedService overloads block explicit guest service without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val executor = directExecutor()
        val flags = bindServiceFlags()
        val wrapper = api34Wrapper(base = base)

        assertFalse(wrapper.bindIsolatedService(sourceIntent, Context.BIND_AUTO_CREATE, "guest", executor, connection))
        assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(wrapper.lastStartServiceMappingResult())
        assertFalse(wrapper.bindIsolatedService(sourceIntent, flags, "guest", executor, connection))
        assertIs<VirtualContextWrapper.StartServiceMappingResult.Remapped>(wrapper.lastStartServiceMappingResult())
        verify(exactly = 0) {
            base.bindIsolatedService(any<Intent>(), any<Int>(), any<String>(), any<Executor>(), any<ServiceConnection>())
        }
        verify(exactly = 0) {
            base.bindIsolatedService(
                any<Intent>(),
                any<Context.BindServiceFlags>(),
                any<String>(),
                any<Executor>(),
                any<ServiceConnection>()
            )
        }
    }

    @Test
    fun `bindService unsupported guest service returns false without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.MissingService")
        val connection = mockk<ServiceConnection>(relaxed = true)
        val wrapper = wrapper(base = base)

        val result = wrapper.bindService(sourceIntent, connection, Context.BIND_AUTO_CREATE)

        assertFalse(result)
        assertBlockedEvidence(
            wrapper.lastStartServiceMappingResult(),
            sourceIntent = sourceIntent,
            reason = "unsupportedServiceIntent",
            foreground = false
        )
        verify(exactly = 0) { base.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
    }

    @Test
    fun `unbindService and updateServiceGroup do not fall back to host for untracked connections`() {
        val base = baseContext()
        val connection = mockk<ServiceConnection>(relaxed = true)
        val wrapper = wrapper(base = base)

        wrapper.unbindService(connection)
        wrapper.updateServiceGroup(connection, 10, 20)

        verify(exactly = 0) { base.unbindService(connection) }
        verify(exactly = 0) { base.updateServiceGroup(connection, 10, 20) }
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
    fun `startForegroundService blocks explicit guest service as unsupported without host fallback`() {
        val base = baseContext()
        val sourceIntent = explicitServiceIntent("com.test.minimal.SyncService")
        val wrapper = wrapper(base = base)

        val result = wrapper.startForegroundService(sourceIntent)

        assertNull(result)
        assertBlockedEvidence(
            wrapper.lastStartServiceMappingResult(),
            sourceIntent = sourceIntent,
            reason = FOREGROUND_SERVICE_LIFECYCLE_UNSUPPORTED_REASON,
            foreground = true
        )
        verify(exactly = 0) { base.startForegroundService(any()) }
    }

    @Test
    fun `startService records fallback evidence for implicit unsupported and missing snapshot`() {
        val base = baseContext()
        every { base.startService(any()) } returns mockk(relaxed = true)
        val wrapper = wrapper(base = base)

        val implicit = implicitIntent()
        assertNull(wrapper.startService(implicit))
        assertBlockedEvidence(
            wrapper.lastStartServiceMappingResult(),
            sourceIntent = implicit,
            reason = "implicitServiceIntent",
            foreground = false
        )

        val unsupported = explicitServiceIntent("com.test.minimal.MissingService")
        assertNull(wrapper.startService(unsupported))
        assertBlockedEvidence(
            wrapper.lastStartServiceMappingResult(),
            sourceIntent = unsupported,
            reason = "unsupportedServiceIntent",
            foreground = false
        )

        val noSnapshotWrapper = wrapper(base = base, snapshot = null)
        val noSnapshotIntent = explicitServiceIntent("com.test.minimal.SyncService")
        assertNull(noSnapshotWrapper.startService(noSnapshotIntent))
        assertBlockedEvidence(
            noSnapshotWrapper.lastStartServiceMappingResult(),
            sourceIntent = noSnapshotIntent,
            reason = "missingPackageSnapshot",
            foreground = false
        )
        verify(exactly = 0) { base.startService(implicit) }
        verify(exactly = 0) { base.startService(unsupported) }
        verify(exactly = 0) { base.startService(noSnapshotIntent) }
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

        assertIs<VirtualContextWrapper.StartServiceMappingResult.Blocked>(wrapper.lastStartServiceMappingResult())
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
    fun `sendBroadcast blocks implicit broadcast and keeps unsupported evidence`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)
        val intent = implicitIntent()
        every { intent.action } returns "com.test.ACTION_IMPLICIT"

        wrapper.sendBroadcast(intent)

        val unsupported = assertIs<VirtualBroadcastResult.UnsupportedImplicit>(wrapper.lastBroadcastDispatchResult())
        assertEquals(VirtualBroadcastResultCode.UnsupportedImplicit, unsupported.record.result)
        assertEquals("com.test.ACTION_IMPLICIT", unsupported.record.action)
        verify(exactly = 0) { base.sendBroadcast(intent) }
    }

    @Test
    fun `sendBroadcast blocks without package snapshot and keeps clear evidence`() {
        val base = baseContext()
        val wrapper = wrapper(base = base, snapshot = null)
        val intent = explicitReceiverIntent("com.test.minimal.BootReceiver")

        wrapper.sendBroadcast(intent)

        val noSnapshot = assertIs<VirtualBroadcastResult.NoPackageSnapshot>(wrapper.lastBroadcastDispatchResult())
        assertEquals(VirtualBroadcastResultCode.NoPackageSnapshot, noSnapshot.record.result)
        assertEquals("com.test.minimal.BootReceiver", noSnapshot.record.receiverClassName)
        verify(exactly = 0) { base.sendBroadcast(intent) }
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
    fun `dynamic receiver handles broadcast even when package snapshot is missing`() {
        val base = baseContext()
        val registry = VirtualDynamicReceiverRegistry()
        val wrapper = wrapper(
            base = base,
            snapshot = null,
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
        verify(exactly = 0) { base.sendBroadcast(intent) }
    }

    @Test
    fun `registerReceiver flags overload uses virtual registry without host registration`() {
        val base = baseContext()
        val registry = VirtualDynamicReceiverRegistry()
        val wrapper = wrapper(base = base, dynamicReceiverRegistry = registry)
        val receiver = RecordingReceiver()
        val filter = mockk<IntentFilter>(relaxed = true) {
            every { countActions() } returns 1
            every { getAction(0) } returns "com.test.ACTION_DYNAMIC"
            every { countCategories() } returns 0
            every { countDataSchemes() } returns 0
        }

        val sticky = wrapper.registerReceiver(receiver, filter, 0)

        assertNull(sticky)
        val registration = assertIs<VirtualContextWrapper.BroadcastReceiverRegistrationResult.Registered>(
            wrapper.lastBroadcastReceiverRegistrationResult()
        )
        assertSame(receiver, registration.receiver)
        assertEquals("inst-001", registration.instanceId)
        verify(exactly = 0) { base.registerReceiver(receiver, filter, 0) }
    }

    @Test
    fun `registerReceiver permission overload without permission or scheduler uses virtual registry`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)
        val receiver = RecordingReceiver()
        val filter = mockk<IntentFilter>(relaxed = true) {
            every { countActions() } returns 1
            every { getAction(0) } returns "com.test.ACTION_DYNAMIC"
            every { countCategories() } returns 0
            every { countDataSchemes() } returns 0
        }

        val sticky = wrapper.registerReceiver(receiver, filter, null, null)

        assertNull(sticky)
        val registration = assertIs<VirtualContextWrapper.BroadcastReceiverRegistrationResult.Registered>(
            wrapper.lastBroadcastReceiverRegistrationResult()
        )
        assertSame(receiver, registration.receiver)
        assertEquals("inst-001", registration.instanceId)
        verify(exactly = 0) { base.registerReceiver(receiver, filter, null, null) }
    }

    @Test
    fun `registerReceiver with missing receiver or filter is blocked without host registration`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)
        val filter = mockk<IntentFilter>(relaxed = true)

        val sticky = wrapper.registerReceiver(null, filter)

        assertNull(sticky)
        val fallback = assertIs<VirtualContextWrapper.BroadcastReceiverRegistrationResult.Fallback>(
            wrapper.lastBroadcastReceiverRegistrationResult()
        )
        assertNull(fallback.receiver)
        assertEquals("missingReceiverOrFilter", fallback.reason)
        verify(exactly = 0) { base.registerReceiver(null, filter) }
    }

    @Test
    fun `registerReceiver with permission is blocked and records unsupported evidence`() {
        val base = baseContext()
        val wrapper = wrapper(base = base)
        val receiver = RecordingReceiver()
        val filter = mockk<IntentFilter>(relaxed = true)

        val sticky = wrapper.registerReceiver(receiver, filter, "com.test.PERMISSION", null)

        assertNull(sticky)
        val fallback = assertIs<VirtualContextWrapper.BroadcastReceiverRegistrationResult.Fallback>(
            wrapper.lastBroadcastReceiverRegistrationResult()
        )
        assertSame(receiver, fallback.receiver)
        assertEquals("permissionOrSchedulerUnsupported", fallback.reason)
        verify(exactly = 0) { base.registerReceiver(receiver, filter, "com.test.PERMISSION", null) }
    }

    @Test
    fun `registerReceiver records AMS API evidence when dynamic receiver is accepted`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val wrapper = wrapper(base = base)
            val receiver = RecordingReceiver()
            val filter = mockk<IntentFilter>(relaxed = true) {
                every { countActions() } returns 1
                every { getAction(0) } returns "com.test.minimal.ACTION_DYNAMIC_PR8_PROBE"
                every { countCategories() } returns 0
                every { countDataSchemes() } returns 0
            }

            wrapper.registerReceiver(receiver, filter)

            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER }
            assertEquals("registerReceiver", record.api)
            assertEquals("DYNAMIC_RECEIVER_REGISTERED", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals(true, record.fields["registered"])
            verify(exactly = 0) { base.registerReceiver(receiver, filter) }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    @Test
    fun `registerReceiver missing receiver or filter records rejected evidence`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val wrapper = wrapper(base = base)
            val filter = mockk<IntentFilter>(relaxed = true)

            val sticky = wrapper.registerReceiver(null, filter)

            assertNull(sticky)
            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER }
            assertEquals("registerReceiver", record.api)
            assertEquals("DYNAMIC_RECEIVER_REJECTED", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals(false, record.fields["registered"])
            assertEquals("missingReceiverOrFilter", record.fields["reason"])
            verify(exactly = 0) { base.registerReceiver(null, filter) }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    @Test
    fun `startActivity records remap evidence without host fallback`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val sourceIntent = explicitActivityIntent("com.test.minimal.MainActivity")
            val proxyIntent = explicitActivityIntent(
                className = "com.multiapp.app.container.ProxyActivity0",
                packageName = "com.multiapp.app"
            )
            val dispatcher = FakeAmsDispatcher(startActivityIntent = proxyIntent)
            val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

            wrapper.startActivity(sourceIntent)

            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.START_ACTIVITY_OVERLOAD }
            assertEquals("startActivity", record.api)
            assertEquals("ACTIVITY_REMAP_READY", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals(true, record.fields["remapped"])
            assertEquals("", record.fields["reason"])
            assertEquals("com.test.minimal/com.test.minimal.MainActivity", record.fields["sourceComponent"])
            assertEquals("com.multiapp.app/com.multiapp.app.container.ProxyActivity0", record.fields["proxyComponent"])
            verify(exactly = 1) { base.startActivity(proxyIntent) }
            verify(exactly = 0) { base.startActivity(sourceIntent) }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    @Test
    fun `startActivities records blocked batch evidence without host fallback`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val first = explicitActivityIntent("com.test.minimal.MainActivity")
            val second = explicitActivityIntent("com.test.minimal.MissingActivity")
            val proxyIntent = explicitActivityIntent(
                className = "com.multiapp.app.container.ProxyActivity0",
                packageName = "com.multiapp.app"
            )
            val dispatcher = SequencedAmsDispatcher(
                activityResults = listOf(
                    VirtualContextWrapper.StartActivityMappingResult.Remapped(first, proxyIntent),
                    VirtualContextWrapper.StartActivityMappingResult.Blocked(second, "unsupportedActivityIntent")
                )
            )
            val wrapper = wrapper(base = base, amsDispatcher = dispatcher)

            wrapper.startActivities(arrayOf(first, second))

            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.START_ACTIVITIES_OVERLOAD }
            assertEquals("startActivities", record.api)
            assertEquals("ACTIVITY_BATCH_BLOCKED", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals(2, record.fields["batchSize"])
            assertEquals(1, record.fields["remappedCount"])
            assertEquals("unsupportedActivityIntent", record.fields["reason"])
            assertEquals("com.test.minimal/com.test.minimal.MissingActivity", record.fields["blockedSourceComponent"])
            verify(exactly = 0) { base.startActivities(any<Array<Intent>>(), any()) }
            verify(exactly = 0) { base.startActivity(any<Intent>()) }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    @Test
    fun `registerReceiver permission or scheduler unsupported records rejected evidence`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val wrapper = wrapper(base = base)
            val receiver = RecordingReceiver()
            val filter = mockk<IntentFilter>(relaxed = true)
            val scheduler = mockk<Handler>(relaxed = true)

            val sticky = wrapper.registerReceiver(receiver, filter, null, scheduler)

            assertNull(sticky)
            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER }
            assertEquals("registerReceiver", record.api)
            assertEquals("DYNAMIC_RECEIVER_REJECTED", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals(false, record.fields["registered"])
            assertEquals("permissionOrSchedulerUnsupported", record.fields["reason"])
            verify(exactly = 0) { base.registerReceiver(receiver, filter, null, scheduler) }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    @Test
    fun `sendStickyOrderedBroadcast records non-success top-level status while preserving dispatchStatus`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val sourceIntent = explicitReceiverIntent("com.test.minimal.BootReceiver")
            val delivered = deliveredBroadcastResult(sourceIntent)
            val dispatcher = FakeAmsDispatcher(broadcastResult = delivered)
            val wrapper = wrapper(base = base, amsDispatcher = dispatcher)
            val resultReceiver = mockk<BroadcastReceiver>(relaxed = true)
            val scheduler = mockk<Handler>(relaxed = true)
            val initialExtras = mockk<Bundle>(relaxed = true)

            wrapper.sendStickyOrderedBroadcast(sourceIntent, resultReceiver, scheduler, 200, "pr8", initialExtras)

            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.STICKY_ORDERED_BROADCAST }
            assertEquals("sendStickyOrderedBroadcast", record.api)
            assertEquals("STICKY_ORDERED_INTERCEPTED", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals("Delivered", record.fields["dispatchStatus"])
            verify(exactly = 0) {
                base.sendStickyOrderedBroadcast(sourceIntent, resultReceiver, scheduler, 200, "pr8", initialExtras)
            }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    @Test
    fun `bindService records AMS API evidence while returning false`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val wrapper = wrapper(
                base = base,
                snapshot = snapshot().copy(
                    services = listOf(ResolvedComponent(name = "com.test.minimal.ProbeService", exported = false))
                )
            )
            val service = explicitServiceIntent("com.test.minimal.ProbeService")
            val connection = mockk<ServiceConnection>(relaxed = true)

            val result = wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE)

            assertFalse(result)
            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD }
            assertEquals("bindService:int", record.api)
            assertEquals("BIND_BLOCKED", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals(false, record.fields["returnValue"])
            assertEquals(true, record.fields["serviceResolved"])
            assertEquals("explicit", record.fields["reason"])
            verify(exactly = 0) { base.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    @Test
    fun `startForegroundService records unsupported AMS API evidence while returning null`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        try {
            val base = baseContext()
            val wrapper = wrapper(
                base = base,
                snapshot = snapshot().copy(
                    services = listOf(ResolvedComponent(name = "com.test.minimal.ProbeService", exported = false))
                )
            )
            val service = explicitServiceIntent("com.test.minimal.ProbeService")

            val result = wrapper.startForegroundService(service)

            assertNull(result)
            val record = records.single { it.component == VirtualAmsApiEvidenceComponent.START_FOREGROUND_SERVICE }
            assertEquals("startForegroundService", record.api)
            assertEquals("FOREGROUND_SERVICE_UNSUPPORTED", record.status)
            assertEquals(false, record.hostFallback)
            assertEquals("null", record.fields["returnValue"])
            assertEquals(true, record.fields["serviceResolved"])
            assertEquals(FOREGROUND_SERVICE_LIFECYCLE_UNSUPPORTED_REASON, record.fields["reason"])
            assertEquals(true, record.fields["foreground"])
            assertEquals(false, record.fields["lifecycleImplemented"])
            assertEquals("UNSUPPORTED", record.fields["capabilityVerdict"])
            verify(exactly = 0) { base.startForegroundService(any()) }
        } finally {
            VirtualAmsApiEvidenceRecorders.reset()
        }
    }

    private fun wrapper(
        base: Context = baseContext(),
        snapshot: VirtualPackageSnapshot? = snapshot(),
        registry: VirtualPackageRegistry = VirtualPackageRegistry().apply { snapshot?.let { register(it) } },
        serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime(recordManager = VirtualServiceRecordManager()),
        broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
        dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry(),
        activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager(),
        serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { _, _ ->
            mockk(relaxed = true)
        },
        amsDispatcher: VirtualAmsComponentDispatcher? = null
    ): VirtualContextWrapper {
        return VirtualContextWrappers.create(
            base = base,
            config = config(snapshot),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            activityRecordManager = activityRecordManager,
            servicePackageRegistry = registry,
            serviceRuntime = serviceRuntime,
            broadcastManager = broadcastManager,
            dynamicReceiverRegistry = dynamicReceiverRegistry,
            serviceProxyIntentFactory = serviceProxyIntentFactory,
            amsDispatcher = amsDispatcher
        )
    }

    private fun api34Wrapper(
        base: Context = baseContext(),
        snapshot: VirtualPackageSnapshot? = snapshot(),
        registry: VirtualPackageRegistry = VirtualPackageRegistry().apply { snapshot?.let { register(it) } },
        serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime(recordManager = VirtualServiceRecordManager()),
        broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
        dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry(),
        activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager(),
        serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { _, _ ->
            mockk(relaxed = true)
        },
        amsDispatcher: VirtualAmsComponentDispatcher? = null
    ): VirtualContextWrapperApi34 {
        return VirtualContextWrapperApi34(
            base = base,
            config = config(snapshot),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            activityRecordManager = activityRecordManager,
            servicePackageRegistry = registry,
            serviceRuntime = serviceRuntime,
            broadcastManager = broadcastManager,
            dynamicReceiverRegistry = dynamicReceiverRegistry,
            serviceProxyIntentFactory = serviceProxyIntentFactory,
            amsDispatcher = amsDispatcher
        )
    }

    private fun api36Wrapper(
        base: Context = baseContext(),
        snapshot: VirtualPackageSnapshot? = snapshot(),
        registry: VirtualPackageRegistry = VirtualPackageRegistry().apply { snapshot?.let { register(it) } },
        serviceRuntime: VirtualServiceRuntime = VirtualServiceRuntime(recordManager = VirtualServiceRecordManager()),
        broadcastManager: VirtualBroadcastManager = VirtualBroadcastManager(),
        dynamicReceiverRegistry: VirtualDynamicReceiverRegistry = VirtualDynamicReceiverRegistry(),
        activityRecordManager: VirtualActivityRecordManager = VirtualActivityRecordManager(),
        serviceProxyIntentFactory: (VirtualServiceManager, VirtualServiceStartRequest) -> Intent = { _, _ ->
            mockk(relaxed = true)
        },
        amsDispatcher: VirtualAmsComponentDispatcher? = null
    ): VirtualContextWrapperApi36 {
        return VirtualContextWrapperApi36(
            base = base,
            config = config(snapshot),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            activityRecordManager = activityRecordManager,
            servicePackageRegistry = registry,
            serviceRuntime = serviceRuntime,
            broadcastManager = broadcastManager,
            dynamicReceiverRegistry = dynamicReceiverRegistry,
            serviceProxyIntentFactory = serviceProxyIntentFactory,
            amsDispatcher = amsDispatcher
        )
    }

    private fun baseContext(): Context {
        val base = mockk<Context>(relaxed = true)
        every { base.packageName } returns "com.multiapp.app"
        return base
    }

    private fun explicitActivityIntent(
        className: String,
        packageName: String = "com.test.minimal"
    ): Intent {
        val component = mockk<ComponentName>(relaxed = true)
        every { component.packageName } returns packageName
        every { component.className } returns className
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns component
        every { intent.`package` } returns null
        every { intent.flags } returns 0
        every { intent.dataString } returns null
        every { intent.action } returns null
        every { intent.categories } returns emptySet()
        every { intent.extras } returns null
        return intent
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
        every { intent.`package` } returns null
        every { intent.flags } returns 0
        every { intent.dataString } returns null
        every { intent.action } returns null
        every { intent.categories } returns emptySet()
        every { intent.extras } returns null
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

    private fun assertBlockedEvidence(
        actual: VirtualContextWrapper.StartServiceMappingResult?,
        sourceIntent: Intent,
        reason: String,
        foreground: Boolean
    ) {
        val blocked = assertIs<VirtualContextWrapper.StartServiceMappingResult.Blocked>(actual)
        assertSame(sourceIntent, blocked.sourceIntent)
        assertEquals(reason, blocked.reason)
        assertEquals(foreground, blocked.foreground)
    }

    private fun directExecutor(): Executor = Executor { runnable -> runnable.run() }

    private fun bindServiceFlags(): Context.BindServiceFlags = mockk(relaxed = true)

    private fun serviceStartRequest(sourceIntent: Intent) = VirtualServiceStartRequest(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestServiceClassName = "com.test.minimal.SyncService",
        sourceIntent = sourceIntent,
        reason = "explicit"
    )

    private fun deliveredBroadcastResult(sourceIntent: Intent): VirtualBroadcastResult.Delivered {
        val request = VirtualBroadcastDispatchRequest(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            receiverClassName = "com.test.minimal.BootReceiver",
            sourceIntent = sourceIntent,
            action = "com.test.ACTION_BOOT",
            reason = "explicit"
        )
        return VirtualBroadcastResult.Delivered(
            request = request,
            receiver = RecordingReceiver(),
            record = VirtualBroadcastRecord(
                instanceId = "inst-001",
                receiverClassName = "com.test.minimal.BootReceiver",
                action = "com.test.ACTION_BOOT",
                result = VirtualBroadcastResultCode.Delivered
            )
        )
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

    private class SequencedAmsDispatcher(
        private val activityResults: List<VirtualContextWrapper.StartActivityMappingResult>
    ) : VirtualAmsComponentDispatcher {
        val resolvedActivityIntents = mutableListOf<Intent>()
        private var activityIndex = 0

        override fun resolveStartActivityIntent(intent: Intent): VirtualContextWrapper.StartActivityMappingResult {
            resolvedActivityIntents += intent
            return activityResults[activityIndex++]
        }

        override fun resolveStartServiceIntent(
            intent: Intent,
            foreground: Boolean
        ): VirtualContextWrapper.StartServiceMappingResult {
            return VirtualContextWrapper.StartServiceMappingResult.Blocked(
                sourceIntent = intent,
                foreground = foreground,
                reason = "fakeBlocked"
            )
        }

        override fun dispatchStopService(intent: Intent): VirtualServiceStopDispatchResult? = null

        override fun dispatchBroadcast(
            intent: Intent,
            virtualContext: Context,
            receiverClassLoader: ClassLoader
        ): VirtualBroadcastResult = VirtualBroadcastManager.noPackageSnapshot(intent)
    }

    private class FakeAmsDispatcher(
        private val startActivityIntent: Intent? = null,
        private val startServiceResult: VirtualContextWrapper.StartServiceMappingResult? = null,
        private val broadcastResult: VirtualBroadcastResult? = null
    ) : VirtualAmsComponentDispatcher {
        var lastStartActivityIntent: Intent? = null
        var lastStartServiceIntent: Intent? = null
        var lastStartServiceForeground: Boolean? = null
        var lastBroadcastIntent: Intent? = null
        var lastBroadcastContext: Context? = null

        override fun resolveStartActivityIntent(intent: Intent): VirtualContextWrapper.StartActivityMappingResult {
            lastStartActivityIntent = intent
            return startActivityIntent?.let { proxyIntent ->
                VirtualContextWrapper.StartActivityMappingResult.Remapped(
                    sourceIntent = intent,
                    proxyIntent = proxyIntent
                )
            } ?: VirtualContextWrapper.StartActivityMappingResult.Blocked(
                sourceIntent = intent,
                reason = "fakeBlocked"
            )
        }

        override fun resolveStartServiceIntent(
            intent: Intent,
            foreground: Boolean
        ): VirtualContextWrapper.StartServiceMappingResult {
            lastStartServiceIntent = intent
            lastStartServiceForeground = foreground
            return startServiceResult ?: VirtualContextWrapper.StartServiceMappingResult.Blocked(
                sourceIntent = intent,
                foreground = foreground,
                reason = "fakeBlocked"
            )
        }

        override fun dispatchStopService(intent: Intent): VirtualServiceStopDispatchResult? = null

        override fun dispatchBroadcast(
            intent: Intent,
            virtualContext: Context,
            receiverClassLoader: ClassLoader
        ): VirtualBroadcastResult {
            lastBroadcastIntent = intent
            lastBroadcastContext = virtualContext
            return broadcastResult ?: VirtualBroadcastManager.noPackageSnapshot(intent)
        }
    }

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
