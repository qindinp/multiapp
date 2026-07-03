package com.multiapp.app

import android.content.pm.PackageInfo
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.app.container.ContainerActivity
import com.multiapp.app.container.ContainerAmsApiEvidenceRecorder
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorders
import com.multiapp.core.loader.VirtualInstrumentationInstaller
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
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostedContainerPr8AmsApiEvidenceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager
    private val minimalPackageName = "com.test.minimal"
    private val pr8AmsApiEvidenceComponents = listOf(
        "ams-register-receiver",
        "ams-sticky-ordered-broadcast",
        "ams-bind-service-overload"
    )

    @Before
    fun cleanPreviousPr8AmsApiState() {
        hiltRule.inject()
        VirtualAmsApiEvidenceRecorders.install(ContainerAmsApiEvidenceRecorder(targetContext))
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        installStore.delete(minimalPackageName)
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(targetContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(targetContext),
            installRecordStore = installStore
        )
        val previousInstances = instanceManager.getInstanceByOrigin(minimalPackageName)
        previousInstances.forEach { instance ->
            deletePr8AmsApiEvidence(instance.instanceId)
            instanceManager.deleteInstance(instance.instanceId)
        }
        deletePr8AmsApiEvidenceFilesByComponentName()
    }

    @After
    fun restoreGlobalRuntimeHooks() {
        VirtualAmsApiEvidenceRecorders.reset()
        VirtualInstrumentationInstaller.restore()
    }

    @Test
    fun hostedContainerRecordsPr8AmsApiEvidence() {
        val installedPackageInfo = findInstalledMinimalPackage()
        assertNotNull("com.test.minimal must be installed before this test", installedPackageInfo)
        val packageInfo = checkNotNull(installedPackageInfo)

        val appInfo = packageInfo.applicationInfo
        assertNotNull("minimal app applicationInfo must exist", appInfo)
        val applicationInfo = checkNotNull(appInfo)
        assertNotNull("minimal app sourceDir must exist", applicationInfo.sourceDir)
        assertTrue(File(applicationInfo.sourceDir).isFile)

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
            originApkPath = applicationInfo.sourceDir,
            versionCode = packageInfo.longVersionCodeCompat(),
            versionName = packageInfo.versionName ?: "1.0",
            targetSdk = applicationInfo.targetSdkVersion,
            minSdk = applicationInfo.minSdkVersionCompat(),
            applicationClassName = applicationInfo.className,
            packageLabel = applicationInfo.loadLabel(packageManager).toString()
        )
        assertTrue(importResult.exceptionOrNull()?.stackTraceToString(), importResult.isSuccess)

        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(targetContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(targetContext),
            installRecordStore = installStore
        )
        val instance = instanceManager.createInstance(
            originPackageName = minimalPackageName,
            displayName = "MinimalTest PR8 AMS API",
            compatibilityMode = CompatibilityMode.DEFAULT
        ).getOrThrow()

        ActivityScenario.launch<ContainerActivity>(
            ContainerActivity.createIntent(
                targetContext,
                instance.instanceId,
                "androidTest:pr8-ams-api-evidence"
            )
        ).close()
        instrumentation.waitForIdleSync()

        waitForPr8AmsApiEvidence(instance.instanceId)

        pr8AmsApiEvidenceComponents.forEach { component ->
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "instanceId=${instance.instanceId}")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "originPackageName=$minimalPackageName")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "virtualPackageName=${instance.virtualPackageName}")
        }

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-register-receiver", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-register-receiver", "api=registerReceiver")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-register-receiver", "status=DYNAMIC_RECEIVER_REGISTERED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-register-receiver", "registered=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-register-receiver", "hostFallback=false")

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-sticky-ordered-broadcast", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-sticky-ordered-broadcast", "api=sendStickyOrderedBroadcast")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-sticky-ordered-broadcast", "status=STICKY_ORDERED_INTERCEPTED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-sticky-ordered-broadcast", "hostFallback=false")
        assertRuntimeEvidenceHasLineStartingWith(instance.instanceId, "ams-sticky-ordered-broadcast", "dispatchStatus=")

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "status=BIND_BLOCKED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "returnValue=false")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "hostFallback=false")
        assertRuntimeEvidenceHasLineStartingWith(instance.instanceId, "ams-bind-service-overload", "api=")
        assertRuntimeEvidenceHasLineStartingWith(instance.instanceId, "ams-bind-service-overload", "serviceResolved=")
    }

    private fun waitForPr8AmsApiEvidence(instanceId: String) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val missing = pr8AmsApiEvidenceComponents.filterNot { component ->
                ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
            }
            if (missing.isEmpty()) return
            Thread.sleep(100L)
        }
        val missing = pr8AmsApiEvidenceComponents.filterNot { component ->
            ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
        }
        assertTrue("missing PR-8 AMS API evidence for $instanceId: $missing", missing.isEmpty())
    }

    private fun deletePr8AmsApiEvidence(instanceId: String) {
        pr8AmsApiEvidenceComponents.forEach { component ->
            val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
            if (file.exists()) {
                assertTrue("failed to delete stale PR-8 AMS API evidence: ${file.absolutePath}", file.delete())
            }
        }
    }

    private fun deletePr8AmsApiEvidenceFilesByComponentName() {
        ContainerRuntimePaths.hostedLaunchEvidenceDir(targetContext)
            .listFiles()
            ?.filter { file -> file.isFile && pr8AmsApiEvidenceComponents.any { component -> component in file.name } }
            ?.forEach { file ->
                assertTrue("failed to delete stale PR-8 AMS API evidence: ${file.absolutePath}", file.delete())
            }
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
