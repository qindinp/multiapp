# v2 Reference Architecture Mapping

Date: 2026-06-29
Owner: hosted container runtime
Review model: Xiaomi-style role split + owner gate

## 0. Purpose

This document maps the MultiApp v2 hosted user-space container to public reference architectures such as VirtualApp, BlackBox, and DroidPlugin. It is a planning and evidence document only; runtime code remains in canonical Gradle modules.

The goal is to mature MultiApp from a hosted-container MVP into a maintainable App-level virtual installation container without overclaiming completion.

```text
Reference projects teach architecture patterns.
Commercial products define observable maturity expectations.
MultiApp completion still requires direct project evidence.
```

## 1. Role split and owner gate

PR-1 is executed with a Xiaomi-style涓撻」鍥㈤槦 split:

| Role | Responsibility | Output used here |
| --- | --- | --- |
| 鏋舵瀯璐熻矗浜?| Map VirtualApp / BlackBox / DroidPlugin concepts to MultiApp modules. | Target layers, mapping table, anti-patterns. |
| Android Runtime 璐熻矗浜?| Review ActivityThread, LoadedApk, Instrumentation, PMS/AMS, Provider, Storage state. | Current implementation labels and runtime caveats. |
| 娴嬭瘯璇佹嵁璐熻矗浜?| Define evidence gates, current test coverage, and missing device proof. | Verification commands, anti-false-DONE checklist. |
| 浜у搧/鍟嗕笟瀵规爣璐熻矗浜?| Convert commercial multi-app expectations into measurable product criteria. | Product maturity metrics and evidence fields. |
| Owner / 璐熻矗浜?| Integrate role findings, resolve conflicts, block overclaims. | Final doc content and approval gate. |

Owner rule:

```text
A role can propose DONE/PARTIAL labels, but only the owner can accept them into docs.
When evidence is missing, the owner must keep the status PARTIAL or NOT PROVEN.
```

## 2. Non-goals and clean-room boundary

This document does not authorize copying code from reference projects.

```text
Do not copy VirtualApp / BlackBox / DroidPlugin code without license and clean-room review.
Do not infer commercial products' private implementation details.
Do not use LSPlant/Xposed/no-op/business native stubs as default hosted runtime behavior.
Do not treat QQ Reader/protected-app behavior as the first maturity gate.
```

Allowed use of references:

```text
- architecture vocabulary
- module boundary comparison
- evidence gate design
- product capability benchmarking
- compatibility matrix shape
```

## 3. Target architecture layers

### 3.1 Reference / decision layer

Location:

```text
docs/container-runtime-refactor/
docs/multiapp-container-lsplant-roadmap.md
```

Responsibility:

```text
- preserve roadmap and evidence decisions
- prevent context loss across sessions
- define what can and cannot be claimed
```

### 3.2 Product / container entry layer

Location:

```text
app/src/main/java/com/multiapp/app/container/ContainerActivity.kt
app/src/main/java/com/multiapp/app/container/ProxyActivityBase.kt
app/src/main/java/com/multiapp/app/container/StubService.kt
app/src/main/java/com/multiapp/app/container/StubContentProvider.kt
app/src/main/AndroidManifest.xml
```

Responsibility:

```text
- own host-declared Android component slots
- launch hosted instances by instanceId
- write runtime evidence
- avoid virtualization-kernel logic creeping into UI/container entry
```

Reference analogue:

```text
VirtualApp / BlackBox / DroidPlugin stub component pool and host-visible entry points.
```

### 3.3 Virtual install / instance source-of-truth layer

Location:

```text
core/model/src/main/java/com/multiapp/core/model/installer/
core/model/src/main/java/com/multiapp/core/model/instance/
feature/launcher/src/main/java/com/multiapp/feature/launcher/LauncherViewModel.kt
```

Responsibility:

```text
- durable InstallRecord
- durable VirtualInstanceRecord
- origin APK artifact metadata
- instance dataRoot and virtual package identity
```

Reference analogue:

```text
VirtualApp/BlackBox virtual package DB and install records.
```

### 3.4 Runtime bootstrap layer

Location:

```text
core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProcessRuntime.kt
core/loader/src/main/java/com/multiapp/core/loader/RuntimeBootstrap*.kt
```

Responsibility:

```text
- load instance/install records
- resolve origin APK
- extract native libraries when possible
- create package snapshot
- create guest ClassLoader
- attach and start guest Application
- resolve launcher Activity
- record stage evidence
```

Reference analogue:

```text
VirtualApp / BlackBox process bootstrap and bindApplication preparation.
```

### 3.5 ActivityThread / LoadedApk sandbox layer

Location:

```text
core/loader/src/main/java/com/multiapp/core/loader/ActivityClientRecordBridge.kt
core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadCompat.kt
core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstaller.kt
core/loader/src/main/java/com/multiapp/core/loader/LoadedApkBridge.kt
core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextInjector.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
```

Responsibility:

```text
- substitute proxy Activity with guest Activity where supported
- inject guest context/application/classloader/resources
- patch ActivityThread launch records
- install or patch guest LoadedApk aliases
```

Current status:

```text
PARTIAL until device evidence proves loadedApkSource=GUEST_SANDBOX and required ActivityThread fields.
```

### 3.6 Virtual PMS layer

Location:

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageService.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageManagerWrapper.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageSnapshotFactory.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
```

Responsibility:

```text
- answer guest package/application/component queries from VirtualPackageSnapshot
- preserve virtual identity from guest Context and PackageManager paths
```

Current status:

```text
PARTIAL: context-local wrapper exists; global AppGlobals/IPackageManager path is not yet complete.
```

### 3.7 Virtual AMS / component dispatch layer

Location:

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualIntentResolver.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceRuntime.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualBroadcastManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualReceiverRuntime.kt
```

Responsibility:

```text
- map guest Activity/Service/Broadcast requests to host proxy slots
- preserve instance identity
- record dispatcher evidence
```

Current status:

```text
PARTIAL: local dispatchers and proxy slots exist; unified IActivityTaskManager/IActivityManager layer is not complete.
```

### 3.8 Provider dispatcher layer

Location:

```text
app/src/main/java/com/multiapp/app/container/StubContentProvider.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderDispatcher.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderRuntime.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualContentResolver.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderRoutingPlan.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderHookInstaller.kt
```

Responsibility:

```text
- rewrite guest provider authorities to host stub authority
- dispatch stub provider calls to guest provider runtime
- record method-level evidence
```

Current status:

```text
PARTIAL+: query/insert/update/delete/call paths exist; openFile/openAssetFile/full permission/cross-process evidence is still required.
```

### 3.9 Storage / data isolation layer

Location:

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextStorage.kt
core/loader/src/main/java/com/multiapp/core/loader/FileBackedSharedPreferences.kt
app/src/main/java/com/multiapp/app/container/ContainerRuntimePaths.kt
```

Responsibility:

```text
- route Context files/cache/db/shared-prefs/external dirs under instance dataRoot
- expose storage evidence
```

Current status:

```text
PARTIAL+: Java Context API coverage exists; Java absolute paths and native open/openat/stat/access are not device-proven.
```

### 3.10 Native diagnostics / protected profile layer

Location:

```text
core/hook/src/main/java/com/multiapp/core/hook/
core/loader/src/main/java/com/multiapp/core/loader/QqReaderProfile.kt
tools/qqreader-baseline/
```

Responsibility:

```text
- observe native load / JNI_OnLoad / FindClass / RegisterNatives
- keep QQ Reader/protected-app diagnostics profile-gated
- avoid default LSPlant/Xposed/no-op/business wrappers
```

Current status:

```text
PARTIAL: diagnostic framing exists; QQ Reader compatibility is not complete without specific device verdict.
```

## 4. Reference concept mapping

| Reference concept | VirtualApp / BlackBox / DroidPlugin analogue | MultiApp current files | Current status | Adopt | Do not adopt / caveat | Evidence gate |
| --- | --- | --- | --- | --- | --- | --- |
| Virtual package DB / install source of truth | VirtualApp package parser/DB; BlackBox install records | `core/model/.../installer/*`, `core/model/.../instance/*`, `feature/launcher/.../LauncherViewModel.kt` | PARTIAL+ | Make InstallRecord + VirtualInstanceRecord the durable source of truth. | Do not infer runtime completeness from model existence. | Instance create/launch/delete evidence; install record exists before launch. |
| Host container entry | BlackBox launch container; VA client bootstrap | `app/.../container/ContainerActivity.kt` | PARTIAL+ | Keep as fixed internal entry by `instanceId`. | Do not add QQ Reader hook logic here. | launch evidence with instanceId/stage/result. |
| Stub/proxy Activity pool | VA/DroidPlugin stub Activity pool | `ProxyActivityBase.kt`, `AndroidManifest.xml`, `ProxyActivityRegistry` | PARTIAL | Use manifest-declared host slots. | Do not regress to manual guest Activity `newInstance()+onCreate`. | guest Activity substitution and first-screen evidence. |
| Runtime bootstrap / bindApplication | VA/BlackBox process bootstrap | `HostedRuntimeBootstrap.kt`, `VirtualProcessRuntime.kt` | PARTIAL+ | Stage bootstrap, cache successful runtime per instance. | Do not keep expanding `LoaderFactory.kt` as kernel. | stage evidence; no duplicate Application creation. |
| ActivityThread launch record repair | ActivityThread/ClientTransaction repair | `ActivityClientRecordBridge.kt`, `VirtualInstrumentation.kt`, `HostedActivityContextInjector.kt` | PARTIAL | Patch before guest `onCreate`, record patched fields. | Not complete until pre-attach/device proof exists. | `activityRecordPatchedFields=activityInfo,intent,packageInfo`. |
| LoadedApk sandbox | VA/BlackBox LoadedApk/package-map aliasing | `ActivityThreadLoadedApkInstaller.kt`, `LoadedApkBridge.kt`, `ActivityThreadCompat.kt` | PARTIAL | Prefer guest sandbox via framework-created LoadedApk + alias maps. | Avoid treating host LoadedApk patch as complete sandbox. | `loadedApkSource=GUEST_SANDBOX`, alias count >= 2. |
| Virtual PMS | `VPackageManagerService` | `VirtualPackageService.kt`, `VirtualPackageManagerWrapper.kt`, `VirtualPackageSnapshotFactory.kt` | PARTIAL | Use snapshot-backed service for guest package queries. | Context wrapper alone is not global PMS. | AppGlobals/IPackageManager/ApplicationPackageManager evidence. |
| Virtual AMS / Activity dispatcher | `VActivityManagerService` / DroidPlugin AMS hook | `VirtualActivityManager.kt`, `VirtualInstrumentation.kt`, `VirtualIntentResolver.kt` | PARTIAL | Resolve guest intents to proxy slots and record dispatch evidence. | Not full AMS until IActivityTaskManager/IActivityManager, task/back-stack/result are covered. | Activity chain, newIntent, result, back-stack evidence. |
| Virtual Service dispatcher | VA/BlackBox service proxy | `VirtualServiceManager.kt`, `VirtualServiceRuntime.kt`, `StubService.kt`, `VirtualContextWrapper.kt` | PARTIAL | Route guest service starts/stops to host stub and runtime. | Foreground/bind/permission/ANR semantics still need coverage. | service-proxy evidence per instance. |
| Virtual Broadcast/Receiver dispatcher | VA receiver runtime | `VirtualBroadcastManager.kt`, `VirtualReceiverRuntime.kt`, `VirtualDynamicReceiverRegistry.kt` | PARTIAL | Support static/dynamic receiver dispatch where implemented. | Ordered/sticky/cross-process behavior not proven. | broadcast evidence and no silent fallback. |
| Provider proxy / authority rewrite | VA/DroidPlugin provider proxy | `StubContentProvider.kt`, `VirtualProviderDispatcher.kt`, `VirtualContentResolver.kt` | PARTIAL+ | Use host stub authority and guest provider runtime. | Provider hook stays profile-controlled; not ordinary default. | query/insert/update/delete/call/openFile evidence. |
| Java storage isolation | VA app data redirection | `VirtualContextWrapper.kt`, `VirtualContextStorage.kt`, `FileBackedSharedPreferences.kt` | PARTIAL+ | Route Context storage under instance dataRoot. | Java Context storage success does not prove native IO. | per-instance files/prefs/db/external evidence. |
| Native library path / namespace diagnostics | Native library namespace compatibility | `HostedRuntimeBootstrap.kt`, `core/hook/*`, QQ Reader diagnostics tools | PARTIAL | Extract native libs, record nativeLibraryDir, diagnose namespace/RegisterNatives. | Do not patch shell/business native by default. | nativeLoad/registerNatives/interface20 verdicts. |
| Protected-app diagnostics profile | optional diagnostics layer | `QqReaderProfile.kt`, `core/hook/*`, `tools/qqreader-baseline/*` | PARTIAL | observe-only, profile-gated diagnostics. | Do not call protected app compatibility complete. | all protected default gates false + specific verdict. |
| Legacy stub clone route | transitional container/evidence collector | `core/stub`, `core/loader/LoaderFactory.kt`, legacy QQ Reader docs | TRANSITIONAL | Keep as comparison and legacy evidence path. | Do not add new v2 kernel features here. | v2 new instances do not generate stub APK. |

## 5. Architecture invariants

```text
1. core/model owns durable install/package/instance truth.
2. app/container owns Android-declared host slots and evidence paths.
3. core/loader owns hosted runtime preparation, framework repair, dispatchers, and evidence.
4. VirtualProcessRuntime.global.bindApplication(instanceId) is the process-local runtime reuse boundary.
5. Host LoadedApk patching is fallback only; mature evidence prefers guest sandbox/alias proof.
6. Provider hook is diagnostic/profile-controlled and disabled by default.
7. Protected diagnostics are observe-only unless a user explicitly enables a diagnostic profile.
8. Every fallback path must write evidence or an explicit unsupported reason.
```

## 6. Anti-patterns and risks

| Anti-pattern | Why it is risky | Owner decision |
| --- | --- | --- |
| Continue expanding `LoaderFactory.kt` | Recreates the giant transitional kernel and hides evidence boundaries. | New v2 work must land in stage/profile/virtual-service modules. |
| Class existence = complete | Hides missing device/framework proof. | Status remains PARTIAL until evidence gate passes. |
| Manual `Activity.newInstance()+onCreate()` | Bypasses ActivityThread attach/token/window/resources state. | Not a production route. Keep only as diagnostic/negative evidence if needed. |
| Context-wrapper PMS = global PMS | Guest code can reach AppGlobals/ApplicationPackageManager paths. | PMS remains PARTIAL until global proxy evidence exists. |
| Java storage redirect = native storage redirect | Native code bypasses Java Context APIs. | Separate Java Context, Java absolute path, and native IO verdicts. |
| LSPlant/Xposed as default kernel | Masks missing PMS/AMS/Provider/Storage implementation. | Optional extension only after baseline evidence. |
| QQ Reader overfitting | One protected app can distort ordinary App container maturity. | Ordinary App matrix first; QQ Reader diagnostics later. |
| Commercial product equivalence claims | Commercial internals are unknown. | Benchmark only observable product outcomes. |
| Stale comments as truth | Manifest comments may lag implementation. | Use current code + evidence; update stale comments in a separate cleanup. |
| Silent fallback | Makes false PASS likely. | Fallback must emit reason and keep gate PARTIAL. |

## 7. Evidence gate cross-reference

The seven current kernel gates are defined in the pre-existing source document [v2-seven-kernel-gap-execution-2026-06-29.md](v2-seven-kernel-gap-execution-2026-06-29.md). This mapping document does not change their status and does not require that source document to be modified in PR-1.

```text
Current owner status: all seven kernel gates remain PARTIAL unless a later PR attaches direct evidence.
```

Minimum ordinary-APK runtime proof remains:

```text
activityRecordPatchedFields=activityInfo,intent,packageInfo
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount>=2
contextInjected=true
packageName=<virtualPackageName>
dataDir=<instance dataRoot>
provider/service/broadcast evidence remains non-regressed
```

Minimum protected-app proof remains:

```text
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
nativeLoadVerdict=<PASS|FAIL|UNKNOWN>
interface20Verdict=<specific verdict, not a generic crash label>
```

## 8. Source index for future agents

Read these before changing runtime behavior:

```text
docs/container-runtime-refactor/v2-container-maturity-execution-blueprint-2026-06-29.md
docs/container-runtime-refactor/v2-seven-kernel-gap-execution-2026-06-29.md
docs/multiapp-container-lsplant-roadmap.md
docs/container-runtime-refactor/v2-hosted-container-audit-remediation-2026-06-27.md
app/src/main/AndroidManifest.xml
app/src/main/java/com/multiapp/app/container/ContainerActivity.kt
app/src/main/java/com/multiapp/app/container/ProxyActivityBase.kt
app/src/main/java/com/multiapp/app/container/StubContentProvider.kt
core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderDispatcher.kt
core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstaller.kt
core/loader/src/main/java/com/multiapp/core/loader/LoadedApkBridge.kt
core/loader/src/main/java/com/multiapp/core/loader/QqReaderProfile.kt
app/src/androidTest/java/com/multiapp/app/HostedContainerMinimalBaselineTest.kt
tools/qqreader-baseline/README.md
```

## 9. Owner sign-off rule for later PRs

A later PR can change a status from PARTIAL to DONE only if it includes:

```text
1. Code path description.
2. Unit/build verification command and result.
3. Device/runtime evidence path.
4. Evidence fields matching the gate.
5. Failure/fallback behavior.
6. Protected-app default gates if protected diagnostics are involved.
7. Owner review explicitly accepting the status change.
```

Without all required items, use:

```text
implemented MVP / evidence path added / PARTIAL remains
```
