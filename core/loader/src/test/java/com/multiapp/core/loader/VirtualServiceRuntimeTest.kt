package com.multiapp.core.loader

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualServiceRuntimeTest {

    @Test
    fun `start creates attaches calls onCreate once and reuses service`() {
        val service = FakeService()
        val attached = mutableListOf<String>()
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, className, _ -> attached += className },
            recordManager = records,
            clock = { 100L }
        )
        val firstRequest = request(startId = 1)
        val secondRequest = request(startId = 2)

        val first = runtime.start(firstRequest)
        val second = runtime.start(secondRequest)

        val created = assertIs<VirtualServiceRuntimeResult.CreatedAndStarted>(first)
        val cached = assertIs<VirtualServiceRuntimeResult.StartedCached>(second)
        assertSame(service, created.service)
        assertSame(service, cached.service)
        assertEquals(1, created.activeStartCount)
        assertEquals(0, created.activeBindCount)
        assertEquals(1, cached.activeStartCount)
        assertEquals(0, cached.activeBindCount)
        assertEquals(1, service.onCreateCalls)
        assertEquals(listOf(1, 2), service.startIds)
        assertEquals(listOf("com.test.minimal.SyncService"), attached)

        val record = records.get("inst-001", "com.test.minimal.SyncService")!!
        assertEquals(2, record.startCount)
        assertEquals(2, record.lastStartId)
        assertEquals(Service.START_NOT_STICKY, record.lastStartCommandResult)
    }

    @Test
    fun `start captures guest foreground state updated during onStartCommand`() {
        val records = VirtualServiceRecordManager()
        lateinit var runtime: VirtualServiceRuntime
        val service = FakeService(
            onStartCommandAction = {
                val token = records.get("inst-001", "com.test.minimal.SyncService")!!.token
                runtime.setServiceForegroundToken(
                    token = token,
                    notificationId = 77,
                    notification = mockk<Notification>(relaxed = true),
                    foregroundServiceType = 8
                )
            }
        )
        runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records,
            clock = { 100L }
        )

        val result = assertIs<VirtualServiceRuntimeResult.CreatedAndStarted>(
            runtime.start(request(startId = 1))
        )

        assertTrue(result.foreground)
        assertEquals(77, result.foregroundNotificationId)
        assertEquals(8, result.foregroundServiceType)
        val record = records.get("inst-001", "com.test.minimal.SyncService")!!
        assertTrue(record.foreground)
        assertEquals(77, record.foregroundNotificationId)
        assertEquals(8, record.foregroundServiceType)
    }

    @Test
    fun `stopServiceToken only stops latest start id`() {
        val service = FakeService()
        val records = VirtualServiceRecordManager()
        val idleStops = mutableListOf<Pair<VirtualServiceStartRequest, String>>()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records,
            hostServiceIdleStopper = HostServiceIdleStopper { request, reason ->
                idleStops += request to reason
                HostServiceIdleStopResult(
                    idleStopRequested = true,
                    idleStopReason = reason,
                    hostStopServiceReturnValue = true,
                    detail = "testStopper"
                )
            },
            clock = { 100L }
        )
        runtime.start(request(startId = 1))
        runtime.start(request(startId = 2))
        val token = records.get("inst-001", "com.test.minimal.SyncService")!!.token

        assertFalse(runtime.stopServiceToken(token, 1))
        assertTrue(runtime.stopServiceToken(token, 2))

        assertEquals(1, service.onDestroyCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
        assertEquals(1, idleStops.size)
        assertEquals("stopServiceTokenDestroyed", idleStops.single().second)
    }

    @Test
    fun `stopServiceToken keeps service until active bind is released`() {
        val binder = mockk<IBinder>(relaxed = true)
        val service = FakeService(binder = binder)
        val records = VirtualServiceRecordManager()
        val idleStops = mutableListOf<String>()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records,
            hostServiceIdleStopper = HostServiceIdleStopper { _, reason ->
                idleStops += reason
                HostServiceIdleStopResult(
                    idleStopRequested = true,
                    idleStopReason = reason,
                    hostStopServiceReturnValue = true,
                    detail = "testStopper"
                )
            },
            clock = { 100L }
        )
        runtime.start(request(startId = 1))
        runtime.bind(bindRequest())
        val token = records.get("inst-001", "com.test.minimal.SyncService")!!.token

        assertTrue(runtime.stopServiceToken(token, 1))
        val retained = records.get("inst-001", "com.test.minimal.SyncService")!!
        assertFalse(retained.started)
        assertEquals(1, retained.activeBindCount)
        assertEquals(0, service.onDestroyCalls)
        assertEquals(emptyList(), idleStops)

        val unbound = assertIs<VirtualServiceRuntimeUnbindResult.Unbound>(runtime.unbind(unbindRequest()))
        assertTrue(unbound.destroyed)
        assertTrue(unbound.idleStopResult.idleStopRequested)
        assertEquals("unbindDestroyed", unbound.idleStopResult.idleStopReason)
        assertEquals(1, service.onDestroyCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
        assertEquals(listOf("unbindDestroyed"), idleStops)
    }

    @Test
    fun `start returns create failure without caching`() {
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> error("boom") },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records
        )

        val result = runtime.start(request(startId = 1))

        assertIs<VirtualServiceRuntimeResult.CreateFailed>(result)
        assertEquals(null, records.get("inst-001", "com.test.minimal.SyncService"))
    }

    @Test
    fun `start returns attach failure without caching`() {
        val service = FakeService()
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> error("attach failed") },
            recordManager = records
        )

        val result = runtime.start(request(startId = 1))

        val failed = assertIs<VirtualServiceRuntimeResult.AttachFailed>(result)
        assertSame(service, failed.service)
        assertEquals(0, service.onCreateCalls)
        assertEquals(null, records.get("inst-001", "com.test.minimal.SyncService"))
    }

    @Test
    fun `bind without auto create does not create stopped service`() {
        val service = FakeService()
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records
        )

        val result = runtime.bind(bindRequest(flags = 0))

        val blocked = assertIs<VirtualServiceRuntimeBindResult.NotCreated>(result)
        assertEquals("bindAutoCreateNotRequested", blocked.reason)
        assertEquals(0, blocked.flags)
        assertFalse(blocked.serviceAlreadyRunning)
        assertEquals(0, service.onCreateCalls)
        assertEquals(0, service.onBindCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
    }

    @Test
    fun `bind without auto create attaches to already started service`() {
        val binder = mockk<IBinder>(relaxed = true)
        val service = FakeService(binder = binder)
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records
        )
        runtime.start(request(startId = 1))

        val result = runtime.bind(bindRequest(flags = 0))

        val bound = assertIs<VirtualServiceRuntimeBindResult.Bound>(result)
        assertSame(binder, bound.binder)
        assertEquals(0, bound.flags)
        assertTrue(bound.cached)
        assertEquals(1, service.onCreateCalls)
        assertEquals(1, service.onBindCalls)
        assertEquals(1, records.get("inst-001", "com.test.minimal.SyncService")?.activeBindCount)
    }

    @Test
    fun `bind creates service records binder and unbind destroys bind-only service`() {
        val binder = mockk<IBinder>(relaxed = true)
        val service = FakeService(binder = binder)
        val records = VirtualServiceRecordManager()
        val idleStops = mutableListOf<String>()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records,
            hostServiceIdleStopper = HostServiceIdleStopper { _, reason ->
                idleStops += reason
                HostServiceIdleStopResult(
                    idleStopRequested = true,
                    idleStopReason = reason,
                    hostStopServiceReturnValue = true,
                    detail = "testStopper"
                )
            },
            clock = { 100L }
        )

        val bound = runtime.bind(bindRequest(flags = Context.BIND_AUTO_CREATE))
        val unbound = runtime.unbind(unbindRequest())

        val bindResult = assertIs<VirtualServiceRuntimeBindResult.Bound>(bound)
        assertSame(service, bindResult.service)
        assertSame(binder, bindResult.binder)
        assertEquals(1, service.onCreateCalls)
        assertEquals(1, service.onBindCalls)
        val unbindResult = assertIs<VirtualServiceRuntimeUnbindResult.Unbound>(unbound)
        assertEquals(false, unbindResult.onUnbindResult)
        assertEquals(true, unbindResult.destroyed)
        assertTrue(unbindResult.idleStopResult.idleStopRequested)
        assertEquals("unbindDestroyed", unbindResult.idleStopResult.idleStopReason)
        assertEquals(true, unbindResult.idleStopResult.hostStopServiceReturnValue)
        assertEquals(1, service.onUnbindCalls)
        assertEquals(1, service.onDestroyCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
        assertEquals(listOf("unbindDestroyed"), idleStops)
    }

    @Test
    fun `bind reuses active binder for same intent and calls onUnbind after last connection`() {
        val binder = mockk<IBinder>(relaxed = true)
        val service = FakeService(binder = binder)
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records,
            clock = { 100L }
        )

        val first = assertIs<VirtualServiceRuntimeBindResult.Bound>(
            runtime.bind(bindRequest(flags = Context.BIND_AUTO_CREATE))
        )
        val second = assertIs<VirtualServiceRuntimeBindResult.Bound>(runtime.bind(bindRequest(flags = 2)))
        val firstUnbind = assertIs<VirtualServiceRuntimeUnbindResult.Unbound>(runtime.unbind(unbindRequest()))
        val onUnbindCallsAfterFirstUnbind = service.onUnbindCalls
        val secondUnbind = assertIs<VirtualServiceRuntimeUnbindResult.Unbound>(runtime.unbind(unbindRequest()))

        assertSame(binder, first.binder)
        assertSame(binder, second.binder)
        assertFalse(first.reusedBinder)
        assertTrue(second.reusedBinder)
        assertEquals(1, service.onBindCalls)
        assertEquals(
            0,
            onUnbindCallsAfterFirstUnbind,
            "onUnbind should wait until all active connections are released"
        )
        assertFalse(firstUnbind.destroyed)
        assertFalse(firstUnbind.onUnbindCalled)
        assertEquals(1, firstUnbind.activeConnectionCount)
        assertEquals(1, firstUnbind.activeBindCount)
        assertTrue(secondUnbind.destroyed)
        assertTrue(secondUnbind.onUnbindCalled)
        assertEquals(1, service.onUnbindCalls)
        assertNull(records.get("inst-001", "com.test.minimal.SyncService"))
    }

    @Test
    fun `bind delivers onRebind when previous onUnbind requested it`() {
        val binder = mockk<IBinder>(relaxed = true)
        val service = FakeService(binder = binder, onUnbindReturnValue = true)
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records,
            clock = { 100L }
        )
        runtime.start(request(startId = 1))
        runtime.bind(bindRequest())
        val unbound = assertIs<VirtualServiceRuntimeUnbindResult.Unbound>(runtime.unbind(unbindRequest()))

        val rebound = assertIs<VirtualServiceRuntimeBindResult.Bound>(runtime.bind(bindRequest()))

        assertFalse(unbound.destroyed)
        assertTrue(unbound.onUnbindResult)
        assertTrue(rebound.rebindDelivered)
        assertTrue(rebound.reusedBinder)
        assertSame(binder, rebound.binder)
        assertEquals(1, service.onBindCalls)
        assertEquals(1, service.onRebindCalls)
        assertEquals(1, service.onUnbindCalls)
    }

    @Test
    fun `stop calls onDestroy removes record and returns not found once stopped`() {
        val service = FakeService()
        val records = VirtualServiceRecordManager()
        val idleStops = mutableListOf<String>()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records,
            hostServiceIdleStopper = HostServiceIdleStopper { _, reason ->
                idleStops += reason
                HostServiceIdleStopResult(
                    idleStopRequested = true,
                    idleStopReason = reason,
                    hostStopServiceReturnValue = true,
                    detail = "testStopper"
                )
            }
        )
        runtime.start(request(startId = 1))

        val first = runtime.stop(stopRequest())
        val second = runtime.stop(stopRequest())

        val stopped = assertIs<VirtualServiceRuntimeStopResult.Stopped>(first)
        assertSame(service, stopped.service)
        assertTrue(stopped.idleStopResult.idleStopRequested)
        assertEquals("stopServiceDestroyed", stopped.idleStopResult.idleStopReason)
        assertEquals(true, stopped.idleStopResult.hostStopServiceReturnValue)
        assertEquals(1, service.onDestroyCalls)
        assertEquals(null, records.get("inst-001", "com.test.minimal.SyncService"))
        assertIs<VirtualServiceRuntimeStopResult.NotFound>(second)
        assertEquals(1, service.onDestroyCalls)
        assertEquals(listOf("stopServiceDestroyed"), idleStops)
    }

    @Test
    fun `stop returns onDestroy failure and keeps record`() {
        val service = FakeService(failOnDestroy = true)
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records
        )
        runtime.start(request(startId = 1))

        val result = runtime.stop(stopRequest())

        val failed = assertIs<VirtualServiceRuntimeStopResult.OnDestroyFailed>(result)
        assertSame(service, failed.service)
        assertEquals("destroy failed", failed.error.message)
        assertEquals(1, service.onDestroyCalls)
        assertSame(service, records.get("inst-001", "com.test.minimal.SyncService")?.service)
    }

    private fun request(startId: Int): VirtualServiceRuntimeStartRequest {
        val snapshot = snapshot()
        val startRequest = VirtualServiceStartRequest(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            guestServiceClassName = "com.test.minimal.SyncService",
            sourceIntent = mockk(relaxed = true),
            reason = "explicit"
        )
        val config = VirtualContextConfig(
            instanceId = snapshot.instanceId,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            dataDir = snapshot.dataDir,
            sourceDir = snapshot.sourceDir,
            nativeLibraryDir = snapshot.nativeLibraryDir,
            classLoader = ClassLoader.getSystemClassLoader(),
            applicationLabel = snapshot.applicationLabel,
            packageSnapshot = snapshot
        )
        return VirtualServiceRuntimeStartRequest(
            startRequest = startRequest,
            guestContext = mockk<Context>(relaxed = true),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            guestApplication = null,
            config = config,
            flags = 0,
            startId = startId
        )
    }

    private fun bindRequest(flags: Int = 0): VirtualServiceRuntimeBindRequest {
        val start = request(startId = 1)
        return VirtualServiceRuntimeBindRequest(
            startRequest = start.startRequest,
            guestContext = start.guestContext,
            guestClassLoader = start.guestClassLoader,
            guestApplication = start.guestApplication,
            config = start.config,
            flags = flags
        )
    }

    private fun unbindRequest(): VirtualServiceRuntimeUnbindRequest =
        VirtualServiceRuntimeUnbindRequest(bindRequest().startRequest)

    private fun stopRequest(): VirtualServiceStopRequest = VirtualServiceStopRequest(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestServiceClassName = "com.test.minimal.SyncService",
        sourceIntent = mockk(relaxed = true),
        reason = "explicitStop"
    )

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
        dataDir = "/data/inst"
    )

    private class FakeService(
        private val failOnDestroy: Boolean = false,
        private val binder: IBinder? = null,
        private val onUnbindReturnValue: Boolean = false,
        private val onStartCommandAction: () -> Unit = {}
    ) : Service() {
        var onCreateCalls = 0
        var onBindCalls = 0
        var onUnbindCalls = 0
        var onRebindCalls = 0
        var onDestroyCalls = 0
        val startIds = mutableListOf<Int>()

        override fun onCreate() {
            onCreateCalls += 1
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            startIds += startId
            onStartCommandAction()
            return START_NOT_STICKY
        }

        override fun onDestroy() {
            onDestroyCalls += 1
            if (failOnDestroy) error("destroy failed")
        }

        override fun onBind(intent: Intent?): IBinder? {
            onBindCalls += 1
            return binder
        }

        override fun onUnbind(intent: Intent?): Boolean {
            onUnbindCalls += 1
            return onUnbindReturnValue
        }

        override fun onRebind(intent: Intent?) {
            onRebindCalls += 1
        }
    }
}
