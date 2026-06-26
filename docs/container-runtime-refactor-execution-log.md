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

Working tree note:

- `LoaderFactory` outer-edge `BOOTSTRAP` wiring has been compiled and tested in
  the local working tree, but `LoaderFactory.kt` already contains unrelated
  dirty QQ Reader experiments. It must be isolated into a separate focused
  commit before being treated as delivered.

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
