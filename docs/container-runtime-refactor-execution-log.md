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
