package com.multiapp.app

import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.app.container.ContainerActivity
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.loader.VirtualInstrumentationInstaller
import com.multiapp.core.loader.VirtualStoragePathDiagnostics
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.InstallMetadata
import com.multiapp.core.model.installer.InstallMetadataResolver
import com.multiapp.core.model.installer.JsonInstallRecordStore
import com.multiapp.core.model.installer.ProductionVirtualInstallService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostedContainerPr10StorageEvidenceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager
    private val minimalPackageName = "com.test.minimal"
    private val pr10JavaEvidence = mapOf(
        "storage-java-data-data" to "/data/data/$minimalPackageName/files/pr10-data-data.txt",
        "storage-java-data-user" to "/data/user/0/$minimalPackageName/files/pr10-data-user.txt",
        "storage-java-sdcard" to "/sdcard/Android/data/$minimalPackageName/files/pr10-sdcard.txt",
        "storage-java-storage-emulated" to
            "/storage/emulated/0/Android/data/$minimalPackageName/files/pr10-storage-emulated.txt"
    )
    private val pr10NativeEvidence = VirtualStoragePathDiagnostics.DEFAULT_NATIVE_IO_OPERATIONS
        .associate { operation -> "storage-native-$operation" to operation }
    private val pr10EvidenceComponents = pr10JavaEvidence.keys + pr10NativeEvidence.keys

    @Before
    fun cleanPreviousPr10StorageState() {
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
            deletePr10Evidence(instance.instanceId)
            instanceManager.deleteInstance(instance.instanceId)
        }
        deletePr10EvidenceFilesByComponentName()
    }

    @After
    fun restoreGlobalRuntimeHooks() {
        VirtualInstrumentationInstaller.restore()
    }

    @Test
    fun hostedContainerRecordsPr10StorageEvidenceForTwoInstances() {
        val packageInfo = checkNotNull(findInstalledMinimalPackage()) {
            "com.test.minimal must be installed before this test"
        }
        val applicationInfo = checkNotNull(packageInfo.applicationInfo) {
            "minimal app applicationInfo must exist"
        }
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
        val first = instanceManager.createInstance(
            originPackageName = minimalPackageName,
            displayName = "MinimalTest PR10 Storage A",
            compatibilityMode = CompatibilityMode.DEFAULT
        ).getOrThrow()
        val second = instanceManager.createInstance(
            originPackageName = minimalPackageName,
            displayName = "MinimalTest PR10 Storage B",
            compatibilityMode = CompatibilityMode.DEFAULT
        ).getOrThrow()

        val instrumentationInstall = VirtualInstrumentationInstaller.install()
        assertTrue(instrumentationInstall.exceptionOrNull()?.stackTraceToString(), instrumentationInstall.isSuccess)
        assertTrue(
            "VirtualInstrumentation must be installed before launching the hosted proxy Activity",
            VirtualInstrumentationInstaller.isInstalled()
        )

        launchAndWaitForPr10Evidence(first)
        launchAndWaitForPr10Evidence(second)

        val firstMarkers = assertPr10StorageEvidence(first)
        val secondMarkers = assertPr10StorageEvidence(second)
        assertNotEquals(File(first.dataRoot).canonicalPath, File(second.dataRoot).canonicalPath)
        pr10JavaEvidence.keys.forEach { component ->
            val firstMarker = checkNotNull(firstMarkers[component])
            val secondMarker = checkNotNull(secondMarkers[component])
            assertNotEquals(firstMarker.canonicalPath, secondMarker.canonicalPath)
            assertTrue(firstMarker.readText().contains("instanceId=${first.instanceId}"))
            assertTrue(secondMarker.readText().contains("instanceId=${second.instanceId}"))
            assertTrue(!firstMarker.readText().contains("instanceId=${second.instanceId}"))
            assertTrue(!secondMarker.readText().contains("instanceId=${first.instanceId}"))
        }
    }

    private fun launchAndWaitForPr10Evidence(instance: VirtualInstanceRecord) {
        val scenario = ActivityScenario.launch<ContainerActivity>(
            ContainerActivity.createIntent(
                targetContext,
                instance.instanceId,
                "androidTest:pr10-storage-evidence"
            )
        )
        try {
            instrumentation.waitForIdleSync()
            waitForPr10Evidence(instance.instanceId)
        } finally {
            scenario.close()
        }
    }

    private fun assertPr10StorageEvidence(instance: VirtualInstanceRecord): Map<String, File> {
        val dataRootPath = File(instance.dataRoot).canonicalPath
        val markers = linkedMapOf<String, File>()
        pr10JavaEvidence.forEach { (component, originalPath) ->
            val fields = runtimeEvidenceFields(instance.instanceId, component)
            assertEquals("STORAGE_PATH_DIAGNOSTIC", fields["stage"])
            assertEquals(instance.instanceId, fields["instanceId"])
            assertEquals(minimalPackageName, fields["originPackageName"])
            assertEquals(instance.virtualPackageName, fields["virtualPackageName"])
            assertEquals("JAVA_ABSOLUTE_PATH", fields["storageDiagnosticKind"])
            assertEquals("REDIRECTED", fields["storageDiagnosticStatus"])
            assertEquals(originalPath, fields["originalPath"])
            assertEquals("ContainerActivity.PR10_STORAGE_DIAGNOSTICS", fields["caller"])
            assertEquals("true", fields["withinDataRoot"])

            val redirectedPath = File(checkNotNull(fields["redirectedPath"])).canonicalPath
            assertTrue("$component redirected outside dataRoot: $redirectedPath", redirectedPath.startsWith(dataRootPath))
            val marker = File(checkNotNull(fields["isolationMarkerPath"]))
            assertTrue("$component marker missing: ${marker.absolutePath}", marker.isFile)
            assertTrue(marker.canonicalPath.startsWith(dataRootPath))
            assertTrue(fields["isolationMarkerContent"].orEmpty().contains("instanceId=${instance.instanceId}"))
            markers[component] = marker
        }

        pr10NativeEvidence.forEach { (component, operation) ->
            val fields = runtimeEvidenceFields(instance.instanceId, component)
            assertEquals("STORAGE_PATH_DIAGNOSTIC", fields["stage"])
            assertEquals(instance.instanceId, fields["instanceId"])
            assertEquals(minimalPackageName, fields["originPackageName"])
            assertEquals(instance.virtualPackageName, fields["virtualPackageName"])
            assertEquals("NATIVE_IO", fields["storageDiagnosticKind"])
            assertEquals("UNSUPPORTED", fields["storageDiagnosticStatus"])
            assertEquals("UNSUPPORTED", fields["nativeIoDiagnosticStatus"])
            assertEquals(operation, fields["nativeIoOperation"])
            assertEquals("NATIVE_IO_HOOK_NOT_INSTALLED_FOR_ORDINARY_BASELINE", fields["reason"])
            assertEquals("", fields["redirectedPath"])
            assertEquals("true", fields["candidateWithinDataRoot"])
            val candidatePath = File(checkNotNull(fields["candidateRedirectedPath"])).canonicalPath
            assertTrue("$component candidate outside dataRoot: $candidatePath", candidatePath.startsWith(dataRootPath))
        }
        return markers
    }

    private fun waitForPr10Evidence(instanceId: String) {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            val missing = pr10EvidenceComponents.filterNot { component ->
                ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
            }
            if (missing.isEmpty()) return
            Thread.sleep(100L)
        }
        val missing = pr10EvidenceComponents.filterNot { component ->
            ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component).isFile
        }
        assertTrue("missing PR-10 storage evidence for $instanceId: $missing", missing.isEmpty())
    }

    private fun deletePr10Evidence(instanceId: String) {
        pr10EvidenceComponents.forEach { component ->
            val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
            if (file.exists()) {
                assertTrue("failed to delete stale PR-10 storage evidence: ${file.absolutePath}", file.delete())
            }
        }
    }

    private fun deletePr10EvidenceFilesByComponentName() {
        ContainerRuntimePaths.hostedLaunchEvidenceDir(targetContext)
            .listFiles()
            ?.filter { file -> file.isFile && pr10EvidenceComponents.any { component -> component in file.name } }
            ?.forEach { file ->
                assertTrue("failed to delete stale PR-10 storage evidence: ${file.absolutePath}", file.delete())
            }
    }

    private fun runtimeEvidenceFields(instanceId: String, component: String): Map<String, String> {
        val file = ContainerRuntimePaths.hostedRuntimeEvidenceFile(targetContext, instanceId, component)
        assertTrue("missing $component evidence for $instanceId", file.isFile)
        return file.readLines().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator < 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
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
            if (android.os.Build.VERSION.SDK_INT >= 33) {
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
        return if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
    }

    private fun android.content.pm.ApplicationInfo.minSdkVersionCompat(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= 24) minSdkVersion else 1
    }
}
