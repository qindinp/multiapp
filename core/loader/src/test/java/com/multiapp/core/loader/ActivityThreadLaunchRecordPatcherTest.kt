package com.multiapp.core.loader

import android.content.Intent
import android.content.pm.ActivityInfo
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivityThreadLaunchRecordPatcherTest {

    @AfterTest
    fun tearDown() {
        VirtualActivityIntentStore.clearAll()
        VirtualActivityIntentStore.resetIntentCopierForTest()
        VirtualPackageRegistry.global.clear()
        VirtualProcessRuntime.global.clearAll()
    }

    @Test
    fun `patchLaunchRecord rewrites legacy ActivityClientRecord before attach`() {
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        val proxyIntent = proxyIntent(token = "token-001")
        val originalGuestIntent = mockk<Intent>(relaxed = true)
        VirtualActivityIntentStore.remember("token-001", originalGuestIntent)
        val record = FakeActivityClientRecord().apply {
            intent = proxyIntent
            activityInfo = ActivityInfo().apply {
                packageName = "com.multiapp.app"
                name = "com.multiapp.app.container.ProxyActivity0"
            }
        }

        val result = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(record)

        assertTrue(result.observedProxyLaunch)
        assertTrue("intent" in result.patchedFields)
        assertTrue("activityInfo" in result.patchedFields)
        assertSame(originalGuestIntent, record.intent)
        assertEquals("com.test.minimal", record.activityInfo?.packageName)
        assertEquals("com.test.minimal.MainActivity", record.activityInfo?.name)
    }

    @Test
    fun `patchMessageObject rewrites LaunchActivityItem inside client transaction`() {
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        val proxyIntent = proxyIntent(token = "token-002")
        val originalGuestIntent = mockk<Intent>(relaxed = true)
        VirtualActivityIntentStore.remember("token-002", originalGuestIntent)
        val launchItem = FakeLaunchActivityItem().apply {
            mIntent = proxyIntent
            mInfo = ActivityInfo().apply {
                packageName = "com.multiapp.app"
                name = "com.multiapp.app.container.ProxyActivity1"
            }
        }
        val transaction = FakeClientTransaction(listOf(launchItem))

        val result = ActivityThreadLaunchRecordPatcher.patchMessageObject(transaction)

        assertTrue(result.observedProxyLaunch)
        assertEquals(1, result.patchedRecordCount)
        assertTrue("mIntent" in result.patchedFields)
        assertTrue("mInfo" in result.patchedFields)
        assertSame(originalGuestIntent, launchItem.mIntent)
        assertEquals("com.test.minimal", launchItem.mInfo?.packageName)
        assertEquals("com.test.minimal.MainActivity", launchItem.mInfo?.name)
    }

    @Test
    fun `patchMessageObject rewrites LaunchActivityItem from transaction items field`() {
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        val proxyIntent = proxyIntent(token = "token-003")
        val originalGuestIntent = mockk<Intent>(relaxed = true)
        VirtualActivityIntentStore.remember("token-003", originalGuestIntent)
        val launchItem = FakeLaunchActivityItem().apply {
            mIntent = proxyIntent
            mInfo = ActivityInfo().apply {
                packageName = "com.multiapp.app"
                name = "com.multiapp.app.container.ProxyActivity2"
            }
        }
        val transaction = FakeClientTransactionItems(listOf(launchItem))

        val result = ActivityThreadLaunchRecordPatcher.patchMessageObject(transaction)

        assertTrue(result.observedProxyLaunch)
        assertEquals(1, result.patchedRecordCount)
        assertSame(originalGuestIntent, launchItem.mIntent)
        assertEquals("com.test.minimal", launchItem.mInfo?.packageName)
        assertEquals("com.test.minimal.MainActivity", launchItem.mInfo?.name)
    }

    @Test
    fun `launchRecordVerdict is partial when LoadedApk evidence is missing`() {
        val result = ActivityThreadLaunchRecordPatchResult(
            patchedFields = listOf("intent", "activityInfo"),
            loadedApkSource = null
        )

        assertEquals("PARTIAL", ActivityThreadLaunchRecordPatcher.launchRecordVerdict(result))
    }

    @Test
    fun `launchRecordVerdict passes only when launch identity and LoadedApk evidence are present`() {
        val result = ActivityThreadLaunchRecordPatchResult(
            patchedFields = listOf("mIntent", "mInfo"),
            loadedApkSource = "GUEST_SANDBOX"
        )

        assertEquals("PASS", ActivityThreadLaunchRecordPatcher.launchRecordVerdict(result))
    }

    private fun proxyIntent(token: String): Intent =
        mockk(relaxed = true) {
            every { getStringExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN) } returns token
            every { getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID) } returns "inst-001"
            every { getStringExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns "com.test.minimal"
            every { getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME) } returns "com.test.minimal.MainActivity"
            every { getStringExtra(VirtualActivityManager.EXTRA_HOST_PACKAGE_NAME) } returns "com.multiapp.app"
            every { getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_LAUNCH_MODE) } returns null
            every { getStringExtra(VirtualActivityManager.EXTRA_GUEST_TASK_AFFINITY) } returns null
        }

    @Suppress("unused")
    private class FakeActivityClientRecord {
        var intent: Intent? = null
        var activityInfo: ActivityInfo? = null
        var packageInfo: Any? = null
    }

    @Suppress("unused")
    private class FakeLaunchActivityItem {
        var mIntent: Intent? = null
        var mInfo: ActivityInfo? = null
    }

    @Suppress("unused")
    private class FakeClientTransaction(
        private val mActivityCallbacks: List<Any>
    )

    @Suppress("unused")
    private class FakeClientTransactionItems(
        private val mTransactionItems: List<Any>
    )
}
