# Container Runtime Bug Audit — General App Container Review

**Date:** 2026-07-07
**Branch:** `container-runtime-refactor`
**Mode:** read-only multi-agent review
**Decision:** **BLOCK — P0 provider/native mitigations landed; P1/P2 architecture blockers remain**
**Scope:** Android general app container runtime, not only QQ / QQ Reader compatibility.

## Executive Summary

This review treats MultiApp as a **general Android app container**. The current branch should not be merged as-is. The implementation has made progress on hosted Activity, Provider, Service, PackageManager, native path redirection, and evidence capture, but several fundamentals required by a VirtualApp / DroidPlugin / BlackBox-style runtime are still incomplete.

Current gate interpretation:

- The three original P0 provider/native findings are **mitigated in the current dirty tree** and are no longer documented as open CRITICAL blockers.
- The review decision remains **BLOCK** because HIGH P1/P2 runtime fidelity, module boundary, loading model, component semantics, and device evidence gaps still prevent a general-commercial-container claim.
- The mitigated P0 items remain mandatory regression gates; future PRs must not weaken token routing, process-slot-scoped native redirect, or native containment checks.

The most important open issues are not isolated QQ bugs. They are container architecture and runtime fidelity defects:

1. Split APK / dynamic feature / multidex support is only partially implemented and lacks end-to-end behavior proof.
2. Guest `Application` is still reflectively constructed/attached rather than created through a full `LoadedApk` / `ActivityThread` equivalent path.
3. Guest `Application.attachBaseContext()` / `onCreate()` can still run on a custom prewarm Looper instead of a controlled virtual main-thread model.
4. `:app` still imports loader/hook primitives directly instead of routing all runtime control through `:core:engine` facades.
5. `:core:model` still contains Android framework types, so it is not yet a pure model/contract module.
6. Provider, Service, and Broadcast lifecycle semantics diverge from Android framework behavior.
7. Tests still verify evidence strings more often than actual guest behavior.

These defects explain the observed device failures:

- QQ Reader clone resolves `com.qq.reader.activity.launch.DefaultAliasSplashActivity` but the runtime class/resource/split model is not yet proven end-to-end, causing `ClassNotFoundException` risk for classes outside the base path.
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

Caveat: these references are behavior/architecture comparisons, not an implementation decision or license clearance. Before porting or copying any approach, separately confirm license compatibility, Android API-level behavior, hidden-API risk, ROM variance, and maintenance cost. Until that confirmation is complete, the plan should reference these projects only as design precedents for runtime semantics.

## Severity Summary

| Severity | Count | Merge impact |
|---|---:|---|
| CRITICAL | 0 open / 3 mitigated in current dirty tree | No open CRITICAL; keep P0 regression gates and continue P1/P2 before commercial claim |
| HIGH | 20+ confirmed / plausible | Block |
| MEDIUM | Several | Track after P1 |
| LOW | Not prioritized | Track later |

## Implementation Update — 2026-07-07

The original P0 provider/native findings are no longer accurate as open CRITICAL blockers in the current dirty tree:

- Provider routing now requires an unguessable `multiapp_routeToken` issued by `ProviderRouteTokenRegistry`, bound to caller/target instance, authority, operation, and expiry. `StubContentProvider` canonicalizes the proxy URI from the token-backed route before dispatching.
- Native private path redirection now records `processSlot + instanceId + dataRoot`, installs scoped rules through `NativeHookBridge.setupGuestPrivatePathRedirections(...)`, and same-origin engine runtime slots are assigned per process slot.
- Native path validation now rejects parent traversal and performs canonical containment checks for existing paths plus parent-directory containment checks for create operations.
- This update adds private-root rules without trailing slashes (`/data/data/<pkg>` and `/data/user/0/<pkg>`) and segment-boundary path matching so `/data/data/com.foo` cannot match `/data/data/com.foobar`.

Remaining blockers are architectural P1/P2 items, especially:

- `:app` still directly installs/uses loader/hook primitives (`VirtualInstrumentationInstaller`, `NativeHookBridge`, `VirtualProvider*`, `VirtualService*`) instead of routing all runtime control through `:core:engine` facades.
- `:core:model` is still an Android library and still contains Android framework types, so it is not yet a pure model/contract module.
- `HostedRuntimeEngine` / app container binders still expose or consume `core:loader` bootstrap types.
- Full LoadedApk/Application equivalence, Provider pre-install ordering, Service/Broadcast fidelity, PMS signatures/typed meta-data, and commercial device evidence remain P1/P2 work.

## P0 / CRITICAL Findings — Mitigated in current dirty tree

The following findings were CRITICAL when found. They are no longer documented as open CRITICAL blockers in the current dirty tree, but remain mandatory regression gates.

### 1. Cross-instance Provider access could be forged by same-process guest code

**Severity when found:** CRITICAL
**Status:** MITIGATED in current dirty tree; regression gate remains
**Primary files:**

- [StubContentProvider.kt:251-259](../../app/src/main/java/com/multiapp/app/container/StubContentProvider.kt#L251-L259)
- [VirtualProviderDispatcher.kt:16-21](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderDispatcher.kt#L16-L21)
- [HostedProviderRuntimeBinder.kt:32-60](../../app/src/main/java/com/multiapp/app/container/HostedProviderRuntimeBinder.kt#L32-L60)
- [VirtualProviderManager.kt:54-60](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderManager.kt#L54-L60)
- [ContentProviderHook.kt:836-847](../../core/identity/src/main/java/com/multiapp/core/identity/ContentProviderHook.kt#L836-L847)

#### Pre-mitigation evidence

Before the current mitigation, `StubContentProvider` trusted route data supplied in URI query parameters or `call()` extras:

- `multiapp_instanceId`
- `multiapp_guestAuthority`

`VirtualProviderDispatcher.dispatch(uri)` read those values directly and routed to that instance/provider. `HostedProviderRuntimeBinder.ensureBound()` could bootstrap the target instance using the forged `instanceId` from the URI.

`StubContentProvider` is `exported=false`, which reduces external app attack surface, but does not protect against code already running inside the host UID/process.

#### Current mitigation evidence

The current dirty tree adds a token-backed route gate:

- [ContentProviderHook.kt](../../core/identity/src/main/java/com/multiapp/core/identity/ContentProviderHook.kt) defines `ProviderRouteTokenRegistry`, issues 32-byte unguessable tokens, and validates caller instance, target instance, authority, operation, and expiry.
- [ProviderRouteTokenGate.kt](../../app/src/main/java/com/multiapp/app/container/ProviderRouteTokenGate.kt) rejects missing/invalid tokens and rebuilds the canonical proxy URI from the registry route.
- [StubContentProvider.kt](../../app/src/main/java/com/multiapp/app/container/StubContentProvider.kt) calls `validateRouteToken(...)` before dispatch and removes route tokens before forwarding guest URIs/extras.

#### Failure / attack scenario guarded by regression tests

Guest instance A constructs a direct stub URI:

```text
content://<host>.multiapp.provider.stub/path?multiapp_instanceId=<instanceB>&multiapp_guestAuthority=<providerB>
```

Then calls `query`, `insert`, `update`, `delete`, `openFile`, or `call`. Without the token gate, the dispatcher routes to instance B. With the current mitigation, direct stub URI without a valid operation-bound token must fail.

#### Implemented mitigation / remaining validation

Implemented main-path mitigation:

1. Generate an unguessable route token when the host-side hook rewrites a legitimate guest URI.
2. Store token metadata in a process-local registry:
   - caller instance
   - target instance
   - guest authority
   - allowed operation
   - expiry
3. Stub provider accepts only valid tokens.
4. Direct stub URI without a valid token fails.

Remaining regression gates:

- forged URI without token must fail for `query/insert/update/delete/call/openFile`.
- expired token, wrong-operation token, wrong-authority token, and cross-instance token reuse must fail.
- Provider `exported`, permission, and URI-grant fidelity remain P1/P2 runtime policy work; they are no longer part of the original direct-route-forgery CRITICAL blocker.

### 2. Native private path redirects were global per package and could overwrite another instance

**Severity when found:** CRITICAL
**Status:** MITIGATED in current dirty tree; main path uses process-slot-scoped guest private redirection
**Primary files:**

- [NativeHookBridge.kt:868-879](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt#L868-L879)
- [native-hook.cpp:90-91](../../core/hook/src/main/cpp/native-hook.cpp#L90-L91)

#### Pre-mitigation evidence

`setupGuestPrivatePathRedirections()` accepted `instanceId`, but installed rules only by `guestPackageName`:

```text
/data/data/<guestPackage>/ -> <dataRoot>/
/data/user/0/<guestPackage>/ -> <dataRoot>/
```

The native layer stored these in a process-global `g_path_redirects` map.

#### Current mitigation evidence

The current dirty tree adds process-slot scoped private path redirects:

- [NativePrivatePathRedirectInstaller.kt](../../core/loader/src/main/java/com/multiapp/core/loader/NativePrivatePathRedirectInstaller.kt) records `instanceId`, `originPackageName`, canonical `dataRoot`, and `processSlot`; evidence declares `processSlot+instanceId+dataRoot` binding.
- [NativeHookBridge.kt](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt) sets the active native redirect scope and stores scoped rules with `processSlot`, `instanceId`, and `dataRoot`.
- [native-hook.cpp](../../core/hook/src/main/cpp/native-hook.cpp) stores `PathRedirectRule.process_slot`, `instance_id`, `data_root`, and matches scoped rules only against the active process slot/instance.
- Engine runtime allocation assigns same-origin instances to separate process slots in the main path.

#### Failure scenario guarded by regression tests

Two instances of the same original package must not share package-name-only native redirect state:

```text
Instance A: /data/data/com.foo/ -> A/dataRoot
Instance B: /data/data/com.foo/ -> B/dataRoot
```

With package-name-only redirect, B can overwrite A's sandbox mapping. With the current main-path mitigation, same-origin instances must use distinct process slots and native private redirects must match `processSlot + instanceId`.

#### Implemented mitigation / remaining hardening

Implemented main-path mitigation:

- same-origin multi-instance runtime allocation is process-slot based.
- native private redirect rules are scoped by `processSlot + instanceId + dataRoot`.
- evidence records the binding scope and installed private path prefixes.

Remaining regression gates:

- same-origin dual-instance concurrency must verify storage, provider, service, broadcast, activity slot, and native redirect isolation.
- legacy unscoped `NativeHookBridge.addPathRedirection(...)` still exists; it must not be used for guest private data prefixes and should be retired, compat-profile-gated, or reject private data prefixes.

### 3. Native path rewrite could escape sandbox via `..` or symlink

**Severity when found:** CRITICAL
**Status:** MITIGATED in current dirty tree; native containment checks added
**Primary file:** [native-hook.cpp:197-231](../../core/hook/src/main/cpp/native-hook.cpp#L197-L231)

#### Pre-mitigation evidence

`redirect_path()` performed a prefix match and then string concatenation:

```cpp
return best_to + path_str.substr(best_from.length());
```

It did not reject `..`, did not canonicalize the destination, and did not verify the final target stayed under `dataRoot`.

#### Current mitigation evidence

The current dirty tree adds main-path containment checks:

- [native-hook.cpp](../../core/hook/src/main/cpp/native-hook.cpp) rejects parent traversal in source/result paths, canonicalizes existing redirected paths, validates canonical containment under `dataRoot`, and validates the parent directory for create operations.
- [NativeHookBridge.kt](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt) rejects parent traversal in Java-side scoped rules and uses segment-boundary prefix matching.
- [NativePrivatePathRedirectInstaller.kt](../../core/loader/src/main/java/com/multiapp/core/loader/NativePrivatePathRedirectInstaller.kt) canonicalizes `dataRoot` and now installs both trailing-slash and no-trailing-slash private root prefixes for `/data/data/<pkg>` and `/data/user/0/<pkg>`.

#### Failure scenario guarded by regression tests

A guest/native library can request:

```text
/data/data/<pkg>/files/../../../../...
```

or use symlinks under the sandbox. Native file APIs bypass Java-level checks, so native containment must be enforced at the hook boundary. With the current mitigation, parent traversal and out-of-root canonical targets must fail.

#### Implemented mitigation / remaining native coverage

Implemented main-path mitigation:

- reject unsafe segments such as `..`.
- canonicalize existing paths and verify they remain under the target data root.
- for `O_CREAT` / create-like modes, canonicalize and validate the parent directory.
- use no-trailing-slash root rules plus segment-boundary matching so `/data/data/com.foo` cannot match `/data/data/com.foobar`.

Remaining P2/native compatibility work:

- device probes must cover all claimed API families, including `open64`, `stat64`, `lstat64`, `fopen64`, `faccessat`, `newfstatat`, `fstatat64`, `mkdirat`, `unlinkat`, `renameat`, `renameat2`, `rmdir`, `remove`, and bionic/fortify wrappers.
- symlink/no-follow/openat-style hardening should continue where Android API support permits.

## P1 / HIGH Runtime and Loading Findings

### 4. Split APK / dynamic feature / multidex paths are partially implemented but not proven end-to-end

**Severity:** HIGH
**Status:** PARTIALLY IMPLEMENTED; remains HIGH until split/multidex fixture and device evidence pass
**Primary files:**

- [InstallRecord.kt:35](../../core/model/src/main/java/com/multiapp/core/model/installer/InstallRecord.kt#L35)
- [VirtualPackageSnapshot.kt:19-20](../../core/model/src/main/java/com/multiapp/core/model/virtual/VirtualPackageSnapshot.kt#L19-L20)
- [HostedRuntimeBootstrap.kt:550-559](../../core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt#L550-L559)
- [VirtualPackageInfoFactory.kt:17-33](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt#L17-L33)

#### Evidence

The original review found a single-APK runtime path. The current dirty tree has partial split support:

- [InstallRecord.kt](../../core/model/src/main/java/com/multiapp/core/model/installer/InstallRecord.kt) stores `splitApkPaths`, `splitPublicSourceDirs`, `splitNames`, and exposes `codeSourceDirs` / `publicResourceDirs`.
- [VirtualPackageSnapshot.kt](../../core/model/src/main/java/com/multiapp/core/model/virtual/VirtualPackageSnapshot.kt) stores split source/public dirs and exposes runtime path lists.
- [VirtualPackageInfoFactory.kt](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt) fills `ApplicationInfo.splitSourceDirs`, `splitPublicSourceDirs`, and `splitNames`.
- [VirtualResourcesManager.kt](../../core/loader/src/main/java/com/multiapp/core/loader/VirtualResourcesManager.kt), `VirtualProviderDispatcher`, `VirtualServiceManager`, `VirtualInstrumentation`, and launch-record patching now pass split fields through several runtime paths.

Remaining gap: the branch still lacks end-to-end behavior proof that Activity, Provider, Service, resources, and native libraries loaded from split/dynamic-feature/multidex fixtures work on device.

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

### 18. Root package data dir without trailing slash was not redirected — mitigated

**Severity when found:** HIGH
**Status:** MITIGATED in current dirty tree; pending device probes
**Primary file:** [NativeHookBridge.kt:875-877](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt#L875-L877)

#### Pre-mitigation evidence

Only these were registered:

```text
/data/data/<pkg>/
/data/user/0/<pkg>/
```

Not:

```text
/data/data/<pkg>
/data/user/0/<pkg>
```

#### Current mitigation evidence

[NativePrivatePathRedirectInstaller.kt](../../core/loader/src/main/java/com/multiapp/core/loader/NativePrivatePathRedirectInstaller.kt) now installs four private path prefixes:

```text
/data/data/<pkg>
/data/data/<pkg>/
/data/user/0/<pkg>
/data/user/0/<pkg>/
```

[NativeHookBridge.kt](../../core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt) and [native-hook.cpp](../../core/hook/src/main/cpp/native-hook.cpp) also use segment-boundary matching so a no-slash root prefix does not match sibling package names.

#### Remaining validation

Shell/native code doing `stat("/data/data/<pkg>")`, `realpath("/data/data/<pkg>")`, or equivalent no-trailing-slash probes must redirect to the sandbox on device. This is tracked as a regression probe, not an open HIGH design blocker.

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

### Phase 0 — P0 regression gates, main mitigations landed

1. Keep direct stub provider routing disabled without a valid route token.
2. Preserve route token registry enforcement for caller/target instance, authority, operation, and expiry.
3. Preserve process-slot-scoped native private redirection for same-origin multi-instance isolation.
4. Preserve native canonical containment checks, parent-traversal rejection, create-parent containment, and segment-boundary prefix matching.
5. Add/keep behavior and device regression tests for forged provider routes, native root/no-slash paths, traversal attempts, and same-origin dual-instance isolation.

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

- Code modified after this review: native private-path redirect root mapping, segment-boundary matching, and related unit tests.
- Current dirty tree also contains provider route-token enforcement, process-slot scoped native private redirects, partial split-path model/runtime propagation, default guest `Application` creation, and launch-record patch readiness guards.
- Validated locally:
  - `./gradlew :core:hook:testDebugUnitTest --tests "com.multiapp.core.hook.NativeHookBridgeTest" --tests "com.multiapp.core.hook.PathTrieTest"`
  - `./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.NativePrivatePathRedirectInstallerTest" --tests "com.multiapp.core.loader.NativeLibrariesStageTest"`
- Review decision remains: **BLOCK** until P1/P2 commercial runtime blockers are closed.
- Next repair target: move app/container direct loader/hook access behind `:core:engine` facades, make `:core:model` a pure contract/model module, close LoadedApk/Application equivalence, and add behavior-first device evidence.

## Execution Update - 2026-07-08

### Implemented in this pass

- Added AppOps package/uid rewrite on the hosted baseline path, following the VirtualApp-style rule: guest/origin/virtual package arguments are rewritten to the host package when crossing AppOps binder-facing APIs, and the adjacent AppOps uid is rewritten to the real runtime uid. This targets the observed GKD failure class: `Specified package "li.songe.gkd" under uid ... but it is not`.
- Added a ServiceManager-level AppOps binder proxy for `ServiceManager.sCache["appops"]`. This covers direct `IAppOpsService.Stub.asInterface(ServiceManager.getService("appops"))` callers that bypass `Context.getSystemService(Context.APP_OPS_SERVICE)`.
- `VirtualContextWrapper` keeps guest-facing `getPackageName()` as origin identity but exposes host identity through `getOpPackageName()`, and API 34+ `getAttributionSource()` is rewritten to the host package.
- `VirtualPackageManagerProxyStage` records AppOps proxy evidence beside PMS/Notification proxy evidence: `appOpsPackageProxyStatus`, `appOpsServiceManagerProxyStatus`, source packages, host package, mode, and failure reason fields.
- Added Application bootstrap progress evidence (`application-progress`) so QQ/QQ Reader style stalls can be split into class load, constructor, context creation, attach, runtime publication, and `onCreate` phases.
- Process slots are no longer only evidence strings. Each proxy Activity slot is mapped to a concrete Android process (`:v0` through `:v23`), and each matching `ContainerActivityV*` entry is declared in the same process so guest bootstrap and hosted proxy run in the same process slot.
- Engine process/proxy allocation is now paired: `runtime.processSlot` is derived from the selected `runtime.proxySlot`, preventing the old state where evidence could say one process slot while the selected proxy belongs to another.
- `ContainerActivity` writes `activity-process-slot` evidence and aborts early if the actual process name does not match `engineProcessSlot`.

### Open-source references used for this pass

- VirtualApp AppOps proxy precedent: `AppOpsManagerStub` rewrites AppOps package/uid at the system-service boundary.
- DroidPlugin ActivityManager proxy precedent: central AMS proxying instead of scattered per-call patches.

### Verification

```bash
./gradlew :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

All four commands passed. A combined loader/engine/app unit-test invocation exceeded the local 180s command timeout, so the gate was split by module. After adding the ServiceManager AppOps proxy, `:core:loader:testDebugUnitTest` passed again in 1m 27s. `:app:assembleDebug` completed in 2m 50s and still emits the existing AGP 8.7.3 / `compileSdk=36` compatibility warning.

### Still BLOCK

- AppOps now covers both `AppOpsManager.mService` and `ServiceManager.sCache["appops"]` paths. Nested `AttributionSource` / `AttributionSourceState`-like values are covered by local tests, but the path still needs device proof.
- Process slots now bind hosted Activity bootstrap to real Android processes, but Provider/Service/Broadcast stubs are not yet fully per-process and per-instance.
- Per-process globals (`EngineRuntimeRegistry.global`, `VirtualProcessRuntime.global`, `VirtualActivityRecordManager.global`) must be audited because real process slots mean memory singletons are no longer shared across all slots.
- LoadedApk/Application equivalence remains incomplete: progress evidence improved observability, but this is not yet a full `LoadedApk.makeApplication()` / `ActivityThread` model.
- Device evidence is still required before any `DONE` claim: `logcat`, `dumpsys activity exit-info`, `dumpsys activity recents`, `run-as com.multiapp.app files/hosted_launch_evidence`, and `files/instances`.

## Execution Update - 2026-07-08 Process-Slot Consistency

### Implemented in this pass

- Fixed the proxy/process slot model so one slot index owns all three Activity
  proxy launchMode variants:
  - `ProxyActivityN`
  - `ProxyActivitySingleTopN`
  - `ProxyActivitySingleTaskN`
- The owning process set is now `:v0` through `:v7`; all three proxy variants
  for index `N` run in `:vN`. This prevents one guest instance from being split
  across different host processes only because a guest Activity uses
  `singleTop` or `singleTask`.
- Carried `processSlot` through `HostedBootstrapResult` and
  `VirtualContextConfig`, then used it to restrict proxy allocation in
  `VirtualContextWrapper`, `VirtualInstrumentation`,
  `ActivityThreadLaunchRecordPatcher`, `VirtualAmsComponentDispatcher`,
  provider dispatch, and service dispatch where runtime state is available.
- Extended AppOps rewrite to nested `AttributionSource` /
  `AttributionSourceState`-like values and arrays, so AppOps calls are no
  longer limited to direct `String` package arguments.
- `StubService` now returns the guest Service `onStartCommand()` result for the
  synchronous path. Async bootstrap still returns `START_NOT_STICKY` because
  the host method has already returned; evidence records that distinction.

### Verification

```bash
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ProxyActivitySlotsTest" --tests "com.multiapp.core.loader.VirtualContextWrapperTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:testDebugUnitTest --tests "com.multiapp.app.container.ProxyActivityClassParityTest" --tests "com.multiapp.app.container.StubServiceTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

All three commands passed. A combined loader/app targeted invocation exceeded
the local 180s tool timeout, so it was split by module and then passed.

### Still BLOCK

- This is still not device proof. Need evidence that standard/singleTop/
  singleTask launches for one instance remain in the same `:vN` process.
- Provider/Service/Broadcast owning-process routing is still incomplete.
- LoadedApk/Application equivalence remains incomplete and still blocks
  commercial compatibility claims.
