# PR-8 AMS API Device Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 PR-8 Virtual AMS 补齐 `registerReceiver`、`sendStickyOrderedBroadcast`、`bindService` overload 的 hosted guest production path 设备端专项 evidence。

**Architecture:** 在 `core/loader` 增加轻量 AMS API evidence recorder contract，由 `VirtualContextWrapper`/API-gated wrapper 在实际拦截点发出 evidence record；`app` 层安装 file-backed recorder 写入现有 `files/hosted_launch_evidence` 目录；`test-fixtures/minimal-app` 真实调用目标 API；`app` 的 androidTest 创建 hosted instance 并断言 evidence 文件。

**Tech Stack:** Kotlin/JVM + Android instrumentation test、Java minimal fixture、Android SDK 36、Gradle、ADB、现有 `ContainerRuntimeEvidenceWriter`/`ContainerRuntimePaths`。

## Global Constraints

- 全程中文沟通；代码命名遵循现有英文 Kotlin/Java 风格。
- 严格 TDD：先写失败测试，确认 RED，再写最小实现。
- 编译/实机验证前必须先进行 code review；不要直接 build。
- 不提交、不 push，除非用户明确要求。
- 不能宣称 `Virtual AMS complete`；本 slice 只声明 PR-8 三个 AMS API 边界的 device evidence。
- `Context.BindServiceFlags` 相关签名只允许放在 API34+ wrapper，不能放回 base `VirtualContextWrapper`，避免 minSdk 28 class-loading 风险。
- 不实现真实 bound-service lifecycle；`bindService` overload 仍应 fail-closed / return false / no host fallback。
- 不清空设备 logcat，除非用户明确授权。
- 目标设备默认使用当前已连接 SDK36 设备；本轮实机证据来自 `192.168.2.42:44113`，ADB 路径为 `C:/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe`。

---

## File Structure

### 新增文件

- `core/loader/src/main/java/com/multiapp/core/loader/VirtualAmsApiEvidenceRecorder.kt`
  - 定义 PR-8 AMS API evidence record、recorder、global installer、no-op 默认实现。
  - 不依赖 app 层，保持 core 可复用。

- `app/src/main/java/com/multiapp/app/container/ContainerAmsApiEvidenceRecorder.kt`
  - 将 core 层 `VirtualAmsApiEvidenceRecord` 写入 `files/hosted_launch_evidence/<instanceId>.<component>.properties`。
  - component 映射：
    - `registerReceiver` -> `ams-register-receiver`
    - `sendStickyOrderedBroadcast` -> `ams-sticky-ordered-broadcast`
    - bind overload -> `ams-bind-service-overload`

- `app/src/androidTest/java/com/multiapp/app/HostedContainerPr8AmsApiEvidenceTest.kt`
  - 专项 instrumentation test：安装前提、导入 minimal APK、创建 hosted instance、启动 ContainerActivity、等待并断言三类 evidence。

### 修改文件

- `core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt`
  - 在 `registerReceiver(...)` 成功注册后记录 `DYNAMIC_RECEIVER_REGISTERED`。
  - 在 `sendStickyOrderedBroadcast(...)` / `sendStickyOrderedBroadcastAsUser(...)` 拦截后记录 `STICKY_ORDERED_INTERCEPTED`。
  - 在 int/executor/asUser/isolated bind overload 记录 `BIND_BLOCKED`。

- `core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapperApi34.kt`
  - 在 `Context.BindServiceFlags` overload 中传入可区分的 API 名称，例如 `bindService:flags` / `bindService:flags-executor`。

- `app/src/main/java/com/multiapp/app/MultiAppApplication.kt`
  - 安装 `ContainerAmsApiEvidenceRecorder`。

- `test-fixtures/minimal-app/src/main/java/com/test/minimal/MainActivity.java`
  - 新增 `runPr8AmsApiProbe()`，真实调用动态 receiver 注册、sticky ordered broadcast、bindService overload。
  - `bindService` 只记录返回值，不期待绑定成功。

- `tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1`
  - 可选增强：汇总三类新 evidence；不是首要必要条件。若 scope 要保持最小，可不改。

---

### Task 1: 写 RED androidTest，证明三类 evidence 当前缺失

**Files:**
- Create: `app/src/androidTest/java/com/multiapp/app/HostedContainerPr8AmsApiEvidenceTest.kt`
- Reads existing pattern from: `app/src/androidTest/java/com/multiapp/app/HostedContainerMinimalBaselineTest.kt`

**Interfaces:**
- Consumes:
  - `ContainerRuntimePaths.hostedRuntimeEvidenceFile(context, instanceId, component): File`
  - `ContainerActivity.createIntent(context, instanceId, reason): Intent`
  - `ProductionVirtualInstallService.importFromMetadata(...)`
  - `DefaultInstanceManager.createInstance(...)`
- Produces:
  - A failing test class expecting these files:
    - `<instanceId>.ams-register-receiver.properties`
    - `<instanceId>.ams-sticky-ordered-broadcast.properties`
    - `<instanceId>.ams-bind-service-overload.properties`

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/multiapp/app/HostedContainerPr8AmsApiEvidenceTest.kt` with this content:

```kotlin
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
class HostedContainerPr8AmsApiEvidenceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val packageManager = instrumentation.context.packageManager
    private val minimalPackageName = "com.test.minimal"

    @Before
    fun cleanPreviousPr8AmsApiState() {
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
    fun hostedContainerRecordsPr8AmsApiEvidence() {
        val packageInfo = findInstalledMinimalPackage()
        assumeTrue("com.test.minimal must be installed before this test", packageInfo != null)
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
        val requiredComponents = listOf(
            "ams-register-receiver",
            "ams-sticky-ordered-broadcast",
            "ams-bind-service-overload"
        )
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
        assertTrue("missing PR-8 AMS API evidence for $instanceId: $missing", missing.isEmpty())
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
```

- [ ] **Step 2: Build/install minimal fixture and run RED**

Run from repo root:

```bash
./gradlew.bat :test-fixtures:minimal-app:assembleDebug --console=plain --no-build-cache
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" connect 192.168.2.42:36339
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 install -r "test-fixtures/minimal-app/build/outputs/apk/debug/minimal-app-debug.apk"
ANDROID_SERIAL=192.168.2.42:36339 ./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.multiapp.app.HostedContainerPr8AmsApiEvidenceTest" --console=plain --no-build-cache
```

Expected RED:

```text
FAILURES!!!
missing PR-8 AMS API evidence for <instanceId>: [ams-register-receiver, ams-sticky-ordered-broadcast, ams-bind-service-overload]
```

If failure is `com.test.minimal must be installed before this test`, install the fixture and rerun. If failure is compile error caused by the new test, fix the test only until it compiles and fails because evidence is missing.

---

### Task 2: 新增 core AMS API evidence recorder contract

**Files:**
- Create: `core/loader/src/main/java/com/multiapp/core/loader/VirtualAmsApiEvidenceRecorder.kt`
- Test: `core/loader/src/test/java/com/multiapp/core/loader/VirtualAmsApiEvidenceRecorderTest.kt`

**Interfaces:**
- Produces:
  - `enum class VirtualAmsApiEvidenceComponent`
  - `data class VirtualAmsApiEvidenceRecord`
  - `fun interface VirtualAmsApiEvidenceRecorder`
  - `object VirtualAmsApiEvidenceRecorders`
  - `object GlobalVirtualAmsApiEvidenceRecorder`
- Consumes later:
  - `VirtualContextWrapper` will call `GlobalVirtualAmsApiEvidenceRecorder.record(record)`.
  - `ContainerAmsApiEvidenceRecorder` will map `record.component.componentName` to evidence file component.

- [ ] **Step 1: Write failing JVM test for recorder install/reset**

Create `core/loader/src/test/java/com/multiapp/core/loader/VirtualAmsApiEvidenceRecorderTest.kt`:

```kotlin
package com.multiapp.core.loader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualAmsApiEvidenceRecorderTest {

    @After
    fun tearDown() {
        VirtualAmsApiEvidenceRecorders.reset()
    }

    @Test
    fun `global recorder delegates installed AMS API evidence records`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }

        GlobalVirtualAmsApiEvidenceRecorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                virtualPackageName = "com.multiapp.instance.inst001",
                api = "registerReceiver",
                status = "DYNAMIC_RECEIVER_REGISTERED",
                hostFallback = false,
                fields = linkedMapOf("registered" to true)
            )
        )

        assertEquals(1, records.size)
        assertEquals(VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER, records.single().component)
        assertEquals("inst-001", records.single().instanceId)
        assertEquals("registered", records.single().fields.keys.single())
    }

    @Test
    fun `reset restores no-op AMS API evidence recorder`() {
        val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
        VirtualAmsApiEvidenceRecorders.install { record -> records += record }
        VirtualAmsApiEvidenceRecorders.reset()

        GlobalVirtualAmsApiEvidenceRecorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                virtualPackageName = "com.multiapp.instance.inst001",
                api = "bindService:int",
                status = "BIND_BLOCKED",
                hostFallback = false,
                fields = linkedMapOf("returnValue" to false)
            )
        )

        assertEquals(emptyList<VirtualAmsApiEvidenceRecord>(), records)
    }
}
```

- [ ] **Step 2: Run RED for recorder test**

Run:

```bash
./gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualAmsApiEvidenceRecorderTest" --console=plain --no-build-cache
```

Expected RED:

```text
Unresolved reference: VirtualAmsApiEvidenceRecorders
Unresolved reference: VirtualAmsApiEvidenceRecord
```

- [ ] **Step 3: Implement minimal recorder contract**

Create `core/loader/src/main/java/com/multiapp/core/loader/VirtualAmsApiEvidenceRecorder.kt`:

```kotlin
package com.multiapp.core.loader

enum class VirtualAmsApiEvidenceComponent(val componentName: String) {
    REGISTER_RECEIVER("ams-register-receiver"),
    STICKY_ORDERED_BROADCAST("ams-sticky-ordered-broadcast"),
    BIND_SERVICE_OVERLOAD("ams-bind-service-overload")
}

data class VirtualAmsApiEvidenceRecord(
    val component: VirtualAmsApiEvidenceComponent,
    val instanceId: String,
    val originPackageName: String,
    val virtualPackageName: String,
    val api: String,
    val status: String,
    val hostFallback: Boolean,
    val fields: Map<String, Any?> = emptyMap()
)

fun interface VirtualAmsApiEvidenceRecorder {
    fun record(record: VirtualAmsApiEvidenceRecord)
}

object VirtualAmsApiEvidenceRecorders {
    private val noOp = VirtualAmsApiEvidenceRecorder { }

    @Volatile
    private var delegate: VirtualAmsApiEvidenceRecorder = noOp

    fun install(recorder: VirtualAmsApiEvidenceRecorder) {
        delegate = recorder
    }

    fun reset() {
        delegate = noOp
    }

    internal fun current(): VirtualAmsApiEvidenceRecorder = delegate
}

object GlobalVirtualAmsApiEvidenceRecorder : VirtualAmsApiEvidenceRecorder {
    override fun record(record: VirtualAmsApiEvidenceRecord) {
        VirtualAmsApiEvidenceRecorders.current().record(record)
    }
}
```

- [ ] **Step 4: Verify GREEN for recorder test**

Run:

```bash
./gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualAmsApiEvidenceRecorderTest" --console=plain --no-build-cache
```

Expected:

```text
BUILD SUCCESSFUL
```

---

### Task 3: app 层写入 AMS API evidence 文件

**Files:**
- Create: `app/src/main/java/com/multiapp/app/container/ContainerAmsApiEvidenceRecorder.kt`
- Modify: `app/src/main/java/com/multiapp/app/MultiAppApplication.kt`
- Test: `app/src/test/java/com/multiapp/app/container/ContainerAmsApiEvidenceRecorderTest.kt`

**Interfaces:**
- Consumes:
  - `VirtualAmsApiEvidenceRecord.component.componentName`
  - `ContainerRuntimeEvidenceWriter.write(context, instanceId, component, fields)`
- Produces:
  - `ContainerAmsApiEvidenceRecorder(context: Context) : VirtualAmsApiEvidenceRecorder`
  - Evidence files with required common fields and record-specific fields.

- [ ] **Step 1: Write failing JVM test for file-backed recorder**

Create `app/src/test/java/com/multiapp/app/container/ContainerAmsApiEvidenceRecorderTest.kt`:

```kotlin
package com.multiapp.app.container

import android.content.Context
import com.multiapp.core.loader.VirtualAmsApiEvidenceComponent
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecord
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContainerAmsApiEvidenceRecorderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `writes AMS API evidence file with shared and specific fields`() {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns tempDir
        val recorder = ContainerAmsApiEvidenceRecorder(context)

        recorder.record(
            VirtualAmsApiEvidenceRecord(
                component = VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
                instanceId = "inst-001",
                originPackageName = "com.test.minimal",
                virtualPackageName = "com.multiapp.instance.inst001",
                api = "bindService:int",
                status = "BIND_BLOCKED",
                hostFallback = false,
                fields = linkedMapOf(
                    "returnValue" to false,
                    "serviceResolved" to true,
                    "reason" to "explicit"
                )
            )
        )

        val file = File(tempDir, "hosted_launch_evidence/inst-001.ams-bind-service-overload.properties")
        assertTrue(file.isFile)
        val lines = file.readLines().map { it.trim() }
        assertEquals(
            listOf(
                "status=BIND_BLOCKED",
                "stage=AMS_API_OVERLOAD",
                "instanceId=inst-001",
                "originPackageName=com.test.minimal",
                "virtualPackageName=com.multiapp.instance.inst001",
                "api=bindService:int",
                "hostFallback=false",
                "returnValue=false",
                "serviceResolved=true",
                "reason=explicit"
            ),
            lines
        )
    }
}
```

- [ ] **Step 2: Run RED for app recorder test**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerAmsApiEvidenceRecorderTest" --console=plain --no-build-cache
```

Expected RED:

```text
Unresolved reference: ContainerAmsApiEvidenceRecorder
```

- [ ] **Step 3: Implement file-backed recorder**

Create `app/src/main/java/com/multiapp/app/container/ContainerAmsApiEvidenceRecorder.kt`:

```kotlin
package com.multiapp.app.container

import android.content.Context
import android.util.Log
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecord
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorder

/** File-backed recorder for PR-8 AMS API interception evidence inside hosted containers. */
class ContainerAmsApiEvidenceRecorder(
    context: Context
) : VirtualAmsApiEvidenceRecorder {
    private val appContext = context.applicationContext

    override fun record(record: VirtualAmsApiEvidenceRecord) {
        runCatching {
            ContainerRuntimeEvidenceWriter.write(
                context = appContext,
                instanceId = record.instanceId,
                component = record.component.componentName,
                fields = linkedMapOf<String, Any?>(
                    "status" to record.status,
                    "stage" to "AMS_API_OVERLOAD",
                    "instanceId" to record.instanceId,
                    "originPackageName" to record.originPackageName,
                    "virtualPackageName" to record.virtualPackageName,
                    "api" to record.api,
                    "hostFallback" to record.hostFallback
                ).apply {
                    putAll(record.fields)
                }
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write AMS API evidence for instanceId=${record.instanceId}", error)
        }
    }

    companion object {
        private const val TAG = "AmsApiEvidence"
    }
}
```

- [ ] **Step 4: Install recorder in application**

Modify `app/src/main/java/com/multiapp/app/MultiAppApplication.kt` imports and `onCreate()`.

Add import:

```kotlin
import com.multiapp.app.container.ContainerAmsApiEvidenceRecorder
import com.multiapp.core.loader.VirtualAmsApiEvidenceRecorders
```

After the existing broadcast recorder install line:

```kotlin
VirtualBroadcastRecorders.install(ContainerBroadcastEvidenceRecorder(this))
VirtualAmsApiEvidenceRecorders.install(ContainerAmsApiEvidenceRecorder(this))
```

- [ ] **Step 5: Verify GREEN for app recorder test**

Run:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerAmsApiEvidenceRecorderTest" --console=plain --no-build-cache
```

Expected:

```text
BUILD SUCCESSFUL
```

---

### Task 4: VirtualContextWrapper 发出 registerReceiver / sticky ordered / bindService evidence

**Files:**
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapperApi34.kt`
- Test: `core/loader/src/test/java/com/multiapp/core/loader/VirtualContextWrapperTest.kt`

**Interfaces:**
- Consumes:
  - `GlobalVirtualAmsApiEvidenceRecorder.record(record)`
  - `VirtualAmsApiEvidenceRecord`
  - `VirtualAmsApiEvidenceComponent`
- Produces:
  - `registerReceiver` success records `DYNAMIC_RECEIVER_REGISTERED`。
  - sticky ordered overload records `STICKY_ORDERED_INTERCEPTED`。
  - bind overloads record `BIND_BLOCKED` with specific API name and `returnValue=false`。

- [ ] **Step 1: Add failing unit tests for recorder calls**

Append tests to `core/loader/src/test/java/com/multiapp/core/loader/VirtualContextWrapperTest.kt`:

```kotlin
@Test
fun `registerReceiver records AMS API evidence when dynamic receiver is accepted`() {
    val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
    VirtualAmsApiEvidenceRecorders.install { record -> records += record }
    try {
        val base = baseContext()
        val receiver = mockk<BroadcastReceiver>(relaxed = true)
        val wrapper = wrapper(base = base)

        wrapper.registerReceiver(receiver, IntentFilter("com.test.minimal.ACTION_DYNAMIC_PR8_PROBE"))

        val record = records.single { it.component == VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER }
        assertEquals("registerReceiver", record.api)
        assertEquals("DYNAMIC_RECEIVER_REGISTERED", record.status)
        assertEquals(false, record.hostFallback)
        assertEquals(true, record.fields["registered"])
        verify(exactly = 0) { base.registerReceiver(any(), any()) }
    } finally {
        VirtualAmsApiEvidenceRecorders.reset()
    }
}

@Test
fun `sendStickyOrderedBroadcast records AMS API evidence without host fallback`() {
    val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
    VirtualAmsApiEvidenceRecorders.install { record -> records += record }
    try {
        val base = baseContext()
        val wrapper = wrapper(
            base = base,
            snapshot = snapshot().copy(
                receivers = listOf(ResolvedComponent(name = "com.test.minimal.ProbeReceiver", exported = false))
            )
        )
        val intent = explicitReceiverIntent("com.test.minimal.ProbeReceiver")

        wrapper.sendStickyOrderedBroadcast(intent, null, null, 200, "pr8", Bundle())

        val record = records.single { it.component == VirtualAmsApiEvidenceComponent.STICKY_ORDERED_BROADCAST }
        assertEquals("sendStickyOrderedBroadcast", record.api)
        assertEquals("STICKY_ORDERED_INTERCEPTED", record.status)
        assertEquals(false, record.hostFallback)
        assertEquals("Delivered", record.fields["dispatchStatus"])
        verify(exactly = 0) { base.sendStickyOrderedBroadcast(any(), any(), any(), any(), any(), any()) }
    } finally {
        VirtualAmsApiEvidenceRecorders.reset()
    }
}

@Test
fun `bindService records AMS API evidence while returning false`() {
    val records = mutableListOf<VirtualAmsApiEvidenceRecord>()
    VirtualAmsApiEvidenceRecorders.install { record -> records += record }
    try {
        val base = baseContext()
        val wrapper = wrapper(
            base = base,
            snapshot = snapshot().copy(
                services = listOf(ResolvedComponent(name = "com.test.minimal.ProbeService", exported = false))
            )
        )
        val service = explicitServiceIntent("com.test.minimal.ProbeService")
        val connection = mockk<ServiceConnection>(relaxed = true)

        val result = wrapper.bindService(service, connection, Context.BIND_AUTO_CREATE)

        assertFalse(result)
        val record = records.single { it.component == VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD }
        assertEquals("bindService:int", record.api)
        assertEquals("BIND_BLOCKED", record.status)
        assertEquals(false, record.hostFallback)
        assertEquals(false, record.fields["returnValue"])
        assertEquals(true, record.fields["serviceResolved"])
        assertEquals("explicit", record.fields["reason"])
        verify(exactly = 0) { base.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
    } finally {
        VirtualAmsApiEvidenceRecorders.reset()
    }
}
```

If helper `explicitReceiverIntent` does not exist, add this test helper near existing helpers:

```kotlin
private fun explicitReceiverIntent(className: String): Intent = Intent("com.test.minimal.ACTION_PR8_STICKY_ORDERED")
    .setComponent(ComponentName("com.test.minimal", className))
```

- [ ] **Step 2: Run RED for wrapper tests**

Run:

```bash
./gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --console=plain --no-build-cache
```

Expected RED:

```text
Expected one matching VirtualAmsApiEvidenceRecord but collection was empty
```

- [ ] **Step 3: Implement evidence helpers in `VirtualContextWrapper`**

In `VirtualContextWrapper.kt`, add private/protected helpers inside the class:

```kotlin
private fun recordRegisterReceiverEvidence(registered: Boolean, reason: String) {
    GlobalVirtualAmsApiEvidenceRecorder.record(
        VirtualAmsApiEvidenceRecord(
            component = VirtualAmsApiEvidenceComponent.REGISTER_RECEIVER,
            instanceId = config.instanceId,
            originPackageName = config.originPackageName,
            virtualPackageName = config.virtualPackageName,
            api = "registerReceiver",
            status = if (registered) "DYNAMIC_RECEIVER_REGISTERED" else "DYNAMIC_RECEIVER_REJECTED",
            hostFallback = false,
            fields = linkedMapOf(
                "registered" to registered,
                "reason" to reason
            )
        )
    )
}

private fun recordStickyOrderedBroadcastEvidence(api: String, result: VirtualBroadcastResult) {
    GlobalVirtualAmsApiEvidenceRecorder.record(
        VirtualAmsApiEvidenceRecord(
            component = VirtualAmsApiEvidenceComponent.STICKY_ORDERED_BROADCAST,
            instanceId = config.instanceId,
            originPackageName = config.originPackageName,
            virtualPackageName = config.virtualPackageName,
            api = api,
            status = "STICKY_ORDERED_INTERCEPTED",
            hostFallback = false,
            fields = linkedMapOf(
                "dispatchStatus" to result.record.result.name,
                "receiverClassName" to result.record.receiverClassName.orEmpty(),
                "action" to result.record.action.orEmpty()
            )
        )
    )
}

protected fun recordBindServiceEvidence(api: String, result: StartServiceMappingResult) {
    val serviceResolved = result is StartServiceMappingResult.Remapped
    val reason = when (result) {
        is StartServiceMappingResult.Remapped -> result.request.reason
        is StartServiceMappingResult.Blocked -> result.reason
    }
    GlobalVirtualAmsApiEvidenceRecorder.record(
        VirtualAmsApiEvidenceRecord(
            component = VirtualAmsApiEvidenceComponent.BIND_SERVICE_OVERLOAD,
            instanceId = config.instanceId,
            originPackageName = config.originPackageName,
            virtualPackageName = config.virtualPackageName,
            api = api,
            status = "BIND_BLOCKED",
            hostFallback = false,
            fields = linkedMapOf(
                "returnValue" to false,
                "serviceResolved" to serviceResolved,
                "reason" to reason
            )
        )
    )
}
```

Change `dispatchBindServiceIntent` signature and body:

```kotlin
protected fun dispatchBindServiceIntent(service: Intent, api: String): Boolean {
    val result = componentDispatcher().resolveStartServiceIntent(service, foreground = false)
    lastStartServiceMappingResult = result
    recordBindServiceEvidence(api, result)
    return false
}
```

Update base bind overloads:

```kotlin
override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
    return dispatchBindServiceIntent(service, api = "bindService:int")
}

override fun bindService(
    service: Intent,
    flags: Int,
    executor: Executor,
    conn: ServiceConnection
): Boolean {
    return dispatchBindServiceIntent(service, api = "bindService:executor")
}

override fun bindServiceAsUser(
    service: Intent,
    conn: ServiceConnection,
    flags: Int,
    user: UserHandle
): Boolean {
    return dispatchBindServiceIntent(service, api = "bindServiceAsUser:int")
}

override fun bindIsolatedService(
    service: Intent,
    flags: Int,
    instanceName: String,
    executor: Executor,
    conn: ServiceConnection
): Boolean {
    return dispatchBindServiceIntent(service, api = "bindIsolatedService:int")
}
```

Change `dispatchBroadcastIntent` to return the result:

```kotlin
protected fun dispatchBroadcastIntent(intent: Intent): VirtualBroadcastResult {
    val result = componentDispatcher().dispatchBroadcast(
        intent = intent,
        virtualContext = this,
        receiverClassLoader = guestClassLoader
    )
    lastBroadcastDispatchResult = result
    return result
}
```

Update sticky ordered overloads:

```kotlin
override fun sendStickyOrderedBroadcast(
    intent: Intent,
    resultReceiver: BroadcastReceiver?,
    scheduler: Handler?,
    initialCode: Int,
    initialData: String?,
    initialExtras: Bundle?
) {
    val result = dispatchBroadcastIntent(intent)
    recordStickyOrderedBroadcastEvidence("sendStickyOrderedBroadcast", result)
}

override fun sendStickyOrderedBroadcastAsUser(
    intent: Intent,
    user: UserHandle?,
    resultReceiver: BroadcastReceiver?,
    scheduler: Handler?,
    initialCode: Int,
    initialData: String?,
    initialExtras: Bundle?
) {
    val result = dispatchBroadcastIntent(intent)
    recordStickyOrderedBroadcastEvidence("sendStickyOrderedBroadcastAsUser", result)
}
```

Update successful `registerReceiver(receiver, filter)` path after `dynamicReceiverRegistry.register(...)`:

```kotlin
lastBroadcastReceiverRegistrationResult = BroadcastReceiverRegistrationResult.Registered(
    receiver = receiver,
    filter = filter,
    record = record
)
recordRegisterReceiverEvidence(registered = true, reason = "")
return null
```

For rejected/fallback paths, keep `hostFallback=false` and record rejection only if needed. The first slice only requires successful dynamic registration evidence.

- [ ] **Step 4: Update API34 bind overloads**

In `VirtualContextWrapperApi34.kt`, update calls:

```kotlin
override fun bindService(
    service: Intent,
    flags: Context.BindServiceFlags,
    executor: Executor,
    conn: ServiceConnection
): Boolean {
    return dispatchBindServiceIntent(service, api = "bindService:flags-executor")
}

override fun bindService(service: Intent, conn: ServiceConnection, flags: Context.BindServiceFlags): Boolean {
    return dispatchBindServiceIntent(service, api = "bindService:flags")
}

override fun bindServiceAsUser(
    service: Intent,
    conn: ServiceConnection,
    flags: Context.BindServiceFlags,
    user: UserHandle
): Boolean {
    return dispatchBindServiceIntent(service, api = "bindServiceAsUser:flags")
}

override fun bindIsolatedService(
    service: Intent,
    flags: Context.BindServiceFlags,
    instanceName: String,
    executor: Executor,
    conn: ServiceConnection
): Boolean {
    return dispatchBindServiceIntent(service, api = "bindIsolatedService:flags")
}
```

- [ ] **Step 5: Verify GREEN for wrapper tests**

Run:

```bash
./gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --console=plain --no-build-cache
```

Expected:

```text
BUILD SUCCESSFUL
```

---

### Task 5: minimal fixture 真实触发 PR-8 AMS API probe

**Files:**
- Modify: `test-fixtures/minimal-app/src/main/java/com/test/minimal/MainActivity.java`

**Interfaces:**
- Consumes hosted virtual context APIs:
  - `registerReceiver(BroadcastReceiver, IntentFilter, int)` on API 33+ or `registerReceiver(BroadcastReceiver, IntentFilter)` below 33.
  - `sendStickyOrderedBroadcast(Intent, BroadcastReceiver, Handler, int, String, Bundle)`.
  - `bindService(Intent, ServiceConnection, int)`.
  - API 29+ executor overload and API 34+ flags overload only when available.
- Produces:
  - Real guest production calls that cause Task 4 recorder paths to write evidence.

- [ ] **Step 1: Add imports**

In `MainActivity.java`, add imports:

```java
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;
```

- [ ] **Step 2: Add constants**

Near existing action constants, add:

```java
private static final String ACTION_DYNAMIC_PR8_PROBE = "com.test.minimal.ACTION_DYNAMIC_PR8_PROBE";
private static final String ACTION_STICKY_ORDERED_PR8_PROBE = "com.test.minimal.ACTION_STICKY_ORDERED_PR8_PROBE";
```

- [ ] **Step 3: Call PR-8 probe from `onCreate`**

After existing component probe block:

```java
String pr8AmsApiProbe = runPr8AmsApiProbe();
addText(layout, "\nPR-8 AMS API probe:\n" + pr8AmsApiProbe, 14, 0xFF263238);
```

- [ ] **Step 4: Add `runPr8AmsApiProbe()`**

Add this method before `redactUri(...)`:

```java
private String runPr8AmsApiProbe() {
    StringBuilder out = new StringBuilder();

    try {
        BroadcastReceiver dynamicReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "=== PR8 dynamic receiver === action=" + (intent != null ? intent.getAction() : ""));
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_DYNAMIC_PR8_PROBE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dynamicReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dynamicReceiver, filter);
        }
        sendBroadcast(new Intent(ACTION_DYNAMIC_PR8_PROBE));
        out.append("registerReceiver.status: requested\n");
    } catch (Exception e) {
        out.append("registerReceiver failed: ")
            .append(e.getClass().getSimpleName())
            .append(": ")
            .append(e.getMessage())
            .append("\n");
    }

    try {
        Intent stickyOrdered = new Intent(ACTION_STICKY_ORDERED_PR8_PROBE)
            .setComponent(new ComponentName(getPackageName(), ProbeReceiver.class.getName()));
        BroadcastReceiver resultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "=== PR8 sticky ordered result === action=" + (intent != null ? intent.getAction() : ""));
            }
        };
        Bundle extras = new Bundle();
        extras.putString("probe", "pr8-sticky-ordered");
        sendStickyOrderedBroadcast(stickyOrdered, resultReceiver, new Handler(Looper.getMainLooper()), 200, "pr8", extras);
        out.append("stickyOrdered.status: requested\n");
    } catch (Exception e) {
        out.append("stickyOrdered failed: ")
            .append(e.getClass().getSimpleName())
            .append(": ")
            .append(e.getMessage())
            .append("\n");
    }

    try {
        Intent service = new Intent().setComponent(new ComponentName(getPackageName(), ProbeService.class.getName()));
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, android.os.IBinder service) {
                Log.d(TAG, "=== PR8 bind connected unexpectedly === " + name);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "=== PR8 bind disconnected === " + name);
            }
        };
        boolean bound = bindService(service, connection, Context.BIND_AUTO_CREATE);
        out.append("bindService.intResult: ").append(bound).append("\n");
    } catch (Exception e) {
        out.append("bindService failed: ")
            .append(e.getClass().getSimpleName())
            .append(": ")
            .append(e.getMessage())
            .append("\n");
    }

    if (Build.VERSION.SDK_INT >= 29) {
        try {
            Intent service = new Intent().setComponent(new ComponentName(getPackageName(), ProbeService.class.getName()));
            Executor executor = Runnable::run;
            ServiceConnection connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, android.os.IBinder service) {
                    Log.d(TAG, "=== PR8 executor bind connected unexpectedly === " + name);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.d(TAG, "=== PR8 executor bind disconnected === " + name);
                }
            };
            boolean bound = bindService(service, Context.BIND_AUTO_CREATE, executor, connection);
            out.append("bindService.executorResult: ").append(bound).append("\n");
        } catch (Exception e) {
            out.append("bindService executor failed: ")
                .append(e.getClass().getSimpleName())
                .append(": ")
                .append(e.getMessage())
                .append("\n");
        }
    }

    String result = out.toString();
    Log.d(TAG, "=== PR8 AMS API probe ===\n" + result);
    return result;
}
```

Do not add direct `Context.BindServiceFlags` Java calls in the fixture unless the module compiles against SDK 34+ and the source compatibility accepts the overload. The API34 wrapper unit/device coverage handles those signatures; the first hosted guest fixture can use int/executor overloads.

- [ ] **Step 5: Build minimal fixture**

Run:

```bash
./gradlew.bat :test-fixtures:minimal-app:assembleDebug --console=plain --no-build-cache
```

Expected:

```text
BUILD SUCCESSFUL
```

---

### Task 6: Run targeted GREEN androidTest and capture device evidence

**Files:**
- Test run only, no code edits.
- Evidence output directory: `.tmp/pr8-ams-api-evidence-<timestamp>`

**Interfaces:**
- Consumes:
  - `HostedContainerPr8AmsApiEvidenceTest`
  - `files/hosted_launch_evidence`
- Produces:
  - Device evidence bundle with logcat and the three PR-8 AMS API evidence files.

- [ ] **Step 1: Install fresh host and fixture APKs**

Run:

```bash
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" connect 192.168.2.42:36339
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 install -r "app/build/outputs/apk/debug/app-debug.apk"
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 install -r "test-fixtures/minimal-app/build/outputs/apk/debug/minimal-app-debug.apk"
```

Expected:

```text
Success
Success
```

- [ ] **Step 2: Run targeted androidTest**

Run:

```bash
ANDROID_SERIAL=192.168.2.42:36339 ./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.multiapp.app.HostedContainerPr8AmsApiEvidenceTest" --console=plain --no-build-cache
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Capture evidence without clearing logcat**

Run:

```bash
TS=$(date +%Y%m%d-%H%M%S)
OUT=".tmp/pr8-ams-api-evidence-$TS"
mkdir -p "$OUT"
ADB="/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe"
SERIAL="192.168.2.42:36339"
"$ADB" -s "$SERIAL" devices -l > "$OUT/adb-devices.txt"
"$ADB" -s "$SERIAL" shell getprop ro.product.model > "$OUT/device-model.txt" 2>&1
"$ADB" -s "$SERIAL" shell getprop ro.build.version.release > "$OUT/android-release.txt" 2>&1
"$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk > "$OUT/android-sdk.txt" 2>&1
"$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi > "$OUT/abi.txt" 2>&1
"$ADB" -s "$SERIAL" logcat -d -v time > "$OUT/logcat.txt" 2>&1
"$ADB" -s "$SERIAL" shell run-as com.multiapp.app ls files/hosted_launch_evidence > "$OUT/hosted-evidence-ls.txt" 2>&1
{
  printf '=== hosted evidence ===\n'
  mapfile -t files < "$OUT/hosted-evidence-ls.txt"
  for raw in "${files[@]}"; do
    name=$(printf '%s' "$raw" | tr -d '\r')
    [ -z "$name" ] && continue
    printf '===files/hosted_launch_evidence/%s===\n' "$name"
    "$ADB" -s "$SERIAL" shell run-as com.multiapp.app cat "files/hosted_launch_evidence/$name" </dev/null 2>&1
    printf '\n'
  done
} > "$OUT/hosted-launch-evidence.txt"
printf '%s\n' "$OUT"
```

Expected:

```text
.tmp/pr8-ams-api-evidence-<timestamp>
```

- [ ] **Step 4: Verify captured PR-8 evidence files**

Run:

```bash
grep -E "ams-register-receiver|ams-sticky-ordered-broadcast|ams-bind-service-overload|status=|api=|hostFallback=|returnValue=|dispatchStatus=" "$OUT/hosted-launch-evidence.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime" "$OUT/logcat.txt" | grep "com.multiapp.app" || true
```

Expected evidence includes:

```text
===files/hosted_launch_evidence/<instanceId>.ams-register-receiver.properties===
status=DYNAMIC_RECEIVER_REGISTERED
stage=AMS_API_OVERLOAD
api=registerReceiver
hostFallback=false
registered=true

===files/hosted_launch_evidence/<instanceId>.ams-sticky-ordered-broadcast.properties===
status=STICKY_ORDERED_INTERCEPTED
stage=AMS_API_OVERLOAD
api=sendStickyOrderedBroadcast
hostFallback=false
dispatchStatus=UnsupportedImplicit

===files/hosted_launch_evidence/<instanceId>.ams-bind-service-overload.properties===
status=BIND_BLOCKED
stage=AMS_API_OVERLOAD
returnValue=false
hostFallback=false
```

Expected crash check:

```text
(no output from com.multiapp.app AndroidRuntime/FATAL EXCEPTION grep)
```

---

### Task 7: Review before broader build/test, then run targeted verification

**Files:**
- No source edits unless review finds blockers.

**Interfaces:**
- Consumes completed code from Tasks 1-6.
- Produces review verdict and verification results suitable for PR-8 status update.

- [ ] **Step 1: Request code review before build**

Run code review with focus on:

```text
- minSdk 28 class-loading risk from Context.BindServiceFlags
- no host fallback from bind/register/sticky ordered APIs
- evidence writer path safety
- whether evidence overclaims sticky semantics
- whether tests prove hosted guest production path
```

Expected:

```text
No CRITICAL/HIGH findings, or findings are fixed before build.
```

- [ ] **Step 2: Run focused JVM/unit tests**

Run:

```bash
./gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualAmsApiEvidenceRecorderTest" --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --console=plain --no-build-cache
./gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerAmsApiEvidenceRecorderTest" --console=plain --no-build-cache
```

Expected:

```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

- [ ] **Step 3: Run compile and targeted device test**

Run:

```bash
./gradlew.bat :core:loader:compileDebugKotlin :app:compileDebugKotlin :test-fixtures:minimal-app:assembleDebug --console=plain --no-build-cache
ANDROID_SERIAL=192.168.2.42:36339 ./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.multiapp.app.HostedContainerPr8AmsApiEvidenceTest" --console=plain --no-build-cache
```

Expected:

```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

- [ ] **Step 4: Summarize status without overclaiming**

Use this wording if verification passes:

```text
PR-8 AMS API专项 device evidence 已补：registerReceiver、sticky ordered broadcast、bindService overload blocking 均从 hosted guest production path 写入 files/hosted_launch_evidence，并在 SDK36 设备 192.168.2.42:36339 上通过 targeted androidTest 验证。Virtual AMS 仍不声明 complete；全局 IActivityManager/IActivityTaskManager proxy、完整 sticky semantics 和真实 bound-service lifecycle 仍在范围外。
```

Use this wording if device test fails:

```text
PR-8 AMS API专项实现/测试已推进，但设备验证未通过；当前状态保持 PARTIAL+，失败点为 <exact failure>，证据目录为 <path>。
```

Actual 2026-07-03 device result:

```text
PR-8 AMS API专项 device evidence 已补：registerReceiver、sticky ordered broadcast、bindService overload blocking 均从 hosted guest production path 写入 files/hosted_launch_evidence，并在 SDK36 设备 192.168.2.42:44113 上通过用户手动启动路径验证。connectedDebugAndroidTest 未通过自动化验收，失败点是 MIUI/HyperOS 对 instrumentation 启动 ContainerActivity 的权限拦截；该失败不代表 runtime probe 失败。Virtual AMS 仍不声明 complete；全局 IActivityManager/IActivityTaskManager proxy、完整 sticky semantics 和真实 bound-service lifecycle 仍在范围外。
evidenceDir=.tmp/pr8-after-manual-20260703-202959
fixedDump=.tmp/pr8-after-manual-20260703-202959/evidence-dump-fixed-20260703-203204.txt
stickyOrderedStatus=STICKY_ORDERED_INTERCEPTED
stickyOrderedDispatchStatus=UnsupportedImplicit
```

---

## Self-Review

### Spec coverage

- `registerReceiver(...)` evidence: Task 1 asserts, Task 4 emits, Task 5 triggers, Task 6 captures.
- `sendStickyOrderedBroadcast(...)` evidence: Task 1 asserts, Task 4 emits, Task 5 triggers, Task 6 captures.
- `bindService(...)` overload blocking evidence: Task 1 asserts, Task 4 emits, Task 5 triggers int/executor overload, Task 6 captures.
- Hosted guest production path: Task 5 invokes APIs from minimal guest `MainActivity`, Task 1 launches `ContainerActivity` hosted instance.
- Device evidence: Task 6 captures from `192.168.2.42:44113` without clearing logcat.
- Anti-overclaim: Task 7 gives explicit allowed wording and out-of-scope wording.

### Placeholder scan

No `TBD`, `TODO`, `implement later`, vague “add appropriate handling”, or “write tests for the above” instructions remain. Every code step includes concrete code or exact replacement snippets.

### Type consistency

- `VirtualAmsApiEvidenceComponent.componentName` is defined in Task 2 and consumed in Task 3.
- `VirtualAmsApiEvidenceRecord.fields` is a `Map<String, Any?>` in Task 2 and written by Task 3.
- Evidence component names match spec and androidTest assertions:
  - `ams-register-receiver`
  - `ams-sticky-ordered-broadcast`
  - `ams-bind-service-overload`
- `dispatchBindServiceIntent(service, api)` is defined in Task 4 and consumed by base/API34 wrappers.

### Scope check

The plan is one focused PR-8 evidence slice. It intentionally excludes real bound-service lifecycle, global AMS Binder proxies, full sticky broadcast compatibility, and protected app claims.
