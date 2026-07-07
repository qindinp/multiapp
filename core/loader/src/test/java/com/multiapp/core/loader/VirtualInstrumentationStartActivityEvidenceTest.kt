package com.multiapp.core.loader

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.multiapp.core.model.virtual.VirtualActivityStack
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VirtualInstrumentationStartActivityEvidenceTest {

    @AfterTest
    fun tearDown() {
        VirtualActivityIntentStore.clearAll()
        VirtualActivityIntentStore.resetIntentCopierForTest()
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
    fun `remap evidence records overload and unsupported result routing`(@TempDir filesDir: File) {
        val instrumentation = VirtualInstrumentation(mockk<Instrumentation>(relaxed = true))

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
                String::class.java
            ),
            filesDir,
            "inst-001",
            "com.test.minimal.DetailActivity",
            "com.multiapp.app.container.ProxyActivitySingleTop0",
            "execStartActivity:activity-options",
            42,
            "explicit",
            "singleTop"
        )

        val lines = remapEvidenceLines(filesDir)
        assertTrue("status=GUEST_ACTIVITY_REMAP" in lines)
        assertTrue("stage=ACTIVITY_START_REMAP" in lines)
        assertTrue("api=execStartActivity:activity-options" in lines)
        assertTrue("hostFallback=false" in lines)
        assertTrue("requestCode=42" in lines)
        assertTrue("resultRequested=true" in lines)
        assertTrue("activityResultVerdict=UNSUPPORTED" in lines)
        assertTrue("activityResultVerdictReason=HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED" in lines)
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
    fun `activity record recovery skips incomplete proxy metadata`() {
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
        originalGuestIntent: Intent? = sourceIntentForRecovery()
    ): Intent = mockk(relaxed = true) {
        every { getStringExtra("multiapp.virtualActivityToken") } returns token
        every { getStringExtra("multiapp.originPackageName") } returns originPackageName
        every { getStringExtra("multiapp.guestActivityLaunchMode") } returns launchMode
        every { getStringExtra("multiapp.guestTaskAffinity") } returns taskAffinity
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
}
