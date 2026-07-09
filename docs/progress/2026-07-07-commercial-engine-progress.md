# MultiApp Commercial Engine Progress - 2026-07-07

## Scope

This document records the current progress against `D:\Downloads\PLAN.md` and
`docs/reviews/2026-07-07-container-runtime-bug-audit.md`.

Baseline saved before this implementation:

- `38746fc chore: save container runtime baseline`

Current decision:

- The project is moving to a VirtualApp/DroidPlugin-style engine structure.
- This round is a P0 structural/runtime-safety baseline, not a claim that QQ,
  WeChat, QQ Reader, or other protected apps are commercially compatible.
- Device evidence is still required before any runtime compatibility item can be
  marked `PASS`.

## Team Review

- System architecture: keep dependency direction as
  `feature -> app DI -> core:engine -> core:loader/core:instance/core:hook/core:model`.
  Do not introduce `core:instance -> core:engine` or `core:engine -> app`.
- Framework/runtime: provider routing must not trust forged
  `multiapp_instanceId` or `multiapp_guestAuthority` in guest-supplied URIs.
- Native runtime: native private-path redirect must be scoped by
  `processSlot + instanceId`, with path traversal and canonical containment
  checks.
- Owner review: current changes are acceptable as a first P0 baseline, but
  P1/P2 commercial runtime work remains open.

## Implemented

### `:core:engine`

- Added Gradle module `:core:engine`.
- Added public engine contract in `:core:model`:
  - `VirtualizationEngine`
  - `LaunchInstanceRequest`
  - `VirtualInstanceRuntime`
  - `EngineProfile`
  - `EngineResult`
  - `EngineEvidenceReport`
- Added `DefaultVirtualizationEngine` as the first engine coordinator.
- Added `EngineRuntimeRegistry` for in-process runtime/evidence state.
- Added `CompatibilityProfilePolicy`:
  - `BASELINE` enabled without LSPlant/Xposed/proc spoof/signature fake/business
    wrappers/no-op patches.
  - `COMPAT_HOOK` requires explicit allow-list.
  - `DIAGNOSTICS_ONLY` is observe-only.
  - `EXPERIMENTAL_COMPAT` is disabled by default.
- Connected app Hilt DI to provide `VirtualizationEngine`.
- Updated launcher/app-manager feature launch flows to call
  `VirtualizationEngine.launchInstance(...)`.

### Provider Route Token

- Added process-local route token registry.
- Hook-side provider URI rewrite now issues and attaches
  `multiapp_routeToken`.
- Stub provider validates token before binding/dispatching to guest provider.
- Token validation binds:
  - caller instance
  - target instance
  - guest authority
  - operation
  - expiry
- Direct forged stub URI without a valid token is rejected.
- Existing guest query is preserved while proxy-only route parameters are
  stripped before dispatching to guest.

### Native Redirect Safety

- Native redirect rules are now scoped by `processSlot + instanceId + dataRoot`
  instead of only guest package name.
- Java and native redirect layers reject `..` path traversal input.
- Existing redirected targets are canonicalized and checked to remain under
  `dataRoot`.
- `O_CREAT` paths validate the canonical parent directory before allowing the
  operation.
- Tests cover same-package multi-instance redirect scoping and unsafe path
  rejection.

### Guardrail Fixes

- `ApplicationInfoNativePathCompat` keeps Android-style `/data/...` path strings
  in unit tests instead of turning them into Windows paths.
- Runtime diagnostics logging avoids unmocked `android.util.Log` crashes in unit
  tests.
- Instance manager logging uses safe wrappers for local JVM tests.
- Provider authority map generation keeps stable
  `provider.clone<instanceId>` / `fileprovider.clone<instanceId>` aliases.

### Hosted Runtime Binding

- Added `HostedRuntimeEngine` in `:core:engine`.
- `ContainerActivity` now obtains hosted runtime bootstrap through the engine
  using an app-side Hilt EntryPoint, instead of constructing
  `JsonInstanceRecordStore`, `JsonInstallRecordStore`,
  `DefaultInstanceManager`, and `HostedRuntimeBootstrap` directly.
- `HostedActivityRuntimeBinder`, `HostedProviderRuntimeBinder`, and
  `HostedServiceRuntimeBinder` now use the same engine bootstrap path by
  default while keeping their test injection seams.
- `DefaultVirtualizationEngine` no longer hardcodes
  `com.multiapp.app.container.ContainerActivity`. The app layer provides
  `EngineActivityLauncher` through DI and owns the real Android component
  selection.

### Runtime Slot Assignment

- Added `EngineRuntimeSlotStore` and file-backed persistence for
  `processSlot` / launcher `proxySlot` assignment.
- `DefaultVirtualizationEngine` now allocates stable runtime slots before
  dispatching a launch and returns `UNSUPPORTED` on slot exhaustion instead of
  silently reusing a slot.
- Same-origin instances are assigned different logical `processSlot` values in
  the current engine layer.
- App DI stores engine runtime slots under
  `files/engine_runtime_slots.properties`.
- `ContainerActivity` consumes the engine `proxySlot` launch extra and binds it
  into the shared proxy assignment store before launching the guest proxy.
- `ProxyActivitySlotAssignmentStore` now has a `reserve(...)` API so registry
  selection does not overwrite a proxy slot already owned by another instance
  task.
- `FileBackedProxyActivitySlotAssignmentStore` now uses a shared in-process
  file lock per assignment file and reserves a candidate slot atomically inside
  one load/select/store operation.
- `ContainerRuntimePaths` now reuses `ProxyActivitySlots.SLOT_ASSIGNMENT_FILE`
  for the proxy slot file name to avoid app/loader path drift.

Limit:

- This is a persistent slot and recents-foundation slice. The manifest proxy
  components still run in the current app process, so this is not yet real
  Android process-slot isolation for native multi-instance execution.

### Non-Isolated Split APK Runtime Paths

- Added split APK metadata to installed app discovery, install records,
  artifact manifests, package snapshots, and engine runtime snapshots.
- Imported installed base + split APK artifacts into the app-owned artifact
  directory and persisted split names, copied paths, and split SHA-256 values.
- Normalized old install-record JSON that does not contain split fields so
  existing installations keep loading without null-list crashes.
- Propagated `splitSourceDirs`, `splitPublicSourceDirs`, and `splitNames`
  through `VirtualContextConfig`, `ApplicationInfo`, `LoadedApk` patching,
  Activity/Provider/Service dispatch contexts, and hosted Activity identity.
- Built guest `PathClassLoader` dex paths from base APK plus split APKs.
- Added all split asset paths to fallback `AssetManager` resource loading.
- Scanned base + split APKs for native libraries and included split APK ABI
  entries in the classloader native search path.
- Marked `isolatedSplits=true` as an explicit hosted baseline
  `UNSUPPORTED` classloader failure instead of pretending that dynamic feature
  isolation is supported.

Limit:

- This is the minimal commercial slice for non-isolated split APK paths. Split
  manifest component merging, isolated split classloader graphs, and dynamic
  feature on-demand loading are still open.

### Application / LoadedApk Binding Foundation

- Added `GuestApplicationCreator` as the single Application creation seam used
  by `ApplicationStage` and `HostedRuntimeBootstrap`.
- `ApplicationStage` now creates a default `android.app.Application` path when
  the manifest does not declare a custom Application and a host context is
  available.
- Local JVM bootstrap paths without a host context now record an explicit
  skipped default-Application reason instead of failing the whole bootstrap.
- `LoadedApkRuntimeState` can carry the created guest `Application`.
- `LoadedApkBridge` patches `mApplication` when a guest Application is present.
- `ActivityThreadLoadedApkInstaller` exposes a `bindApplication(...)` helper so
  the already-installed hosted LoadedApk can be updated after Application
  creation without scattering reflection.
- This is still not a full framework `LoadedApk.makeApplication()` replacement.
  It creates the swappable seam and binds evidence first; the real
  ActivityThread/LoadedApk makeApplication-equivalent path remains open.

### Same-Origin Dual-Instance Engine Tests

- Strengthened engine tests for launching two instances of the same origin
  package.
- Tests now assert `instanceId`, `virtualPackageName`, and `dataRoot` do not
  cross between runtimes.
- Tests now assert `processSlot` and `proxySlot` are distinct per instance and
  persisted in the runtime slot store.
- Added engine rebuild coverage using `FileBackedEngineRuntimeSlotStore` to
  prove slot assignments survive process-style engine recreation.

### Provider Route Token Gate

- Added an app-container route-token gate before `StubContentProvider` dispatch.
- Direct provider proxy access now fails closed when the route token is missing,
  expired, or does not match instance, authority, or operation.
- Dispatch canonicalizes the proxy URI from the validated token before runtime
  binding and provider resolution, so forged query parameters are not the source
  of truth.
- `ProviderProxyUri` removes the route token together with instance and guest
  authority parameters when converting back to the guest URI, while preserving
  the guest's original query parameters.
- This is a local/runtime safety foundation. Device evidence for all provider
  operations is still required before marking provider routing `PASS`.

### Native Path Isolation Foundation

- Added a native private-path redirect config model bound to
  `instanceId + dataRoot + processSlot`.
- Native private-path evidence now records redirect binding scope, process slot,
  instance id, origin package, canonical data root, and rule prefixes.
- Storage diagnostics now model native private path decisions with path
  traversal rejection, canonical containment checks, `O_CREAT` parent checks,
  same-origin multi-instance data-root separation, and non-private path
  unchanged behavior.
- Proc/maps and proc/status spoofing remain explicitly disabled in the baseline.
- This is still JVM-level verification and Java bridge configuration. Real
  native syscall/device evidence remains open.

### Engine Evidence Aggregation

- `EngineEvidenceReport` now keeps the original launch/runtime `entries` map and
  adds grouped operation evidence under `operationEvidence[component][operation]`.
- Added `EngineOperationEvidence` for provider/native operation verdicts and
  sanitized key-value details.
- `EngineRuntimeRegistry` can append operation evidence only for an active
  registered runtime. Missing or stopped runtimes reject the evidence instead of
  creating an implicit `PASS` report.
- Provider proxy evidence and provider runtime-bind evidence now also append a
  provider operation verdict to the engine report.
- Native storage diagnostics now append native operation verdicts to the engine
  report.
- App-side engine evidence bridge redacts token/password/secret-like fields
  before writing operation evidence, so route tokens are not copied into engine
  reports.
- `EngineRuntimeRegistry` also sanitizes operation evidence at the aggregation
  boundary, so future adapters cannot bypass bridge-side redaction by calling
  the registry directly.
- This is an in-process aggregation path. Device evidence still has to prove
  that exported engine reports include the expected provider/native entries for
  real launches.

### Engine Evidence Export

- Added a deterministic `EngineEvidenceReport.flattenedOperationEvidence()`
  helper for report exporters. It sorts component/operation groups while
  preserving append order inside each operation group.
- Added an app-container engine report exporter that writes accepted active
  runtime reports to
  `files/hosted_launch_evidence/<instanceId>.engine-report.properties`.
- Provider and native operation evidence now trigger an engine report export
  after `EngineRuntimeRegistry` accepts the evidence.
- Exported reports include `status`, `profile`, `evidenceSessionId`, runtime
  entries, grouped operation counts, indexed operation entries, and grep-friendly
  provider/native verdict fields.
- Export is skipped when the registry rejects evidence, so missing/stopped
  runtime updates do not create stale `PASS` report files.
- Exporter output reuses the shared evidence sanitizer and also sanitizes
  component/operation labels, so route token and password-like suffixes cannot
  leak even if an unsanitized report is passed directly to the exporter.
- This creates a device-readable evidence file path, but it is not yet
  device-proven. A real launch still needs `run-as com.multiapp.app cat
  files/hosted_launch_evidence/*.engine-report.properties`.

### Runtime Path And Process Slot Contract

- Added explicit runtime path helpers for base-first class/resource loading:
  `InstallRecord.codeSourceDirs`, `InstallRecord.publicResourceDirs`,
  `VirtualPackageSnapshot.codeSourceDirs`, and
  `VirtualPackageSnapshot.publicResourceDirs`.
- `VirtualContextConfig` now carries `publicSourceDir` and exposes matching
  base-first `codeSourceDirs` / `publicResourceDirs` while still allowing
  snapshotless pre-launch patch contexts to remain partial instead of crashing.
- Virtual activity ApplicationInfo delegation now preserves
  `publicSourceDir`, `splitSourceDirs`, `splitPublicSourceDirs`, and
  `splitNames`.
- Native private-path redirect installation now accepts the engine-selected
  `processSlot` instead of silently deriving `process:<instanceId>`.
- `HostedRuntimeBootstrap`, `HostedRuntimeEngine`, app launch bootstrap, and
  `ContainerActivity` now pass the engine `processSlot` into native redirect
  setup. If an old caller omits it, `DefaultHostedRuntimeEngine` falls back to
  `EngineRuntimeRegistry.global` for the active instance runtime.
- Added JVM behavior coverage for same-origin provider/native evidence
  isolation, stopped-runtime evidence rejection without clearing sibling
  runtimes, provider cache isolation by instance id, and base/split path
  ordering.

## Verification

The full all-in-one gate exceeded the local 180 second tool timeout, so the same
scope was run in smaller chunks with the same Gradle settings:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:instance:testDebugUnitTest :core:manifest:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:identity:testDebugUnitTest :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:hook:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :feature:launcher:testDebugUnitTest :feature:appmanager:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
```

Additional verification after hosted-runtime binding was moved behind
`:core:engine`:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
```

Additional verification after persistent runtime slots and proxy slot
reservation:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
git diff --check
```

Result:

- All commands above passed.
- `git diff --check` passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Additional verification after non-isolated split APK runtime paths:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:instance:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:manifest:testDebugUnitTest :core:hook:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
git diff --check
```

Result:

- All commands above passed.
- `git diff --check` passed with only existing CRLF normalization warnings.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Additional verification after Application / LoadedApk binding foundation and
same-origin dual-instance engine tests:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --tests "com.multiapp.core.loader.LoadedApkBridgeTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --tests "com.multiapp.core.loader.HostedRuntimeBootstrapTest" --tests "com.multiapp.core.loader.DualInstanceBaselineTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
git diff --check
```

Result:

- All commands above passed.
- `git diff --check` passed with only existing CRLF normalization warnings.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Additional verification after Provider route token gate and native path
isolation foundation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.ProviderProxyUriTest" --tests "com.multiapp.app.container.StubContentProviderRouteTokenTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.NativePrivatePathRedirectInstallerTest" --tests "com.multiapp.core.loader.VirtualContextStorageTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
git diff --check
```

Result:

- Targeted app provider tests passed.
- Targeted loader native/storage tests passed.
- Full `:app:testDebugUnitTest` passed.
- Full `:core:loader:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `git diff --check` passed with only existing CRLF normalization warnings.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Additional verification after engine evidence aggregation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerEngineEvidenceBridgeTest" --tests "com.multiapp.app.container.ContainerStorageDiagnosticsEvidenceTest" --tests "com.multiapp.app.container.ProviderProxyUriTest" --tests "com.multiapp.app.container.StubContentProviderRouteTokenTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:common:testDebugUnitTest :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Targeted app evidence bridge/provider/storage tests passed.
- `:core:common:testDebugUnitTest`, `:core:model:testDebugUnitTest`, and
  `:core:engine:testDebugUnitTest` passed after registry-boundary redaction was
  added.
- Full `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `git diff --check` passed with only existing CRLF normalization warnings.
- During integration, one transient KSP generated-file collision occurred under
  `app/build/generated/ksp/debug`; removing that generated directory and rerunning
  the same target tests passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Additional verification after engine evidence export:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerEngineEvidenceBridgeTest" --tests "com.multiapp.app.container.ContainerRuntimePathsTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Targeted app engine-report export tests passed.
- `:core:model:testDebugUnitTest` and `:core:engine:testDebugUnitTest` passed.
- `git diff --check` passed with only existing CRLF normalization warnings.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Additional verification after runtime path and engine process-slot contract:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ActivityThreadLaunchRecordPatcherTest" --tests "com.multiapp.core.loader.NativeLibrariesStageTest" --tests "com.multiapp.core.loader.NativePrivatePathRedirectInstallerTest" --tests "com.multiapp.core.loader.VirtualProviderRuntimeTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Targeted loader launch-record/native/provider runtime tests passed.
- `:core:model:testDebugUnitTest`, `:core:engine:testDebugUnitTest`,
  `:app:testDebugUnitTest`, and `:app:assembleDebug` passed.
- A broader combined gate including full `:core:loader:testDebugUnitTest`
  exceeded the local 180 second tool timeout, so the failing loader scope was
  rerun as targeted tests and passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

## Execution Update - 2026-07-08 Runtime Identity Baseline

This slice moves the hosted runtime closer to the VirtualApp/DroidPlugin model
without claiming device-level compatibility yet.

Implemented:

- Bound `ProxyActivity` slots to real Android process names `:v0` through
  `:v23`, and added matching `ContainerActivityV0` through
  `ContainerActivityV23` manifest entries.
- Derived engine `processSlot` from the selected `proxySlot`, so native redirect
  and evidence no longer disagree about the runtime process.
- Added `activity-process-slot` evidence in `ContainerActivity`, including a
  fail-fast check when the actual process name does not match
  `engineProcessSlot`.
- Rewrote AppOps identity at two levels:
  - `VirtualContextWrapper.getOpPackageName()` and API 34
    `getAttributionSource()` expose the host package for binder validation.
  - `AppOpsManager.mService` and `ServiceManager.sCache["appops"]` are proxied
    so direct `IAppOpsService` callers rewrite origin/virtual package arguments
    to the host package and adjacent uid arguments to `Process.myUid()`.
- Added Application bootstrap progress evidence for class load, constructor,
  context creation, attach, runtime publication, and `onCreate` phases.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
git diff --check
```

Result:

- Full `:core:loader:testDebugUnitTest` passed.
- Full `:core:engine:testDebugUnitTest` passed.
- Full `:app:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- `git diff --check` passed with only existing CRLF normalization warnings.

Remaining gate for this slice:

- Install on device and capture `logcat`, `dumpsys activity exit-info`,
  `dumpsys activity recents`, `files/hosted_launch_evidence`, and
  `files/instances`.
- Confirm `activity-process-slot=PASS`, `appOpsPackageProxyStatus=INSTALLED`,
  `appOpsServiceManagerProxyStatus=INSTALLED`, and no AppOps
  uid/package mismatch during GKD, QQ, WeChat, and QQ Reader launches.

## Execution Update - 2026-07-08 Process-Slot Consistency Slice

This slice addresses a multi-instance/process bug found during parallel review:
the previous catalog mapped `ProxyActivity0`, `ProxyActivitySingleTop0`, and
`ProxyActivitySingleTask0` to different Android processes. That could split one
guest instance across multiple host processes when the guest launched Activities
with different `launchMode` values.

Implemented:

- Changed the proxy/process model to eight owning process slots:
  `:v0` through `:v7`.
- Mapped each slot's three proxy variants to the same process:
  - `ProxyActivityN`
  - `ProxyActivitySingleTopN`
  - `ProxyActivitySingleTaskN`
- Restricted runtime proxy allocation to the current runtime `processSlot` by
  carrying `processSlot` through `HostedBootstrapResult` and
  `VirtualContextConfig`.
- Updated `VirtualContextWrapper`, `VirtualInstrumentation`,
  `ActivityThreadLaunchRecordPatcher`, `VirtualAmsComponentDispatcher`,
  provider dispatch, and service dispatch to preserve the owning process slot
  where local runtime state is available.
- Extended AppOps package rewriting to handle nested
  `AttributionSource`/`AttributionSourceState`-like arguments and arrays,
  not only direct `String` package arguments.
- Made `StubService` return the guest Service `onStartCommand()` result on the
  synchronous path. Async runtime bootstrap still returns
  `START_NOT_STICKY`, and evidence records
  `hostStartCommandReturnMode=ASYNC_HOST_ALREADY_RETURNED_DEFAULT`.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ProxyActivitySlotsTest" --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.ProxyActivityClassParityTest" --tests "com.multiapp.app.container.StubServiceTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Targeted loader tests passed.
- Targeted app tests passed.
- Full `:core:engine:testDebugUnitTest` passed.
- A combined loader/app targeted invocation exceeded the 180 second local tool
  timeout; the same scopes were split by module and passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Remaining gate for this slice:

- Device evidence must prove that an instance starting standard, singleTop, and
  singleTask guest Activities remains inside one owning `:vN` process.
- Same-origin dual-instance recents still require device evidence.
- Provider/Service/Broadcast still need owning-process binding instead of
  default-process bootstrap.

## Execution Update - 2026-07-08 Provider/Service Slot Entry Slice

This slice starts closing the owning-process gap for non-Activity components.
The goal is to stop Provider and Service cold-start paths from losing
`processSlot` and silently bootstrapping guest runtime in the default host
process.

Implemented:

- `ProviderRouteTokenRegistry` now stores optional `processSlot` on route
  tokens and validates an expected slot when supplied.
- `ProviderRoutingStage` records `instanceId -> processSlot` before provider
  hook installation, so baseline and hook-enabled paths share one route source.
- Provider URI/extras rewrite now carries `multiapp_processSlot`; guest URI and
  extras conversion strips it before dispatching to guest code.
- `VirtualProviderManager` resolves slot-specific stub authorities such as
  `${applicationId}.multiapp.provider.stub.v3` when an instance process slot is
  known.
- Added `StubContentProviderV0..V7` and manifest provider declarations bound to
  `:v0..:v7`.
- `HostedProviderRuntimeBinder` passes the validated process slot into
  `runHostedRuntimeBootstrap(...)` and rejects cached runtimes from a different
  process slot.
- `VirtualServiceStartRequest` / `VirtualServiceProxySpec` now carry
  `processSlot`.
- `VirtualServiceManager` maps slot-aware requests to
  `StubServiceV0..V7`, with legacy `StubService` kept as no-slot fallback.
- Added manifest service declarations for `StubServiceV0..V7`, each bound to
  its matching `:vN`.
- `HostedServiceRuntimeBinder` passes service process slot into bootstrap and
  rejects cached runtime slot mismatches.
- Service/provider evidence now records the runtime-bind process slot.

Verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.HostedProviderRuntimeBinderTest" --tests "com.multiapp.app.container.HostedServiceRuntimeBinderTest" --tests "com.multiapp.app.container.StubContentProviderRouteTokenTest" --tests "com.multiapp.app.container.ProviderProxyUriTest" --tests "com.multiapp.app.container.ProxyActivityClassParityTest" :core:identity:testDebugUnitTest --tests "com.multiapp.core.identity.ContentProviderHookUriRewriteTest" :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ProviderRoutingStageTest" --tests "com.multiapp.core.loader.VirtualServiceManagerTest" --tests "com.multiapp.core.loader.VirtualProviderManagerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ProviderRoutingStageTest" --tests "com.multiapp.core.loader.VirtualServiceManagerTest" --tests "com.multiapp.core.loader.VirtualProviderManagerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- App target tests passed before the combined command reached the later
  loader failure.
- The loader failure was a JVM test using unmocked `android.net.Uri.parse`;
  the test was corrected to assert pure provider resolution instead.
- Retried core loader target tests passed.
- `:app:assembleDebug` passed in 1m 34s. Known warning remains: AGP `8.7.3`
  was tested up to `compileSdk=35`, while the project uses `compileSdk=36`.

Remaining gate for this slice:

- Device evidence must prove provider and service requests enter the expected
  `:vN` process and do not bootstrap the same instance in the default process.
- Broadcast remains process-local dispatch only; slot-aware broadcast gateway
  and ordered/sticky/result semantics are still open.

## Not Complete Yet

The following items from the plan are still open and must not be marked `DONE`:

- Split manifest component merging, isolated split classloader graphs, dynamic
  feature on-demand loading, and device-proven split APK launches.
- Multidex fixture validation on device.
- Full `LoadedApk.makeApplication()`-equivalent application creation model.
  The swappable creator/binding seam exists, but device evidence and real
  ActivityThread-backed creation are not complete.
- Device-proven engine-owned hosted runtime binding. Local code now routes
  `ContainerActivity` and `Hosted*RuntimeBinder` through `:core:engine`, but
  device evidence is still required before marking this runtime path `PASS`.
- Device-proven simultaneous same-origin multi-instance recents.
- Real Android process-slot isolation for native multi-instance execution. JVM
  decision tests exist, but native syscall evidence is not complete.
- Device-proven exported `EngineEvidenceReport` for provider/native operation
  evidence. Local export to `hosted_launch_evidence` exists, but real launch
  reports still need device evidence.
- Full PMS signatures/SigningInfo, typed metadata, provider read/write
  permissions, and `IntentFilter.match()` fidelity.
- Complete AMS/ATM activity stack semantics:
  `launchMode/taskAffinity/onNewIntent/result/finish/back stack`.
- Service foreground/sticky/bind semantics and device proof for slot-aware
  service stubs.
- Device proof for slot-aware provider stubs and provider operations.
- Ordered/sticky broadcast/result receiver semantics.
- Notification/AppOps/Clipboard/Account/Alarm/Job/Shortcut service proxy
  registry.
- Device evidence matrix for minimal app, GKD, native sample, provider-heavy
  sample, QQ, WeChat, and QQ Reader diagnostics.

## Next Development Slice

Recommended next slice:

1. Install the current debug APK on device, run provider/native sample launches,
   and capture `*.engine-report.properties` with `run-as` to prove real export.
2. Add a split/multidex fixture and device evidence for launcher Activity,
   resources, provider, service, and native library loading from splits.
3. Add behavior tests for same-origin dual-instance isolation across storage,
   provider, service, and native redirect.
4. Add Provider pre-install before guest `Application.onCreate()`, then close
   Broadcast slot-aware gateway and ordered/sticky/result semantics.

## Execution Update - 2026-07-09 LoadedApk-First Application Creation

This slice moves the hosted Application bootstrap from reflective-first toward
the VirtualApp/BlackBox-style `LoadedApk.makeApplication()` path. It is still a
local foundation slice, not a device-proven commercial compatibility claim.

Implemented:

- `ApplicationStage` now defaults to `LoadedApkGuestApplicationCreator` instead
  of reflective construction.
- `HostedRuntimeBootstrap` now uses the same LoadedApk-first creator on the
  production hosted runtime path.
- `LoadedApkGuestApplicationCreator` installs a guest sandbox LoadedApk through
  `ActivityThreadLoadedApkInstaller.installGuestSandbox(...)`, calls
  `LoadedApk.makeApplication(false, instrumentation)`, then binds the returned
  Application back into the installed LoadedApk through
  `ActivityThreadLoadedApkInstaller.bindApplication(...)`.
- If the LoadedApk path is unavailable, the creator falls back to the existing
  reflective attach path and records explicit fallback evidence instead of
  pretending the LoadedApk path passed.
- Application runtime publication now preserves the engine-selected
  `processSlot`, so Application `onCreate()` observers see the same slot as the
  hosted launch path.
- Added focused JVM coverage for the LoadedApk-first creator using injected
  fake ActivityThread/LoadedApk/makeApplication seams.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.LoadedApkBridgeTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --tests "com.multiapp.core.loader.HostedRuntimeBootstrapTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.LoadedApkBridgeTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- All three focused loader commands passed.
- Full `:core:loader:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Remaining gate for this slice:

- Device evidence must show `applicationCreator=LOADED_APK_MAKE_APPLICATION`
  and `loadedApkApplicationCreatorStatus=PASS` for real hosted launches before
  this can be marked `PASS`.
- JVM fallback evidence is expected in local unit tests; it is not sufficient
  for commercial readiness.
- Provider pre-install before guest `Application.onCreate()` is still open.

## Execution Update - 2026-07-09 Provider Preinstall Ordering

This slice adds the first same-process provider preinstall path between guest
Application attach/runtime publish and guest `Application.onCreate()`.

Implemented:

- Added `GuestProviderPreinstaller` as the single preinstall seam for
  same-process guest providers.
- `ApplicationStage` now runs provider preinstall after publishing the reusable
  runtime and before calling guest `Application.onCreate()`.
- Same-process filtering follows Android's default process rule: providers
  without a custom `processName` are preinstalled in the Application process;
  providers declaring a different process are skipped for this slice.
- Provider creation uses the existing `VirtualProviderManager` resolution and
  `VirtualProviderRuntime.getOrCreate(...)`, so preinstall shares the same
  provider cache as later stub-provider dispatch.
- Application stage evidence now records `providerPreinstallStatus`, provider
  counts, installed/cached/failed authorities, and skipped/failure reasons.
- Added a JVM ordering test proving the sequence:
  runtime published -> provider attached -> guest `Application.onCreate()`.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Targeted `ApplicationStageTest` passed.
- Full `:core:loader:testDebugUnitTest` passed.
- `:app:assembleDebug` passed.

Remaining gate for this slice:

- Device evidence must prove same-process provider attach happens before
  guest `Application.onCreate()` in a real hosted launch.
- Custom-process providers are still skipped; process-name-aware provider
  runtime remains part of the broader multi-process container work.
