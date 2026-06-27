# Container Runtime Refactor Execution Log

Date: 2026-06-26

Branch: `container-runtime-refactor`

Roadmap: `docs/multiapp-container-lsplant-roadmap.md`

## Completed In This Pass

1. Added the container-first model foundation in `core/model`:
   - `CompatibilityMode`
   - `InstallArtifactManifest`
   - `VirtualPackageRecord`
   - `VirtualInstanceRecord`
2. Added runtime bootstrap evidence primitives in `core/loader`:
   - `RuntimeStage`
   - `BootstrapStatus`
   - `BootstrapEvidence`
   - `BootstrapResult`
3. Added protected-app hook-free policy primitives in `core/hook`:
   - `ProtectedAppBaselineMode`
   - `NativeHookPolicy`
   - `NativeHookCapability`
4. Fixed hook module JVM unit-test configuration with
   `unitTests.isReturnDefaultValues = true`, so existing `android.util.Log`
   calls do not block local unit tests.

## Verification

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain
```

Result: `BUILD SUCCESSFUL`

## Scope Note

- This pass does not claim QQ Reader is fixed.
- This pass establishes the first executable foundation for the roadmap:
  container baseline data models, staged runtime evidence, and hook-free
  protected-app policy switches.
- LSPlant, Xposed modules, business native stubs, method replacement, and
  no-op patches remain optional and disabled for the protected-app baseline
  path.

## Next Execution Target

Move from passive records to the first runtime integration slice:

1. Persist `VirtualPackageRecord` and `VirtualInstanceRecord` through the
   installer/instance boundary.
2. Emit `BootstrapResult` from the outer edge of `LoaderFactory` without
   changing behavior.
3. Wire `ProtectedAppBaselineMode.strict()` into QQ Reader baseline launch
   selection.
4. Add a `Jiagu360Profile` detect/verify skeleton that records evidence only
   and does not patch business behavior.

## 2026-06-26 Follow-up Slice

Completed:

1. Added `Jiagu360Profile` as a pure evidence profile:
   - detects `libjiagu*.so`
   - detects `com.stub.StubApp` / `com.qihoo.util.StubApp`
   - verifies loaded Jiagu native library evidence
   - verifies original-shell `RegisterNatives` evidence for
     `com.stub.StubApp`
   - rejects MultiApp fallback `RegisterNatives` as success evidence
   - treats `UnsatisfiedLinkError: com.stub.StubApp.interface20` as
     incomplete verification
2. Added `Jiagu360ProfileTest`.

Verification:

```powershell
.\gradlew.bat :core:hook:testDebugUnitTest --tests com.multiapp.core.hook.Jiagu360ProfileTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain
.\gradlew.bat :core:hook:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain
```

Result: `BUILD SUCCESSFUL`

Next target is now the first integration point: feed real runtime/log evidence
into `Jiagu360Profile` without enabling fallback stubs, LSPlant, Xposed modules,
method replacement, or no-op patches in protected-app baseline mode.

## 2026-06-26 Runtime Evidence Recorder Slice

Completed:

1. Added `RuntimeBootstrapRecorder` as an in-process recorder for
   `BootstrapResult` events.
2. Added `RuntimeBootstrapRecorderTest`.
3. Validated the recorder together with the existing `RuntimeBootstrapTest`.

Follow-up integration:

- Isolated `LoaderFactory` outer-edge `BOOTSTRAP` wiring from unrelated dirty
  QQ Reader experiments.
- Emits `BootstrapResult` records for:
  - `CONFIG`
  - `GUEST_CONTEXT`
  - `PACKAGE_METADATA`
  - `ORIGIN_APK`
  - `NATIVE_LIBS`
  - `CLASS_LOADER`
  - `APPLICATION`
- Records failure at the current runtime stage before dumping debug logs and
  rethrowing.

Workbench:

- Added `docs/container-runtime-refactor/` as the branch workbench for plans,
  migration notes, evidence, and draft patches.
- Runtime source remains in canonical Gradle module paths so each slice stays
  buildable and testable.

Verification:

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests com.multiapp.core.loader.RuntimeBootstrapRecorderTest --tests com.multiapp.core.loader.RuntimeBootstrapTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain
```

Result: `BUILD SUCCESSFUL`

Runtime note:

- The intended runtime log marker is `BOOTSTRAP stage=<stage> status=<status> ...`.
- This is still an evidence layer only. It does not make LSPlant, Xposed,
  native business stubs, method replacement, or no-op patches part of the
  protected-app baseline path.

## 2026-06-26 Route v2 Clarification

Completed:

1. Updated `docs/multiapp-container-lsplant-roadmap.md` with a v2 authoritative
   route section.
2. Clarified that the current `Stub clone APK + LoaderFactory` path is a
   transitional container, not the final architecture.
3. Set the target route as a user-space virtual install container:
   - `VirtualPackageRecord` / `VirtualInstanceRecord`
   - virtual PMS / AMS / Provider / Storage
   - staged `RuntimeBootstrap`
   - profile-controlled native diagnostics
   - optional LSPlant/Xposed runtime
4. Added hard rules that QQ Reader must default to `CloneProfile.NORMAL` and
   protected baseline, while `QQ_READER_SPECIAL` remains only a manual
   legacy/diagnostic comparison path.

QQ Reader baseline evidence:

```text
.tmp\qqreader-baseline-20260626-174957-summary.txt
```

Result:

```text
cloneProfile=NORMAL
policyMode=BASELINE
lsPlantEnabled=false
xposedModulesEnabled=false
businessNativeStubsEnabled=false
methodReplacementEnabled=false
noOpPatchesEnabled=false
BOOTSTRAP CONFIG..APPLICATION = SUCCESS
crash=UnsatisfiedLinkError: com.stub.StubApp.interface20()
```

Engineering interpretation:

- The baseline reached `APPLICATION SUCCESS`, so the next problem is not early
  container bootstrap.
- The crash shows the original 360 shell `RegisterNatives` path did not bind
  `com.stub.StubApp.interface20` in the current container environment.
- The next phase is `NativeDiagnosticsProfile(register-natives-only)`, not a
  return to QQ Reader special patching, business stubs, no-op patches, or
  default LSPlant.

## 2026-06-26 Route v2 Review Hardening

Completed:

1. Reviewed the external multi-role route review document:
   `D:\wxjl\xwechat_files\wxid_tkcgnu0ggpcs22_00a3\msg\file\2026-06\doc-review-multiapp-lsplant-roadmap-2026-06-26.md`.
2. Strengthened the v2 authoritative route in
   `docs/multiapp-container-lsplant-roadmap.md` with explicit gates for:
   - open-source learning scope, shell-compatibility boundaries, and commercial
     multi-app product references
   - ordinary App regression before protected-App conclusions
   - Phase A-G / Phase 0-8 / Sprint 1.x mapping
   - Stub transitional implementation retirement criteria
   - `interface20` root-cause matrix
   - stage reversibility and rollback semantics
   - device matrix, ADB evidence template, and CI gates
   - baseline permanent failure and Plan B decisions

Result:

- The route remains container-first and hook-free baseline first.
- QQ Reader remains a protected baseline diagnostic target. The target is to
  make the original shell initialize normally in a compatible container
  environment, not to damage, replace, or bypass the shell with default
  `QQ_READER_SPECIAL`, business native stubs, method replacement, no-op patches,
  or default LSPlant.

## 2026-06-26 Route v2 Scope Correction

Correction:

1. Reworded the v2 route to state that MultiApp is an open-source learning and
   container research project.
2. Reframed protected-App support as shell compatibility: preserve the original
   shell and make the container match real install-state expectations.
3. Added commercial multi-app products as market references while avoiding
   assumptions about their internal permissions, OEM relationships, or risk
   handling.
4. Changed Plan B ordering so the first fallback is to continue fixing the
   container kernel, not to prematurely mark a protected App as unsupported.

## 2026-06-26 v2 In-Repo Kernel Rewrite Plan

Completed:

1. Added `docs/container-runtime-refactor/v2-in-repo-kernel-rewrite-plan.md` as
   the concrete execution plan for the full MultiApp v2 rewrite.
2. Locked the execution decision as an in-repo kernel rewrite:
   - keep the current repository, branch, build system, device evidence, and
     legacy runtime as comparison assets
   - freeze `Stub clone APK + LoaderFactory` feature growth
   - build the new container kernel in canonical Gradle modules
   - gradually move creation, instance identity, virtual services, bootstrap,
     and diagnostics to v2 boundaries
3. Added roadmap and docs index links so the rewrite plan becomes the active
   execution entry point.

Next implementation target:

```text
VirtualInstallService + InstallRecordStore
-> InstanceManager + VirtualInstanceRecord persistence
-> Hosted Container Launch MVP
-> Virtual PMS / AMS / Provider / Storage minimum loop
```

## 2026-06-26 v2 Final-State Execution Correction

Correction:

1. Updated the rewrite plan to remove the Step 4 transitional Stub launcher
   path for v2 new instances.
2. Replaced Step 4 with `Hosted Container Launch MVP`: MultiApp starts a
   hosted container entry by `instanceId`, then `RuntimeBootstrap` consumes
   `VirtualInstanceRecord` and `InstallRecord` directly.
3. Clarified that v2 new instances must not generate or install new Stub APKs.
4. Kept existing Stub APK / `LoaderFactory` only as legacy evidence and
   comparison paths for old clones such as the current QQ Reader clone.

Next implementation target:

```text
ContainerActivity / hosted container entry
-> HostedRuntimeBootstrap consumes instanceId
-> load origin APK dex/resources/native libs
-> create guest Application or emit precise failed stage
-> minimal test app launch baseline
```

## 2026-06-27 v2 Hosted Container Audit

Completed:

1. Ran multi-role review across architecture, Android runtime/container,
   testing, and protected-app compatibility.
2. Added `docs/container-runtime-refactor/v2-hosted-container-audit-remediation-2026-06-27.md`.

Audit conclusion:

```text
No-Go: v2 direction is correct, but Hosted Container execution cannot be
marked complete yet.
```

Main blockers:

1. Creation flow does not reliably import/create `InstallRecord` before
   creating launchable `VirtualInstanceRecord`.
2. `ContainerActivity` and `HostedRuntimeBootstrap` do not launch a guest
   launcher Activity yet.
3. Guest `Application.onCreate`, resources/assets/theme, virtual
   PackageManager, and nativeLibraryDir are not closed.
4. Current tests are mostly JVM contract/synthetic evidence tests; real
   `ContainerActivity`, minimal APK, dual-instance, and QQ Reader device
   evidence are still missing.
5. protected baseline still has native hook / register-natives wrapper risks
   that must be split into observe-only diagnostics and explicit compatibility
   capabilities.
