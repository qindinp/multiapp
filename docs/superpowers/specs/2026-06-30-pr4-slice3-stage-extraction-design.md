# PR-4 Slice 3 Stage Extraction Design

Date: 2026-06-30
Owner: hosted container runtime
Status: approved design / pending implementation plan

## 1. Purpose

PR-4 Slice 3 continues the incremental `HostedRuntimeBootstrap.run()` extraction by turning the existing native-library-dir lookup, package snapshot creation, and provider routing preparation into explicit JVM-testable stages.

This slice is structural. It must preserve current runtime behavior and evidence semantics while making the middle part of bootstrap easier to reason about and test.

## 2. Scope

Create and wire these stage classes:

1. `NativeLibrariesStage`
2. `PackageSnapshotStage`
3. `ProviderRoutingStage`

Modify `HostedRuntimeBootstrap.run()` only enough to call these extracted stages and consume their explicit context outputs.

## 3. Non-goals

This slice must not:

- Change `RuntimeStage` enum values.
- Add a generic pipeline abstraction.
- Implement APK `.so` extraction.
- Scan APK native libraries.
- Select ABI from APK contents.
- Change default `PathClassLoader` behavior.
- Change provider hook default enablement.
- Implement provider method coverage.
- Extract `ClassLoaderStage`, `ApplicationStage`, `LauncherActivityStage`, or `DiagnosticsStage`.
- Claim native-library support, provider routing completion, or v2 runtime completion.

## 4. RuntimeStage mapping

Because this slice does not change `RuntimeStage`, each new stage maps to an existing enum value:

| Stage class | RuntimeStage | Rationale |
| --- | --- | --- |
| `NativeLibrariesStage` | `NATIVE_LIBS` | Existing enum directly describes native-library-dir evidence. |
| `PackageSnapshotStage` | `RESOURCES` | Current enum has no package-snapshot value; `RESOURCES` is the closest existing runtime-preparation stage for snapshot/resource metadata. |
| `ProviderRoutingStage` | `GUEST_CONTEXT` | Provider routing prepares guest runtime context before classloader/application work without adding a new provider enum. |

This mapping is intentionally temporary. Later PRs may introduce more precise enum values only if an owner explicitly expands that scope.

## 5. Stage behavior

### 5.1 NativeLibrariesStage

Input:

- `BootstrapStageInput.instance`

Dependencies:

- `nativeLibraryDirResolver: (dataRoot: String?) -> String?`, defaulting to the current `HostedRuntimeBootstrap.resolveNativeLibraryDir` behavior.

Output:

- `BootstrapStageInput.nativeLibraryDir`
- `BootstrapResult` with `RuntimeStage.NATIVE_LIBS`

Behavior:

- Missing `instance` returns terminal `FAILED` with message `Instance is required before resolving native library directory`.
- Existing `dataRoot/lib` directory returns `SUCCESS`, updates `nativeLibraryDir`, and records evidence:
  - `nativeLibraryDir`
  - `nativeLibrarySource=INSTANCE_DATA_ROOT_LIB`
  - `nativeLibrariesExtraction=DEFERRED`
- Missing `dataRoot/lib` returns non-terminal `SKIPPED`, keeps `nativeLibraryDir = null`, and records evidence:
  - `nativeLibraryDir=`
  - `nativeLibrarySource=INSTANCE_DATA_ROOT_LIB`
  - `nativeLibrariesExtraction=DEFERRED`
  - `reason=instance lib dir not present`

The stage must not inspect or mutate APK contents.

### 5.2 PackageSnapshotStage

Input:

- `BootstrapStageInput.instance`
- `BootstrapStageInput.installRecord`
- `BootstrapStageInput.originApkPath`
- `BootstrapStageInput.nativeLibraryDir`

Dependencies:

- `packageMetadataResolver: (originApkPath: String) -> ResolvedPackage?`, defaulting to current manifest/package resolver behavior.
- `packageSnapshotFactory`, using existing `VirtualPackageSnapshotFactory.create(...)`.
- `packageRegistry`, defaulting to `VirtualPackageRegistry.global`.

Output:

- `BootstrapStageInput.packageSnapshot`
- `BootstrapResult` with `RuntimeStage.RESOURCES`

Behavior:

- Missing `instance`, `installRecord`, or `originApkPath` returns terminal `FAILED` with a stable message naming the missing prerequisite.
- On success, resolve package metadata, create a `VirtualPackageSnapshot`, register it, update stage context, and record evidence:
  - `instanceId`
  - `originPackageName`
  - `virtualPackageName`
  - `sourceDir`
  - `dataDir`
  - `nativeLibraryDir`
  - `providerCount`
  - `activityCount`

The stage must preserve existing `VirtualPackageSnapshotFactory` semantics and registry side effects.

### 5.3 ProviderRoutingStage

Input:

- `BootstrapStageInput.packageSnapshot`

Dependencies:

- `hostPackageName: String?`
- `providerHookInstallEnabled: Boolean`, defaulting to current bootstrap constructor value.
- `providerHookInstaller: VirtualProviderHookInstaller`
- `routingPlanFactory: VirtualProviderRoutingPlanFactory`

Output:

- `BootstrapStageInput.providerRoutingPlan`
- `BootstrapResult` with `RuntimeStage.GUEST_CONTEXT`

Behavior:

- Missing `packageSnapshot` returns terminal `FAILED` with message `Package snapshot is required before provider routing`.
- Creates a routing plan through existing `VirtualProviderRoutingPlanFactory.create(...)`.
- If `providerHookInstallEnabled=false`, returns `VirtualProviderHookInstallResult.Skipped(plan, "PROFILE_DISABLED")`.
- If enabled, delegates to existing `providerHookInstaller.install(plan)`.
- On success, updates stage context and records evidence from:
  - `providerRoutingPlan.toEvidence()`
  - `providerHookInstallResult.toEvidence()`

The stage must not change provider hook defaults or provider dispatch behavior.

## 6. HostedRuntimeBootstrap wiring

`HostedRuntimeBootstrap.run()` should replace the current inline block:

```kotlin
val nativeLibraryDir = resolveNativeLibraryDir(instance.dataRoot)
val resolvedPackage = resolvePackageMetadata(originApkPath)
val packageSnapshot = VirtualPackageSnapshotFactory.create(...)
VirtualPackageRegistry.global.register(packageSnapshot)
val providerRoutingPlan = VirtualProviderRoutingPlanFactory().create(...)
val providerHookInstallResult = ...
```

with stage calls in this order:

1. `NativeLibrariesStage`
2. `PackageSnapshotStage`
3. `ProviderRoutingStage`

After wiring:

- `nativeLibraryDir` must come from `nativeOutput.context.nativeLibraryDir`.
- `packageSnapshot` must come from `packageSnapshotOutput.context.packageSnapshot`.
- `providerRoutingPlan` must come from `providerRoutingOutput.context.providerRoutingPlan`.
- Existing `CLASS_LOADER` evidence must continue to include provider routing and hook-install evidence.

If a new stage returns terminal failure, `run()` should return the same structured `HostedBootstrapResult` failure shape used by earlier extracted stages.

## 7. TDD plan summary

Write RED tests before production code:

### NativeLibrariesStageTest

- `execute resolves instance lib dir when dataRoot lib directory exists`
- `execute skips native library dir when instance lib dir is missing`
- `execute fails terminally when instance is missing`

### PackageSnapshotStageTest

- `execute creates and registers package snapshot from explicit stage context`
- `execute preserves nativeLibraryDir in package snapshot`
- `execute fails terminally when required context is missing`

### ProviderRoutingStageTest

- `execute creates provider routing plan and skipped hook evidence when profile disabled`
- `execute installs provider hook when profile enabled`
- `execute fails terminally when package snapshot is missing`

### HostedRuntimeBootstrapTest integration guard

Keep current tests green and add or preserve coverage proving:

- `classLoaderFactory` receives the same native library directory as before.
- `CLASS_LOADER` evidence still includes provider routing evidence.
- Provider hook disabled still records `providerHookInstallStatus=SKIPPED` and `providerHookInstallReason=PROFILE_DISABLED`.

## 8. Verification

Focused JVM command:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.NativeLibrariesStageTest" --tests "com.multiapp.core.loader.PackageSnapshotStageTest" --tests "com.multiapp.core.loader.ProviderRoutingStageTest" --tests "com.multiapp.core.loader.HostedRuntimeBootstrapTest"
```

Full loader JVM command:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Device evidence is not part of this slice.

## 9. Review gate

After focused JVM tests pass:

1. Run one PR-4 Slice 3 scoped code review.
2. Review only HIGH/CRITICAL issues for this slice.
3. If HIGH/CRITICAL findings exist, fix them and run one focused re-review.
4. Do not use review feedback to expand into ClassLoader/Application/Provider method coverage unless the owner explicitly approves a new scope.

## 10. Completion wording

Allowed wording:

```text
PR-4 Slice 3 extracted native-library-dir, package snapshot, and provider routing stage seams.
Existing behavior preserved.
JVM stage evidence added.
Device evidence pending.
PR-4 remains PARTIAL.
```

Disallowed wording:

```text
native libraries complete
native extraction complete
provider routing complete
v2 runtime complete
container runtime complete
```
