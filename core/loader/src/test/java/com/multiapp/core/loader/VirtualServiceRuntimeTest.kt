package com.multiapp.core.loader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

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
        assertEquals(1, service.onCreateCalls)
        assertEquals(listOf(1, 2), service.startIds)
        assertEquals(listOf("com.test.minimal.SyncService"), attached)

        val record = records.get("inst-001", "com.test.minimal.SyncService")!!
        assertEquals(2, record.startCount)
        assertEquals(2, record.lastStartId)
        assertEquals(Service.START_NOT_STICKY, record.lastStartCommandResult)
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
    fun `stop calls onDestroy removes record and returns not found once stopped`() {
        val service = FakeService()
        val records = VirtualServiceRecordManager()
        val runtime = VirtualServiceRuntime(
            serviceFactory = ServiceFactory { _, _ -> service },
            serviceAttacher = ServiceAttacher { _, _, _, _ -> },
            recordManager = records
        )
        runtime.start(request(startId = 1))

        val first = runtime.stop(stopRequest())
        val second = runtime.stop(stopRequest())

        val stopped = assertIs<VirtualServiceRuntimeStopResult.Stopped>(first)
        assertSame(service, stopped.service)
        assertEquals(1, service.onDestroyCalls)
        assertEquals(null, records.get("inst-001", "com.test.minimal.SyncService"))
        assertIs<VirtualServiceRuntimeStopResult.NotFound>(second)
        assertEquals(1, service.onDestroyCalls)
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
        private val failOnDestroy: Boolean = false
    ) : Service() {
        var onCreateCalls = 0
        var onDestroyCalls = 0
        val startIds = mutableListOf<Int>()

        override fun onCreate() {
            onCreateCalls += 1
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            startIds += startId
            return START_NOT_STICKY
        }

        override fun onDestroy() {
            onDestroyCalls += 1
            if (failOnDestroy) error("destroy failed")
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }
}
