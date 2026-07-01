# v2 PR-6 Activity lifecycle baseline evidence

Date: 2026-06-30
Scope: recommended PR graph `PR-6 Activity lifecycle baseline` from `v2-container-maturity-execution-blueprint-2026-06-29.md`.

## What changed

PR-6 adds an evidence-backed ordinary hosted Activity lifecycle baseline on top of the PR-5 LoadedApk/context sandbox path.

Runtime changes:

- `VirtualInstrumentation` now records hosted guest Activity lifecycle evidence from instrumentation callbacks instead of relying only on proxy Activity callbacks.
- Hosted guest intents preserve `multiapp.virtualActivityToken`, allowing lifecycle evidence to resolve the `VirtualActivityRecord` that was allocated for the proxy launch.
- `onNewIntent` writes explicit evidence after restoring the original guest intent from the proxy intent.
- Activity result baseline writes explicit unsupported evidence when full request/result routing is not implemented.
- Launcher proxy allocation can preserve the guest launcher Activity launch mode, so the minimal fixture can exercise `singleTop` / `onNewIntent` through the existing proxy slot pool.

Fixture/test changes:

- Minimal fixture `MainActivity` is `singleTop` and exposes an explicit `onNewIntent` probe.
- Minimal fixture still exposes `MainActivity -> SecondActivity`; `SecondActivity` now has an explicit finish button for manual Back-equivalent validation.
- Baseline instrumentation test performs a second launch of the same hosted instance to trigger `singleTop` / `onNewIntent` evidence, then verifies that pending new intent was consumed.

## Which kernel gate it moves

Blueprint Phase 4 / Roadmap Phase D: ActivityThread + LoadedApk sandbox closure.

This PR moves only the ordinary Activity lifecycle baseline portion:

```text
minimal app first screen visible
Activity A -> B -> Back works path exists in the fixture
onNewIntent evidence exists
Activity result baseline is explicit unsupported evidence
```

It does not complete Virtual AMS, PMS, Provider, Storage, native IO, or protected-app compatibility.

## Evidence added

New component-scoped files under `files/hosted_launch_evidence`:

```text
<instanceId>.activity-lifecycle.properties  # appended event snapshots separated by ---
<instanceId>.activity-new-intent.properties
<instanceId>.activity-result.properties
```

Expected lifecycle fields include:

```text
status=GUEST_ACTIVITY_LIFECYCLE
stage=ACTIVITY_LIFECYCLE
event=onCreate|onStart|onResume|onPause|onStop|onDestroy|onNewIntent
instanceId=<instanceId>
guestActivityClassName=<guest activity>
token=<virtual activity token>
activityRecordFound=true|false
isFinishing=true|false
taskId=<host task id>
```

Expected new-intent fields include:

```text
status=GUEST_ACTIVITY_ON_NEW_INTENT
stage=ACTIVITY_NEW_INTENT
pendingNewIntentConsumed=true|false
pendingAction=<guest action>
pendingDataUri=<guest data>
pendingFlags=<flags>
sourceToken=<source virtual token>
reason=<empty or explicit reason>
```

Expected activity-result baseline fields include:

```text
status=ACTIVITY_RESULT_UNSUPPORTED
stage=ACTIVITY_RESULT_BASELINE
resultSupported=false
unsupportedReason=HOST_PROXY_RESULT_ROUTING_NOT_IMPLEMENTED
```

Existing PR-5 files are preserved:

```text
<instanceId>.activity-instrumentation.properties
<instanceId>.activity-context.properties
<instanceId>.activity-remap.properties
```

## Evidence still missing

- Full Activity result request/result routing is not implemented; PR-6 records explicit unsupported evidence instead.
- Full Virtual AMS dispatcher is still PR-8 scope.
- Device/manual evidence still needs to record actual device model/API/ABI/page-size when run.
- Broader Activity lifecycle matrix (`singleTask`, `CLEAR_TOP`, multi-task, orientation/config changes) remains future work.

## Verification commands and status

Planned commands from repo root:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:testDebugUnitTest :test-fixtures:minimal-app:assembleDebug :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Device/instrumented verification when a device is available:

```powershell
.\gradlew.bat --no-parallel :app:connectedDebugAndroidTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

## Device / ADB status

Pending until run on a connected Android device.

## Protected runtime hook-free status

PR-6 does not enable default LSPlant, Xposed, business native stubs/wrappers, no-op patches, or protected-app patching.

`ContainerActivity.createIntent(..., providerHookEnabled = false)` remains the default path.

## Follow-up

- PR-8 Virtual AMS dispatcher should unify Activity/Service/Broadcast dispatch evidence.
- Future Activity result support should replace the explicit unsupported baseline with PASS evidence only after requestCode/result routing works end to end.
