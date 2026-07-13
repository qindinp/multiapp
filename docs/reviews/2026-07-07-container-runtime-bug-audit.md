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
5. `:core:model` main sources no longer expose Android framework types after the
   2026-07-10 boundary cleanup, but the module is still an Android Gradle
   library and still needs a later build-structure migration before it is a
   fully pure JVM/Kotlin contract module.
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

- Production `:app` sources and `app` implementation dependencies have been
  moved off direct `core.loader`, `core.hook`, `core.xposed`, and
  `core.identity` runtime primitives for the current main container paths.
  This boundary is now covered by `EngineBoundaryTest`, but test fixtures still
  construct some legacy loader DTOs and broader Service/Broadcast/native
  runtime fidelity remains open.
- `:core:model` main sources now have a regression test preventing Android
  framework imports/typed references, but the module is still packaged as an
  Android library and is not yet a pure JVM/Kotlin contract module.
- `HostedRuntimeEngine` / app container binders now expose engine summary and
  diagnostics DTOs for app production consumers, but internal engine
  implementation still adapts to `core:loader` bootstrap/runtime primitives.
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
- Provider/Service/Broadcast owning-process routing is still incomplete. The
  next slice starts Provider/Service slot-aware Android entry components, but
  device evidence and Broadcast routing remain open.
- LoadedApk/Application equivalence remains incomplete and still blocks
  commercial compatibility claims.

## Execution Update - 2026-07-08 Provider/Service Slot Entry

### Implemented in this pass

- Provider route tokens now optionally bind `processSlot`, and validation can
  reject an otherwise valid token when the expected slot does not match.
- `ProviderRoutingStage` stores `instanceId -> processSlot` before installing
  provider routing, so both baseline and hook-enabled URI rewrite paths can
  issue slot-bound provider tokens.
- Provider URI/extras rewrite now carries `multiapp_processSlot`; guest URI and
  extras conversion removes it before dispatch to guest provider code.
- Added `StubContentProviderV0..V7` manifest provider entries with unique
  authorities `${applicationId}.multiapp.provider.stub.vN` and matching
  `android:process=":vN"`.
- `VirtualProviderManager` resolves slot-specific proxy authorities when a
  process slot is known, and `HostedProviderRuntimeBinder` passes that slot
  into hosted runtime bootstrap.
- Added `StubServiceV0..V7` manifest service entries bound to `:v0..:v7`.
- `VirtualServiceStartRequest` and `VirtualServiceProxySpec` now carry
  `processSlot`; `VirtualServiceManager` maps slot-aware starts to the matching
  `StubServiceVN` instead of the default-process fallback.
- `HostedServiceRuntimeBinder` passes the service process slot into hosted
  runtime bootstrap and rejects cached runtime results from a different slot.
- Service/provider evidence records the runtime-bind process slot.

### Verification

```bash
./gradlew :app:testDebugUnitTest --tests "com.multiapp.app.container.HostedProviderRuntimeBinderTest" --tests "com.multiapp.app.container.HostedServiceRuntimeBinderTest" --tests "com.multiapp.app.container.StubContentProviderRouteTokenTest" --tests "com.multiapp.app.container.ProviderProxyUriTest" --tests "com.multiapp.app.container.ProxyActivityClassParityTest" :core:identity:testDebugUnitTest --tests "com.multiapp.core.identity.ContentProviderHookUriRewriteTest" :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ProviderRoutingStageTest" --tests "com.multiapp.core.loader.VirtualServiceManagerTest" --tests "com.multiapp.core.loader.VirtualProviderManagerTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ProviderRoutingStageTest" --tests "com.multiapp.core.loader.VirtualServiceManagerTest" --tests "com.multiapp.core.loader.VirtualProviderManagerTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

The combined command reached a JVM test failure in loader because the test used
unmocked `android.net.Uri.parse`; app target tests had already passed. The
loader test was corrected to assert pure provider resolution, and the focused
loader command then passed. `:app:assembleDebug` also passed; the existing AGP
8.7.3 / `compileSdk=36` compatibility warning remains.

### Still BLOCK

- Provider/Service slot-aware entry is locally covered but not device-proven.
  Device evidence must show the actual Android process name for provider and
  service dispatch is the expected `:vN` and not the default host process.
- Broadcast still has no slot-aware gateway and remains process-local.
- `EngineRuntimeRegistry.global`, `VirtualProcessRuntime.global`, and
  component record managers remain per-process memory state; cross-process
  runtime truth still needs a durable/binder-backed strategy.
- LoadedApk/Application equivalence remains incomplete.

## Execution Update - 2026-07-09 LoadedApk-First Application Creation

### Implemented in this pass

- `ApplicationStage` and the production `HostedRuntimeBootstrap` path now
  default to `LoadedApkGuestApplicationCreator` instead of reflective-first
  Application construction.
- The new creator installs a guest sandbox LoadedApk through
  `ActivityThreadLoadedApkInstaller.installGuestSandbox(...)`, invokes
  `LoadedApk.makeApplication(false, instrumentation)`, and patches the returned
  guest Application back into the installed LoadedApk through
  `ActivityThreadLoadedApkInstaller.bindApplication(...)`.
- Fallback is explicit: if the LoadedApk path is unavailable, the old
  reflective attach creator is used and evidence records
  `loadedApkApplicationCreatorStatus=FALLBACK` plus the failure class/reason.
- Application runtime publication now carries the engine `processSlot`, so
  guest `Application.onCreate()` sees the same hosted runtime slot as the
  Activity/Provider/Service path.
- Focused JVM tests cover the LoadedApk-first creator with injected fake
  ActivityThread, LoadedApk, resource bundle, and `makeApplication` seams.

### Verification

```bash
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.LoadedApkBridgeTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --tests "com.multiapp.core.loader.HostedRuntimeBootstrapTest" --tests "com.multiapp.core.loader.ActivityThreadLoadedApkInstallerTest" --tests "com.multiapp.core.loader.LoadedApkBridgeTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

All three focused loader commands passed. Full `:core:loader:testDebugUnitTest`
and `:app:assembleDebug` also passed.

### Still BLOCK

- This is not device proof. Real hosted launches must show
  `applicationCreator=LOADED_APK_MAKE_APPLICATION` and
  `loadedApkApplicationCreatorStatus=PASS` in `files/hosted_launch_evidence`
  before the Application model can be marked `PASS`.
- Provider pre-install before guest `Application.onCreate()` remains open.
- Application main-thread/prewarm semantics still need device validation for
  QQ/WeChat/QQ Reader style heavy bootstrap paths.

## Execution Update - 2026-07-09 Provider Preinstall Ordering

### Implemented in this pass

- Added `GuestProviderPreinstaller` for same-process guest provider preinstall.
- `ApplicationStage` now runs provider preinstall after runtime publication and
  before guest `Application.onCreate()`, matching Android's
  `makeApplication -> installContentProviders -> callApplicationOnCreate`
  ordering.
- The preinstall path uses `VirtualProviderManager` and
  `VirtualProviderRuntime.getOrCreate(...)`, so preinstalled providers share
  the same runtime cache as later stub-provider dispatch.
- Application evidence now records `providerPreinstallStatus`, total/attempted/
  installed/cached/failed/skipped counts, authorities, and failure/skipped
  reasons.
- A JVM ordering test proves runtime publication happens before provider attach
  and provider attach happens before guest `Application.onCreate()`.

### Verification

```bash
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ApplicationStageTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:assembleDebug --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

The targeted `ApplicationStageTest`, full `:core:loader:testDebugUnitTest`, and
`:app:assembleDebug` commands passed.

### Still BLOCK

- This is same-process provider preinstall only. Custom provider processes are
  skipped until process-name-aware virtual process routing is completed.
- Device evidence must prove provider attach occurs before guest
  `Application.onCreate()` for real hosted launches.
- Provider operation semantics and return-value behavior still need broader
  behavior tests beyond evidence fields.

## Execution Update - 2026-07-10 Engine Subservice Contracts

The engine now exposes runtime-bound subsystem service contracts for Activity,
Provider, Service, Broadcast, Storage, and Native through
`VirtualSystemServer`. Each subsystem can query the current runtime binding for
an `instanceId`, including process/proxy slot and runtime epoch. Missing
runtimes fail closed, and known runtimes return `PARTIAL` with explicit
supported/unsupported operation sets instead of pretending that incomplete
component semantics are complete.

Verification:

```powershell
./gradlew :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- These contracts are not full VAMS/VProvider/VService/VBroadcast
  implementations.
- App/container and loader dispatchers still need to consume these engine
  services before the architecture can claim VirtualApp/BlackBox-style central
  system-service ownership.
- Device evidence remains missing for component semantics and process-death
  recovery.

## Execution Update - 2026-07-10 VPMS Query and Native Evidence Follow-up

Two additional engine-side slices landed after the runtime-bound subsystem
contracts:

- `VirtualPackageService` now resolves snapshot-backed components with action,
  category, scheme, MIME exact/wildcard, authority, exact path, and priority
  ordering. `EngineRuntimeStateStore` persists/restores those filter fields and
  provider read/write permissions.
- PR-10 storage/native evidence now derives native runtime verdicts from
  hosted bootstrap facts. Packages without guest `.so` files report explicit
  `UNSUPPORTED / NO_GUEST_NATIVE_LIBRARIES`. Packages with resolvable guest
  native libraries can report `findLibraryVerdict=PASS`, while
  `namespaceVerdict` and `nativeLoadVerdict` remain `PARTIAL` until a real
  device/native-load probe exists.

Verification:

```powershell
./gradlew :core:model:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineStorageDiagnosticsEvidenceTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- VPMS is still only as complete as the parsed `VirtualPackageSnapshot`; parser
  coverage for signatures, `SigningInfo`, typed metadata, and full
  `IntentFilter` semantics remains open.
- `nativeLoadVerdict=PARTIAL` is not native compatibility proof. It only means
  storage diagnostics can now distinguish "no native libs", "classloader cannot
  resolve library", and "classloader can resolve library but native load was not
  executed".
- Device evidence remains required before any native/runtime line item can be
  marked `PASS`.

## Execution Update - 2026-07-10 Activity Proxy Recovery Evidence

The Activity proxy recovery path now preserves launch flags during
process-death recovery and exports task identity evidence. When the in-memory
guest intent store is gone, recovery falls back to the current proxy intent
flags instead of recording `0`, so `FLAG_ACTIVITY_NEW_TASK` survives a recents
restore path.

Added evidence fields in proxy Activity launch records:

- `taskId`
- `taskAffinity`
- `launchMode`
- `intentFlags`

`VirtualSystemServer.activityService` now lists
`proxy-process-death-recovery-evidence` as a supported operation. It still
keeps `recents-device-proof` in the unsupported operation set.

Verification:

```powershell
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:testDebugUnitTest --tests "com.multiapp.app.container.ProxyActivityEvidenceTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- This is not a full durable task-stack implementation. `VirtualActivityStack`
  still lives in process memory.
- Device evidence still must prove two same-origin instances appear separately
  in recents and survive process death without black screen or repeated proxy
  relaunch.

## Execution Update - 2026-07-10 Activity Task State Persistence Foundation

Activity task state now has a first engine-owned persistence layer:

- `VirtualActivityStack.restore(...)` restores task snapshots, rebuilds future
  task/event ids, and filters finished/destroyed/duplicate records.
- `VirtualActivityRecordManager.exportTasks()` / `restoreTasks(...)` rebuild
  task state plus token, proxy slot, and activity-id indexes.
- `EngineActivityTaskStateStore` provides in-memory and file-backed snapshot
  persistence, and `EngineActivityTaskRecords` exposes snapshot/persist/restore
  through an engine facade.
- `ContainerActivity` now restores and persists
  `engine_activity_task_state.properties` around proxy launch/prune.
- `ProxyActivityBase` restores persisted state only when the current in-memory
  task manager is empty, then persists after proxy intent observation/recovery.
  This keeps the process-death path recoverable without overwriting fresh
  hot-path records with stale disk state.
- `ProxyActivityBase` now mirrors the VirtualApp-style lifecycle feedback loop:
  resume/pause/stop update and persist virtual Activity state, while
  `onDestroy` only removes the record from the task snapshot when Android marks
  the proxy as finishing.
- `VirtualActivityService.queryTaskState(instanceId)` now reads the engine
  task snapshot and exposes instance-filtered task/activity counts, top task,
  top Activity, and state.
- `VirtualEvidenceService.exportReport(instanceId)` adds
  `operationEvidence.activity.task-state` entries so exported engine reports
  can show task-state evidence without claiming Android recents parity.

Verification:

```powershell
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerRuntimePathsTest" --tests "com.multiapp.app.container.ProxyActivityEvidenceTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" :app:testDebugUnitTest --tests "com.multiapp.app.container.ContainerRuntimePathsTest" --tests "com.multiapp.app.container.ProxyActivityEvidenceTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.DefaultVirtualizationEngineTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- The task snapshot store is wired into the current app/container Activity
  path and engine report path, but not yet proven on device.
- `recents-device-proof` remains unsupported until device evidence shows
  same-origin multi-instance tasks appear separately in Android recents and
  recover after process death without black screen.

## Execution Update - 2026-07-10 Activity Control-Plane Operations

`VirtualActivityService` now owns the first persisted-record control-plane
operations for Activity state:

- `markActivityState(...)`
- `finishActivity(...)`
- `setActivityResult(...)`
- `consumeActivityResult(...)`
- `consumePendingNewIntent(...)`

The implementation restores the persisted task snapshot, verifies the runtime
exists, verifies the token belongs to the requested instance, applies the
operation through `VirtualActivityRecordManager`, and persists the updated
snapshot. Cross-instance token use fails closed and leaves the other instance's
record unchanged.

`DefaultVirtualizationEngine.exportEvidence(instanceId)` now asks
`VirtualEvidenceService.exportReport(...)` for the report first, so engine
exports include `operationEvidence.activity.task-state` when a runtime exists.
Missing/stopped runtimes still fall back to the runtime-registry failure report.

`EngineActivityTaskController` now acts as the app/container-facing task-state
facade. `ContainerActivity` and `ProxyActivityBase` use this controller instead
of directly constructing `EngineActivityTaskRecords` or
`FileBackedEngineActivityTaskStateStore`. The controller attaches the
file-backed engine runtime state, delegates lifecycle mutations to
`VirtualActivityService`, and keeps restore/persist logic inside
`:core:engine`.

`RegistryBackedVirtualActivityService` now operates on the shared hosted
`VirtualActivityRecordManager` instead of a temporary manager, and only restores
persisted task state when the requested token is missing. This keeps hot
process Activity records coherent while still allowing process-death recovery
from the file-backed task snapshot.

`VirtualInstrumentation` now depends on a loader-level
`VirtualActivityOperations` interface for pending `onNewIntent` consumption and
finish marking. `EngineRuntimeInstallers.installInstrumentation()` injects an
engine-backed implementation that calls `VirtualActivityService` first and
falls back to the local manager when a runtime record is unavailable.

The Activity subsystem now advertises precise supported local operations:

- `finish-record`
- `result-record`
- `on-new-intent-record`
- `back-stack-state`

It still keeps the Android/device-level claims unsupported:

- `result-delivery`
- `finish-result-delivery`
- `recents-device-proof`

Verification:

```powershell
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.DefaultVirtualizationEngineTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- This is not yet full Android Activity result delivery. The app/container
  proxy and instrumentation path still needs to consume these engine operations
  instead of relying on loader-local calls.
- The app/container lifecycle task-state path now uses an engine controller,
  and `VirtualInstrumentation` uses an injected engine-backed operation facade
  for pending new-intent consumption and finish marking. Launch/remap records
  and Activity result delivery still keep direct loader dependencies.
- This is not recents proof. Device evidence must still show same-origin
  multi-instance tasks in Android recents and process-death restore without
  black screen.

## Execution Update - 2026-07-10 Activity Result Route Foundation

The Activity result route is now part of the virtual Activity record model and
engine task snapshot:

- `VirtualActivityRecord` stores `resultToToken` and `resultRequestCode`.
- Hosted `startActivityForResult` remap records the parent hosted Activity token
  and request code when a source token is available.
- Remap evidence now marks result handling as `PARTIAL` with
  `HOST_PROXY_RESULT_ROUTE_RECORDED_DELIVERY_PENDING` instead of leaving the
  successful route indistinguishable from an unimplemented path.
- Task-state persistence stores and restores the route metadata, so
  process-death recovery does not silently lose the parent result target.
- `VirtualActivityOperations` and the engine-backed implementation now expose
  `setActivityResult(...)` and `consumeActivityResult(...)` for the future
  finish/delivery bridge.

Verification:

```powershell
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --tests "com.multiapp.core.loader.VirtualActivityManagerTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- This is not real `onActivityResult` delivery. The runtime still needs to
  capture guest `setResult(...)` on finish, route the result to
  `resultToToken`, and prove delivery on device.
- `result-delivery` and `finish-result-delivery` remain unsupported in the
  Activity subsystem evidence until that bridge and device proof land.

## Execution Update - 2026-07-10 Activity Finish Result Record Foundation

The runtime now records a finishing child Activity's result onto its source
Activity token:

- `VirtualActivityOperations.recordActivityResultForFinish(...)` is the new
  loader/engine operation boundary.
- The manager-backed implementation verifies the child record, result route,
  source token, and same-instance ownership before writing the result.
- The engine-backed implementation first attempts
  `VirtualActivityService.setActivityResult(...)` and only falls back to the
  local manager when hot task-state has not yet reached the persisted engine
  store.
- `VirtualInstrumentation` reads the finishing Activity's `mResultCode` and
  `mResultData` before marking the child finished, then writes
  `ACTIVITY_FINISH_RESULT` evidence.

Open-source comparison:

- VirtualApp routes `resultTo/resultWho/requestCode` through its virtual
  Activity manager on `startActivity(...)`.
- VirtualApp's finish path reads `Activity.mResultCode` and
  `Activity.mResultData` before finishing the Activity.
- VirtualApp has a separate result sending path, so MultiApp must still add and
  prove the callback delivery step before claiming parity.

Verification:

```powershell
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --tests "com.multiapp.core.loader.VirtualActivityManagerTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- The result is now recorded on the source token, but Android
  `onActivityResult` delivery is still not proven.
- `result-delivery` and `finish-result-delivery` remain unsupported until
  callback dispatch and device evidence land.

## Execution Update - 2026-07-10 ActivityThread Result Dispatch Bridge

MultiApp now has a code-level `ActivityThread.sendActivityResult(...)` bridge
and a conservative source-`onResume` fallback, but the review decision remains
**BLOCK** because the path has not been device-proven and does not yet cover
process-death or cross-process result routes.

What changed:

- `ActivityThreadCompat.sendActivityResult(...)` reflects the hidden platform
  bridge and returns an explicit `SKIPPED / PARTIAL / FAIL` result.
- `VirtualInstrumentation` records a process-local virtual-token to framework
  `Activity.mToken` mapping and attempts result dispatch after
  `recordActivityResultForFinish(...)`.
- `VirtualActivityResult` now stores `requestCode`, optional `resultWho`, and
  framework-dispatch attempted/invoked state; the task-state store persists
  those fields.
- Source `onResume` can consume and deliver a pending virtual result only when
  the framework bridge was not invoked. If the hidden bridge was invoked, the
  fallback does not consume the result, avoiding an obvious double-callback
  path.
- Finish-result evidence now reports
  `activityThreadSendActivityResultVerdict`,
  `activityThreadSendActivityResultAttempted`,
  `activityThreadSendActivityResultInvoked`, method, reason, and error class.
- Resume fallback writes `ACTIVITY_RESULT_RESUME_FALLBACK` evidence.

Verification:

```powershell
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ActivityThreadCompatTest" --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
./gradlew :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.ActivityThreadCompatTest" --tests "com.multiapp.core.loader.VirtualInstrumentationStartActivityEvidenceTest" --tests "com.multiapp.core.loader.VirtualActivityRecordManagerTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" :app:compileDebugKotlin --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process -Dkotlin.incremental=false -Pksp.incremental=false
```

### Still BLOCK

- This is a JVM/code bridge plus evidence path, not device proof. The Activity
  subsystem must keep `result-delivery` and `finish-result-delivery`
  unsupported until a device artifact shows the bridge or resume fallback
  delivers the result and the source guest Activity receives
  `onActivityResult(...)`.
- The bridge depends on a process-local source framework token map. A
  VirtualApp/BlackBox-grade runtime still needs durable or IPC-backed routing
  for process death and cross-process Activity result delivery.

## Execution Update - 2026-07-10 Broadcast Engine Route Foundation

MultiApp now has a first engine-owned Broadcast route-planning surface in
`VirtualSystemServer`, but the Broadcast finding remains **HIGH / BLOCK**
because framework-equivalent receiver execution semantics are not complete and
not device-proven.

What changed:

- `VirtualBroadcastService` now exposes `planBroadcast(...)` and
  `recordBroadcastDispatch(...)`.
- Broadcast route planning is backed by the runtime's
  `VirtualPackageSnapshot` through `VirtualPackageService`, not app-side
  direct loader dispatch.
- Explicit receiver routes normalize relative class names and fail closed when
  the target receiver is missing.
- Implicit receiver routes use snapshot intent-filter matching and preserve
  manifest priority ordering.
- Route plans and dispatch results record engine operation evidence with
  target receivers, `processSlot`, verdict, supported operations, and
  unsupported operations.
- Ordered, sticky, result receiver, and abort semantics return explicit
  `UNSUPPORTED`.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- Engine route planning is not full `VActivityManager`/`VBroadcast` parity.
- Real `BroadcastReceiver.onReceive`, `PendingResult`, ordered result chains,
  sticky broadcasts, receiver permissions, abort/result extras, cross-process
  route delivery, and device evidence remain open.
- Broadcast support must remain `PARTIAL` or `UNSUPPORTED` in capability
  matrices until these semantics are implemented and proven on-device.

## Execution Update - 2026-07-10 Service Engine Route Foundation

MultiApp now has a first engine-owned Service route-planning surface in
`VirtualSystemServer`, but the Service finding remains **HIGH / BLOCK** because
the full Android Service lifecycle is not yet implemented or device-proven.

What changed:

- `VirtualServiceService` now exposes `planService(...)` and
  `recordServiceDispatch(...)`.
- Service route planning is backed by `VirtualPackageSnapshot` through
  `VirtualPackageService`, not direct app-side loader dispatch.
- Explicit Service routes normalize relative class names and fail closed when
  the target Service is missing.
- Implicit Service routes use snapshot intent-filter matching and select the
  highest-priority target.
- Route plans and dispatch results record engine operation evidence with
  operation, target Service, `processSlot`, foreground flag, verdict, supported
  operations, and unsupported operations.
- `BIND` / `UNBIND`, foreground service type mapping, and sticky restart
  semantics return explicit `UNSUPPORTED`.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- Engine route planning is not full `VActivityManager`/`VService` parity.
- Real `onStartCommand` return propagation, Service destruction rules for
  started + bound state, `onBind`/`onUnbind`/`onRebind`, foreground
  notification/type mapping, sticky restart, cross-process delivery, and
  device evidence remain open.
- Service support must remain `PARTIAL` or `UNSUPPORTED` in capability
  matrices until these semantics are implemented and proven on-device.

## Execution Update - 2026-07-10 Service Dispatcher Engine Gate

The app/container Service dispatcher now consumes the engine Service route
plan before invoking loader execution. This is a structural improvement toward
VirtualApp/BlackBox-style central service ownership, but it still does not
complete Android Service parity.

What changed:

- `DefaultEngineServiceDispatcher` calls
  `VirtualServiceService.planService(...)` before loader dispatch.
- `FAIL` / `UNSUPPORTED` route plans fail closed and do not invoke
  `VirtualServiceDispatcher`.
- Successful loader dispatch results are translated into
  `VirtualServiceOperationResult` and recorded through
  `VirtualServiceService.recordServiceDispatch(...)`.
- Tests cover both the evidence path and the fail-closed no-loader-dispatch
  path.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- This is still not complete `VService` parity. Loader primitives execute the
  guest Service after engine gating.
- `bindService`, foreground service type and notification mapping, sticky
  restart, started+bound destruction semantics, cross-process Service routing,
  and device evidence remain open.

## Execution Update - 2026-07-10 Provider Engine Route Foundation and Dispatcher Gate

MultiApp now has a first engine-owned Provider route-planning and dispatcher
gate path. This moves Provider dispatch closer to a VirtualApp/BlackBox-style
central `VProvider` control plane, but it still does not complete Android
Provider parity.

What changed:

- `VirtualProviderService` now exposes `planProvider(...)` and
  `recordProviderDispatch(...)`.
- Provider route planning is backed by `VirtualPackageSnapshot` through
  `VirtualPackageService`, not app-side direct loader dispatch.
- Route plans include operation, guest authority, proxy authority, route-token
  presence, `processSlot`, Provider process name, permission/readPermission/
  writePermission, grant-URI flag, supported operations, unsupported
  operations, and target Provider evidence.
- Missing runtime, process-slot mismatch, missing Provider authority, and
  unknown Provider operations fail closed or return explicit `UNSUPPORTED`.
- `DefaultEngineProviderDispatcher` now calls
  `VirtualProviderService.planProvider(...)` before invoking
  `VirtualProviderDispatcher`.
- `FAIL` / `UNSUPPORTED` route plans do not invoke loader dispatch.
- Loader dispatch results are translated into
  `VirtualProviderOperationResult` and recorded through
  `VirtualProviderService.recordProviderDispatch(...)`.
- `StubContentProvider` passes `operationName` into the engine dispatcher so
  evidence distinguishes `query`, `insert`, `openFile`, `notifyChange`, and
  related operations.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- This is still not complete `VProvider` parity. Loader primitives still create
  and invoke guest Provider instances after engine gating.
- URI grants, `ContentObserver`, `notifyChange`, custom Provider process
  delivery, Provider permission enforcement, typed Provider metadata,
  same-process preinstall device proof, split/multidex Provider loading, and
  real query/insert/update/delete/call/openFile return-value behavior remain
  open.
- Device evidence is still required before Provider support can be marked
  `PASS` in a commercial compatibility matrix.

## Execution Update - 2026-07-10 Broadcast Dispatcher Engine Gate

MultiApp now has an engine-installed Broadcast dispatch gate in front of the
hosted `VirtualContextWrapper.sendBroadcast(...)` path. This is another step
toward a central `VBroadcast` service, but the Broadcast finding remains
**HIGH / BLOCK**.

What changed:

- `:core:loader` now exposes `VirtualAmsComponentDispatchers`, an installable
  dispatcher factory extension point. Loader still does not depend on engine.
- `VirtualContextWrapper` creates the current
  `DefaultVirtualAmsComponentDispatcher` fallback and lets the installed
  engine factory wrap it.
- `DefaultEngineAmsComponentDispatcher` in `:core:engine` wraps the fallback
  dispatcher.
- For Broadcast dispatch, the engine wrapper calls
  `VirtualBroadcastService.planBroadcast(...)` before loader dispatch.
- `FAIL` / `UNSUPPORTED` plans fail closed and do not invoke loader fallback
  dispatch.
- Successful fallback results are converted into
  `VirtualBroadcastOperationResult` and recorded through
  `VirtualBroadcastService.recordBroadcastDispatch(...)`.
- App startup installs this path through
  `EngineRuntimeInstallers.installAmsComponentDispatcher()`.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- This is not full `VBroadcast` parity. Loader primitives still perform actual
  receiver instantiation and `onReceive` invocation after engine gating.
- Ordered broadcasts, sticky broadcasts, result receivers, permission checks,
  abort/result extras, receiver process routing, `PendingResult`, cross-process
  delivery, and device evidence remain open.
- Broadcast support must remain `PARTIAL` or `UNSUPPORTED` until those
  semantics are implemented and proven on-device.

## Execution Update - 2026-07-10 Service AMS Dispatcher Engine Gate

MultiApp now gates the guest Context `startService`,
`startForegroundService`, and `bindService` paths through the engine-installed
AMS dispatcher wrapper before loader remap/execution. This is a control-plane
improvement, but the Service finding remains **HIGH / BLOCK**.

What changed:

- `DefaultEngineAmsComponentDispatcher.resolveStartServiceIntent(...)` builds
  a `VirtualServiceDispatchPlanRequest` from the source `Intent` and calls
  `VirtualServiceService.planService(...)`.
- `START` / `START_FOREGROUND` plans gate host proxy-Service remapping.
- `FAIL` / `UNSUPPORTED` plans return
  `StartServiceMappingResult.Blocked`, record service dispatch evidence, and
  do not call the loader fallback remapper.
- `DefaultEngineAmsComponentDispatcher.dispatchBindService(...)` now calls
  `VirtualServiceService.planService(...)` with operation `BIND` before loader
  bind execution.
- Since `BIND` remains explicitly unsupported by the engine service model, the
  guest `bindService` path now fails closed through engine evidence instead of
  invoking loader bind primitives.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- This is not full `VService` parity. `stopService` and `unbindService` are not
  fully engine-gated in this slice.
- `bindService` is deliberately fail-closed as `UNSUPPORTED`; it is not a
  compatibility pass.
- Foreground notification/type mapping, sticky restart, started+bound
  destruction policy, cross-process Service delivery, and device evidence
  remain open.

## Execution Update - 2026-07-10 Service Stop/Unbind AMS Dispatcher Follow-up

MultiApp now gates the remaining guest Context Service control-plane calls in
the engine-installed AMS dispatcher wrapper. The Service finding remains
**HIGH / BLOCK** because this is still not full Android `VService` parity.

What changed:

- `DefaultEngineAmsComponentDispatcher.dispatchStopService(...)` now calls
  `VirtualServiceService.planService(...)` with operation `STOP` before loader
  stop execution.
- `FAIL` / `UNSUPPORTED` stop plans fail closed and do not call loader
  fallback.
- Loader stop results are converted into `VirtualServiceOperationResult` and
  recorded as `service/dispatch` operation evidence.
- `DefaultEngineAmsComponentDispatcher.dispatchUnbindService(...)` now calls
  `VirtualServiceService.planService(...)` with operation `UNBIND`.
- Since `UNBIND` is currently part of unsupported bind-service semantics, it
  fails closed through engine evidence instead of invoking loader unbind
  primitives.
- `VirtualServiceOperationResult` now includes `stopped` and `unbound` fields
  in exported Service dispatch evidence.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- This is not full `VService` parity. Loader primitives still perform actual
  guest Service lifecycle after engine gating.
- `bindService` and `unbindService` remain deliberately `UNSUPPORTED` until
  bound-service lifecycle, connection tracking, `onUnbind` / `onRebind`, and
  started+bound destruction rules are implemented centrally.
- Foreground notification/type mapping, sticky restart, cross-process Service
  delivery, and device evidence remain open.

## Execution Update - 2026-07-10 Activity AMS Dispatcher Engine Gate

MultiApp now gates guest Context Activity launches through the engine-installed
AMS dispatcher wrapper before loader proxy-record allocation. The Activity /
AMS finding remains **HIGH / BLOCK** because this is route planning and
evidence only, not complete Android task/recents/result parity.

What changed:

- `VirtualActivityService` now exposes `planActivity(...)` and
  `recordActivityDispatch(...)`.
- Activity route planning is backed by `VirtualPackageSnapshot` through
  `VirtualPackageService`; explicit and implicit targets fail closed when the
  central engine snapshot cannot resolve them.
- Activity plan evidence records target Activity, action, target package,
  launch flags, process slot, supported operations, unsupported operations,
  and verdict.
- `DefaultEngineAmsComponentDispatcher.resolveStartActivityIntent(...)` now
  calls the engine Activity plan before loader fallback remap.
- `DefaultEngineAmsComponentDispatcher.resolveStartActivityIntents(...)` plans
  the batch first and blocks the whole batch before loader fallback if any
  member fails engine planning.
- Loader remap/block results are recorded as `activity/dispatch` evidence.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- This is not full `VActivityManager` / Android task manager parity. Loader
  primitives still allocate proxy records and proxy intents after engine
  gating.
- `launchMode`, `taskAffinity`, `onNewIntent`, result delivery, finish,
  recents/task behavior, and process-death recovery need broader behavior
  implementation and device proof.
- Activity support must remain `PARTIAL` until real device evidence proves
  separate same-origin instances in recents and correct task/result behavior.

## Execution Update - 2026-07-10 Activity Launch Task-State Sync

Successful guest Activity remaps now synchronize the hot loader Activity
records into the engine task-state store immediately. This narrows the window
where loader-local launch state and `VirtualActivityService.queryTaskState(...)`
disagree, but the Activity finding remains **HIGH / BLOCK**.

What changed:

- Added `VirtualActivityService.syncActivityTaskState(...)`.
- Sync validates runtime ownership, refuses to persist an empty instance task
  state, and stores the complete shared task snapshot through
  `EngineActivityTaskStateStore`.
- Single and batch Activity remaps call sync before dispatch evidence is
  finalized.
- Focused tests cover hot-record persistence and dispatcher invocation.

Verification:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- Loader still owns initial proxy-slot allocation and hot Activity record
  creation; engine currently validates the route and persists the result.
- This is not yet a VirtualApp-style server-owned ActivityStack allocator.
- Recents separation, task restoration after process death, launch-mode edge
  cases, and result delivery remain unproven on device.

## Execution Update - 2026-07-10 Multi-Instance Activity Task Merge

The persisted Activity task model now uses instance-level merge rather than
whole-snapshot replacement. This closes a concrete same-origin multi-instance
data-loss path, but the recents/task finding remains **HIGH / BLOCK** until
Android device behavior is proven.

What changed:

- `EngineActivityTaskStateStore.mergeInstance(...)` replaces only the target
  instance's Activity records and preserves sibling instances.
- File-backed state operations use a shared process monitor plus an OS file
  lock, so cooperating `:vN` processes do not concurrently rewrite the task
  file.
- Launch sync and Activity lifecycle/result/new-intent persistence now use the
  instance merge path.
- Regression tests prove instance A can be replaced without removing instance
  B when separate store objects write the same file.

Verification:

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- This protects engine task persistence; it does not prove Android recents or
  task-affinity behavior.
- Real simultaneous same-origin instances, process death, concurrent restart,
  and recents restoration still require device evidence.

## Execution Update - 2026-07-10 File-Backed Activity Hot Path Wiring

App startup no longer installs an in-memory Activity service beside the
file-backed engine service. AMS dispatch and Instrumentation operations now use
the same runtime/task files as `DefaultVirtualizationEngine`.

What changed:

- `EngineRuntimeInstallers` accepts `Context` and creates a file-backed
  `VirtualSystemServer` for the AMS dispatcher.
- Instrumentation Activity operations use the same `filesDir` runtime and task
  state paths.
- `MultiAppApplication` installs both paths with the application context.

Verification:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityTaskStateStoreTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- A shared file-backed control plane is not equivalent to VirtualApp-style
  Binder system-server ownership.
- Cross-process stale-cache behavior, concurrent launch ordering, and recents
  restoration still need device evidence.

## Execution Update - 2026-07-10 Engine-Owned Activity Launch Allocation

Initial Activity proxy allocation is now coordinated by `:core:engine` after
central route planning. This removes the previous normal-path split where the
engine approved a route but loader code independently chose the proxy slot and
created the launch record.

What changed:

- `EngineActivityLaunchCoordinator` validates `instanceId` and `processSlot`,
  allocates from the process-specific proxy pool, registers the initial virtual
  Activity record, and creates the proxy Intent.
- The engine AMS dispatcher uses this coordinator for single and batch starts.
- Loader allocation remains available only as an adapter fallback when the
  engine coordinator is absent.
- Engine dispatch evidence identifies whether an Activity was remapped by the
  engine coordinator or the legacy fallback.
- Focused tests prove engine-owned allocation and fail-closed process-slot
  mismatch behavior.

Verification:

```powershell
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.multiapp.app.EngineBoundaryTest" :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineActivityRuntimeTest" --tests "com.multiapp.core.engine.EngineContainerDispatchersTest" --tests "com.multiapp.core.engine.VirtualSystemServerTest" --no-daemon --console=plain --max-workers=1 "-Dkotlin.compiler.execution.strategy=in-process" "-Dkotlin.incremental=false" "-Pksp.incremental=false"
```

### Still BLOCK

- Batch mapping now restores records, guest Intent cache, and slot assignments
  after partial allocation failure. Framework-dispatch abort remains open.
- A file-backed process-local coordinator is not equivalent to VirtualApp's
  Binder-owned `VActivityManagerService` / ActivityStack.
- Recents separation, task restoration after process death, launch-mode edge
  cases, and result delivery remain unproven on real devices.

## Execution Update - 2026-07-10 Activity Mapping Transaction Rollback

The engine Activity allocator now restores its pre-operation state when a
single mapping or a later member of `startActivities(...)` fails.

What changed:

- Activity manager snapshots include tasks, records, launch result, and guest
  Intent cache.
- Proxy-slot assignment rollback uses compare-and-set so it cannot overwrite a
  different owner written after the snapshot.
- Batch failure blocks every member instead of exposing partially remapped
  Intents.
- Focused model/loader/engine tests passed.

### Still BLOCK

- Mapping rollback does not yet receive failure callbacks from the real Android
  `Context.startActivities(...)` dispatch.
- Device task/recents behavior remains unproven.

## Execution Update - 2026-07-10 Cross-Process Runtime State Hardening

The runtime state file is now protected against cooperating `:vN` process
races and stale runtime epochs.

What changed:

- Added OS file locking, fsync, and atomic file replacement.
- Added monotonic `runtimeEpoch` writes and epoch-conditional removal.
- Cached registries re-read durable state and observe external update/stop.
- Concurrent writers for different instances no longer replace each other's
  records.
- Runtime registry/server tests and app boundary compilation passed.

### Still BLOCK

- This is durable shared storage, not a Binder-owned engine server.
- Evidence aggregation and mutation ordering are not yet centralized in one
  authoritative process.
- Real process-death, restart, and same-origin multi-instance stress evidence
  is still missing.

## Execution Update - 2026-07-10 BinderProvider Engine Authority Foundation

The app now publishes a main-process `IEngineRuntimeService` Binder through a
private BinderProvider, following the same bootstrap category used by
VirtualApp rather than relying only on independently opened files.

What changed:

- Binder calls are restricted to the host UID and the provider is
  `exported=false` with no `android:process` override.
- Online clients can query runtime identity/epoch/slot/state, submit operation
  evidence, query evidence summary, and issue epoch-conditional stop.
- Activity, Provider, and Service binders fail closed when the connected engine
  authority reports a missing, dead, stale, or wrong-process runtime.
- IPC unavailable is explicitly distinguished from authoritative rejection;
  only the former may use durable recovery.
- AIDL/app compilation and focused authority, component-binder, boundary, and
  Manifest security tests passed.

### Still BLOCK

- This is the runtime authority foundation, not complete remote
  `VirtualPackageService` / `VirtualActivityService` / component-service
  ownership.
- Binder death handling, reconnect, full evidence transport, and real
  multi-process device proof remain open.

## Execution Update - 2026-07-10 Binder-Owned Activity Route Planning

Activity routing is no longer merely validated against the runtime authority
and then independently planned in each `:vN` process. The main-process Binder
service now owns Activity component resolution and route-plan evidence.

What changed:

- Activity request/plan/result contracts cross `IEngineRuntimeService`.
- Main-process `VirtualActivityService` resolves explicit and implicit targets
  from the authoritative package snapshot.
- Connected IPC parse/transport failure blocks launch rather than silently
  falling back to a second local decision source.
- IPC unavailable may use the locked durable-state fallback.
- Focused Activity IPC ownership and dispatcher tests passed.

### Still BLOCK

- Lifecycle/task/result mutation still executes in virtual clients and must be
  reconciled with server state.
- Provider, Service, and Broadcast policy ownership remains local until their
  Binder contracts are migrated.
- No device evidence yet proves Binder route ownership across all `:vN`
  processes.

## Execution Update - 2026-07-10 Binder-Owned Component Route Planning

Provider, Service, and Broadcast policy ownership has joined Activity in the
main-process engine Binder. Component target resolution now uses one
authoritative package snapshot instead of independent `:vN` decisions.

What changed:

- Provider authority/operation/process-slot plans and results cross Binder.
- Service start/foreground/stop/bind/unbind plans and results cross Binder.
- Broadcast explicit/implicit target plans and delivery results cross Binder.
- Connected malformed responses fail closed; unavailable IPC may use the
  locked durable fallback.
- Binder death clears stale proxies and reconnects through BinderProvider on
  the next operation.
- One engine-created server handle supplies all Binder subsystem endpoints.
- Focused component adapter/dispatcher and app security tests passed.

### Still BLOCK

- This centralizes route policy and evidence, not every component lifecycle
  mutation or Android framework callback.
- Ordered/sticky Broadcast, complete Service binding/foreground semantics, and
  Provider grants/observer semantics remain incomplete.
- Binder process-death recovery and cross-process ordering still require device
  evidence.

## Execution Update - 2026-07-10 URI Grant and AppOps Authority

The previous statement that no virtual URI grant/AppOps authority existed is
now partially superseded, but the overall review decision remains **BLOCK**.

Closed or mitigated in the current dirty tree:

- Provider URI grants are stored by owner instance, target instance,
  authority, encoded path, access mode, and prefix scope in a locked,
  atomic-replace store.
- Grant/revoke/check cross the engine Binder. Provider data-plane access trusts
  only a matching durable record, not a URI/extras claim.
- Guest `Context` grant/revoke/check/enforce entry points use the engine for
  guest authorities and preserve host delegation for external/system
  authorities.
- Explicit `checkOperation`, `checkOperationRaw`, and `checkAudioOperation`
  modes are persistent and instance-scoped. The loader intercepts only integer
  return types; otherwise it delegates after identity rewrite.
- Guest AppOps mutation methods are blocked, including mutation methods with no
  package argument.
- Focused recovery, isolation, fail-closed, dynamic-proxy, IPC ownership, and
  app-boundary tests pass.

Still BLOCK:

- Persisted URI take/release, external recipient grants, virtual permission
  ownership, and custom Provider processes are incomplete. Static path policy
  is implemented by the later Provider batch/path update.
- Modern AppOps note/start/finish semantics use richer AOSP return/callback
  types and are still `UNSUPPORTED`; this implementation deliberately does not
  fake them.
- There is no device evidence yet for process death, cross-process ordering,
  OEM hidden-API behavior, or the resulting QQ/WeChat/QQ Reader runtime paths.
- Existing LoadedApk/Application, Service/Broadcast, native namespace, split,
  and compatibility-matrix blockers remain unchanged.

## Execution Update - 2026-07-10 Provider Caller Gate and Content Runtime

The Provider path now distinguishes virtual caller identity from target
identity and has an API 29+ non-LSPlant data-plane entry.

What changed:

- Token validation treats URI fields as target routing only and retrieves the
  caller from the token registry. Forged caller/target reuse remains rejected.
- Original Provider Binder UID/PID, caller slot, target slot, engine Binder
  identity, access type, required permission, permission verdict, and AppOps
  verdict are aggregated in engine evidence.
- Known UID/PID/slot mismatch and non-exported cross-instance access fail.
  Protected cross-instance access is `UNSUPPORTED`, not falsely granted from
  requested manifest permissions.
- API 29+ guest Contexts receive an engine-created wrapped resolver. Guest
  authorities route through the engine; system/external authorities delegate
  to the host resolver.
- A process-wide `IContentService` proxy rewrites observer register/notify URIs
  to a stable instance route and records `PARTIAL` operation evidence. The
  compatibility hook no longer inserts short-lived tokens into observer URIs.
- Focused identity, loader, engine, app compile, and module-boundary tests pass.

### Still BLOCK

- Virtual URI grants and stable AppOps checks now have partial engine-owned
  authority, but persistable take/release, complete revoke scope, external
  non-virtual recipients, and modern AppOps note/start/finish remain incomplete.
- API 28 lacks the wrapped resolver path.
- Device evidence must prove `ContentResolver.sContentService` reflection and
  observer delivery on API 30-36/HyperOS, including descendant, self-change,
  flags, user routing, and process death.
- `applyBatch`, `refresh`, and static path policy are implemented by the later
  update below. Custom Provider processes and the complete provider-client
  lifecycle remain open.
- This is a generic Provider runtime improvement, not evidence that QQ, WeChat,
  QQ Reader, or GKD is commercially compatible.

## Execution Update - 2026-07-10 PMS Resource, Metadata, and Signing Truth

This tranche closes three static package-model defects without changing the
overall decision.

Closed locally:

- Base/public and split code/resource paths are no longer conflated in the
  hosted Activity/Context/Resources path.
- Provider general/read/write permissions are independently modeled from
  parser and binary manifest through install record, snapshot, VPMS, engine
  routing, and evidence.
- String-only manifest metadata was replaced by a typed pure-model value for
  Application and component metadata, with durable install/runtime-state
  persistence.
- Missing live manifest resolution now falls back to persisted components.
- Signer digest identity is persisted, survives metadata refresh and engine
  process restoration, and gates APK-derived `PackageInfo.signatures` and
  `PackageInfo.signingInfo` reconstruction.
- Focused model/manifest/loader/engine tests and app compile/boundary tests
  passed.

### Still BLOCK

- `checkSignatures`, `hasSigningCertificate`, shared UID/signature permission,
  signing lineage capabilities, and installer identity are not virtual-system
  services yet.
- Provider permission/AppOps enforcement and virtual caller ownership are not
  implemented; preserving fields is not authorization.
- URI grants/observers/notify, complete Service/Broadcast semantics,
  `isolatedSplits`, native namespace/load, and device process-death evidence
  remain open.
- API 28-36 device evidence is required for typed Bundle values and both
  signing query flags.

Decision remains: **BLOCK**.

## Execution Update - 2026-07-10 Durable Broadcast State and Semantic Gate

The previous Broadcast gate declared ordered/sticky behavior unsupported, but
several `Context` overloads discarded those flags before reaching the engine.
That bypass is now closed, and delivery outcomes have a durable server-owned
record.

What changed:

- Loader dispatch metadata now preserves ordered/sticky/result-receiver/abort,
  receiver permission/AppOps, AsUser, and platform-options semantics.
- Sticky removal and all AsUser overloads no longer enter the ordinary
  broadcast path silently.
- Main-process Broadcast dispatch persists an epoch- and process-slot-bound
  delivery record with atomic delivered/blocked/failed counters.
- `queryBroadcastRuntimeState` is Binder-owned, validates decoded record count,
  and rejects malformed connected responses.
- Broadcast runtime state is included in aggregate engine evidence.
- Unsupported ordered/sticky/result/permission/AppOps/user/options requests are
  blocked before loader receiver creation. No fake ordered result, sticky
  cache, permission check, or abort behavior is reported as compatibility.
- Focused loader, engine store/server/IPC/dispatcher, app compile, Manifest,
  and boundary tests passed.

### Still BLOCK

- A real virtual Broadcast service must own ordered result propagation,
  `PendingResult`/abort, sticky records, virtual-user routing, permission/AppOps
  checks, dynamic receiver process lifecycle, and cross-process callbacks.
- Normal manifest receiver delivery remains `PARTIAL` until device evidence
  proves process-slot routing, process death recovery, and ordering.
- This batch closes false-positive routing and adds durable truth; it does not
  make Broadcast commercially complete.

## Execution Update - 2026-07-10 Durable Provider Runtime State

Provider lifecycle truth now survives engine-server recreation and is queried
through the main-process Binder authority.

What changed:

- Successful provider dispatch updates a file-backed READY record keyed by
  `instanceId + guestAuthority`.
- Records include `runtimeEpoch`, process slot, provider class, cached state,
  last operation, and operation count; stale epochs are filtered.
- `queryProviderRuntimeState` is Binder-owned and rejects malformed connected
  snapshots.
- Provider runtime state is included in engine evidence.
- Unimplemented notify/observer/grant operations now fail closed as
  `UNSUPPORTED` and cannot reach loader dispatch.
- Focused store, server, IPC, and dispatcher tests passed.

### Still BLOCK

- URI grant ownership, observer registration/delivery, and `notifyChange`
  propagation need a real virtual `IContentService`/grant layer.
- Custom-process Provider lifecycle and device process-death recovery are not
  yet proven.

## Execution Update - 2026-07-10 Durable Service Runtime State

The Service route/evidence layer now persists engine-owned lifecycle records
after actual guest dispatch.

What changed:

- Main-process `recordServiceDispatch` updates a file-backed
  `EngineServiceRuntimeRecord` keyed by `instanceId + serviceClassName`.
- Records carry `runtimeEpoch`, and state queries filter out previous process
  generations after an instance is recreated.
- START/START_FOREGROUND records include process slot, active counts, cached
  status, foreground state, and `onStartCommand()` result.
- STOP transitions the existing record to `STOPPED` and clears start count.
- `queryServiceRuntimeState` is exposed over Binder and fails closed on an
  invalid connected response.
- Service runtime state is included in engine evidence.
- Focused state-store, server lifecycle, and Service IPC tests passed.

### Still BLOCK

- This is durable post-dispatch lifecycle truth, not complete Android Service
  callback parity.
- BIND/UNBIND, foreground notification/type ownership, sticky restart, and
  cross-process callback delivery remain incomplete/`UNSUPPORTED`.
- No device artifact yet proves ServiceRecord recovery after engine-server or
  virtual-process death.

## Execution Update - 2026-07-10 Binder-Owned Activity Lifecycle Mutation

Activity launch planning was already server-owned, but lifecycle and result
records could still diverge between virtual processes. Those mutations now
cross the engine Binder and execute against the main-process Activity service.

What changed:

- `mark-state`, `finish`, atomic `record-finish-result`, `set-result`,
  `mark-result-dispatch`, result consume, resume-fallback consume, and pending
  new-intent consume now have explicit AIDL operations.
- Activity records and nested result/intent payloads have typed Bundle codecs;
  malformed connected responses fail closed.
- Instrumentation and `ProxyActivityBase` lifecycle paths use the same
  IPC-backed service.
- Legitimate empty consume responses are distinguished from Binder
  unavailability, preventing stale local state from being consumed twice.
- `queryTaskState` is now served by the main-process Activity authority and
  rejects task/activity count mismatches during IPC decoding.
- `syncActivityTaskState` sends the live virtual-process snapshot through
  Binder; the server filters it by `instanceId` before durable merge, while
  unavailable Binder may use the locked local recovery path.
- `ContainerActivity` and `ProxyActivityBase` persistence now flows through
  `EngineActivityTaskController -> VirtualActivityService`; direct online
  task-file writes no longer bypass the authority.
- The manager-level fallback below the IPC service was removed, leaving one
  authority/fallback decision point.
- Core/app compilation and focused lifecycle, runtime, server, Manifest, and
  boundary tests passed.

### Still BLOCK

- Task snapshot admission and reads are now Binder-owned, with the locked file
  store retained as server persistence and unavailable-Binder recovery.
- Finish-result route lookup plus target-result mutation now executes as one
  server operation and has focused source/target persistence coverage.
- No device artifact yet proves process-death recovery and cross-process
  ordering for these lifecycle operations.
- Existing Provider/Service/Broadcast semantic gaps and native/split gates are
  unchanged.

## Execution Update - 2026-07-10 Binder-Owned Component Route Planning

Provider, Service, and Broadcast policy ownership has joined Activity in the
main-process engine Binder. Component target resolution is now package-snapshot
backed in one server process instead of independently decided by each `:vN`
client.

What changed:

- Provider authority/operation/process-slot plans and dispatch results cross
  Binder.
- Service start/foreground/stop/bind/unbind plans and results cross Binder.
- Broadcast explicit/implicit target plans and delivery results cross Binder.
- Connected malformed/failed IPC responses fail closed; only unavailable IPC
  can use durable fallback.
- Binder death clears the stale proxy and triggers BinderProvider reconnect on
  the next operation.
- Focused component adapter/dispatcher and app security tests passed.

### Still BLOCK

- This centralizes route policy and evidence, not every component lifecycle
  mutation or Android framework callback.
- Ordered/sticky Broadcast, complete Service binding/foreground semantics, and
  Provider grants/observer semantics remain incomplete as documented above.
- Binder process-death recovery and cross-process ordering still require device
  evidence.

## Execution Update - 2026-07-10 Global Provider Authority Routing

The API 29+ Provider data plane no longer assumes every guest authority belongs
to the calling package snapshot.

What changed:

- The main-process `VirtualProviderService` resolves an authority over all
  durable virtual runtimes.
- Self access is stable, a unique external owner can be selected, and duplicate
  clone owners are rejected unless a matching URI grant identifies the target
  instance.
- Resolution crosses Binder and malformed connected responses fail closed.
- System/non-virtual authorities preserve host delegation. A known virtual
  authority with no verified target cannot reach the host resolver.
- The selected owner is bound into the route token and is still checked by the
  target Provider plan, process-slot gate, permission/AppOps gate, and URI-grant
  gate.
- Focused server, IPC, resolver, dispatcher, and app-boundary tests passed.

### Still BLOCK

- Persistable grants, custom Provider processes, live authority-index
  invalidation, and device process-death evidence are incomplete. Batch
  atomicity and static path matching are implemented by the next update.
- This closes a generic cross-virtual-package routing defect. It is not device
  proof for QQ, WeChat, QQ Reader, or GKD.

## Execution Update - 2026-07-10 Provider Batch and Path Policy

The previous Provider review treated batch operations and every URI under one
authority too coarsely. The current dirty tree now has a narrower, fail-closed
model.

What changed:

- Every `applyBatch` operation is planned before execution. Mixed authorities,
  mixed clone owners, unresolved targets, or different Provider objects abort
  the batch before guest mutation; a valid batch reaches one guest
  `ContentProvider.applyBatch()` call.
- System/non-virtual batch and `refresh()` calls retain host delegation.
- Provider path permissions and URI grant patterns are pure model contracts and
  survive Manifest parsing, install-record persistence, runtime-state recovery,
  snapshot reconstruction, and guest `ProviderInfo` creation.
- Matching path permissions override authority-wide read/write permissions.
- With authority-wide grants disabled, only matching grant patterns can create
  a durable virtual URI grant.
- PackageManager fallback requests URI permission patterns and chooses the
  conservative interpretation when Android does not expose whether
  `grantUriPermissions` came from the Provider attribute or a child pattern.
- Focused model/Manifest/loader/engine/app tests and app compilation passed.

### Still BLOCK

- The engine has no complete virtual runtime permission grant service, so a
  protected cross-instance path remains `UNSUPPORTED` even though the required
  permission is now selected correctly.
- Persistable grants, external non-virtual recipients, custom Provider
  processes, glob parity across API/OEM implementations, and device
  process-death evidence remain incomplete.
- No device artifact from this batch proves compatibility for QQ, WeChat, QQ
  Reader, or GKD.

## Execution Update - 2026-07-10 Full Local Gate

The consolidated commercial-engine change set passed the full local gate:

- 1,816 tests across common/model/instance/manifest/identity/loader/hook/
  engine/app reported 0 failures, 0 errors, and 12 skipped.
- `:app:assembleDebug` completed in the same Gradle invocation.
- The rebuilt debug APK is 99,315,837 bytes with SHA-256
  `E7BEDDC21C8281C7221B26A0E77EE4A280FCB4263247F249ACD4884E7BC7F4D4`.
- `git diff --check` passed apart from line-ending warnings.

### Decision

Decision remains **BLOCK**. Local tests prove contract and regression coverage;
they do not prove API 30-36/HyperOS Binder behavior, process-death recovery,
multi-instance recents, native isolation, or QQ/WeChat/QQ Reader/GKD device
compatibility. The AGP 8.7.3 versus compileSdk 36 warning also remains open.

## Execution Update - 2026-07-10 Persistable URI Grant Control Plane

The earlier statement that persistable take/release and live authority-index
invalidation were entirely missing is now partially superseded.

What changed:

- URI grant storage now separates transient modes, persistable eligibility,
  and modes actually persisted by the target instance.
- Target-owned take/release operations cross the main-process engine Binder and
  validate authoritative caller UID/PID, instance, authority, path, mode, and
  duplicate virtual owners.
- Owner revoke clears both transient and persisted access; target release
  clears only persisted access.
- API 29+ `ContentResolver` take/release calls are intercepted at the AOSP
  `uri_grants` Binder boundary. Only virtual authorities are consumed; system
  authorities delegate to Android and denied virtual calls fail closed.
- External virtual authorities are recognized by the URI permission dispatcher
  using live runtime snapshots.
- Non-self Provider authority lookup now calls the engine service on every
  request instead of relying on a first-use process-local authority cache.
- Focused store/server/IPC/dispatcher/resolver/Binder-proxy/stage/Context tests
  passed.

### Still BLOCK

- Persisted incoming/outgoing `UriPermission` list objects are not virtualized.
- API 28's ActivityManager URI-grant path, OEM hidden-API behavior, process
  death, and reboot-like restore have no device evidence.
- External non-virtual recipients, virtual manifest-permission ownership,
  custom Provider processes, and ContentObserver/notify semantics remain open.
- This is generic Provider infrastructure, not compatibility proof for QQ,
  WeChat, QQ Reader, or GKD. Decision remains **BLOCK**.

## Execution Update - 2026-07-10 Launch Ordering and Permission Authority

Two earlier findings are partially superseded in the current dirty tree:

- Guest Application creation through `LoadedApk.makeApplication`, runtime
  publication, same-process Provider preinstall, and Instrumentation-driven
  `Application.onCreate()` are now serialized on the Android main Looper.
  Background prewarm threads no longer own permanent custom Loopers.
- A file-backed, per-instance virtual permission service now owns PMS and
  Provider permission decisions. Manifest declaration alone no longer grants
  a permission, and connected malformed Binder responses fail closed.
- First launch mirrors the installed source package's current Android grant
  state only when the clone has no existing decision. Later explicit clone
  decisions are preserved.
- Launcher work is off the UI dispatcher, duplicate launch requests are
  coalesced, global slot collisions are repaired, and the baseline profile no
  longer enables the legacy LSPlant Provider hook.
- Focused launcher, slot, Application, engine, permission, PMS, Provider, and
  IPC tests passed.

### Still BLOCK

- Main-thread behavior, ActivityThread hidden APIs, ANR avoidance, and Provider
  install ordering have no current device proof.
- Runtime permission request/result UI, flags/groups, one-time grants,
  auto-reset, shared UID, AppOps coupling, and user-facing clone permission
  controls remain incomplete.
- Source-package permission mirroring is a conservative bootstrap policy, not
  complete virtual PackageManager permission semantics.

## Execution Update - 2026-07-10 Same-Process Service Bind and Unbind

The earlier blanket `bind-service`/`unbind-service` unsupported statement is
partially superseded.

What changed:

- The engine admits same-guest-process `BIND` and connection-based `UNBIND`,
  then records loader lifecycle results in Binder-owned evidence and durable
  Service state.
- Durable Service state can represent a bound-only `BOUND` record and retains
  active start/bind counts across state-store recreation.
- Service route IPC carries `sameProcess`; custom/remote guest process routes
  fail closed before loader dispatch.
- Focused engine plan, AMS dispatcher, lifecycle-state, and persistence tests
  passed.

### Still BLOCK

- Cross-process Service hosting and callbacks, binder-death rebind, foreground
  service types, sticky restart, process-death ordering, and device evidence
  remain incomplete.
- This closes a same-process control-plane gap only. It is not full Service
  parity or compatibility proof for QQ, WeChat, QQ Reader, or GKD. Decision
  remains **BLOCK**.

## Device Finding - 2026-07-11 GKD Application Attach ANR

Artifact: `.tmp/manual-after-user-20260711-082728`.

The latest GKD failure is now attributed to a general framework-contract bug:

- The hosted process reached `LoadedApk.makeApplication` but never returned
  from `Application.attach()`.
- MIUI's repeated stack samples remained at `ContextImpl.getImpl()` through
  `VirtualInstrumentation.newApplication()` for over 45 seconds.
- `VirtualContextWrapper.getBaseContext()` returned itself, while AOSP
  `ContextImpl.getImpl()` repeatedly follows `ContextWrapper.getBaseContext()`.
  This produced an infinite unwrap loop and an input ANR, not a slow GKD
  `Application.onCreate()`.

The current dirty tree removes the self-cycle and adopts the VirtualApp-style
identity split: the framework-created guest ContextImpl/LoadedApk is retained,
guest-visible identity is the origin package, and only ContextImpl /
ContentResolver caller fields are rewritten to the host package for Android
Binder validation. Focused loader tests pass.

### Still BLOCK

- No post-fix device artifact yet proves that GKD passes
  `MAKE_APPLICATION_FINISHED` and `Application.onCreate()`.
- The process-bootstrap readiness handshake is now implemented locally; a
  device artifact must still prove that it precedes foreground proxy launch
  and prevents an initialization stall from becoming an input ANR.
- This finding does not establish QQ, WeChat, or QQ Reader compatibility.

## Execution Update - 2026-07-11 Startup and System-Service Identity

The post-fix device capture `.tmp/gkd-clipboard-current-20260711-144555`
supersedes the statement that no post-fix GKD Application evidence exists:

- Two GKD clones reached guest `onResume` in separate `:v1` and `:v3`
  processes with `loadedApkApplicationCreatorStatus=PASS`.
- Guest Application creation completed in 103 ms and 81 ms, and the capture
  contains no new Java fatal or ANR.
- Runtime-state snapshot caching and minimal baseline evidence remove repeated
  full-file parsing and synchronous storage/Provider diagnostics from the
  foreground launch path.
- LauncherApps and Clipboard now have guest-to-host caller identity proxies at
  both manager and ServiceManager Binder layers. Clipboard package argument
  positions are explicit per method for API 30-36 rather than using a broad
  first/last-String rewrite.
- Current source reports manager and ServiceManager Clipboard injection
  separately and degrades one-sided installation to `PARTIAL`.

### Still BLOCK

- GKD Provider preinstall remains `PARTIAL`; `androidx-startup` failed because
  a `VirtualContextWrapperApi36` was cast to `Application`.
- Runtime permission request/result UX is not implemented, and AstroBox still
  has an unresolved black-screen device result.
- The local process-bootstrap readiness implementation is not yet device-
  proven to prevent a proxy Activity from becoming foreground before guest
  readiness.
- Clipboard install evidence is not yet a device proof of the actual copy
  operation, and the stricter split evidence fields require a new device run.
- Activity process-death recovery, full Service/Broadcast semantics,
  custom-process Providers, native/linker coverage, and the compatibility
  matrix remain open. Decision remains **BLOCK**.

## Implementation Update - 2026-07-11 Process Bootstrap Readiness

The previously missing process-bootstrap readiness gate is implemented in the
current tree and passes the full local unit/build gate.

Current mitigation:

- Engine activation uses private slot Providers in `:v0..:v7`; foreground
  proxy launch occurs only after a bounded READY response.
- READY is bound to instance, epoch, engine session, process slot, PID, and a
  live Binder token. Guest ClassLoader without a guest Application is not
  reusable READY state.
- Durable transitions use whole-record compare-and-set. Same-epoch foreign
  sessions, stale READY responses, and old Binder death callbacks fail closed.
- Process-local initialization distinguishes provisional `BINDING` from
  complete `READY` and applies a deadline to ordinary waiters.
- Central runtime state remains `PREWARMED` after `startActivity`; only the
  real guest Application lifecycle callback after guest Activity `onResume`
  can acknowledge `RUNNING`, with epoch/session/process/PID validation.
- The engine directly launches the assigned proxy after READY, and launcher
  Intent recovery preserves guest component identity across the process
  boundary.

Local evidence:

- Full module tests plus `:app:assembleDebug` passed in 4m49s.
- APK SHA-256:
  `FFC58BB4D81A631D788C52E34763C2E0A97DFDFC26DBD923C6A9F10620A5F994`.

### Still BLOCK

- The mitigation is not device-proven. Required evidence is target `:vN`
  READY, linked process token, no foreground `ContainerActivity`, successful
  proxy substitution, and a central `PREWARMED -> RUNNING` resume ACK.
- Timeout currently fails closed but does not explicitly kill/recycle the
  half-initialized process slot.
- Runtime permissions, process-death recents, custom-process Providers,
  Service/Broadcast fidelity, native linker/load, and the commercial device
  matrix remain incomplete.
- This update does not establish compatibility for GKD, AstroBox, QQ, WeChat,
  or QQ Reader. Decision remains **BLOCK**.

## Review Update - 2026-07-13 Launch Authority and Process Generation

The process-bootstrap implementation has been tightened after a source-level
comparison with VirtualApp `7d739c85`, BlackBox `ffe950f7`, and DroidPlugin
`c6ebf652`. Their common structural rule is retained: a central manager owns
process/component authority, clients prove liveness over Binder, and proxy
components do not invent a second local recovery truth.

### Findings closed in the current tree

- Foreground launch is now capability-bound to the current runtime epoch,
  engine session, process slot, PID, proxy class, and guest class.
- Process Binder death is generation-aware. Binder link/unlink calls no longer
  execute under the registry lock, old callbacks are inert after replacement,
  and a synchronous death cannot publish READY.
- Engine authority restart marks persisted live-looking process states `DEAD`.
  IPC loss no longer falls back to a durable record for live authorization.
- Unsubstituted proxies fail closed. The previous app-side loader prewarm and
  relaunch route has been removed.
- Runtime reuse requires an exact package/artifact/data/profile/process
  fingerprint and identical Application/ClassLoader objects.
- A reflective Application fallback cannot satisfy READY, and app/feature main
  sources now have a build-time engine-boundary import gate.

### Verification

- Full configured local gate passed in 2m37s: 502 Gradle tasks, 1,768 tests,
  0 failures, 0 errors, and 12 skipped tests.
- Debug APK SHA-256:
  `D04CBB326C1854C696665B1FC667EC042FFA6FDDD64F7ECC0349CDBEAED7F879`.
- `git diff --check` passed; only existing LF/CRLF conversion warnings remain.

### Still BLOCK

- The new capability/PID/death flow has no end-to-end device evidence. Local
  tests cannot prove Android/OEM ActivityThread launch ordering or Binder death
  timing.
- There is no formal client reattach/restart protocol and no fresh capability
  for recents restoration after process death. The current engine authority is
  centralized through `EngineBinderProvider` but still lives in the default
  app process.
- Complete `ActivityThread.mBoundApplication` / `mInitialApplication`
  equivalence and per-guest-`processName` process records are missing.
- Runtime permissions, custom-process components, complete Service/Broadcast
  semantics, native linker/load, and the commercial device matrix remain open.
- GKD, AstroBox, QQ, WeChat, and QQ Reader remain unproven. Review decision
  remains **BLOCK**.

## Review Update - 2026-07-13 Client Reattach and Framework Application Binding

The previously missing formal client reattach protocol is partially
superseded in the current tree. Source comparison remains pinned to VirtualApp
`7d739c85`, BlackBox `ffe950f7`, and DroidPlugin `c6ebf652`.

### Findings closed in the current tree

- Engine AIDL now exposes `attachClient`, `processRestarted`, and a separate
  recents restore-capability operation. Live authority requires an exact
  generation plus Binder token; persisted state is only a recovery input.
- DEAD-runtime restart atomically allocates a new epoch and sessions. Pending
  death registrations serialize concurrent restart attempts, and stale or
  repeated generations are rejected.
- Caller PID and `/proc/<pid>/cmdline` are checked against processSlot before
  process authority is granted.
- Recents restoration issues a fresh capability from a persisted virtual
  Activity record and never reuses a persisted Android system Activity token.
- Guest launch-record patching cannot occur before capability authorization,
  guest ClassLoader readiness, and guest LoadedApk readiness.
- LoadedApk/Application installation now includes ActivityThread bound/initial
  Application references and rollback. Reflective production fallback is
  removed; default Application classes also require the framework
  `makeApplication` path.

### Verification

- Full local gate passed in 5m21s: 515 Gradle tasks, 1,941 tests, 0 failures,
  0 errors, and 12 skipped.
- Debug APK SHA-256:
  `CF039FA8F068B07697723D931C317BC4B09A9AF9101E7864F655762F4D0F80BA`.
- `git diff --check` passed apart from existing line-ending warnings.

### Still BLOCK

- This is the server/control-plane half of recents recovery. The Android
  ActivityThread launch-message callback does not yet perform restart,
  bindApplication, PREWARMED publication, fresh capability issuance, and
  same-message guest patching as one transaction.
- The engine authority is not yet isolated in a dedicated server process, and
  per-guest-`processName` runtime records remain incomplete.
- OEM ActivityThread/AppBindData layouts, custom-process Provider/Service,
  runtime permissions, complete Service/Broadcast semantics, native linker/
  load, and device process-death evidence remain open.
- This local gate is not compatibility proof for GKD, AstroBox, QQ, WeChat, or
  QQ Reader. Review decision remains **BLOCK**.

## Review Update - 2026-07-13 ActivityThread Recents Recovery Wiring

The earlier statement that the client reattach protocol was not connected to
the Android launch message is superseded in the current tree.

### Findings closed in the current tree

- The ActivityThread callback can recover a stale/missing launch capability
  before guest record patching. Recovery is synchronous and modifies the same
  `ActivityClientRecord`/`LaunchActivityItem`; it does not enqueue a copied old
  message as VirtualApp/BlackBox do.
- Recovery obtains the current DEAD generation from engine IPC, attaches the
  new process Binder token, binds guest LoadedApk/Application locally, promotes
  PREWARMED through a live-authority AIDL operation, registers resume ACK, and
  requests a fresh capability from the persisted virtual Activity record.
- The returned engine record controls restored proxy/guest identity. A forged
  origin package or process slot leaves the proxy launch untouched.
- PREWARMED promotion is PID/processName/processSlot/generation-gated and
  idempotent. No durable snapshot can promote itself without live Binder
  authority.
- Focused tests cover same-message capability replacement, ordered
  query/restart/bind/prewarm/capability execution, wrong-process rejection, and
  repeated PREWARMED promotion.

### Verification

- Full local gate passed in 3m19s: 515 Gradle tasks, 1,945 tests, 0 failures,
  0 errors, and 12 skipped.
- Debug APK SHA-256:
  `C6FFD5B24159DE85AA4A558F06736D0FE762235A2DA02375593495E0BC6F15EE`.
- Open-source mapping is documented in
  `docs/reviews/2026-07-09-open-source-engine-comparison.md`.

### Still BLOCK

- There is no device artifact proving this path against real Android recents,
  process death, OEM transaction layouts, or final RUNNING acknowledgement.
- Owner-thread Application reconstruction has no independent timeout watchdog;
  a non-returning guest attach/onCreate can still stall launch until the system
  kills the process.
- Engine authority still shares the default app process. Per-guest-process
  records, runtime permissions, custom-process components, complete Service/
  Broadcast semantics, native linker/load, and the API 28-36/HyperOS matrix
  remain incomplete.
- This does not prove GKD, AstroBox, QQ, WeChat, or QQ Reader compatibility.
  Review decision remains **BLOCK**.

## Review Update - 2026-07-13 Recents Watchdog and Generation Recycle

The prior finding that owner-thread Application reconstruction had no
independent watchdog is superseded in the current tree.

### Finding mitigated locally

- A 45-second watchdog now owns the recovery race through one atomic
  `PENDING/COMPLETED/TIMED_OUT` state. Timeout and normal completion cannot
  both succeed.
- Timeout calls the engine's new `abandonProcessClient` control-plane method
  for the exact PID/processSlot/runtimeEpoch/engineSession generation. Accepted
  abandon marks the runtime `DEAD`, removes the death registration, and revokes
  launch capabilities for that generation.
- The Binder abandon call cannot indefinitely block recycling: it runs outside
  the ActivityThread owner thread, is awaited for at most 500 ms, and exact
  guest-PID termination proceeds regardless. A cached daemon executor prevents
  one stuck abandon transaction from starving later recycle attempts.
- Only recognized guest slots `:v0..:v7` can enter the termination path. The
  default host process, wrong process name, wrong PID, stale generation, and
  wrong slot fail closed.
- Unit tests cover exact-PID termination, host-process rejection, a blocking
  abandon transport, idempotent DEAD abandon, and capability revocation.

### Verification

- Final full local gate passed in 2m34s: 515 Gradle tasks, 1,949 tests, 0 failures,
  0 errors, and 12 skipped.
- Debug APK SHA-256:
  `83E2D0A512B16EAB7B18397122F267431C4F614CDC925B4B351ABBDAE51CB323`.

### Still BLOCK

- This is a safety/recycle timeout, not proof that the synchronous owner-thread
  bind completes before Android or an OEM reports an ANR. Real device evidence
  must determine whether prewarming must move earlier or recovery policy must
  be further staged.
- No artifact yet proves timeout -> DEAD -> process death -> successor epoch ->
  PREWARMED -> RUNNING against system recents on API 28-36/HyperOS.
- Dedicated engine-server isolation, one-instance/multiple-process records,
  runtime permissions, custom-process Provider/Service, full Service/Broadcast
  semantics, native linker/load, and the compatibility matrix remain open.
- GKD, AstroBox, QQ, WeChat, and QQ Reader remain unproven. Review decision
  remains **BLOCK**.

## Review Update - 2026-07-13 Central Owner Preparation

The owner graph and high-level engine facade have been reviewed against the
fixed VirtualApp, BlackBox, and DroidPlugin references. The safe migration
order is now enforced locally: first make capability generation, process death,
component services, and the public engine API share one owner; then remove
client-side authority; only then move the Binder Provider to a dedicated
process.

### Findings closed in the current tree

- `EngineServerRuntime` owns one registry/system-server/capability/death/
  control-plane/engine graph. `DefaultVirtualizationEngine` and
  `EngineBinderProvider` consume that graph instead of constructing competing
  copies.
- The six public `VirtualizationEngine` operations are available through AIDL
  and a strict `IpcVirtualizationEngine` facade. Production app DI no longer
  binds the local engine implementation.
- Mutation calls do not retry an unknown Binder result. Runtime-bearing replies
  are accepted only when their generation identity matches the read-only
  persisted snapshot.
- Binder reconnect state is generation-bound. Old or synchronous death
  callbacks cannot clear or publish a different live connection, and concurrent
  reconnect callers share one linked Binder.
- Contract tests cover all six endpoints, malformed requests, unavailable
  owner, missing runtime, wrong UID, complete evidence, owner identity, and app
  DI boundaries.

### Verification

- Full local gate passed in 3m03s: 515 Gradle tasks, 1,970 tests, 0 failures,
  0 errors, and 12 skipped.
- Debug APK SHA-256:
  `2379DA191DEED50D19DEDACE2131B194BC7BD146DA214C2741A931FDD9863F11`.
- Oracle JDK 17 `Future.cancel` and `ThreadPoolExecutor.prestartCoreThread`
  semantics were used to remove a test-only executor-start race without
  changing production watchdog deadlines.

### Still BLOCK

- The Provider intentionally remains in the host main process. This batch is a
  prerequisite for dedicated `:engine` isolation, not that migration itself.
- `EngineRuntimeInstallers.fileBackedSystemServer()` and several component,
  ContentResolver, URI-grant, permission, AppOps, and evidence paths can still
  create local mutable authority. Binder failure can therefore still become
  split-brain instead of a fail-closed result.
- UI/use-case instance create/delete and proxy-slot mutation are not yet owned
  by the engine server. Shared files and process-local synchronization do not
  provide single-writer semantics.
- `VirtualProcessRuntime.global` and `VirtualActivityRecordManager.global` may
  remain process-local mirrors, but they must not be used as a replacement
  system-service authority.
- Required next gate: remove mutable local fallback, add authoritative
  read/command APIs, enforce one writer, add process-role startup, then migrate
  the Provider to `:engine` in one change with server-death and device tests.
- No current device artifact proves the new owner/IPC path. Review decision
  remains **BLOCK**.

## Review Update - 2026-07-13 Fail-Closed IPC and Instance Deletion

The central-owner preparation finding has been partially closed. Covered IPC
facades no longer turn an unavailable engine Binder into local mutable
authority, and permanent deletion is now an engine command rather than a UI
record/dataRoot operation.

### Findings closed in the current tree

- Activity, Provider, Service, Broadcast, Permission, and AppOps facade
  mutations have zero `fallback.*`/`fallback::` production calls. Null,
  exception, malformed identity, and unavailable authority paths fail closed.
- Runtime/task fallback is read-only and explicitly injected; a successful
  local snapshot cannot claim more than `PARTIAL`.
- `deleteInstance` is covered by the public engine contract, AIDL endpoint,
  IPC facade, launcher, app manager, and clone-creation rollback.
- A delete cannot proceed past an active guest process until exact process-slot
  termination is confirmed. Host PID, PID mismatch, bad `:vN` slot,
  unverified `/proc` identity, interruption, and timeout are rejected.
- Target-instance task/activity, Provider, Service, Broadcast, URI grant,
  permission, and AppOps state is cleared without deleting sibling state.
  Task deletion count is instance-specific rather than a global before/after
  difference.
- dataRoot deletion failure retains the instance record and both persistent
  slot assignments. Package-scoped install artifacts remain available to
  sibling clones and failed rollback retries.

### Verification

- Full local gate passed in 7m55s.
- UTF-8 JUnit aggregation: 2,020 tests, 0 failures, 0 errors, 12 skipped.
- Debug APK SHA-256:
  `EE67E2FAD2BD77B5924D1D8A7D399653EE1548FB1AFE83AEAF33234743271A37`.
- `git diff --check` passed apart from existing line-ending warnings.

### Still BLOCK

- Instance creation still bypasses the engine command because the current
  create API cannot carry the imported package metadata and requested display
  name. This must be solved before moving the owner to another process.
- Proxy Activity slot allocation still has app/loader/guest file-backed
  writers. Central deletion cleanup is not proof of single-writer allocation.
- File-backed local server construction and remaining direct dispatcher reads
  must be removed or converted to immutable snapshots delivered by the engine.
- The Provider has not moved to `:engine`; server-death/reconnect, process-role
  startup, and device evidence are absent.
- PMS/AMS/Provider/Service/Broadcast/runtime-permission/native completeness and
  the GKD/AstroBox/QQ/WeChat/QQ Reader matrix remain unresolved. Review
  decision remains **BLOCK**.

## Review Update - 2026-07-13 Authoritative Create and Package Generation

The prior finding that product creation bypassed the engine is closed for the
current production path. The review also found and closed three related
integrity defects before accepting the batch: incomplete request-id matching,
split artifact filename collisions, and APK/package identity fallback.

### Findings closed in the current tree

- Create metadata and the mandatory `creationRequestId` cross the engine Binder
  boundary. The old package-name-only endpoint is explicitly unsupported.
- The persisted instance record contains both request ID and a full payload
  fingerprint. Same-ID/different-payload retries fail; same-ID/same-payload
  retries return the original instance after an unknown Binder result.
- Launcher state preserves a pending attempt across page/process recreation
  without locally creating an instance or retrying through another authority.
- Sibling create does not refresh or recopy matching shared package artifacts.
  Generation mismatch is an explicit refresh-required failure.
- Archive and manifest package names are validated against the requested
  package. Metadata resolver failures propagate instead of silently falling
  back to installed-package metadata.
- Base/split imports use content-addressed targets and staging. Sanitized split
  name collisions cannot overwrite one another, final files are re-hashed, and
  covered failures roll back new artifacts while preserving the old record and
  generation.
- Instance deletion rejects a persisted `dataRoot` outside the canonical
  instance root and restores staged data when record deletion is rejected.

### Verification

- Full local gate passed in 4m34s.
- UTF-8 JUnit aggregation: 2,054 tests, 0 failures, 0 errors, 12 skipped.
- Debug APK SHA-256:
  `5102D551B4E9A25E6D9B48FA01681489929C82624D9B7A689076245E3C4BDDDA`.
- Static search confirms no product clone-create call to
  `InstanceManager.createInstance`.

### Still BLOCK

- Proxy Activity allocation is still multi-writer. Eight production
  `FileBackedProxyActivitySlotAssignmentStore` constructions remain across
  app/engine/loader paths; process-local locks do not serialize `:vN` writers.
- Package staging does not yet have crash-time journal reconciliation. The
  current guarantees cover normal completion and caught failure, not power or
  process loss at every file/record boundary.
- The Binder Provider remains in the host process, direct owner-file reads and
  local server construction remain, and commercial component/native semantics
  plus device evidence are incomplete.
- This batch is infrastructure proof only. GKD, AstroBox, QQ, WeChat, and QQ
  Reader compatibility remain unproven; review decision remains **BLOCK**.
