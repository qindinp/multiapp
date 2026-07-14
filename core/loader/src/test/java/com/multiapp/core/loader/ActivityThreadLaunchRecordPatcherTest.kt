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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivityThreadLaunchRecordPatcherTest {

    @BeforeTest
    fun setUp() {
        VirtualPackageRegistry.global.clear()
        VirtualProcessRuntime.global.clearAll()
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                VirtualActivityLaunchAuthorityResult(true, "test_authorized")
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
    }

    @AfterTest
    fun tearDown() {
        VirtualActivityIntentStore.clearAll()
        VirtualActivityIntentStore.resetIntentCopierForTest()
        VirtualPackageRegistry.global.clear()
        VirtualProcessRuntime.global.clearAll()
        VirtualActivityLaunchAuthority.clearForTests()
        VirtualActivityLaunchRecovery.clearForTests()
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
            loadedApkSource = "GUEST_SANDBOX",
            launchAuthorityStatus = "PASS"
        )

        assertEquals("PASS", ActivityThreadLaunchRecordPatcher.launchRecordVerdict(result))
    }

    @Test
    fun `prepatch keeps proxy record when engine launch identity is missing`() {
        val proxyIntent = proxyIntent(token = "token-missing-capability", includeCapability = false)
        val proxyInfo = ActivityInfo().apply {
            packageName = "com.multiapp.app"
            name = "com.multiapp.app.container.ProxyActivity0"
        }
        val proxyLoadedApk = Any()
        val record = FakeActivityClientRecord().apply {
            intent = proxyIntent
            activityInfo = proxyInfo
            packageInfo = proxyLoadedApk
        }

        val result = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(record)

        assertEquals("ENGINE_LAUNCH_IDENTITY_MISSING", result.skippedReason)
        assertEquals("FAIL", result.launchAuthorityStatus)
        assertTrue(result.patchedFields.isEmpty())
        assertSame(proxyIntent, record.intent)
        assertSame(proxyInfo, record.activityInfo)
        assertSame(proxyLoadedApk, record.packageInfo)
        assertEquals("com.multiapp.app.container.ProxyActivity0", record.activityInfo?.name)
    }

    @Test
    fun `rejected capability cannot prepatch guest class and bypass newActivity authority`() {
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                VirtualActivityLaunchAuthorityResult(false, "stale_generation")
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
        val proxyIntent = proxyIntent(token = "token-rejected")
        val proxyInfo = ActivityInfo().apply {
            packageName = "com.multiapp.app"
            name = "com.multiapp.app.container.ProxyActivity1"
        }
        val proxyLoadedApk = Any()
        val record = FakeActivityClientRecord().apply {
            intent = proxyIntent
            activityInfo = proxyInfo
            packageInfo = proxyLoadedApk
        }

        val result = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(record)

        assertEquals("ENGINE_LAUNCH_REJECTED:stale_generation", result.skippedReason)
        assertEquals("FAIL", result.launchAuthorityStatus)
        assertTrue(result.patchedFields.isEmpty())
        assertSame(proxyIntent, record.intent)
        assertSame(proxyInfo, record.activityInfo)
        assertSame(proxyLoadedApk, record.packageInfo)
        assertEquals("com.multiapp.app.container.ProxyActivity1", record.activityInfo?.name)
    }

    @Test
    fun `initial launch authority can be authorized repeatedly before record patch`() {
        var authorizationCalls = 0
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                authorizationCalls += 1
                VirtualActivityLaunchAuthorityResult(true, "idempotent_authorized")
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
        val proxyIntent = proxyIntent(token = "token-repeat")
        val record = FakeActivityClientRecord().apply {
            intent = proxyIntent
            activityInfo = ActivityInfo().apply {
                packageName = "com.multiapp.app"
                name = "com.multiapp.app.container.ProxyActivity2"
            }
        }

        val first = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(record)
        val second = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(record)

        assertEquals(2, authorizationCalls)
        assertEquals("PACKAGE_SNAPSHOT_MISSING", first.skippedReason)
        assertEquals("PACKAGE_SNAPSHOT_MISSING", second.skippedReason)
        assertEquals("PASS", first.launchAuthorityStatus)
        assertEquals("PASS", second.launchAuthorityStatus)
        assertTrue(first.patchedFields.isEmpty())
        assertTrue(second.patchedFields.isEmpty())
    }

    @Test
    fun `stale recents launch recovers fresh capability before patching the same record`() {
        val extras = mutableMapOf<String, Any?>(
            VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN to "activity-root",
            VirtualActivityManager.EXTRA_INSTANCE_ID to "inst-001",
            VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME to "com.test.minimal",
            VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME to "com.test.minimal.OldActivity",
            VirtualActivityManager.EXTRA_HOST_PACKAGE_NAME to "com.multiapp.app",
            VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY to "stale-capability",
            VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH to 7L,
            VirtualActivityManager.EXTRA_ENGINE_SESSION_ID to "stale-session",
            VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT to "com.multiapp.app:v0"
        )
        val proxyIntent = mutableProxyIntent(extras)
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator { identity ->
                VirtualActivityLaunchAuthorityResult(
                    accepted = identity.capabilityToken == "fresh-capability",
                    reason = if (identity.capabilityToken == "fresh-capability") {
                        "fresh_capability_authorized"
                    } else {
                        "stale_generation"
                    }
                )
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
        VirtualActivityLaunchRecovery.install(
            VirtualActivityLaunchRecoveryHandler { request ->
                assertEquals("activity-root", request.restoreActivityId)
                assertEquals(7L, request.previousRuntimeEpoch)
                VirtualActivityLaunchRecoveryResult(
                    recovered = true,
                    identity = VirtualActivityLaunchIdentity(
                        capabilityToken = "fresh-capability",
                        instanceId = request.instanceId,
                        runtimeEpoch = 8L,
                        engineSessionId = "fresh-session",
                        processSlot = request.processSlot,
                        proxyActivityClassName = request.proxyActivityClassName,
                        guestActivityClassName = "com.test.minimal.RestoredActivity"
                    ),
                    reason = "test_recents_recovered"
                )
            }
        )
        val record = FakeActivityClientRecord().apply {
            intent = proxyIntent
            activityInfo = ActivityInfo().apply {
                packageName = "com.multiapp.app"
                name = "com.multiapp.app.container.ProxyActivity0"
            }
        }

        val result = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(record)

        assertEquals("PACKAGE_SNAPSHOT_MISSING", result.skippedReason)
        assertEquals("PASS", result.launchAuthorityStatus)
        assertEquals("PASS", result.launchRecoveryStatus)
        assertEquals("test_recents_recovered", result.launchRecoveryReason)
        assertEquals(8L, extras[VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH])
        assertEquals("fresh-session", extras[VirtualActivityManager.EXTRA_ENGINE_SESSION_ID])
        assertEquals("fresh-capability", extras[VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY])
        assertEquals(
            "com.test.minimal.RestoredActivity",
            extras[VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME]
        )
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

    private fun proxyIntent(token: String, includeCapability: Boolean = true): Intent =
        mockk(relaxed = true) {
            every { getStringExtra(VirtualActivityManager.EXTRA_VIRTUAL_ACTIVITY_TOKEN) } returns token
            every { getStringExtra(VirtualActivityManager.EXTRA_INSTANCE_ID) } returns "inst-001"
            every { getStringExtra(VirtualActivityManager.EXTRA_ORIGIN_PACKAGE_NAME) } returns "com.test.minimal"
            every { getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_CLASS_NAME) } returns "com.test.minimal.MainActivity"
            every { getStringExtra(VirtualActivityManager.EXTRA_HOST_PACKAGE_NAME) } returns "com.multiapp.app"
            every { getStringExtra(VirtualActivityManager.EXTRA_GUEST_ACTIVITY_LAUNCH_MODE) } returns null
            every { getStringExtra(VirtualActivityManager.EXTRA_GUEST_TASK_AFFINITY) } returns null
            every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY) } returns
                if (includeCapability) "capability-$token" else null
            every { getLongExtra(VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH, 0L) } returns 7L
            every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_SESSION_ID) } returns "session-001"
            every { getStringExtra(VirtualActivityManager.EXTRA_ENGINE_PROCESS_SLOT) } returns "com.multiapp.app:v0"
        }

    private fun mutableProxyIntent(extras: MutableMap<String, Any?>): Intent {
        lateinit var intent: Intent
        intent = mockk(relaxed = true) {
            every { getStringExtra(any()) } answers { extras[firstArg<String>()] as? String }
            every { getLongExtra(any(), any()) } answers {
                (extras[firstArg<String>()] as? Long) ?: secondArg<Long>()
            }
            every { putExtra(any(), any<String>()) } answers {
                extras[firstArg()] = secondArg<String>()
                intent
            }
            every { putExtra(any(), any<Long>()) } answers {
                extras[firstArg()] = secondArg<Long>()
                intent
            }
        }
        return intent
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
