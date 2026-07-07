# Container Runtime Bug Audit — General App Container Review

**Date:** 2026-07-07
**Branch:** `container-runtime-refactor`
**Mode:** read-only multi-agent review
**Decision:** **BLOCK**
**Scope:** Android general app container runtime, not only QQ / QQ Reader compatibility.

## Executive Summary

This review treats MultiApp as a **general Android app container**. The current branch should not be merged as-is. The implementation has made progress on hosted Activity, Provider, Service, PackageManager, native path redirection, and evidence capture, but several fundamentals required by a VirtualApp / DroidPlugin / BlackBox-style runtime are still incomplete.

The most important issues are not isolated QQ bugs. They are container architecture defects:

1. Cross-instance Provider routing can be forged by same-process guest code.
2. Native private path redirection is global per package name and can overwrite routes for another instance.
3. Native path rewriting is vulnerable to `..` / symlink escape because it concatenates strings without canonical containment checks.
4. Split APK / dynamic feature / multidex support is incomplete.
5. Guest `Application` is not created through a full `LoadedApk` / `ActivityThread` equivalent path.
6. Provider, Service, and Broadcast lifecycle semantics diverge from Android framework behavior.
7. Tests still verify evidence strings more often than actual guest behavior.

These defects explain the observed device failures:

- QQ Reader clone resolves `com.qq.reader.activity.launch.DefaultAliasSplashActivity` but the runtime `PathClassLoader` only sees the base/origin APK, causing `ClassNotFoundException`.
- QQ clone spends time in `DexFile.openDexFileNative` and Tencent `RFix/QFix` `attachBaseContext`, because heavy and framework-sensitive guest bootstrap still does not have a full virtual Android runtime context.

## Review Inputs

### Local evidence

- Full logcat: [.tmp/manual-clone-crash-logcat-20260706-170416.txt](../../.tmp/manual-clone-crash-logcat-20260706-170416.txt)
- Focused QQ Reader log: [.tmp/manual-clone-qqreader-crash-20260706-170416.txt](../../.tmp/manual-clone-qqreader-crash-20260706-170416.txt)
- Focused QQ log: [.tmp/manual-clone-qq-crash-20260706-170416.txt](../../.tmp/manual-clone-qq-crash-20260706-170416.txt)

### Open-source references searched

The review used GitHub/code search against known Android container/plugin/runtime projects and implementation notes:

- DroidPlugin `IActivityManagerHook`: `DroidPluginTeam/DroidPlugin:project/Libraries/DroidPlugin/src/main/java/com/morgoo/droidplugin/hook/proxy/IActivityManagerHook.java`
- VirtualApp/OpenVirtualApp provider lifecycle: `VClient.installContentProviders(...)`
- BlackBox/BlackDex `BActivityThread.bindApplication` and `HCallback` launch record patching
- LoadedApk plugin frameworks using `ActivityThread.mPackages` / `getPackageInfoNoCheck`
- RePlugin / Small-style `splitSourceDirs` and `ApplicationInfo` handling references

## Severity Summary

| Severity | Count | Merge impact |
|---|---:|---|
| CRITICAL | 3 confirmed | Block |
| HIGH | 20+ confirmed / plausible | Block |
| MEDIUM | Several | Track after P0/P1 |
| LOW | Not prioritized | Track later |

## P0 / CRITICAL Findings

### 1. Cross-instance Provider access can be forged by same-process guest code

**Severity:** CRITICAL
**Status:** CONFIRMED by security-reviewer
**Primary files:**

- [StubContentProvider.kt:251-259](../../app/src/main/java/com/multiapp/app/container/StubContentProvider.kt#L251-L259)
- [VirtualProviderDispatcher.kt:16-21](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderDispatcher.kt#L16-L21)
- [HostedProviderRuntimeBinder.kt:32-60](../../app/src/main/java/com/multiapp/app/container/HostedProviderRuntimeBinder.kt#L32-L60)
- [VirtualProviderManager.kt:54-60](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderManager.kt#L54-L60)
- [ContentProviderHook.kt:836-847](../../core/identity/src/main/java/com/multiapp/core/identity/ContentProviderHook.kt#L836-L847)

#### Evidence

`StubContentProvider` trusts route data supplied in URI query parameters or `call()` extras:

- `multiapp_instanceId`
- `multiapp_guestAuthority`

`VirtualProviderDispatcher.dispatch(uri)` reads those values directly and routes to that instance/provider. `HostedProviderRuntimeBinder.ensureBound()` can even bootstrap the target instance using the forged `instanceId` from the URI.

`StubContentProvider` is `exported=false`, which reduces external app attack surface, but does not protect against code already running inside the host UID/process.

#### Failure / attack scenario

Guest instance A constructs a direct stub URI:

```text
content://<host>.multiapp.provider.stub/path?multiapp_instanceId=<instanceB>&multiapp_guestAuthority=<providerB>
```

Then calls `query`, `insert`, `update`, `delete`, `openFile`, or `call`. Because the URI already uses the stub authority, `ContentProviderHook` does not rewrite or sanitize it. The dispatcher routes to instance B.

This violates the core isolation guarantee of a multi-instance app container.

#### Required fix

Do not treat `instanceId` or `guestAuthority` as authorization data.

Recommended approach:

1. Generate an unguessable route token when the host-side hook rewrites a legitimate guest URI.
2. Store token metadata in a process-local registry:
   - caller instance
   - target instance
   - guest authority
   - allowed operation
   - expiry / one-shot policy
3. Stub provider accepts only valid tokens.
4. Direct stub URI without a valid token must fail.
5. `exported=false`, provider permissions, and URI grants must be enforced in virtual policy.

### 2. Native private path redirects are global per package and overwrite another instance

**Severity:** CRITICAL
**Primary files:**

- [NativeHookBridge.kt:868-879](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt#L868-L879)
- [native-hook.cpp:90-91](../../core/hook/src/main/cpp/native-hook.cpp#L90-L91)

#### Evidence

`setupGuestPrivatePathRedirections()` accepts `instanceId`, but installs rules only by `guestPackageName`:

```text
/data/data/<guestPackage>/ -> <dataRoot>/
/data/user/0/<guestPackage>/ -> <dataRoot>/
```

The native layer stores these in a process-global `g_path_redirects` map.

#### Failure scenario

Two instances of the same original package run in the same host process:

```text
Instance A: /data/data/com.foo/ -> A/dataRoot
Instance B: /data/data/com.foo/ -> B/dataRoot
```

After B installs its rules, A's native file access to `/data/data/com.foo/...` resolves into B's sandbox. This causes cross-instance data leakage and corruption.

#### Required fix

One of these must be implemented before treating this as a general container:

- isolate same-origin instances into separate processes; or
- make native redirection scoped by current virtual instance; or
- enforce a strict single-active-instance model per host process with safe rule switching and no concurrent guest execution.

Package-name-only native redirect is not compatible with multi-instance isolation.

### 3. Native path rewrite can escape sandbox via `..` or symlink

**Severity:** CRITICAL
**Primary file:** [native-hook.cpp:197-231](../../core/hook/src/main/cpp/native-hook.cpp#L197-L231)

#### Evidence

`redirect_path()` performs a prefix match and then string concatenation:

```cpp
return best_to + path_str.substr(best_from.length());
```

It does not reject `..`, does not canonicalize the destination, and does not verify the final target remains under `dataRoot`.

#### Failure scenario

A guest/native library can request:

```text
/data/data/<pkg>/files/../../../../...
```

or use symlinks under the sandbox. The Java layer has scoped canonical checks, but native file APIs bypass those Java checks.

#### Required fix

Native path resolution must enforce containment:

- reject unsafe segments such as `..`
- canonicalize existing paths and verify they are still under the target data root
- for `O_CREAT`, canonicalize and validate the parent directory
- handle symlinks using no-follow/openat-style constraints where possible

## P1 / HIGH Runtime and Loading Findings

### 4. Split APK / dynamic feature / multidex paths are missing

**Severity:** HIGH
**Primary files:**

- [InstallRecord.kt:35](../../core/model/src/main/java/com/multiapp/core/model/installer/InstallRecord.kt#L35)
- [VirtualPackageSnapshot.kt:19-20](../../core/model/src/main/java/com/multiapp/core/model/virtual/VirtualPackageSnapshot.kt#L19-L20)
- [HostedRuntimeBootstrap.kt:550-559](../../core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt#L550-L559)
- [VirtualPackageInfoFactory.kt:17-33](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt#L17-L33)

#### Evidence

The runtime records and loads a single APK path. `ApplicationInfo` does not carry `splitSourceDirs`, and `PathClassLoader` receives only one path.

#### Failure scenario

Apps installed through Play often have split APKs for ABI, language, density, or dynamic features. If a launcher Activity, resource, class, or native library lives outside the base APK, the clone fails with:

- `ClassNotFoundException`
- `Resources.NotFoundException`
- missing native library

This matches the QQ Reader clone failure where a manifest-resolved launcher class was not visible to the current `PathClassLoader`.

#### Fix direction

Add split support end-to-end:

- install record stores base + split APK paths
- snapshot stores `splitSourceDirs` / `splitPublicSourceDirs`
- classloader dex path includes base + splits
- resources add all asset paths
- native library extraction scans base + splits

### 5. Guest Application is not created through a complete LoadedApk / ActivityThread path

**Severity:** HIGH
**Primary files:**

- [ApplicationStage.kt:37-47](../../core/loader/src/main/java/com/multiapp/core/loader/ApplicationStage.kt#L37-L47)
- [ApplicationStage.kt:54-82](../../core/loader/src/main/java/com/multiapp/core/loader/ApplicationStage.kt#L54-L82)
- [ApplicationStage.kt:177-207](../../core/loader/src/main/java/com/multiapp/core/loader/ApplicationStage.kt#L177-L207)

#### Evidence

If there is no custom `Application`, the stage skips Application creation entirely. If there is a custom class, the code reflectively constructs it and invokes `attachBaseContext()` / `onCreate()` manually. This is not equivalent to Android's `LoadedApk.makeApplication()` / `ActivityThread` path.

#### Failure scenario

Shells, hot-fix frameworks, and SDKs may call:

- `ActivityThread.currentApplication()`
- `LoadedApk.getApplication()`
- `Activity.getApplication()`
- current process/package APIs

They may see the host app, null, or an incomplete guest runtime.

#### Fix direction

- Always create a guest Application, even default `android.app.Application`.
- Bind Application to guest LoadedApk/ContextImpl equivalent.
- Populate `LoadedApk.mApplication`, Activity `mApplication`, and related context fields consistently.
- Define which ActivityThread globals are guest-facing and which must remain host-facing.

### 6. Guest Application `attachBaseContext` / `onCreate` runs on a custom background Looper

**Severity:** HIGH
**Primary files:**

- [ContainerActivity.kt:276-294](../../app/src/main/java/com/multiapp/app/container/ContainerActivity.kt#L276-L294)
- [ContainerActivity.kt:345-349](../../app/src/main/java/com/multiapp/app/container/ContainerActivity.kt#L345-L349)
- [ApplicationStage.kt:76-82](../../core/loader/src/main/java/com/multiapp/core/loader/ApplicationStage.kt#L76-L82)

#### Evidence

`ContainerActivity` creates `multiapp-prewarm-*`, prepares a Looper, runs bootstrap, and may call `Looper.loop()` permanently after guest Application creation.

#### Failure scenario

Some Apps assume Application startup happens on the process main thread. QQ/RFix/QFix-style frameworks may perform dex/native setup, Handler creation, or main-thread assertions inside `attachBaseContext()`. Running that on a custom Looper can create deadlocks, wrong Handler affinity, or leaked threads.

#### Fix direction

- Background work should stop at parsing, native extraction, and classloader preparation.
- Guest Application lifecycle should run on a controlled virtual main thread.
- The Looper must have lifecycle management, timeout, and quit behavior.

### 7. Activity substitution failure is logged but falls back to proxy Activity

**Severity:** HIGH
**Primary files:**

- [VirtualInstrumentation.kt:80-87](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt#L80-L87)
- [VirtualInstrumentation.kt:812-872](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt#L812-L872)

#### Evidence

If `createHostedGuestActivity()` fails, it logs and returns null. `newActivity()` then calls `base.newActivity()` with the proxy class.

#### Failure scenario

The UI can show/resume the proxy shell even though the guest Activity failed to instantiate. In the QQ Reader log, the real failure was `ClassNotFoundException`, but the proxy Activity still resumed afterward.

#### Fix direction

- Treat guest substitution failure as a launch failure, not a silent proxy fallback.
- Finish or show a diagnostic failure surface.
- Tie substitution failures to launch evidence and user-visible result.

### 8. Launch record patching can occur before runtime is ready

**Severity:** HIGH
**Primary file:** [ActivityThreadLaunchRecordPatcher.kt](../../core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadLaunchRecordPatcher.kt)

#### Evidence

The runtime agent found that launch records may be patched to guest based on proxy extras even when guest classloader / LoadedApk are unavailable.

#### Failure scenario

After process death, restoring a proxy Activity from recents can patch the record to guest too early. ActivityThread may then create the guest Activity with host LoadedApk / host ClassLoader, causing direct `ClassNotFoundException` before ProxyActivity recovery can run.

#### Fix direction

Only patch to guest when guest ClassLoader and guest LoadedApk are both available. Otherwise leave the proxy record intact and let ProxyActivityBase perform recovery/prewarm.

## P1 / HIGH Package and Manifest Findings

### 9. Virtual PackageInfo lacks signatures / SigningInfo

**Severity:** HIGH
**Primary file:** [VirtualPackageInfoFactory.kt:35-45](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt#L35-L45)

#### Failure scenario

Apps and SDKs calling `GET_SIGNATURES` or `GET_SIGNING_CERTIFICATES` get empty signature data. Shells, payment SDKs, maps, ads, Firebase, or self-checks may reject the runtime.

#### Fix direction

Store original signing certificate chain at import time and fill `signatures` / `signingInfo` based on request flags.

### 10. Manifest meta-data loses type information and component meta-data

**Severity:** HIGH
**Primary files:**

- [ManifestVirtualPackageResolver.kt:141-147](../../core/loader/src/main/java/com/multiapp/core/loader/ManifestVirtualPackageResolver.kt#L141-L147)
- [VirtualPackageInfoFactory.kt:191-194](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt#L191-L194)
- [VirtualPackageInfoFactory.kt:47-78](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt#L47-L78)

#### Evidence

Meta-data is converted to `Map<String, String>` and later written with only `putString()`.

#### Failure scenario

SDK calls like `applicationInfo.metaData.getInt(...)` or `getBoolean(...)` return wrong defaults. Google Play services, Firebase, maps, push, Unity/game configs can fail.

#### Fix direction

Represent metadata as typed values and resource references. Parse and preserve Activity, Service, Receiver, and Provider meta-data separately.

### 11. Provider read/write permissions are not modeled separately

**Severity:** HIGH
**Primary file:** [VirtualPackageInfoFactory.kt:80-95](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt#L80-L95)

#### Failure scenario

A provider with different read/write permissions can be represented as an invalid combined string or applied to both fields. Virtual permission policy may reject valid reads or allow invalid writes.

#### Fix direction

Add separate fields for:

- `permission`
- `readPermission`
- `writePermission`

Then fill `ProviderInfo` accurately.

### 12. IntentFilter matching is simplified too far

**Severity:** HIGH
**Primary file:** [ManifestVirtualPackageResolver.kt:56-62](../../core/loader/src/main/java/com/multiapp/core/loader/ManifestVirtualPackageResolver.kt#L56-L62)

#### Evidence

Resolved filters only preserve action, category, and scheme. Host/path/port/MIME/priority are not modeled.

#### Failure scenario

Deep links and OAuth/payment/file-sharing callbacks may route to the wrong component when multiple components share `VIEW https` or similar broad filters.

#### Fix direction

Model full Android `IntentFilter` data and prefer Android's `IntentFilter.match()` semantics where possible.

### 13. Global virtual PMS aliases can still mix instances with the same origin package

**Severity:** HIGH
**Primary files:**

- [VirtualPackageRegistry.kt](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageRegistry.kt)
- [VirtualPackageService.kt](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageService.kt)

#### Failure scenario

Two clones of the same origin package register package aliases globally. A self-query by origin package may resolve to the wrong instance snapshot, exposing the wrong dataDir, components, providers, or metadata.

#### Fix direction

Origin package queries must be bound to the current virtual context/instance. Global alias registration should not overwrite by origin package unless that origin is unique.

## P1 / HIGH Component Runtime Findings

### 14. `installContentProviders` order is not simulated

**Severity:** HIGH
**Primary files:**

- [ApplicationStage.kt:76-82](../../core/loader/src/main/java/com/multiapp/core/loader/ApplicationStage.kt#L76-L82)
- [VirtualProviderRuntime.kt:22-57](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderRuntime.kt#L22-L57)

#### Evidence

Guest Provider instances are created lazily on first access. Android normally installs same-process providers before `Application.onCreate()`.

#### Failure scenario

Framework initializers, databases, or SDKs that depend on provider side effects before Application startup behave differently from a real install.

#### Fix direction

During bootstrap, create and attach same-process guest providers before guest Application `onCreate()`.

### 15. Foreground service and sticky service semantics are degraded

**Severity:** HIGH
**Primary files:**

- [StubService.kt:63](../../app/src/main/java/com/multiapp/app/container/StubService.kt#L63)
- [StubService.kt:84](../../app/src/main/java/com/multiapp/app/container/StubService.kt#L84)
- [StubService.kt:181-187](../../app/src/main/java/com/multiapp/app/container/StubService.kt#L181-L187)

#### Evidence

`StubService` returns `START_NOT_STICKY` regardless of guest `onStartCommand()` result. Foreground service uses a host placeholder notification/type and does not map guest notification/type to the real host service lifecycle.

#### Failure scenario

Guest services expecting `START_STICKY`, `START_REDELIVER_INTENT`, foreground location/media/camera/phone-call service types, or correct notifications may be killed or behave incorrectly.

#### Fix direction

Return the guest `onStartCommand()` result where possible. Map guest `startForeground(id, notification, type)` to actual host foreground behavior, or explicitly reject unsupported types.

### 16. Broadcast semantics are reduced to simple dispatch

**Severity:** HIGH
**Primary file:** [VirtualContextWrapper.kt](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt)

#### Evidence

The provider/service agent found ordered, sticky, receiver permission, result receiver, initial code/data/extras, and abort semantics are not faithfully implemented.

#### Failure scenario

Push SDKs, media scanners, account flows, SMS, device state listeners, and installers can silently fail or receive wrong results.

#### Fix direction

Implement or explicitly fail:

- ordered broadcast result chain
- sticky broadcast cache and initial return from `registerReceiver`
- permission checks
- result receiver callbacks
- abort/result extras semantics

## P2 / Native Compatibility Findings

### 17. Native hook API coverage is incomplete

**Severity:** HIGH
**Primary files:**

- [native-hook.cpp:672-683](../../core/hook/src/main/cpp/native-hook.cpp#L672-L683)
- [NativePrivatePathRedirectInstaller.kt:52-64](../../core/loader/src/main/java/com/multiapp/core/loader/NativePrivatePathRedirectInstaller.kt#L52-L64)

#### Missing API families

- `open64`, `stat64`, `lstat64`, `fopen64`
- `faccessat`, `newfstatat`, `fstatat64`
- `mkdirat`, `unlinkat`, `renameat`, `renameat2`
- `rmdir`, `remove`
- bionic / fortify wrappers

#### Failure scenario

32-bit protected libraries, SQLite, C++ filesystem, or bionic wrappers can bypass current redirect hooks.

### 18. Root package data dir without trailing slash is not redirected

**Severity:** HIGH
**Primary file:** [NativeHookBridge.kt:875-877](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt#L875-L877)

#### Evidence

Only these are registered:

```text
/data/data/<pkg>/
/data/user/0/<pkg>/
```

Not:

```text
/data/data/<pkg>
/data/user/0/<pkg>
```

#### Failure scenario

Shell/native code doing `stat("/data/data/<pkg>")` or `realpath("/data/data/<pkg>")` sees the real system path, not the sandbox.

### 19. Guest `/data/data/<pkg>/lib` does not map to ABI-specific native library dir

**Severity:** HIGH
**Primary files:**

- [NativeHookBridge.kt:875-877](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt#L875-L877)
- [NativeLibraryPaths.kt](../../core/loader/src/main/java/com/multiapp/core/loader/NativeLibraryPaths.kt)

#### Failure scenario

Guest code opening `/data/data/<pkg>/lib/libx.so` maps to `dataRoot/lib/libx.so`, while the actual extracted file is usually `dataRoot/lib/<abi>/libx.so`.

#### Fix direction

Add a special mapping for `/data/data/<pkg>/lib/` to the selected ABI native library dir, or create compatibility symlinks/copies.

## Test Coverage Gaps

The test-review agent found that many tests validate evidence files or source strings rather than actual runtime behavior.

Priority missing tests:

1. Cross-instance provider attack must fail.
2. Provider query/insert/update/delete/call/openFile must verify real guest URI, values, extras, Cursor/Bundle/FD return values.
3. Split + multidex + shell-like fixture must prove business Activity/Provider/Service class loading.
4. Same-origin dual-instance concurrency must verify storage, provider, service, broadcast, and activity slot isolation.
5. Process-death ProxyActivity restore must prove recovery without black screen or repeated relaunch.
6. `singleTop` / `singleTask` / `onNewIntent` must be verified on device, not only formatter/manifest parity.
7. Slow bootstrap/provider/service must prove no main-thread ANR or foreground service timeout.
8. ContentResolver hook callbacks must be unit-tested by executing captured before-callbacks for all overloads.

## Recommended Fix Plan

### Phase 0 — Stop unsafe routes

1. Disable direct stub provider routing without token.
2. Add route token registry and enforcement.
3. Enforce virtual Provider `exported` and permission policy.
4. Block or isolate same-origin multi-instance native redirect until scoped redirect exists.
5. Add native canonical containment checks.

### Phase 1 — Make loading model real

1. Add split APK metadata to install records and snapshots.
2. Update classloader, resources, native extraction, and ApplicationInfo for splits.
3. Populate signatures / SigningInfo.
4. Preserve typed manifest meta-data.
5. Create guest Application through a LoadedApk-equivalent path.
6. Simulate installContentProviders before Application `onCreate()`.

### Phase 2 — Component fidelity

1. Service sticky / foreground / bind callback semantics.
2. Ordered/sticky broadcast semantics.
3. processName-aware virtual process slots.
4. launchMode/taskAffinity/Recents behavior.

### Phase 3 — Behavior-first tests

Add device tests before large refactors where possible. Evidence files should support debugging, not be the main proof of correctness.

## Suggested Validation Commands

After implementing fixes, use targeted validation before broad build:

```bash
./gradlew :core:loader:testDebugUnitTest
./gradlew :core:instance:testDebugUnitTest
./gradlew :core:manifest:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Native-specific validation should include device probes for all claimed operations:

- open/openat/open64
- stat/lstat/stat64/lstat64/fstatat
- access/faccessat
- fopen/fopen64
- mkdir/mkdirat
- unlink/unlinkat/remove/rmdir
- rename/renameat/renameat2
- realpath/readlink

## Current Status

- Code modified by this review: none.
- Build/test run by this review: none.
- Review decision: **BLOCK**.
- First repair target: Provider route token + native multi-instance redirect isolation.
