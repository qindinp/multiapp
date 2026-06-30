# PR-5 LoadedApk Sandbox Device Evidence

Date: 2026-06-30
Branch: container-runtime-refactor
Scope: Blueprint Phase 4 / Roadmap Phase D / ActivityThread + LoadedApk sandbox closure
Status: implementation evidence added; ordinary hosted launch device evidence passed; post-review identity/storage hardening verified locally

## Scope

This PR targets ordinary hosted-app ActivityThread/LoadedApk evidence only.

In scope:

- Activity context evidence enrichment.
- `LoadedApkRuntimeState` virtual package identity for ActivityThread sandbox construction.
- ActivityClientRecord `ActivityInfo` / `ApplicationInfo` virtual package evidence.
- Origin + virtual package aliases in ActivityThread package maps.

Out of scope:

- PR-6 lifecycle matrix: Activity A -> B -> Back, `onNewIntent`, activity result baseline.
- Global PMS proxy hooks.
- Virtual AMS dispatcher.
- Provider method coverage.
- Java absolute path and native IO redirect.
- QQ Reader/protected-app compatibility.

## Required minimum evidence

```text
activityRecordPatchedFields=activityInfo,intent,packageInfo
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount>=2
contextInjected=true
activityInfo.packageName=<virtualPackageName>
applicationInfo.packageName=<virtualPackageName>
dataDir=<instance dataRoot>
```

## JVM verification

Focused command:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon --tests "com.multiapp.core.loader.HostedActivityIdentityTest" --tests "com.multiapp.core.loader.HostedActivityContextEvidenceFormatterTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.ActivityClientRecordBridgeTest"
```

Focused result:

```text
PASS on 2026-06-30: 102 actionable tasks, 9 executed, 93 up-to-date.
```

Full command:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Full result:

```text
PASS on 2026-06-30: BUILD SUCCESSFUL in 46s; 102 actionable tasks, 1 executed, 101 up-to-date.
```

## App build verification

Command:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Result:

```text
PASS on 2026-06-30: BUILD SUCCESSFUL in 3m 20s; 432 actionable tasks, 59 executed, 373 up-to-date.
```

## Device evidence command sequence

ADB path:

```powershell
C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe
```

Commands:

```powershell
$ADB="C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
& $ADB devices
& $ADB shell getprop ro.product.model
& $ADB shell getprop ro.build.version.sdk
& $ADB shell getprop ro.product.cpu.abi
& $ADB shell getconf PAGESIZE
& $ADB install -r app\build\outputs\apk\debug\app-debug.apk
& $ADB logcat -c
```

Manual action:

```text
Open MultiApp, launch a minimal or ordinary hosted instance, wait until the first guest Activity is visible or the launch fails.
```

Capture:

```powershell
& $ADB logcat -d -t 15s > .tmp\pr5-loadedapk-logcat.txt
& $ADB shell run-as com.multiapp.app ls files/hosted_launch_evidence
& $ADB shell run-as com.multiapp.app cat files/hosted_launch_evidence/<instanceId>.activity-context.properties
```

## Device verdict

```text
Verdict: PASS
Device model: 2509FPN0BC
Android API: 36
ABI: arm64-v8a
Page size: 4096
Evidence file: files/hosted_launch_evidence/354bbb6b-929d-4220-bc37-ef0be785b6f3.activity-context.properties
Instrumentation evidence file: files/hosted_launch_evidence/354bbb6b-929d-4220-bc37-ef0be785b6f3.activity-instrumentation.properties
Launch evidence file: files/hosted_launch_evidence/354bbb6b-929d-4220-bc37-ef0be785b6f3.launch.properties
Logcat capture: .tmp/pr5-loadedapk-logcat.txt

Observed fields:
activityRecordPatchedFields=activityInfo,intent,packageInfo
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount=4
contextInjected=true
activityInfo.packageName=com.multiapp.instance.354bbb6b929d
applicationInfo.packageName=com.multiapp.instance.354bbb6b929d
dataDir=/data/user/0/com.multiapp.app/files/instance_data/354bbb6b-929d-4220-bc37-ef0be785b6f3

Additional evidence:
loadedApkInstalledAliasesByField=mPackages:com.test.minimal,com.multiapp.instance.354bbb6b929d;mResourcePackages:com.test.minimal,com.multiapp.instance.354bbb6b929d
loadedApkSkippedReason=
activityRecordSkippedReason=
instrumentation.status=GUEST_ACTIVITY_SUBSTITUTED
instrumentation.proxyActivityClassName=com.multiapp.app.container.ProxyActivity0
instrumentation.guestActivityClassName=com.test.minimal.SecondActivity
launch.status=PROXY_LAUNCHED
logcat.crashCheck=No FATAL EXCEPTION or Process: com.multiapp.app crash entry found; AndroidRuntime entry observed was VM exiting with result code 0.

Conclusion:
PR-5 LoadedApk sandbox and ActivityThread record patch evidence passed for this ordinary hosted launch. Broader Activity lifecycle, PMS/AMS, provider, storage/native, and protected-app gates remain out of scope and PARTIAL.
```

## Post-review hardening verification

After pre-build code review on 2026-06-30, PR-5 identity/storage hardening was added:

- Runtime `ApplicationInfo.processName` / `taskAffinity` now derive from the package snapshot or origin identity instead of host source identity.
- Runtime `ApplicationInfo.deviceProtectedDataDir` is rewritten to the virtual instance data root when the platform field is present.
- Matched and fallback `ActivityInfo` records preserve guest process/task identity; fallback records default to `exported=false`.
- `LoadedApkBridge` patches credential/device protected LoadedApk path fields from matching `ApplicationInfo` fields and falls back to `dataDir` when those fields are unavailable.

Post-review local verification:

```text
PASS on 2026-06-30: focused HostedActivityIdentityTest + LoadedApkBridgeTest.
PASS on 2026-06-30: PR-5 focused loader tests.
PASS on 2026-06-30: full :core:loader:testDebugUnitTest.
PASS on 2026-06-30: :core:loader:testDebugUnitTest :app:assembleDebug; BUILD SUCCESSFUL in 2m 14s; 432 actionable tasks, 18 executed, 414 up-to-date.
```

This post-review pass did not rerun the manual device launch; the device verdict below remains the recorded ordinary-hosted-launch evidence for instance `354bbb6b-929d-4220-bc37-ef0be785b6f3`.

## Protected runtime defaults

This PR does not enable protected-app hooks by default:

```text
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
```

## Remaining gaps after PR-5

```text
PR-6 Activity lifecycle baseline remains: Activity A -> B -> Back, onNewIntent evidence, and activity result baseline or explicit unsupported reason.
PR-7 Virtual PMS global proxy remains.
PR-8 Virtual AMS dispatcher remains.
PR-9 Provider method coverage remains.
PR-10 Java + native storage redirect remains.
PR-11 protected app register-natives-only diagnostics remains.
```

## Current conclusion

```text
Implementation-side PR-5 evidence fields are added, focused JVM tests pass, full core loader tests pass, app assembleDebug passes, and ordinary-hosted-launch device evidence passed for instance 354bbb6b-929d-4220-bc37-ef0be785b6f3.
This does not complete PR-6 lifecycle, Virtual PMS/AMS, provider, storage/native, or protected-app gates.
```
