# PR-8 AMS API Device Evidence Design

Date: 2026-07-03
Owner: hosted container runtime
Status: approved design draft

## Goal

Add device-verifiable hosted evidence for the PR-8 Virtual AMS API edges that are not proven by the current baseline evidence:

1. guest `registerReceiver(...)`
2. guest `sendStickyOrderedBroadcast(...)`
3. guest `bindService(...)` overload blocking

The evidence must come from the hosted guest production path, not only from direct wrapper tests or logcat inference.

## Context

Current manual device capture on `192.168.2.42:36339` produced hosted evidence for Activity, Service, Broadcast, Provider, PMS, LoadedApk sandbox, lifecycle, and onNewIntent paths. It does not distinguish whether the guest exercised dynamic receiver registration, sticky ordered broadcast overloads, or bindService overloads.

Blueprint completion rules still apply:

- Class existence and JVM tests are insufficient.
- Device evidence must be explicit.
- Avoid overclaiming `Virtual AMS complete` until all gate evidence exists.

## Recommended Approach

Implement a small PR-8 AMS API evidence slice:

- Add a lightweight AMS API evidence recorder contract in `core/loader`.
- Install a file-backed app recorder that writes to `files/hosted_launch_evidence`.
- Have `VirtualContextWrapper` and API-gated wrappers emit evidence at the relevant API interception points.
- Extend the minimal guest fixture so hosted guest code really calls the APIs.
- Add a dedicated androidTest that creates a hosted instance, launches it, and asserts the evidence files.

This keeps the evidence tied to the production hosted path while staying narrow.

## Evidence Files

Write component-scoped evidence files under the existing directory:

```text
files/hosted_launch_evidence/<instanceId>.ams-register-receiver.properties
files/hosted_launch_evidence/<instanceId>.ams-sticky-ordered-broadcast.properties
files/hosted_launch_evidence/<instanceId>.ams-bind-service-overload.properties
```

Required shared fields:

```text
stage=AMS_API_OVERLOAD
instanceId=<instanceId>
originPackageName=com.test.minimal
virtualPackageName=com.multiapp.instance...
hostFallback=false
```

### registerReceiver evidence

Minimum fields:

```text
status=DYNAMIC_RECEIVER_REGISTERED
api=registerReceiver
registered=true
hostFallback=false
```

This evidence is emitted after `VirtualContextWrapper.registerReceiver(receiver, filter)` accepts and stores the dynamic receiver in `VirtualDynamicReceiverRegistry`.

### sticky ordered broadcast evidence

Minimum fields:

```text
status=STICKY_ORDERED_INTERCEPTED
api=sendStickyOrderedBroadcast
hostFallback=false
dispatchStatus=<Delivered|ReceiverNotFound|...>
```

This evidence is emitted from the sticky ordered overload path after the overload is intercepted; `dispatchStatus` records the current virtual dispatcher result.

### bindService overload evidence

Minimum fields:

```text
status=BIND_BLOCKED
api=<bindService:int|bindService:executor|bindService:flags|bindService:flags-executor>
returnValue=false
hostFallback=false
serviceResolved=<true|false>
reason=<explicit|unsupportedServiceIntent|...>
```

The bindService overloads remain intentionally blocked for now: resolving guest service evidence is useful, but the call must not fall through to the host `Context.bindService`.

API 34+ `Context.BindServiceFlags` signatures remain in API-gated wrapper classes to preserve minSdk 28 class-loading safety.

## Minimal Guest Fixture Behavior

Extend `test-fixtures/minimal-app` with a PR-8 probe invoked during hosted `MainActivity.onCreate()` or an equivalent deterministic hosted launch path.

The probe should:

1. Register a dynamic receiver with an explicit action.
2. Send a broadcast for that dynamic receiver, if needed to show registration participates in virtual dispatch.
3. Call `sendStickyOrderedBroadcast(...)` with a guest receiver component and a simple result receiver.
4. Call at least the `bindService(Intent, ServiceConnection, int)` overload and record that the return value is `false`.
5. On API levels where available, call executor and `BindServiceFlags` overloads so SDK36 device evidence covers the current overload surface.

The fixture may log its local probe output for human debugging, but logcat is not the source of truth. The hosted evidence files are the source of truth.

## Android Instrumentation Test

Add a dedicated test class:

```text
app/src/androidTest/java/com/multiapp/app/HostedContainerPr8AmsApiEvidenceTest.kt
```

The test should reuse the existing baseline pattern:

1. Ensure `com.test.minimal` is installed.
2. Import it into `JsonInstallRecordStore`.
3. Create a hosted instance via `DefaultInstanceManager`.
4. Launch `ContainerActivity` for that instance.
5. Wait for required evidence files.
6. Assert exact lines or prefixes in the three new evidence files.

Required assertions:

```text
ams-register-receiver:
  stage=AMS_API_OVERLOAD
  api=registerReceiver
  status=DYNAMIC_RECEIVER_REGISTERED
  hostFallback=false

ams-sticky-ordered-broadcast:
  stage=AMS_API_OVERLOAD
  api=sendStickyOrderedBroadcast
  status=STICKY_ORDERED_INTERCEPTED
  hostFallback=false

ams-bind-service-overload:
  stage=AMS_API_OVERLOAD
  status=BIND_BLOCKED
  returnValue=false
  hostFallback=false
```

The test may accept multiple bind overload entries in one evidence file if the writer appends stable numbered fields, but the first slice should keep parsing simple and deterministic.

## TDD Plan

Follow RED/GREEN/REFACTOR:

1. Write `HostedContainerPr8AmsApiEvidenceTest` first.
2. Run the targeted androidTest and verify it fails because the new evidence files are missing.
3. Add the minimal recorder and wrapper evidence implementation.
4. Extend the minimal guest probe.
5. Rerun the targeted androidTest and verify it passes.
6. Run loader JVM tests that cover wrapper API behavior.
7. Capture device evidence from `192.168.2.42:36339` without clearing logcat unless explicitly authorized.

## Verification Commands

Build/install minimal fixture when needed:

```bash
./gradlew.bat :test-fixtures:minimal-app:assembleDebug --console=plain --no-build-cache
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 install -r "test-fixtures/minimal-app/build/outputs/apk/debug/minimal-app-debug.apk"
```

Run targeted device test:

```bash
ANDROID_SERIAL=192.168.2.42:36339 ./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.multiapp.app.HostedContainerPr8AmsApiEvidenceTest" --console=plain --no-build-cache
```

Capture hosted evidence:

```bash
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 shell run-as com.multiapp.app ls files/hosted_launch_evidence
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 shell run-as com.multiapp.app cat files/hosted_launch_evidence/<instanceId>.ams-register-receiver.properties
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 shell run-as com.multiapp.app cat files/hosted_launch_evidence/<instanceId>.ams-sticky-ordered-broadcast.properties
"/c/Users/Administrator/.openclaw/workspace/apk_analysis/platform-tools/adb.exe" -s 192.168.2.42:36339 shell run-as com.multiapp.app cat files/hosted_launch_evidence/<instanceId>.ams-bind-service-overload.properties
```

## Out of Scope

- Implementing real guest bound-service lifecycle.
- Claiming full `Virtual AMS complete`.
- Adding IActivityManager or IActivityTaskManager global proxies.
- Full sticky broadcast semantics compatibility beyond proving the overload is intercepted and does not fall back to host.
- Protected app compatibility claims.

## Completion Wording

Allowed:

```text
PR-8 AMS API专项 device evidence added for registerReceiver, sticky ordered broadcast, and bindService overload blocking.
```

Not allowed yet:

```text
Virtual AMS complete.
```

## Spec Self-Review

- Placeholder scan: no TODO/TBD placeholders remain.
- Internal consistency: evidence file names, fields, and test assertions all refer to the same three API surfaces.
- Scope check: one focused PR-8 device evidence slice; no global AMS proxy or bound-service lifecycle implementation is included.
- Ambiguity check: bindService remains explicitly blocked; sticky ordered evidence proves interception/dispatch, not full sticky semantics compatibility.
