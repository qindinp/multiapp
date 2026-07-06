# PR-5 LoadedApk Sandbox Device Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce ordinary-app device evidence for the ActivityThread/LoadedApk gate: `loadedApkSource=GUEST_SANDBOX`, alias installation, guest ActivityClientRecord patching, and virtual Activity/Application identity before `onCreate`.

**Architecture:** Keep the existing preferred sandbox path through `ActivityThread.getPackageInfoNoCheck(...)` and make PR-5 a narrow evidence/identity closure slice. Add a small `HostedActivityIdentity` helper that creates virtual-package `ApplicationInfo` / `ActivityInfo` copies for ActivityThread and LoadedApk only, plus a testable `HostedActivityContextEvidenceFormatter` so the device evidence file contains deterministic gate fields. Do not change global PMS/AMS/provider/storage routing in this PR.

**Tech Stack:** Kotlin, Android framework compatibility reflection, Android Gradle Plugin, kotlin.test/JVM unit tests, existing MockK usage, manual ADB device evidence.

---

## Scope guard

This plan implements the next step after commit `0029358 refactor: extract hosted bootstrap stages`:

```text
PR-5: LoadedApk sandbox device evidence
Blueprint Phase 4: ActivityThread + LoadedApk sandbox closure
Roadmap Phase D: hosted runtime kernel
Primary gates: ActivityThread launch record restoration + LoadedApk sandbox
```

Allowed runtime changes:

- Enrich Activity context evidence fields.
- Ensure ActivityThread/LoadedApk receives virtual-package `ActivityInfo.packageName` and `ApplicationInfo.packageName` while preserving origin + virtual aliases.
- Keep fallback LoadedApk patch behavior explicit and diagnosable.
- Add deterministic JVM tests for the evidence formatter and identity helper.

Out of scope:

- Do not implement PR-6 lifecycle matrix (`Activity A -> B -> Back`, `onNewIntent`, activity result baseline).
- Do not hook global PMS (`AppGlobals/IPackageManager`, `ActivityThread.sPackageManager`, `ApplicationPackageManager.mPM`).
- Do not introduce a Virtual AMS dispatcher or `IActivityTaskManager` / `IActivityManager` proxy.
- Do not expand provider method coverage, Java absolute-path rewrite, or native IO diagnostics.
- Do not change QQ Reader/protected-app behavior, LSPlant/Xposed defaults, business native wrappers, or no-op patches.
- Do not grow legacy Stub clone APK / LoaderFactory paths.

Project git rule: do not commit unless the owner explicitly asks. Each task ends with a checkpoint instead of a commit.

Project verification rule from memory: after code changes, run code review before Gradle build.

---

## File structure

### Create

- `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatter.kt`
  - Formats `HostedActivityContextInjector.InjectionResult` into the `.activity-context.properties` body.
  - Keeps field ordering deterministic for device evidence and review.
- `core/loader/src/test/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatterTest.kt`
  - Verifies the PR-5 gate fields are emitted exactly.
- `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityIdentity.kt`
  - Creates virtual-package `ApplicationInfo` and `ActivityInfo` copies for ActivityThread/LoadedApk record restoration.
  - Avoids changing `VirtualPackageInfoFactory` global PMS behavior in this PR.
- `core/loader/src/test/java/com/multiapp/core/loader/HostedActivityIdentityTest.kt`
  - Verifies virtual identity fields and no mutation of the source `ApplicationInfo`.
- `docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md`
  - Records scope, verification, device evidence, remaining gaps, and protected-runtime defaults.

### Modify

- `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextInjector.kt`
  - Extend `InjectionResult` with origin/virtual identity, ActivityInfo/ApplicationInfo package evidence, LoadedApk alias map evidence, and skipped field reasons.
  - Use `HostedActivityIdentity` for LoadedApk state and ActivityClientRecord state.
- `core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt`
  - Delegate Activity context evidence formatting to `HostedActivityContextEvidenceFormatter`.
- `core/loader/src/test/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstallerTest.kt`
  - Add a guard that `GUEST_SANDBOX` aliases remain origin + virtual in both package maps.
- `core/loader/src/test/java/com/multiapp/core/loader/ActivityClientRecordBridgeTest.kt`
  - Add a guard that a virtual-package `ActivityInfo` is preserved in the record patch result.

---

## Evidence contract for PR-5

The context evidence file at `hosted_launch_evidence/<instanceId>.activity-context.properties` must contain these fields after this plan:

```text
status=GUEST_ACTIVITY_CONTEXT_INJECTED
stage=ACTIVITY_CONTEXT
guestActivityClassName=<guest Activity class>
contextInjected=true
applicationInjected=<true|false>
packageName=<virtualPackageName>
originPackageName=<origin package>
virtualPackageName=<virtual package>
activityInfo.packageName=<virtualPackageName>
applicationInfo.packageName=<virtualPackageName>
applicationClassName=<guest Application class or empty>
loadedApkTargetClassName=<android.app.LoadedApk or fake test target>
loadedApkPatchedFields=<comma-separated fields>
loadedApkSkippedFieldReasons=<comma-separated field:reason entries>
loadedApkInstalledAliasCount=<count>
loadedApkInstalledAliasesByField=mPackages:<origin>,<virtual>;mResourcePackages:<origin>,<virtual>
loadedApkAliasSkippedReasonsByField=<field:reason entries or empty>
loadedApkSkippedReason=<empty or specific guard/failure>
loadedApkSource=GUEST_SANDBOX
activityRecordPatchedFields=activityInfo,intent,packageInfo
activityRecordSkippedReason=<empty or specific reason>
appCompatThemeGuardApplied=<true|false>
appCompatThemeResourceId=<resource id or 0>
dataDir=<instance dataRoot>
```

Device success for PR-5 requires these minimum values for a normal ordinary-app launch:

```text
activityRecordPatchedFields=activityInfo,intent,packageInfo
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount>=2
contextInjected=true
activityInfo.packageName=<virtualPackageName>
applicationInfo.packageName=<virtualPackageName>
dataDir=<instance dataRoot>
```

If device evidence shows `EXISTING_PATCH`, `HOST_LOADED_APK_GUARD`, missing alias maps, or origin package identity in ActivityThread fields, PR-5 remains PARTIAL and the document must record the exact failed field.

---

## Task 1: Add testable Activity context evidence formatter

**Files:**
- Create: `core/loader/src/test/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatterTest.kt`
- Create: `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatter.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextInjector.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt`

- [ ] **Step 1: Write the failing formatter test**

Create `core/loader/src/test/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatterTest.kt`:

```kotlin
package com.multiapp.core.loader

import kotlin.test.Test
import kotlin.test.assertTrue

class HostedActivityContextEvidenceFormatterTest {

    @Test
    fun `format emits PR5 LoadedApk and ActivityThread gate fields`() {
        val injection = HostedActivityContextInjector.InjectionResult(
            contextInjected = true,
            applicationInjected = true,
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
            packageName = "com.multiapp.instance.abc",
            applicationClassName = "com.test.minimal.MinimalApp",
            originPackageName = "com.test.minimal",
            virtualPackageName = "com.multiapp.instance.abc",
            activityInfoPackageName = "com.multiapp.instance.abc",
            applicationInfoPackageName = "com.multiapp.instance.abc",
            loadedApkTargetClassName = "android.app.LoadedApk",
            loadedApkPatchedFields = listOf(
                "mApplicationInfo",
                "mResources",
                "mClassLoader",
                "mPackageName",
                "mDataDir"
            ),
            loadedApkSkippedFieldReasons = listOf("mDeviceProtectedDataDirFile:FIELD_NOT_FOUND"),
            loadedApkInstalledAliasCount = 4,
            loadedApkInstalledAliasesByField = linkedMapOf(
                "mPackages" to listOf("com.test.minimal", "com.multiapp.instance.abc"),
                "mResourcePackages" to listOf("com.test.minimal", "com.multiapp.instance.abc")
            ),
            loadedApkAliasSkippedReasonsByField = emptyMap(),
            loadedApkSkippedReason = null,
            loadedApkSource = "GUEST_SANDBOX",
            activityRecordPatchedFields = listOf("activityInfo", "intent", "packageInfo"),
            activityRecordSkippedReason = null,
            appCompatThemeGuardApplied = false,
            appCompatThemeResourceId = 0
        )

        val text = HostedActivityContextEvidenceFormatter.format(
            guestActivityClassName = "com.test.minimal.MainActivity",
            injection = injection
        )

        assertTrue("status=GUEST_ACTIVITY_CONTEXT_INJECTED" in text)
        assertTrue("stage=ACTIVITY_CONTEXT" in text)
        assertTrue("guestActivityClassName=com.test.minimal.MainActivity" in text)
        assertTrue("packageName=com.multiapp.instance.abc" in text)
        assertTrue("originPackageName=com.test.minimal" in text)
        assertTrue("virtualPackageName=com.multiapp.instance.abc" in text)
        assertTrue("activityInfo.packageName=com.multiapp.instance.abc" in text)
        assertTrue("applicationInfo.packageName=com.multiapp.instance.abc" in text)
        assertTrue("loadedApkTargetClassName=android.app.LoadedApk" in text)
        assertTrue("loadedApkSource=GUEST_SANDBOX" in text)
        assertTrue("loadedApkInstalledAliasCount=4" in text)
        assertTrue(
            "loadedApkInstalledAliasesByField=mPackages:com.test.minimal,com.multiapp.instance.abc;" +
                "mResourcePackages:com.test.minimal,com.multiapp.instance.abc" in text
        )
        assertTrue("loadedApkSkippedFieldReasons=mDeviceProtectedDataDirFile:FIELD_NOT_FOUND" in text)
        assertTrue("activityRecordPatchedFields=activityInfo,intent,packageInfo" in text)
        assertTrue("dataDir=/data/user/0/com.multiapp.app/files/instance_data/inst-001" in text)
    }
}
```

- [ ] **Step 2: Run RED formatter test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedActivityContextEvidenceFormatterTest"
```

Expected: FAIL at compile time with unresolved `HostedActivityContextEvidenceFormatter` and unresolved named arguments on `InjectionResult`.

- [ ] **Step 3: Extend `InjectionResult`**

Replace the `InjectionResult` data class in `HostedActivityContextInjector.kt` with:

```kotlin
    data class InjectionResult(
        val contextInjected: Boolean,
        val applicationInjected: Boolean,
        val dataDir: String,
        val packageName: String,
        val applicationClassName: String?,
        val originPackageName: String,
        val virtualPackageName: String,
        val activityInfoPackageName: String?,
        val applicationInfoPackageName: String?,
        val loadedApkTargetClassName: String? = null,
        val loadedApkPatchedFields: List<String> = emptyList(),
        val loadedApkSkippedFieldReasons: List<String> = emptyList(),
        val loadedApkInstalledAliasCount: Int = 0,
        val loadedApkInstalledAliasesByField: Map<String, List<String>> = emptyMap(),
        val loadedApkAliasSkippedReasonsByField: Map<String, String> = emptyMap(),
        val loadedApkSkippedReason: String? = null,
        val loadedApkSource: String? = null,
        val activityRecordPatchedFields: List<String> = emptyList(),
        val activityRecordSkippedReason: String? = null,
        val appCompatThemeGuardApplied: Boolean = false,
        val appCompatThemeResourceId: Int = 0
    )
```

- [ ] **Step 4: Create the formatter implementation**

Create `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatter.kt`:

```kotlin
package com.multiapp.core.loader

internal object HostedActivityContextEvidenceFormatter {

    fun format(
        guestActivityClassName: String,
        injection: HostedActivityContextInjector.InjectionResult
    ): String = listOf(
        "status=GUEST_ACTIVITY_CONTEXT_INJECTED",
        "stage=ACTIVITY_CONTEXT",
        "guestActivityClassName=$guestActivityClassName",
        "contextInjected=${injection.contextInjected}",
        "applicationInjected=${injection.applicationInjected}",
        "packageName=${injection.packageName}",
        "originPackageName=${injection.originPackageName}",
        "virtualPackageName=${injection.virtualPackageName}",
        "activityInfo.packageName=${injection.activityInfoPackageName.orEmpty()}",
        "applicationInfo.packageName=${injection.applicationInfoPackageName.orEmpty()}",
        "applicationClassName=${injection.applicationClassName.orEmpty()}",
        "loadedApkTargetClassName=${injection.loadedApkTargetClassName.orEmpty()}",
        "loadedApkPatchedFields=${injection.loadedApkPatchedFields.joinToString(",")}",
        "loadedApkSkippedFieldReasons=${injection.loadedApkSkippedFieldReasons.joinToString(",")}",
        "loadedApkInstalledAliasCount=${injection.loadedApkInstalledAliasCount}",
        "loadedApkInstalledAliasesByField=${formatStringListMap(injection.loadedApkInstalledAliasesByField)}",
        "loadedApkAliasSkippedReasonsByField=${formatStringMap(injection.loadedApkAliasSkippedReasonsByField)}",
        "loadedApkSkippedReason=${injection.loadedApkSkippedReason.orEmpty()}",
        "loadedApkSource=${injection.loadedApkSource.orEmpty()}",
        "activityRecordPatchedFields=${injection.activityRecordPatchedFields.joinToString(",")}",
        "activityRecordSkippedReason=${injection.activityRecordSkippedReason.orEmpty()}",
        "appCompatThemeGuardApplied=${injection.appCompatThemeGuardApplied}",
        "appCompatThemeResourceId=${injection.appCompatThemeResourceId}",
        "dataDir=${injection.dataDir}"
    ).joinToString("\n")

    private fun formatStringListMap(values: Map<String, List<String>>): String =
        values.toSortedMap().entries.joinToString(";") { (field, entries) ->
            "$field:${entries.joinToString(",")}"
        }

    private fun formatStringMap(values: Map<String, String>): String =
        values.toSortedMap().entries.joinToString(";") { (field, reason) ->
            "$field:$reason"
        }
}
```

- [ ] **Step 5: Delegate `VirtualInstrumentation` context evidence writing to the formatter**

In `VirtualInstrumentation.writeActivityContextEvidence(...)`, replace the `File(...).writeText(listOf(...).joinToString("\n"))` block with:

```kotlin
            File(evidenceDir, HostedActivityEvidenceFiles.context(instanceId)).writeText(
                HostedActivityContextEvidenceFormatter.format(
                    guestActivityClassName = guestActivityClassName,
                    injection = injection
                )
            )
```

- [ ] **Step 6: Update the existing `InjectionResult` construction with temporary passthrough values**

In `HostedActivityContextInjector.inject(...)`, add the new constructor arguments using the current config and current patch result. Use these exact values for now; Task 3 will replace the ActivityInfo/ApplicationInfo package values with the virtualized runtime identity:

```kotlin
            originPackageName = config.originPackageName,
            virtualPackageName = config.virtualPackageName,
            activityInfoPackageName = config.virtualPackageName,
            applicationInfoPackageName = config.virtualPackageName,
            loadedApkTargetClassName = loadedApkPatch?.targetClassName,
            loadedApkSkippedFieldReasons = loadedApkPatch?.patchResult?.skippedFieldReasons.orEmpty(),
            loadedApkInstalledAliasesByField = loadedApkPatch?.installedAliasesByField.orEmpty(),
            loadedApkAliasSkippedReasonsByField = loadedApkPatch?.skippedAliasInstallReasonsByField.orEmpty(),
```

The full return block should include the existing fields plus the new ones:

```kotlin
        return InjectionResult(
            contextInjected = contextInjected,
            applicationInjected = applicationInjected,
            dataDir = config.dataDir,
            packageName = config.virtualPackageName,
            applicationClassName = guestApplication?.javaClass?.name,
            originPackageName = config.originPackageName,
            virtualPackageName = config.virtualPackageName,
            activityInfoPackageName = config.virtualPackageName,
            applicationInfoPackageName = config.virtualPackageName,
            loadedApkTargetClassName = loadedApkPatch?.targetClassName,
            loadedApkPatchedFields = loadedApkPatch?.patchResult?.patchedFields.orEmpty(),
            loadedApkSkippedFieldReasons = loadedApkPatch?.patchResult?.skippedFieldReasons.orEmpty(),
            loadedApkInstalledAliasCount = loadedApkPatch?.installedAliasCount ?: 0,
            loadedApkInstalledAliasesByField = loadedApkPatch?.installedAliasesByField.orEmpty(),
            loadedApkAliasSkippedReasonsByField = loadedApkPatch?.skippedAliasInstallReasonsByField.orEmpty(),
            loadedApkSkippedReason = loadedApkPatch?.skippedReason,
            loadedApkSource = loadedApkPatch?.source?.name,
            activityRecordPatchedFields = activityRecordPatch?.patchedFields.orEmpty(),
            activityRecordSkippedReason = activityRecordPatch?.skippedReason,
            appCompatThemeGuardApplied = appCompatThemeGuard.applied,
            appCompatThemeResourceId = appCompatThemeGuard.themeResourceId
        )
```

- [ ] **Step 7: Run GREEN formatter test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedActivityContextEvidenceFormatterTest"
```

Expected: PASS for `HostedActivityContextEvidenceFormatterTest`.

- [ ] **Step 8: Checkpoint**

Record:

```text
Checkpoint Task 1:
- Formatter test added and passing.
- Activity context evidence writer now uses HostedActivityContextEvidenceFormatter.
- No global PMS/AMS/provider/storage/protected-app changes.
```

---

## Task 2: Add virtual ActivityThread identity helper

**Files:**
- Create: `core/loader/src/test/java/com/multiapp/core/loader/HostedActivityIdentityTest.kt`
- Create: `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityIdentity.kt`

- [ ] **Step 1: Write the failing identity helper tests**

Create `core/loader/src/test/java/com/multiapp/core/loader/HostedActivityIdentityTest.kt`:

```kotlin
package com.multiapp.core.loader

import android.content.pm.ApplicationInfo
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualContextConfig
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HostedActivityIdentityTest {

    @Test
    fun `application info for runtime uses virtual package and instance paths without mutating source`() {
        val source = ApplicationInfo().apply {
            packageName = "com.test.minimal"
            className = "com.test.minimal.MinimalApp"
            name = "com.test.minimal.MinimalApp"
            sourceDir = "/host/old.apk"
            publicSourceDir = "/host/old.apk"
            dataDir = "/host/data"
            nativeLibraryDir = "/host/lib"
            processName = "com.test.minimal"
            taskAffinity = "com.test.minimal"
            theme = 0x7f010001
        }
        val config = config()

        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(config, source)

        assertEquals("com.multiapp.instance.abc", runtimeInfo.packageName)
        assertEquals("com.test.minimal.MinimalApp", runtimeInfo.className)
        assertEquals("com.test.minimal.MinimalApp", runtimeInfo.name)
        assertEquals("/data/apks/minimal.apk", runtimeInfo.sourceDir)
        assertEquals("/data/apks/minimal.apk", runtimeInfo.publicSourceDir)
        assertEquals("/data/user/0/com.multiapp.app/files/instance_data/inst-001", runtimeInfo.dataDir)
        assertEquals("/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib", runtimeInfo.nativeLibraryDir)
        assertEquals("com.test.minimal", source.packageName)
        assertEquals("/host/data", source.dataDir)
    }

    @Test
    fun `activity info for record uses virtual package and supplied virtual application info`() {
        val config = config(snapshot = snapshot())
        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = ApplicationInfo().apply {
                packageName = "com.test.minimal"
                sourceDir = "/data/apks/minimal.apk"
                publicSourceDir = "/data/apks/minimal.apk"
                dataDir = "/origin/data"
                nativeLibraryDir = "/origin/lib"
            }
        )

        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = "com.test.minimal.MainActivity",
            applicationInfo = runtimeInfo
        )

        assertEquals("com.multiapp.instance.abc", activityInfo.packageName)
        assertEquals("com.test.minimal.MainActivity", activityInfo.name)
        assertEquals(0x7f010002, activityInfo.theme)
        assertSame(runtimeInfo, activityInfo.applicationInfo)
        assertEquals("com.multiapp.instance.abc", activityInfo.applicationInfo.packageName)
    }

    @Test
    fun `activity info fallback uses virtual package when snapshot component is missing`() {
        val config = config(snapshot = snapshot())
        val runtimeInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = ApplicationInfo().apply {
                packageName = "com.test.minimal"
                theme = 0x7f010003
                processName = "com.test.minimal"
                taskAffinity = "com.test.minimal"
            }
        )

        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = "com.test.minimal.MissingActivity",
            applicationInfo = runtimeInfo
        )

        assertEquals("com.multiapp.instance.abc", activityInfo.packageName)
        assertEquals("com.test.minimal.MissingActivity", activityInfo.name)
        assertEquals(0x7f010003, activityInfo.theme)
        assertSame(runtimeInfo, activityInfo.applicationInfo)
    }

    private fun config(snapshot: VirtualPackageSnapshot? = null) = VirtualContextConfig(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
        sourceDir = "/data/apks/minimal.apk",
        nativeLibraryDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
        classLoader = ClassLoader.getSystemClassLoader(),
        applicationLabel = "MinimalTest",
        packageSnapshot = snapshot
    )

    private fun snapshot() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.test.minimal",
        virtualPackageName = "com.multiapp.instance.abc",
        applicationLabel = "MinimalTest",
        versionCode = 42,
        versionName = "4.2",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/data/apks/minimal.apk",
        dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001",
        nativeLibraryDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib",
        applicationClassName = "com.test.minimal.MinimalApp",
        launcherActivityName = "com.test.minimal.MainActivity",
        themeId = 0x7f010001,
        activities = listOf(
            ResolvedComponent(
                name = "com.test.minimal.MainActivity",
                exported = true,
                themeId = 0x7f010002
            )
        )
    )
}
```

- [ ] **Step 2: Run RED identity test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedActivityIdentityTest"
```

Expected: FAIL at compile time with unresolved `HostedActivityIdentity`.

- [ ] **Step 3: Implement `HostedActivityIdentity`**

Create `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityIdentity.kt`:

```kotlin
package com.multiapp.core.loader

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import com.multiapp.core.model.virtual.VirtualContextConfig

internal object HostedActivityIdentity {

    fun applicationInfoForRuntime(
        config: VirtualContextConfig,
        source: ApplicationInfo
    ): ApplicationInfo = ApplicationInfo(source).apply {
        packageName = config.virtualPackageName
        sourceDir = config.sourceDir
        publicSourceDir = config.sourceDir
        dataDir = config.dataDir
        nativeLibraryDir = config.nativeLibraryDir
        nonLocalizedLabel = config.applicationLabel ?: source.nonLocalizedLabel ?: config.originPackageName
        enabled = true
    }

    fun activityInfoForRecord(
        config: VirtualContextConfig,
        guestActivityClassName: String,
        applicationInfo: ApplicationInfo
    ): ActivityInfo {
        val snapshot = config.packageSnapshot
        val componentInfo = snapshot?.activities?.firstOrNull { it.name == guestActivityClassName }
        if (snapshot != null && componentInfo != null) {
            return ActivityInfo(VirtualPackageInfoFactory.activityInfo(snapshot, componentInfo)).apply {
                packageName = config.virtualPackageName
                this.applicationInfo = applicationInfo
            }
        }
        return ActivityInfo().apply {
            packageName = config.virtualPackageName
            name = guestActivityClassName
            this.applicationInfo = applicationInfo
            enabled = true
            exported = true
            theme = applicationInfo.theme
            processName = applicationInfo.processName
            taskAffinity = applicationInfo.taskAffinity
        }
    }
}
```

- [ ] **Step 4: Run GREEN identity test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedActivityIdentityTest"
```

Expected: PASS for `HostedActivityIdentityTest`.

- [ ] **Step 5: Checkpoint**

Record:

```text
Checkpoint Task 2:
- HostedActivityIdentity creates virtual-package ApplicationInfo/ActivityInfo copies.
- Source ApplicationInfo remains unmodified.
- VirtualPackageInfoFactory and VirtualPackageService global PMS behavior were not changed.
```

---

## Task 3: Use virtual identity in LoadedApk state and ActivityClientRecord state

**Files:**
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextInjector.kt`
- Modify: `core/loader/src/test/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstallerTest.kt`
- Modify: `core/loader/src/test/java/com/multiapp/core/loader/ActivityClientRecordBridgeTest.kt`

- [ ] **Step 1: Add a LoadedApk installer identity guard test**

Append this test to `ActivityThreadLoadedApkInstallerTest` before the fake classes:

```kotlin
    @Test
    fun `guest sandbox patches virtual package identity while installing origin and virtual aliases`() {
        val activityThread = FakeActivityThread()
        val appInfo = ApplicationInfo().apply {
            packageName = "com.multiapp.instance.abc"
            sourceDir = "/data/app/minimal.apk"
            publicSourceDir = "/data/app/minimal.apk"
            dataDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001"
            nativeLibraryDir = "/data/user/0/com.multiapp.app/files/instance_data/inst-001/lib"
        }

        val result = ActivityThreadLoadedApkInstaller.installGuestSandbox(
            activityThread = activityThread,
            state = LoadedApkRuntimeState(
                packageName = "com.multiapp.instance.abc",
                applicationInfo = appInfo,
                resources = mockk(relaxed = true),
                classLoader = ClassLoader.getSystemClassLoader()
            ),
            packageAliases = listOf("com.test.minimal", "com.multiapp.instance.abc")
        )

        val loadedApk = result.loadedApk as FakeLoadedApk
        assertEquals(LoadedApkInstallSource.GUEST_SANDBOX, result.source)
        assertEquals("com.multiapp.instance.abc", loadedApk.packageName())
        assertEquals("com.multiapp.instance.abc", loadedApk.applicationInfo()?.packageName)
        assertEquals(4, result.installedAliasCount)
        assertEquals(
            listOf("com.test.minimal", "com.multiapp.instance.abc"),
            result.installedAliasesByField["mPackages"]
        )
        assertEquals(
            listOf("com.test.minimal", "com.multiapp.instance.abc"),
            result.installedAliasesByField["mResourcePackages"]
        )
    }
```

- [ ] **Step 2: Add an ActivityClientRecord virtual identity guard test**

Append this test to `ActivityClientRecordBridgeTest` before the fake classes:

```kotlin
    @Test
    fun `patch preserves virtual package ActivityInfo in ActivityClientRecord`() {
        val record = FakeActivityClientRecord()
        val activityInfo = ActivityInfo().apply {
            packageName = "com.multiapp.instance.abc"
            name = "com.test.minimal.MainActivity"
        }

        val result = ActivityClientRecordBridge.patch(
            record = record,
            state = ActivityClientRecordRuntimeState(
                activityInfo = activityInfo,
                intent = Intent("guest.intent"),
                loadedApk = Any()
            )
        )

        assertEquals(null, result.skippedReason)
        assertEquals("com.multiapp.instance.abc", record.activityInfo?.packageName)
    }
```

- [ ] **Step 3: Run the targeted bridge tests**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.ActivityClientRecordBridgeTest"
```

Expected: PASS. These are guard tests around existing bridge behavior; the production injector wiring changes in the next step.

- [ ] **Step 4: Add an internal Activity record evidence wrapper**

Inside `HostedActivityContextInjector`, add this private data class near `AppCompatThemeGuardResult`:

```kotlin
    private data class ActivityRecordInjectionResult(
        val patchResult: ActivityClientRecordPatchResult?,
        val activityInfoPackageName: String,
        val applicationInfoPackageName: String?
    )
```

- [ ] **Step 5: Replace `patchActivityClientRecordIfPresent` signature and implementation**

Replace the existing `patchActivityClientRecordIfPresent(...)` function with:

```kotlin
    private fun patchActivityClientRecordIfPresent(
        activity: Activity,
        config: VirtualContextConfig,
        applicationInfo: android.content.pm.ApplicationInfo,
        loadedApk: Any?
    ): ActivityRecordInjectionResult {
        val guestActivityClassName = activity.intent?.getStringExtra("multiapp.guestActivityClassName")
            ?.takeIf { it.isNotBlank() }
            ?: activity.javaClass.name
        val activityInfo = HostedActivityIdentity.activityInfoForRecord(
            config = config,
            guestActivityClassName = guestActivityClassName,
            applicationInfo = applicationInfo
        )
        val guestIntent = Intent(activity.intent).apply {
            component = ComponentName(config.originPackageName, guestActivityClassName)
            setPackage(config.originPackageName)
        }
        val patchResult = runCatching {
            ActivityClientRecordBridge.patchCurrentActivityRecord(
                activityThread = ActivityThreadCompat.currentActivityThread(),
                activity = activity,
                state = ActivityClientRecordRuntimeState(
                    activityInfo = activityInfo,
                    intent = guestIntent,
                    loadedApk = loadedApk
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to patch ActivityClientRecord: ${activity.javaClass.name}", error)
        }.getOrNull()
        return ActivityRecordInjectionResult(
            patchResult = patchResult,
            activityInfoPackageName = activityInfo.packageName,
            applicationInfoPackageName = activityInfo.applicationInfo?.packageName
        )
    }
```

- [ ] **Step 6: Replace `patchLoadedApkIfPresent` signature and state construction**

Change the `patchLoadedApkIfPresent(...)` signature to accept the virtual runtime `ApplicationInfo`:

```kotlin
    private fun patchLoadedApkIfPresent(
        activity: Activity,
        guestContext: VirtualContextWrapper,
        config: VirtualContextConfig,
        applicationInfo: android.content.pm.ApplicationInfo,
        guestClassLoader: ClassLoader
    ): ActivityThreadLoadedApkInstallResult? {
```

Inside it, replace the `LoadedApkRuntimeState` block with:

```kotlin
        val state = LoadedApkRuntimeState(
            packageName = config.virtualPackageName,
            applicationInfo = applicationInfo,
            resources = guestContext.resources,
            classLoader = guestClassLoader
        )
```

Keep aliases unchanged:

```kotlin
        val aliases = listOf(config.originPackageName, config.virtualPackageName)
```

- [ ] **Step 7: Wire virtual identity through `inject(...)`**

In `HostedActivityContextInjector.inject(...)`, create the runtime identity immediately after `guestContext`:

```kotlin
        val runtimeApplicationInfo = HostedActivityIdentity.applicationInfoForRuntime(
            config = config,
            source = guestContext.applicationInfo
        )
```

Replace the LoadedApk call with:

```kotlin
        val loadedApkPatch = patchLoadedApkIfPresent(
            activity = activity,
            guestContext = guestContext,
            config = config,
            applicationInfo = runtimeApplicationInfo,
            guestClassLoader = guestClassLoader
        )
```

Replace the ActivityClientRecord call with:

```kotlin
        val activityRecordPatch = patchActivityClientRecordIfPresent(
            activity = activity,
            config = config,
            applicationInfo = runtimeApplicationInfo,
            loadedApk = loadedApkPatch?.loadedApk
        )
```

In the `InjectionResult` return block, replace the ActivityInfo/ApplicationInfo package and record fields with:

```kotlin
            activityInfoPackageName = activityRecordPatch.activityInfoPackageName,
            applicationInfoPackageName = activityRecordPatch.applicationInfoPackageName,
            activityRecordPatchedFields = activityRecordPatch.patchResult?.patchedFields.orEmpty(),
            activityRecordSkippedReason = activityRecordPatch.patchResult?.skippedReason,
```

- [ ] **Step 8: Remove the old private `buildActivityInfo(...)` function**

Delete the old `buildActivityInfo(...)` function from `HostedActivityContextInjector.kt` because `HostedActivityIdentity.activityInfoForRecord(...)` now owns that responsibility.

Remove the unused `android.content.pm.ActivityInfo` import if it is still present.

- [ ] **Step 9: Run targeted tests**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedActivityIdentityTest" --tests "com.multiapp.core.loader.HostedActivityContextEvidenceFormatterTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.ActivityClientRecordBridgeTest"
```

Expected: PASS for all four test classes.

- [ ] **Step 10: Checkpoint**

Record:

```text
Checkpoint Task 3:
- LoadedApkRuntimeState now uses virtual package identity.
- ActivityClientRecord ActivityInfo/ApplicationInfo evidence now comes from runtime virtual identity.
- Origin and virtual aliases are still both installed.
- Existing bridge tests pass.
```

---

## Task 4: Add PR-5 evidence document

**Files:**
- Create: `docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md`

- [ ] **Step 1: Create the evidence document with current scope and verification slots**

Create `docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md`:

```markdown
# PR-5 LoadedApk Sandbox Device Evidence

Date: 2026-06-30
Branch: container-runtime-refactor
Scope: Blueprint Phase 4 / Roadmap Phase D / ActivityThread + LoadedApk sandbox closure
Status: implementation evidence added; device verdict recorded below after manual run

## Scope

This PR targets ordinary hosted-app ActivityThread/LoadedApk evidence only.

In scope:

- Activity context evidence enrichment.
- `LoadedApkRuntimeState` virtual package identity for ActivityThread sandbox construction.
- ActivityClientRecord `ActivityInfo` / `ApplicationInfo` virtual package evidence.
- Origin + virtual package aliases in ActivityThread package maps.

Out of scope:

- PR-6 lifecycle matrix: Activity A -> B -> Back, `onNewIntent`, activity result baseline.
- Global PMS proxy hooks.
- Virtual AMS dispatcher.
- Provider method coverage.
- Java absolute path and native IO redirect.
- QQ Reader/protected-app compatibility.

## Required minimum evidence

```text
activityRecordPatchedFields=activityInfo,intent,packageInfo
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount>=2
contextInjected=true
activityInfo.packageName=<virtualPackageName>
applicationInfo.packageName=<virtualPackageName>
dataDir=<instance dataRoot>
```

## JVM verification

Command:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Result:

```text
Not run yet in this PR-5 implementation session.
```

## App build verification

Command:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Result:

```text
Not run yet in this PR-5 implementation session.
```

## Device evidence command sequence

ADB path:

```powershell
C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe
```

Commands:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB devices
& $ADB shell getprop ro.product.model
& $ADB shell getprop ro.build.version.sdk
& $ADB shell getprop ro.product.cpu.abi
& $ADB shell getconf PAGESIZE
& $ADB install -r app\build\outputs\apk\debug\app-debug.apk
& $ADB logcat -c
```

Manual action:

```text
Open MultiApp, launch a minimal or ordinary hosted instance, wait until the first guest Activity is visible or the launch fails.
```

Capture:

```powershell
& $ADB logcat -d -t 15s > .tmp\pr5-loadedapk-logcat.txt
& $ADB shell run-as com.multiapp.app ls files/hosted_launch_evidence
& $ADB shell run-as com.multiapp.app cat files/hosted_launch_evidence/<instanceId>.activity-context.properties
```

## Device verdict

```text
Device evidence not captured yet.
```

## Protected runtime defaults

This PR does not enable protected-app hooks by default:

```text
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
```

## Remaining gaps after PR-5

```text
PR-6 Activity lifecycle baseline remains: Activity A -> B -> Back, onNewIntent evidence, and activity result baseline or explicit unsupported reason.
PR-7 Virtual PMS global proxy remains.
PR-8 Virtual AMS dispatcher remains.
PR-9 Provider method coverage remains.
PR-10 Java + native storage redirect remains.
PR-11 protected app register-natives-only diagnostics remains.
```
```

- [ ] **Step 2: Checkpoint**

Record:

```text
Checkpoint Task 4:
- PR-5 evidence doc exists with exact device commands and anti-overclaim scope.
- Device verdict remains explicitly not captured until manual ADB run.
```

---

## Task 5: Run code review before build

**Files:**
- Review modified files from Tasks 1-4.

- [ ] **Step 1: Inspect the diff scope**

Run:

```powershell
git diff -- core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextInjector.kt core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatter.kt core/loader/src/main/java/com/multiapp/core/loader/HostedActivityIdentity.kt core/loader/src/test/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatterTest.kt core/loader/src/test/java/com/multiapp/core/loader/HostedActivityIdentityTest.kt core/loader/src/test/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstallerTest.kt core/loader/src/test/java/com/multiapp/core/loader/ActivityClientRecordBridgeTest.kt docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md
```

Expected: diff contains only PR-5 evidence/identity files and no QQ Reader/protected-app/default-hook changes.

- [ ] **Step 2: Launch code-reviewer agent before build**

Use the `code-reviewer` agent with this prompt:

```text
Review this PR-5 scoped diff before any Gradle build. Focus on Kotlin correctness, Android framework identity risks, evidence accuracy, overclaiming, and scope creep. The intended scope is only ActivityThread/LoadedApk evidence and virtual ActivityInfo/ApplicationInfo identity for ordinary hosted Activity launch. Verify no global PMS/AMS/provider/storage/protected-app behavior changed.
```

Expected: reviewer returns no CRITICAL/HIGH findings, or returns findings that are fixed before build.

- [ ] **Step 3: Fix review findings inside PR-5 scope**

If the reviewer finds issues, fix only files listed in this plan unless the finding names a directly related compile break. Do not expand to PMS/AMS/provider/storage/protected app.

- [ ] **Step 4: Checkpoint**

Record:

```text
Checkpoint Task 5:
- Code review completed before Gradle build.
- CRITICAL/HIGH findings fixed or no CRITICAL/HIGH findings reported.
```

---

## Task 6: Run JVM and app build verification

**Files:**
- No source edits unless a compile/test failure requires a narrow fix.

- [ ] **Step 1: Run focused tests**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedActivityIdentityTest" --tests "com.multiapp.core.loader.HostedActivityContextEvidenceFormatterTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.ActivityClientRecordBridgeTest"
```

Expected: PASS.

- [ ] **Step 2: Run full core loader unit tests**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Run app integration build**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Expected: PASS and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 4: Update the PR-5 evidence document with build results**

In `docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md`, replace the JVM and app build result blocks with actual command results:

```text
PASS on 2026-06-30: <short Gradle summary>
```

If a command fails, record:

```text
FAIL on 2026-06-30: <first failing task and exact error summary>
```

- [ ] **Step 5: Checkpoint**

Record:

```text
Checkpoint Task 6:
- Focused tests result recorded.
- Full core loader tests result recorded.
- App assemble result recorded.
```

---

## Task 7: Capture device evidence for ordinary hosted launch

**Files:**
- Modify: `docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md`
- Create or overwrite local evidence files under `.tmp/` only.

- [ ] **Step 1: Confirm ADB sees a device**

Run from repo root:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB devices
```

Expected: one device line with `device`, not `offline` or `unauthorized`.

- [ ] **Step 2: Record device properties**

Run:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB shell getprop ro.product.model
& $ADB shell getprop ro.build.version.sdk
& $ADB shell getprop ro.product.cpu.abi
& $ADB shell getconf PAGESIZE
```

Expected: non-empty model/API/ABI/page-size values.

- [ ] **Step 3: Install debug APK**

Run:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: `Success`.

- [ ] **Step 4: Clear logcat and launch ordinary hosted instance manually**

Run:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB logcat -c
```

Manual action:

```text
Open MultiApp on the device.
Launch a minimal or ordinary hosted instance from the app UI.
Wait until the first guest Activity is visible or a failure appears.
```

- [ ] **Step 5: Capture logcat**

Run:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
New-Item -ItemType Directory -Force .tmp | Out-Null
& $ADB logcat -d -t 15s > .tmp\pr5-loadedapk-logcat.txt
```

Expected: `.tmp/pr5-loadedapk-logcat.txt` contains the launch window.

- [ ] **Step 6: List evidence files**

Run:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB shell run-as com.multiapp.app ls files/hosted_launch_evidence
```

Expected: at least one `<instanceId>.activity-context.properties` file.

- [ ] **Step 7: Pull or print the Activity context evidence file**

Use the actual file name from Step 6:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB shell run-as com.multiapp.app cat files/hosted_launch_evidence/<instanceId>.activity-context.properties
```

Expected minimum successful content:

```text
status=GUEST_ACTIVITY_CONTEXT_INJECTED
stage=ACTIVITY_CONTEXT
contextInjected=true
packageName=<virtualPackageName>
originPackageName=<origin package>
virtualPackageName=<virtual package>
activityInfo.packageName=<virtualPackageName>
applicationInfo.packageName=<virtualPackageName>
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount=<number >= 2>
activityRecordPatchedFields=activityInfo,intent,packageInfo
dataDir=<instance dataRoot>
```

- [ ] **Step 8: Classify the device result**

Use this exact classification:

```text
PASS: all minimum fields match the expected values and logcat has no host crash.
PARTIAL: app builds and evidence exists, but one or more minimum fields are missing or show fallback.
FAIL: launch crashes before context evidence is written, or run-as cannot read evidence after a launch attempt.
```

- [ ] **Step 9: Update the PR-5 evidence document with device results**

In `docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md`, replace `Device evidence not captured yet.` with:

```text
Verdict: <PASS|PARTIAL|FAIL>
Device model: <model from adb>
Android API: <api from adb>
ABI: <abi from adb>
Page size: <page size from adb>
Evidence file: files/hosted_launch_evidence/<instanceId>.activity-context.properties
Logcat capture: .tmp/pr5-loadedapk-logcat.txt

Observed fields:
activityRecordPatchedFields=<actual value>
loadedApkSource=<actual value>
loadedApkInstalledAliasCount=<actual value>
contextInjected=<actual value>
activityInfo.packageName=<actual value>
applicationInfo.packageName=<actual value>
dataDir=<actual value>

Conclusion:
<one sentence matching the verdict without claiming PR-6 or full container completion>
```

- [ ] **Step 10: Checkpoint**

Record:

```text
Checkpoint Task 7:
- Device evidence captured or exact blocker recorded.
- PR-5 verdict is PASS/PARTIAL/FAIL based only on observed evidence fields.
```

---

## Task 8: Final anti-overclaim review and handoff

**Files:**
- Modify: `docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md` if wording overclaims.

- [ ] **Step 1: Search the PR-5 doc for disallowed claims**

Review the document and remove any wording equivalent to:

```text
LoadedApk sandbox complete
Activity lifecycle complete
container complete
QQ Reader fixed
protected apps compatible
Virtual PMS complete
Virtual AMS complete
native redirect complete
```

Allowed wording examples:

```text
LoadedApk sandbox device evidence added for the tested ordinary launch.
ActivityThread record patch evidence added for the tested ordinary launch.
Gate remains PARTIAL pending PR-6 lifecycle baseline and broader matrix evidence.
```

- [ ] **Step 2: Record next action based on PR-5 verdict**

Add one of these to the PR-5 document:

```text
Next action if PASS: PR-6 Activity lifecycle baseline using the same ordinary hosted instance path.
```

or:

```text
Next action if PARTIAL/FAIL: PR-5 follow-up focused on the failed evidence field before moving to lifecycle/PMS/AMS work.
```

- [ ] **Step 3: Final diff scope check**

Run:

```powershell
git diff --name-only
```

Expected PR-5-owned files are:

```text
core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatter.kt
core/loader/src/main/java/com/multiapp/core/loader/HostedActivityIdentity.kt
core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextInjector.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
core/loader/src/test/java/com/multiapp/core/loader/HostedActivityContextEvidenceFormatterTest.kt
core/loader/src/test/java/com/multiapp/core/loader/HostedActivityIdentityTest.kt
core/loader/src/test/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstallerTest.kt
core/loader/src/test/java/com/multiapp/core/loader/ActivityClientRecordBridgeTest.kt
docs/container-runtime-refactor/v2-pr5-loadedapk-sandbox-device-evidence-2026-06-30.md
```

Existing unrelated dirty files from the workspace must not be staged or described as PR-5 work.

- [ ] **Step 4: Final checkpoint**

Record:

```text
Final PR-5 checkpoint:
- Code review result: <summary>
- Focused tests: <PASS|FAIL>
- Full core loader tests: <PASS|FAIL>
- App assemble: <PASS|FAIL>
- Device evidence: <PASS|PARTIAL|FAIL>
- Protected default hooks unchanged: true
- Next PR: <PR-6 or PR-5 follow-up>
```

---

## Self-review

### Spec coverage

- Blueprint Phase 4 minimum fields are covered by Tasks 1, 2, 3, and 7.
- Seven-kernel gap `LoadedApk sandbox` evidence is covered by Tasks 1, 3, 6, and 7.
- Seven-kernel gap `ActivityThread launch record` evidence is covered by Tasks 1, 3, and 7.
- Device evidence requirement is covered by Task 7.
- Code review before build memory is covered by Task 5.
- Anti-overclaim rules are covered by Task 8.
- Protected runtime defaults are recorded in Task 4 and Task 8.

### Placeholder scan

This plan uses explicit file paths, test code, implementation code, commands, and expected results. Runtime values that only the device can produce are represented as `<actual value>` inside the evidence document update step and must be replaced during Task 7.

### Type consistency

- `HostedActivityContextEvidenceFormatter.format(...)` consumes `HostedActivityContextInjector.InjectionResult`.
- `HostedActivityIdentity.applicationInfoForRuntime(...)` returns `ApplicationInfo` for `LoadedApkRuntimeState` and ActivityClientRecord evidence.
- `HostedActivityIdentity.activityInfoForRecord(...)` returns `ActivityInfo` for `ActivityClientRecordRuntimeState`.
- `ActivityRecordInjectionResult` wraps `ActivityClientRecordPatchResult?` plus package identity fields for the final `InjectionResult`.
