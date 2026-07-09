# MultiApp Commercial Engine Progress - 2026-07-07

## Scope

This document records the current progress against `D:\Downloads\PLAN.md` and
`docs/reviews/2026-07-07-container-runtime-bug-audit.md`.
Open-source route comparison is tracked in
`docs/reviews/2026-07-09-open-source-engine-comparison.md`.

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

### Engine Boundary Facades

- Added engine-owned provider dispatch DTOs so `StubContentProvider` and
  `ProviderRouteTokenGate` no longer consume loader `VirtualProvider*` result
  types directly.
- Added engine-owned service dispatch DTOs and evidence-field mapping so
  `StubService` no longer switches on loader `VirtualServiceDispatchResult` in
  the app layer.
- Added `EngineHostedBootstrapResult` as the app-visible hosted bootstrap
  wrapper. `HostedRuntimeEngine`, hosted runtime binders, and
  `ContainerActivity` now exchange the engine wrapper instead of exposing
  loader `HostedBootstrapResult` across the app boundary.
- Added `EngineActivityRuntime` facades for proxy Activity slots, launcher
  dispatch, proxy record observation/recovery, and stale proxy-record pruning.
  `ContainerActivity` and `ProxyActivityBase` no longer import loader
  `ProxyActivitySlots`, `VirtualActivityManager`,
  `VirtualActivityRecordManager`, or `VirtualActivityIntentStore` directly.
- Engine storage/provider evidence facades accept the wrapper and unwrap it
  inside `:core:engine`, keeping app/container on the engine facade path.
- `EngineBoundaryTest` was tightened by removing the migrated
  `HostedBootstrapResult`, `VirtualServiceDispatchResult`,
  `VirtualProviderDispatchResult`, `VirtualProviderEvidence`, and Activity
  slot/record primitive app-side allowances.

Verification:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
git diff --check
```

Result:

- Unified local engine/app test and debug build gate passed.
- `git diff --check` passed with only existing CRLF normalization warnings.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Limit:

- This is a boundary cleanup and ownership step, not a new compatibility proof.
  `ContainerActivity` still reads loader bootstrap stage primitives
  (`BootstrapResult` / `RuntimeStage`) for launch evidence; moving those to an
  engine evidence DTO is the next small boundary slice. Device verification for
  this specific boundary-facade batch is still pending.

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

## Execution Update - 2026-07-09 VirtualSystemServer Boundary

This slice starts the commercial-engine boundary required by the VirtualApp /
BlackBox-style plan. It does not claim full VPMS/VAMS completion; it creates
the engine-owned service boundary and freezes new app-side direct runtime
imports so later slices can migrate behavior behind `:core:engine`.

Implemented:

- Extended the public engine contract with runtime lifecycle metadata:
  `runtimeEpoch`, `engineSessionId`, `processId`, `processName`, and
  `VirtualRuntimeState`.
- Extended `LaunchInstanceRequest` with pure-model launch controls:
  `targetComponentClassName`, `launchFlags`, `EngineTaskPolicy`,
  `EnginePrewarmPolicy`, and `EngineEvidenceMode`.
- Added `EngineSubsystem` and subsystem verdict aggregation to
  `EngineEvidenceReport`.
- Added `VirtualSystemServer` plus typed engine subsystem facades for runtime,
  package, activity, provider, service, broadcast, storage, native, and
  evidence.
- Routed `DefaultVirtualizationEngineCore` runtime registration, query, stop,
  and evidence export through `VirtualSystemServer.runtimeService` instead of
  directly touching `EngineRuntimeRegistry`.
- Engine reports now export runtime lifecycle fields and subsystem verdicts.
- Added an app boundary freeze test: existing direct `:app -> loader/hook`
  imports are treated as a migration baseline, and any new direct runtime
  import must fail the test until it goes through engine facades.

Verification:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --tests "com.multiapp.core.model.engine.VirtualizationEngineModelTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.DefaultVirtualizationEngineTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --tests "com.multiapp.app.container.ContainerEngineEvidenceBridgeTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `:core:model` and `:core:engine` tests passed.
- Focused `:app` boundary/exporter tests passed.

Remaining gate for this slice:

- Existing app/container direct imports are still a migration baseline, not a
  solved boundary.
- At the time of this slice, `VirtualSystemServer` had only the runtime facade
  wired to existing behavior; later slices add first-stage package/runtime
  state recovery, but VAMS/Provider/Service/Broadcast/Storage/Native services
  still need real implementations and device evidence.

## Execution Update - 2026-07-09 Durable Runtime State

This slice starts P0-B cross-process/process-death recovery work. It does not
create a full binder-backed virtual system server yet; it makes the current
engine runtime identity durable enough for hosted processes to recover
`instanceId + processSlot + proxySlot + runtimeEpoch` after engine recreation.

Implemented:

- Added `EngineRuntimeStateStore` with in-memory and file-backed
  implementations.
- Persisted `VirtualInstanceRuntime` identity and the package snapshot fields
  needed for first-stage recovery:
  `originPackageName`, `virtualPackageName`, `dataRoot`, source/split paths,
  native library directory, application class, permissions, certificate hash,
  `processSlot`, `proxySlot`, `runtimeEpoch`, `engineSessionId`, process name,
  and runtime state.
- `EngineRuntimeRegistry` now writes runtime state on register, restores from
  the durable store on lookup/evidence, and removes durable state on stop.
- Production `DefaultVirtualizationEngine` and `DefaultHostedRuntimeEngine`
  attach the shared file store at
  `files/engine_runtime_state.properties`.
- Hosted bootstrap now falls back to durable runtime state for `processSlot`
  resolution instead of requiring an in-memory `EngineRuntimeRegistry.global`
  hit.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineRuntimeStateStoreTest" --tests "com.multiapp.core.engine.EngineRuntimeRegistryTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.DefaultVirtualizationEngineTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `:core:engine` runtime state and system-server tests passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Remaining gate for this slice:

- The durable state currently stores runtime identity and package snapshot
  fields, including component declarations. Task stacks, provider/service
  lifecycles, broadcast state, URI grants, observer state, and native runtime
  bindings are still not durable.
- Device evidence is still required to prove process-death recents recovery and
  hosted-process bootstrap after kill/restart.

## Execution Update - 2026-07-09 VPMS Snapshot Facade

This slice starts the `VirtualPackageService` side of the engine-owned virtual
system server. It does not implement full Android `PackageManager` parity yet;
it creates the first package-query facade backed by the same runtime snapshot
that launch, LoadedApk, Context, Resources, and future PMS hooks must share.

Implemented:

- `VirtualPackageService` now exposes:
  - `queryPackageSnapshot(instanceId)`
  - `queryPackageIdentity(instanceId)`
- Added `VirtualPackageIdentity` as a small identity view over
  `VirtualInstanceRuntime + VirtualPackageSnapshot`.
- `DefaultVirtualSystemServer` now wires `packageService` to
  `RegistryBackedVirtualPackageService`, so package queries read through the
  engine runtime service rather than a separate app/container cache.
- File-backed runtime state now preserves package-level `metaData` and declared
  `activities`, `services`, `receivers`, and `providers`, including resolved
  intent filters, authorities, launch mode, process name, task affinity,
  permissions, grant-URI flag, component meta-data, and alias target.
- The app boundary test now freezes direct runtime references by
  `kind + path + fqcn + count` and also scans fully-qualified direct usages,
  not only `import` statements. Existing references remain migration debt, and
  new bypasses must go through `:core:engine`.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineRuntimeStateStoreTest" --tests "com.multiapp.core.engine.VirtualPackageServiceTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineRuntimeRegistryTest" --tests "com.multiapp.core.engine.DefaultVirtualizationEngineTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --tests "com.multiapp.app.container.ContainerEngineEvidenceBridgeTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused engine runtime-state/package/system-server tests passed.
- Focused app boundary/exporter tests passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

Remaining gate for this slice:

- This is snapshot-backed VPMS foundation only. It still does not answer real
  Android `PackageInfo`, `ApplicationInfo`, `ProviderInfo`,
  `ResolveInfo`, signing, permissions, or intent matching requests.
- Device evidence still has to prove exported engine reports contain package
  subsystem verdicts for real launches.

## Execution Update - 2026-07-09 VPMS Component Resolver

This slice expands `VirtualPackageService` from package identity lookup into
the first engine-owned component resolver. It follows the VirtualApp /
DroidPlugin direction: component lookup must be answered from the central
virtual package snapshot rather than from ad-hoc app/container state.

Implemented:

- Added `VirtualPackageComponentType` for `ACTIVITY`, `SERVICE`, `RECEIVER`,
  and `PROVIDER`.
- `VirtualPackageService` now supports:
  - explicit component lookup by class name
  - alias target lookup through `targetActivityName`
  - provider lookup by authority
  - basic intent resolution by action, category, and data scheme
- The resolver works after file-backed runtime state restore, so component
  queries do not depend on the original in-memory runtime object surviving.
- Tests cover missing-runtime behavior, restored Activity/Service/Receiver/
  Provider lookup, launcher intent matching, service data-scheme matching,
  receiver action matching, provider authority matching, and wrong-category
  rejection.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualPackageServiceTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused VPMS component resolver tests passed.

Remaining gate for this slice:

- Intent matching is still a deliberately small subset. MIME type, host, port,
  path, priority, preferred activities, permissions, disabled components, and
  per-user enabled state are not complete.
- The app/container runtime is not yet migrated to use this resolver for all
  Activity/Provider/Service/Broadcast dispatch paths.

## Execution Update - 2026-07-09 Engine Container Dispatch Facades

This slice starts migrating the real Android app/container stubs behind
`:core:engine` dispatch facades. It follows the same boundary direction as the
open-source comparison: app owns manifest-declared Android stub components, but
runtime dispatch decisions should move into the engine layer.

Implemented:

- Added `EngineProviderDispatcher` / `DefaultEngineProviderDispatcher` and
  `EngineProviderDispatchRequest` in `:core:engine`.
- Added `EngineServiceDispatcher` / `DefaultEngineServiceDispatcher` and
  `EngineServiceDispatchRequest` in `:core:engine`.
- `StubContentProvider` no longer constructs `VirtualProviderDispatcher`
  directly; after route-token validation and hosted runtime binding, it calls
  the engine provider facade.
- `StubService` no longer constructs `VirtualServiceDispatcher` directly; after
  hosted runtime binding, it calls the engine service facade.
- `EngineBoundaryTest` migration baseline was reduced by removing the old
  direct `VirtualProviderDispatcher` and `VirtualServiceDispatcher` imports.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused engine dispatch facade test passed.
- Focused app boundary test passed after serial rerun.

Remaining gate for this slice:

- The engine facades still wrap loader primitives and return loader result
  types. This is a boundary migration step, not the final pure-engine DTO
  shape.
- `StubContentProvider`, `StubService`, binders, `ContainerActivity`, and
  proxy activities still import other loader primitives. Those direct imports
  remain migration debt and are intentionally tracked by `EngineBoundaryTest`.

## Execution Update - 2026-07-09 Runtime Binder Engine Boundary

This slice removes the app/container runtime binders' direct dependency on
`VirtualProcessRuntime.global`. Activity, Provider, and Service proxy binders
now bind through `HostedRuntimeEngine`, keeping the process-local loader
registry behind the engine boundary.

Implemented:

- `HostedActivityRuntimeBinder` now calls `HostedRuntimeEngine.reusableResult`
  and `HostedRuntimeEngine.bindApplication` instead of directly using
  `VirtualProcessRuntime`.
- `HostedProviderRuntimeBinder` now uses the same engine runtime entrypoint
  while preserving provider route `instanceId`, `guestAuthority`, and
  `processSlot` validation.
- `HostedServiceRuntimeBinder` now consumes `EngineServiceStartRoute`, and
  `StubService` routes proxy-intent decoding, reusable-runtime checks, and
  proxy-token cleanup through `DefaultEngineServiceRouter`.
- Added `EngineServiceStartRoute`, `EngineServiceLaunchInfo`, and
  `EngineServiceRouter` in `:core:engine` as the first Service-specific route
  facade over the existing loader primitive.
- `EngineBoundaryTest` was tightened by removing the legacy
  `VirtualProcessRuntime` allow-list entries for Activity/Provider/Service
  binders and the old `VirtualServiceManager` / `VirtualServiceStartRequest` /
  `VirtualServiceIntentStore` entries from `StubService` and
  `HostedServiceRuntimeBinder`.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.container.HostedActivityRuntimeBinderTest" --tests "com.multiapp.app.container.HostedProviderRuntimeBinderTest" --tests "com.multiapp.app.container.HostedServiceRuntimeBinderTest" --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused engine dispatcher test passed.
- Focused Activity/Provider/Service binder tests passed.
- Focused app boundary test passed.

Remaining gate for this slice:

- Provider route parameter names still come from `VirtualProviderManager`.
  Extracting an engine/model route contract is the next small boundary slice.
- Activity launch primitives in `ContainerActivity` / `ProxyActivityBase` still
  have direct loader references; migrating task/record/proxy-slot control to an
  engine Activity facade is the next high-risk boundary slice.

## Execution Update - 2026-07-09 Container Facade Contract Batch

This batch continues the app/container boundary shrink after the runtime binder
slice. The work was batched before running Gradle so the local verification cost
stays closer to a team-style integration gate instead of testing after every
small edit.

Implemented:

- Added `ProviderRouteContract` in `:core:model` and migrated provider proxy
  URI, route-token gate, hosted provider binder, and stub provider code to use
  that shared contract. `VirtualProviderManager` keeps compatibility forwards
  for loader-side callers.
- Added `ProxySlotContract` in `:core:model` and routed app/loader proxy-slot
  file naming through the same constant to avoid path drift.
- Added `EngineProviderRouteSlots.stubAuthority(...)` so
  `StubContentProvider` no longer reaches directly into `ProxyActivitySlots`
  for process-slot authority selection.
- Added engine AMS/Broadcast evidence DTOs and recorder adapters. App-side
  `ContainerAmsApiEvidenceRecorder` and `ContainerBroadcastEvidenceRecorder`
  now consume engine records instead of loader records.
- Added `EngineRuntimeInstallers` so `MultiAppApplication` installs
  instrumentation and AMS/Broadcast recorders through `:core:engine` rather
  than importing loader installer singletons directly.
- Added `EngineProviderOperationEvidenceFacade` so
  `ContainerProviderOperationEvidence` only writes file-backed evidence batches;
  bootstrap/result field extraction now lives behind the engine facade.
- Tightened `EngineBoundaryTest` by removing migrated allow-list entries for
  AMS/Broadcast recorder imports, provider operation bootstrap import, and
  app-level instrumentation/evidence installer imports.

Verification:

```powershell
git diff --check
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `git diff --check` passed with only existing Windows CRLF normalization
  warnings.
- The `core:model/core:engine/core:loader` test batch passed.
- The `app:testDebugUnitTest` + `app:assembleDebug` batch passed.
- Known warnings remain: AGP `8.7.3` is still newer-compileSdk-warning-prone
  for `compileSdk=36`, Gradle reports deprecated features, and
  `generateLoaderDex` still prints invalid-locals warnings from dependency
  bytecode.

Remaining gate for this slice:

- This is still a boundary migration. Several app/container files intentionally
  keep tracked migration-debt imports for `ContainerActivity`, proxy Activity,
  storage diagnostics, and loader dispatch result DTOs.
- Device evidence was not collected in this batch. QQ/WeChat/QQ Reader/GKD
  compatibility remains unproven until a fresh install/manual launch cycle
  exports logcat, exit-info, recents, hosted launch evidence, and instances.

## Execution Update - 2026-07-09 Storage Diagnostics Engine Facade

This batch migrates PR-10 storage/native diagnostics behind `:core:engine`.
It follows the VirtualApp / BlackBox direction that app-side Android stubs
should write evidence and host components, while the engine owns runtime and
native/storage decisions.

Implemented:

- Added `EngineStorageDiagnosticsFacade` plus engine-owned storage diagnostic
  DTOs and evidence entries.
- Moved Java absolute-path diagnostic planning, native IO unsupported/probe
  decisions, native runtime verdict fields, and component naming from
  `app/container` into `:core:engine`.
- `ContainerStorageDiagnosticsEvidence` is now a thin file-backed writer. It
  accepts the hosted bootstrap object as an opaque value, asks the engine facade
  for a plan, writes isolation markers, and records engine evidence.
- `ContainerEngineEvidenceBridge` now accepts engine storage diagnostics instead
  of loader `VirtualStoragePathDiagnostic`.
- Updated app tests to use engine storage DTOs and added focused engine tests
  for bootstrap-unsupported and native-fail planning.
- Tightened `EngineBoundaryTest` by removing migrated app-side storage and
  `NativeHookBridge` allow-list entries.

Verification:

```powershell
git diff --check
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineStorageDiagnosticsEvidenceTest" :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerStorageDiagnosticsEvidenceTest" --tests "com.multiapp.app.container.ContainerEngineEvidenceBridgeTest" --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `git diff --check` passed with only existing Windows CRLF normalization
  warnings.
- Focused engine storage facade, app storage evidence, engine evidence bridge,
  and boundary tests passed.

Remaining gate for this slice:

- This is still a facade/boundary step. Real native IO redirect still needs
  device evidence for `open/openat/stat/access/fopen/realpath`, namespace,
  `findLibrary`, and `nativeLoad`.
- `ContainerActivity`, `ProxyActivityBase`, provider/service dispatch result
  DTOs, and hosted bootstrap result exposure remain app/container migration
  debt.

## Execution Update - 2026-07-09 Bootstrap Stage Engine DTO Batch

This batch keeps the verification cadence coarse: the code and tests were
updated together, then one local gate was run. No device install or manual
runtime test was performed in this slice.

Implemented:

- Added engine-owned bootstrap stage/status/result DTOs on top of
  `EngineHostedBootstrapResult`.
- Moved `ContainerActivity` package-manager, application, and launcher evidence
  lookup to engine bootstrap DTOs instead of app-side `BootstrapResult` /
  `RuntimeStage` imports.
- Tightened `EngineBoundaryTest` again so app main code no longer has an
  allow-list for `ContainerActivity -> BootstrapResult/RuntimeStage`.
- Added engine tests for loader-to-engine bootstrap DTO conversion and launcher
  failure evidence preservation.
- App main direct runtime reference scan now only shows the existing
  `AppModule.kt -> HookEngine` DI entry.

Verification:

```powershell
git diff --check
rg -n "import com\.multiapp\.core\.(loader|hook|xposed)\.|com\.multiapp\.core\.(loader|hook|xposed)\." app/src/main/java -S
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `git diff --check` passed with only Windows CRLF normalization warnings.
- The app main direct runtime reference scan returned only
  `app/src/main/java/com/multiapp/app/AppModule.kt -> HookEngine`.
- The Gradle gate passed in `2m 34s`.
- Known warnings remain: AGP `8.7.3` compileSdk 36 compatibility warning,
  deprecated `ActivityManager.TaskDescription(String)`,
  `generateLoaderDex` invalid locals warning, and Gradle deprecated features.

Remaining gate for this slice:

- This is still structural boundary work. It does not prove QQ/WeChat/QQ
  Reader/GKD runtime compatibility.
- `AppModule.kt -> HookEngine` is still app-side migration debt until hook
  profile wiring is fully owned by engine DI.

## Execution Update - 2026-07-09 Hook Profile Engine Facade

This batch removes the last app/main direct runtime primitive reference. Hook
capability remains available, but the app no longer provides or imports
`HookEngine`; hook/profile evidence now belongs to `:core:engine`.

Implemented:

- Added `EngineHookRuntime` and `DefaultEngineHookRuntime` in `:core:engine`.
- Added `hook-profile/profile-gate` operation evidence with profile flags for
  LSPlant, Xposed, proc/maps spoof, signature fake, business wrappers, no-op
  patches, native hook enhancement, and diagnostics observe-only mode.
- `DefaultVirtualizationEngine` now injects the engine hook runtime and records
  hook profile evidence during launch after runtime registration.
- Baseline and diagnostics profiles do not touch the `HookEngine` singleton.
  Allow-listed `COMPAT_HOOK` is reported as `PARTIAL` until the real hook init
  path has runtime/device evidence.
- Removed `AppModule.provideHookEngine()` and tightened `EngineBoundaryTest` to
  zero allowed app/main `core.loader/core.hook/core.xposed` direct references.
- Added tests for hook profile evidence and allow-listed compat-hook launch
  routing through the engine hook runtime.

Verification:

```powershell
git diff --check
rg -n "import com\.multiapp\.core\.(loader|hook|xposed)\.|com\.multiapp\.core\.(loader|hook|xposed)\." app/src/main/java -S
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `git diff --check` passed with only Windows CRLF normalization warnings.
- The app main direct runtime reference scan returned no matches.
- The Gradle gate exited successfully within the 180s timeout.

Remaining gate for this slice:

- This is profile ownership and boundary cleanup only. It does not initialize
  LSPlant for guest apps and does not prove QQ/WeChat/QQ Reader compatibility.
- Next engine slices should keep moving provider/service/broadcast/native
  runtime primitives behind `VirtualSystemServer` and replace in-process global
  state with recoverable runtime state.

## Execution Update - 2026-07-09 Engine Evidence Sink

This batch removes the app/container default dependency on
`EngineRuntimeRegistry.global` for operation evidence writes. The direction is
closer to the VirtualApp/BlackBox model: app Android stubs may collect local
facts and write files, but engine runtime truth is reached through an engine
service facade.

Implemented:

- Added `EngineOperationEvidenceSink` and
  `DefaultEngineOperationEvidenceSink` in `:core:engine`.
- The default sink routes operation evidence through
  `DefaultVirtualSystemServer(EngineRuntimeRegistry.global).runtimeService`
  inside `:core:engine`.
- `ContainerEngineEvidenceBridge` now depends on
  `EngineOperationEvidenceSink` / `EngineOperationEvidenceSinks.global`
  instead of defaulting to `EngineRuntimeRegistry.global`.
- Engine evidence export now uses the report returned by the sink, so app code
  no longer reads the global registry to fetch the updated report.
- Added engine tests for accepted/missing-runtime sink behavior and migrated app
  evidence bridge tests to explicit injected sinks.

Verification:

```powershell
git diff --check
rg -n "EngineRuntimeRegistry\.global|registry: EngineRuntimeRegistry =|import com\.multiapp\.core\.(loader|hook|xposed)\.|com\.multiapp\.core\.(loader|hook|xposed)\." app/src/main/java -S
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `git diff --check` passed with only Windows CRLF normalization warnings.
- The app/main direct global-registry and loader/hook/xposed scan returned no
  matches.
- The first Gradle run failed on a Kotlin smart-cast in
  `ContainerEngineEvidenceBridge`; this was fixed by assigning
  `result.report` to a local `report` before export.
- The second Gradle gate passed in `2m 7s`.

Remaining gate for this slice:

- `EngineOperationEvidenceSinks.global` still wraps the process global registry
  inside `:core:engine`; later work should bind this to a real durable/IPC
  system-server service instead of process-local singleton state.
- Hosted runtime bootstrap still uses `VirtualProcessRuntime.global` in engine
  adapter code, so process-death and cross-process recovery remain open.

## Execution Update - 2026-07-09 Hosted Process Runtime Facade

This batch moves hosted Application runtime reuse behind an engine facade.
The goal is to keep following the VirtualApp/BlackBox-style engine boundary:
Android stubs and loader primitives may still exist, but process runtime truth
should be reached through `:core:engine` rather than scattered direct global
lookups.

Implemented:

- Added `EngineHostedProcessRuntime` and `DefaultEngineHostedProcessRuntime`
  in `:core:engine`.
- `DefaultHostedRuntimeEngine` now uses the engine process-runtime facade for
  reusable runtime lookup, Application binding, and early Application runtime
  publication.
- `HostedRuntimeBootstrap` now accepts an injected `runtimePublisher`; loader
  no longer hardcodes `VirtualProcessRuntime.global` inside
  `attachAndLaunch()`.
- `DefaultEngineServiceRouter` now checks reusable runtime through the engine
  facade instead of directly depending on `VirtualProcessRuntime.global`.
- Added focused tests for engine process-runtime reuse/bind behavior and loader
  bootstrap publisher injection.

Verification:

```powershell
git diff --check
rg -n "VirtualProcessRuntime\.global" core/engine/src/main/java core/loader/src/main/java app/src/main/java -S
.\gradlew.bat :core:engine:testDebugUnitTest :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `git diff --check` passed with only Windows CRLF normalization warnings.
- A single full local gate with model/engine/loader/app test plus app assemble
  exceeded the local 180s command timeout before returning a Gradle failure
  stack.
- The affected-module split gate passed:
  `:core:engine:testDebugUnitTest :core:loader:testDebugUnitTest` in 25s.
- The app gate passed:
  `:app:testDebugUnitTest :app:assembleDebug` in 1m 10s.
- Known warnings remain: AGP `8.7.3` compileSdk 36 compatibility warning and
  `generateLoaderDex` invalid locals warnings from dependency class metadata.

Remaining gate for this slice:

- `DefaultEngineHostedProcessRuntime` still wraps `VirtualProcessRuntime.global`
  inside `:core:engine`; this is a containment step, not the final durable/IPC
  process runtime.
- Loader still has direct `VirtualProcessRuntime.global` usage in legacy
  instrumentation, launch-record patching, Provider/Service/AMS dispatchers.
  Those are separate migration slices and still block commercial readiness.

## Execution Update - 2026-07-09 Process Runtime Injection Path

This batch continues the hosted process runtime migration. The intent is not to
remove every legacy default in one patch, but to make the active engine and
guest Context paths carry one shared `VirtualProcessRuntime` explicitly.

Implemented:

- `DefaultEngineProviderDispatcher` and `DefaultEngineServiceDispatcher` now
  pass the engine-owned loader runtime into `VirtualProviderDispatcher` and
  `VirtualServiceDispatcher`.
- `HostedRuntimeBootstrap` passes the same process runtime into
  `ApplicationStage`.
- `ApplicationStage`, `GuestApplicationCreateRequest`, reflective Application
  context creation, `VirtualContextWrappers`, `VirtualContextWrapper`,
  `VirtualContextWrapperApi34`, and `VirtualContextWrapperApi36` now carry an
  injectable process runtime.
- `VirtualProviderDispatcher` and `VirtualServiceDispatcher` pass their
  process runtime into the guest `VirtualContextWrapper`.
- `DefaultVirtualAmsComponentDispatcher` already supported process-runtime
  injection; `VirtualContextWrapper` now forwards its injected runtime into the
  default dispatcher, so guest `startService()` / `bindService()` can reuse the
  same process slot/application state.
- `VirtualInstrumentation` and `HostedActivityContextInjector` now carry
  injectable process runtime instead of hardcoding lookup/bind calls inside
  hosted Activity context injection.
- `ActivityThreadLaunchRecordPatcher` now accepts process runtime parameters
  through its patch entry points and no longer does internal hard global
  lookups for guest classloader/processSlot.
- `VirtualInstrumentationInstaller` and `ActivityThreadLaunchCallbackInstaller`
  now accept and forward the process runtime; `EngineRuntimeInstallers` installs
  instrumentation with the engine-owned shared loader runtime.
- `VirtualInstrumentation.createHostedRuntime()` now wires
  `runtimePublisher = processRuntime::rememberApplication` so early Application
  publication and the foreground Activity path share the same runtime cache.
- Added a `VirtualAmsComponentDispatcherTest` case proving service remap uses
  the injected process runtime slot.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `:core:loader:testDebugUnitTest` passed in 1m 56s.
- Static scan found no remaining direct
  `VirtualProcessRuntime.global.get/bindApplication/reusableResult/rememberApplication`
  calls in `core/loader/src/main/java`, `core/engine/src/main/java`, or
  `app/src/main/java`.
- Known warnings remain: AGP `8.7.3` compileSdk 36 compatibility warning and
  existing deprecated Android API override/test warnings.

Remaining gate for this slice:

- Several loader classes still keep `VirtualProcessRuntime.global` as a
  compatibility default parameter. The active engine path now passes a shared
  runtime explicitly, but the old default entry points still need later
  deprecation or ownership by `:core:engine`.
- `ActivityThreadLaunchRecordPatcher` and several loader entry points still
  expose `VirtualProcessRuntime.global` as compatibility defaults. A later
  engine installer facade should remove or hide those defaults behind a loader
  owned narrow interface.
