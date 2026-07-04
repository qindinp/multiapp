package com.multiapp.app

import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.app.container.ContainerActivity
import com.multiapp.app.container.ContainerRuntimePaths
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
class HostedContainerPr9ProviderMethodEvidenceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager
    private val minimalPackageName = "com.test.minimal"
    private val pr9ProviderMethodEvidence = mapOf(
        "provider-query" to "QUERY",
        "provider-insert" to "INSERT",
        "provider-update" to "UPDATE",
        "provider-delete" to "DELETE",
        "provider-call" to "CALL",
        "provider-open-file" to "OPEN_FILE",
        "provider-open-asset-file" to "OPEN_ASSET_FILE",
        "provider-bulk-insert" to "BULK_INSERT"
    )

    @Before
    fun cleanPreviousPr9ProviderState() {
        hiltRule.inject()
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        installStore.delete(minimalPackageName)
        val instanceManager = DefaultInstanceManager(
            store = JsonInstanceRecordStore(ContainerRuntimePaths.instanceStoreDir(targetContext)),
            dataRootBase = ContainerRuntimePaths.instanceDataRootBase(targetContext),
            installRecordStore = installStore
        )
        val previousInstances = instanceManager.getInstanceByOrigin(minimalPackageName)
        previousInstances.forEach { instance ->
            deletePr9ProviderEvidence(instance.instanceId)
            instanceManager.deleteInstance(instance.instanceId)
        }
        deletePr9ProviderEvidenceFilesByComponentName()
    }

    @After
    fun restoreGlobalRuntimeHooks() {
        VirtualInstrumentationInstaller.restore()
    }

    @Test
    fun hostedContainerRecordsPr9ProviderMethodEvidence() {
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
            displayName = "MinimalTest PR9 Provider Methods",
            compatibilityMode = CompatibilityMode.DEFAULT
        ).getOrThrow()

        val instrumentationInstall = VirtualInstrumentationInstaller.install()
        assertTrue(instrumentationInstall.exceptionOrNull()?.stackTraceToString(), instrumentationInstall.isSuccess)
        assertTrue(
            "VirtualInstrumentation must be installed before launching the hosted proxy Activity",
            VirtualInstrumentationInstaller.isInstalled()
        )

        val scenario = ActivityScenario.launch<ContainerActivity>(
            ContainerActivity.createIntent(
                targetContext,
                instance.instanceId,
                "androidTest:pr9-provider-method-evidence"
            )
        )
        try {
            instrumentation.waitForIdleSync()
            waitForPr9ProviderEvidence(instance.instanceId)
        } finally {
            scenario.close()
        }

        pr9ProviderMethodEvidence.forEach { (component, operation) ->
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "stage=PROVIDER_PROXY")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "instanceId=${instance.instanceId}")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "originPackageName=$minimalPackageName")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "virtualPackageName=${instance.virtualPackageName}")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "guestAuthority=com.test.minimal.probe")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "providerClassName=com.test.minimal.ProbeProvider")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "evidenceOperation=$operation")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "evidenceSuccess=true")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "providerExported=false")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "providerPermission=com.test.minimal.permission.PROBE_PROVIDER")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "providerGrantUriPermissions=true")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "providerPolicyStatus=INTERNAL_ONLY")
            assertRuntimeEvidenceHasLine(
                instance.instanceId,
                component,
                "providerPolicyReason=exported=false;permission=com.test.minimal.permission.PROBE_PROVIDER;grantUriPermissions=true"
            )
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "providerRoutingScope=INSTANCE")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "processWideProviderHook=false")
            assertRuntimeEvidenceHasLine(instance.instanceId, component, "authorityRewriteEntry=VirtualContentResolver")
            assertRuntimeEvidenceHasAnyLine(component = component, instanceId = instance.instanceId, expectedLines = setOf(
                "status=PROVIDER_CREATED",
                "status=PROVIDER_CACHED"
            ))
        }
    }

    private fun waitForPr9ProviderEvidence(instanceId: String) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val missing = pr9ProviderMethodEvidence.keys.filterNot { component ->
                ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
            }
            if (missing.isEmpty()) return
            Thread.sleep(100L)
        }
        val missing = pr9ProviderMethodEvidence.keys.filterNot { component ->
            ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
        }
        assertTrue("missing PR-9 provider method evidence for $instanceId: $missing", missing.isEmpty())
    }

    private fun deletePr9ProviderEvidence(instanceId: String) {
        pr9ProviderMethodEvidence.keys.forEach { component ->
            val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
            if (file.exists()) {
                assertTrue("failed to delete stale PR-9 provider evidence: ${file.absolutePath}", file.delete())
            }
        }
    }

    private fun deletePr9ProviderEvidenceFilesByComponentName() {
        ContainerRuntimePaths.hostedLaunchEvidenceDir(targetContext)
            .listFiles()
            ?.filter { file -> file.isFile && pr9ProviderMethodEvidence.keys.any { component -> component in file.name } }
            ?.forEach { file ->
                assertTrue("failed to delete stale PR-9 provider evidence: ${file.absolutePath}", file.delete())
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
                ComponentInfo(
                    name = name,
                    exported = component.exported,
                    permission = (component as? ProviderInfo)?.providerPermission(),
                    grantUriPermissions = (component as? ProviderInfo)?.grantUriPermissions ?: false
                )
            }
        }.orEmpty()
    }

    private fun ProviderInfo.providerPermission(): String? {
        val readPermission = readPermission?.takeIf { it.isNotBlank() }
        val writePermission = writePermission?.takeIf { it.isNotBlank() }
        return when {
            readPermission == null && writePermission == null -> null
            readPermission == writePermission -> readPermission
            else -> listOfNotNull(
                readPermission?.let { "read=$it" },
                writePermission?.let { "write=$it" }
            ).joinToString(";")
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
    }

    private fun android.content.pm.ApplicationInfo.minSdkVersionCompat(): Int {
        return if (Build.VERSION.SDK_INT >= 24) minSdkVersion else 1
    }
}
