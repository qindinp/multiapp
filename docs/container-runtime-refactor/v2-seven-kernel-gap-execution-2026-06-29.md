# v2 Seven-Kernel-Gap Execution

Date: 2026-06-29
Owner: hosted container runtime

## Decision

The seven items below are not optional follow-up work. They are the core of the
v2 hosted user-space container. The project must not claim that the container is
complete, or that QQ Reader/protected apps are compatible, until these gates have
direct evidence.

```text
1. ActivityThread launch record level ActivityInfo / theme / applicationInfo restoration
2. Complete LoadedApk sandbox
3. Virtual PMS proxy
4. Virtual AMS component dispatcher
5. Provider dispatcher
6. Java + native storage redirect
7. protected-app diagnostics as profile only, not default runtime behavior
```

## Current Audit

| Gate | Current State | Verdict |
| --- | --- | --- |
| ActivityThread launch record | Previous code only injected Activity Context after guest Activity creation. This round adds `ActivityClientRecordBridge`, which patches `ActivityThread.mActivities` record fields by Activity token before `onCreate`. | PARTIAL |
| LoadedApk sandbox | `LoadedApkBridge` and `ActivityThreadLoadedApkInstaller` exist. This round adds a preferred guest sandbox path through `ActivityThread.getPackageInfoNoCheck(ApplicationInfo, CompatibilityInfo)`, then patches and aliases the returned LoadedApk. Existing LoadedApk patch remains only as fallback. Device evidence is still required. | PARTIAL |
| Virtual PMS proxy | `VirtualPackageService` and `VirtualPackageManagerWrapper` exist and are exposed through `VirtualContextWrapper.getPackageManager()`. Global `AppGlobals/IPackageManager` queries are not hooked yet. | PARTIAL |
| Virtual AMS dispatcher | `VirtualInstrumentation`, `VirtualActivityManager`, `VirtualServiceManager`, and broadcast handling exist as local dispatchers. A unified `IActivityTaskManager/IActivityManager` layer is not complete. | PARTIAL |
| Provider dispatcher | `StubContentProvider`, `VirtualProviderDispatcher`, and provider runtime exist. Full authority rewrite and provider method coverage still need evidence. | PARTIAL |
| Java + native storage redirect | Java Context API storage isolation has manual dual-instance evidence. Native `open/openat/stat/access` and Java absolute path redirects are not complete. | PARTIAL |
| Protected diagnostics profile | Default runtime does not enable LSPlant/Xposed/no-op/business wrappers. QQ Reader evidence currently shows `libjiagu` native load PASS but `interface20` unregistered. | PARTIAL |

## Code Landed In This Round

```text
core/loader/src/main/java/com/multiapp/core/loader/ActivityClientRecordBridge.kt
core/loader/src/test/java/com/multiapp/core/loader/ActivityClientRecordBridgeTest.kt
core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadCompat.kt
core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstaller.kt
core/loader/src/test/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstallerTest.kt
core/loader/src/main/java/com/multiapp/core/loader/HostedActivityContextInjector.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
```

New Activity context evidence fields:

```text
activityRecordPatchedFields=<activityInfo,intent,packageInfo>
activityRecordSkippedReason=<reason>
loadedApkSource=<GUEST_SANDBOX|EXISTING_PATCH>
```

LoadedApk sandbox behavior:

```text
preferred: ActivityThread.getPackageInfoNoCheck(guest ApplicationInfo, DEFAULT_COMPATIBILITY_INFO)
then:      LoadedApkBridge.patch(mApplicationInfo/mResources/mClassLoader/mPackageName/mAppDir/mResDir)
then:      ActivityThread.mPackages/mResourcePackages aliases for originPackageName and virtualPackageName
fallback:  patch existing Activity.mLoadedApk/mPackageInfo only when guest sandbox creation fails
```

## Verification

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

```text
result=BUILD SUCCESSFUL
```

## Execution Order

1. Finish `ActivityClientRecord` evidence on device.
2. Replace the current LoadedApk patch-only path with a guest LoadedApk sandbox path.
3. Add AppGlobals/IPackageManager proxy backed by `VirtualPackageService`.
4. Consolidate Activity/Service/Broadcast dispatch under a single AMS dispatcher contract.
5. Extend provider dispatcher evidence for query/insert/update/delete/call/openFile.
6. Add storage redirect evidence for Java absolute paths and native IO.
7. Keep protected-app diagnostics profile-gated and separate from ordinary runtime.

## Parallel Workstreams

The seven gates can move in parallel, but completion is judged only by evidence.

| Workstream | Owner Role | Write Scope | Evidence Gate |
| --- | --- | --- | --- |
| Runtime | ActivityThread / LoadedApk | `ActivityThreadCompat`, `ActivityThreadLoadedApkInstaller`, `LoadedApkBridge`, `HostedActivityContextInjector` | `loadedApkSource=GUEST_SANDBOX`, `activityRecordPatchedFields` includes `activityInfo,intent,packageInfo`, no host LoadedApk guard on normal guest launch |
| PMS / AMS | Virtual services and component dispatch | `VirtualPackageService`, `VirtualPackageManagerWrapper`, `VirtualInstrumentation`, `VirtualActivityManager`, `VirtualIntentResolver`, `VirtualContextWrapper` dispatch areas | guest self package queries return snapshot data; guest startActivity/startService/sendBroadcast map to proxy/dispatcher with evidence |
| Provider / Storage / Diagnostics | Provider, file roots, protected profile | `VirtualProvider*`, `VirtualContentResolver`, `VirtualContextStorage`, storage/provider areas in `VirtualContextWrapper`, diagnostics profile files | provider query/insert/update/delete/call/openFile have dispatcher evidence; Java absolute paths and native IO gaps are explicitly tested or reported; protected diagnostics stays profile-gated |

## Evidence Standard

A gate is not complete because a class exists or a JVM unit test passes. A gate is complete only when all applicable evidence exists:

```text
unit tests: deterministic coverage for resolver/dispatcher/bridge behavior
debug APK: :app:assembleDebug succeeds
manual device run: user launches hosted instance from MultiApp
logcat window: no host crash for the tested ordinary APK path
run-as evidence: hosted_launch_evidence contains the expected fields
storage evidence: per-instance files/prefs/db/provider state are under instance dataRoot
protected evidence: QQ Reader diagnostics state PASS/FAIL/UNKNOWN without default LSPlant/Xposed/no-op/business wrappers
```

Minimum ordinary-APK device evidence for the next checkpoint:

```text
activityRecordPatchedFields=activityInfo,intent,packageInfo
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount>=2
contextInjected=true
packageName=<virtualPackageName>
dataDir=<instance dataRoot>
provider/service/broadcast evidence remains non-regressed
```

Minimum protected-app evidence for the next checkpoint:

```text
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
nativeLoadVerdict=<PASS|FAIL|UNKNOWN>
interface20Verdict=<specific verdict, not a generic crash label>
```

## 2026-06-29 Parallel Agent Integration Checkpoint

Parallel work was split into three roles. Final integration remains owner-gated.

```text
Runtime group:
  landed LoadedApk patch coverage for mBaseClassLoader, mLibDir, mDataDir,
  mDataDirFile, mCredentialProtectedDataDirFile, mDeviceProtectedDataDirFile,
  plus skipped field reasons and package map alias skipped reasons.

PMS/AMS group:
  reviewed current implementation and ran :core:loader:testDebugUnitTest.
  no file changes.

Provider/Storage/Diagnostics group:
  landed method-level provider evidence, storage redirect evidence,
  lastStorageEvidence(), and explicit QQ Reader diagnostics gate.
```

Owner review result:

```text
Diagnostics hooks are still blocked by default.
QQ Reader diagnostics require QQReader profile + LSPlant ready + debug.multiapp.qqreader.diagnostics=1/true.
LoadedApk sandbox remains PARTIAL until device evidence shows loadedApkSource=GUEST_SANDBOX.
Provider/storage evidence remains PARTIAL until device evidence confirms method and path coverage.
```

Verification:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualProviderDispatcherTest" --tests "com.multiapp.core.loader.VirtualContextStorageTest" --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --tests "com.multiapp.core.loader.QqReaderProfileTest" "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon

.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:compileDebugKotlin "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

```text
result=BUILD SUCCESSFUL
```

## 2026-06-29 Owner Remediation Checkpoint

Three role reviews were restarted against the seven gates. The shared finding is
that the direction matches the VirtualApp/BlackBox family of designs, but the
current implementation is still a hosted-container MVP, not a complete
PMS/AMS/ActivityThread/LoadedApk virtualization layer.

Fixed in this checkpoint:

```text
1. VirtualPackageSnapshotFactory now preserves application-level processName,
   taskAffinity, themeId, and metaData from ResolvedPackage.
2. VirtualPackageInfoFactory.activityInfo() now falls back from activity theme
   to application theme when the activity does not declare its own theme.
3. VirtualInstrumentation.createHostedRuntime() now reuses
   VirtualProcessRuntime.global.bindApplication(instanceId), matching
   ContainerActivity and reducing duplicate guest Application creation risk.
```

Files changed for this checkpoint:

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageSnapshotFactory.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualPackageSnapshotFactoryTest.kt
```

Verification:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualPackageSnapshotFactoryTest" "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon

.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

```text
result=BUILD SUCCESSFUL
```

Still not complete:

```text
1. ActivityClientRecord restoration still happens around Instrumentation
   newActivity/callActivityOnCreate, not at ClientTransaction/LaunchActivityItem
   pre-attach level.
2. LoadedApk sandbox must be proven on device with loadedApkSource=GUEST_SANDBOX
   and non-empty ActivityThread package/resource alias evidence.
3. Virtual PMS is still primarily Context wrapper backed. AppGlobals,
   ActivityThread.sPackageManager, and ApplicationPackageManager.mPM are not
   yet unified under the snapshot-backed proxy.
4. Virtual AMS is still local dispatcher + proxy slots. IActivityTaskManager /
   IActivityManager interception, result delivery, task/back-stack, and modern
   startActivity overload coverage remain open.
5. Provider hook remains profile-controlled and process-wide when enabled. It
   must not be enabled for ordinary baseline until instance-scoped routing
   evidence exists.
6. Java Context storage redirect is present, but native open/openat/stat/access
   redirect is not proven by device evidence.
7. Protected-app diagnostics remain profile-only. QQ Reader compatibility is
   not complete until device evidence gives a specific native/RegisterNatives
   verdict without default LSPlant/Xposed/no-op/business wrappers.
```

Device verification status:

```text
attemptedDevice=192.168.2.122:33811
result=ADB_CONNECT_FAILED_10061_DEVICE_OFFLINE_OR_PORT_CHANGED
nextAction=request current wireless ADB endpoint, install latest app-debug.apk,
then user manually launches AstroBox and QQ Reader while owner captures a 15s
logcat/evidence window.
```

## Non-Goals

```text
No LSPlant/Xposed in default hosted runtime.
No business native wrappers or no-op patches in default hosted runtime.
No shell breaking or shell bypassing.
No claim that QQ Reader compatibility is complete without device evidence.
```
