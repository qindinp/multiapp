package com.multiapp.app

import android.content.pm.PackageInfo
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.app.container.ContainerActivity
import com.multiapp.app.container.ContainerAmsApiEvidenceRecorder
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.engine.EngineRuntimeInstallers
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorders
import com.multiapp.core.loader.VirtualInstrumentationInstaller
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.installer.ComponentInfo
import com.multiapp.core.model.installer.JsonInstallRecordStore
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
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostedContainerPr8AmsApiEvidenceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var virtualizationEngine: VirtualizationEngine

    private lateinit var activityScenario: ActivityScenario<MainActivity>

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager
    private val minimalPackageName = "com.test.minimal"
    private val pr8AmsApiEvidenceComponents = listOf(
        "ams-start-activity-overload",
        "ams-start-activities-overload",
        "ams-start-service",
        "ams-register-receiver",
        "ams-sticky-ordered-broadcast",
        "ams-start-foreground-service",
        "ams-bind-service-overload",
        "ams-unbind-service-overload"
    )

    @Before
    fun cleanPreviousPr8AmsApiState() {
        hiltRule.inject()
        // host Activity 前台化：MIUI BAL 视窗检查（2026-08-01）
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        EngineRuntimeInstallers.installAmsApiEvidenceRecorder(
            ContainerAmsApiEvidenceRecorder(targetContext)
        )
        val installStore = JsonInstallRecordStore(ContainerRuntimePaths.installStoreDir(targetContext))
        installStore.delete(minimalPackageName)
        // engine facade 清理：owner store 只在 :engine 进程构造（2026-08-01 修复）
        virtualizationEngine.listInstances()
            .filter { it.originPackageName == minimalPackageName }
            .forEach { instance ->
                deletePr8AmsApiEvidence(instance.instanceId)
                val result = virtualizationEngine.deleteInstance(instance.instanceId)
                assertTrue(
                    "engine cleanup failed for ${instance.instanceId}: ${result.status}:${result.message}",
                    result.status == EngineResultStatus.PASS || result.status == EngineResultStatus.PARTIAL
                )
            }
        deletePr8AmsApiEvidenceFilesByComponentName()
    }

    @After
    fun restoreGlobalRuntimeHooks() {
        if (::activityScenario.isInitialized) {
            activityScenario.close()
        }
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
        val createResult = virtualizationEngine.createInstance(
            CreateInstanceRequest(
                creationRequestId = "androidTest-pr8-ams-api-${System.currentTimeMillis()}",
                install = installRequest,
                displayName = "MinimalTest PR8 AMS API",
                compatibilityMode = CompatibilityMode.DEFAULT
            )
        )
        assertTrue(
            "engine createInstance failed: ${createResult.status}:${createResult.message}",
            createResult.status == EngineResultStatus.PASS
        )
        val instanceId = checkNotNull(createResult.instanceId) { "createInstance returned no instanceId" }
        val instance = virtualizationEngine.listInstances().first { it.instanceId == instanceId }

        // engine.launchInstance 建立 runtime 状态（package snapshot/进程绑定），
        // 否则 ContainerActivity 的 bindApplication 无法构建 binding fingerprint
        // （2026-08-01 真机定位：Unable to build hosted runtime binding fingerprint）
        val launchResult = virtualizationEngine.launchInstance(
            com.multiapp.core.model.engine.LaunchInstanceRequest(
                instanceId = instance.instanceId,
                reason = "androidTest:pr8-ams-api-evidence"
            )
        )
        assertTrue(
            "engine launchInstance failed: ${launchResult.status}:${launchResult.message}",
            launchResult.status == EngineResultStatus.PASS
        )
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

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activity-overload", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activity-overload", "api=startActivity:options")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activity-overload", "status=ACTIVITY_START_BLOCKED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activity-overload", "hostFallback=false")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activity-overload", "remapped=false")
        // reason 为演进后精确版本（engine 侧 explicit activity plan 失败），2026-08-01 真机对齐
        assertRuntimeEvidenceHasLineStartingWith(
            instance.instanceId,
            "ams-start-activity-overload",
            "reason=engine_activity_plan_fail:"
        )

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activities-overload", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activities-overload", "api=startActivities")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activities-overload", "status=ACTIVITY_BATCH_BLOCKED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-activities-overload", "hostFallback=false")
        // reason 为演进后精确版本（batch 分配缺失），2026-08-01 真机对齐
        assertRuntimeEvidenceHasLineStartingWith(
            instance.instanceId,
            "ams-start-activities-overload",
            "reason=engine_activity_batch_"
        )

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "api=startService")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "status=SERVICE_PROXY_STARTED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "proxyStarted=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "serviceResolved=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "foreground=false")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "capabilityVerdict=PARTIAL")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-service", "hostFallback=false")

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "api=startForegroundService")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "status=FOREGROUND_SERVICE_PROXY_STARTED")
        assertRuntimeEvidenceHasLineStartingWith(instance.instanceId, "ams-start-foreground-service", "returnValue=")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "proxyStarted=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "serviceResolved=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "foreground=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "reason=explicitForeground")
        assertRuntimeEvidenceHasLine(
            instance.instanceId,
            "ams-start-foreground-service",
            "guestServiceClassName=com.test.minimal.ProbeService"
        )
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "lifecycleImplemented=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "guestForegroundLifecycleImplemented=false")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "capabilityVerdict=PARTIAL")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-start-foreground-service", "hostFallback=false")

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "status=BIND_CONNECTED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "returnValue=true")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "capabilityVerdict=PASS")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-bind-service-overload", "hostFallback=false")
        assertRuntimeEvidenceHasLineStartingWith(instance.instanceId, "ams-bind-service-overload", "api=")
        assertRuntimeEvidenceHasLineStartingWith(instance.instanceId, "ams-bind-service-overload", "serviceResolved=")

        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-unbind-service-overload", "stage=AMS_API_OVERLOAD")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-unbind-service-overload", "status=UNBIND_DISPATCHED")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-unbind-service-overload", "hostFallback=false")
        assertRuntimeEvidenceHasLine(instance.instanceId, "ams-unbind-service-overload", "capabilityVerdict=PASS")
    }

    private fun waitForPr8AmsApiEvidence(instanceId: String) {
        // guest 冷启动 + AMS probe 执行可能超过 8s（Android 16 + MIUI 实测 ~10-15s），
        // 2026-08-01 真机修正：8s → 20s
        val deadline = System.currentTimeMillis() + 20_000L
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
