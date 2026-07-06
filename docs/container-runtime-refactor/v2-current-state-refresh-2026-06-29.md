# v2 Current-State Refresh

Date: 2026-06-29
Owner: hosted container runtime
Review model: Xiaomi-style role split + owner gate

## 0. Summary

This document refreshes the current state of the `container-runtime-refactor` branch after the latest hosted-container work. It does not replace the seven-gate evidence standard in [v2-seven-kernel-gap-execution-2026-06-29.md](v2-seven-kernel-gap-execution-2026-06-29.md).

```text
Current owner verdict:
- v2 direction is correct.
- hosted user-space container MVP has substantial implementation.
- all seven kernel gates remain PARTIAL unless later direct evidence says otherwise.
- QQ Reader/protected-app compatibility is not complete.
```

## 1. Safe current-state claims

The following claims are safe if stated with their evidence limits:

```text
1. The v2 hosted launch path uses InstallRecord + VirtualInstanceRecord as runtime input.
2. ContainerActivity is the internal hosted entry point for launching by instanceId.
3. HostedRuntimeBootstrap loads instance/install records, resolves origin APK, extracts native libraries when possible, creates package snapshots, creates guest ClassLoader, attaches guest Application, calls Application.onCreate(), and resolves a launcher Activity.
4. VirtualProcessRuntime provides a process-local runtime reuse boundary by instanceId.
5. Host manifest declares ContainerActivity, six ProxyActivity slots, StubService, and StubContentProvider.
6. VirtualInstrumentation is installed in the host process and contains proxy-to-guest Activity substitution plus partial startActivity remapping.
7. LoadedApk handling has bridge/installer/alias support, but device proof is still required.
8. Context-local PackageManager virtualization exists through VirtualContextWrapper and VirtualPackageManagerWrapper.
9. Provider dispatch exists through StubContentProvider and VirtualProviderDispatcher, but full method/permission/cross-process coverage is not proven.
10. Java Context storage redirection exists for files/cache/db/shared-prefs/external dirs.
11. QQ Reader hosted diagnostics are profile-gated and must remain observe-only by default.
```

Unsafe claims:

```text
container complete
QQ Reader fixed
protected apps compatible
Virtual PMS complete
Virtual AMS complete
LoadedApk sandbox complete
native storage redirect complete
```

## 2. Current-state status table

| Area | Current implementation | Verified by | Current status | Must not claim | Next evidence needed |
| --- | --- | --- | --- | --- | --- |
| Manifest / host surface | `ContainerActivity`, six proxy Activity slots, `StubService`, `StubContentProvider` are declared. | Source review; app build records in previous docs. | COMPLETE for declared host surface only | Full runtime compatibility | Keep manifest comments aligned with dispatcher reality. |
| HostedRuntimeBootstrap | Loads instance/install/APK/native libs/package snapshot/classloader/Application/launcher. | JVM tests and recorded Gradle success; source review. | PARTIAL+ | App launch complete | Device evidence from ContainerActivity to guest first screen. |
| VirtualProcessRuntime | Caches successful runtime by instanceId. | Unit/source review. | PARTIAL+ | Full process manager | Evidence that duplicate Application creation is avoided on warm launch. |
| Instrumentation install/remap | Host app installs `VirtualInstrumentation`; `newActivity` substitution and partial `execStartActivity` remap exist. | Source review and tests. | PARTIAL | Full AMS/ATM interception | Activity chain/newIntent/result/back-stack device evidence. |
| ActivityThread launch-record repair | `ActivityClientRecordBridge` patches fields by Activity token. | Unit tests and docs. | PARTIAL | ClientTransaction pre-attach completeness | Device evidence: `activityRecordPatchedFields=activityInfo,intent,packageInfo`. |
| LoadedApk/package map aliasing | `LoadedApkBridge` and `ActivityThreadLoadedApkInstaller` exist with sandbox/fallback concepts. | Unit tests and source review. | PARTIAL | Complete LoadedApk sandbox | Device evidence: `loadedApkSource=GUEST_SANDBOX`, alias count >= 2. |
| PMS / PackageManager | `VirtualPackageManagerWrapper` is exposed through `VirtualContextWrapper.getPackageManager()`. | Unit tests/source review. | PARTIAL | Global PMS complete | AppGlobals/IPackageManager/ApplicationPackageManager.mPM evidence. |
| AMS / Activity management | `VirtualActivityManager`, `VirtualInstrumentation`, `VirtualIntentResolver` map some Activity launches to proxy slots. | Unit/source review. | PARTIAL | Full Virtual AMS | Unified dispatcher, task/back-stack/result/start overload evidence. |
| Service dispatcher | `VirtualServiceManager`, `VirtualServiceRuntime`, `StubService`, Context start/stop mapping exist. | Unit/source review. | PARTIAL | Full Service lifecycle | start/stop/foreground/bind/failure evidence on device. |
| Broadcast/receiver dispatcher | `VirtualBroadcastManager`, receiver runtime/registry, Context send/register mapping exist. | Unit/source review. | PARTIAL | Full broadcast semantics | static/dynamic/ordered/sticky/cross-process scope evidence or unsupported reason. |
| Provider dispatcher | `StubContentProvider`, `VirtualProviderDispatcher`, provider runtime exist; query/insert/update/delete/call paths dispatch. | Unit/source review. | PARTIAL+ | Complete provider virtualization | openFile/openAssetFile/bulkInsert/permission/observer evidence. |
| Storage / Context redirection | `VirtualContextWrapper` redirects Context files/cache/db/shared-prefs/external dirs. | Unit/source review; manual evidence records in docs. | PARTIAL+ | Native storage complete | Java absolute path and native open/openat/stat/access evidence. |
| Native library path/native namespace | Bootstrap extracts ABI-matching libraries under instance dataRoot/lib and passes nativeLibraryDir to classloader. | Unit/source review. | PARTIAL | Native namespace correctness | maps/linker/native load/RegisterNatives evidence. |
| QQ Reader/protected diagnostics | Hosted diagnostics tool and profile gates exist; legacy QQ Reader hooks are not baseline. | Source/docs review. | PARTIAL/profile-gated | QQ Reader compatibility | specific `interface20Verdict` without default LSPlant/Xposed/no-op/business wrappers. |

## 3. Verification commands

### 3.1 Previously recorded successful commands

These commands are copied from prior execution records, especially [v2-seven-kernel-gap-execution-2026-06-29.md](v2-seven-kernel-gap-execution-2026-06-29.md). They are historical evidence for the branch state at that time, not fresh verification for this documentation-only PR.

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

```text
recordedResult=BUILD SUCCESSFUL
source=v2-seven-kernel-gap-execution-2026-06-29.md
```

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

```text
recordedResult=BUILD SUCCESSFUL
source=v2-seven-kernel-gap-execution-2026-06-29.md
```

### 3.2 Commands to run for future runtime PRs

Targeted runtime-unit verification:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualProviderDispatcherTest" --tests "com.multiapp.core.loader.VirtualContextStorageTest" --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --tests "com.multiapp.core.loader.QqReaderProfileTest" "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualPackageSnapshotFactoryTest" "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Device/runtime evidence commands, only valid with a current device serial:

```powershell
.\tools\qqreader-baseline\run-hosted-diagnostics-capture.ps1 -Device <serial> -InstanceId <instance-id>
```

If using hosted-container baseline scripts, record the exact script path, serial, command, and output directory in the PR body.

## 4. Test coverage inventory

Current useful coverage surfaces:

```text
core/loader/src/test/java/com/multiapp/core/loader/*
app/src/test/java/com/multiapp/app/container/*
app/src/androidTest/java/com/multiapp/app/HostedContainerMinimalBaselineTest.kt
```

Important limits:

```text
1. JVM tests prove deterministic resolver/dispatcher/bridge behavior, not real Android framework compatibility.
2. app androidTest requires a real device/emulator and preconditions.
3. HostedContainerMinimalBaselineTest assumes com.test.minimal is installed by the baseline setup.
4. A skipped androidTest is not device proof.
5. Evidence file existence is not equal to full field correctness unless the expected fields are asserted or manually checked.
```

`HostedContainerMinimalBaselineTest` waits for evidence files:

```text
launch
activity-instrumentation
activity-context
provider-proxy
service-proxy
broadcast
```

## 5. Required runtime evidence fields

Ordinary hosted APK launch should produce or preserve:

```text
contextInjected=true
applicationInjected=<true|false>
packageName=<virtualPackageName>
loadedApkPatchedFields=<non-empty or justified>
loadedApkInstalledAliasCount>=2
loadedApkSkippedReason=<empty or explicit reason>
loadedApkSource=GUEST_SANDBOX
activityRecordPatchedFields=activityInfo,intent,packageInfo
activityRecordSkippedReason=<empty or explicit reason>
dataDir=<instance dataRoot>
```

If the launch uses fallback behavior, the fallback must be explicit:

```text
fallbackReason=<reason>
PARTIAL remains
```

## 6. Missing device evidence as of this refresh

Unless a later artifact is attached, the following remain missing:

```text
1. No current successful ADB/device capture for attempted endpoint 192.168.2.122:33811; recorded result remains ADB_CONNECT_FAILED_10061_DEVICE_OFFLINE_OR_PORT_CHANGED.
2. No accepted device proof yet for loadedApkSource=GUEST_SANDBOX.
3. No accepted device proof yet that activityRecordPatchedFields includes activityInfo,intent,packageInfo.
4. No accepted device proof yet for loadedApkInstalledAliasCount>=2 on a normal hosted launch.
5. No accepted device proof yet for provider query/insert/update/delete/call/openFile all through dispatcher evidence.
6. No accepted device proof yet for Java absolute path redirects.
7. No accepted device proof yet for native open/openat/stat/access redirect.
8. No QQ Reader compatibility evidence beyond diagnostic framing; interface20 still requires specific verdict.
```

## 7. QQ Reader diagnostics rules

A valid hosted QQ Reader diagnostics summary must include:

```text
mode=hosted-register-natives-only-diagnostics
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
nativeLoadVerdict=<PASS|FAIL|UNKNOWN>
registerNativesVerdict=<PASS|UNKNOWN>
findClassVerdict=<PASS|UNKNOWN>
selfKillVerdict=<NOT_OBSERVED|OBSERVED|UNKNOWN>
fatalVerdict=<NOT_OBSERVED|OBSERVED|UNKNOWN>
interface20Verdict=<specific verdict>
interface20VerdictReason=<reason>
```

Allowed wording:

```text
QQ Reader hosted diagnostics capture pipeline exists.
QQ Reader diagnostics produced <specific verdict> on <device/artifact>.
```

Forbidden wording without artifacts:

```text
QQ Reader works.
Protected apps are compatible.
RegisterNatives is fixed.
interface20 is solved.
Native shell problem is fixed.
```

## 8. PR-1 anti-false-DONE checklist

Before any PR claims a gate moved from PARTIAL to DONE, check:

```text
[ ] Gradle JVM/unit command and result recorded.
[ ] App assembleDebug command and result recorded if app/runtime changed.
[ ] If androidTest is claimed, device serial, command, result, skip count, and failure count recorded.
[ ] If HostedContainerMinimalBaselineTest is claimed, com.test.minimal preinstall is recorded and the test did not skip.
[ ] hosted-launch-evidence path is recorded.
[ ] logcat path is recorded.
[ ] exit-info path is recorded when relevant.
[ ] activityRecordPatchedFields includes activityInfo,intent,packageInfo.
[ ] loadedApkSource is GUEST_SANDBOX, not only EXISTING_PATCH.
[ ] loadedApkInstalledAliasCount is >=2.
[ ] contextInjected=true.
[ ] dataDir points under hosted instance dataRoot.
[ ] provider/service/broadcast evidence remains present.
[ ] storage evidence distinguishes Java Context storage, Java absolute paths, and native IO.
[ ] QQ Reader summary records protected default gates as false.
[ ] QQ Reader interface20Verdict is specific, not a generic crash/compatibility label.
[ ] Any failed/offline ADB attempt is documented as missing evidence, not success.
```

## 9. PR-2 legacy/comment cleanup rule

Legacy Stub clone APK / LoaderFactory / QQ Reader special-hook paths are not the v2 default container architecture. They may remain as legacy evidence collectors, manual diagnostics, or comparison paths, but PR-2 must not present them as advancing the seven kernel gates.

For comment-only cleanup:

```text
- seven gates remain PARTIAL;
- device evidence remains pending;
- QQ Reader/protected-app compatibility remains not complete;
- no new runtime capability is claimed.
```

Approve PR-2 only if changes are docs/comments only and do not change manifest semantics, Gradle config, hook defaults, or executable runtime logic.

Current owner scope note: [v2-pr2-legacy-freeze-comment-cleanup-2026-06-29.md](v2-pr2-legacy-freeze-comment-cleanup-2026-06-29.md) records that the present mixed working diff contains runtime changes that must be split or reclassified before PR-2 can be approved as clean comment-only work.

## 10. Owner current verdict

```text
GO: Continue implementing under v2 hosted user-space container direction.
NO-GO: Do not claim v2 container complete.
NO-GO: Do not claim QQ Reader/protected-app compatibility complete.
NO-GO: Do not add default LSPlant/Xposed/no-op/business wrappers as a substitute for kernel gates.
```

Next recommended work remains:

```text
1. Finish reference architecture mapping review.
2. Refresh stale comments and docs that contradict current provider/service implementation.
3. Run latest ordinary APK device evidence capture with current ADB endpoint.
4. Prove or refute loadedApkSource=GUEST_SANDBOX on device.
```
