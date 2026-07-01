package com.multiapp.app

import android.content.pm.PackageInfo
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.app.container.ContainerActivity
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallMetadata
import com.multiapp.core.model.installer.InstallMetadataResolver
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.installer.ProductionVirtualInstallService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostedContainerMinimalBaselineTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager
    private val minimalPackageName = "com.test.minimal"
    @Before
    fun cleanPreviousBaselineState() {
        hiltRule.inject()
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        installStore.delete(minimalPackageName)
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(targetContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(targetContext),
            installRecordStore = installStore
        )
        instanceManager.getInstanceByOrigin(minimalPackageName).forEach { instance ->
            instanceManager.deleteInstance(instance.instanceId)
        }
        ContainerRuntimePaths.hostedLaunchEvidenceDir(targetContext)
            .listFiles()
            ?.forEach { file ->
                assertTrue("failed to delete stale hosted evidence: ${file.absolutePath}", file.deleteRecursively())
            }
    }

    @Test
    fun hostedContainerLaunchesTwoInstalledMinimalApkInstances() {
        val packageInfo = findInstalledMinimalPackage()
        assumeTrue("com.test.minimal must be installed by baseline script", packageInfo != null)
        packageInfo!!

        val appInfo = packageInfo.applicationInfo
        assertNotNull("minimal app sourceDir must exist", appInfo?.sourceDir)
        assertTrue(File(appInfo!!.sourceDir).isFile)

        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        val installService = ProductionVirtualInstallService(
            installRecordStore = installStore,
            artifactDir = ContainerRuntimePaths.artifactDir(targetContext),
            metadataResolver = InstallMetadataResolver { packageName, _ ->
                val info = findInstalledPackage(packageName) ?: return@InstallMetadataResolver InstallMetadata()
                InstallMetadata(
                    permissions = info.requestedPermissions?.toList().orEmpty(),
                    activities = info.activities.toComponentInfos(),
                    services = info.services.toComponentInfos(),
                    receivers = info.receivers.toComponentInfos(),
                    providers = info.providers.toComponentInfos()
                )
            }
        )
        val importResult = installService.importFromMetadata(
            packageName = minimalPackageName,
            originApkPath = appInfo.sourceDir,
            versionCode = packageInfo.longVersionCodeCompat(),
            versionName = packageInfo.versionName ?: "1.0",
            targetSdk = appInfo.targetSdkVersion,
            minSdk = appInfo.minSdkVersionCompat(),
            applicationClassName = appInfo.className,
            packageLabel = appInfo.loadLabel(packageManager).toString()
        )
        assertTrue(importResult.exceptionOrNull()?.stackTraceToString(), importResult.isSuccess)
        assertNotNull(installStore.load(minimalPackageName))

        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(targetContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(targetContext),
            installRecordStore = installStore
        )
        val instances = listOf("A", "B").map { label ->
            instanceManager.createInstance(
                originPackageName = minimalPackageName,
                displayName = "MinimalTest baseline $label",
                compatibilityMode = CompatibilityMode.DEFAULT
            ).getOrThrow().also { instance ->
                assertTrue(File(instance.dataRoot).isDirectory)
            }
        }
        assertEquals(2, instances.map { it.instanceId }.distinct().size)
        assertEquals(2, instances.map { it.virtualPackageName }.distinct().size)
        assertEquals(2, instances.map { it.dataRoot }.distinct().size)

        instances.forEachIndexed { index, instance ->
            ActivityScenario.launch<ContainerActivity>(
                ContainerActivity.createIntent(
                    targetContext,
                    instance.instanceId,
                    "androidTest:minimal-baseline-${index + 1}"
                )
            ).close()
            instrumentation.waitForIdleSync()
            waitForRuntimeEvidence(instance.instanceId, includeNewIntent = false)
            assertRuntimeEvidenceHasLine(instance.instanceId, "activity-lifecycle", "status=GUEST_ACTIVITY_LIFECYCLE")
            assertRuntimeEvidenceHasLine(instance.instanceId, "activity-lifecycle", "activityRecordFound=true")
            assertRuntimeEvidenceDoesNotHaveLine(
                instance.instanceId,
                "activity-lifecycle",
                "status=GUEST_ACTIVITY_LIFECYCLE_UNLINKED"
            )
            assertRuntimeEvidenceDoesNotHaveLine(instance.instanceId, "activity-lifecycle", "activityRecordFound=false")
            assertRuntimeEvidenceDoesNotHaveLine(instance.instanceId, "activity-lifecycle", "reason=TOKEN_MISSING")
            assertRuntimeEvidenceDoesNotHaveLine(
                instance.instanceId,
                "activity-lifecycle",
                "guestActivityClassName=com.multiapp.app.container.ContainerActivity"
            )
            assertRuntimeEvidenceDoesNotHaveLine(instance.instanceId, "activity-lifecycle", "reason=ACTIVITY_RECORD_MISSING")
            assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "status=ACTIVITY_RESULT_UNSUPPORTED")
            assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "resultSupported=false")
            assertRuntimeEvidenceHasLine(
                instance.instanceId,
                "activity-result",
                "unsupportedReason=HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED"
            )

            ActivityScenario.launch<ContainerActivity>(
                ContainerActivity.createIntent(
                    targetContext,
                    instance.instanceId,
                    "androidTest:minimal-new-intent-${index + 1}"
                )
            ).close()
            instrumentation.waitForIdleSync()
            waitForRuntimeEvidence(instance.instanceId, includeNewIntent = true)
            assertRuntimeEvidenceHasLine(instance.instanceId, "activity-new-intent", "status=GUEST_ACTIVITY_ON_NEW_INTENT")
            assertRuntimeEvidenceHasLine(instance.instanceId, "activity-new-intent", "pendingNewIntentConsumed=true")
            assertRuntimeEvidenceHasLine(instance.instanceId, "activity-new-intent", "reason=")
            assertRuntimeEvidenceDoesNotHaveLine(
                instance.instanceId,
                "activity-new-intent",
                "status=GUEST_ACTIVITY_ON_NEW_INTENT_UNLINKED"
            )
            assertRuntimeEvidenceDoesNotHaveLine(instance.instanceId, "activity-new-intent", "pendingNewIntentConsumed=false")
            assertRuntimeEvidenceDoesNotHaveLine(instance.instanceId, "activity-new-intent", "reason=TOKEN_MISSING")
            assertRuntimeEvidenceDoesNotHaveLine(
                instance.instanceId,
                "activity-new-intent",
                "reason=NO_PENDING_NEW_INTENT_RECORD"
            )
        }
    }

    private fun waitForRuntimeEvidence(instanceId: String, includeNewIntent: Boolean = false) {
        val requiredComponents = listOf(
            "launch",
            "activity-instrumentation",
            "activity-context",
            "activity-lifecycle",
            "activity-result",
            "provider-proxy",
            "service-proxy",
            "broadcast"
        ) + if (includeNewIntent) listOf("activity-new-intent") else emptyList()
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val missing = requiredComponents.filterNot { component ->
                ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
            }
            if (missing.isEmpty()) return
            Thread.sleep(100L)
        }
        val missing = requiredComponents.filterNot { component ->
            ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
        }
        assertTrue("missing hosted runtime evidence for $instanceId: $missing", missing.isEmpty())
    }

    private fun assertRuntimeEvidenceHasLine(
        instanceId: String,
        component: String,
        expectedLine: String
    ) {
        val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
        assertTrue("missing $component evidence for $instanceId", file.isFile)
        val text = file.readText()
        val lines = text.lines().map { it.trim() }
        assertTrue(
            "$component evidence for $instanceId did not contain exact line $expectedLine:\n$text",
            expectedLine in lines
        )
    }

    private fun assertRuntimeEvidenceDoesNotHaveLine(
        instanceId: String,
        component: String,
        unexpectedLine: String
    ) {
        val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
        assertTrue("missing $component evidence for $instanceId", file.isFile)
        val text = file.readText()
        val lines = text.lines().map { it.trim() }
        assertTrue(
            "$component evidence for $instanceId unexpectedly contained exact line $unexpectedLine:\n$text",
            unexpectedLine !in lines
        )
    }

    private fun findInstalledMinimalPackage(): PackageInfo? = findInstalledPackage(minimalPackageName)

    private fun findInstalledPackage(packageName: String): PackageInfo? {
        return runCatching {
            val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS or
                android.content.pm.PackageManager.GET_PROVIDERS or
                android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_META_DATA
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags)
            }
        }.getOrNull()
    }

    private fun Array<out android.content.pm.ComponentInfo>?.toComponentInfos(): List<ComponentInfo> {
        return this?.mapNotNull { component ->
            component.name?.takeIf { it.isNotBlank() }?.let { name ->
                ComponentInfo(name = name, exported = component.exported)
            }
        }.orEmpty()
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
    }

    private fun android.content.pm.ApplicationInfo.minSdkVersionCompat(): Int {
        return if (Build.VERSION.SDK_INT >= 24) minSdkVersion else 1
    }
}
