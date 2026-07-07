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

Result:

- All commands above passed.
- `git diff --check` passed.
- Known warning remains: AGP `8.7.3` was tested up to `compileSdk=35`, while
  the project uses `compileSdk=36`.

## Not Complete Yet

The following items from the plan are still open and must not be marked `DONE`:

- Full split APK / dynamic feature / multidex import and runtime loading.
- Full `LoadedApk.makeApplication()`-equivalent application creation model.
- Engine-owned hosted runtime binding for `ContainerActivity` and
  `Hosted*RuntimeBinder`; current container bootstrap still creates parts of the
  runtime directly.
- Real Android process-slot/proxy-slot allocation for simultaneous same-origin
  multi-instance recents.
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

1. Move hosted runtime binding behind `:core:engine` for `ContainerActivity`,
   `HostedActivityRuntimeBinder`, `HostedProviderRuntimeBinder`, and
   `HostedServiceRuntimeBinder`.
2. Make process/proxy slot assignment persistent per instance and feed it into
   actual stub/proxy component selection.
3. Start split APK metadata support in install records and
   `VirtualPackageSnapshot`.
4. Add behavior tests for forged provider URI rejection and same-origin
   dual-instance isolation.
