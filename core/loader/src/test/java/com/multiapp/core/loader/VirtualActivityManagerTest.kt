package com.multiapp.core.loader

import android.content.Context
import android.content.Intent
import com.multiapp.core.model.virtual.ProxyActivityRegistry
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualActivityState
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualActivityManagerTest {

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

        val spec = manager.createProxyLaunchSpec(record)

        assertEquals("com.multiapp.app", spec.hostPackageName)
        assertEquals("com.multiapp.app.container.ProxyActivity0", spec.proxyActivityClassName)
        assertEquals(record.token, spec.token)
        assertEquals("inst-001", spec.instanceId)
        assertEquals("com.test.minimal", spec.originPackageName)
        assertEquals("com.test.minimal.MainActivity", spec.guestActivityClassName)
        assertEquals(null, spec.launchMode)
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
        assertEquals("com.test.minimal", record.taskAffinity)
        assertSame(record, recordManager.resolve(record.token))
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
    fun `clearTop launch reuses existing activity and unregisters cleared records`() {
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

        assertEquals(detail.token, relaunched.token)
        assertTrue(evidence?.reused == true)
        assertEquals(listOf(settings.token), evidence?.clearedActivities?.map { it.token })
        assertEquals(VirtualActivityStack.FLAG_ACTIVITY_CLEAR_TOP, relaunched.intentFlags)
        assertEquals(root.token, recordManager.resolve(root.token)?.token)
        assertEquals(detail.token, recordManager.resolve(detail.token)?.token)
        assertEquals(VirtualActivityState.FINISHED, recordManager.resolve(settings.token)?.state)
        assertTrue(recordManager.resolveByProxy(settings.proxyActivityClassName)?.token != settings.token)
        assertEquals(listOf(root.token, detail.token), recordManager.listTasks().single().activities.map { it.token })
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
}
