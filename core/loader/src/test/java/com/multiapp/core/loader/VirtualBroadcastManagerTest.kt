package com.multiapp.core.loader

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedIntentFilter
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class VirtualBroadcastManagerTest {

    @Test
    fun `dispatch explicit receiver intent delivers in process`() {
        val receiver = RecordingReceiver()
        val recorder = InMemoryVirtualBroadcastRecorder()
        val runtime = VirtualReceiverRuntime(
            receiverFactory = ReceiverFactory { _, _ -> receiver },
            recorder = recorder
        )
        val manager = VirtualBroadcastManager(runtime = runtime)
        val intent = receiverIntent(
            packageName = "com.multiapp.instance.abc",
            className = ".BootReceiver",
            action = "com.test.ACTION_BOOT"
        )
        val context = mockk<Context>(relaxed = true)

        val result = manager.dispatchExplicit(
            snapshot = snapshot(),
            intent = intent,
            virtualContext = context,
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val delivered = assertIs<VirtualBroadcastResult.Delivered>(result)
        assertSame(receiver, delivered.receiver)
        assertSame(context, receiver.context)
        assertSame(intent, receiver.intent)
        assertEquals("inst-001", delivered.request.instanceId)
        assertEquals("com.test.minimal.BootReceiver", delivered.request.receiverClassName)
        assertEquals("com.test.ACTION_BOOT", delivered.record.action)
        assertEquals(VirtualBroadcastResultCode.Delivered, delivered.record.result)
        assertEquals(listOf(delivered.record), recorder.records())
    }

    @Test
    fun `default runtime records delivered broadcasts through global recorder`() {
        val receiver = RecordingReceiver()
        val records = mutableListOf<VirtualBroadcastRecord>()
        VirtualBroadcastRecorders.install(VirtualBroadcastRecorder { records += it })
        try {
            val runtime = VirtualReceiverRuntime(
                receiverFactory = ReceiverFactory { _, _ -> receiver }
            )
            val manager = VirtualBroadcastManager(runtime = runtime)
            val intent = receiverIntent(
                packageName = "com.multiapp.instance.abc",
                className = ".BootReceiver",
                action = "com.test.ACTION_BOOT"
            )

            val result = manager.dispatchExplicit(
                snapshot = snapshot(),
                intent = intent,
                virtualContext = mockk(relaxed = true),
                receiverClassLoader = ClassLoader.getSystemClassLoader()
            )

            assertIs<VirtualBroadcastResult.Delivered>(result)
            assertEquals(listOf(result.record), records)
        } finally {
            VirtualBroadcastRecorders.reset()
        }
    }

    @Test
    fun `implicit broadcast is unsupported and recorded`() {
        val recorder = InMemoryVirtualBroadcastRecorder()
        val manager = VirtualBroadcastManager(recorder = recorder)
        val intent = mockk<Intent>(relaxed = true)
        every { intent.component } returns null
        every { intent.action } returns "com.test.ACTION_IMPLICIT"

        val result = manager.dispatchExplicit(
            snapshot = snapshot(),
            intent = intent,
            virtualContext = mockk(relaxed = true),
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val unsupported = assertIs<VirtualBroadcastResult.UnsupportedImplicit>(result)
        assertEquals(null, unsupported.record.instanceId)
        assertEquals(null, unsupported.record.receiverClassName)
        assertEquals("com.test.ACTION_IMPLICIT", unsupported.record.action)
        assertEquals(VirtualBroadcastResultCode.UnsupportedImplicit, unsupported.record.result)
        assertEquals(listOf(unsupported.record), recorder.records())
    }

    @Test
    fun `implicit broadcast delivers all matching manifest receivers`() {
        val first = RecordingReceiver()
        val second = RecordingReceiver()
        val receivers = mapOf(
            "com.test.minimal.LegacyReceiver" to first,
            "com.test.minimal.StructuredReceiver" to second
        )
        val recorder = InMemoryVirtualBroadcastRecorder()
        val runtime = VirtualReceiverRuntime(
            receiverFactory = ReceiverFactory { _, className -> receivers.getValue(className) },
            recorder = recorder
        )
        val manager = VirtualBroadcastManager(runtime = runtime)
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { `package` } returns null
            every { action } returns "com.test.ACTION_SYNC"
            every { categories } returns setOf("com.test.CATEGORY")
            every { data } returns null
        }
        val context = mockk<Context>(relaxed = true)

        val result = manager.dispatch(
            instanceId = "inst-001",
            snapshot = snapshot(
                receivers = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.LegacyReceiver",
                        exported = false,
                        intentFilters = listOf("com.test.ACTION_SYNC")
                    ),
                    ResolvedComponent(
                        name = "com.test.minimal.StructuredReceiver",
                        exported = false,
                        resolvedIntentFilters = listOf(
                            ResolvedIntentFilter(
                                actions = listOf("com.test.ACTION_SYNC"),
                                categories = listOf("com.test.CATEGORY")
                            )
                        )
                    )
                )
            ),
            intent = intent,
            virtualContext = context,
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val batch = assertIs<VirtualBroadcastResult.Batch>(result)
        assertEquals(2, batch.results.size)
        assertSame(intent, first.intent)
        assertSame(intent, second.intent)
        assertEquals(
            listOf(
                "com.test.minimal.LegacyReceiver",
                "com.test.minimal.StructuredReceiver"
            ),
            recorder.records().map { it.receiverClassName }
        )
        assertEquals(
            listOf(VirtualBroadcastResultCode.Delivered, VirtualBroadcastResultCode.Delivered),
            recorder.records().map { it.result }
        )
    }

    @Test
    fun `explicit receiver miss is recorded separately from implicit unsupported`() {
        val recorder = InMemoryVirtualBroadcastRecorder()
        val manager = VirtualBroadcastManager(recorder = recorder)
        val intent = receiverIntent(
            packageName = "com.multiapp.instance.abc",
            className = "com.test.minimal.MissingReceiver",
            action = "com.test.ACTION_MISSING"
        )

        val result = manager.dispatchExplicit(
            snapshot = snapshot(),
            intent = intent,
            virtualContext = mockk(relaxed = true),
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val missing = assertIs<VirtualBroadcastResult.ReceiverNotFound>(result)
        assertEquals("inst-001", missing.record.instanceId)
        assertEquals("com.test.minimal.MissingReceiver", missing.record.receiverClassName)
        assertEquals("com.test.ACTION_MISSING", missing.record.action)
        assertEquals(VirtualBroadcastResultCode.ReceiverNotFound, missing.record.result)
        assertEquals(listOf(missing.record), recorder.records())
    }

    @Test
    fun `dispatch delivers dynamic receiver before explicit manifest route`() {
        val receiver = RecordingReceiver()
        val recorder = InMemoryVirtualBroadcastRecorder()
        val registry = VirtualDynamicReceiverRegistry().apply {
            register(
                instanceId = "inst-001",
                receiver = receiver,
                filter = VirtualDynamicReceiverFilter(actions = setOf("com.test.ACTION_DYNAMIC"))
            )
        }
        val manager = VirtualBroadcastManager(recorder = recorder, dynamicReceiverRegistry = registry)
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { action } returns "com.test.ACTION_DYNAMIC"
            every { categories } returns emptySet()
            every { data } returns null
        }
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.instance.abc"

        val result = manager.dispatch(
            instanceId = "inst-001",
            snapshot = snapshot(),
            intent = intent,
            virtualContext = context,
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val delivered = assertIs<VirtualBroadcastResult.Delivered>(result)
        assertSame(receiver, delivered.receiver)
        assertEquals("dynamic", delivered.request.reason)
        assertEquals(VirtualBroadcastResultCode.Delivered, delivered.record.result)
        assertSame(context, receiver.context)
        assertSame(intent, receiver.intent)
    }

    @Test
    fun `dispatch delivers all matching dynamic receivers`() {
        val first = RecordingReceiver()
        val second = RecordingReceiver()
        val recorder = InMemoryVirtualBroadcastRecorder()
        val registry = VirtualDynamicReceiverRegistry().apply {
            register(
                instanceId = "inst-001",
                receiver = first,
                filter = VirtualDynamicReceiverFilter(actions = setOf("com.test.ACTION_DYNAMIC"))
            )
            register(
                instanceId = "inst-001",
                receiver = second,
                filter = VirtualDynamicReceiverFilter(actions = setOf("com.test.ACTION_DYNAMIC"))
            )
        }
        val manager = VirtualBroadcastManager(
            recorder = recorder,
            dynamicReceiverRegistry = registry
        )
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { action } returns "com.test.ACTION_DYNAMIC"
            every { categories } returns emptySet()
            every { data } returns null
        }
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.instance.abc"

        val result = manager.dispatch(
            instanceId = "inst-001",
            snapshot = snapshot(),
            intent = intent,
            virtualContext = context,
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val batch = assertIs<VirtualBroadcastResult.Batch>(result)
        assertEquals(2, batch.results.size)
        assertSame(intent, first.intent)
        assertSame(intent, second.intent)
        assertEquals(2, recorder.records().size)
        assertEquals(
            listOf(VirtualBroadcastResultCode.Delivered, VirtualBroadcastResultCode.Delivered),
            recorder.records().map { it.result }
        )
    }

    @Test
    fun `dispatch implicit broadcast delivers dynamic and manifest receivers`() {
        val dynamic = RecordingReceiver()
        val manifest = RecordingReceiver()
        val recorder = InMemoryVirtualBroadcastRecorder()
        val registry = VirtualDynamicReceiverRegistry().apply {
            register(
                instanceId = "inst-001",
                receiver = dynamic,
                filter = VirtualDynamicReceiverFilter(actions = setOf("com.test.ACTION_BOTH"))
            )
        }
        val runtime = VirtualReceiverRuntime(
            receiverFactory = ReceiverFactory { _, _ -> manifest },
            recorder = recorder
        )
        val manager = VirtualBroadcastManager(
            runtime = runtime,
            recorder = recorder,
            dynamicReceiverRegistry = registry
        )
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { `package` } returns null
            every { action } returns "com.test.ACTION_BOTH"
            every { categories } returns emptySet()
            every { data } returns null
        }
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.instance.abc"

        val result = manager.dispatch(
            instanceId = "inst-001",
            snapshot = snapshot(
                receivers = listOf(
                    ResolvedComponent(
                        name = "com.test.minimal.ManifestReceiver",
                        exported = false,
                        intentFilters = listOf("com.test.ACTION_BOTH")
                    )
                )
            ),
            intent = intent,
            virtualContext = context,
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        val batch = assertIs<VirtualBroadcastResult.Batch>(result)
        assertEquals(2, batch.results.size)
        assertSame(intent, dynamic.intent)
        assertSame(intent, manifest.intent)
        assertEquals(
            listOf(
                dynamic.javaClass.name,
                "com.test.minimal.ManifestReceiver"
            ),
            recorder.records().map { it.receiverClassName }
        )
    }

    @Test
    fun `dispatch falls back to unsupported implicit after dynamic unregister`() {
        val receiver = RecordingReceiver()
        val registry = VirtualDynamicReceiverRegistry().apply {
            register("inst-001", receiver, VirtualDynamicReceiverFilter(actions = setOf("com.test.ACTION_DYNAMIC")))
            unregister(receiver)
        }
        val manager = VirtualBroadcastManager(dynamicReceiverRegistry = registry)
        val intent = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { action } returns "com.test.ACTION_DYNAMIC"
            every { categories } returns emptySet()
            every { data } returns null
        }

        val result = manager.dispatch(
            instanceId = "inst-001",
            snapshot = snapshot(),
            intent = intent,
            virtualContext = mockk(relaxed = true),
            receiverClassLoader = ClassLoader.getSystemClassLoader()
        )

        assertIs<VirtualBroadcastResult.UnsupportedImplicit>(result)
    }

    private fun receiverIntent(packageName: String, className: String, action: String): Intent {
        val component = mockk<ComponentName>(relaxed = true)
        every { component.packageName } returns packageName
        every { component.className } returns className
        return mockk(relaxed = true) {
            every { this@mockk.component } returns component
            every { this@mockk.action } returns action
        }
    }

    private fun snapshot(
        receivers: List<ResolvedComponent> = listOf(
            ResolvedComponent(name = "com.test.minimal.BootReceiver", exported = false)
        )
    ) = VirtualPackageSnapshot(
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
        receivers = receivers
    )

    private class RecordingReceiver : BroadcastReceiver() {
        var context: Context? = null
        var intent: Intent? = null

        override fun onReceive(context: Context, intent: Intent) {
            this.context = context
            this.intent = intent
        }
    }
}
