package com.multiapp.app

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.multiapp.app.container.ContainerRuntimePaths
import com.multiapp.core.model.engine.CreateInstanceRequest
import com.multiapp.core.model.engine.EnginePackageInstallRequest
import com.multiapp.core.model.engine.EngineProfile
import com.multiapp.core.model.engine.EngineResultStatus
import com.multiapp.core.model.engine.LaunchInstanceRequest
import com.multiapp.core.model.engine.VirtualizationEngine
import com.multiapp.core.model.instance.CompatibilityMode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/**
 * 真实应用兼容性冒烟测试（诊断语义，非断言式）。
 *
 * 对设备上实际安装的候选应用执行：import → createInstance → launchInstance → evidence 检查，
 * 输出 compatibility dossier。不 assert 导入/启动成功（加固/so 依赖等失败本身就是兼容性数据），
 * 只断言每应用有明确 outcome 且 dossier 文件写入成功。
 *
 * 2026-08-01 首建：P0-COMPAT-01 从"无 APK 资源"推进到"设备基线数据"。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealAppCompatibilitySmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var virtualizationEngine: VirtualizationEngine

    private lateinit var activityScenario: ActivityScenario<MainActivity>

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager

    private data class Candidate(val packageName: String, val label: String, val trait: String)

    private val candidates = listOf(
        Candidate("com.tencent.mm", "微信", "加固/大型"),
        Candidate("com.qidian.QDReader", "起点读书", "阅文系/与com.qq.reader同门"),
        Candidate("com.sina.weibo", "微博", "社交"),
        Candidate("cn.wps.moffice_eng", "WPS Office", "办公"),
        Candidate("com.kugou.android.lite", "酷狗音乐", "轻量"),
        Candidate("com.autonavi.minimap", "高德地图", "so密集"),
        Candidate("com.test.minimal", "Minimal fixture", "对照(应通过)")
    )

    private val dossierDir = File(targetContext.filesDir, "compat_dossier")
    private val dossierFile = File(dossierDir, "2026-08-01.txt")

    @Before
    fun cleanPreviousRealAppState() {
        hiltRule.inject()
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        // 清理上次残留实例（按候选包名），避免 createInstance 重复
        val candidatePackages = candidates.map { it.packageName }.toSet()
        virtualizationEngine.listInstances()
            .filter { it.originPackageName in candidatePackages }
            .forEach { instance ->
                runCatching { virtualizationEngine.deleteInstance(instance.instanceId) }
            }
        dossierDir.mkdirs()
    }

    @Test
    fun smokeImportAndLaunchRealApps() {
        val lines = mutableListOf<String>()
        lines += "# RealApp Compatibility Smoke Dossier 2026-08-01"
        lines += "# device=popsicle(API36/HyperOS) host=com.multiapp.app hosted flavor"
        lines += "# per-app: install→create→launch→evidence; stage outcomes are diagnostic, not assertions"
        lines += "# column: pkg\tlabel\ttrait\tinstall\tcreate\tlaunch\tlaunchDetail\tevidenceCount"

        candidates.forEach { cand ->
            val row = runCandidate(cand)
            lines += row
            println("[REALAPP] $row")
        }

        dossierFile.writeText(lines.joinToString("\n") + "\n")
        assertTrue(
            "dossier must be written: ${dossierFile.absolutePath}",
            dossierFile.isFile && dossierFile.length() > 0
        )
    }

    private fun runCandidate(cand: Candidate): String {
        val pkg = cand.packageName
        val info = try {
            packageManager.getPackageInfo(pkg, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            return "$pkg\t${cand.label}\t${cand.trait}\tNOT_INSTALLED\t-\t-\t-\t-"
        }
        val appInfo = info.applicationInfo
        val apkPath = appInfo?.sourceDir ?: return "$pkg\t${cand.label}\t${cand.trait}\tNO_SOURCE_DIR\t-\t-\t-\t-"

        // ---- stage 1: engine install (import) ----
        val installRequest = EnginePackageInstallRequest(
            originPackageName = pkg,
            originApkPath = apkPath,
            versionCode = info.longVersionCodeCompat(),
            versionName = info.versionName ?: "1.0",
            targetSdk = appInfo.targetSdkVersion,
            minSdk = appInfo.minSdkVersionCompat(),
            applicationClassName = appInfo.className,
            packageLabel = appInfo.loadLabel(packageManager).toString()
        )

        val createResult = runCatching {
            virtualizationEngine.createInstance(
                CreateInstanceRequest(
                    creationRequestId = "compat-smoke-${pkg}-${System.currentTimeMillis()}",
                    install = installRequest,
                    displayName = "${cand.label} CompatSmoke",
                    compatibilityMode = CompatibilityMode.DEFAULT
                )
            )
        }.getOrElse { e ->
            return "$pkg\t${cand.label}\t${cand.trait}\t-\tEXCEPTION\t-\t${e.javaClass.simpleName}:${e.message?.take(120)}\t-"
        }
        if (createResult.status != EngineResultStatus.PASS) {
            return "$pkg\t${cand.label}\t${cand.trait}\t-\t${createResult.status}\t-\t${createResult.message?.take(160) ?: "-"}\t-"
        }
        val instanceId = createResult.instanceId
            ?: return "$pkg\t${cand.label}\t${cand.trait}\t-\tPASS\t-\tNO_INSTANCE_ID\t-"

        // ---- stage 2: launch (bootstrap guest) ----
        val launchStart = System.currentTimeMillis()
        val launch = runCatching {
            virtualizationEngine.launchInstance(
                LaunchInstanceRequest(
                    instanceId = instanceId,
                    reason = "compat-smoke:${pkg}"
                )
            )
        }.getOrElse { e ->
            val detail = "EXCEPTION:${e.javaClass.simpleName}:${e.message?.take(120)}"
            virtualizationEngine.deleteInstance(instanceId)
            return "$pkg\t${cand.label}\t${cand.trait}\tPASS\tPASS\tEXCEPTION\t$detail\t-"
        }
        val launchStatus = launch.status
        val launchDetail = launch.message?.take(160) ?: "-"

        // ---- stage 3: evidence window (wait for guest bootstrap artifacts) ----
        val deadline = System.currentTimeMillis() + 20_000L
        var evidenceCount = 0
        while (System.currentTimeMillis() < deadline) {
            evidenceCount = evidenceFor(instanceId).size
            if (launchStatus == EngineResultStatus.PASS && evidenceCount > 0) break
            if (launchStatus != EngineResultStatus.PASS) break
            Thread.sleep(500L)
        }

        val outcome = when {
            launchStatus == EngineResultStatus.PASS && evidenceCount > 0 -> "BOOTSTRAPPED"
            launchStatus == EngineResultStatus.PASS -> "LAUNCH_PASS_NO_EVIDENCE"
            else -> launchStatus.toString()
        }

        virtualizationEngine.deleteInstance(instanceId)
        return "$pkg\t${cand.label}\t${cand.trait}\tPASS\tPASS\t$outcome\t$launchDetail\t$evidenceCount"
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
    }

    private fun android.content.pm.ApplicationInfo.minSdkVersionCompat(): Int {
        return if (Build.VERSION.SDK_INT >= 24) minSdkVersion else 1
    }

    private fun evidenceFor(instanceId: String): List<File> =
        ContainerRuntimePaths.hostedLaunchEvidenceDir(targetContext)
            .listFiles()
            ?.filter { it.name.startsWith(instanceId) }
            ?.toList()
            .orEmpty()
}
