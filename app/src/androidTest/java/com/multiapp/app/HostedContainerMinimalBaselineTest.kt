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
import org.junit.After
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
    private var createdInstanceId: String? = null

    @Before
    fun cleanPreviousBaselineState() {
        hiltRule.inject()
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        installStore.delete(minimalPackageName)
    }

    @After
    fun cleanupCreatedInstance() {
        val instanceId = createdInstanceId ?: return
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(targetContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(targetContext),
            installRecordStore = installStore
        )
        instanceManager.deleteInstance(instanceId)
    }

    @Test
    fun hostedContainerLaunchesInstalledMinimalApk() {
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
        val instance = instanceManager.createInstance(
            originPackageName = minimalPackageName,
            displayName = "MinimalTest baseline",
            compatibilityMode = CompatibilityMode.DEFAULT
        ).getOrThrow()
        createdInstanceId = instance.instanceId

        ActivityScenario.launch<ContainerActivity>(
            ContainerActivity.createIntent(targetContext, instance.instanceId, "androidTest:minimal-baseline")
        ).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(ContainerActivity::class.java.name, activity.javaClass.name)
            }
        }
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
