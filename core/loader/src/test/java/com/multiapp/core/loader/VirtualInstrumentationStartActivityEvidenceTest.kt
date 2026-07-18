package com.multiapp.core.loader

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ProxyActivitySlotExhaustedException
import com.multiapp.core.model.virtual.VirtualActivityPendingNewIntent
import com.multiapp.core.model.virtual.VirtualActivityRecord
import com.multiapp.core.model.virtual.VirtualActivityResult
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualIntentSnapshot
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualInstrumentationStartActivityEvidenceTest {

    @BeforeTest
    fun setUp() {
        ProxyActivitySlotAssignmentStoreProvider.clearForTests()
        ProxyActivitySlotAssignmentStoreProvider.install(TestProxyActivitySlotAssignmentStore())
        VirtualActivityLaunchAllocationProviders.install(TestActivityLaunchAllocationProvider())
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
        VirtualActivityRecordManager.global.clearAll()
        VirtualProcessRuntime.global.clearAll()
        VirtualPackageRegistry.global.clear()
        VirtualActivityLaunchAuthority.clearForTests()
        VirtualActivityLaunchAllocationProviders.clearForTest()
        ProxyActivitySlotAssignmentStoreProvider.clearForTests()
        unmockkConstructor(Intent::class)
        unmockkObject(ActivityThreadCompat)
        unmockkStatic(Log::class)
    }

    @Test
    fun `hosted runtime cache requires the current ClassLoader and Application identity`() {
        val snapshot = snapshotForInstance(
            instanceId = "inst-001",
            virtualPackageName = "com.multiapp.instance.minimal"
        )
        val guestApplication = mockk<Application>(relaxed = true)
        val current = hostedBootstrapResult(snapshot).copy(
            guestClassLoader = javaClass.classLoader,
            guestApplication = guestApplication
        )

        assertTrue(VirtualInstrumentation.canReuseHostedRuntimeCache(current.copy(), current))
        assertFalse(
            VirtualInstrumentation.canReuseHostedRuntimeCache(
                current.copy(guestApplication = mockk<Application>(relaxed = true)),
                current
            )
        )
        assertFalse(
            VirtualInstrumentation.canReuseHostedRuntimeCache(
                current.copy(guestClassLoader = object : ClassLoader(current.guestClassLoader) {}),
                current
            )
        )
        assertFalse(VirtualInstrumentation.canReuseHostedRuntimeCache(current, null))
    }

    @Test
    fun `callActivityOnResume delegates guest onResume before returning to lifecycle ACK`() {
        val events = mutableListOf<String>()
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        val base = mockk<Instrumentation>(relaxed = true)
        val activity = mockk<Activity>(relaxed = true) {
            every { intent } returns proxyIntentForRecordRecovery()
        }
        every { base.callActivityOnResume(activity) } answers { events += "base" }
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                VirtualActivityLaunchAuthorityResult(true, "test_authorized")
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> events += "observer" }
        )

        VirtualInstrumentation(base).callActivityOnResume(activity)

        verify(exactly = 1) { base.callActivityOnResume(activity) }
        assertEquals(listOf("base", "observer"), events)
    }

    @Test
    fun `callActivityOnResume does not ACK when guest onResume throws`() {
        var observerCalls = 0
        val base = mockk<Instrumentation>(relaxed = true)
        val activity = mockk<Activity>(relaxed = true) {
            every { intent } returns proxyIntentForRecordRecovery()
        }
        every { base.callActivityOnResume(activity) } throws IllegalStateException("guest resume failed")
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                VirtualActivityLaunchAuthorityResult(true, "test_authorized")
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> observerCalls++ }
        )

        assertFailsWith<IllegalStateException> {
            VirtualInstrumentation(base).callActivityOnResume(activity)
        }
        assertEquals(0, observerCalls)
    }

    @Test
    fun `hostedStartActivitySource prefers explicit target activity`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val target = mockk<Activity>(relaxed = true)
        val who = mockk<Activity>(relaxed = true)

        val resolved = instrumentation.hostedStartActivitySource(target = target, who = who)

        assertSame(target, resolved)
    }

    @Test
    fun `hostedStartActivitySource falls back to Activity context for non Activity overloads`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val who = mockk<Activity>(relaxed = true)

        val resolved = instrumentation.hostedStartActivitySource(who = who)

        assertSame(who, resolved)
    }

    @Test
    fun `hostedStartActivitySource ignores non Activity context`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val who = mockk<Context>(relaxed = true)

        val resolved = instrumentation.hostedStartActivitySource(who = who)

        assertEquals(null, resolved)
    }

    @Test
    fun `hostedStartActivityInstanceId resolves current virtual context package`() {
        VirtualPackageRegistry.global.clear()
        try {
            VirtualPackageRegistry.global.register(
                snapshotForInstance(
                    instanceId = "inst-service",
                    virtualPackageName = "com.multiapp.instance.service"
                )
            )
            val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
            val who = mockk<Context>(relaxed = true) {
                every { packageName } returns "com.multiapp.instance.service"
            }
            val intent = mockk<Intent>(relaxed = true) {
                every { selector } returns null
                every { component } returns null
                every { `package` } returns null
            }

            val instanceId = instrumentation.hostedStartActivityInstanceId(who = who, intent = intent)

            assertEquals("inst-service", instanceId)
        } finally {
            VirtualPackageRegistry.global.clear()
        }
    }

    @Test
    fun `hostedStartActivityInstanceId does not guess ambiguous origin package`() {
        VirtualPackageRegistry.global.clear()
        try {
            VirtualPackageRegistry.global.register(
                snapshotForInstance(instanceId = "inst-001", virtualPackageName = "com.multiapp.instance.one")
            )
            VirtualPackageRegistry.global.register(
                snapshotForInstance(instanceId = "inst-002", virtualPackageName = "com.multiapp.instance.two")
            )
            val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
            val who = mockk<Context>(relaxed = true) {
                every { packageName } returns "com.multiapp.app"
            }
            val component = mockk<ComponentName>(relaxed = true) {
                every { packageName } returns "com.test.minimal"
            }
            val intent = mockk<Intent>(relaxed = true) {
                every { selector } returns null
                every { this@mockk.component } returns component
                every { `package` } returns null
            }

            val instanceId = instrumentation.hostedStartActivityInstanceId(who = who, intent = intent)

            assertNull(instanceId)
        } finally {
            VirtualPackageRegistry.global.clear()
        }
    }

    @Test
    fun `remap evidence records overload and partial result route`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val resultRouteClass = Class.forName("${VirtualInstrumentation::class.java.name}\$ActivityResultRoute")
        val resultRouteConstructor = resultRouteClass.getDeclaredConstructor(String::class.java, Integer.TYPE)
        resultRouteConstructor.isAccessible = true
        val resultRoute = resultRouteConstructor.newInstance("source-token-001", 42)

        invokePrivate(
            instrumentation,
            "writeRemapEvidence",
            arrayOf(
                File::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE,
                String::class.java,
                String::class.java,
                resultRouteClass
            ),
            filesDir,
            "inst-001",
            "com.test.minimal.DetailActivity",
            "com.multiapp.app.container.ProxyActivitySingleTop0",
            "execStartActivity:activity-options",
            42,
            "explicit",
            "singleTop",
            resultRoute
        )

        val lines = remapEvidenceLines(filesDir)
        assertTrue("status=GUEST_ACTIVITY_REMAP" in lines)
        assertTrue("stage=ACTIVITY_START_REMAP" in lines)
        assertTrue("api=execStartActivity:activity-options" in lines)
        assertTrue("hostFallback=false" in lines)
        assertTrue("requestCode=42" in lines)
        assertTrue("resultRequested=true" in lines)
        assertTrue("activityResultRouteRecorded=true" in lines)
        assertTrue("activityResultToToken=<redacted>" in lines)
        assertTrue("activityResultRecordRequestCode=42" in lines)
        assertTrue("activityResultVerdict=PARTIAL" in lines)
        assertTrue("activityResultVerdictReason=HOST_PROXY_RESULT_ROUTE_RECORDED_DELIVERY_PENDING" in lines)
        assertTrue("launchMode=singleTop" in lines)
    }

    @Test
    fun `skipped remap evidence records host fallback and not requested result`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val intent = mockk<Intent>(relaxed = true) {
            every { action } returns "com.test.ACTION"
            every { component } returns null
            every { dataString } returns null
        }

        invokePrivate(
            instrumentation,
            "writeRemapSkippedEvidence",
            arrayOf(
                File::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE,
                Intent::class.java
            ),
            filesDir,
            "inst-001",
            "INTENT_NOT_RESOLVED",
            "execStartActivity:string-options",
            -1,
            intent
        )

        val lines = remapEvidenceLines(filesDir)
        assertTrue("status=GUEST_ACTIVITY_REMAP_SKIPPED" in lines)
        assertTrue("api=execStartActivity:string-options" in lines)
        assertTrue("hostFallback=true" in lines)
        assertTrue("requestCode=-1" in lines)
        assertTrue("resultRequested=false" in lines)
        assertTrue("activityResultVerdict=NOT_REQUESTED" in lines)
        assertTrue("activityResultVerdictReason=" in lines)
        assertTrue("reason=INTENT_NOT_RESOLVED" in lines)
    }

    @Test
    fun `blocked remap evidence records no host fallback and canceled result`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val intent = mockk<Intent>(relaxed = true) {
            every { action } returns "com.test.ACTION"
            every { component } returns null
            every { `package` } returns "com.test.minimal"
            every { dataString } returns null
        }

        invokePrivate(
            instrumentation,
            "writeRemapBlockedEvidence",
            arrayOf(
                File::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE,
                Intent::class.java
            ),
            filesDir,
            "inst-001",
            "UNRESOLVED_GUEST_ACTIVITY_INTENT",
            "execStartActivity:activity",
            7,
            intent
        )

        val lines = remapEvidenceLines(filesDir)
        assertTrue("status=GUEST_ACTIVITY_REMAP_BLOCKED" in lines)
        assertTrue("api=execStartActivity:activity" in lines)
        assertTrue("hostFallback=false" in lines)
        assertTrue("requestCode=7" in lines)
        assertTrue("resultRequested=true" in lines)
        assertTrue("activityResultVerdict=CANCELED" in lines)
        assertTrue("activityResultVerdictReason=UNRESOLVED_GUEST_ACTIVITY_INTENT" in lines)
        assertTrue("reason=UNRESOLVED_GUEST_ACTIVITY_INTENT" in lines)
        assertTrue("intentPackage=com.test.minimal" in lines)
    }

    @Test
    fun `intentTargetsGuestPackage detects component package and selector package`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val component = mockk<ComponentName>(relaxed = true) {
            every { packageName } returns "com.test.minimal"
        }
        val componentIntent = mockk<Intent>(relaxed = true) {
            every { this@mockk.component } returns component
            every { this@mockk.`package` } returns null
            every { this@mockk.selector } returns null
        }
        val selector = mockk<Intent>(relaxed = true) {
            every { this@mockk.component } returns null
            every { this@mockk.`package` } returns "com.multiapp.instance.abc"
            every { this@mockk.selector } returns null
        }
        val selectorIntent = mockk<Intent>(relaxed = true) {
            every { this@mockk.component } returns null
            every { this@mockk.`package` } returns null
            every { this@mockk.selector } returns selector
        }
        val externalIntent = mockk<Intent>(relaxed = true) {
            every { this@mockk.component } returns null
            every { this@mockk.`package` } returns "com.android.settings"
            every { this@mockk.selector } returns null
        }
        val guestPackages = setOf("com.test.minimal", "com.multiapp.instance.abc")

        assertTrue(instrumentation.intentTargetsGuestPackage(componentIntent, guestPackages))
        assertTrue(instrumentation.intentTargetsGuestPackage(selectorIntent, guestPackages))
        assertEquals(false, instrumentation.intentTargetsGuestPackage(externalIntent, guestPackages))
    }

    @Test
    fun `intentLooksGuestPrivate blocks custom implicit actions but allows external intents`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val guestInternal = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { `package` } returns null
            every { selector } returns null
            every { action } returns "com.test.minimal.OPEN_DETAIL"
            every { categories } returns emptySet()
            every { dataString } returns null
        }
        val systemView = mockk<Intent>(relaxed = true) {
            every { component } returns null
            every { `package` } returns null
            every { selector } returns null
            every { action } returns Intent.ACTION_VIEW
            every { categories } returns setOf(Intent.CATEGORY_BROWSABLE)
            every { dataString } returns "https://example.com"
        }
        val explicitExternal = mockk<Intent>(relaxed = true) {
            every { component } returns mockk<ComponentName>(relaxed = true)
            every { `package` } returns null
            every { selector } returns null
            every { action } returns "com.other.OPEN"
            every { categories } returns emptySet()
            every { dataString } returns null
        }

        assertTrue(instrumentation.intentLooksGuestPrivate(guestInternal))
        assertEquals(false, instrumentation.intentLooksGuestPrivate(systemView))
        assertEquals(false, instrumentation.intentLooksGuestPrivate(explicitExternal))
    }

    @Test
    fun `foreground bootstrap guard blocks main thread cache miss only`() {
        assertEquals(
            true,
            VirtualInstrumentation.shouldBlockForegroundRuntimeBootstrap(
                isMainThread = true,
                hasReusableProcessRuntime = false
            )
        )
        assertEquals(
            false,
            VirtualInstrumentation.shouldBlockForegroundRuntimeBootstrap(
                isMainThread = true,
                hasReusableProcessRuntime = true
            )
        )
        assertEquals(
            false,
            VirtualInstrumentation.shouldBlockForegroundRuntimeBootstrap(
                isMainThread = false,
                hasReusableProcessRuntime = false
            )
        )
    }

    @Test
    fun `hosted task description label includes origin package and short instance id`() {
        val label = VirtualInstrumentation.hostedTaskDescriptionLabel(
            originPackageName = "com.tencent.mobileqq",
            instanceId = "ffc737401234abcd"
        )

        assertEquals("com.tencent.mobileqq #ffc73740", label)
    }

    @Test
    fun `new intent evidence consumes pending intent through injected activity operations`(@TempDir filesDir: File) {
        val operations = RecordingVirtualActivityOperations(
            pending = VirtualActivityPendingNewIntent(
                eventId = 3L,
                sourceToken = "source-token",
                intentFlags = 17,
                dataIntent = VirtualIntentSnapshot(
                    action = "com.test.PENDING",
                    dataUri = "content://guest/items/1?token=secret"
                )
            )
        )
        val instrumentation = VirtualInstrumentation(
            base = mockk<Instrumentation>(relaxed = true),
            activityOperations = operations
        )
        val hostApplication = mockk<Application>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
        }
        val activity = mockk<Activity>(relaxed = true) {
            every { intent } returns hostedIdentityIntent()
        }
        val deliveredIntent = mockk<Intent>(relaxed = true) {
            every { action } returns "com.test.DELIVERED"
            every { dataString } returns "content://guest/delivered?token=secret"
        }
        mockkObject(ActivityThreadCompat)
        every { ActivityThreadCompat.currentApplication() } returns hostApplication

        invokePrivate(
            instrumentation,
            "writeNewIntentEvidence",
            arrayOf(Activity::class.java, Intent::class.java),
            activity,
            deliveredIntent
        )

        val lines = File(
            filesDir,
            "hosted_launch_evidence/${HostedActivityEvidenceFiles.newIntent("inst-001")}"
        ).readLines().map { it.trim() }
        assertEquals(listOf("inst-001" to "token-001"), operations.pendingCalls)
        assertTrue("status=GUEST_ACTIVITY_ON_NEW_INTENT" in lines)
        assertTrue("pendingNewIntentConsumed=true" in lines)
        assertTrue("pendingAction=com.test.PENDING" in lines)
        assertTrue("pendingDataUri=content://guest/<redacted>" in lines)
        assertTrue("intentAction=com.test.DELIVERED" in lines)
        assertTrue("intentDataUri=content://guest/<redacted>" in lines)
    }

    @Test
    fun `delivered activity result consumes virtual result through injected operations`(@TempDir filesDir: File) {
        val operations = RecordingVirtualActivityOperations(
            consumedResult = VirtualActivityResult(
                resultCode = Activity.RESULT_OK,
                dataIntent = VirtualIntentSnapshot(action = "com.test.RESULT", dataUri = "content://guest/<redacted>")
            )
        )
        val baseInstrumentation = ResultCallbackInstrumentation()
        val instrumentation = VirtualInstrumentation(
            base = baseInstrumentation,
            activityOperations = operations
        )
        val hostApplication = mockk<Application>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
        }
        val activity = mockk<Activity>(relaxed = true) {
            every { intent } returns hostedIdentityIntent()
        }
        val resultData = mockk<Intent>(relaxed = true) {
            every { action } returns "com.test.RESULT"
            every { dataString } returns "content://guest/result?token=secret"
        }
        mockkObject(ActivityThreadCompat)
        every { ActivityThreadCompat.currentApplication() } returns hostApplication

        instrumentation.callActivityOnActivityResult(
            activity,
            "child",
            42,
            Activity.RESULT_OK,
            resultData
        )

        val lines = File(
            filesDir,
            "hosted_launch_evidence/${HostedActivityEvidenceFiles.result("inst-001")}"
        ).readLines().map { it.trim() }
        assertEquals(listOf("inst-001" to "token-001"), operations.consumeResultCalls)
        assertEquals(1, baseInstrumentation.resultCallbackCount)
        assertTrue("status=ACTIVITY_RESULT_DELIVERED" in lines)
        assertTrue("stage=ACTIVITY_RESULT_DELIVERY" in lines)
        assertTrue("requestCode=42" in lines)
        assertTrue("baseCallbackInvoked=true" in lines)
        assertTrue("virtualResultConsumed=true" in lines)
        assertTrue("resultCodeMatches=true" in lines)
        assertTrue("reason=" in lines)
    }

    @Test
    fun `destroy finish records result through injected activity operations`(@TempDir filesDir: File) {
        val operations = RecordingVirtualActivityOperations(
            finishResult = VirtualActivityFinishResultRecord(
                instanceId = "inst-001",
                sourceToken = "source-token",
                requestCode = 42,
                resultCode = Activity.RESULT_CANCELED,
                recorded = true,
                reason = ""
            )
        )
        val instrumentation = VirtualInstrumentation(
            base = mockk<Instrumentation>(relaxed = true),
            activityOperations = operations
        )
        val hostApplication = mockk<Application>(relaxed = true) {
            every { this@mockk.filesDir } returns filesDir
        }
        val activity = mockk<Activity>(relaxed = true) {
            every { isFinishing } returns true
            every { intent } returns hostedIdentityIntent()
        }
        mockkObject(ActivityThreadCompat)
        every { ActivityThreadCompat.currentApplication() } returns hostApplication

        invokePrivate(
            instrumentation,
            "markActivityFinishedIfNeeded",
            arrayOf(Activity::class.java),
            activity
        )

        val lines = File(
            filesDir,
            "hosted_launch_evidence/${HostedActivityEvidenceFiles.result("inst-001")}"
        ).readLines().map { it.trim() }
        assertEquals(listOf(Triple("inst-001", "token-001", Activity.RESULT_CANCELED)), operations.finishResultCalls)
        assertEquals(listOf("inst-001" to "token-001"), operations.finishCalls)
        assertTrue("status=ACTIVITY_FINISH_RESULT_RECORDED" in lines)
        assertTrue("stage=ACTIVITY_FINISH_RESULT" in lines)
        assertTrue("sourceToken=<redacted>" in lines)
        assertTrue("requestCode=42" in lines)
        assertTrue("virtualResultRecorded=true" in lines)
        assertTrue("activityThreadSendActivityResultVerdict=SKIPPED" in lines)
        assertTrue("activityThreadSendActivityResultReason=SOURCE_ACTIVITY_THREAD_TOKEN_MISSING" in lines)
    }

    @Test
    fun `remap start activity uses instrumentation activity record manager`(@TempDir filesDir: File) {
        val recordManager = VirtualActivityRecordManager()
        val processRuntime = VirtualProcessRuntime()
        val instrumentation = VirtualInstrumentation(
            base = mockk<Instrumentation>(relaxed = true),
            processRuntime = processRuntime,
            activityRecordManager = recordManager
        )
        val snapshot = snapshotForInstance(
            instanceId = "inst-001",
            virtualPackageName = "com.multiapp.instance.minimal"
        ).copy(
            activities = listOf(
                ResolvedComponent(
                    name = "com.test.minimal.DetailActivity",
                    taskAffinity = "com.test.minimal.task"
                )
            )
        )
        val hostApplication = mockk<Application>(relaxed = true) {
            every { packageName } returns "com.multiapp.app"
            every { this@mockk.filesDir } returns filesDir
        }
        VirtualPackageRegistry.global.register(snapshot)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        rememberHostedRuntime(
            instrumentation = instrumentation,
            instanceId = "inst-001",
            hostApplication = hostApplication,
            result = hostedBootstrapResult(snapshot = snapshot)
        )
        val who = mockk<Context>(relaxed = true) {
            every { packageName } returns "com.multiapp.instance.minimal"
        }
        val component = mockk<ComponentName>(relaxed = true) {
            every { packageName } returns "com.test.minimal"
            every { className } returns "com.test.minimal.DetailActivity"
        }
        val intent = mockk<Intent>(relaxed = true) {
            every { this@mockk.component } returns component
            every { selector } returns null
            every { `package` } returns null
            every { flags } returns Intent.FLAG_ACTIVITY_NEW_TASK
            every { action } returns null
            every { dataString } returns null
            every { categories } returns emptySet()
            every { extras } returns null
        }

        instrumentation.remapStartActivityIntent(
            target = null,
            who = who,
            intent = intent,
            api = "execStartActivity:test",
            requestCode = -1
        )

        val record = assertNotNull(recordManager.list().singleOrNull())
        assertEquals("com.test.minimal.DetailActivity", record.guestActivityClassName)
        assertSame(record, recordManager.resolveByProxy(record.proxyActivityClassName))
        assertTrue(VirtualActivityRecordManager.global.list().isEmpty())
    }

    @Test
    fun `remap start activity records result route for hosted source activity`(@TempDir filesDir: File) {
        val recordManager = VirtualActivityRecordManager()
        val processRuntime = VirtualProcessRuntime()
        val instrumentation = VirtualInstrumentation(
            base = mockk<Instrumentation>(relaxed = true),
            processRuntime = processRuntime,
            activityRecordManager = recordManager
        )
        val snapshot = snapshotForInstance(
            instanceId = "inst-001",
            virtualPackageName = "com.multiapp.instance.minimal"
        ).copy(
            activities = listOf(
                ResolvedComponent(
                    name = "com.test.minimal.DetailActivity",
                    taskAffinity = "com.test.minimal.task"
                )
            )
        )
        val hostApplication = mockk<Application>(relaxed = true) {
            every { packageName } returns "com.multiapp.app"
            every { this@mockk.filesDir } returns filesDir
        }
        VirtualPackageRegistry.global.register(snapshot)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        rememberHostedRuntime(
            instrumentation = instrumentation,
            instanceId = "inst-001",
            hostApplication = hostApplication,
            result = hostedBootstrapResult(snapshot = snapshot)
        )
        val sourceActivity = mockk<Activity>(relaxed = true) {
            every { intent } returns hostedIdentityIntent(token = "source-token-001")
        }
        val who = mockk<Context>(relaxed = true) {
            every { packageName } returns "com.multiapp.instance.minimal"
        }
        val component = mockk<ComponentName>(relaxed = true) {
            every { packageName } returns "com.test.minimal"
            every { className } returns "com.test.minimal.DetailActivity"
        }
        val intent = mockk<Intent>(relaxed = true) {
            every { this@mockk.component } returns component
            every { selector } returns null
            every { `package` } returns null
            every { flags } returns 0
            every { action } returns null
            every { dataString } returns null
            every { categories } returns emptySet()
            every { extras } returns null
        }
        every { Log.w(any(), any<String>(), any()) } answers {
            0
        }

        instrumentation.remapStartActivityIntent(
            target = sourceActivity,
            who = who,
            intent = intent,
            api = "execStartActivity:test",
            requestCode = 42
        )

        val record = assertNotNull(recordManager.list().singleOrNull())
        assertEquals("source-token-001", record.resultToToken)
        assertEquals(42, record.resultRequestCode)
    }

    @Test
    fun `remap start activities releases allocations in reverse and restores state when second local allocation fails`(
        @TempDir filesDir: File
    ) {
        val loggedFailures = mutableListOf<Throwable>()
        val allocationProvider = SameSlotRecordingAllocationProvider()
        VirtualActivityLaunchAllocationProviders.install(allocationProvider)
        mockkConstructor(Intent::class)
        every {
            anyConstructed<Intent>().setClassName(any<String>(), any<String>())
        } answers { self as Intent }
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<String>())
        } answers { self as Intent }
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Long>())
        } answers { self as Intent }
        every {
            anyConstructed<Intent>().putExtra(any<String>(), any<Int>())
        } answers { self as Intent }
        every { anyConstructed<Intent>().flags } returns 0
        every { anyConstructed<Intent>().setFlags(any()) } answers { self as Intent }
        val recordManager = VirtualActivityRecordManager()
        val processRuntime = VirtualProcessRuntime()
        val instrumentation = VirtualInstrumentation(
            base = mockk<Instrumentation>(relaxed = true),
            processRuntime = processRuntime,
            activityRecordManager = recordManager
        )
        val snapshot = snapshotForInstance(
            instanceId = "inst-001",
            virtualPackageName = "com.multiapp.instance.minimal"
        ).copy(
            activities = listOf(
                ResolvedComponent(
                    name = "com.test.minimal.FirstActivity",
                    taskAffinity = "com.test.minimal:first"
                ),
                ResolvedComponent(
                    name = "com.test.minimal.SecondActivity",
                    taskAffinity = "com.test.minimal:second"
                )
            )
        )
        val hostApplication = mockk<Application>(relaxed = true) {
            every { packageName } returns "com.multiapp.app"
            every { this@mockk.filesDir } returns filesDir
        }
        VirtualPackageRegistry.global.register(snapshot)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } answers {
            loggedFailures += thirdArg<Throwable>()
            0
        }
        rememberHostedRuntime(
            instrumentation = instrumentation,
            instanceId = "inst-001",
            hostApplication = hostApplication,
            result = hostedBootstrapResult(snapshot = snapshot)
        )
        val who = mockk<Context>(relaxed = true) {
            every { packageName } returns "com.multiapp.instance.minimal"
        }

        val result = instrumentation.remapStartActivityIntents(
            target = null,
            who = who,
            intents = arrayOf(
                explicitRemapIntent("com.test.minimal.FirstActivity"),
                explicitRemapIntent("com.test.minimal.SecondActivity")
            ),
            api = "execStartActivities:test"
        )

        assertNull(result)
        assertEquals(
            2,
            allocationProvider.allocations.size,
            loggedFailures.joinToString("\n") { it.stackTraceToString() }
        )
        assertEquals(
            allocationProvider.allocations.asReversed()
                .mapNotNull { it.launchIdentity?.capabilityToken },
            allocationProvider.releasedCapabilityTokens
        )
        assertTrue(loggedFailures.any { it is ProxyActivitySlotExhaustedException })
        assertTrue(recordManager.list().isEmpty())
        assertTrue(recordManager.listTasks().isEmpty())
    }

    @Test
    fun `activity record recovery restores missing proxy record during substitution`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val recordManager = VirtualActivityRecordManager()
        val originalGuestIntent = sourceIntentForRecovery(
            flags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK,
            action = "com.test.OPEN",
            dataString = "https://example.com/start?token=secret"
        )
        val proxyIntent = proxyIntentForRecordRecovery(originalGuestIntent = originalGuestIntent)
        val guestIntent = guestIntentForRecovery()
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        VirtualActivityIntentStore.remember("token-001", originalGuestIntent)

        val result = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            guestIntent = guestIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )

        val record = assertNotNull(recordManager.resolve("token-001"))
        assertEquals(false, result.activityRecordFound)
        assertTrue(result.activityRecordRecovered)
        assertEquals("com.test.minimal", record.originPackageName)
        assertEquals("com.test.minimal.MainActivity", record.guestActivityClassName)
        assertEquals("com.multiapp.app.container.ProxyActivity0", record.proxyActivityClassName)
        assertEquals("singleTask", record.launchMode)
        assertEquals("com.test.minimal:inst-001", record.taskAffinity)
        assertEquals(VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK, record.intentFlags)
        assertEquals(1, record.taskId)
        assertSame(record, recordManager.resolveByProxy("com.multiapp.app.container.ProxyActivity0"))
        assertEquals(false, recordManager.lastLaunchResult()?.reused)
    }

    @Test
    fun `activity record recovery reuses the already authorized substitution preflight`() {
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
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val recordManager = VirtualActivityRecordManager()
        val proxyIntent = proxyIntentForRecordRecovery()
        val guestIntent = guestIntentForRecovery()
        val preflight = instrumentation.validateProxyActivityLaunchBeforeBootstrap(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )

        val result = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            guestIntent = guestIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager,
            authorizedPreflight = preflight
        )

        assertEquals(1, authorizationCalls)
        assertTrue(result.activityRecordRecovered)
        assertNotNull(recordManager.resolve("token-001"))
    }

    @Test
    fun `instrumentation fallback owns one capability consumption after metadata is complete`() {
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
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val recordManager = VirtualActivityRecordManager()

        val incomplete = instrumentation.validateProxyActivityLaunchBeforeBootstrap(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntentForRecordRecovery(originPackageName = null),
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )
        val preflight = instrumentation.validateProxyActivityLaunchBeforeBootstrap(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntentForRecordRecovery(),
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )
        val recovered = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntentForRecordRecovery(),
            guestIntent = guestIntentForRecovery(),
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager,
            authorizedPreflight = preflight
        )

        assertTrue(incomplete.isRejected)
        assertEquals("ORIGIN_PACKAGE_MISSING", incomplete.skippedReason)
        assertEquals("ENGINE_LAUNCH_AUTHORIZED", preflight.skippedReason)
        assertEquals(1, authorizationCalls)
        assertTrue(recovered.activityRecordRecovered)
    }

    @Test
    fun `instrumentation fallback consumes once even when activity record was pre-registered`() {
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
        val recordManager = VirtualActivityRecordManager()
        recordManager.registerLaunch(
            record = VirtualActivityRecord(
                token = "token-001",
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                guestActivityClassName = "com.test.minimal.MainActivity",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0"
            ),
            intentFlags = 0,
            dataIntent = null
        )
        val instrumentation = VirtualInstrumentation(
            base = mockk<Instrumentation>(relaxed = true),
            activityRecordManager = recordManager
        )
        val proxyIntent = proxyIntentForRecordRecovery()

        val first = instrumentation.validateProxyActivityLaunchBeforeBootstrap(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity"
        )
        val repeated = instrumentation.validateProxyActivityLaunchBeforeBootstrap(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity"
        )

        assertEquals(1, authorizationCalls)
        assertTrue(first.activityRecordFound)
        assertEquals("ALREADY_REGISTERED", first.skippedReason)
        assertTrue(repeated.activityRecordFound)
        assertEquals("ALREADY_REGISTERED", repeated.skippedReason)
    }

    @Test
    fun `activity record recovery defaults to instrumentation record manager`() {
        val recordManager = VirtualActivityRecordManager()
        val instrumentation = VirtualInstrumentation(
            base = mockk<Instrumentation>(relaxed = true),
            activityRecordManager = recordManager
        )
        val originalGuestIntent = sourceIntentForRecovery(
            flags = VirtualActivityStack.FLAG_ACTIVITY_NEW_TASK,
            action = "com.test.OPEN"
        )
        val proxyIntent = proxyIntentForRecordRecovery(originalGuestIntent = originalGuestIntent)
        val guestIntent = guestIntentForRecovery()
        VirtualActivityIntentStore.setIntentCopierForTest { it }
        VirtualActivityIntentStore.remember("token-001", originalGuestIntent)

        val result = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            guestIntent = guestIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity"
        )

        val record = assertNotNull(recordManager.resolve("token-001"))
        assertTrue(result.activityRecordRecovered)
        assertEquals("com.test.minimal.MainActivity", record.guestActivityClassName)
        assertSame(record, recordManager.resolveByProxy("com.multiapp.app.container.ProxyActivity0"))
    }

    @Test
    fun `activity record recovery is idempotent when token already exists`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val recordManager = VirtualActivityRecordManager()
        val proxyIntent = proxyIntentForRecordRecovery()
        val guestIntent = guestIntentForRecovery()
        val first = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            guestIntent = guestIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )
        val firstLaunch = recordManager.lastLaunchResult()

        val second = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntent,
            guestIntent = guestIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )

        assertTrue(first.activityRecordRecovered)
        assertEquals(true, second.activityRecordFound)
        assertEquals(false, second.activityRecordRecovered)
        assertEquals("ALREADY_REGISTERED", second.skippedReason)
        assertSame(firstLaunch, recordManager.lastLaunchResult())
        assertEquals(1, recordManager.list().size)
    }

    @Test
    fun `activity record recovery rejects bare extras without engine launch identity`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val recordManager = VirtualActivityRecordManager()

        val result = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntentForRecordRecovery(includeEngineIdentity = false),
            guestIntent = guestIntentForRecovery(),
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )

        assertTrue(result.isRejected)
        assertEquals("ENGINE_LAUNCH_IDENTITY_MISSING", result.skippedReason)
        assertNull(recordManager.resolve("token-001"))
    }

    @Test
    fun `activity record recovery fails closed when engine rejects capability`() {
        VirtualActivityLaunchAuthority.install(
            validator = VirtualActivityLaunchValidator {
                VirtualActivityLaunchAuthorityResult(false, "stale_generation")
            },
            resumeObserver = VirtualActivityResumeObserver { _, _ -> }
        )
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val recordManager = VirtualActivityRecordManager()

        val result = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntentForRecordRecovery(),
            guestIntent = guestIntentForRecovery(),
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )

        assertTrue(result.isRejected)
        assertEquals("ENGINE_LAUNCH_REJECTED:stale_generation", result.skippedReason)
        assertNull(recordManager.resolve("token-001"))
    }

    @Test
    fun `activity record recovery rejects incomplete proxy metadata`() {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val recordManager = VirtualActivityRecordManager()
        val guestIntent = guestIntentForRecovery(token = null)

        val missingToken = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntentForRecordRecovery(token = null),
            guestIntent = guestIntent,
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )
        val missingOrigin = instrumentation.restoreActivityRecordFromProxyIntentIfMissing(
            proxyClassName = "com.multiapp.app.container.ProxyActivity0",
            proxyIntent = proxyIntentForRecordRecovery(originPackageName = null),
            guestIntent = guestIntentForRecovery(),
            instanceId = "inst-001",
            guestActivityClassName = "com.test.minimal.MainActivity",
            activityRecordManager = recordManager
        )

        assertEquals("TOKEN_MISSING", missingToken.skippedReason)
        assertEquals("ORIGIN_PACKAGE_MISSING", missingOrigin.skippedReason)
        assertTrue(missingToken.isRejected)
        assertTrue(missingOrigin.isRejected)
        assertNull(recordManager.resolve("token-001"))
        assertEquals(0, recordManager.list().size)
    }

    @Test
    fun `foreground bootstrap blocked evidence records cache miss without host fallback`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))

        invokePrivate(
            instrumentation,
            "writeForegroundBootstrapBlockedEvidence",
            arrayOf(File::class.java, String::class.java, String::class.java),
            filesDir,
            "inst-001",
            "main"
        )

        val lines = File(
            filesDir,
            "hosted_launch_evidence/${HostedActivityEvidenceFiles.instrumentation("inst-001")}"
        ).readLines().map { it.trim() }
        assertTrue("status=FAIL" in lines)
        assertTrue("stage=ACTIVITY_INSTRUMENTATION" in lines)
        assertTrue("detail=FOREGROUND_BOOTSTRAP_BLOCKED" in lines)
        assertTrue("runtimeCacheHit=false" in lines)
        assertTrue("processRuntimeReusable=false" in lines)
        assertTrue("foregroundBootstrapAllowed=false" in lines)
        assertTrue("reason=RUNTIME_CACHE_MISS_ON_MAIN_THREAD" in lines)
    }

    @Test
    fun `substitution evidence redacts activity token without prefix leakage`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))
        val rawToken = "raw-activity-token-super-secret"
        val recovery = VirtualInstrumentation.ActivityRecordRecoveryResult(
            record = VirtualActivityRecord(
                token = rawToken,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                guestActivityClassName = "com.test.minimal.MainActivity",
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0"
            ),
            activityRecordFound = true
        )

        invokePrivate(
            instrumentation,
            "writeSubstitutionEvidence",
            arrayOf(
                File::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                VirtualInstrumentation.ActivityRecordRecoveryResult::class.java
            ),
            filesDir,
            "inst-001",
            "com.multiapp.app.container.ProxyActivity0",
            "com.test.minimal.MainActivity",
            recovery
        )

        val text = File(
            filesDir,
            "hosted_launch_evidence/${HostedActivityEvidenceFiles.instrumentation("inst-001")}"
        ).readText()
        assertTrue(text.contains("token=<redacted>"))
        assertTrue(!text.contains(rawToken), "substitution evidence leaked raw token in $text")
        assertTrue(!text.contains(rawToken.take(8)), "substitution evidence leaked token prefix in $text")
    }

    private fun remapEvidenceLines(filesDir: File): List<String> {
        val file = File(filesDir, "hosted_launch_evidence/inst-001.activity-remap.properties")
        assertTrue(file.isFile)
        return file.readLines().map { it.trim() }
    }

    private fun proxyIntentForRecordRecovery(
        token: String? = "token-001",
        originPackageName: String? = "com.test.minimal",
        launchMode: String? = "singleTask",
        taskAffinity: String? = "com.test.minimal:inst-001",
        originalGuestIntent: Intent? = sourceIntentForRecovery(),
        includeEngineIdentity: Boolean = true
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra("multiapp.virtualActivityToken") } returns token
        every { getStringExtra("multiapp.instanceId") } returns "inst-001"
        every { getStringExtra("multiapp.originPackageName") } returns originPackageName
        every { getStringExtra("multiapp.guestActivityClassName") } returns "com.test.minimal.MainActivity"
        every { getStringExtra("multiapp.guestActivityLaunchMode") } returns launchMode
        every { getStringExtra("multiapp.guestTaskAffinity") } returns taskAffinity
        every { getStringExtra("multiapp.engine.sessionId") } returns
            if (includeEngineIdentity) "engine-session-42" else null
        every { getStringExtra("multiapp.engine.processSlot") } returns
            if (includeEngineIdentity) "com.multiapp.app:v0" else null
        every { getStringExtra("multiapp.engine.proxyActivityClassName") } returns
            if (includeEngineIdentity) "com.multiapp.app.container.ProxyActivity0" else null
        every { getStringExtra("multiapp.engine.launchCapability") } returns
            if (includeEngineIdentity) "capability-42" else null
        every { getLongExtra("multiapp.engine.runtimeEpoch", any()) } returns
            if (includeEngineIdentity) 42L else 0L
        every { getParcelableExtra<Intent>("multiapp.originalGuestIntent") } returns originalGuestIntent
        every { flags } returns 0
        every { action } returns null
        every { dataString } returns null
        every { categories } returns emptySet()
        every { extras } returns null
    }

    private fun guestIntentForRecovery(token: String? = "token-001"): Intent = mockk(relaxed = true) {
        every { getStringExtra("multiapp.virtualActivityToken") } returns token
        every { flags } returns 0
        every { action } returns null
        every { dataString } returns null
        every { categories } returns emptySet()
        every { extras } returns null
    }

    private fun hostedIdentityIntent(
        instanceId: String = "inst-001",
        token: String = "token-001",
        guestActivityClassName: String = "com.test.minimal.MainActivity"
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra("multiapp.instanceId") } returns instanceId
        every { getStringExtra("multiapp.virtualActivityToken") } returns token
        every { getStringExtra("multiapp.guestActivityClassName") } returns guestActivityClassName
    }

    private fun explicitRemapIntent(activityClassName: String): Intent {
        val component = mockk<ComponentName>(relaxed = true) {
            every { packageName } returns "com.test.minimal"
            every { className } returns activityClassName
        }
        return mockk(relaxed = true) {
            every { this@mockk.component } returns component
            every { selector } returns null
            every { `package` } returns null
            every { flags } returns 0
            every { action } returns null
            every { dataString } returns null
            every { categories } returns emptySet()
            every { extras } returns null
        }
    }

    private fun sourceIntentForRecovery(
        flags: Int = 0,
        action: String? = null,
        dataString: String? = null
    ): Intent = mockk(relaxed = true) {
        every { this@mockk.flags } returns flags
        every { this@mockk.action } returns action
        every { this@mockk.dataString } returns dataString
        every { this@mockk.categories } returns emptySet()
        every { this@mockk.extras } returns null
    }

    private fun hostedBootstrapResult(snapshot: VirtualPackageSnapshot): HostedBootstrapResult {
        val stageResult = BootstrapResult.success(RuntimeStage.CONFIG, "test runtime")
        return HostedBootstrapResult(
            instanceId = snapshot.instanceId,
            installId = snapshot.originPackageName,
            originPackageName = snapshot.originPackageName,
            virtualPackageName = snapshot.virtualPackageName,
            applicationLabel = snapshot.applicationLabel,
            processSlot = "com.multiapp.app:v0",
            originApkPath = snapshot.sourceDir,
            dataRoot = snapshot.dataDir,
            guestClassLoader = javaClass.classLoader,
            guestApplication = mockk<Application>(relaxed = true),
            packageSnapshot = snapshot,
            launcherActivityClassName = snapshot.launcherActivityName,
            stageResults = listOf(stageResult),
            summary = listOf(stageResult).toSummary(),
            success = true
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun rememberHostedRuntime(
        instrumentation: VirtualInstrumentation,
        instanceId: String,
        hostApplication: Application,
        result: HostedBootstrapResult
    ) {
        val runtimeClass = Class.forName("${VirtualInstrumentation::class.java.name}\$HostedActivityRuntime")
        val constructor = runtimeClass.getDeclaredConstructor(Context::class.java, HostedBootstrapResult::class.java)
        constructor.isAccessible = true
        val runtime = constructor.newInstance(hostApplication, result)
        val cacheField = VirtualInstrumentation::class.java.getDeclaredField("hostedRuntimeCache")
        cacheField.isAccessible = true
        val cache = cacheField.get(instrumentation) as MutableMap<String, Any>
        cache[instanceId] = runtime
        val processRuntimeField = VirtualInstrumentation::class.java.getDeclaredField("processRuntime")
        processRuntimeField.isAccessible = true
        val processRuntime = processRuntimeField.get(instrumentation) as VirtualProcessRuntime
        processRuntime.rememberApplication(instanceId, result)
    }

    private fun snapshotForInstance(
        instanceId: String,
        virtualPackageName: String,
        originPackageName: String = "com.test.minimal"
    ): VirtualPackageSnapshot = VirtualPackageSnapshot(
        instanceId = instanceId,
        originPackageName = originPackageName,
        virtualPackageName = virtualPackageName,
        applicationLabel = "Minimal",
        versionCode = 1,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 23,
        sourceDir = "/tmp/minimal.apk",
        dataDir = "/tmp/$instanceId"
    )

    private fun invokePrivate(
        target: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?
    ) {
        val method = target.javaClass.getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private class RecordingVirtualActivityOperations(
        private val pending: VirtualActivityPendingNewIntent? = null,
        private val finishResult: VirtualActivityFinishResultRecord? = null,
        private val consumedResult: VirtualActivityResult? = null
    ) : VirtualActivityOperations {
        val pendingCalls = mutableListOf<Pair<String, String>>()
        val finishCalls = mutableListOf<Pair<String, String>>()
        val finishResultCalls = mutableListOf<Triple<String, String, Int>>()
        val setResultCalls = mutableListOf<Triple<String, String, Int>>()
        val consumeResultCalls = mutableListOf<Pair<String, String>>()

        override fun consumePendingNewIntent(
            instanceId: String,
            token: String
        ): VirtualActivityPendingNewIntent? {
            pendingCalls += instanceId to token
            return pending
        }

        override fun recordActivityResultForFinish(
            instanceId: String,
            token: String,
            resultCode: Int,
            dataIntent: VirtualIntentSnapshot?
        ): VirtualActivityFinishResultRecord {
            finishResultCalls += Triple(instanceId, token, resultCode)
            return finishResult ?: VirtualActivityFinishResultRecord(
                instanceId = instanceId,
                resultCode = resultCode,
                dataIntent = dataIntent,
                recorded = false,
                reason = "RESULT_ROUTE_MISSING"
            )
        }

        override fun setActivityResult(
            instanceId: String,
            token: String,
            resultCode: Int,
            dataIntent: VirtualIntentSnapshot?,
            requestCode: Int,
            resultWho: String?,
            frameworkDispatchAttempted: Boolean,
            frameworkDispatchInvoked: Boolean
        ): Boolean {
            setResultCalls += Triple(instanceId, token, resultCode)
            return true
        }

        override fun consumeActivityResult(instanceId: String, token: String): VirtualActivityResult? {
            consumeResultCalls += instanceId to token
            return consumedResult
        }

        override fun consumeActivityResultForResumeFallback(
            instanceId: String,
            token: String
        ): VirtualActivityResult? {
            consumeResultCalls += instanceId to token
            return consumedResult?.takeIf { !it.frameworkDispatchInvoked && it.requestCode >= 0 }
        }

        override fun markActivityResultDispatchState(
            instanceId: String,
            token: String,
            frameworkDispatchAttempted: Boolean,
            frameworkDispatchInvoked: Boolean
        ): Boolean = true

        override fun finishActivity(instanceId: String, token: String): Boolean {
            finishCalls += instanceId to token
            return true
        }
    }

    private class TestActivityLaunchAllocationProvider : VirtualActivityLaunchAllocationProvider {
        private var tokenIndex = 0

        override fun allocate(
            request: VirtualActivityLaunchAllocationRequest
        ): VirtualActivityLaunchAllocation {
            val hostPackageName = request.processSlot.substringBeforeLast(":v")
            val launchModes = ProxyActivitySlots.launchModeByClassName(hostPackageName)
            val proxyActivityClassName = ProxyActivitySlots.classNamesForProcessSlot(
                hostPackageName,
                request.processSlot
            ).first { className -> launchModes[className] == request.launchMode }
            val identity = VirtualActivityLaunchIdentity(
                capabilityToken = "instrumentation-capability-${++tokenIndex}",
                instanceId = request.instanceId,
                runtimeEpoch = 42L,
                engineSessionId = "engine-session-42",
                processSlot = request.processSlot,
                proxyActivityClassName = proxyActivityClassName,
                guestActivityClassName = request.guestActivityClassName
            )
            return VirtualActivityLaunchAllocation(
                accepted = true,
                request = request,
                proxyActivityClassName = proxyActivityClassName,
                launchIdentity = identity,
                reason = "activity_allocation_authorized"
            )
        }

        override fun release(allocation: VirtualActivityLaunchAllocation): Boolean = true
    }

    private class SameSlotRecordingAllocationProvider : VirtualActivityLaunchAllocationProvider {
        val allocations = mutableListOf<VirtualActivityLaunchAllocation>()
        val releasedCapabilityTokens = mutableListOf<String>()

        override fun allocate(
            request: VirtualActivityLaunchAllocationRequest
        ): VirtualActivityLaunchAllocation {
            val identity = VirtualActivityLaunchIdentity(
                capabilityToken = "batch-capability-${allocations.size + 1}",
                instanceId = request.instanceId,
                runtimeEpoch = 42L,
                engineSessionId = "engine-session-42",
                processSlot = request.processSlot,
                proxyActivityClassName = "com.multiapp.app.container.ProxyActivity0",
                guestActivityClassName = request.guestActivityClassName
            )
            return VirtualActivityLaunchAllocation(
                accepted = true,
                request = request,
                proxyActivityClassName = identity.proxyActivityClassName,
                launchIdentity = identity,
                reason = "activity_allocation_authorized"
            ).also(allocations::add)
        }

        override fun release(allocation: VirtualActivityLaunchAllocation): Boolean {
            releasedCapabilityTokens += allocation.launchIdentity?.capabilityToken.orEmpty()
            return true
        }
    }

    @Suppress("unused")
    private class ResultCallbackInstrumentation : Instrumentation() {
        var resultCallbackCount = 0

        fun callActivityOnActivityResult(
            activity: Activity,
            id: String?,
            requestCode: Int,
            resultCode: Int,
            data: Intent?
        ) {
            resultCallbackCount++
        }
    }
}
