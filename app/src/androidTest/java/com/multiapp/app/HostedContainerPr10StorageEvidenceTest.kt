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
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.JsonInstallRecordStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostedContainerPr10StorageEvidenceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var virtualizationEngine: VirtualizationEngine

    private lateinit var activityScenario: ActivityScenario<MainActivity>

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
        // host Activity 前台化：MIUI BAL 视窗检查（2026-08-01）
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        installStore.delete(minimalPackageName)
        // engine facade 清理：owner store 只在 :engine 进程构造（2026-08-01 修复）
        virtualizationEngine.listInstances()
            .filter { it.originPackageName == minimalPackageName }
            .forEach { instance ->
                deletePr10Evidence(instance.instanceId)
                val result = virtualizationEngine.deleteInstance(instance.instanceId)
                assertTrue(
                    "engine cleanup failed for ${instance.instanceId}: ${result.status}:${result.message}",
                    result.status == EngineResultStatus.PASS || result.status == EngineResultStatus.PARTIAL
                )
            }
        deletePr10EvidenceFilesByComponentName()
    }

    @After
    fun restoreGlobalRuntimeHooks() {
        if (::activityScenario.isInitialized) {
            activityScenario.close()
        }
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

        // engine.createInstance 在 install record 缺失时会 ensureInstallRecord 自动导入
        // （2026-08-01 真机修正：refreshPackage 是"仅刷新已有记录"语义）
        val installRequest = EnginePackageInstallRequest(
            originPackageName = minimalPackageName,
            originApkPath = applicationInfo.sourceDir,
            versionCode = packageInfo.longVersionCodeCompat(),
            versionName = packageInfo.versionName ?: "1.0",
            targetSdk = applicationInfo.targetSdkVersion,
            minSdk = applicationInfo.minSdkVersionCompat(),
            applicationClassName = applicationInfo.className,
            packageLabel = applicationInfo.loadLabel(packageManager).toString()
        )
        fun createStorageInstance(label: String, displayName: String): VirtualInstanceRecord {
            val result = virtualizationEngine.createInstance(
                CreateInstanceRequest(
                    creationRequestId = "androidTest-pr10-storage-$label-${System.currentTimeMillis()}",
                    install = installRequest,
                    displayName = displayName,
                    compatibilityMode = CompatibilityMode.DEFAULT
                )
            )
            assertTrue(
                "engine createInstance failed for $label: ${result.status}:${result.message}",
                result.status == EngineResultStatus.PASS
            )
            val instanceId = checkNotNull(result.instanceId) { "createInstance returned no instanceId for $label" }
            return virtualizationEngine.listInstances().first { it.instanceId == instanceId }
        }
        val first = createStorageInstance("A", "MinimalTest PR10 Storage A")
        val second = createStorageInstance("B", "MinimalTest PR10 Storage B")

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
        // engine.launchInstance 建立 runtime 状态（package snapshot/进程绑定），
        // 否则 ContainerActivity 的 bindApplication 无法构建 binding fingerprint
        // （2026-08-01 真机定位：Unable to build hosted runtime binding fingerprint）
        val launchResult = virtualizationEngine.launchInstance(
            com.multiapp.core.model.engine.LaunchInstanceRequest(
                instanceId = instance.instanceId,
                reason = "androidTest:pr10-storage-evidence"
            )
        )
        assertTrue(
            "engine launchInstance failed for ${instance.instanceId}: ${launchResult.status}:${launchResult.message}",
            launchResult.status == EngineResultStatus.PASS
        )
        val containerIntent = ContainerActivity.createIntent(
            targetContext,
            instance.instanceId,
            "androidTest:pr10-storage-evidence"
        )
        // storage evidence 由 ContainerActivity 在 evidenceMode=FULL 时写入（intent extra，
        // 非 launchInstance；2026-08-01 真机定位）
        containerIntent.putExtra(
            com.multiapp.core.model.engine.EngineLaunchIntentContract.EXTRA_ENGINE_EVIDENCE_MODE,
            com.multiapp.core.model.engine.EngineEvidenceMode.FULL.name
        )
        val scenario = ActivityScenario.launch<ContainerActivity>(containerIntent)
        try {
            instrumentation.waitForIdleSync()
            waitForPr10Evidence(instance.instanceId)
        } finally {
            scenario.safeClose()
        }
    }

    private fun ActivityScenario<*>.safeClose() {
        try {
            close()
        } catch (throwable: Throwable) {
            if (!throwable.isAndroidXCreatedStageCleanupNpe()) throw throwable
        }
    }

    private fun Throwable.isAndroidXCreatedStageCleanupNpe(): Boolean {
        return this is NullPointerException &&
            message.orEmpty().contains("Current state was null unexpectedly") &&
            message.orEmpty().contains("Last stage = CREATED") &&
            stackTrace.any { it.className == "androidx.test.core.app.ActivityScenario" }
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
            assertEquals(operation, fields["nativeIoOperation"])
            assertEquals("true", fields["candidateWithinDataRoot"])
            assertEquals("true", fields["nativeIoCandidateWithinDataRoot"])
            assertEquals("GUEST_PRIVATE_PATHS_ONLY", fields["nativeRedirectScope"])
            assertEquals("false", fields["procMapsSpoofEnabled"])
            assertEquals("false", fields["procStatusSpoofEnabled"])
            assertNotEquals("UNKNOWN", fields["namespaceVerdict"])
            assertNotEquals("UNKNOWN", fields["findLibraryVerdict"])
            assertNotEquals("UNKNOWN", fields["nativeLoadVerdict"])

            val candidatePath = File(checkNotNull(fields["candidateRedirectedPath"])).canonicalPath
            assertTrue("$component candidate outside dataRoot: $candidatePath", candidatePath.startsWith(dataRootPath))
            when (fields["nativeIoRedirectVerdict"]) {
                "PASS" -> {
                    assertEquals("REDIRECTED", fields["storageDiagnosticStatus"])
                    assertEquals("REDIRECTED", fields["nativeIoDiagnosticStatus"])
                    assertEquals("true", fields["withinDataRoot"])
                    assertEquals("true", fields["nativeIoRedirectEnabled"])
                    assertEquals("true", fields["nativeProbeCandidateExists"])
                    assertEquals("0", fields["nativeProbeResultCode"])
                    assertEquals("", fields["nativeIoRedirectVerdictReason"])

                    val redirectedPath = File(checkNotNull(fields["redirectedPath"])).canonicalPath
                    assertTrue("$component redirected outside dataRoot: $redirectedPath", redirectedPath.startsWith(dataRootPath))
                }

                "UNSUPPORTED" -> {
                    assertEquals("UNSUPPORTED", fields["storageDiagnosticStatus"])
                    assertEquals("UNSUPPORTED", fields["nativeIoDiagnosticStatus"])
                    assertEquals("false", fields["withinDataRoot"])
                    assertEquals("false", fields["nativeIoRedirectEnabled"])
                    assertEquals("", fields["redirectedPath"])
                    assertTrue(
                        "$component unsupported native IO must include a reason",
                        fields["nativeIoRedirectVerdictReason"].orEmpty().isNotBlank()
                    )
                }

                "FAIL" -> fail(
                    "$component native IO redirect probe failed: " +
                        fields["nativeIoRedirectVerdictReason"].orEmpty()
                )

                else -> fail("$component unknown native IO redirect verdict: ${fields["nativeIoRedirectVerdict"]}")
            }
        }
        return markers
    }

    private fun waitForPr10Evidence(instanceId: String) {
        val deadline = System.currentTimeMillis() + 20_000L // guest 冷启动阈值（2026-08-01 真机修正：8s→20s）
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
