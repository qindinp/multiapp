package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualActivityState
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualActivityManagerTest {

    @AfterTest
    fun tearDown() {
        VirtualActivityIntentStore.clearAll()
        VirtualActivityIntentStore.resetIntentCopierForTest()
    }

    @Test
    fun `original guest intent extra key is stable`() {
        assertEquals("multiapp.originalGuestIntent", VirtualActivityManager.EXTRA_ORIGINAL_GUEST_INTENT)
    }

    @Test
    fun `createProxyLaunchSpec maps virtual activity record into proxy launch data`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))
        val manager = VirtualActivityManager(context, registry)
        val record = registry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            nowMs = 1000L
        )

        val launchIdentity = VirtualActivityLaunchIdentity(
            capabilityToken = "capability-42",
            instanceId = "inst-001",
            runtimeEpoch = 42L,
            engineSessionId = "engine-session-42",
            processSlot = "com.multiapp.app:v0",
            proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
            guestActivityClassName = "com.test.minimal.MainActivity"
        )
        val spec = manager.createProxyLaunchSpec(record, launchIdentity)

        assertEquals("com.multiapp.app", spec.hostPackageName)
        assertEquals("com.multiapp.app.container.ProxyActivity0", spec.proxyActivityClassName)
        assertEquals(record.token, spec.token)
        assertEquals("inst-001", spec.instanceId)
        assertEquals("com.test.minimal", spec.originPackageName)
        assertEquals("com.test.minimal.MainActivity", spec.guestActivityClassName)
        assertEquals(null, spec.launchMode)
        assertEquals(null, spec.taskAffinity)
        assertSame(launchIdentity, spec.engineLaunchIdentity)
    }

    @Test
    fun `registry allocation provides proxy record for launcher`() {
        val registry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))

        val record = registry.allocate(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity"
        )

        assertEquals("inst-001", record.instanceId)
        assertEquals("com.multiapp.app.container.ProxyActivity0", record.proxyActivityClassName)
    }

    @Test
    fun `allocateGuestActivity maps resolved request to proxy record`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))
        val recordManager = VirtualActivityRecordManager()
        val manager = VirtualActivityManager(context, registry, activityRecordManager = recordManager)
        val request = VirtualActivityLaunchRequest(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.SecondActivity",
            sourceIntent = intentWithFlags(),
            reason = "explicit"
        )

        val record = manager.allocateGuestActivity(request)

        assertEquals("inst-001", record.instanceId)
        assertEquals("com.test.minimal", record.originPackageName)
        assertEquals("com.test.minimal.SecondActivity", record.guestActivityClassName)
        assertEquals("com.multiapp.app.container.ProxyActivity0", record.proxyActivityClassName)
        assertEquals(1, record.taskId)
        assertEquals(0, record.intentFlags)
        assertEquals(VirtualActivityState.RESUMED, record.state)
        assertEquals("com.test.minimal:inst-001", record.taskAffinity)
        assertSame(record, recordManager.resolve(record.token))
    }

    @Test
    fun `allocateGuestActivity preserves activity result route metadata`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))
        val recordManager = VirtualActivityRecordManager()
        val manager = VirtualActivityManager(context, registry, activityRecordManager = recordManager)
        val request = VirtualActivityLaunchRequest(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.SecondActivity",
            sourceIntent = intentWithFlags(),
            reason = "explicit",
            resultToToken = "token-parent",
            resultRequestCode = 42
        )

        val record = manager.allocateGuestActivity(request)

        assertEquals("token-parent", record.resultToToken)
        assertEquals(42, record.resultRequestCode)
        assertEquals("token-parent", recordManager.resolve(record.token)?.resultToToken)
        assertEquals(42, recordManager.resolve(record.token)?.resultRequestCode)
    }

    @Test
    fun `same origin package instances use instance scoped task affinity`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(
            listOf(
                "com.multiapp.app.container.ProxyActivity0",
                "com.multiapp.app.container.ProxyActivity1"
            )
        )
        val recordManager = VirtualActivityRecordManager()
        val manager = VirtualActivityManager(context, registry, activityRecordManager = recordManager)

        val first = manager.allocateGuestActivity(
            VirtualActivityLaunchRequest(
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                guestActivityClassName = "com.test.minimal.MainActivity",
                sourceIntent = intentWithFlags(VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK),
                reason = "launcher"
            )
        )
        val second = manager.allocateGuestActivity(
            VirtualActivityLaunchRequest(
                instanceId = "inst-002",
                originPackageName = "com.test.minimal",
                guestActivityClassName = "com.test.minimal.MainActivity",
                sourceIntent = intentWithFlags(VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK),
                reason = "launcher"
            )
        )

        assertNotEquals(first.taskId, second.taskId)
        assertEquals("com.test.minimal:inst-001", first.taskAffinity)
        assertEquals("com.test.minimal:inst-002", second.taskAffinity)
        assertEquals(
            listOf("com.test.minimal:inst-001", "com.test.minimal:inst-002"),
            recordManager.listTasks().map { it.affinity }
        )
    }

    @Test
    fun `allocateGuestActivity preserves launch mode in record and proxy spec`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(
            listOf(
                "com.multiapp.app.container.ProxyActivity0",
                "com.multiapp.app.container.ProxyActivitySingleTop0"
            ),
            mapOf(
                "com.multiapp.app.container.ProxyActivity0" to null,
                "com.multiapp.app.container.ProxyActivitySingleTop0" to "singleTop"
            )
        )
        val manager = VirtualActivityManager(context, registry)
        val request = VirtualActivityLaunchRequest(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.SecondActivity",
            sourceIntent = intentWithFlags(),
            reason = "explicit",
            launchMode = "singleTop"
        )

        val record = manager.allocateGuestActivity(request)
        val spec = manager.createProxyLaunchSpec(record)

        assertEquals("singleTop", record.launchMode)
        assertEquals("com.multiapp.app.container.ProxyActivitySingleTop0", record.proxyActivityClassName)
        assertEquals("singleTop", spec.launchMode)
        assertEquals("com.test.minimal:inst-001", spec.taskAffinity)
    }

    @Test
    fun `singleTop launch reuses top activity and exposes launch evidence`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(
            listOf("com.multiapp.app.container.ProxyActivitySingleTop0"),
            mapOf("com.multiapp.app.container.ProxyActivitySingleTop0" to "singleTop")
        )
        val recordManager = VirtualActivityRecordManager()
        val manager = VirtualActivityManager(context, registry, activityRecordManager = recordManager)
        val request = VirtualActivityLaunchRequest(
            instanceId = "inst-001",
            originPackageName = "com.test.minimal",
            guestActivityClassName = "com.test.minimal.MainActivity",
            sourceIntent = intentWithFlags(),
            reason = "explicit",
            launchMode = "singleTop"
        )

        val first = manager.allocateGuestActivity(request)
        val second = manager.allocateGuestActivity(request)
        val evidence = recordManager.lastLaunchResult()

        assertEquals(first.token, second.token)
        assertTrue(evidence?.reused == true)
        assertEquals(first.token, evidence?.activity?.token)
        assertEquals(1, recordManager.listTasks().single().activities.size)
    }

    @Test
    fun `standard clearTop launch recreates target and unregisters cleared records`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))
        val recordManager = VirtualActivityRecordManager()
        val manager = VirtualActivityManager(context, registry, activityRecordManager = recordManager)
        val root = manager.allocateGuestActivity(request("com.test.minimal.RootActivity"))
        val detail = manager.allocateGuestActivity(request("com.test.minimal.DetailActivity"))
        val settings = manager.allocateGuestActivity(request("com.test.minimal.SettingsActivity"))

        val relaunched = manager.allocateGuestActivity(
            request(
                guestActivityClassName = "com.test.minimal.DetailActivity",
                sourceIntent = intentWithFlags(VirtualActivityStack.FLAG_ACTIVITY_CLEAR_TOP)
            )
        )
        val evidence = recordManager.lastLaunchResult()

        assertNotEquals(detail.token, relaunched.token)
        assertFalse(evidence?.reused == true)
        assertEquals(
            listOf(detail.token, settings.token),
            evidence?.clearedActivities?.map { it.token }
        )
        assertEquals(VirtualActivityStack.FLAG_ACTIVITY_CLEAR_TOP, relaunched.intentFlags)
        assertEquals(root.token, recordManager.resolve(root.token)?.token)
        assertEquals(VirtualActivityState.FINISHED, recordManager.resolve(detail.token)?.state)
        assertEquals(VirtualActivityState.FINISHED, recordManager.resolve(settings.token)?.state)
        assertTrue(recordManager.resolveByProxy(settings.proxyActivityClassName)?.token != settings.token)
        assertEquals(
            listOf(root.token, relaunched.token),
            recordManager.listTasks().single().activities.map { it.token }
        )
        assertNull(evidence?.pendingNewIntent)
    }

    @Test
    fun `reused activity snapshots redact sensitive data uri`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(
            listOf("com.multiapp.app.container.ProxyActivitySingleTop0"),
            mapOf("com.multiapp.app.container.ProxyActivitySingleTop0" to "singleTop")
        )
        val recordManager = VirtualActivityRecordManager()
        val manager = VirtualActivityManager(context, registry, activityRecordManager = recordManager)
        val firstRequest = request(
            guestActivityClassName = "com.test.minimal.MainActivity",
            sourceIntent = intentWithData("https://example.com/start?token=first"),
            launchMode = "singleTop"
        )
        val secondRequest = request(
            guestActivityClassName = "com.test.minimal.MainActivity",
            sourceIntent = intentWithData("https://user:pass@example.com/private/path;token=second?password=secret#fragment"),
            launchMode = "singleTop"
        )

        manager.allocateGuestActivity(firstRequest)
        manager.allocateGuestActivity(secondRequest)
        val pending = recordManager.lastLaunchResult()?.pendingNewIntent?.dataIntent

        assertEquals("https://example.com/<redacted>", pending?.dataUri)
        listOf("user:pass", "private", "token=", "password=", "secret", "fragment").forEach { leaked ->
            assertFalse(pending?.dataUri?.contains(leaked) == true, "snapshot leaked $leaked in ${pending?.dataUri}")
        }
    }

    @Test
    fun `activity records and intent store keep raw token for internal routing`() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageName } returns "com.multiapp.app"
        val registry = ProxyActivityRegistry(listOf("com.multiapp.app.container.ProxyActivity0"))
        val recordManager = VirtualActivityRecordManager()
        val manager = VirtualActivityManager(context, registry, activityRecordManager = recordManager)
        val sourceIntent = intentWithFlags()
        val record = manager.allocateGuestActivity(
            VirtualActivityLaunchRequest(
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                guestActivityClassName = "com.test.minimal.MainActivity",
                sourceIntent = sourceIntent,
                reason = "explicit"
            )
        )
        VirtualActivityIntentStore.remember(record.token, sourceIntent)
        val spec = manager.createProxyLaunchSpec(record)

        assertEquals(record.token, spec.token)
        assertTrue(VirtualActivityIntentStore.find(record.token) != null)
        assertSame(record, recordManager.resolve(record.token))
        assertNull(VirtualActivityIntentStore.find("<redacted>"))
        assertNull(recordManager.resolve("<redacted>"))
    }

    private fun request(
        guestActivityClassName: String,
        sourceIntent: Intent = intentWithFlags(),
        launchMode: String? = null
    ) = VirtualActivityLaunchRequest(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        guestActivityClassName = guestActivityClassName,
        sourceIntent = sourceIntent,
        reason = "explicit",
        launchMode = launchMode
    )

    private fun intentWithFlags(flags: Int = 0): Intent = mockk(relaxed = true) {
        every { this@mockk.flags } returns flags
    }

    private fun intentWithData(dataString: String): Intent = mockk(relaxed = true) {
        every { this@mockk.flags } returns 0
        every { this@mockk.dataString } returns dataString
        every { this@mockk.action } returns "com.test.ACTION"
        every { this@mockk.categories } returns emptySet()
        every { this@mockk.extras } returns null
    }
}
