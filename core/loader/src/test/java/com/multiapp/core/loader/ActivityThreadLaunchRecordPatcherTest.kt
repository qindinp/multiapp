package com.multiapp.core.loader

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
        unmockkObject(ActivityThreadCompat)
    }

    @Test
    fun `patchLaunchRecord keeps proxy record when package snapshot is missing`() {
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
        assertEquals("PACKAGE_SNAPSHOT_MISSING", result.skippedReason)
        assertTrue(result.patchedFields.isEmpty())
        assertSame(proxyIntent, record.intent)
        assertEquals("com.multiapp.app", record.activityInfo?.packageName)
        assertEquals("com.multiapp.app.container.ProxyActivity0", record.activityInfo?.name)
    }

    @Test
    fun `patchMessageObject keeps LaunchActivityItem proxy when package snapshot is missing`() {
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
        assertEquals("PACKAGE_SNAPSHOT_MISSING", result.skippedReason)
        assertEquals(0, result.patchedRecordCount)
        assertTrue(result.patchedFields.isEmpty())
        assertSame(proxyIntent, launchItem.mIntent)
        assertEquals("com.multiapp.app", launchItem.mInfo?.packageName)
        assertEquals("com.multiapp.app.container.ProxyActivity1", launchItem.mInfo?.name)
    }

    @Test
    fun `patchMessageObject keeps transaction items proxy when package snapshot is missing`() {
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
        assertEquals("PACKAGE_SNAPSHOT_MISSING", result.skippedReason)
        assertEquals(0, result.patchedRecordCount)
        assertSame(proxyIntent, launchItem.mIntent)
        assertEquals("com.multiapp.app", launchItem.mInfo?.packageName)
        assertEquals("com.multiapp.app.container.ProxyActivity2", launchItem.mInfo?.name)
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

    @Test
    fun `launch record evidence redacts activity token`(@TempDir filesDir: File) {
        val rawToken = "raw-activity-token-super-secret"
        val hostApplication = mockk<Application>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
        }
        mockkObject(ActivityThreadCompat)
        every { ActivityThreadCompat.currentApplication() } returns hostApplication
        val result = ActivityThreadLaunchRecordPatchResult(
            targetClassName = "android.app.ActivityThread\$ActivityClientRecord",
            observedProxyLaunch = true,
            patchedFields = listOf("intent", "activityInfo"),
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            token = rawToken,
            loadedApkSource = "GUEST_SANDBOX"
        )

        invokePrivateWriteEvidence(result)

        val text = File(
            filesDir,
            "hosted_launch_evidence/${HostedActivityEvidenceFiles.launchRecord("inst-001")}"
        ).readText()
        assertTrue(text.contains("token=<redacted>"))
        assertTrue(!text.contains(rawToken), "launch record evidence leaked raw token in $text")
    }

    private fun invokePrivateWriteEvidence(result: ActivityThreadLaunchRecordPatchResult) {
        val method = ActivityThreadLaunchRecordPatcher::class.java.getDeclaredMethod(
            "writeEvidence",
            ActivityThreadLaunchRecordPatchResult::class.java
        )
        method.isAccessible = true
        method.invoke(ActivityThreadLaunchRecordPatcher, result)
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
