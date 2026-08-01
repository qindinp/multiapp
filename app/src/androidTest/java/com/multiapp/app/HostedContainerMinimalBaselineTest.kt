package com.multiapp.app

import android.content.pm.PackageInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.installer.VirtualInstallService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostedContainerMinimalBaselineTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var virtualizationEngine: VirtualizationEngine

    @Inject
    lateinit var virtualInstallService: VirtualInstallService

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager
    private val minimalPackageName = "com.test.minimal"
    @Before
    fun cleanPreviousBaselineState() {
        hiltRule.inject()
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(targetContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(targetContext),
            installRecordStore = installStore
        )
        instanceManager.getInstanceByOrigin(minimalPackageName).forEach { instance ->
            val result = virtualizationEngine.deleteInstance(instance.instanceId)
            assertTrue(
                "engine cleanup failed for ${instance.instanceId}: ${result.status}:${result.message}",
                result.status == EngineResultStatus.PASS || result.status == EngineResultStatus.PARTIAL
            )
        }
        assertTrue(
            "engine cleanup left baseline instances behind",
            instanceManager.getInstanceByOrigin(minimalPackageName).isEmpty()
        )
        installStore.delete(minimalPackageName)
        ContainerRuntimePaths.hostedLaunchEvidenceDir(targetContext)
            .listFiles()
            ?.forEach { file ->
                assertTrue("failed to delete stale hosted evidence: ${file.absolutePath}", file.deleteRecursively())
            }
    }

    @Test
    fun hostedContainerLaunchesTwoInstalledMinimalApkInstances() {
        val packageInfo = checkNotNull(findInstalledMinimalPackage()) {
            "com.test.minimal must be installed by baseline script"
        }

        val appInfo = packageInfo.applicationInfo
        assertNotNull("minimal app sourceDir must exist", appInfo?.sourceDir)
        assertTrue(File(appInfo!!.sourceDir).isFile)

        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        val importResult = virtualInstallService.importFromMetadata(
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
        val installRecord = checkNotNull(installStore.load(minimalPackageName))
        assertEquals(
            "singleTop",
            installRecord.activities.firstOrNull { it.name == "com.test.minimal.MainActivity" }?.launchMode
        )

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
                val initialLaunch = virtualizationEngine.launchInstance(
                    LaunchInstanceRequest(
                        instanceId = instance.instanceId,
                        reason = "androidTest:minimal-baseline-${index + 1}"
                    )
                )
                assertEquals(
                    "engine launch failed for ${instance.instanceId}: ${initialLaunch.message}",
                    EngineResultStatus.PASS,
                    initialLaunch.status
                )
                instrumentation.waitForIdleSync()
                waitForRuntimeEvidence(instance.instanceId, includeNewIntent = false)
                waitForProviderProbe(instance.dataRoot, instance.instanceId)
                assertRuntimeEvidenceHasLine(instance.instanceId, "package-manager-proxy", "stage=PACKAGE_MANAGER_PROXY")
                assertRuntimeEvidenceHasAnyLine(
                    instance.instanceId,
                    "package-manager-proxy",
                    setOf("status=SUCCESS", "status=DEGRADED")
                )
                assertRuntimeEvidenceHasLineStartingWith(
                    instance.instanceId,
                    "package-manager-proxy",
                    "globalPmsProxyEnabled="
                )
                assertRuntimeEvidenceHasLineStartingWith(
                    instance.instanceId,
                    "package-manager-proxy",
                    "sPackageManagerPatched="
                )
                assertRuntimeEvidenceHasLine(
                    instance.instanceId,
                    "package-manager-proxy",
                    "uidAggregateVirtualizationMode=merge-packages-preserve-name"
                )
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
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "status=ACTIVITY_RESULT_NOT_REQUESTED")
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "resultPipelineInstalled=true")
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "resultRequested=false")
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "resultDelivered=false")
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "unsupportedReason=")

                val newIntentAction = "com.test.minimal.NEW_INTENT_PROBE.${instance.instanceId}"
                val newIntentLaunch = virtualizationEngine.launchInstance(
                    LaunchInstanceRequest(
                        instanceId = instance.instanceId,
                        reason = "androidTest:minimal-new-intent-${index + 1}",
                        launchAction = newIntentAction
                    )
                )
                assertEquals(
                    "engine re-launch failed for ${instance.instanceId}: ${newIntentLaunch.message}",
                    EngineResultStatus.PASS,
                    newIntentLaunch.status
                )
                instrumentation.waitForIdleSync()
                waitForRuntimeEvidence(instance.instanceId, includeNewIntent = true)
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-new-intent", "status=GUEST_ACTIVITY_ON_NEW_INTENT")
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-new-intent", "pendingNewIntentConsumed=true")
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-new-intent", "pendingAction=$newIntentAction")
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-new-intent", "intentAction=$newIntentAction")
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

                val resultProbeAction = "com.test.minimal.ACTION_ACTIVITY_RESULT_PROBE"
                val resultResponseAction = "com.test.minimal.ACTION_ACTIVITY_RESULT_RESPONSE"
                val resultLaunch = virtualizationEngine.launchInstance(
                    LaunchInstanceRequest(
                        instanceId = instance.instanceId,
                        reason = "androidTest:minimal-activity-result-${index + 1}",
                        launchAction = resultProbeAction
                    )
                )
                assertEquals(
                    "engine result probe failed for ${instance.instanceId}: ${resultLaunch.message}",
                    EngineResultStatus.PASS,
                    resultLaunch.status
                )
                waitForActivityResultProbe(instance.dataRoot, instance.instanceId, resultResponseAction)
                assertRuntimeEvidenceHasLine(
                    instance.instanceId,
                    "activity-result",
                    "status=ACTIVITY_RESULT_DELIVERED"
                )
                assertRuntimeEvidenceHasLine(instance.instanceId, "activity-result", "requestCode=4242")
                assertRuntimeEvidenceHasLine(
                    instance.instanceId,
                    "activity-result",
                    "dataAction=$resultResponseAction"
                )
        }
    }

    private fun waitForActivityResultProbe(
        dataRoot: String,
        instanceId: String,
        expectedAction: String
    ) {
        val probe = File(dataRoot, "files/activity-result-probe.txt")
        val evidence = ContainerRuntimePaths.hostedRuntimeEvidenceFile(
            targetContext,
            instanceId,
            "activity-result"
        )
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val probeLines = probe.takeIf(File::isFile)?.readLines()?.map(String::trim).orEmpty()
            val evidenceLines = evidence.takeIf(File::isFile)?.readLines()?.map(String::trim).orEmpty()
            if (
                "status=DELIVERED" in probeLines &&
                "requestCode=4242" in probeLines &&
                "resultCode=-1" in probeLines &&
                "action=$expectedAction" in probeLines &&
                "status=ACTIVITY_RESULT_DELIVERED" in evidenceLines
            ) {
                return
            }
            Thread.sleep(100L)
        }
        assertTrue("missing guest Activity result probe for $instanceId: ${probe.takeIf(File::isFile)?.readText()}", false)
    }

    private fun waitForProviderProbe(dataRoot: String, instanceId: String) {
        val probe = File(dataRoot, "files/provider-probe.txt")
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val lines = probe.takeIf(File::isFile)?.readLines()?.map(String::trim).orEmpty()
            if (
                "provider.queryStatus: QUERY_OK" in lines &&
                "provider.callStatus: CALL_OK" in lines &&
                "provider.updateRows: 1" in lines &&
                "provider.deleteRows: 1" in lines &&
                "provider.bulkRows: 2" in lines &&
                "provider.openFilePayload: provider-open-ok" in lines &&
                "provider.openAssetFilePayload: provider-open-ok" in lines &&
                "provider.openTypedAssetFilePayload: provider-open-ok" in lines
            ) {
                return
            }
            Thread.sleep(100L)
        }
        assertTrue(
            "missing real guest Provider results for $instanceId: ${probe.takeIf(File::isFile)?.readText()}",
            false
        )
    }

    private fun waitForRuntimeEvidence(instanceId: String, includeNewIntent: Boolean = false) {
        val requiredComponents = listOf(
            "launch",
            "package-manager-proxy",
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

    private fun assertRuntimeEvidenceHasAnyLine(
        instanceId: String,
        component: String,
        expectedLines: Set<String>
    ) {
        val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
        assertTrue("missing $component evidence for $instanceId", file.isFile)
        val text = file.readText()
        val lines = text.lines().map { it.trim() }
        assertTrue(
            "$component evidence for $instanceId did not contain any expected line $expectedLines:\n$text",
            expectedLines.any { it in lines }
        )
    }

    private fun assertRuntimeEvidenceHasLineStartingWith(
        instanceId: String,
        component: String,
        expectedPrefix: String
    ) {
        val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
        assertTrue("missing $component evidence for $instanceId", file.isFile)
        val text = file.readText()
        val lines = text.lines().map { it.trim() }
        assertTrue(
            "$component evidence for $instanceId did not contain line starting with $expectedPrefix:\n$text",
            lines.any { it.startsWith(expectedPrefix) }
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

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
    }

    private fun android.content.pm.ApplicationInfo.minSdkVersionCompat(): Int {
        return if (Build.VERSION.SDK_INT >= 24) minSdkVersion else 1
    }
}
