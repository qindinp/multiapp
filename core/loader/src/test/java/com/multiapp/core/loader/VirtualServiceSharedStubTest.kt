package com.multiapp.core.loader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualServiceSharedStubTest {

    @Test
    fun `shared proxy stub stops only after the last started guest service exits`() {
        val firstService = CountingService()
        val secondService = CountingService()
        val records = VirtualServiceRecordManager()
        val idleStops = mutableListOf<VirtualServiceStartRequest>()
        val runtime = runtime(
            services = mapOf(FIRST_SERVICE to firstService, SECOND_SERVICE to secondService),
            records = records,
            idleStops = idleStops
        )
        runtime.start(startRequest(FIRST_SERVICE, startId = 1))
        runtime.start(startRequest(SECOND_SERVICE, startId = 2))

        val firstStop = assertIs<VirtualServiceRuntimeStopResult.Stopped>(runtime.stop(stopRequest(FIRST_SERVICE)))

        assertTrue(firstStop.destroyed)
        assertFalse(firstStop.idleStopResult.idleStopRequested)
        assertEquals("proxyStubStillActive", firstStop.idleStopResult.detail)
        assertEquals(0, idleStops.size)
        assertEquals(1, firstService.onDestroyCalls.get())
        assertTrue(records.get(INSTANCE_ID, SECOND_SERVICE)?.started == true)

        val lastStop = assertIs<VirtualServiceRuntimeStopResult.Stopped>(runtime.stop(stopRequest(SECOND_SERVICE)))

        assertTrue(lastStop.destroyed)
        assertTrue(lastStop.idleStopResult.idleStopRequested)
        assertEquals(1, idleStops.size)
        assertEquals(PROCESS_SLOT, idleStops.single().processSlot)
        assertEquals(1, secondService.onDestroyCalls.get())
    }

    @Test
    fun `concurrent stop and unbind of started bound service destroy and stop shared stub once`() {
        val service = CountingService()
        val records = VirtualServiceRecordManager()
        val idleStops = mutableListOf<VirtualServiceStartRequest>()
        val runtime = runtime(mapOf(FIRST_SERVICE to service), records, idleStops)
        runtime.start(startRequest(FIRST_SERVICE, startId = 1))
        runtime.bind(bindRequest(FIRST_SERVICE))
        val unbindRequest = VirtualServiceRuntimeUnbindRequest(startRequest(FIRST_SERVICE, 1).startRequest)
        val stopRequest = stopRequest(FIRST_SERVICE)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val stopFuture = executor.submit<Any> {
                ready.countDown()
                go.await()
                runtime.stop(stopRequest)
            }
            val unbindFuture = executor.submit<Any> {
                ready.countDown()
                go.await()
                runtime.unbind(unbindRequest)
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()

            val stopResult = assertIs<VirtualServiceRuntimeStopResult.Stopped>(stopFuture.get(5, TimeUnit.SECONDS))
            val unbindResult = assertIs<VirtualServiceRuntimeUnbindResult.Unbound>(
                unbindFuture.get(5, TimeUnit.SECONDS)
            )

            assertTrue(stopResult.destroyed || unbindResult.destroyed)
            assertFalse(stopResult.destroyed && unbindResult.destroyed)
            assertEquals(1, service.onUnbindCalls.get())
            assertEquals(1, service.onDestroyCalls.get())
            assertEquals(1, idleStops.size)
            assertNull(records.get(INSTANCE_ID, FIRST_SERVICE))
            assertIs<VirtualServiceRuntimeStopResult.NotFound>(runtime.stop(stopRequest))
            assertIs<VirtualServiceRuntimeUnbindResult.NotFound>(runtime.unbind(unbindRequest))
            assertEquals(1, service.onDestroyCalls.get())
            assertEquals(1, idleStops.size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun runtime(
        services: Map<String, CountingService>,
        records: VirtualServiceRecordManager,
        idleStops: MutableList<VirtualServiceStartRequest>
    ) = VirtualServiceRuntime(
        serviceFactory = ServiceFactory { _, className -> services.getValue(className) },
        serviceAttacher = ServiceAttacher { _, _, _, _ -> },
        recordManager = records,
        hostServiceIdleStopper = HostServiceIdleStopper { request, reason ->
            idleStops += request
            HostServiceIdleStopResult(
                idleStopRequested = true,
                idleStopReason = reason,
                hostStopServiceReturnValue = true,
                detail = "testStopper"
            )
        }
    )

    private fun startRequest(className: String, startId: Int): VirtualServiceRuntimeStartRequest {
        val snapshot = snapshot()
        val startRequest = VirtualServiceStartRequest(
            instanceId = INSTANCE_ID,
            originPackageName = ORIGIN_PACKAGE,
            guestServiceClassName = className,
            sourceIntent = mockk(relaxed = true),
            reason = "explicit",
            processSlot = PROCESS_SLOT
        )
        return VirtualServiceRuntimeStartRequest(
            startRequest = startRequest,
            guestContext = mockk<Context>(relaxed = true),
            guestClassLoader = ClassLoader.getSystemClassLoader(),
            guestApplication = null,
            config = VirtualContextConfig(
                instanceId = snapshot.instanceId,
                originPackageName = snapshot.originPackageName,
                virtualPackageName = snapshot.virtualPackageName,
                dataDir = snapshot.dataDir,
                sourceDir = snapshot.sourceDir,
                nativeLibraryDir = snapshot.nativeLibraryDir,
                classLoader = ClassLoader.getSystemClassLoader(),
                applicationLabel = snapshot.applicationLabel,
                packageSnapshot = snapshot,
                processSlot = PROCESS_SLOT
            ),
            flags = 0,
            startId = startId
        )
    }

    private fun bindRequest(className: String): VirtualServiceRuntimeBindRequest {
        val start = startRequest(className, 1)
        return VirtualServiceRuntimeBindRequest(
            startRequest = start.startRequest,
            guestContext = start.guestContext,
            guestClassLoader = start.guestClassLoader,
            guestApplication = start.guestApplication,
            config = start.config,
            flags = 0
        )
    }

    private fun stopRequest(className: String) = VirtualServiceStopRequest(
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        guestServiceClassName = className,
        sourceIntent = mockk<Intent>(relaxed = true),
        reason = "explicitStop"
    )

    private fun snapshot() = VirtualPackageSnapshot(
        instanceId = INSTANCE_ID,
        originPackageName = ORIGIN_PACKAGE,
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/minimal.apk",
        dataDir = "/data/inst"
    )

    private class CountingService : Service() {
        val onUnbindCalls = AtomicInteger()
        val onDestroyCalls = AtomicInteger()
        private val binder = mockk<IBinder>(relaxed = true)

        override fun onCreate() = Unit

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

        override fun onBind(intent: Intent?): IBinder = binder

        override fun onUnbind(intent: Intent?): Boolean {
            onUnbindCalls.incrementAndGet()
            return false
        }

        override fun onDestroy() {
            onDestroyCalls.incrementAndGet()
        }
    }

    private companion object {
        const val INSTANCE_ID = "inst-001"
        const val ORIGIN_PACKAGE = "com.test.minimal"
        const val PROCESS_SLOT = "com.multiapp.app:v3"
        const val FIRST_SERVICE = "com.test.minimal.FirstService"
        const val SECOND_SERVICE = "com.test.minimal.SecondService"
    }
}
