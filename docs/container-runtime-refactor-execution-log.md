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
