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
- Service foreground/sticky/bind semantics.
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
4. Replace the reflective Application creator with a device-tested
   `LoadedApk.makeApplication()`-equivalent creator behind the new
   `GuestApplicationCreator` seam.
