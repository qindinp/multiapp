# PR-4 RuntimeBootstrap Stage Pipeline Plan

Date: 2026-06-29
Owner: hosted container runtime
Review model: Xiaomi-style role split + owner gate
Status: Slice 1 complete; further stage extraction pending

## 0. Purpose

PR-4 turns `HostedRuntimeBootstrap.run()` from a large linear function into a testable stage pipeline.

This PR does **not** try to prove QQ Reader/device success. It creates the structure that makes later device evidence easier to collect and interpret.

## 1. Owner decision

```text
Do not expand PR-3.
Do not jump directly to LoadedApk/device work.
Do not rewrite HostedRuntimeBootstrap in one large edit.
First define stage contracts, then add stage-level JVM tests, then extract stages incrementally.
```

## 1.1 Slice 1 completion note

Completed in this slice:

```text
- Added BootstrapStageInput / BootstrapStageOutput contract types.
- Added deterministic JVM tests for explicit stage context fields.
- Added deterministic JVM tests for terminal failure semantics.
- Kept HostedRuntimeBootstrap.run() behavior unchanged.
```

Verification:

```bash
./gradlew --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Observed result:

```text
BUILD SUCCESSFUL in 49s
102 actionable tasks: 1 executed, 101 up-to-date
```

Device/androidTest evidence remains pending and is not part of Slice 1.

## 2. Xiaomi-style role split

| Role | Responsibility | Gate |
| --- | --- | --- |
| Runtime owner | Keep the PR focused on stage pipeline structure and anti-overclaim wording. | This plan and final PR wording. |
| Bootstrap owner | Extract stage contracts and move logic out of `HostedRuntimeBootstrap.run()` incrementally. | Stage tests pass and existing bootstrap tests remain green. |
| Evidence owner | Ensure every stage exposes input/output/failure reason as deterministic evidence. | JVM stage-result assertions. |
| Diagnostics owner | Preserve diagnostics behavior while making failure stage explicit. | Existing diagnostics tests pass. |
| Security owner | Only join if PR-4 changes file/path/packageName/native-library extraction trust boundaries. | No default security review for pure structure/tests. |

## 3. Current problem

`HostedRuntimeBootstrap.run()` currently owns many responsibilities at once:

```text
load instance
load install record
resolve origin APK
extract native libraries
create package snapshot
install provider routing hooks
create classloader
create/attach/onCreate application
resolve launcher activity
build diagnostics
build final result
```

This makes failures hard to isolate. It also encourages broad edits because each new runtime concern tends to be added inside the same function.

## 4. Target stage list

The target stage names align with the blueprint but keep implementation small enough for incremental extraction:

```text
ConfigStage
InstallRecordStage
OriginApkStage
NativeLibrariesStage
PackageSnapshotStage
ProviderRoutingStage
ClassLoaderStage
ApplicationStage
LauncherActivityStage
DiagnosticsStage
```

Notes:

```text
ResourcesStage is deferred unless a concrete resource-loading seam is already present during extraction.
ProviderRoutingStage stays after PackageSnapshotStage because it needs the snapshot.
DiagnosticsStage should remain terminal aggregation, not a side-effect-heavy runtime stage.
```

## 5. Stage contract

Each stage should have a small contract:

```kotlin
data class BootstrapStageInput(
    val instanceId: String,
    val instance: VirtualInstanceRecord? = null,
    val installRecord: InstallRecord? = null,
    val originApkPath: String? = null,
    val nativeLibraryDir: String? = null,
    val packageSnapshot: VirtualPackageSnapshot? = null,
    val providerRoutingPlan: VirtualProviderRoutingPlan? = null,
    val guestClassLoader: ClassLoader? = null,
    val guestApplication: Application? = null
)

data class BootstrapStageOutput(
    val input: BootstrapStageInput,
    val result: BootstrapResult,
    val isTerminalFailure: Boolean = false
)
```

The exact type names can be adjusted during implementation, but the rules should hold:

```text
- No stage silently swallows terminal failure.
- Every failure records RuntimeStage, status, message, optional throwable, and evidence.
- Later stages read only explicit fields from the stage context.
- A stage either returns updated context or a terminal failure.
```

## 6. Extraction sequence

### Slice 1: Read-only contract and adapters

Files:

```text
core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt
core/loader/src/test/java/com/multiapp/core/loader/HostedRuntimeBootstrapTest.kt
```

Steps:

```text
1. Add internal stage context/result types near HostedRuntimeBootstrap or in a focused new file.
2. Do not change behavior yet.
3. Add JVM tests for context/result terminal failure semantics.
4. Run :core:loader:testDebugUnitTest.
```

### Slice 2: Extract ConfigStage + InstallRecordStage + OriginApkStage

These stages are lowest risk and already covered by existing tests.

Expected tests:

```text
- missing instance returns CONFIG failure
- missing InstallRecord returns PACKAGE_METADATA failure
- missing origin APK returns ORIGIN_APK failure
- valid input reaches origin APK success with evidence
```

### Slice 3: Extract NativeLibrariesStage + PackageSnapshotStage + ProviderRoutingStage

Keep native-library extraction behavior unchanged.

Expected tests:

```text
- no native libraries produces NATIVE_LIBS skipped evidence
- matching native library ABI produces extraction evidence
- package snapshot is registered with origin package and virtual package
- provider routing evidence remains present in CLASS_LOADER or dedicated ProviderRoutingStage output
```

### Slice 4: Extract ClassLoaderStage

Expected tests:

```text
- classLoaderFactory receives InstallRecord.originApkPath
- classLoaderFactory receives resolved nativeLibraryDir
- classLoaderFactory exception creates terminal CLASS_LOADER failure
```

### Slice 5: Extract ApplicationStage + LauncherActivityStage

Expected tests:

```text
- null application class skips APPLICATION stage
- hostContext missing fails ApplicationStage only when app class exists
- attachBaseContext + onCreate still run for fake Application
- launcher resolver still prefers VirtualPackageResolver over InstallRecord fallback
```

### Slice 6: Extract DiagnosticsStage / final result assembly

Expected tests:

```text
- result still includes diagnostics when instance not found
- diagnostics evidence still includes classloader/application/origin_apk_path where applicable
- final summary overallStatus remains compatible with existing tests
```

## 7. Verification commands

Primary PR-4 JVM command:

```bash
./gradlew --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

If any model contract changes are made:

```bash
./gradlew --no-parallel :core:model:testDebugUnitTest :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Device commands are **not** part of PR-4 unless a later owner decision expands scope.

## 8. Review frequency rule

```text
Do not review after every small test extraction.
Use one scoped code review after the stage pipeline compiles and focused JVM tests pass.
Add security review only if PR-4 changes packageName, originApkPath, artifact, native-library file extraction, provider URI, or storage path trust boundaries.
If review finds HIGH/CRITICAL, fix and do one focused re-review.
```

## 9. Done / not done wording

Allowed wording:

```text
RuntimeBootstrap stage pipeline introduced.
Stage-level JVM evidence added.
Failure stage and evidence are easier to diagnose.
Device evidence pending.
```

Disallowed wording:

```text
LoadedApk sandbox complete
Activity lifecycle fixed
QQ Reader fixed
container runtime complete
protected apps compatible
```

## 10. Owner verdict

```text
PR-4 can start after PR-3 JVM evidence is documented.
The first implementation slice should be stage contract + low-risk Config/InstallRecord/OriginApk extraction.
The owner should stop the PR if it begins to absorb PR-5 LoadedApk or PR-6 Activity lifecycle work.
```
