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

## Execution Update - 2026-07-09 Core Model Android Boundary Slice

This batch starts shrinking the `:core:model` Android framework surface without
touching the active proxy/token dirty work. The goal is to move toward a pure
contract/model module while avoiding a broad UI/repository migration in the
same patch.

Implemented:

- Removed `android.graphics.drawable.Drawable` from `ApkInfo`.
- Removed APK icon loading from `ApkParser` when constructing `ApkInfo`; icon
  materialization should remain in Android/UI/repository layers instead of the
  model DTO.

Verification:

```powershell
rg -n "^import android\.|android\." core/model/src/main/java -S
.\gradlew.bat :core:model:testDebugUnitTest :core:apk:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `ApkInfo` no longer imports or exposes `android.graphics.drawable.Drawable`.
- `:core:model:testDebugUnitTest :core:apk:compileDebugKotlin` passed in 32s.
- Known warnings remain: AGP `8.7.3` compileSdk 36 compatibility warning and
  an existing deprecated `Bundle.get(String)` warning in `ApkParser`.

Remaining gate for this slice:

- `VirtualApp.icon`, `ProcessSlot`, `VirtualContextFactory`, and
  `VirtualActivityController` still expose Android framework types in
  `:core:model`. Those need separate migration into Android-facing adapter
  modules or narrow pure contracts.

## Execution Update - 2026-07-10 Core Model Pure Contract Follow-up

This batch continues the `:core:model` Android boundary cleanup after the
previous APK DTO slice. It keeps the active app/container proxy-token and
loader dirty work untouched and focuses on model contracts that were called out
by the BLOCK review.

Implemented:

- Removed `VirtualApp.icon` and the `android.graphics.drawable.Drawable`
  dependency from `:core:model`.
- Moved app-list icon materialization to the Android UI/repository edge:
  `InstalledAppRepository` no longer loads icons into model DTOs, while the
  launcher UI resolves installed-package or archive APK icons when rendering.
- Replaced Android-facing `VirtualContextFactory` signatures with a pure
  `VirtualContextSpec` generated from `VirtualContextConfig`.
- Replaced `VirtualActivityController.launchGuestActivity(...)` Android
  `Activity` input with a pure `GuestActivityLaunchRequest` /
  `planGuestActivityLaunch(...)` contract.
- Removed the Android `Activity`, `Context`, `ContextWrapper`, `Bundle`,
  `ApplicationInfo`, and `Log` dependencies from
  `DefaultVirtualActivityController`; the class now only validates/describes
  launch plans and leaves real Activity creation to engine/loader adapters.
- Replaced Android framework types in `GmsServiceRouter` with pure DTOs:
  `VirtualServiceIntentSpec`, `VirtualBinderHandle`,
  `VirtualPackageIdentity`, and `VirtualAccountRecord`.
- Added `CoreModelAndroidBoundaryTest` so `:core:model` main sources cannot
  reintroduce Android framework imports or typed references without failing the
  local gate.

Verification:

```powershell
rg -n "^import android\.|android\." core/model/src/main/java -S
rg -n "^import android\.|android\.app\.|android\.content\.Context|android\.content\.Intent|android\.os\.IBinder|android\.content\.pm\.|android\.accounts\." core/model/src/test/java -S
.\gradlew.bat :core:model:testDebugUnitTest :core:instance:testDebugUnitTest :feature:launcher:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:model:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `:core:model:testDebugUnitTest`, `:core:instance:testDebugUnitTest`, and
  `:feature:launcher:compileDebugKotlin` passed.
- `:app:compileDebugKotlin` passed.
- `:core:model:testDebugUnitTest` passed again after adding the source
  boundary regression test.
- `core/model/src/main/java` no longer has Android framework imports or typed
  Android framework references. Remaining broad `android.*` hits are manifest
  action string constants.

Remaining gate for this slice:

- `:core:model` is still an Android Gradle library and still depends on
  Android build tooling, even though the main source Android framework surface
  has been narrowed. A later build-structure slice should convert it toward a
  pure JVM/Kotlin contract module after downstream Android modules no longer
  require Android-library packaging.

## Execution Update - 2026-07-10 VirtualSystemServer Runtime-Bound Subservices

This batch follows the VirtualApp/BlackBox server-side split without touching
the active app/container or loader dirty files. The goal is to make engine
subservices answer runtime state explicitly instead of staying as empty marker
interfaces.

Implemented:

- Added `VirtualRuntimeBoundSubsystemService` and
  `VirtualSubsystemRuntimeBinding`.
- `VirtualActivityService`, `VirtualProviderService`, `VirtualServiceService`,
  `VirtualBroadcastService`, `VirtualStorageService`, and
  `VirtualNativeService` now expose `queryRuntimeBinding(instanceId)`.
- `DefaultVirtualSystemServer` now wires registry-backed subsystem services
  instead of static empty singleton services.
- Runtime-bound subsystem responses include `processSlot`, `proxySlot`,
  `runtimeEpoch`, process identity, runtime state, supported operations, and
  unsupported operations.
- Component services fail closed with `FAIL/runtime_not_found` when no runtime
  exists, and return `PARTIAL` for known runtimes to avoid overstating
  incomplete Activity/Provider/Service/Broadcast/Storage/Native semantics.
- `VirtualEvidenceService` now exposes `exportReport(instanceId)` through the
  engine runtime service.

Verification:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `:core:model` + `:core:engine` unit tests passed after adding
  `VirtualSystemServerTest` coverage for subsystem bindings, fail-closed
  missing runtime behavior, and evidence export.
- `:app:compileDebugKotlin` passed, proving the public engine changes did not
  break current app compile.

Remaining gate for this slice:

- These are engine service contracts and state queries only. They do not yet
  implement full VAMS/VProvider/VService/VBroadcast semantics, do not replace
  loader/app dispatch paths, and are not device proof.

## Execution Update - 2026-07-10 VPMS Snapshot Query Semantics

This batch continues the engine-owned VPMS facade. It keeps the implementation
snapshot-backed and does not claim full Android `PackageManager` parity yet.

Implemented:

- Extended resolved intent filters with MIME types, authorities, exact paths,
  and priority.
- Extended resolved provider/component metadata with read/write permissions.
- `VirtualPackageService.resolveIntent(...)` now evaluates action, categories,
  scheme, MIME exact/wildcard match, authority, path, and returns higher
  priority filters first with stable class-name tie breaking.
- `EngineRuntimeStateStore` persists/restores the new filter and provider
  permission fields while keeping older state files compatible.
- Added focused VPMS tests for persisted metadata, MIME wildcard matching,
  authority/path matching, and priority ordering.

Verification:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `:core:model:testDebugUnitTest :core:engine:testDebugUnitTest` passed before
  this documentation update.
- `:app:compileDebugKotlin` passed before this documentation update.

Remaining gate for this slice:

- Parser coverage is still incomplete. The engine can now store/query these
  fields when present, but APK/manifest parsing still needs to populate all
  Android-equivalent `IntentFilter`, signature, `SigningInfo`, and typed
  metadata fields before VPMS can be treated as commercial complete.

## Execution Update - 2026-07-10 Native Runtime Evidence Narrowing

This batch reduces the PR-10 native/storage evidence blind spot. It does not
enable hook profiles or claim QQ/WeChat/QQ Reader native compatibility.

Implemented:

- Added `EngineNativeRuntimeEvidence` behind
  `EngineStorageDiagnosticsFacade`.
- Native storage diagnostics now derive `namespaceVerdict`,
  `findLibraryVerdict`, and `nativeLoadVerdict` from hosted bootstrap evidence:
  native library count/list, native library dir, classloader native search path,
  and `guestClassLoader.findLibrary(...)`.
- Apps with no guest native libraries now report explicit `UNSUPPORTED /
  NO_GUEST_NATIVE_LIBRARIES` instead of `UNKNOWN`.
- If `findLibrary(...)` resolves a guest `.so`, evidence reports
  `findLibraryVerdict=PASS`, `namespaceVerdict=PARTIAL`, and
  `nativeLoadVerdict=PARTIAL` because storage diagnostics still do not execute
  a real `Runtime.nativeLoad` probe.
- Android instrumentation storage evidence now asserts these native runtime
  verdict fields are no longer `UNKNOWN` for a complete hosted bootstrap.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineStorageDiagnosticsEvidenceTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `EngineStorageDiagnosticsEvidenceTest` passed.

Remaining gate for this slice:

- `nativeLoadVerdict=PARTIAL` is intentional until a controlled device/native
  load probe exists. `namespaceVerdict=PARTIAL` is also not proof of linker
  namespace execution; it only proves the classloader search path and
  `findLibrary` layer are coherent enough to move past an `UNKNOWN` result.

## Execution Update - 2026-07-10 Activity Proxy Recovery Evidence

This batch follows the VirtualApp/BlackBox/DroidPlugin pattern that Activity
state must be owned by a central virtual activity layer, while real Android
components remain stub/proxy carriers. It tightens the process-death/recents
recovery path without touching the broader `ActivityThread` patcher.

Implemented:

- `EngineProxyActivityRecords.observeProxyIntent(...)` now returns task
  identity evidence for the observed/recovered record: `taskId`,
  `taskAffinity`, `launchMode`, and `intentFlags`.
- Proxy Activity recovery now preserves launch flags from the original guest
  intent when available, and falls back to current proxy intent flags after
  process death. This prevents recents restore from silently dropping
  `FLAG_ACTIVITY_NEW_TASK` when the in-memory `VirtualActivityIntentStore` is
  gone.
- `ProxyActivityEvidence` now exports the task and launch-mode fields into
  `files/hosted_launch_evidence/*activity-proxy.properties`.
- `VirtualSystemServer.activityService` now advertises
  `proxy-process-death-recovery-evidence` as supported while keeping
  `recents-device-proof` unsupported until device evidence exists.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:testDebugUnitTest --tests "com.multiapp.app.container.ProxyActivityEvidenceTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused engine Activity runtime, system-server, and app proxy evidence tests
  passed.

Remaining gate for this slice:

- This is still JVM/local evidence. Same-origin dual-instance recents and
  process-death recovery must still be validated on device with
  `dumpsys activity recents`, `exit-info`, and hosted launch evidence before
  it can be marked `PASS`.

## Execution Update - 2026-07-10 Activity Task State Persistence Foundation

This batch starts moving Activity task truth from process-only memory toward an
engine-owned recoverable state model. It follows the same VirtualApp/BlackBox
shape as the previous proxy evidence slice: the Android Activity remains a
stub/proxy carrier, while the virtual task/back-stack record is owned by the
container runtime.

Implemented:

- `VirtualActivityStack` can restore a task snapshot and rebuild `nextTaskId`
  / `nextEventId` while filtering `FINISHED`, `DESTROYED`, and duplicate
  records.
- `VirtualActivityRecordManager` now exposes `exportTasks()` and
  `restoreTasks(...)`, rebuilding token, proxy-class, and activity-id lookup
  indexes from the restored stack.
- Added `EngineActivityTaskStateSnapshot` plus in-memory and file-backed
  `EngineActivityTaskStateStore` implementations in `:core:engine`.
- Added `EngineActivityTaskRecords` as the engine facade for
  snapshot/persist/restore so later app/container wiring does not need to call
  loader globals directly.
- `ContainerRuntimePaths` now owns
  `engine_activity_task_state.properties`.
- `ContainerActivity` restores persisted task state before proxy launch/prune
  and persists the task snapshot after slot prune and after successful proxy
  launch.
- `ProxyActivityBase` restores persisted task state only when the current
  in-memory manager is empty, avoiding a hot-path race where stale disk state
  could overwrite a just-registered proxy record. It persists the snapshot
  after observing/recovering the proxy intent.
- Activity lifecycle now feeds the engine task facade: `onResume`, `onPause`,
  and `onStop` update the virtual record state and persist the snapshot;
  `onDestroy` removes the record from the task snapshot only when
  `isFinishing=true`, otherwise it keeps the task recoverable as `STOPPED`.
- `VirtualActivityService.queryTaskState(instanceId)` now exposes the
  persisted virtual task snapshot through `VirtualSystemServer`, including
  `taskCount`, `activityCount`, top task/activity identity, top Activity state,
  and filtered task records for the requested instance only.
- `VirtualEvidenceService.exportReport(instanceId)` now adds
  `operationEvidence.activity.task-state` so exported engine reports can show
  task-state query verdicts and counts without marking full recents semantics
  as complete.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerRuntimePathsTest" --tests "com.multiapp.app.container.ProxyActivityEvidenceTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerRuntimePathsTest" --tests "com.multiapp.app.container.ProxyActivityEvidenceTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.DefaultVirtualizationEngineTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused loader Activity record manager and engine task-state tests passed.
- App path test/compile gate passed after wiring the file-backed task-state
  store into `ContainerActivity` and `ProxyActivityBase`.
- Lifecycle state update tests and app compile gate passed after adding
  `resume/pause/stop/destroy` task-state persistence.
- `VirtualSystemServerTest`, `EngineActivityTaskStateStoreTest`,
  `DefaultVirtualizationEngineTest`, and `:app:compileDebugKotlin` passed after
  exposing task snapshots through `VirtualActivityService` and engine-report
  operation evidence.

Remaining gate for this slice:

- This is now wired into the app/container path, but is not device proof.
  Device evidence still must prove same-origin multi-instance recents,
  process-death recovery, and no black screen before `recents-device-proof`
  can move out of `UNSUPPORTED`.

## Execution Update - 2026-07-10 Activity Control-Plane Operations

This batch continues the Activity task-state work by moving local record
operations behind the engine-facing `VirtualActivityService` facade. The goal
is to stop treating finish/result/onNewIntent as loose loader-only helpers and
make them explicit engine control-plane operations with instance ownership
checks.

Implemented:

- `VirtualActivityService` now exposes persisted-record operations:
  `markActivityState(...)`, `finishActivity(...)`,
  `setActivityResult(...)`, `consumeActivityResult(...)`, and
  `consumePendingNewIntent(...)`.
- `RegistryBackedVirtualActivityService` restores the persisted task snapshot
  into a temporary `VirtualActivityRecordManager`, verifies the runtime exists,
  verifies the token belongs to the requested `instanceId`, mutates the record,
  then writes the task snapshot back through `EngineActivityTaskStateStore`.
- Cross-instance token use fails closed with
  `activity_record_instance_mismatch:<token>` and does not mutate the other
  instance's persisted state.
- `DefaultVirtualizationEngine.exportEvidence(instanceId)` now routes through
  `VirtualEvidenceService.exportReport(...)` first, so exported engine reports
  include `operationEvidence.activity.task-state` instead of returning only the
  raw runtime-registry report.
- Added `EngineActivityTaskController` and `EngineActivityTaskControllers` as
  the app/container-facing Activity task facade. The controller attaches the
  file-backed engine runtime state, uses `VirtualActivityService` for
  lifecycle mutations, and keeps task snapshot restore/persist inside
  `:core:engine`.
- `ContainerActivity` and `ProxyActivityBase` now use the controller instead of
  directly constructing `EngineActivityTaskRecords` or
  `FileBackedEngineActivityTaskStateStore`.
- `RegistryBackedVirtualActivityService` now uses the shared hosted
  `VirtualActivityRecordManager` and only restores persisted task state when
  the requested token is missing, avoiding unconditional replacement of hot
  process Activity records.
- Added loader-level `VirtualActivityOperations` so `VirtualInstrumentation`
  can consume pending `onNewIntent` records and finish Activity records through
  an injected operation facade instead of hard-coding direct manager calls.
- `EngineRuntimeInstallers.installInstrumentation()` now injects
  `EngineVirtualActivityOperationsFactory.hotPath()`. The engine operation path
  delegates to `VirtualActivityService` first and falls back to the local
  manager when no runtime record is available, preserving existing direct
  instrumentation install tests while moving the normal app path toward engine
  ownership.
- Activity binding supported operations now include precise local capabilities:
  `finish-record`, `result-record`, `on-new-intent-record`, and
  `back-stack-state`.
- The unsupported set keeps delivery/device claims separate:
  `result-delivery`, `finish-result-delivery`, and `recents-device-proof`
  remain unsupported until app/container and device evidence prove them.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.DefaultVirtualizationEngineTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `VirtualSystemServerTest` and `EngineActivityTaskStateStoreTest`
  passed.
- Focused `DefaultVirtualizationEngineTest`, `VirtualSystemServerTest`, and
  `EngineActivityTaskStateStoreTest` passed after the export-evidence bridge.
- Focused `EngineActivityTaskStateStoreTest`, `VirtualSystemServerTest`, and
  `:app:compileDebugKotlin` passed after the app/container controller wiring.
- Focused `VirtualInstrumentationStartActivityEvidenceTest`,
  `EngineActivityTaskStateStoreTest`, `VirtualSystemServerTest`, and
  `:app:compileDebugKotlin` passed after instrumentation operation injection.

Remaining gate for this slice:

- These are engine/JVM control-plane operations only. The app/container
  delivery path still must route real Android `setResult`, finish/result
  propagation, and `onNewIntent` delivery through the engine facade before
  claiming Android Activity parity. The lifecycle task-state path is now routed
  through the engine controller, and `VirtualInstrumentation` uses an injected
  operation facade for pending new-intent consumption and finish marking.
  Activity result delivery and lower-level launch/remap records still keep
  direct loader dependencies.
- Device evidence is still required before same-origin multi-instance recents,
  process-death restore, and black-screen avoidance can move to `PASS`.

## Execution Update - 2026-07-10 Activity Result Route Foundation

This batch continues the Activity control-plane slice by recording
`startActivityForResult` route metadata in the virtual Activity record instead
of only writing an `UNSUPPORTED` evidence string.

Implemented:

- `VirtualActivityRecord` now carries `resultToToken` and `resultRequestCode`.
- `VirtualActivityLaunchRequest` can carry the source Activity result route.
- `VirtualInstrumentation.remapStartActivityIntent(...)` records the source
  hosted Activity token and `requestCode` when `requestCode >= 0`.
- Remap evidence now reports `activityResultVerdict=PARTIAL` with
  `HOST_PROXY_RESULT_ROUTE_RECORDED_DELIVERY_PENDING` when the result route is
  recorded, rather than claiming full delivery.
- `VirtualActivityManager.allocateGuestActivity(...)` persists the route into
  the launch record, and proxy launch intents carry the route for device-path
  recovery.
- `VirtualActivityStack` refreshes route metadata when `singleTop`,
  `singleTask`, or `CLEAR_TOP` reuse an existing Activity record, avoiding
  stale parent result routes.
- `EngineActivityTaskStateStore` persists and restores result route metadata.
- Loader-level `VirtualActivityOperations` now exposes
  `setActivityResult(...)` and `consumeActivityResult(...)`, and
  `EngineVirtualActivityOperations` delegates those calls through
  `VirtualActivityService` first.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --tests "com.multiapp.core.loader.VirtualActivityManagerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused loader remap/Activity manager tests passed.
- Focused engine task-state/system-server tests and `:app:compileDebugKotlin`
  passed.

Remaining gate for this slice:

- This is still not full Android Activity result delivery. The next Activity
  slice must capture guest `setResult(...)` on finish, write it to the source
  token through the engine facade, and prove `onActivityResult` delivery with
  Android/device evidence before `result-delivery` or `finish-result-delivery`
  can move out of `UNSUPPORTED`.

## Execution Update - 2026-07-10 Activity Finish Result Record Foundation

This batch follows the VirtualApp-style finish path more closely: the proxy
runtime reads the finishing Activity's result code/data and records that result
against the source Activity token through the injected operation facade. It
does not yet claim framework callback delivery.

Implemented:

- Added `VirtualActivityOperations.recordActivityResultForFinish(...)`.
- `ManagerBackedVirtualActivityOperations` resolves the finishing child
  record, verifies `resultToToken/resultRequestCode`, verifies the target token
  belongs to the same instance, and writes the virtual result to the source
  record.
- `EngineVirtualActivityOperations` first attempts the same write through
  `VirtualActivityService.setActivityResult(...)` and falls back to the local
  manager when persisted task-state does not yet contain the hot record.
- `VirtualInstrumentation.markActivityFinishedIfNeeded(...)` now reads
  `Activity.mResultCode` and `Activity.mResultData` before finishing the child
  record, then calls the operation facade to record the result.
- Added `ACTIVITY_FINISH_RESULT` evidence entries with source token redaction,
  request code, result code, record verdict, and data URI redaction.

Open-source precedent checked:

- VirtualApp `VActivityManager.startActivity(...)` passes `resultTo`,
  `resultWho`, and `requestCode` into its virtual Activity manager.
- VirtualApp `VActivityManager.finishActivity(...)` reads `mResultCode` and
  `mResultData` before ending the Activity.
- VirtualApp still has a separate `sendActivityResult(...)` path, which maps to
  MultiApp's remaining `onActivityResult` delivery gap.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --tests "com.multiapp.core.loader.VirtualActivityManagerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused loader instrumentation/Activity manager tests passed.
- Focused loader record-manager tests, engine Activity runtime/system-server
  tests, and `:app:compileDebugKotlin` passed.

Remaining gate for this slice:

- This is still not full Android result callback delivery. The next Activity
  slice must consume the source record and prove `onActivityResult` delivery.
- `result-delivery` and `finish-result-delivery` stay `UNSUPPORTED` until that
  callback bridge has device evidence.

## Execution Update - 2026-07-10 ActivityThread Result Dispatch Bridge

This batch closes the next code-level step in the VirtualApp-style Activity
result chain: recorded virtual results can now be scheduled back to the source
Activity through Android's `ActivityThread.sendActivityResult(...)` hidden
bridge, with a conservative source-`onResume` fallback when the framework
bridge was not invoked. This is still intentionally recorded as `PARTIAL` until
device evidence proves the framework callback reaches guest
`onActivityResult(...)`.

Implemented:

- Added `ActivityThreadCompat.sendActivityResult(...)`, a fail-closed
  reflection bridge for the AOSP
  `ActivityThread.sendActivityResult(IBinder, String, int, int, Intent)` path.
- `VirtualInstrumentation` now keeps a process-local map from virtual Activity
  token to framework `Activity.mToken` on `onCreate` / `onResume`, and removes
  it on destroy.
- When a child Activity finishes, `VirtualInstrumentation` records the virtual
  result first, then attempts the `ActivityThread` dispatch to the source
  Activity token if the source framework token is available.
- `ACTIVITY_FINISH_RESULT` evidence now includes
  `activityThreadSendActivityResultVerdict`,
  `activityThreadSendActivityResultAttempted`,
  `activityThreadSendActivityResultInvoked`, method, reason, and error class.
- Added focused JVM tests for the `ActivityThreadCompat` bridge and the
  skip-evidence path when the source framework token is not available.
- `VirtualActivityResult` now preserves `requestCode`, optional `resultWho`,
  and framework-dispatch attempted/invoked state, so resume fallback does not
  lose the information needed to call `onActivityResult(...)`.
- `EngineActivityTaskStateStore` persists the new result metadata while keeping
  older state files compatible.
- `VirtualInstrumentation.callActivityOnResume(...)` now consumes a pending
  result only when `frameworkDispatchInvoked=false`, then calls the same
  base-instrumentation result callback bridge and writes
  `ACTIVITY_RESULT_RESUME_FALLBACK` evidence. If the `ActivityThread` bridge
  was invoked, fallback leaves the result pending for the framework callback
  path instead of risking duplicate delivery.

Open-source / platform alignment:

- AOSP `ActivityThread` exposes the hidden `sendActivityResult(...)` scheduling
  path used for Activity result callback delivery.
- VirtualApp uses the same concept in `VActivityManager.sendActivityResult(...)`
  and its AMS start-activity proxy records `resultTo/resultWho/requestCode`.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ActivityThreadCompatTest" --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ActivityThreadCompatTest" --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused loader bridge and instrumentation evidence tests passed.
- Focused loader Activity record-manager tests, engine task-state/runtime/
  system-server tests, and `:app:compileDebugKotlin` passed.

Remaining gate for this slice:

- `result-delivery` and `finish-result-delivery` must remain unsupported in the
  engine capability matrix until device evidence shows either
  `activityThreadSendActivityResultVerdict=PARTIAL` followed by
  `ACTIVITY_RESULT_DELIVERY`, or `ACTIVITY_RESULT_RESUME_FALLBACK`, and also
  confirms the source guest Activity callback was actually invoked.
- The current virtual-token-to-framework-token map is process-local. Process
  death and cross-process Activity result routes still need a durable/IPC route
  before this can be treated as commercial-grade Activity result semantics.

## Execution Update - 2026-07-10 App Container Engine Boundary

This batch advances the P0-A architecture boundary: production `:app` container
code no longer directly imports or declares implementation dependencies on the
loader/hook/identity runtime primitives for Provider route-token validation,
AMS/Broadcast evidence recorder installation, and hosted bootstrap result
summaries. These paths now go through `:core:engine` DTOs/facades.

Implemented:

- Added `EngineProviderRouteTokenGate` in `:core:engine`.
  `StubContentProvider` now validates route tokens and canonicalizes proxy URIs
  through the engine facade instead of importing `ProviderRouteTokenRegistry`
  from `:core:identity`.
- Removed the app-local `ProviderRouteTokenGate`.
- Strengthened `EngineBoundaryTest` to fail on new production app imports or
  fully-qualified usage of `core.loader`, `core.hook`, `core.xposed`, or
  `core.identity` runtime APIs.
- Removed `:core:loader`, `:core:hook`, and `:core:identity` from `app`
  `implementation` dependencies. Unit/android tests keep explicit test-scoped
  dependencies where they still construct legacy fixtures.
- Converted engine-exposed hosted bootstrap summary and native diagnostics into
  engine DTOs (`EngineBootstrapSummary`, `EngineNativeDiagnosticsSummary`) so
  app production compile no longer needs loader/hook DTOs just to consume
  `EngineHostedBootstrapResult`.
- Made `EngineAmsApiEvidenceRecorder` and `EngineBroadcastRecorder` pure engine
  interfaces. `EngineRuntimeInstallers` adapts them to loader recorder
  interfaces internally.
- Hid loader-backed `VirtualActivityRecordManager` constructor parameters from
  the app-facing constructors of `EngineActivityProxyLauncher`,
  `EngineProxyActivityRecords`, and `EngineActivityTaskRecords`.

Verification:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --tests "com.multiapp.app.container.StubContentProviderRouteTokenTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineHostedBootstrapResultTest" --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ActivityThreadCompatTest" --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `:app:compileDebugKotlin` passed without app production implementation
  dependencies on `:core:loader`, `:core:hook`, or `:core:identity`.
- Focused app boundary/provider-token tests passed.
- Focused engine hosted-bootstrap/activity-runtime/task-state/system-server
  tests and focused loader Activity result tests passed.

Remaining gate for this slice:

- This does not close the commercial engine BLOCK. Tests still use some legacy
  loader fixtures, and device evidence is still required for LoadedApk,
  Provider preinstall, Activity result delivery, recents, Service/Broadcast,
  and native behavior.
- `:app` still has real Android stub/proxy components by design; those
  components should continue calling engine facades only.

## Execution Update - 2026-07-10 Broadcast Engine Route Foundation

This batch starts moving Broadcast from loader-only process-local dispatch
toward the engine-owned `VirtualSystemServer` route model used by
VirtualApp/BlackBox-style runtimes. It does not instantiate guest receivers or
claim ordered/sticky/result semantics.

Implemented:

- Extended `VirtualBroadcastService` with engine-facing broadcast route and
  dispatch evidence APIs:
  - `planBroadcast(instanceId, VirtualBroadcastDispatchPlanRequest)`
  - `recordBroadcastDispatch(instanceId, VirtualBroadcastOperationResult)`
- Added pure engine DTOs for broadcast route planning and dispatch evidence,
  keeping loader `VirtualBroadcastResult` types out of app/feature contracts.
- `RegistryBackedVirtualBroadcastService` now resolves explicit receivers and
  implicit manifest receivers from `VirtualPackageSnapshot` through
  `VirtualPackageService`, preserving action/category/data matching and
  manifest priority ordering.
- Broadcast route plans include `processSlot`, optional component process
  name, target receivers, verdict, supported operations, unsupported
  operations, and engine operation evidence.
- Ordered, sticky, result-receiver, and abort semantics return explicit
  `UNSUPPORTED` instead of being hidden behind a best-effort dispatch path.
- Missing runtime and missing explicit receiver fail closed.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `VirtualSystemServerTest` passed, including missing-runtime
  fail-closed behavior, explicit receiver route planning, implicit receiver
  priority ordering, unsupported ordered/sticky/result/abort semantics, and
  dispatch evidence recording.

Remaining gate for this slice:

- This is route planning and evidence only. Real `BroadcastReceiver.onReceive`,
  PendingResult lifecycle, ordered broadcast chain, sticky cache,
  permission checks, result callbacks, abort/result extras, cross-process
  receiver routing, and device evidence remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Provider Authorization and Baseline Resolver

Provider execution now has a caller-aware authorization gate and an API 29+
baseline resolver path that does not require LSPlant for normal data-plane
operations.

Implemented:

- Route-token validation now obtains `callerInstanceId` from the unguessable
  token while validating target instance, authority, operation, expiry, and
  process slot. It no longer infers the caller from URI parameters.
- `StubContentProvider` captures the original Provider Binder UID/PID before
  nested engine calls. Provider plan IPC carries caller/target instance,
  original UID/PID, host UID, caller slot, access mode, and token-verification
  state; the engine endpoint independently records its own Binder UID/PID.
- Engine authorization rejects missing/unverified tokens, target/caller
  mismatch, external UID, known PID mismatch, caller-slot mismatch, and
  cross-instance access to non-exported providers.
- Query/read, mutation/write, and file-mode (`r` versus `w/rw`) permission
  requirements are evaluated separately. Same-instance access follows Android
  self-access behavior. Cross-instance permission/AppOps access remains
  fail-closed `UNSUPPORTED` until virtual permission and AppOps stores exist.
- Provider read/write permissions now remain visible in engine dispatch
  evidence instead of collapsing into one permission string.
- `VirtualContextWrapper.getContentResolver()` now uses an engine-installed
  factory. On API 29+, `ContentResolver.wrap(ContentProvider)` routes guest
  CRUD, call, canonicalization, and file operations through
  `EngineRoutingContentProvider -> EngineProviderDispatcher`.
- Non-guest authorities such as Settings, MediaStore, and Contacts are
  delegated to the host resolver, so the guest resolver does not swallow
  system providers.
- Added an engine-installed `IContentService` proxy for
  `registerContentObserver`, `notifyChange`, and unregister. Observer routes
  are stable across operations and intentionally omit expiring provider route
  tokens; all unrelated content-service methods pass through unchanged.
- The legacy LSPlant compatibility hook now uses the same stable observer URI,
  preventing register and notify from diverging because each operation issued
  a different token.

Verification:

```powershell
.\gradlew.bat :core:identity:compileDebugKotlin :core:engine:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.EngineProviderIpcServiceTest" :core:identity:testDebugUnitTest --tests "com.multiapp.core.identity.ContentProviderHookUriRewriteTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualContentServiceProxyTest" --tests "com.multiapp.core.loader.VirtualContextWrapperTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineVirtualContentResolverTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Identity, loader, engine, app compilation, caller authorization, stable
  observer routing, guest/system resolver split, and app boundary tests passed.

Remaining gate:

- API 28 still uses the existing fallback because `ContentResolver.wrap` was
  added in API 29.
- The hidden `ContentResolver.sContentService` proxy requires API 30-36 device
  evidence across AOSP and HyperOS; local reflection/unit tests are not device
  proof.
- At this point URI grant/revoke, cross-package authority ownership,
  `applyBatch`, `refresh`, and path policy were still open; later 2026-07-10
  updates below supersede those items. Persistable grants and custom Provider
  processes remain open.
- Observer behavior is `PARTIAL` until device evidence proves exact URI,
  descendant, self-change, flags, user, and process-death behavior.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Base/Public Resources and Provider Permission Fidelity

The package snapshot now keeps code and public resource paths distinct through
Activity identity, Context, Resources, and launch-record patching. Provider
permission facts are no longer collapsed into one synthetic string.

What changed:

- `publicSourceDir` and split public resource paths remain independent from
  code paths across `ApplicationInfo`, Resources, Activity identity, and
  launch-record patching.
- Provider `permission`, `readPermission`, and `writePermission` survive XML
  and PackageManager parsing, binary manifest generation, install-record JSON,
  snapshot creation, VPMS `ProviderInfo`, engine route targets, and policy
  evidence.
- When live manifest resolution is unavailable, snapshot creation falls back
  to persisted install-record components instead of returning empty component
  lists.
- Focused model, manifest, loader, engine, and app boundary tests passed.

### Still BLOCK

- Provider permission/AppOps enforcement, caller virtual UID ownership, URI
  grants, observers, and `notifyChange` remain incomplete.
- `isolatedSplits` remains explicit `UNSUPPORTED`; resource path fidelity does
  not implement isolated split dependency graphs.
- Device evidence is still required for split Resources and Provider access.

## Execution Update - 2026-07-10 Typed PMS Metadata and Signing Identity

Snapshot-backed PMS identity now owns typed manifest metadata and signer
digests instead of reducing metadata to strings and relying only on late
`LoaderFactory` signature patches.

What changed:

- A pure-model `VirtualMetaDataValue` preserves String, Boolean, Int, Long,
  Float, Double, and resource values.
- Application and component metadata survive Manifest parsing, PackageManager
  type enrichment, install-record JSON, package snapshots, and file-backed
  engine runtime restoration.
- VPMS converts typed values to corresponding Android `Bundle` value types;
  legacy string metadata remains a backward-compatible fallback.
- Install metadata records signer SHA-256 identity and multiple-signer state.
  Existing signer identity is retained if a later metadata refresh cannot read
  certificates.
- VPMS reparses the immutable snapshot APK for `signatures` and `signingInfo`,
  and rejects the result when its signer digest set does not match snapshot
  identity.
- Focused install-store, manifest, snapshot, signer-resolver, runtime-state,
  engine, app compile, and boundary tests passed.

This follows the AOSP package-manager shape: framework `PackageInfo` objects
are reconstructed at the Android boundary from engine-owned package truth.

### Still BLOCK

- VPMS does not yet own `checkSignatures`, `hasSigningCertificate`, shared UID,
  key-rotation capability checks, or installer signing identity.
- Signing scheme version and full lineage capabilities are recovered from the
  APK, not persisted as an engine-native signing model.
- Device gates must prove `GET_SIGNATURES`, `GET_SIGNING_CERTIFICATES`, typed
  Bundle values, and process-death restoration on API 28-36.
- This is not evidence that QQ, WeChat, QQ Reader, or protected apps are
  compatible.

## Execution Update - 2026-07-10 Service Engine Route Foundation

This batch applies the same engine-first route model to Service dispatch. It
keeps loader `VirtualServiceManager` / `VirtualServiceRuntime` as execution
primitives, but adds an engine-owned plan/result/evidence surface so app and
feature layers have a central `VirtualSystemServer` contract to consume.

Implemented:

- Extended `VirtualServiceService` with:
  - `planService(instanceId, VirtualServiceDispatchPlanRequest)`
  - `recordServiceDispatch(instanceId, VirtualServiceOperationResult)`
- Added pure engine DTOs for Service operation planning and dispatch evidence,
  without exposing loader `VirtualServiceDispatchResult` types.
- `RegistryBackedVirtualServiceService` now resolves explicit and implicit
  Service routes from `VirtualPackageSnapshot` through `VirtualPackageService`.
- Explicit Service names are normalized the same way as Android component
  names (`.SyncService`, `SyncService`, and fully-qualified names).
- Implicit Service routes use VPMS intent-filter matching and choose the
  highest-priority target, matching the single-target Service resolution shape.
- Service route plans include `processSlot`, optional service process name,
  operation, foreground flag, target service, verdict, supported operations,
  unsupported operations, and engine operation evidence.
- `BIND` / `UNBIND`, foreground service type mapping, and sticky restart
  semantics return explicit `UNSUPPORTED`.
- Missing runtime and missing Service target fail closed.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `VirtualSystemServerTest` passed, including Service missing-runtime
  fail-closed behavior, explicit start route planning, implicit priority
  resolution, explicit stop route planning, unsupported bind/foreground-type/
  sticky semantics, and dispatch evidence recording.

Remaining gate for this slice:

- This is route planning and evidence only. It does not yet prove real Android
  `startService`/`stopService`/`bindService` lifecycle parity, foreground
  notification/type mapping, sticky restart behavior, cross-process Service
  delivery, or device behavior.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Service Dispatcher Consumes Engine Plan

This batch wires the Service route foundation into the container dispatcher
path. `StubService` still calls the app-facing engine dispatcher, but the
dispatcher now uses `VirtualServiceService` as the gate before falling through
to loader execution primitives.

Implemented:

- `DefaultEngineServiceDispatcher` now calls
  `VirtualServiceService.planService(...)` before invoking loader
  `VirtualServiceDispatcher`.
- Engine plan verdicts now gate Service dispatch:
  - `PASS` / `PARTIAL`: proceed to loader execution.
  - `FAIL` with `runtime_not_found`: return `RuntimeNotBound` without loader
    dispatch.
  - Other `FAIL` or `UNSUPPORTED`: return an engine `Unsupported` dispatch
    result without loader dispatch.
- Loader execution results are converted back into
  `VirtualServiceOperationResult` and recorded through
  `VirtualServiceService.recordServiceDispatch(...)`.
- Added focused tests proving:
  - `DefaultEngineServiceDispatcher` records both `service/plan` and
    `service/dispatch` engine evidence.
  - Loader dispatch is not invoked when the engine plan fails.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `EngineContainerDispatchersTest` passed.

Remaining gate for this slice:

- This still uses loader primitives for actual guest Service execution. Real
  `bindService`, foreground notification/type mapping, sticky restart,
  started+bound destruction policy, cross-process Service routing, and device
  evidence remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Provider Engine Route Foundation

This batch starts moving Provider routing from app/loader-local dispatch into
the engine-owned `VirtualSystemServer` control plane. It is route planning and
evidence gating, not full Android Provider parity.

Implemented:

- `VirtualProviderService` now exposes:
  - `planProvider(instanceId, VirtualProviderDispatchPlanRequest)`
  - `recordProviderDispatch(instanceId, VirtualProviderOperationResult)`
- Added pure engine DTOs for Provider route targets, route plans, and dispatch
  operation evidence without exposing loader `VirtualProviderDispatchResult`
  types as engine service contracts.
- `RegistryBackedVirtualProviderService` resolves Provider authorities from
  `VirtualPackageSnapshot` through `VirtualPackageService`.
- Provider route plans carry `processSlot`, provider process name, authority,
  permission/readPermission/writePermission, grant-URI flag, route-token
  presence, supported operations, unsupported operations, and verdict.
- Missing runtime, process-slot mismatch, unknown operation, and missing
  Provider target fail closed or return explicit `UNSUPPORTED`.

## Execution Update - 2026-07-10 Provider Dispatcher Engine Gate

This batch wires the Provider route foundation into the container dispatcher
path. `StubContentProvider` still calls the app-facing engine dispatcher, but
the dispatcher now asks `VirtualProviderService` for a plan before falling
through to loader execution primitives.

Implemented:

- `EngineProviderDispatchRequest` now carries `operationName`, so engine
  evidence can distinguish `query`, `insert`, `openFile`, `notifyChange`, and
  other Provider operations.
- `DefaultEngineProviderDispatcher` now calls
  `VirtualProviderService.planProvider(...)` before invoking loader
  `VirtualProviderDispatcher`.
- Engine plan verdicts gate Provider dispatch:
  - `PASS` / `PARTIAL`: proceed to loader execution.
  - `FAIL` / `UNSUPPORTED`: fail closed without loader dispatch.
- Loader execution results are converted into
  `VirtualProviderOperationResult` and recorded through
  `VirtualProviderService.recordProviderDispatch(...)`.
- `StubContentProvider` passes the operation name into the engine dispatcher.
- Added focused tests proving:
  - Provider dispatcher records both `provider/plan` and `provider/dispatch`
    evidence.
  - Loader dispatch is not invoked when the engine plan fails.
  - Provider service covers missing runtime, authority route planning,
    process-slot mismatch, unknown operation, and dispatch evidence recording.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `VirtualSystemServerTest` and `EngineContainerDispatchersTest`
  passed.
- `:app:compileDebugKotlin` and `EngineBoundaryTest` passed.
- Existing AGP 8.7.3 / `compileSdk=36` compatibility warning remains.

Remaining gate for this slice:

- This still uses loader primitives for actual guest Provider creation and
  method execution after engine gating.
- URI grants, `ContentObserver`, `notifyChange`, custom Provider process
  delivery, permission enforcement, real return-value behavior, split Provider
  loading, and device evidence remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Broadcast Dispatcher Engine Gate

This batch wires the Broadcast route foundation into the actual hosted
Context dispatch path. `VirtualContextWrapper.sendBroadcast(...)` still lives
in `:core:loader`, but its default AMS dispatcher can now be replaced by an
engine-installed dispatcher factory without making loader depend on engine.

Implemented:

- Added `VirtualAmsComponentDispatchers` in `:core:loader` as a small
  installable dispatcher factory extension point.
- `VirtualContextWrapper` now builds the existing
  `DefaultVirtualAmsComponentDispatcher` as a fallback, then lets an installed
  factory wrap it.
- Added `DefaultEngineAmsComponentDispatcher` in `:core:engine`.
  - Activity and Service operations delegate to the fallback dispatcher for
    this slice.
  - Broadcast dispatch first calls `VirtualBroadcastService.planBroadcast(...)`.
  - `PASS` / `PARTIAL`: proceed to fallback loader dispatch.
  - `FAIL` / `UNSUPPORTED`: fail closed and do not call the fallback loader
    dispatch.
  - Loader Broadcast results are converted into
    `VirtualBroadcastOperationResult` and recorded through
    `VirtualBroadcastService.recordBroadcastDispatch(...)`.
- `EngineRuntimeInstallers.installAmsComponentDispatcher()` installs the
  engine-aware dispatcher factory during app startup.
- Added focused tests proving:
  - Broadcast dispatcher records both `broadcast/plan` and
    `broadcast/dispatch` engine evidence.
  - Fallback loader dispatch is not invoked when the engine plan fails.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused engine tests passed.
- `:app:compileDebugKotlin` and `EngineBoundaryTest` passed.
- Existing AGP 8.7.3 / `compileSdk=36` compatibility warning remains.

Remaining gate for this slice:

- This is still not complete `VBroadcast` parity. Ordered/sticky broadcasts,
  result receiver chains, permissions, abort/result extras, cross-process
  receiver process routing, Android `PendingResult` behavior, and device
  evidence remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Service AMS Dispatcher Engine Gate

This batch extends the engine-installed AMS dispatcher wrapper from Broadcast
to the guest Context Service path. It moves `startService`,
`startForegroundService`, and `bindService` decisions closer to the engine
service plan instead of letting the loader path make the first decision.

Implemented:

- `DefaultEngineAmsComponentDispatcher.resolveStartServiceIntent(...)` now
  builds a `VirtualServiceDispatchPlanRequest` from the source `Intent` and
  calls `VirtualServiceService.planService(...)` before using the loader
  fallback remapper.
- `START` / `START_FOREGROUND` plans gate service remapping:
  - `PASS` / `PARTIAL`: proceed to the fallback remapper that creates the host
    proxy Service intent.
  - `FAIL` / `UNSUPPORTED`: return `StartServiceMappingResult.Blocked`, record
    engine service dispatch evidence, and do not call the fallback remapper.
- `DefaultEngineAmsComponentDispatcher.dispatchBindService(...)` now calls
  `VirtualServiceService.planService(...)` with operation `BIND` before loader
  bind execution.
- Because `BIND` is still explicitly unsupported by the current engine service
  model, `bindService` now fails closed through the engine wrapper instead of
  silently invoking loader bind primitives.
- Added focused tests proving:
  - `startService` route planning happens before fallback remap.
  - `bindService` does not call loader fallback when the engine plan returns
    `UNSUPPORTED`.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused engine tests passed.

Remaining gate for this slice:

- `stopService` and `unbindService` are not fully engine-gated in this slice.
- Real started+bound Service lifecycle parity, foreground notification/type
  mapping, sticky restart, cross-process Service delivery, and device evidence
  remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Service Stop/Unbind AMS Dispatcher Follow-up

This follow-up closes the remaining obvious bypass in the engine-installed
AMS dispatcher wrapper: guest Context `stopService(...)` and
`unbindService(...)` now ask the engine service plan before loader fallback
execution.

Implemented:

- `DefaultEngineAmsComponentDispatcher.dispatchStopService(...)` now builds a
  `VirtualServiceDispatchPlanRequest(operation = STOP)` and calls
  `VirtualServiceService.planService(...)` before invoking loader stop
  dispatch.
- `STOP` plans gate loader dispatch:
  - `PASS` / `PARTIAL`: proceed to loader stop execution.
  - `FAIL` / `UNSUPPORTED`: fail closed, record engine service dispatch
    evidence, and do not call loader fallback.
- Loader stop results are converted into `VirtualServiceOperationResult` and
  recorded through `VirtualServiceService.recordServiceDispatch(...)`.
- `DefaultEngineAmsComponentDispatcher.dispatchUnbindService(...)` now calls
  `VirtualServiceService.planService(...)` with operation `UNBIND`.
- Because `UNBIND` is still part of the current unsupported bind-service
  semantics, the engine wrapper fails closed and records `UNSUPPORTED`
  dispatch evidence instead of silently invoking loader unbind primitives.
- `VirtualServiceOperationResult` now records `stopped` and `unbound` fields so
  Service dispatch evidence can distinguish start/bind from stop/unbind.
- Added focused tests proving:
  - `stopService` is planned through engine before loader fallback.
  - loader stop fallback is not called when the engine plan fails.
  - `unbindService` fails closed as `UNSUPPORTED` before loader fallback.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `EngineContainerDispatchersTest` passed.
- `:app:compileDebugKotlin`, `EngineBoundaryTest`, focused
  `EngineContainerDispatchersTest`, and focused `VirtualSystemServerTest`
  passed.
- Existing AGP 8.7.3 / `compileSdk=36` compatibility warning remains.

Remaining gate for this slice:

- `bindService` / `unbindService` remain deliberately `UNSUPPORTED` in the
  engine service model until a real bound-service lifecycle and connection
  state model is implemented.
- Loader primitives still execute actual guest Service lifecycle after engine
  gating.
- Foreground notification/type mapping, sticky restart, started+bound
  destruction policy, cross-process Service delivery, and device evidence
  remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Activity AMS Dispatcher Engine Gate

This slice wires guest Context `startActivity(...)` and
`startActivities(...)` through the engine-installed AMS dispatcher wrapper
before loader proxy-record allocation. It is a route-planning and evidence
gate, not a full VAMS/ATM implementation.

Implemented:

- Added `VirtualActivityService.planActivity(...)` and
  `recordActivityDispatch(...)`.
- Activity route planning is backed by `VirtualPackageSnapshot` through
  `VirtualPackageService`, so explicit and implicit Activity targets are
  checked by the central engine snapshot before loader fallback.
- Activity plans record target Activity, action, target package, launch flags,
  process slot, supported operations, unsupported operations, and verdict.
- `DefaultEngineAmsComponentDispatcher.resolveStartActivityIntent(...)` now
  calls `VirtualActivityService.planActivity(...)` before invoking loader
  fallback remap.
- `DefaultEngineAmsComponentDispatcher.resolveStartActivityIntents(...)` now
  plans the batch first and fails closed before loader fallback if any member
  cannot be planned.
- Loader remap/block results are converted into
  `VirtualActivityDispatchResult` and recorded as `activity/dispatch`
  evidence.
- Added focused tests proving:
  - `startActivity` is planned through engine before loader fallback remap.
  - loader fallback is not called when the engine Activity plan fails.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused `EngineContainerDispatchersTest` and focused
  `VirtualSystemServerTest` passed.
- `:app:compileDebugKotlin`, `EngineBoundaryTest`, focused
  `EngineContainerDispatchersTest`, and focused `VirtualSystemServerTest`
  passed.
- Existing AGP 8.7.3 / `compileSdk=36` compatibility warning remains.

Remaining gate for this slice:

- Loader primitives still allocate proxy records and create proxy intents
  after engine gating.
- Full `launchMode`, `taskAffinity`, `onNewIntent`, result delivery, finish,
  recents/task behavior, process-death recovery, and device evidence remain
  open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Activity Launch Task-State Sync

This follow-up removes a launch-time state split between loader proxy-record
allocation and the engine task-state store. After a successful Activity remap,
the engine now snapshots the shared hot Activity records immediately instead
of waiting for a later lifecycle callback or manual persistence step.

Implemented:

- Added `VirtualActivityService.syncActivityTaskState(instanceId, reason)`.
- The registry-backed implementation validates the active runtime, reads the
  shared `VirtualActivityRecordManager`, requires at least one record owned by
  the requested instance, and persists the complete task snapshot through
  `EngineActivityTaskStateStore`.
- `DefaultEngineAmsComponentDispatcher` calls task-state sync after successful
  `startActivity(...)` and `startActivities(...)` remaps.
- Failed or blocked Activity plans do not write an empty task snapshot.
- Added tests proving hot loader records become visible through engine
  `queryTaskState(...)` and successful remap invokes the sync path.

Verification:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `:app:compileDebugKotlin`, `EngineBoundaryTest`, focused
  `EngineContainerDispatchersTest`, and focused `VirtualSystemServerTest`
  passed.

Remaining gate for this slice:

- Loader still allocates the proxy slot and creates the initial hot Activity
  record; this follow-up synchronizes that result into engine-owned durable
  state but does not yet make engine ActivityStack the allocator.
- Same-origin multi-instance recents, process-death task restoration, and full
  Android task/result behavior still require device evidence.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Multi-Instance Activity Task Merge

This slice hardens the Activity task-state store for same-origin instances in
different virtual process slots. A process-local task snapshot can no longer
replace the complete persisted snapshot and silently remove sibling-instance
tasks.

Implemented:

- Added `EngineActivityTaskStateStore.mergeInstance(...)`.
- Instance merge removes and replaces only records owned by the requested
  instance while preserving sibling-instance activities and tasks.
- Tasks sharing the same task id and affinity are merged without mixing
  unrelated instance records.
- `FileBackedEngineActivityTaskStateStore` now serializes save/load/merge/clear
  with a shared in-process monitor and an OS file lock for virtual-process
  coordination.
- Activity launch sync, lifecycle mutations, result consumption, pending
  `onNewIntent` consumption, and `EngineActivityTaskController.persist(...)`
  now use instance-level merge instead of whole-file replacement.
- Added regression tests with independent file-store objects proving an update
  for instance A preserves instance B and replaces only A's previous record.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused Activity store/server/dispatcher tests passed.
- `:app:compileDebugKotlin` and `EngineBoundaryTest` passed.

Remaining gate for this slice:

- File locking and instance merge protect the persisted model, but Android
  recents separation still depends on real proxy components, task affinity,
  document/task flags, and device behavior.
- Multi-process kill/restart concurrency still needs device stress evidence.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 File-Backed Activity Hot Path Wiring

The app startup hot path now uses the same file-backed engine runtime and
Activity task stores as `DefaultVirtualizationEngine`. This fixes an internal
configuration split where Activity route dispatch used an in-memory task store
while engine launch/evidence used the durable file store.

Implemented:

- `EngineRuntimeInstallers.installAmsComponentDispatcher(context)` creates one
  file-backed `VirtualSystemServer` and injects its Activity, Service, and
  Broadcast services into the dispatcher wrapper.
- `EngineRuntimeInstallers.installInstrumentation(context)` now creates
  Activity operations with the same `filesDir`-backed runtime/task stores.
- `MultiAppApplication` passes the application context to both installers.
- Dispatcher launch sync, Instrumentation lifecycle/result updates, engine
  runtime queries, and evidence export now target the same persisted files.

Verification:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Combined app compile/boundary and focused engine tests passed.

Remaining gate for this slice:

- This unifies the current process-local adapters around one durable store; it
  is not yet an engine-server Binder IPC boundary.
- Device evidence must prove all `:vN` processes observe and merge the same
  task state without stale runtime restoration.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Engine-Owned Activity Launch Allocation

The Activity launch hot path now lets the engine coordinator allocate the
proxy slot and initial virtual Activity record after central route planning.
The loader remains the framework primitive provider, but it no longer owns the
launch decision when the engine dispatcher is installed.

Implemented:

- Added `EngineActivityLaunchCoordinator` to consume a
  `VirtualActivityDispatchPlan`, validate instance/process-slot ownership,
  allocate the proxy component, register the launch record, and create the
  proxy Intent.
- `DefaultEngineAmsComponentDispatcher` now uses the coordinator for single and
  batch Activity remaps; the loader dispatcher is retained only as an adapter
  fallback when no engine coordinator is installed.
- The coordinator uses the process-slot-specific proxy pool and the persisted
  proxy-slot assignment store.
- Instance or process-slot mismatch fails closed before a record is allocated.
- Activity dispatch evidence distinguishes engine-owned remaps from legacy
  loader fallback remaps.
- Added focused tests for engine allocation and process-slot mismatch.

Verification:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- App compile/boundary and focused Activity dispatcher/runtime/server tests
  passed.

Remaining gate for this slice:

- Batch Activity mapping now rolls back earlier records and slot assignments if
  a later member fails. Rollback after Android framework dispatch itself throws
  is still open.
- Android recents/task identity, process-death restoration, and simultaneous
  same-origin instances still require device evidence.
- This is still a file-backed in-process coordinator, not a dedicated Binder
  virtual system server.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Activity Mapping Transaction Rollback

Engine-owned Activity mapping now has rollback semantics for both a single
allocation failure and a partially allocated `startActivities(...)` batch.

Implemented:

- Added `VirtualActivityRecordManager` state snapshots covering tasks, records,
  last launch result, and original guest Intent cache.
- Added compare-and-set restoration for persisted proxy-slot assignments.
- `EngineActivityLaunchCoordinator.remap(...)` restores manager and slot state
  when record or proxy Intent creation fails.
- `remapBatch(...)` restores the complete pre-batch state and returns a blocked
  result for every member when any member fails.
- Added regression tests for record/task/slot rollback and conditional slot
  restoration.

Verification:

```powershell
.\gradlew.bat :core:model:testDebugUnitTest --tests "com.multiapp.core.model.virtual.FileBackedProxyActivitySlotAssignmentStoreTest" :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Focused model, loader, and engine transaction tests passed.

Remaining gate:

- This covers mapping-time failure. A failure from the real Android
  `Context.startActivities(...)` call still needs an engine abort callback.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Cross-Process Runtime State Hardening

The file-backed runtime registry now behaves as a shared recoverable state
source instead of a per-process cache that never observes sibling updates.

Implemented:

- Runtime state reads/writes/removals use a shared process monitor and OS file
  lock.
- Writes use same-directory temporary files, fsync, and atomic replace where
  supported.
- `putIfNewer(...)` rejects lower `runtimeEpoch` writes.
- `removeIfEpoch(...)` prevents a stale stop request from deleting a newer
  launch session.
- `EngineRuntimeRegistry.get(...)` reconciles its cache with durable state on
  every query and observes sibling-process updates and removals.
- Runtime evidence refresh preserves operation evidence only within the same
  engine/evidence session.
- Added concurrent multi-store, stale-writer, cross-registry refresh, and
  cross-registry stop tests.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineRuntimeStateStoreTest" --tests "com.multiapp.core.engine.EngineRuntimeRegistryTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Runtime state/registry/server tests, app compile, and `EngineBoundaryTest`
  passed.

Remaining gate:

- File-backed coordination is an interim recovery layer, not a VirtualApp-style
  Binder-owned virtual system server.
- Operation evidence and ordered mutation ownership are still process-local.
- Device process-death and simultaneous `:vN` stress evidence remain required.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 BinderProvider Engine Authority Foundation

MultiApp now has a real main-process Binder publication path modeled after the
VirtualApp BinderProvider/service-manager bootstrap pattern. Durable files
remain the cold-start recovery layer, while online virtual processes can query
one main-process runtime authority.

Implemented:

- Added `IEngineRuntimeService.aidl` in `:core:engine`.
- Added a same-UID Binder endpoint for runtime query, evidence query, operation
  evidence submission, and epoch-conditional stop.
- Added `EngineBinderProvider` in the host main process with a fixed private
  authority; it is non-exported and has no virtual-process assignment.
- `MultiAppApplication` acquires the Binder through `ContentResolver.call(...)`
  in every process and installs the IPC evidence sink when available.
- Activity, Provider, and Service runtime binders validate authoritative
  runtime existence, epoch, live state, and expected process slot before guest
  bootstrap. A connected authority fails closed; an unavailable authority may
  use the durable recovery fallback.
- Added pure authority-verdict tests and a structured Manifest security test.

Verification:

```powershell
.\gradlew.bat :core:engine:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineRuntimeAuthorityValidatorTest" --tests "com.multiapp.core.engine.EngineRuntimeRegistryTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --tests "com.multiapp.app.container.HostedActivityRuntimeBinderTest" --tests "com.multiapp.app.container.HostedProviderRuntimeBinderTest" --tests "com.multiapp.app.container.HostedServiceRuntimeBinderTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:testDebugUnitTest --tests "com.multiapp.app.EngineBinderProviderManifestTest" --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- AIDL/core/app compilation, authority/registry tests, hosted component binder
  tests, app boundary, and Manifest security tests passed.

Remaining gate:

- Package snapshot and Activity/Provider/Service/Broadcast mutation ownership
  are not yet fully remote; local engine adapters still execute those
  primitives after authoritative runtime validation.
- Binder death/reconnect and device evidence for every `:vN` process remain
  open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Binder-Owned Activity Route Planning

Activity route planning and dispatch evidence now use the main-process Binder
authority. This is the first engine subsystem following the target split:
server owns policy and package resolution, virtual client owns framework
execution and guest lifecycle primitives.

Implemented:

- Extended `IEngineRuntimeService` with Activity plan and dispatch-evidence
  operations.
- Added Bundle codecs for Activity plan requests, targets, plans, and dispatch
  results.
- `EngineRuntimeBinderEndpoint` delegates route planning to the main-process
  `VirtualActivityService`, which resolves components from the authoritative
  runtime package snapshot.
- Added `IpcBackedVirtualActivityService`: remote planning/evidence is used when
  Binder is connected; durable local planning is allowed only when Binder is
  unavailable; malformed connected responses fail closed.
- `EngineRuntimeInstallers` injects the IPC-backed Activity service into the
  AMS dispatcher while lifecycle/task primitives stay in the owning virtual
  process.
- Added tests proving remote ownership, fail-closed connected behavior,
  durable fallback, and remote evidence ownership.

Verification:

```powershell
.\gradlew.bat :core:engine:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityIpcServiceTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.EngineRuntimeAuthorityValidatorTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- AIDL/core/app compilation and focused IPC/dispatcher/boundary tests passed.

Remaining gate:

- Activity lifecycle/result/task mutation is not fully Binder-owned yet.
- Provider, Service, and Broadcast route planning still need the same remote
  authority migration.
- Binder reconnect and device multi-process evidence remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Binder-Owned Component Route Planning

Provider, Service, and Broadcast now follow the same server-policy/client-
execution split as Activity. All four component families resolve targets and
aggregate dispatch evidence in the main-process engine authority.

Implemented:

- Extended `IEngineRuntimeService` with Provider, Service, and Broadcast
  plan/result operations.
- Added Bundle codecs for each request, target, plan, and operation result.
- Added IPC-backed `VirtualProviderService`, `VirtualServiceService`, and
  `VirtualBroadcastService` adapters.
- Wired StubContentProvider, StubService, and Context AMS dispatch to those
  adapters.
- Connected invalid responses fail closed; durable local planning is used only
  when Binder is unavailable.
- Binder clients now link a death recipient and reacquire the endpoint through
  BinderProvider after server death.
- BinderProvider and app installers obtain one file-backed
  `EngineSystemServerHandle`, so all subsystem endpoints share the same
  registry and server instance.
- Added tests for remote ownership, evidence ownership, fail-closed behavior,
  and durable fallback.

Verification:

```powershell
.\gradlew.bat :core:engine:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityIpcServiceTest" --tests "com.multiapp.core.engine.EngineProviderIpcServiceTest" --tests "com.multiapp.core.engine.EngineComponentIpcServicesTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBinderProviderManifestTest" --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- AIDL/core/app compilation and focused component IPC, dispatcher, app
  boundary, and Manifest tests passed.

Remaining gate:

- Component framework execution correctly remains in the target virtual
  process, but durable lifecycle mutation and ordered semantics need further
  server reconciliation.
- Activity task/lifecycle/result mutation is still hybrid.
- Binder death recovery needs device process-kill evidence.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Binder-Owned Component Route Planning

Provider, Service, and Broadcast route planning now follow the same
server-policy/client-execution split as Activity. All four component families
resolve targets and aggregate dispatch evidence in the main-process engine
authority.

Implemented:

- Extended `IEngineRuntimeService` with Provider, Service, and Broadcast
  plan/result operations.
- Added complete Bundle codecs for their request, target, plan, and operation
  result models.
- Added IPC-backed `VirtualProviderService`, `VirtualServiceService`, and
  `VirtualBroadcastService` adapters.
- Connected IPC owns planning/evidence and fails closed on invalid responses;
  durable local planning is used only when the Binder authority is unavailable.
- Wired Provider dispatch, StubService dispatch, and Context AMS
  Service/Broadcast dispatch to the IPC-backed services.
- Added adapter tests for remote ownership, invalid-response blocking, evidence
  ownership, and durable fallback.

Binder lifecycle hardening:

- Clients link a death recipient to the engine Binder.
- Dead proxies are cleared and reacquired through BinderProvider on the next
  operation.
- Provider restart can therefore recover without restarting every virtual
  process.

Verification:

```powershell
.\gradlew.bat :core:engine:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityIpcServiceTest" --tests "com.multiapp.core.engine.EngineProviderIpcServiceTest" --tests "com.multiapp.core.engine.EngineComponentIpcServicesTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBinderProviderManifestTest" --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- AIDL/core/app compilation and focused component IPC, dispatcher, boundary,
  and Manifest tests passed.

Remaining gate:

- Provider/Service/Broadcast execution and lifecycle primitives still run in
  the target virtual process, which is intentional, but their durable runtime
  records and ordered mutation semantics need further server reconciliation.
- Activity task/lifecycle/result mutation is still hybrid.
- Binder death recovery requires real device process-kill evidence.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Binder-Owned Activity Lifecycle Mutation

Activity lifecycle and result mutations now use the main-process engine
authority instead of independently changing each virtual process's local
record manager.

Implemented:

- Extended `IEngineRuntimeService` with typed Activity mutation and consume
  operations.
- Main-process `VirtualActivityService` now owns `mark-state`, `finish`,
  atomic `record-finish-result`, `set-result`, `mark-result-dispatch`, result
  consumption, resume-fallback consumption, and pending `onNewIntent`
  consumption.
- Added complete Bundle codecs for operation results, Activity records,
  result payloads, pending new intents, and intent snapshots.
- Wired both Instrumentation hot-path operations and `ProxyActivityBase`
  lifecycle control through `IpcBackedVirtualActivityService`.
- Connected invalid mutation responses fail closed. Binder-unavailable clients
  may use the locked file-backed service.
- A connected authoritative empty consume result no longer falls through to a
  stale local record manager.
- `queryTaskState` now returns a main-process Binder snapshot with strict task
  and Activity count validation; connected malformed snapshots fail closed.
- `syncActivityTaskState` now sends the current virtual-process task snapshot
  to the main-process authority for instance filtering and durable merge;
  direct file-backed sync is used only when Binder is unavailable.
- `EngineActivityTaskController.persist()` now delegates to that service, so
  `ContainerActivity` and `ProxyActivityBase` no longer bypass the online
  authority with direct task-file writes.
- Removed the second manager-level fallback from
  `EngineVirtualActivityOperations`; the IPC adapter is now the only fallback
  policy owner.

Verification:

```powershell
.\gradlew.bat :core:engine:compileDebugKotlin :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityIpcServiceTest" --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --tests "com.multiapp.app.EngineBinderProviderManifestTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- AIDL/core/app compilation and focused lifecycle ownership, durable fallback,
  task persistence, BinderProvider Manifest, and app boundary tests passed.

Remaining gate:

- Task snapshot admission and reads are Binder-owned; the server still uses the
  locked file store as its durable recovery backend.
- Finish-result route lookup and target-result persistence now execute as one
  server transaction; focused server tests cover the source/target route.
- Process-kill/reconnect ordering still requires device evidence.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Durable Service Runtime State

Binder-owned Service dispatch now updates a recoverable engine ServiceRecord
instead of producing evidence only.

Implemented:

- Added `EngineServiceRuntimeStateStore` with in-memory and locked,
  atomic-replace file-backed implementations.
- Added pure engine `EngineServiceRuntimeRecord` states: `STARTED`,
  `FOREGROUND`, and `STOPPED`.
- Every ServiceRecord is bound to `runtimeEpoch`; a recreated instance cannot
  observe stale records from its previous virtual process generation.
- Successful START/START_FOREGROUND dispatch persists process slot, active
  start/bind counts, cache state, and `onStartCommand()` result.
- Successful STOP transitions the same instance/service record to `STOPPED`
  and clears active start count.
- BIND/UNBIND remain excluded from the commercial state table while their
  framework semantics are explicitly `UNSUPPORTED`.
- Added Binder-owned `queryServiceRuntimeState`; malformed connected responses
  fail closed and unavailable Binder may use the durable fallback.
- Engine evidence now exports an aggregate `service/runtime-state` record.
- The main-process `EngineSystemServerHandle` uses
  `engine_service_runtime_state.properties` for process-death recovery.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineServiceRuntimeStateStoreTest" --tests "com.multiapp.core.engine.EngineComponentIpcServicesTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Service store recovery/isolation, Binder query ownership, fail-closed state,
  and START-to-STOP lifecycle transition tests passed.

Remaining gate:

- This records lifecycle truth after loader execution; guest Service callbacks
  still run in the target virtual process by design.
- Binding, foreground notification/type mapping, sticky restart, and
  cross-process callback delivery remain incomplete.
- Device process-kill/restart evidence is still required.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Durable Provider Runtime State

Provider dispatch now updates a recoverable main-process ProviderRecord rather
than producing route/evidence data only.

Implemented:

- Added `EngineProviderRuntimeStateStore` with in-memory and locked,
  atomic-replace file-backed implementations.
- READY records are keyed by `instanceId + guestAuthority` and contain provider
  class, process slot, `runtimeEpoch`, cache state, last operation, and
  operation count.
- Repeated access updates one record; a recreated runtime cannot observe stale
  records from its previous epoch.
- Added Binder-owned `queryProviderRuntimeState`; malformed connected responses
  fail closed and unavailable Binder may use durable fallback.
- Provider runtime state is aggregated into engine evidence.
- The main-process server persists records in
  `engine_provider_runtime_state.properties`.
- `notifyChange`, observer registration, and URI grant/revoke operations now
  return `UNSUPPORTED` with no loader dispatch until a real system-service
  proxy exists. They are no longer ambiguous `PARTIAL` routes.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineProviderRuntimeStateStoreTest" --tests "com.multiapp.core.engine.EngineProviderIpcServiceTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Provider store recovery/isolation, epoch filtering, Binder query ownership,
  repeated operation accounting, and control-plane fail-closed tests passed.

Remaining gate:

- Real URI grant and ContentObserver semantics require an engine-owned
  `IContentService`/AMS grant proxy; this batch deliberately does not fake it.
- Custom provider processes and device process-death recovery remain open.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Durable Broadcast State and Semantic Gate

Broadcast dispatch now preserves framework call semantics through the loader
boundary and records recoverable engine-owned delivery state.

Implemented:

- Added `VirtualBroadcastDispatchOptions` and propagated ordered, sticky,
  result-receiver, abort, receiver permission, receiver app-op, AsUser, and
  platform-options metadata from every relevant `VirtualContextWrapper`
  overload.
- `removeStickyBroadcast*` is treated as sticky semantics instead of being
  silently downgraded to a normal broadcast.
- Added `EngineBroadcastRuntimeStateStore` with in-memory and locked,
  atomic-replace file-backed implementations.
- Delivery records are keyed by instance, receiver, and action, and carry
  process slot, `runtimeEpoch`, latest verdict/state, and delivered/blocked/
  failed counters.
- Counter mutation executes under one store lock, preventing cross-process
  read-modify-write loss.
- Added Binder-owned `queryBroadcastRuntimeState`; malformed connected
  responses fail closed and unavailable Binder may use the durable fallback.
- Engine evidence now exports `broadcast/runtime-state`.
- Ordered, sticky, result-receiver, abort, permission/app-op, AsUser, and
  platform-options semantics return `UNSUPPORTED` before loader dispatch.
  Normal explicit/implicit manifest delivery remains `PARTIAL` pending device
  proof.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --tests "com.multiapp.core.loader.VirtualAmsComponentDispatcherTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineBroadcastRuntimeStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineComponentIpcServicesTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --tests "com.multiapp.app.EngineBinderProviderManifestTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Loader semantic propagation, durable state recovery/isolation, Binder query
  ownership, engine fail-closed routing, app compilation, Manifest, and app
  boundary tests passed.

Remaining gate:

- Ordered result propagation/abort, sticky storage/removal, virtual-user
  mapping, receiver permission/AppOps enforcement, and cross-process receiver
  callbacks still require real virtual Broadcast system-service support.
- Device process-death and delivery-order evidence is still required.
- The commercial engine BLOCK remains in force.

## Execution Update - 2026-07-10 Provider URI Grants and Virtual AppOps Checks

Provider URI permission state and the stable AppOps check path now have
engine-owned, instance-scoped state instead of relying only on host package
rewrites.

Implemented:

- Added locked, atomic-replace file stores for Provider URI grants and virtual
  AppOps modes:
  - `engine_provider_uri_grants.properties`
  - `engine_app_ops.properties`
- URI grants bind owner instance, target instance, authority, encoded path,
  read/write mode, prefix flag, and persistable eligibility.
- Provider dispatch verifies the durable grant record. A guest-provided
  `uriGrantPresent=true` without that record fails closed.
- Added Binder-owned grant/revoke/check AIDL operations. Connected malformed
  responses fail closed; only unavailable Binder can use the file-backed
  recovery service.
- `VirtualContextWrapper` now routes `grantUriPermission`, both revoke
  overloads, check/calling-check, and enforce overloads through an engine
  extension point for guest authorities. Non-guest authorities still delegate
  to Android.
- Added an `APP_OPS` engine subsystem and persistent per-instance op modes.
  `checkOperation`, `checkOperationRaw`, and `checkAudioOperation` can return an
  explicit virtual integer mode before the host call. Without an explicit
  virtual record, the proxy preserves the existing guest-to-host rewrite and
  delegates to Android.
- Guest `set*`/`reset*` AppOps mutations, including no-package
  `resetAllModes`, are rejected before reaching the host service.
- The implementation follows current AOSP behavior where check calls return an
  integer mode, while modern note/start calls return richer results:
  - https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/com/android/internal/app/IAppOpsService.aidl
  - https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/app/AppOpsManager.java

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualContextWrapperTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineProviderUriGrantStoreTest" --tests "com.multiapp.core.engine.EngineVirtualUriPermissionDispatcherTest" --tests "com.multiapp.core.engine.EngineAppOpsStateStoreTest" --tests "com.multiapp.core.engine.EngineAppOpsServiceTest" --tests "com.multiapp.core.engine.EngineVirtualAppOpsDispatcherTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Store recovery, instance/path/mode isolation, forged-grant rejection,
  Context routing, Binder authority behavior, AppOps mode interception,
  no-package mutation blocking, and app engine-boundary tests passed.

Remaining gate:

- Persistable URI take/release, external non-virtual recipients, custom
  Provider processes, and device proof remain open.
- AppOps note/start/finish, attribution chains, foreground translation,
  watchers, and complete `PackageOps` query objects remain `UNSUPPORTED`; no
  fake `SyncNotedAppOp` is returned.
- Provider path-policy parsing and matching are implemented in the later
  Provider batch/path update below. Virtual runtime permission ownership is
  still incomplete.
- The commercial engine decision remains `BLOCK`.

## Execution Update - 2026-07-11 Process Bootstrap READY and Foreground ACK

This batch implements the engine-owned process bootstrap gate that was still
missing in the previous update. The design was checked against VirtualApp
`VActivityManagerService` / `StubContentProvider` / `VClientImpl`, BlackBox
`BProcessManagerService` / `ProxyContentProvider` / `BActivityThread`, and
AOSP `LoadedApk.makeApplication`. MultiApp keeps their slot-Provider process
activation structure but uses a stricter READY result and bounded waits.

Implemented:

- Eight private bootstrap Providers map one-to-one to Android processes
  `:v0..:v7`. Engine launch first calls the assigned Provider from a background
  worker; no foreground Activity is started before a valid READY response.
- READY validates `instanceId + runtimeEpoch + engineSessionId + processSlot`,
  real PID, a live target-process Binder token, guest ClassLoader, guest
  Application, Application stage, Provider preinstall status, launcher
  Activity, and required system-service bootstrap evidence.
- Runtime epochs are monotonic per instance. Equal-epoch different-session
  writes are rejected, and durable runtime transitions use a file-store-level
  whole-record compare-and-set rather than a process-local check followed by
  an unconditional write.
- The target Binder token is linked to death. Only the matching epoch/session
  can transition to `DEAD`; an old process callback cannot kill a replacement
  runtime, and repeated callbacks are idempotent.
- `VirtualProcessRuntime` now distinguishes `BINDING`, `READY`, `FAILED`, and
  `TIMED_OUT`. A provisional Application runtime is visible only to the
  initialization thread; ordinary callers share a bounded 30-second wait and
  cannot reuse a ClassLoader-only or Application-less result.
- A successful bootstrap promotes the central runtime only to `PREWARMED`.
  A lifecycle callback registered on the real guest Application sends an IPC
  acknowledgement after the real guest Activity completes `onResume`; the
  server validates epoch, session, process slot, and Binder caller PID before
  atomically transitioning `PREWARMED -> RUNNING`.
- READY launches the fixed proxy slot directly instead of foregrounding a
  `ContainerActivity`. The small launcher Intent is carried across processes,
  and recovery rebuilds a guest `ComponentName` rather than exposing the host
  proxy component to the guest.
- App-manager launch work runs on `Dispatchers.IO`, so the bounded Provider
  handshake cannot block the product UI thread.

Verification:

```powershell
.\gradlew.bat :core:common:testDebugUnitTest :core:model:testDebugUnitTest :core:instance:testDebugUnitTest :core:manifest:testDebugUnitTest :core:identity:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 --build-cache "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Final full gate: `BUILD SUCCESSFUL` in 4m49s.
- APK size: 99,509,041 bytes.
- APK SHA-256:
  `FFC58BB4D81A631D788C52E34763C2E0A97DFDFC26DBD923C6A9F10620A5F994`.
- Focused regressions cover malformed/stale/timeout Provider responses,
  missing process tokens, process-slot authorities, provisional-runtime
  visibility, deadline expiry, durable CAS, Binder death, stale generations,
  and guest foreground ACK identity checks.

Remaining gate:

- No device artifact yet proves that a real `:vN` Provider returns READY, the
  direct proxy is substituted without foreground `ContainerActivity`, and the
  guest `onResume` ACK changes the central state to `RUNNING`.
- A timed-out or half-initialized target process is isolated by fail-closed
  state, but this batch does not yet explicitly kill and recycle that slot.
- Process-death recents recovery, runtime permission request/result, custom
  Provider processes, full Service/Broadcast semantics, native linker/load,
  and the API 28-36/HyperOS matrix remain open.
- GKD permission, AstroBox black screen, QQ, WeChat, and QQ Reader still require
  fresh device evidence. The commercial engine decision remains `BLOCK`.

## Execution Update - 2026-07-11 GKD Application Context Chain

Device artifact `.tmp/manual-after-user-20260711-082728` identified a generic
Application attachment defect rather than a GKD-specific compatibility rule:

- `com.multiapp.app:v2` reached `LoadedApk.makeApplication`, then remained in
  `ContextImpl.getImpl -> Application.attach -> Instrumentation.newApplication`
  for more than 45 seconds.
- Android reported an input ANR for `ContainerActivityV2`; `exit-info` recorded
  `reason=6 (ANR)` and the process was killed.
- The direct cause was `VirtualContextWrapper.getBaseContext()` returning the
  wrapper itself. AOSP unwraps `ContextWrapper` repeatedly in
  `ContextImpl.getImpl()`, so that contract created a non-terminating chain.

Current dirty-tree correction:

- `VirtualContextWrapper.getBaseContext()` now returns its actual base.
- `VirtualInstrumentation` wraps the guest `ContextImpl` created by
  `LoadedApk.makeApplication`, instead of replacing that base with the host
  Context. This preserves `Application.mLoadedApk` as the guest LoadedApk.
- `FrameworkApplicationContextCompat` rewrites only ContextImpl/ContentResolver
  caller identity fields to `com.multiapp.app`, following VirtualApp's
  ContextFixer pattern, while guest-facing package identity remains the origin
  package.
- `LoadedApk.mPackageName` now uses `originPackageName`; the virtual package is
  retained only as an ActivityThread lookup alias.

Verification:

- 95 focused loader tests passed for Application creation, Context identity,
  finite wrapper chains, LoadedApk identity, and existing Context behavior.

Remaining gate:

- A new device run must prove `MAKE_APPLICATION_FINISHED`, Provider preinstall,
  and `Application.onCreate()` completion without ANR.
- The current launch still presents a foreground Container Activity before the
  process is fully prewarmed. The VirtualApp-style process bootstrap handshake
  remains required so a proxy Activity is launched only after the guest
  Application reports ready.
- Commercial engine decision remains **BLOCK**.

## Execution Update - 2026-07-10 Launch Ordering and Slot Guardrails

The launch path now avoids two process-wide correctness hazards before adding
more compatibility behavior.

Implemented:

- Launcher-triggered engine launch work runs on the injected IO dispatcher and
  duplicate taps for the same instance are coalesced until the request ends.
- Launch no longer reparses and rewrites the installed APK record on every
  start. Install refresh remains an explicit install/refresh operation.
- Runtime slot reuse now checks every live instance, not only instances with
  the same origin package. A stale persisted assignment that collides with
  another instance is repaired before reuse.
- The baseline profile no longer enables the legacy provider hook merely
  because engine Provider routing is active. That hook is now restricted to a
  profile where LSPlant is explicitly enabled.
- Guest Application creation, runtime publication, same-process Provider
  preinstall, and `Application.onCreate()` are serialized through the Android
  main Looper. `LoadedApk.makeApplication(..., null)` creates and attaches the
  Application without invoking `onCreate`; the engine invokes `onCreate`
  exactly once through ActivityThread Instrumentation after Provider
  preinstall.
- Background prewarm threads no longer create permanent custom Loopers or
  enter `Looper.loop()` after bootstrap.

Verification:

- Focused launcher, slot-store, Application-stage, hosted-bootstrap, and
  virtualization-engine tests passed in this dirty tree.

Remaining gate:

- Main-Looper ordering is locally covered but still needs device evidence for
  ANR behavior, process death, OEM ActivityThread variants, and Provider
  ordering before protected-app compatibility can be claimed.
- Real process-slot isolation is still constrained by declared Android stub
  processes and the device matrix. Decision remains `BLOCK`.

## Execution Update - 2026-07-10 Virtual Permission Ownership

The earlier statement that the engine had no virtual permission service is now
partially superseded. Declaring a permission in the guest manifest no longer
implicitly grants it.

Implemented:

- Added a `PERMISSION` engine subsystem with a file-backed, per-instance grant
  store and explicit grant/revoke sources.
- PackageManager `checkPermission()` and
  `getPackagesHoldingPermissions()` consult the active engine permission
  dispatcher. Unknown state and unrequested permissions deny access instead
  of falling through to a manifest-declared grant.
- Permission checks and runtime-state queries cross the engine Binder;
  malformed responses from a connected authority fail closed.
- Provider read/write/path permission gates consult the same virtual
  permission service, allowing only an explicitly granted target instance.
- On first launch, a compatibility seeder mirrors the currently installed
  source package's Android grant result. Existing per-instance decisions are
  preserved and are not overwritten on later launches.
- Permission seed counts and unresolved checks are exported as structured
  engine evidence. An unresolved seed keeps launch at `PARTIAL`.

Verification:

- Focused grant-store, service, IPC, PackageManager, Provider-permission, and
  source-seeding tests passed.

Remaining gate:

- `requestPermissions()` UI/result delivery, permission flags, groups,
  one-time grants, auto-reset, shared UID, AppOps coupling, and user-facing
  per-clone permission controls remain incomplete.
- Source-package mirroring is only an initial compatibility policy; it is not
  a replacement for virtual runtime permission UX or device evidence.
- Decision remains `BLOCK`.

## Execution Update - 2026-07-10 Same-Process Service Binding

Service binding is no longer globally reported as unsupported. The engine now
admits the lifecycle path that the loader can actually execute and rejects the
process models it cannot yet reproduce.

Implemented:

- `VirtualServiceService` plans same-guest-process `BIND` and connection-based
  `UNBIND` before loader dispatch.
- The existing loader lifecycle path performs `onBind`, connection callback,
  binder reuse, `onUnbind`, optional `onRebind`, and idle destruction.
- Bind/unbind results are converted into engine-owned dispatch evidence and a
  durable Service runtime state including the new `BOUND` state and active
  bind counts.
- Service target IPC preserves whether the component belongs to the guest
  Application process.
- A Service with a custom/remote guest process fails closed as
  `service_cross_process_unsupported` before loader dispatch.

Verification:

- Focused server, AMS dispatcher, and file-backed Service-state tests passed
  for bind, unbind, bound-only recovery, stopped state, and cross-process
  rejection.

Remaining gate:

- Cross-process Service hosting, binder-death rebind, foreground-service type
  mapping, sticky restart, bind callback ordering after process death, and
  device evidence remain incomplete.
- Same-process local tests do not establish full Android `VService` parity.
  Decision remains `BLOCK`.

## Execution Update - 2026-07-10 Global Provider Authority Routing

Provider data-plane routing can now resolve authorities owned by another
virtual package or clone instance instead of checking only the caller's
package snapshot.

Implemented:

- `VirtualProviderService` owns global authority resolution across durable
  virtual runtimes.
- The caller's own Provider wins for self access. A single external virtual
  owner is selected directly; duplicate clone owners require a matching
  instance-scoped URI grant to disambiguate the target.
- Authority resolution crosses the engine Binder with typed request/result
  codecs. A connected malformed response fails closed as a virtual route.
- API 29+ wrapped resolvers delegate non-virtual/system authorities to Android,
  but an unresolved virtual authority never falls through to the host
  `ContentResolver`.
- The verified target instance is carried into the route token and the target
  runtime performs the existing authority, process-slot, permission, AppOps,
  and URI-grant checks before Provider dispatch.
- Fixed a data-plane defect where a globally resolved external authority was
  incorrectly revalidated against the caller's package snapshot.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineProviderIpcServiceTest" --tests "com.multiapp.core.engine.EngineVirtualContentResolverTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 --build-cache "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- Global owner selection, duplicate-owner URI-grant disambiguation, external
  virtual Provider dispatch, host delegation for system authorities, virtual
  fail-closed behavior, and app engine-boundary tests passed.

Remaining gate:

- Persistable URI grants, custom Provider processes, authority-index
  invalidation after install refresh, and device process-death evidence remain
  open. `applyBatch`, `refresh`, and path policy are covered by the next update.
- No device artifact in this batch proves QQ, WeChat, QQ Reader, or GKD
  compatibility.
- The commercial engine decision remains `BLOCK`.

## Execution Update - 2026-07-10 Provider Batch and Path Policy

The baseline wrapped resolver now preserves Provider batch semantics and the
engine models path-scoped permissions instead of applying one authority-wide
policy to every URI.

Implemented:

- Added `APPLY_BATCH` and `REFRESH` as explicit Provider operations.
- `applyBatch` performs a two-phase route: every operation must use the declared
  authority, resolve to the same virtual owner, pass read/write planning, and
  return the same guest Provider object before one guest `applyBatch()` call is
  made. Validation failure occurs before guest mutation.
- Non-virtual batches and `refresh()` preserve host `ContentResolver`
  delegation; unresolved virtual routes fail closed.
- Added pure-model literal/prefix/simple-glob/advanced-glob/suffix Provider path
  patterns and path read/write permissions.
- Manifest parsing, install JSON, runtime-state persistence, snapshot rebuild,
  and guest `ProviderInfo.pathPermissions/uriPermissionPatterns` now preserve
  those policies.
- Engine authorization applies matching path permissions before Provider-level
  permissions. A Provider with global URI grants disabled can grant only a URI
  matching its declared grant patterns.
- PackageManager fallback requests `GET_URI_PERMISSION_PATTERNS` and handles its
  ambiguity conservatively: when patterns are present it does not infer an
  authority-wide grant.
- The behavior follows Android's documented path policy and AOSP Provider
  transport enforcement:
  - https://developer.android.com/guide/topics/manifest/path-permission-element
  - https://developer.android.com/guide/topics/manifest/grant-uri-permission-element
  - https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/ContentProvider.java

Verification:

- Focused model, Manifest, loader, engine, and app boundary tests passed.
- Engine coverage includes batch owner consistency, path permission override,
  grant-pattern containment, global authority routing, runtime-state recovery,
  and system `refresh()` delegation.
- `:app:compileDebugKotlin` and `EngineBoundaryTest` passed.

Remaining gate:

- The Virtual Permission Ownership update above now provides explicit
  per-instance grants for protected cross-instance paths. Runtime permission
  request/result UI, flags, groups, and AppOps coupling remain incomplete.
- Persistable take/release, external non-virtual recipients, advanced/simple
  glob device parity on API 28-36, custom Provider processes, and device
  process-death evidence remain open.
- The commercial engine decision remains `BLOCK`.

## Execution Update - 2026-07-10 Full Local Gate

The current commercial-engine dirty tree passed the complete local test and
debug-package gate after the Provider authority, AppOps, batch, and path-policy
work was integrated.

Command:

```powershell
.\gradlew.bat :core:common:testDebugUnitTest :core:model:testDebugUnitTest :core:instance:testDebugUnitTest :core:manifest:testDebugUnitTest :core:identity:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 --build-cache "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `BUILD SUCCESSFUL` in 9m14s; 514 Gradle tasks completed or reused.
- 1,816 unit tests were reported across common/model/instance/manifest/
  identity/loader/hook/engine/app: 0 failures, 0 errors, 12 skipped.
- `app/build/outputs/apk/debug/app-debug.apk` was rebuilt, size 99,315,837
  bytes, SHA-256
  `E7BEDDC21C8281C7221B26A0E77EE4A280FCB4263247F249ACD4884E7BC7F4D4`.
- `git diff --check` passed; only existing LF/CRLF conversion warnings remain.
- AGP 8.7.3 still warns that it was tested only through compileSdk 35 while
  this project uses compileSdk 36.

This is a local gate only. Device process-death, recents, component lifecycle,
native namespace/IO, and compatibility-matrix evidence are still required, so
the commercial engine decision remains `BLOCK`.

## Execution Update - 2026-07-10 Persistable URI Grants and Live Authority Resolution

The Provider control plane now distinguishes a persistable grant offer from a
grant that the target instance has actually persisted. Authority discovery no
longer freezes a process-local authority set on first resolver use.

Implemented:

- `EngineProviderUriGrantRecord` stores transient access modes, persistable
  eligibility, persisted access modes, and persisted timestamp separately.
- Locked in-memory/file stores implement take/release semantics. Releasing a
  persisted mode keeps a transient grant; an owner revoke removes both the
  transient and persisted mode.
- `VirtualProviderService`, engine AIDL, IPC codecs/client, and the IPC-backed
  service expose target-owned take/release operations with UID/PID, target,
  authority, path, mode, and ambiguous-owner validation.
- The loader URI permission dispatcher handles `TAKE_PERSISTABLE` and
  `RELEASE_PERSISTABLE`, including authorities owned by another virtual
  package. Non-virtual/system authorities remain Android-owned.
- Added an API 29+ `uri_grants` ServiceManager Binder proxy. It routes
  `ContentResolver.takePersistableUriPermission()` and
  `releasePersistableUriPermission()` to the active instance and delegates
  unhandled system URIs to Android. Rejected virtual mutations fail closed.
- Provider authority resolution now asks `VirtualProviderService` on every
  non-self lookup, so a package installed after the first lookup is visible
  without a stale process-local authority index.
- The implementation follows the current AOSP split where `ContentResolver`
  calls `UriGrantsManager`, whose singleton is backed by the `uri_grants`
  Binder:
  - https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/ContentResolver.java
  - https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/IUriGrantsManager.aidl
  - https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/UriGrantsManager.java

Verification:

- Focused engine tests passed for store recreation, take/release behavior,
  owner revoke, target identity, IPC ownership, external virtual authorities,
  and live authority discovery.
- Focused loader tests passed for Binder interception, system-URI delegation,
  fail-closed denial, proxy-stage evidence, and existing Context URI grant
  behavior.

Remaining gate:

- `getPersistedUriPermissions()`/outgoing persisted lists are not virtualized.
- API 28 still uses the older ActivityManager path and is not covered by the
  API 29+ `uri_grants` proxy.
- Reflection/OEM behavior and persistence across real process death require
  device evidence. External non-virtual recipients, complete virtual runtime
  permission UX/flags, and custom Provider processes remain open.
- The commercial engine decision remains `BLOCK`.

## Execution Update - 2026-07-11 Startup Hot Path and Caller Identity Proxies

The current dirty tree reduces startup work on the foreground path and adds
general caller-identity proxies for Android services that validate package and
UID ownership. These changes follow the VirtualApp/DroidPlugin structure of
patching both an already-created manager and the ServiceManager Binder cache;
they are not package-specific GKD rules.

Implemented:

- `FileBackedEngineRuntimeStateStore` uses a process-shared snapshot cache
  keyed by file identity, size, and modification time. Stable sorted snapshots
  avoid reparsing the multi-megabyte runtime-state file for every Binder query.
- `EngineEvidenceMode` is propagated through the engine launch contract.
  Baseline `DEFAULT` resolves to `MINIMAL`; full storage/Provider diagnostics
  run after the guest first-frame path instead of blocking it.
- Added manager plus `ServiceManager.sCache` proxies for LauncherApps and
  Clipboard caller identity. Clipboard rewrites use method-specific AOSP API
  30-36 argument positions, preserve `ClipData` and source-package semantics,
  and clear a guest attribution tag rather than misrepresenting it as a host
  attribution.
- Clipboard evidence now distinguishes
  `clipboardManagerProxyStatus` from
  `clipboardServiceManagerProxyStatus`. Both must install for the aggregate
  status to be `INSTALLED`; one-sided injection is `PARTIAL`.

Device evidence:

- `.tmp/gkd-clipboard-current-20260711-144555` contains two GKD clone launches
  in distinct `com.multiapp.app:v1` and `com.multiapp.app:v3` processes.
- Guest Application creation completed in 103 ms and 81 ms respectively, both
  through `LOADED_APK_MAKE_APPLICATION`; no new Java fatal or ANR was recorded
  in that capture.
- The deployed build reported `clipboardPackageProxyStatus=INSTALLED` for both
  instances. The stricter split manager/Binder fields are locally tested in
  the current source but are not yet device-proven, and the exact clipboard
  mutation flow was not exercised as a pass gate.

Verification:

```powershell
.\gradlew.bat :core:common:testDebugUnitTest :core:model:testDebugUnitTest :core:instance:testDebugUnitTest :core:manifest:testDebugUnitTest :core:identity:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 --build-cache "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `BUILD SUCCESSFUL` in 6m03s; 514 Gradle tasks executed or reused.
- APK size: 99,483,305 bytes.
- APK SHA-256:
  `0D3A2A5D0A9EAC453ED362467C5270176ACD26BAB6DAAE7E22E522397D816CD9`.
- `git diff --check` passed; existing LF/CRLF warnings remain.

Remaining gate:

- The VirtualApp-style process bootstrap readiness handshake is implemented
  locally in the later 2026-07-11 update above, but still lacks device proof
  for target-process READY, direct proxy substitution, and resume ACK.
- Runtime permission request/result UI, flags, groups, one-time grants, and
  per-clone controls remain incomplete.
- System-service proxies are not yet owned by one engine
  `SystemServiceProxyRegistry`; Account, Alarm, Job, and Shortcut remain open.
- The latest device evidence still shows a partial GKD Provider preinstall,
  and AstroBox black-screen behavior has not been closed.
- The commercial engine decision remains `BLOCK`.

## Implementation Update - 2026-07-13 Authoritative Launch Handoff

This batch hardens the READY-to-foreground handoff and removes app-side
recovery paths that could bypass the engine generation. The implementation was
reviewed against VirtualApp `7d739c85`, BlackBox `ffe950f7`, and DroidPlugin
`c6ebf652`: process ownership and component launch authority belong to the
central manager, while Android stub components remain carriers only.

Implemented:

- `launchInstance()` and `stopInstance()` are serialized per instance. A stale
  bootstrap cannot race a newer launch/stop generation into the foreground.
- A short-lived Activity launch capability now binds `instanceId`,
  `runtimeEpoch`, `engineSessionId`, `processSlot`, target PID, proxy Activity,
  and guest Activity. Record recovery and the first real guest `onResume()`
  must present the same capability through the engine Binder authority.
- Binder death ownership moved to `EngineProcessDeathRegistry`. `linkToDeath()`
  and `unlinkToDeath()` run outside registry locks, newer generations replace
  older recipients, and stale/synchronous callbacks cannot mark a current
  runtime dead.
- Engine server startup invalidates durable `CREATED`, `PREWARMED`, and
  `RUNNING` records as `DEAD`; an old PID is never trusted after the authority
  process restarts. IPC unavailability now fails closed instead of accepting a
  local durable snapshot as live authority.
- `ProxyActivityBase` no longer performs an app-owned prewarm/relaunch fallback.
  Missing or mismatched launch identity finishes the proxy instead of invoking
  loader primitives outside the engine.
- `EngineGuestActivityLaunchBridge` is the typed engine-to-loader adapter.
  App and feature main sources are guarded by `verifyEngineBoundary`, which
  rejects direct `core:loader`, `core:hook`, and `core:xposed` imports.
- Hosted runtime reuse now requires a complete fingerprint covering instance
  identity, data root, base/split APK paths and hashes, version, profile,
  process slot, Application class, and Provider-hook mode. A different
  ClassLoader/Application identity cannot replace an in-flight or READY
  runtime.
- READY requires a guest Application and ClassLoader produced by a successful
  `LoadedApk.makeApplication` path. Reflective `FALLBACK` evidence is no longer
  accepted as a reusable READY runtime.

Local gate:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Pkotlin.incremental=false" "-Pksp.incremental=false" :core:model:testDebugUnitTest :core:engine:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest :core:instance:testDebugUnitTest :core:manifest:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Result:

- `BUILD SUCCESSFUL` in 2m37s; 502 Gradle tasks executed or reused.
- The configured modules report 1,768 tests, 0 failures, 0 errors, and 12
  skipped tests.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 99,569,873 bytes.
- APK SHA-256:
  `D04CBB326C1854C696665B1FC667EC042FFA6FDDD64F7ECC0349CDBEAED7F879`.
- `git diff --check` and the engine-boundary build gate pass. AGP 8.7.3 still
  warns that compileSdk 36 is newer than its tested maximum.

Remaining gate:

- No device artifact yet proves capability authorization, target `:vN` PID,
  Binder death, server-restart invalidation, proxy substitution, and the
  central `PREWARMED -> RUNNING` resume ACK as one end-to-end sequence.
- A formal `attachClient/processRestarted` protocol and process-death recents
  restore capability are not implemented. The engine Binder authority also
  still shares the default app process rather than a dedicated server process.
- `ActivityThread.mBoundApplication` / `mInitialApplication` parity, one
  instance with multiple guest `processName` records, runtime-permission UX,
  custom-process Provider/Service, complete Service/Broadcast semantics,
  native linker/load, and the API 28-36/HyperOS matrix remain open.
- This batch is generic launch/runtime infrastructure. It is not compatibility
  proof for GKD, AstroBox, QQ, WeChat, or QQ Reader. The commercial decision
  remains **BLOCK**.

## Implementation Update - 2026-07-13 Client Reattach and LoadedApk Equivalence

This batch implements the server-owned half of the process-death recovery
protocol and tightens the framework Application binding. The design continues
to follow the common VirtualApp `7d739c85`, BlackBox `ffe950f7`, and
DroidPlugin `c6ebf652` rule: the central manager owns process generations and
launch authorization, while the client performs the local LoadedApk bind. It
does not copy BlackBox's server-record Binder-in-Intent design or
DroidPlugin's short-delay launch-message retry.

Implemented:

- Added `EngineProcessControlPlane` and AIDL operations for `attachClient`,
  `processRestarted`, and `issueRecentsRestoreCapability`.
- Initial bootstrap now attaches its live Binder token to the engine authority
  before returning READY. A durable runtime snapshot alone is never accepted
  as live process authority.
- A restart is admitted only from the exact authoritative `DEAD` generation.
  The server atomically allocates `runtimeEpoch + 1`, new engine/evidence
  sessions, the new PID, and the existing process slot. Replay of the old
  generation and a second restart both fail closed.
- Binder caller PID must match the declared PID and `/proc/<pid>/cmdline` must
  match the assigned process slot. Same-UID processes cannot claim another
  slot.
- The recents control plane selects a persisted virtual Activity record and
  issues a fresh launch capability for the new generation. Persisted Android
  system Activity tokens are explicitly not reused.
- ActivityThread launch-record patching now validates the current engine
  capability before replacing the proxy record with guest identity. A
  prepatched guest record can no longer bypass Instrumentation preflight.
- LoadedApk binding now keeps `mApplication`, `mApplicationInfo`, `mResources`,
  `mClassLoader`, and `mLibDir` consistent and also binds
  `mBoundApplication.info/appInfo`, `mInitialApplication`, and
  `mAllApplications`.
- LoadedApk aliases and ActivityThread/Application mutations carry rollback
  handles until Application startup succeeds. Failed creation restores the
  previous framework state.
- Default `android.app.Application` uses the same
  `LoadedApk.makeApplication()` path. The production reflective Application
  fallback is disabled and cannot satisfy READY.
- The bootstrap timeout tombstone test now waits for the Future cleanup
  boundary instead of racing the transport-exit signal; runtime behavior
  remains fail-closed.

Verification:

```powershell
.\gradlew.bat :core:common:testDebugUnitTest :core:model:testDebugUnitTest :core:instance:testDebugUnitTest :core:manifest:testDebugUnitTest :core:identity:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain --max-workers=1 --build-cache "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

Result:

- `BUILD SUCCESSFUL` in 5m21s; 515 Gradle tasks executed, cached, or reused.
- Aggregated reports contain 1,941 tests, 0 failures, 0 errors, and 12 skipped.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 99,687,189 bytes.
- APK SHA-256:
  `CF039FA8F068B07697723D931C317BC4B09A9AF9101E7864F655762F4D0F80BA`.
- `git diff --check` passed; only existing LF/CRLF conversion warnings remain.

Remaining gate:

- The ActivityThread launch callback does not yet execute the complete recents
  recovery sequence: detect stale/missing capability, call
  `processRestarted`, locally bind Application, mark PREWARMED, select the
  persisted record, obtain a fresh capability, and rewrite the same launch
  message.
- The engine Binder authority still lives in the default app process rather
  than a dedicated virtual-system-server process.
- OEM `mBoundApplication` layouts, `AppBindData.processName/providers`, one
  instance with multiple guest `processName` records, and VMRuntime package/
  data-dir identity remain open.
- Runtime permissions, custom-process components, complete Service/Broadcast
  semantics, native linker/load, and the API 28-36/HyperOS device matrix are
  still incomplete.
- No device artifact proves the new reattach generation and LoadedApk rollback
  behavior. GKD, AstroBox, QQ, WeChat, and QQ Reader remain unproven. The
  commercial decision remains **BLOCK**.

## Implementation Update - 2026-07-13 Same-Message Recents Recovery

This batch connects the process reattach control plane to the Android
ActivityThread launch callback. It was checked against VirtualApp
`7d739c85` `HCallbackStub`, BlackBox `ffe950f7` `HCallbackProxy`, and
DroidPlugin `c6ebf652` `PluginProcessManager`.

Implemented:

- Added a loader-owned `VirtualActivityLaunchRecovery` seam and an
  engine-owned `EngineGuestActivityLaunchBridge` adapter. App code installs an
  engine recovery handler without importing loader primitives.
- `EngineGuestRecentsRecoveryCoordinator` is installed before
  `VirtualInstrumentation` in `MultiAppApplication.onCreate()`, so a cold
  `LAUNCH_ACTIVITY`/`EXECUTE_TRANSACTION` can recover before Android creates
  the proxy Activity.
- A stale recents launch now executes one synchronous sequence on the current
  launch message:
  1. query the authoritative runtime generation;
  2. restart only an authoritative DEAD generation and bind the new PID/token;
  3. rebuild LoadedApk/Application through `HostedRuntimeEngine`;
  4. promote the live runtime from CREATED to PREWARMED over engine AIDL;
  5. register foreground RUNNING acknowledgement;
  6. select the persisted virtual Activity record and issue a fresh capability;
  7. write the new generation/capability into the same proxy Intent and run the
     normal launch-record authorization/LoadedApk patch.
- The implementation deliberately does not copy VirtualApp/BlackBox message
  requeue behavior. A failed stage leaves the proxy record unchanged instead
  of replaying an old capability.
- The persisted proxy Intent is not the package/component authority. The
  engine record supplies the restored proxy/guest class, and the package
  snapshot must match the Intent origin package before guest patching.
- `EngineProcessControlPlane.markPrewarmed()` requires the exact live Binder
  generation, caller PID, `/proc` process name, and processSlot. Repeated
  PREWARMED/RUNNING promotion is idempotent.
- Launch-record evidence now includes `launchRecoveryStatus` and
  `launchRecoveryReason`.

Verification:

- Full local gate passed in 3m19s; 515 Gradle tasks executed, cached, or reused.
- Aggregated reports contain 1,945 tests, 0 failures, 0 errors, and 12 skipped.
- Engine/loader/app report 247/695/120 tests respectively, all with 0 failures.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 99,713,821 bytes.
- APK SHA-256:
  `C6FFD5B24159DE85AA4A558F06736D0FE762235A2DA02375593495E0BC6F15EE`.
- Full output is retained in `.tmp/recents-full-gate-20260713.log`.

Remaining gate:

- Device evidence must prove actual process death followed by a system-recents
  launch on API 28-36/HyperOS, including DEAD -> CREATED -> PREWARMED ->
  RUNNING, no proxy black screen, and no stale capability replay.
- The synchronous owner-thread `bindApplication` path still needs a watchdog/
  process-slot recycle policy. A guest Application that never returns can
  otherwise hold the ActivityThread launch callback.
- OEM `ClientTransaction`/`LaunchActivityItem` variants and Android 12+
  launching-record layouts remain device-only risks.
- Dedicated engine-server process isolation, per-guest-process records,
  runtime permissions, custom-process components, full Service/Broadcast
  semantics, native linker/load, and the commercial compatibility matrix remain
  open. The commercial decision remains **BLOCK**.

## Implementation Update - 2026-07-13 Recents Recovery Watchdog and Guest Recycle

This batch bounds a non-returning guest Application during same-message recents
recovery and gives the engine an explicit generation-scoped abandon operation.
It closes the local watchdog/recycle implementation item from the preceding
update; it does not prove an Android ANR-free device path.

Implemented:

- `EngineGuestRecentsRecoveryCoordinator` arms a 45-second watchdog before
  `HostedRuntimeEngine.bindApplication()` and cancels it after the recovery
  path completes.
- Watchdog completion uses one atomic `PENDING/COMPLETED/TIMED_OUT` state. A
  timeout and normal completion cannot both win, so a timed-out recovery cannot
  return a recovered capability while the process is being recycled.
- The recycle path accepts only manifest-backed guest process slots recognized
  as `:v0..:v7`. The default host process and a mismatched Android process name
  are rejected before any termination callback can run.
- Added `IEngineRuntimeService.abandonProcessClient(...)`. The engine accepts
  only the exact live PID/processSlot/runtimeEpoch/engineSession generation,
  marks it `DEAD`, removes its death registration, and revokes launch
  capabilities for that generation. Repeated abandon of the same DEAD
  generation is idempotent.
- The abandon Binder call runs on a daemon cached executor and is awaited for
  at most 500 ms. A blocked engine Binder cannot prevent termination of the
  exact guest PID; Binder death remains the authority cleanup fallback when the
  abandon call does not complete.
- Tests cover normal completion, timeout abandon, exact guest PID termination,
  default-host rejection, process-slot mismatch, and a blocked abandon Binder.

Verification:

- Focused engine/app tests passed in 2m09s.
- Final full local gate passed in 2m34s; 515 Gradle tasks were executed, loaded from
  cache, or reused.
- Aggregated reports contain 1,949 tests, 0 failures, 0 errors, and 12 skipped.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 101,610,089 bytes.
- APK SHA-256:
  `83E2D0A512B16EAB7B18397122F267431C4F614CDC925B4B351ABBDAE51CB323`.
- `git diff --check` passed; existing LF/CRLF conversion warnings remain.

Remaining gate:

- The 45-second watchdog bounds an indefinite stall and recycles the slot, but
  it is not an Android input-ANR deadline. Device evidence must determine the
  safe prewarm/launch policy; a heavy guest may still trigger an OEM/system ANR
  before this safety timeout.
- API 28-36/HyperOS evidence must prove process death, system-recents launch,
  timeout recycle, a later clean generation, and no stale capability replay.
- Engine authority still shares the default app process. Dedicated engine
  server isolation, per-guest-process records, runtime permissions,
  custom-process components, complete Service/Broadcast semantics, native
  linker/load, and the commercial compatibility matrix remain open.
- This is generic recovery infrastructure, not compatibility proof for GKD,
  AstroBox, QQ, WeChat, or QQ Reader. The commercial decision remains
  **BLOCK**.

## Implementation Update - 2026-07-13 Central Engine Ownership and High-Level IPC

This batch creates one in-process server graph before the engine authority is
moved to a dedicated process. The ordering was rechecked against VirtualApp
`7d739c85`, BlackBox `ffe950f7`, and DroidPlugin `c6ebf652`: the server must
own the mutable service graph and capability registries before clients are
forced onto remote facades. Moving the Provider first would split launch
capability generation from authorization.

Implemented:

- Added singleton `EngineServerRuntime`, which owns one
  `EngineRuntimeRegistry`, `VirtualSystemServer`, launch-capability registry,
  process-death registry, process control plane, and
  `DefaultVirtualizationEngineCore` graph.
- `DefaultVirtualizationEngine` now delegates to that owner instead of
  constructing another runtime graph.
- `IEngineRuntimeService` now exposes all six public `VirtualizationEngine`
  operations: install/refresh, create, launch, stop, query, and evidence
  export. The endpoint uses strict Bundle codecs and fails closed for malformed
  launch requests, missing runtimes, unavailable owners, and caller UID
  mismatches.
- Added `IpcVirtualizationEngine`; the production `AppModule` binding now
  exposes only this IPC facade. Mutations are not retried when the Binder result
  is unknown. Returned runtime identity must match the read-only durable
  snapshot before a complete runtime is returned to the caller.
- `EngineBinderProvider` obtains the singleton owner through a Hilt entry point
  and builds its endpoint entirely from that owner. The Provider deliberately
  remains in the host main process for this preparation batch.
- `EngineRuntimeServiceConnection` now creates a separate generation-bound
  death recipient for each Binder. A delayed callback from an old Binder cannot
  clear a newer connection, synchronous death is not published as live, and
  concurrent callers establish one connection.
- Endpoint, IPC codec, owner identity, reconnect race, and app DI/source
  boundary tests were added. Watchdog tests use prestarted executors so the
  test's 20 ms recovery deadline does not cancel an abandon task before its
  worker thread starts; production timeout policy is unchanged.

Verification:

- Final full local gate passed in 3m03s with 515 Gradle tasks executed or
  reused.
- Aggregated reports contain 1,970 tests, 0 failures, 0 errors, and 12 skipped.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 101,612,541 bytes.
- APK SHA-256:
  `2379DA191DEED50D19DEDACE2131B194BC7BD146DA214C2741A931FDD9863F11`.
- `git diff --check` passed; existing LF/CRLF conversion warnings remain.

Remaining gate:

- This is one owner graph, not yet one application-wide authority. Client and
  guest paths can still construct file-backed `DefaultVirtualSystemServer`
  instances, and `IpcBackedVirtual*Service` implementations still perform
  mutable local fallback after Binder failure.
- Instance create/delete paths still bypass the engine in UI/use-case code.
  Delete can remove data before runtime, capability, death registration,
  subsystem state, and slot ownership are released.
- Proxy Activity slot storage still has multiple process writers. A process
  lock or atomic file replacement alone would not establish server ownership.
- The next P0 batch must make non-server mutations fail closed, add the missing
  authoritative read/command APIs, and enforce a single writer for instance,
  slot, runtime, task, Provider, Service, Broadcast, permission, AppOps, and URI
  grant state. Only then may the Provider move atomically to `:engine` with
  Application process-role routing.
- No device artifact proves this owner/IPC batch. Commercial status remains
  **BLOCK**; it is not compatibility proof for GKD, AstroBox, QQ, WeChat, or
  QQ Reader.
