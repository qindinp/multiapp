package com.multiapp.core.loader

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.ref.WeakReference
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
            patchedFields = listOf("mIntent", "mInfo", "mPackageInfo"),
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
    fun `capability validation waits until guest runtime is ready`() {
        var authorizationCalls = 0
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                authorizationCalls += 1
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

        assertEquals("PACKAGE_SNAPSHOT_MISSING", result.skippedReason)
        assertEquals("NOT_CONSUMED", result.launchAuthorityStatus)
        assertEquals(0, authorizationCalls)
        assertTrue(result.patchedFields.isEmpty())
        assertSame(proxyIntent, record.intent)
        assertSame(proxyInfo, record.activityInfo)
        assertSame(proxyLoadedApk, record.packageInfo)
        assertEquals("com.multiapp.app.container.ProxyActivity1", record.activityInfo?.name)
    }

    @Test
    fun `runtime readiness failure does not consume launch capability`() {
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

        assertEquals(0, authorizationCalls)
        assertEquals("PACKAGE_SNAPSHOT_MISSING", first.skippedReason)
        assertEquals("PACKAGE_SNAPSHOT_MISSING", second.skippedReason)
        assertEquals("NOT_CONSUMED", first.launchAuthorityStatus)
        assertEquals("NOT_CONSUMED", second.launchAuthorityStatus)
        assertTrue(first.patchedFields.isEmpty())
        assertTrue(second.patchedFields.isEmpty())
    }

    @Test
    fun `ready launch record consumes capability once and patches guest LoadedApk`() {
        val snapshot = readySnapshot()
        val guestApplication = mockk<Application>(relaxed = true)
        val guestClassLoader = ClassLoader.getSystemClassLoader()
        val loadedApk = FakePrewarmedLoadedApk(
            guestApplication,
            guestClassLoader,
            snapshot.originPackageName
        )
        val activityThread = FakePrewarmedActivityThread(
            loadedApk = loadedApk,
            aliases = listOf(snapshot.originPackageName, snapshot.virtualPackageName)
        )
        val applicationStage = BootstrapResult.success(
            stage = RuntimeStage.APPLICATION,
            evidence = listOf(BootstrapEvidence("loadedApkApplicationCreatorStatus", "PASS"))
        )
        val runtimeResult = HostedBootstrapResult(
            instanceId = snapshot.instanceId,
            installId = snapshot.originPackageName,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            processSlot = "com.multiapp.app:v0",
            originApkPath = snapshot.sourceDir,
            dataRoot = snapshot.dataDir,
            guestClassLoader = guestClassLoader,
            guestApplication = guestApplication,
            packageSnapshot = snapshot,
            stageResults = listOf(applicationStage),
            summary = listOf(applicationStage).toSummary(),
            success = true
        )
        VirtualPackageRegistry.global.register(snapshot)
        VirtualProcessRuntime.global.rememberApplication(snapshot.instanceId, runtimeResult)
        assertSame(
            loadedApk,
            ActivityThreadLoadedApkInstaller.findInstalledGuest(
                activityThread,
                listOf(snapshot.originPackageName, snapshot.virtualPackageName)
            )?.loadedApk
        )
        var authorizationCalls = 0
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                authorizationCalls += 1
                if (authorizationCalls == 1) {
                    VirtualActivityLaunchAuthorityResult(true, "launch_capability_authorized")
                } else {
                    VirtualActivityLaunchAuthorityResult(false, "launch_capability_replayed")
                }
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
        val proxyIntent = proxyIntent(token = "token-ready")
        val guestIntent = mockk<Intent>(relaxed = true)
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        VirtualActivityIntentStore.remember("token-ready", guestIntent)
        val record = FakeActivityClientRecord().apply {
            intent = proxyIntent
            activityInfo = ActivityInfo().apply {
                packageName = "com.multiapp.app"
                name = "com.multiapp.app.container.ProxyActivity0"
            }
            packageInfo = Any()
        }

        val result = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(
            record = record,
            activityThreadProvider = { activityThread }
        )
        val replay = VirtualActivityLaunchAuthority.authorize(
            requireNotNull(proxyIntent.toVirtualActivityLaunchIdentity("com.multiapp.app.container.ProxyActivity0"))
        )

        assertEquals("PASS", result.launchAuthorityStatus, result.toString())
        assertEquals("PREWARMED_GUEST", result.loadedApkSource)
        assertTrue("intent" in result.patchedFields)
        assertTrue("activityInfo" in result.patchedFields)
        assertTrue("packageInfo" in result.patchedFields)
        assertSame(guestIntent, record.intent)
        assertSame(loadedApk, record.packageInfo)
        assertEquals(2, authorizationCalls)
        assertEquals(false, replay.accepted)
        assertEquals("launch_capability_replayed", replay.reason)
    }

    @Test
    fun `ready LaunchActivityItem derives guest LoadedApk from patched ActivityInfo`() {
        val fixture = installReadyRuntime()
        val proxyIntent = proxyIntent(token = "token-launch-item-ready")
        val guestIntent = mockk<Intent>(relaxed = true)
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        VirtualActivityIntentStore.remember("token-launch-item-ready", guestIntent)
        val launchItem = FakeLaunchActivityItem().apply {
            mIntent = proxyIntent
            mInfo = proxyActivityInfo()
        }

        val result = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(
            record = launchItem,
            activityThreadProvider = { fixture.activityThread }
        )

        assertEquals("PASS", result.launchAuthorityStatus, result.toString())
        assertEquals("PASS", ActivityThreadLaunchRecordPatcher.launchRecordVerdict(result))
        assertEquals("PREWARMED_GUEST", result.loadedApkSource)
        assertEquals("FRAMEWORK_DERIVED_FROM_ACTIVITY_INFO", result.loadedApkBindingMode)
        assertEquals(setOf("mInfo", "mIntent"), result.patchedFields.toSet())
        assertSame(guestIntent, launchItem.mIntent)
        assertEquals("com.test.minimal", launchItem.mInfo?.packageName)
        assertEquals("com.test.minimal.MainActivity", launchItem.mInfo?.name)
    }

    @Test
    fun `partial reflection failure rolls back proxy record without consuming capability`() {
        val fixture = installReadyRuntime()
        var authorizationCalls = 0
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                authorizationCalls += 1
                VirtualActivityLaunchAuthorityResult(true, "launch_capability_authorized")
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
        val proxyIntent = proxyIntent(token = "token-partial")
        val guestIntent = mockk<Intent>(relaxed = true)
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        VirtualActivityIntentStore.remember("token-partial", guestIntent)
        val proxyInfo = ActivityInfo().apply {
            packageName = "com.multiapp.app"
            name = "com.multiapp.app.container.ProxyActivity0"
        }
        val record = FakeActivityClientRecordWithWrongLoadedApkType().apply {
            intent = proxyIntent
            activityInfo = proxyInfo
            packageInfo = "host-proxy-loaded-apk"
        }

        val result = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(
            record = record,
            activityThreadProvider = { fixture.activityThread }
        )

        assertEquals(0, authorizationCalls)
        assertEquals("NOT_CONSUMED", result.launchAuthorityStatus)
        assertEquals("record_patch_not_committed", result.launchAuthorityReason)
        assertTrue(result.skippedReason.orEmpty().startsWith("LAUNCH_RECORD_PATCH_INCOMPLETE"))
        assertTrue(result.patchedFields.isEmpty())
        assertEquals(
            setOf("activityInfo", "intent", "packageInfo"),
            result.rolledBackFields.toSet()
        )
        assertEquals("VIRTUAL_INSTRUMENTATION_FALLBACK", result.launchCapabilityOwner)
        assertSame(proxyIntent, record.intent)
        assertSame(proxyInfo, record.activityInfo)
        assertEquals("host-proxy-loaded-apk", record.packageInfo)
    }

    @Test
    fun `replayed capability rolls back second fully patched proxy record`() {
        val fixture = installReadyRuntime()
        var authorizationCalls = 0
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                authorizationCalls += 1
                if (authorizationCalls == 1) {
                    VirtualActivityLaunchAuthorityResult(true, "launch_capability_authorized")
                } else {
                    VirtualActivityLaunchAuthorityResult(false, "launch_capability_replayed")
                }
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
        val guestIntent = mockk<Intent>(relaxed = true)
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        VirtualActivityIntentStore.remember("token-replay", guestIntent)
        val firstRecord = FakeActivityClientRecord().apply {
            intent = proxyIntent(token = "token-replay")
            activityInfo = proxyActivityInfo()
            packageInfo = Any()
        }
        val replayProxyIntent = proxyIntent(token = "token-replay")
        val replayProxyInfo = proxyActivityInfo()
        val replayProxyLoadedApk = Any()
        val replayRecord = FakeActivityClientRecord().apply {
            intent = replayProxyIntent
            activityInfo = replayProxyInfo
            packageInfo = replayProxyLoadedApk
        }

        val first = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(
            record = firstRecord,
            activityThreadProvider = { fixture.activityThread }
        )
        val replay = ActivityThreadLaunchRecordPatcher.patchLaunchRecord(
            record = replayRecord,
            activityThreadProvider = { fixture.activityThread }
        )

        assertEquals(2, authorizationCalls)
        assertEquals("PASS", first.launchAuthorityStatus)
        assertEquals("FAIL", replay.launchAuthorityStatus)
        assertEquals("launch_capability_replayed", replay.launchAuthorityReason)
        assertTrue(replay.patchedFields.isEmpty())
        assertEquals(
            setOf("activityInfo", "intent", "packageInfo"),
            replay.rolledBackFields.toSet()
        )
        assertSame(replayProxyIntent, replayRecord.intent)
        assertSame(replayProxyInfo, replayRecord.activityInfo)
        assertSame(replayProxyLoadedApk, replayRecord.packageInfo)
    }

    @Test
    fun `stale recents capability is not recovered before runtime readiness`() {
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
        assertEquals("NOT_CONSUMED", result.launchAuthorityStatus)
        assertEquals(null, result.launchRecoveryStatus)
        assertEquals(null, result.launchRecoveryReason)
        assertEquals(7L, extras[VirtualActivityManager.EXTRA_ENGINE_RUNTIME_EPOCH])
        assertEquals("stale-session", extras[VirtualActivityManager.EXTRA_ENGINE_SESSION_ID])
        assertEquals("stale-capability", extras[VirtualActivityManager.EXTRA_ENGINE_LAUNCH_CAPABILITY])
        assertEquals(
            "com.test.minimal.OldActivity",
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

    private fun readySnapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.minimal",
        applicationLabel = "Minimal",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/app/minimal.apk",
        dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001"
    )

    private fun installReadyRuntime(): ReadyRuntimeFixture {
        val snapshot = readySnapshot()
        val guestApplication = mockk<Application>(relaxed = true)
        val guestClassLoader = ClassLoader.getSystemClassLoader()
        val loadedApk = FakePrewarmedLoadedApk(
            guestApplication,
            guestClassLoader,
            snapshot.originPackageName
        )
        val activityThread = FakePrewarmedActivityThread(
            loadedApk = loadedApk,
            aliases = listOf(snapshot.originPackageName, snapshot.virtualPackageName)
        )
        val applicationStage = BootstrapResult.success(
            stage = RuntimeStage.APPLICATION,
            evidence = listOf(BootstrapEvidence("loadedApkApplicationCreatorStatus", "PASS"))
        )
        val runtimeResult = HostedBootstrapResult(
            instanceId = snapshot.instanceId,
            installId = snapshot.originPackageName,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            processSlot = "com.multiapp.app:v0",
            originApkPath = snapshot.sourceDir,
            dataRoot = snapshot.dataDir,
            guestClassLoader = guestClassLoader,
            guestApplication = guestApplication,
            packageSnapshot = snapshot,
            stageResults = listOf(applicationStage),
            summary = listOf(applicationStage).toSummary(),
            success = true
        )
        VirtualPackageRegistry.global.register(snapshot)
        VirtualProcessRuntime.global.rememberApplication(snapshot.instanceId, runtimeResult)
        return ReadyRuntimeFixture(
            activityThread = activityThread,
            loadedApk = loadedApk
        )
    }

    private fun proxyActivityInfo() = ActivityInfo().apply {
        packageName = "com.multiapp.app"
        name = "com.multiapp.app.container.ProxyActivity0"
    }

    @Suppress("unused")
    private class FakeActivityClientRecord {
        var intent: Intent? = null
        var activityInfo: ActivityInfo? = null
        var packageInfo: Any? = null
    }

    @Suppress("unused")
    private class FakeActivityClientRecordWithWrongLoadedApkType {
        var intent: Intent? = null
        var activityInfo: ActivityInfo? = null
        var packageInfo: String? = null
    }

    private data class ReadyRuntimeFixture(
        val activityThread: FakePrewarmedActivityThread,
        @Suppress("unused") val loadedApk: Any
    )

    @Suppress("unused")
    private class FakePrewarmedLoadedApk(
        private var mApplication: Application?,
        private var mClassLoader: ClassLoader?,
        packageName: String
    ) {
        private var mPackageName: String? = packageName
        private var mApplicationInfo: ApplicationInfo? = ApplicationInfo().apply {
            this.packageName = packageName
        }
    }

    @Suppress("unused")
    private class FakePrewarmedActivityThread(
        loadedApk: Any,
        aliases: List<String>
    ) {
        private val mPackages = linkedMapOf<Any?, Any?>()
        private val mResourcePackages = linkedMapOf<Any?, Any?>()

        init {
            aliases.forEach { alias ->
                mPackages[alias] = WeakReference(loadedApk)
                mResourcePackages[alias] = WeakReference(loadedApk)
            }
        }

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
