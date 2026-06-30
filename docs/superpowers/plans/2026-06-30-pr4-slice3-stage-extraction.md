# PR-4 Slice 3 Stage Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract existing native-library-dir, package snapshot, and provider routing preparation from `HostedRuntimeBootstrap.run()` into explicit stage classes with JVM evidence while preserving current behavior.

**Architecture:** Add three focused stage classes that consume and return `BootstrapStageInput` / `BootstrapStageOutput`. Keep `HostedRuntimeBootstrap.run()` as the orchestrator, do not add a generic pipeline, and keep `CLASS_LOADER` evidence compatible by carrying provider routing evidence forward. Add `resolvedPackage` to stage context only to preserve the existing manifest-resolution handoff.

**Tech Stack:** Kotlin, Android Gradle Plugin, JUnit 5 / kotlin.test JVM unit tests, MockK for existing provider hook tests.

---

## Scope guard

This plan implements only PR-4 Slice 3 from [2026-06-30-pr4-slice3-stage-extraction-design.md](../specs/2026-06-30-pr4-slice3-stage-extraction-design.md).

Do not do any of the following in this plan:

- Do not change `RuntimeStage` enum values.
- Do not introduce a generic pipeline abstraction.
- Do not implement APK `.so` extraction.
- Do not change default `PathClassLoader` behavior.
- Do not change provider hook default enablement.
- Do not extract `ClassLoaderStage`, `ApplicationStage`, `LauncherActivityStage`, or `DiagnosticsStage`.
- Do not claim native/provider/device completion.

Project git rule: do not commit unless the owner explicitly asks. Each task ends with a checkpoint instead of a commit.

## File structure

### Create

- `core/loader/src/main/java/com/multiapp/core/loader/NativeLibrariesStage.kt`
  - Resolves the already-existing `dataRoot/lib` directory and records `NATIVE_LIBS` evidence.
- `core/loader/src/main/java/com/multiapp/core/loader/PackageSnapshotStage.kt`
  - Resolves package metadata, creates/registers `VirtualPackageSnapshot`, stores `resolvedPackage` and `packageSnapshot` in stage context.
- `core/loader/src/main/java/com/multiapp/core/loader/ProviderRoutingStage.kt`
  - Creates `VirtualProviderRoutingPlan`, applies/skips provider hook install, records routing/hook evidence.
- `core/loader/src/test/java/com/multiapp/core/loader/NativeLibrariesStageTest.kt`
- `core/loader/src/test/java/com/multiapp/core/loader/PackageSnapshotStageTest.kt`
- `core/loader/src/test/java/com/multiapp/core/loader/ProviderRoutingStageTest.kt`

### Modify

- `core/loader/src/main/java/com/multiapp/core/loader/BootstrapStageContract.kt`
  - Add `resolvedPackage: ResolvedPackage? = null` to `BootstrapStageInput`.
- `core/loader/src/test/java/com/multiapp/core/loader/BootstrapStageContractTest.kt`
  - Prove stage context carries `resolvedPackage`.
- `core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt`
  - Replace inline native-dir / package snapshot / provider routing block with stage calls.
  - Preserve existing `resolvedPackage` usage for `applicationLabel` and launcher resolution.
- `core/loader/src/test/java/com/multiapp/core/loader/HostedRuntimeBootstrapTest.kt`
  - Add one integration guard for Slice 3 stage results/evidence while keeping existing tests green.

---

## Task 1: Add `resolvedPackage` to stage context

**Files:**
- Modify: `core/loader/src/test/java/com/multiapp/core/loader/BootstrapStageContractTest.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/BootstrapStageContract.kt`

- [ ] **Step 1: Write the failing context test**

Add the import and test below to `BootstrapStageContractTest.kt`.

```kotlin
import com.multiapp.core.model.virtual.ResolvedPackage
```

Add this test after `stage context carries explicit bootstrap facts between stages`:

```kotlin
    @Test
    fun `stage context carries resolved package metadata between stages`() {
        val resolvedPackage = ResolvedPackage(
            packageName = "com.example.app",
            versionCode = 2L,
            versionName = "2.0",
            targetSdk = 36,
            minSdk = 28,
            applicationLabel = "Resolved Label",
            launcherActivityName = "com.example.app.MainActivity"
        )

        val context = BootstrapStageInput(
            instanceId = "inst-001",
            resolvedPackage = resolvedPackage
        )

        assertSame(resolvedPackage, context.resolvedPackage)
        assertEquals("Resolved Label", context.resolvedPackage?.applicationLabel)
        assertEquals("com.example.app.MainActivity", context.resolvedPackage?.launcherActivityName)
    }
```

- [ ] **Step 2: Run RED test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.BootstrapStageContractTest"
```

Expected: FAIL at compile time with an unresolved `resolvedPackage` named argument or property.

- [ ] **Step 3: Add the context field**

Modify `BootstrapStageContract.kt` imports and `BootstrapStageInput`:

```kotlin
import com.multiapp.core.model.virtual.ResolvedPackage
```

Update the data class constructor to include `resolvedPackage` between `nativeLibraryDir` and `packageSnapshot`:

```kotlin
data class BootstrapStageInput(
    val instanceId: String,
    val instance: VirtualInstanceRecord? = null,
    val installRecord: InstallRecord? = null,
    val originApkPath: String? = null,
    val nativeLibraryDir: String? = null,
    val resolvedPackage: ResolvedPackage? = null,
    val packageSnapshot: VirtualPackageSnapshot? = null,
    val providerRoutingPlan: VirtualProviderRoutingPlan? = null,
    val guestClassLoader: ClassLoader? = null,
    val guestApplication: Application? = null
) {
    init {
        require(instanceId.isNotBlank()) { "instanceId must not be blank" }
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the same command from Step 2.

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checkpoint**

Do not commit unless the owner explicitly asks. Record in the final report that `resolvedPackage` was added to preserve existing manifest-resolution behavior.

---

## Task 2: Extract `NativeLibrariesStage`

**Files:**
- Create: `core/loader/src/test/java/com/multiapp/core/loader/NativeLibrariesStageTest.kt`
- Create: `core/loader/src/main/java/com/multiapp/core/loader/NativeLibrariesStage.kt`

- [ ] **Step 1: Write failing tests**

Create `NativeLibrariesStageTest.kt` with this complete content:

```kotlin
package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeLibrariesStageTest {

    @Test
    fun `execute resolves instance lib dir when dataRoot lib directory exists`(
        @TempDir tempDir: File
    ) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val libDir = File(dataRoot, "lib").apply { mkdirs() }
        val instance = instanceRecord(dataRoot = dataRoot.absolutePath)
        val stage = NativeLibrariesStage(clock = fixedClock(100L, 106L))

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(RuntimeStage.NATIVE_LIBS, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(6L, output.result.durationMs)
        assertEquals(libDir.absolutePath, output.context.nativeLibraryDir)
        assertFalse(output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(libDir.absolutePath, evidence["nativeLibraryDir"])
        assertEquals("INSTANCE_DATA_ROOT_LIB", evidence["nativeLibrarySource"])
        assertEquals("DEFERRED", evidence["nativeLibrariesExtraction"])
    }

    @Test
    fun `execute skips native library dir when instance lib dir is missing`(
        @TempDir tempDir: File
    ) {
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val instance = instanceRecord(dataRoot = dataRoot.absolutePath)
        val stage = NativeLibrariesStage(clock = fixedClock(200L, 203L))

        val output = stage.execute(
            BootstrapStageInput(instanceId = instance.instanceId, instance = instance)
        )

        assertEquals(RuntimeStage.NATIVE_LIBS, output.result.stage)
        assertEquals(BootstrapStatus.SKIPPED, output.result.status)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.nativeLibraryDir)
        assertFalse(output.isTerminalFailure)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("", evidence["nativeLibraryDir"])
        assertEquals("INSTANCE_DATA_ROOT_LIB", evidence["nativeLibrarySource"])
        assertEquals("DEFERRED", evidence["nativeLibrariesExtraction"])
        assertEquals("instance lib dir not present", evidence["reason"])
    }

    @Test
    fun `execute fails terminally when instance is missing`() {
        val stage = NativeLibrariesStage(clock = fixedClock(300L, 302L))

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.NATIVE_LIBS, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Instance is required before resolving native library directory", output.result.message)
        assertEquals(2L, output.result.durationMs)
        assertNull(output.context.nativeLibraryDir)
        assertTrue(output.isTerminalFailure)
    }

    private fun instanceRecord(
        instanceId: String = "inst-001",
        dataRoot: String = "/data/instances/inst-001"
    ) = VirtualInstanceRecord(
        instanceId = instanceId,
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = dataRoot,
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}
```

- [ ] **Step 2: Run RED test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.NativeLibrariesStageTest"
```

Expected: FAIL at compile time with unresolved `NativeLibrariesStage`.

- [ ] **Step 3: Implement minimal stage**

Create `NativeLibrariesStage.kt`:

```kotlin
package com.multiapp.core.loader

import java.io.File

class NativeLibrariesStage(
    private val nativeLibraryDirResolver: (String?) -> String? = ::resolveInstanceLibDir,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val instance = input.instance
        if (instance == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.NATIVE_LIBS,
                    message = "Instance is required before resolving native library directory",
                    durationMs = clock() - startMs
                )
            )
        }

        val nativeLibraryDir = nativeLibraryDirResolver(instance.dataRoot)
        val durationMs = clock() - startMs
        if (nativeLibraryDir.isNullOrBlank()) {
            return BootstrapStageOutput(
                context = input.copy(nativeLibraryDir = null),
                result = BootstrapResult.skipped(
                    stage = RuntimeStage.NATIVE_LIBS,
                    message = "Instance native library directory not present",
                    evidence = nativeEvidence(
                        nativeLibraryDir = "",
                        reason = "instance lib dir not present"
                    )
                ).copy(durationMs = durationMs)
            )
        }

        return BootstrapStageOutput(
            context = input.copy(nativeLibraryDir = nativeLibraryDir),
            result = BootstrapResult.success(
                stage = RuntimeStage.NATIVE_LIBS,
                message = "Native library directory resolved: $nativeLibraryDir",
                evidence = nativeEvidence(nativeLibraryDir = nativeLibraryDir),
                durationMs = durationMs
            )
        )
    }

    private fun nativeEvidence(
        nativeLibraryDir: String,
        reason: String? = null
    ): List<BootstrapEvidence> {
        val evidence = listOf(
            BootstrapEvidence("nativeLibraryDir", nativeLibraryDir),
            BootstrapEvidence("nativeLibrarySource", "INSTANCE_DATA_ROOT_LIB"),
            BootstrapEvidence("nativeLibrariesExtraction", "DEFERRED")
        )
        return if (reason == null) {
            evidence
        } else {
            evidence + BootstrapEvidence("reason", reason)
        }
    }

    companion object {
        private fun resolveInstanceLibDir(dataRoot: String?): String? {
            if (dataRoot.isNullOrBlank()) return null
            val libDir = File(dataRoot, "lib")
            return libDir.takeIf { it.isDirectory }?.absolutePath
        }
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the same command from Step 2.

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checkpoint**

Do not commit. Record that this stage did not inspect APK contents or extract native libraries.

---

## Task 3: Extract `PackageSnapshotStage`

**Files:**
- Create: `core/loader/src/test/java/com/multiapp/core/loader/PackageSnapshotStageTest.kt`
- Create: `core/loader/src/main/java/com/multiapp/core/loader/PackageSnapshotStage.kt`

- [ ] **Step 1: Write failing tests**

Create `PackageSnapshotStageTest.kt`:

```kotlin
package com.multiapp.core.loader

import com.multiapp.core.model.instance.CompatibilityMode
import com.multiapp.core.model.instance.InstanceState
import com.multiapp.core.model.instance.VirtualInstanceRecord
import com.multiapp.core.model.installer.InstallRecord
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.ResolvedPackage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackageSnapshotStageTest {

    @Test
    fun `execute creates and registers package snapshot from explicit stage context`() {
        val registry = VirtualPackageRegistry()
        val instance = instanceRecord()
        val installRecord = installRecord()
        val resolvedPackage = resolvedPackage()
        val stage = PackageSnapshotStage(
            packageMetadataResolver = { path ->
                assertEquals(installRecord.originApkPath, path)
                resolvedPackage
            },
            packageRegistry = registry,
            clock = fixedClock(100L, 111L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                installRecord = installRecord,
                originApkPath = installRecord.originApkPath,
                nativeLibraryDir = "/data/instances/inst-001/lib"
            )
        )

        assertEquals(RuntimeStage.RESOURCES, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(11L, output.result.durationMs)
        val snapshot = assertNotNull(output.context.packageSnapshot)
        assertSame(resolvedPackage, output.context.resolvedPackage)
        assertSame(snapshot, registry.getByInstanceId(instance.instanceId))
        assertEquals(instance.originPackageName, snapshot.originPackageName)
        assertEquals(instance.virtualPackageName, snapshot.virtualPackageName)
        assertEquals("Resolved Label", snapshot.applicationLabel)
        assertEquals("/data/instances/inst-001/lib", snapshot.nativeLibraryDir)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals(instance.instanceId, evidence["instanceId"])
        assertEquals(instance.originPackageName, evidence["originPackageName"])
        assertEquals(instance.virtualPackageName, evidence["virtualPackageName"])
        assertEquals(installRecord.originApkPath, evidence["sourceDir"])
        assertEquals(instance.dataRoot, evidence["dataDir"])
        assertEquals("/data/instances/inst-001/lib", evidence["nativeLibraryDir"])
        assertEquals("1", evidence["providerCount"])
        assertEquals("1", evidence["activityCount"])
    }

    @Test
    fun `execute preserves null nativeLibraryDir when no native dir was resolved`() {
        val registry = VirtualPackageRegistry()
        val instance = instanceRecord()
        val installRecord = installRecord()
        val stage = PackageSnapshotStage(
            packageMetadataResolver = { null },
            packageRegistry = registry,
            clock = fixedClock(200L, 204L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                installRecord = installRecord,
                originApkPath = installRecord.originApkPath,
                nativeLibraryDir = null
            )
        )

        val snapshot = assertNotNull(output.context.packageSnapshot)
        assertNull(output.context.resolvedPackage)
        assertNull(snapshot.nativeLibraryDir)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
    }

    @Test
    fun `execute fails terminally when install record is missing`() {
        val instance = instanceRecord()
        val stage = PackageSnapshotStage(
            packageMetadataResolver = { null },
            packageRegistry = VirtualPackageRegistry(),
            clock = fixedClock(300L, 303L)
        )

        val output = stage.execute(
            BootstrapStageInput(
                instanceId = instance.instanceId,
                instance = instance,
                originApkPath = "/artifact/app.apk"
            )
        )

        assertEquals(RuntimeStage.RESOURCES, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Install record is required before package snapshot", output.result.message)
        assertEquals(3L, output.result.durationMs)
        assertNull(output.context.packageSnapshot)
        assertTrue(output.isTerminalFailure)
    }

    private fun instanceRecord() = VirtualInstanceRecord(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        displayName = "Example App",
        dataRoot = "/data/instances/inst-001",
        compatibilityMode = CompatibilityMode.DEFAULT,
        createdAtMs = 1000L,
        updatedAtMs = 1000L,
        state = InstanceState.READY
    )

    private fun installRecord() = InstallRecord(
        packageName = "com.example.app",
        originApkPath = "/artifact/com.example.app.apk",
        originApkSha256 = "sha256",
        originCertSha256 = "cert-sha256",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 35,
        minSdk = 28,
        installTimeMs = 500L
    )

    private fun resolvedPackage() = ResolvedPackage(
        packageName = "com.example.app",
        versionCode = 2L,
        versionName = "2.0",
        targetSdk = 36,
        minSdk = 28,
        applicationLabel = "Resolved Label",
        launcherActivityName = "com.example.app.MainActivity",
        activities = listOf(ResolvedComponent(name = "com.example.app.MainActivity", exported = true)),
        providers = listOf(
            ResolvedComponent(
                name = "com.example.app.Provider",
                exported = false,
                authorities = listOf("com.example.app.provider")
            )
        )
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}
```

- [ ] **Step 2: Run RED test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.PackageSnapshotStageTest"
```

Expected: FAIL at compile time with unresolved `PackageSnapshotStage`.

- [ ] **Step 3: Implement minimal stage**

Create `PackageSnapshotStage.kt`:

```kotlin
package com.multiapp.core.loader

import com.multiapp.core.model.virtual.ResolvedPackage

class PackageSnapshotStage(
    private val packageMetadataResolver: (String) -> ResolvedPackage?,
    private val packageRegistry: VirtualPackageRegistry = VirtualPackageRegistry.global,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val instance = input.instance ?: return failed(
            input = input,
            startMs = startMs,
            message = "Instance is required before package snapshot"
        )
        val installRecord = input.installRecord ?: return failed(
            input = input,
            startMs = startMs,
            message = "Install record is required before package snapshot"
        )
        val originApkPath = input.originApkPath ?: return failed(
            input = input,
            startMs = startMs,
            message = "Origin APK path is required before package snapshot"
        )

        val resolvedPackage = runCatching {
            packageMetadataResolver(originApkPath)
        }.getOrNull()
        val snapshot = VirtualPackageSnapshotFactory.create(
            instance = instance,
            installRecord = installRecord,
            resolvedPackage = resolvedPackage,
            nativeLibraryDir = input.nativeLibraryDir
        )
        val registeredSnapshot = packageRegistry.register(snapshot)
        val durationMs = clock() - startMs

        return BootstrapStageOutput(
            context = input.copy(
                resolvedPackage = resolvedPackage,
                packageSnapshot = registeredSnapshot
            ),
            result = BootstrapResult.success(
                stage = RuntimeStage.RESOURCES,
                message = "Package snapshot registered: ${registeredSnapshot.virtualPackageName}",
                evidence = snapshotEvidence(registeredSnapshot),
                durationMs = durationMs
            )
        )
    }

    private fun failed(
        input: BootstrapStageInput,
        startMs: Long,
        message: String
    ): BootstrapStageOutput = BootstrapStageOutput(
        context = input,
        result = BootstrapResult.failed(
            stage = RuntimeStage.RESOURCES,
            message = message,
            durationMs = clock() - startMs
        )
    )

    private fun snapshotEvidence(snapshot: com.multiapp.core.model.virtual.VirtualPackageSnapshot): List<BootstrapEvidence> =
        listOf(
            BootstrapEvidence("instanceId", snapshot.instanceId),
            BootstrapEvidence("originPackageName", snapshot.originPackageName),
            BootstrapEvidence("virtualPackageName", snapshot.virtualPackageName),
            BootstrapEvidence("sourceDir", snapshot.sourceDir),
            BootstrapEvidence("dataDir", snapshot.dataDir),
            BootstrapEvidence("nativeLibraryDir", snapshot.nativeLibraryDir.orEmpty()),
            BootstrapEvidence("providerCount", snapshot.providers.size.toString()),
            BootstrapEvidence("activityCount", snapshot.activities.size.toString())
        )
}
```

- [ ] **Step 4: Verify GREEN**

Run the same command from Step 2.

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checkpoint**

Do not commit. Record that package snapshot creation and registry registration remain behavior-compatible.

---

## Task 4: Extract `ProviderRoutingStage`

**Files:**
- Create: `core/loader/src/test/java/com/multiapp/core/loader/ProviderRoutingStageTest.kt`
- Create: `core/loader/src/main/java/com/multiapp/core/loader/ProviderRoutingStage.kt`

- [ ] **Step 1: Write failing tests**

Create `ProviderRoutingStageTest.kt`:

```kotlin
package com.multiapp.core.loader

import com.multiapp.core.hook.HookEngine
import com.multiapp.core.model.virtual.ResolvedComponent
import com.multiapp.core.model.virtual.VirtualPackageSnapshot
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRoutingStageTest {

    @Test
    fun `execute creates provider routing plan and skipped hook evidence when profile disabled`() {
        val snapshot = snapshotWithProvider()
        val stage = ProviderRoutingStage(
            hostPackageName = "com.multiapp.app",
            providerHookInstallEnabled = false,
            providerHookInstaller = VirtualProviderHookInstaller(),
            clock = fixedClock(100L, 108L)
        )

        val output = stage.execute(
            BootstrapStageInput(instanceId = snapshot.instanceId, packageSnapshot = snapshot)
        )

        assertEquals(RuntimeStage.GUEST_CONTEXT, output.result.stage)
        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        assertEquals(8L, output.result.durationMs)
        val plan = assertNotNull(output.context.providerRoutingPlan)
        assertTrue(plan.enabled)
        assertEquals("AUTHORITY_MAP_READY", plan.reason)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("true", evidence["providerRoutingEnabled"])
        assertEquals("AUTHORITY_MAP_READY", evidence["providerRoutingReason"])
        assertEquals("SKIPPED", evidence["providerHookInstallStatus"])
        assertEquals("PROFILE_DISABLED", evidence["providerHookInstallReason"])
    }

    @Test
    fun `execute installs provider hook when profile enabled`() {
        val snapshot = snapshotWithProvider()
        val hookEngine = mockk<HookEngine>(relaxed = true)
        val stage = ProviderRoutingStage(
            hostPackageName = "com.multiapp.app",
            providerHookInstallEnabled = true,
            providerHookInstaller = VirtualProviderHookInstaller(hookEngineProvider = { hookEngine }),
            clock = fixedClock(200L, 209L)
        )

        val output = stage.execute(
            BootstrapStageInput(instanceId = snapshot.instanceId, packageSnapshot = snapshot)
        )

        assertEquals(BootstrapStatus.SUCCESS, output.result.status)
        val evidence = output.result.evidence.associate { it.key to it.value }
        assertEquals("INSTALLED", evidence["providerHookInstallStatus"])
        assertEquals("1", evidence["providerHookInstallAuthorityMapSize"])
        verify(atLeast = 1) {
            hookEngine.hookMethodPassThrough(any(), any(), any())
        }
    }

    @Test
    fun `execute fails terminally when package snapshot is missing`() {
        val stage = ProviderRoutingStage(
            hostPackageName = "com.multiapp.app",
            providerHookInstallEnabled = false,
            providerHookInstaller = VirtualProviderHookInstaller(),
            clock = fixedClock(300L, 304L)
        )

        val output = stage.execute(BootstrapStageInput(instanceId = "inst-001"))

        assertEquals(RuntimeStage.GUEST_CONTEXT, output.result.stage)
        assertEquals(BootstrapStatus.FAILED, output.result.status)
        assertEquals("Package snapshot is required before provider routing", output.result.message)
        assertEquals(4L, output.result.durationMs)
        assertNull(output.context.providerRoutingPlan)
        assertTrue(output.isTerminalFailure)
    }

    private fun snapshotWithProvider() = VirtualPackageSnapshot(
        instanceId = "inst-001",
        originPackageName = "com.example.app",
        virtualPackageName = "com.multiapp.instance.abc123",
        applicationLabel = "Example App",
        versionCode = 1L,
        versionName = "1.0",
        targetSdk = 36,
        minSdk = 28,
        sourceDir = "/artifact/com.example.app.apk",
        dataDir = "/data/instances/inst-001",
        providers = listOf(
            ResolvedComponent(
                name = "com.example.app.Provider",
                exported = false,
                authorities = listOf("com.example.app.provider")
            )
        )
    )

    private fun fixedClock(vararg values: Long): () -> Long {
        var index = 0
        return {
            values.getOrElse(index++) { values.last() }
        }
    }
}
```

- [ ] **Step 2: Run RED test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.ProviderRoutingStageTest"
```

Expected: FAIL at compile time with unresolved `ProviderRoutingStage`.

- [ ] **Step 3: Implement minimal stage**

Create `ProviderRoutingStage.kt`:

```kotlin
package com.multiapp.core.loader

class ProviderRoutingStage(
    private val hostPackageName: String?,
    private val providerHookInstallEnabled: Boolean,
    private val providerHookInstaller: VirtualProviderHookInstaller,
    private val routingPlanFactory: VirtualProviderRoutingPlanFactory = VirtualProviderRoutingPlanFactory(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun execute(input: BootstrapStageInput): BootstrapStageOutput {
        val startMs = clock()
        val packageSnapshot = input.packageSnapshot
        if (packageSnapshot == null) {
            return BootstrapStageOutput(
                context = input,
                result = BootstrapResult.failed(
                    stage = RuntimeStage.GUEST_CONTEXT,
                    message = "Package snapshot is required before provider routing",
                    durationMs = clock() - startMs
                )
            )
        }

        val providerRoutingPlan = routingPlanFactory.create(
            snapshot = packageSnapshot,
            hostPackageName = hostPackageName
        )
        val providerHookInstallResult = if (providerHookInstallEnabled) {
            providerHookInstaller.install(providerRoutingPlan)
        } else {
            VirtualProviderHookInstallResult.Skipped(providerRoutingPlan, "PROFILE_DISABLED")
        }
        val durationMs = clock() - startMs

        return BootstrapStageOutput(
            context = input.copy(providerRoutingPlan = providerRoutingPlan),
            result = BootstrapResult.success(
                stage = RuntimeStage.GUEST_CONTEXT,
                message = "Provider routing prepared: ${providerRoutingPlan.reason}",
                evidence = providerRoutingPlan.toEvidence() + providerHookInstallResult.toEvidence(),
                durationMs = durationMs
            )
        )
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the same command from Step 2.

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checkpoint**

Do not commit. Record that provider hook default behavior remains disabled unless the existing constructor flag enables it.

---

## Task 5: Wire Slice 3 stages into `HostedRuntimeBootstrap.run()`

**Files:**
- Modify: `core/loader/src/test/java/com/multiapp/core/loader/HostedRuntimeBootstrapTest.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt`

- [ ] **Step 1: Add integration guard test**

Add this test to `HostedRuntimeBootstrapTest.kt` near the provider routing tests:

```kotlin
    @Test
    fun `run records slice 3 stage results before classloader evidence`(
        @TempDir tempDir: File
    ) {
        val apkFile = File(tempDir, "example.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        val dataRoot = File(tempDir, "instance-data").apply { mkdirs() }
        val libDir = File(dataRoot, "lib").apply { mkdirs() }
        val mockContext: Context = mockk(relaxed = true)
        every { mockContext.packageName } returns "com.multiapp.app"
        val packageResolver = object : VirtualPackageResolver {
            override fun resolve(apkPath: String): ResolvedPackage? {
                assertEquals(apkFile.absolutePath, apkPath)
                return ResolvedPackage(
                    packageName = "com.example.app",
                    versionCode = 1L,
                    versionName = "1.0",
                    targetSdk = 35,
                    minSdk = 28,
                    providers = listOf(
                        ResolvedComponent(
                            name = "com.example.app.ProbeProvider",
                            exported = false,
                            authorities = listOf("com.example.app.probe")
                        )
                    )
                )
            }
        }
        val bootstrap = HostedRuntimeBootstrap(
            instanceManager = FakeInstanceManager(
                mapOf("inst-001" to instanceRecord().copy(dataRoot = dataRoot.absolutePath))
            ),
            installRecordStore = FakeInstallRecordStore(
                mapOf("com.example.app" to installRecord(originApkPath = apkFile.absolutePath))
            ),
            hostContext = mockContext,
            classLoaderFactory = { _, _ -> ClassLoader.getSystemClassLoader() },
            applicationClassNameResolver = { _, _ -> null },
            packageResolver = packageResolver
        )

        val result = bootstrap.run("inst-001")

        assertTrue(result.success)
        val nativeStage = result.stageResults.first { it.stage == RuntimeStage.NATIVE_LIBS }
        assertEquals(BootstrapStatus.SUCCESS, nativeStage.status)
        assertEquals(libDir.absolutePath, nativeStage.evidence.find { it.key == "nativeLibraryDir" }?.value)
        val snapshotStage = result.stageResults.first { it.stage == RuntimeStage.RESOURCES }
        assertEquals(BootstrapStatus.SUCCESS, snapshotStage.status)
        assertEquals("1", snapshotStage.evidence.find { it.key == "providerCount" }?.value)
        val providerStage = result.stageResults.first { it.stage == RuntimeStage.GUEST_CONTEXT }
        assertEquals(BootstrapStatus.SUCCESS, providerStage.status)
        assertEquals("SKIPPED", providerStage.evidence.find { it.key == "providerHookInstallStatus" }?.value)
        assertEquals("PROFILE_DISABLED", providerStage.evidence.find { it.key == "providerHookInstallReason" }?.value)
        val classLoaderStage = result.stageResults.first { it.stage == RuntimeStage.CLASS_LOADER }
        assertEquals(libDir.absolutePath, classLoaderStage.evidence.find { it.key == "nativeLibraryDir" }?.value)
        assertEquals("true", classLoaderStage.evidence.find { it.key == "providerRoutingEnabled" }?.value)
    }
```

- [ ] **Step 2: Run RED integration test**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedRuntimeBootstrapTest"
```

Expected: FAIL because `NATIVE_LIBS`, `RESOURCES`, and `GUEST_CONTEXT` stage results are not yet emitted by `run()` for Slice 3.

- [ ] **Step 3: Replace inline block in `HostedRuntimeBootstrap.run()`**

In `HostedRuntimeBootstrap.kt`, replace the inline block currently starting with:

```kotlin
val nativeLibraryDir = resolveNativeLibraryDir(instance.dataRoot)
val resolvedPackage = resolvePackageMetadata(originApkPath)
val packageSnapshot = VirtualPackageSnapshotFactory.create(
```

and ending after `providerHookInstallResult` assignment with this staged block:

```kotlin
        val nativeLibrariesOutput = NativeLibrariesStage(
            nativeLibraryDirResolver = { dataRoot -> resolveNativeLibraryDir(dataRoot) },
            clock = clock
        ).execute(originApkOutput.context)
        stageResults.add(nativeLibrariesOutput.result)
        if (nativeLibrariesOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val nativeLibraryDir = nativeLibrariesOutput.context.nativeLibraryDir

        val packageSnapshotOutput = PackageSnapshotStage(
            packageMetadataResolver = { apkPath -> resolvePackageMetadata(apkPath) },
            packageRegistry = VirtualPackageRegistry.global,
            clock = clock
        ).execute(nativeLibrariesOutput.context)
        stageResults.add(packageSnapshotOutput.result)
        if (packageSnapshotOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val resolvedPackage = packageSnapshotOutput.context.resolvedPackage
        val packageSnapshot = requireNotNull(packageSnapshotOutput.context.packageSnapshot) {
            "Package snapshot stage must provide package snapshot after success"
        }

        val providerRoutingOutput = ProviderRoutingStage(
            hostPackageName = hostContext?.packageName,
            providerHookInstallEnabled = providerHookInstallEnabled,
            providerHookInstaller = providerHookInstaller,
            clock = clock
        ).execute(packageSnapshotOutput.context)
        stageResults.add(providerRoutingOutput.result)
        if (providerRoutingOutput.isTerminalFailure) {
            return failedHostedResult(
                instanceId, stageResults,
                originPackageName = instance.originPackageName,
                originApkPath = originApkPath,
                installId = installRecord.packageName
            )
        }
        val providerRoutingPlan = requireNotNull(providerRoutingOutput.context.providerRoutingPlan) {
            "Provider routing stage must provide routing plan after success"
        }
        val providerRoutingEvidence = providerRoutingOutput.result.evidence
```

Then update the `CLASS_LOADER` success evidence from:

```kotlin
                evidence = listOf(
                    BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name),
                    BootstrapEvidence("nativeLibraryDir", nativeLibraryDir ?: "")
                ) + providerRoutingPlan.toEvidence() + providerHookInstallResult.toEvidence(),
```

to:

```kotlin
                evidence = listOf(
                    BootstrapEvidence("classLoaderClass", guestClassLoader.javaClass.name),
                    BootstrapEvidence("nativeLibraryDir", nativeLibraryDir ?: "")
                ) + providerRoutingEvidence,
```

Keep `providerRoutingPlan` because the local variable proves the stage supplied a plan and may be useful for future code; do not re-create the plan inline.

- [ ] **Step 4: Verify integration GREEN**

Run the same command from Step 2.

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checkpoint**

Do not commit. Record that `HostedRuntimeBootstrap.run()` now emits `NATIVE_LIBS`, `RESOURCES`, and `GUEST_CONTEXT` results before `CLASS_LOADER`.

---

## Task 6: Focused verification and scoped review

**Files:**
- No planned code edits unless tests or review expose issues.

- [ ] **Step 1: Run focused Slice 3 JVM tests**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.NativeLibrariesStageTest" --tests "com.multiapp.core.loader.PackageSnapshotStageTest" --tests "com.multiapp.core.loader.ProviderRoutingStageTest" --tests "com.multiapp.core.loader.HostedRuntimeBootstrapTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Request scoped code review**

Dispatch code-reviewer and kotlin-reviewer with this exact scope:

```text
Review PR-4 Slice 3 only. Owner-approved scope: NativeLibrariesStage resolves existing dataRoot/lib only; PackageSnapshotStage creates/registers VirtualPackageSnapshot; ProviderRoutingStage creates provider routing plan and preserves provider hook defaults. Do not request APK native extraction, RuntimeStage enum changes, generic pipeline abstraction, ClassLoader/Application/Launcher/Diagnostics extraction, or Provider method coverage. Look only for HIGH/CRITICAL correctness issues, behavior changes, failure metadata regressions, and scope boundary violations.
```

Expected: no HIGH/CRITICAL findings. If HIGH/CRITICAL findings exist, fix them and do exactly one focused re-review.

- [ ] **Step 3: Run full loader JVM verification**

Run:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Final report wording**

Use this wording in the final report:

```text
PR-4 Slice 3 extracted native-library-dir, package snapshot, and provider routing stage seams.
Existing behavior preserved.
Focused and full loader JVM tests passed.
Device evidence pending.
PR-4 remains PARTIAL.
```

Do not use these phrases:

```text
native libraries complete
native extraction complete
provider routing complete
v2 runtime complete
container runtime complete
```

- [ ] **Step 5: Checkpoint**

Do not commit unless the owner explicitly asks.

---

## Self-review checklist

- Spec coverage: Covered `NativeLibrariesStage`, `PackageSnapshotStage`, `ProviderRoutingStage`, HostedRuntimeBootstrap wiring, tests, review, and verification.
- Placeholder scan: No TBD/TODO/fill-in placeholders are present.
- Type consistency: `BootstrapStageInput.resolvedPackage`, `packageSnapshot`, `providerRoutingPlan`, and `nativeLibraryDir` are named consistently across tasks.
- Scope check: No APK native extraction, enum change, generic pipeline, provider method coverage, or downstream stage extraction is included.
