# MultiApp Open-Source Engine Comparison - 2026-07-09

## Decision

MultiApp should continue on a VirtualApp / BlackBox-style user-space virtual
Android system route. It should not pivot to a RePlugin-style cooperative host
plugin framework.

The practical target is:

```text
:app / :feature:* -> :core:engine -> virtual PMS/AMS/Provider/Service/Broadcast/Storage/Native
```

Hook/LSPlant/Xposed should remain as profile-gated compatibility adapters, not
the baseline engine.

## Source Comparison

| Project | What it proves | What MultiApp should borrow | What not to copy as-is |
|---|---|---|---|
| VirtualApp | A host can run apps in a virtual space without root by mediating framework calls, package identity, components, storage, native IO, and process state. | Virtual system server shape, virtual package/service managers, stub components, multi-user/multi-instance thinking, IO redirect, hook profile model. | Do not copy code or claims. Public repo is old and licensing/commercial status is sensitive; use it as architecture precedent only. |
| DroidPlugin | A host can run third-party APKs without installing/repacking and make plugin packages look installed to host/plugin callers. | PMS/AMS hook patterns, Stub Activity/Service/Provider pools, component dispatch, resource separation, static broadcast handling ideas. | Native-heavy and protected-app compatibility is weak by its own README limitations; not enough for QQ/WeChat-class apps. |
| VirtualXposed | A VirtualApp-derived space can load Xposed modules without root. | Profile-gated hook/module loading and diagnostics-only paths. | It is not a container kernel; it depends on the virtual space model. |
| RePlugin | A production plugin framework can be stable with very low hook usage and clear plugin management. | Engineering discipline, low-hook preference, plugin lifecycle/resource management, task/multi-process lessons. | RePlugin assumes cooperative plugins and host/plugin protocol; it does not solve arbitrary installed APK cloning by itself. |

References:

- VirtualApp: https://github.com/asLody/VirtualApp
- DroidPlugin: https://github.com/DroidPluginTeam/DroidPlugin
- VirtualXposed: https://github.com/android-hacker/VirtualXposed
- RePlugin: https://github.com/Qihoo360/RePlugin

## MultiApp Gap Map

| Engine area | Current MultiApp direction | Open-source benchmark | Current gap |
|---|---|---|---|
| Package service | `VirtualPackageService` now has snapshot identity and component resolver foundation. | VirtualApp-style virtual PMS / DroidPlugin plugin package manager. | Still lacks full `PackageInfo`, `ApplicationInfo`, `ProviderInfo`, `ResolveInfo`, signing, permissions, disabled component state, and Android-grade `IntentFilter.match()`. |
| Activity service | Engine allocates proxy/process slots and records runtime state. | VirtualApp/DroidPlugin Activity Manager + stub pool + task stack. | Back stack, result, `onNewIntent`, recents restoration, launchMode fidelity, and process-death task rebuild are not complete. |
| Provider service | Route-token gate and same-process preinstall foundation exist. | VirtualApp provider install before Application and routed provider calls. | URI grant, observer/notify, custom process providers, read/write permission, and all operations still need device evidence. |
| Service service | Stub dispatch exists and some return semantics are improved. | DroidPlugin/VirtualApp service lifecycle virtualization. | bind/unbind, foreground service type, sticky restart, process death, and start result fidelity remain incomplete. |
| Broadcast service | Runtime records/evidence exist. | Static-as-dynamic handling and ordered/sticky/result semantics. | Ordered/sticky/result/abort/permission and cross-process routing remain open. |
| Storage/native | Scoped private redirect and containment checks exist. | VirtualApp native IO redirect. | Real syscall family coverage, namespace/nativeLoad/RegisterNatives evidence, split native libs, and protected-app diagnostics remain incomplete. |
| Hook profile | `BASELINE` vs `COMPAT_HOOK`/diagnostics model exists. | VirtualApp hook SDK / VirtualXposed module loading. | Enforcement must keep fake/no-op/proc spoof out of baseline and require package+instance profile allow-list. |
| Engineering boundary | `:core:engine` exists and app boundary test freezes new direct runtime imports. | RePlugin-style clear host/plugin boundary plus VA server/client split. | Existing app/container direct loader/hook imports are still migration debt. |

## Execution Order

1. Keep hardening `VirtualPackageService` until all package/component queries
   used by Activity/Provider/Service/Broadcast dispatch come from engine.
2. Migrate app/container dispatchers behind engine facades; keep Android stub
   components in app but move runtime decisions out.
3. Add persistent task/process state for Activity recents and process-death
   recovery.
4. Complete Provider/Service/Broadcast semantics behind engine services.
5. Expand native/split/multidex support and device evidence.
6. Only then evaluate QQ/WeChat/QQ Reader as compat-profile samples, not as the
   baseline success definition.

## Current Verdict

The latest work moves MultiApp closer to the VirtualApp/DroidPlugin family by
centralizing runtime/package facts in `:core:engine`. The project remains
`BLOCK` for commercial readiness until the engine owns PMS/AMS/Provider/
Service/Broadcast/Storage/Native behavior end to end and device evidence proves
real launches across the compatibility matrix.

## Process-Death Recents Comparison - 2026-07-13

Pinned source review:

- VirtualApp `7d739c85`,
  `VirtualApp/lib/src/main/java/com/lody/virtual/client/hook/proxies/am/HCallbackStub.java`:
  a cold launch first calls `VActivityManager.processRestarted()`, then
  `VClientImpl.bindApplication()`, and only after binding replaces the
  `ActivityClientRecord` intent/info. Its implementation requeues the same
  message at the front of `ActivityThread.H` between stages.
- BlackBox `ffe950f7`,
  `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/HCallbackProxy.java`:
  both pre-P `LAUNCH_ACTIVITY` and P+ `EXECUTE_TRANSACTION` paths recover the
  process and bind Application before replacing `LaunchActivityItem` fields.
  It also requeues the message while recovery is incomplete.
- DroidPlugin `c6ebf652`,
  `project/Libraries/DroidPlugin/src/main/java/com/morgoo/droidplugin/core/PluginProcessManager.java`:
  provides useful LoadedApk/Application preload and ActivityThread package-map
  techniques, but does not provide the same durable virtual process-generation
  authority. It is not sufficient as the recents recovery control plane.

MultiApp decision:

- Keep the common ordering: central process restart authority, local
  LoadedApk/Application bind, PREWARMED publication, fresh Activity capability,
  then guest launch-record patch.
- Do not copy VirtualApp/BlackBox message requeue behavior. MultiApp performs a
  synchronous recovery inside the current ActivityThread callback and
  patches the same launch item only after all stages pass. This avoids replaying
  an old capability or racing another callback invocation.
- The old proxy Intent contributes only `instanceId`, process slot, and the
  persisted virtual Activity id. Engine runtime state and the persisted virtual
  Activity record determine the new generation, proxy class, and guest class.
  A fresh capability is always issued; Android system Activity tokens are not
  persisted or reused.
- Recovery remains fail-closed. Missing Binder authority, process-name
  mismatch, failed `bindApplication`, PREWARMED rejection, missing task record,
  or capability mismatch leaves the proxy record untouched.

Current implementation mapping:

- `VirtualActivityLaunchRecovery`: loader-owned synchronous pre-attach seam.
- `EngineGuestActivityLaunchBridge`: engine-to-loader recovery adapter.
- `EngineGuestRecentsRecoveryCoordinator`: app-side Android process carrier
  that invokes only engine APIs and `HostedRuntimeEngine`.
- `EngineProcessControlPlane.markPrewarmed`: live Binder/PID/processSlot-gated
  CREATED-to-PREWARMED transition.
- `ActivityThreadLaunchRecordPatcher`: writes the fresh identity into the same
  launch Intent and then performs normal capability/LoadedApk validation.

This closes the local code path, not the commercial gate. API 28-36 and HyperOS
device evidence must still prove actual process death, system recents relaunch,
Application reconstruction, no black screen, and final RUNNING acknowledgement.
An owner-thread bootstrap watchdog/recycle path is also still required so a
guest Application that never returns cannot hold the launch callback forever.

## Recents Stall Recycle Comparison - 2026-07-13

The pinned VirtualApp and BlackBox launch callbacks establish the required
ordering around process restart, Application bind, and launch-record patching,
but their message-requeue pattern is not itself a durable timeout authority.
DroidPlugin's preload path likewise does not provide a generation-scoped
virtual-process abandon protocol.

MultiApp decision:

- Keep the open-source ordering and the same-message fail-closed recovery
  decision already documented above.
- Add an engine-owned abandon operation instead of treating `killProcess()` as
  runtime truth. The server first validates the live Binder generation, marks
  it DEAD, and revokes that generation's Activity capabilities.
- Treat PID termination as process-slot recycling, not arbitrary host process
  termination. Only recognized `:v0..:v7` guest processes are eligible, and the
  exact calling PID/process name must match the engine record.
- Use one atomic watchdog winner so timeout cannot race a successful capability
  return. Keep Binder abandon bounded and out of the watchdog thread's final
  kill dependency; Binder death supplies cleanup if authority IPC is stalled.
- Do not claim that a 45-second safety watchdog solves Android ANR behavior.
  VirtualApp/BlackBox-style ordering still requires device measurements to
  decide whether heavy Application work must be prewarmed earlier or split
  into another staged launch protocol.

This closes the local watchdog/recycle code slice. It does not change the
commercial `BLOCK`: process-death recents and timeout recycling still need API
28-36/HyperOS evidence, and the engine authority must still move to a dedicated
virtual-system-server process.

## Authoritative Delete Comparison - 2026-07-13

Pinned source review:

- VirtualApp `7d739c85`,
  [`VAppManagerService.uninstallPackageAsUser/uninstallPackageFully`](https://github.com/asLody/VirtualApp/blob/7d739c85/VirtualApp/lib/src/main/java/com/lody/virtual/server/pm/VAppManagerService.java#L317-L368):
  serializes uninstall in the server, kills the target virtual user/package,
  preserves package artifacts when sibling users remain, then removes the
  selected user data. Full uninstall stops broadcasts, kills all package
  processes, deletes package/user artifacts, and removes package cache state.
- VirtualApp `7d739c85`,
  [`VActivityManagerService.killAppByPkg`](https://github.com/asLody/VirtualApp/blob/7d739c85/VirtualApp/lib/src/main/java/com/lody/virtual/server/am/VActivityManagerService.java#L872-L900):
  selects process records by package and virtual user before calling
  `killProcess(pid)`. It does not wait for process disappearance before package
  deletion continues.
- BlackBox `ffe950f7`,
  [`BPackageManagerService.uninstallPackageAsUser/uninstallPackage`](https://github.com/FBlackBox/BlackBox/blob/ffe950f7/Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/BPackageManagerService.java#L530-L588):
  keeps uninstall under package/install locks, kills the package/user first,
  deletes per-user artifacts, then removes package settings and component
  resolver state only when the final user is removed.
- BlackBox `ffe950f7`,
  [`BProcessManagerService.onProcessDie/killPackageAsUser`](https://github.com/FBlackBox/BlackBox/blob/ffe950f7/Bcore/src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java#L199-L257):
  owns process maps and notification cleanup. Explicit kill removes process-map
  entries immediately; Binder/process death is a separate callback, so an
  issued kill is not a confirmed exit barrier.
- DroidPlugin `c6ebf652`,
  [`IPluginManagerImpl.deletePackage`](https://github.com/DroidPluginTeam/DroidPlugin/blob/c6ebf652/project/Libraries/DroidPlugin/src/main/java/com/morgoo/droidplugin/pm/IPluginManagerImpl.java#L995-L1014):
  force-stops the package, removes the parser/cache and plugin base directory,
  notifies its Activity manager, clears signatures, and broadcasts removal.
  [`killBackgroundProcesses`](https://github.com/DroidPluginTeam/DroidPlugin/blob/c6ebf652/project/Libraries/DroidPlugin/src/main/java/com/morgoo/droidplugin/pm/IPluginManagerImpl.java#L1191-L1217)
  only observes that `killProcess(pid)` was issued. DroidPlugin has no
  VirtualApp-style per-user instance/dataRoot contract, and its base
  `onPkgDeleted` hook is empty.

Binder client behavior is also consistent on the key safety point. VirtualApp
`VirtualCore.uninstallPackage*` returns `false` after `RemoteException`,
BlackBox's package client logs the remote failure, and DroidPlugin logs/no-ops
when its manager is disconnected. They may reconnect or return weak errors,
but none performs a second local package deletion as a fallback authority.

MultiApp decisions:

1. Keep one engine/server command as the only permanent-delete writer. A
   Binder failure must remain an unknown/failed command, never a local retry.
2. Revoke launch capability and terminate the exact instance process before
   clearing mutable component state or deleting data.
3. Improve on all three references by confirming PID/processSlot disappearance
   before deletion continues; `killProcess()` issuance alone is insufficient.
4. Preserve package-scoped install artifacts for sibling instances. Delete
   only instance-scoped data, task, component, grant, policy, and slot state.
5. Do not copy VirtualApp/BlackBox partial-failure behavior: installer/dataRoot
   failure must retain a durable instance record and slot ownership so deletion
   can be retried without creating an untracked orphan.
6. Keep process-death cleanup and explicit delete idempotent. Either path may
   run first, but stale Binder callbacks must not affect a successor runtime
   generation.
7. Treat proxy/runtime slots as server-owned resources and release them only
   after durable instance deletion succeeds.
8. DroidPlugin's package-wide plugin deletion is useful for ordering only; it
   is not a sufficient multi-instance isolation or lifecycle model.

Current mapping:

- The current `VirtualizationEngine.deleteInstance` implementation follows the
  above ordering and adds confirmed process termination, target-instance state
  cleanup, dataRoot failure retention, and post-record slot release.
- Creation and proxy Activity slot allocation still have non-engine writers.
  Dedicated `:engine` migration remains blocked until those paths and direct
  owner-file reads are removed.

## Authoritative Create Comparison - 2026-07-13

Pinned source review:

- VirtualApp `7d739c85`,
  [`VAppManagerService.installPackage/installPackageAsUser`](https://github.com/asLody/VirtualApp/blob/7d739c85ffc4b2303a4c711b6f9472431089a42e/VirtualApp/lib/src/main/java/com/lody/virtual/server/pm/VAppManagerService.java#L142-L281):
  package import is serialized in the virtual package server; adding another
  virtual user changes per-user installed state instead of copying the package
  artifact again.
- BlackBox `ffe950f7`,
  [`BPackageManagerService.installPackageAsUser`](https://github.com/FBlackBox/BlackBox/blob/ffe950f7d15dae671cd8e95b58c83b35aab8f141/Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/BPackageManagerService.java#L523-L726):
  install runs under `mInstallLock`, creates/validates the user, delegates file
  work to the package installer, and updates package/user state only through
  the central package service.
- DroidPlugin `c6ebf652`,
  [`IPluginManagerImpl.installPackage`](https://github.com/DroidPluginTeam/DroidPlugin/blob/c6ebf652e0f73aa0e5746766e117e51efaf41dbd/project/Libraries/DroidPlugin/src/main/java/com/morgoo/droidplugin/pm/IPluginManagerImpl.java#L819-L994):
  centralizes plugin installation and replacement but has no VirtualApp-style
  per-user instance/dataRoot contract. It remains useful for package install
  ordering, not as the MultiApp instance model.

MultiApp decisions:

1. Keep package artifact generation separate from instance state. Matching
   sibling creation adds an instance reference/dataRoot and never rebuilds
   shared code.
2. Make the engine Binder endpoint the only production create writer. Client
   Binder failure cannot fall back to `InstanceManager` or installer calls.
3. Improve on the references with a durable client operation identity:
   `creationRequestId + full payload fingerprint`. An unknown Binder result is
   retried as the same operation, not as a second instance.
4. Bind declared package identity to the actual APK archive/manifest before
   import. Do not accept metadata from one installed package for another APK.
5. Use content-addressed base/split targets and same-directory staging. Stable
   split indexes prevent sanitized-name collisions; committed bytes are
   re-hashed before the record is accepted.
6. Preserve the prior package generation on caught update failures and preserve
   shared artifacts for sibling instances. Package upgrade remains a separate
   staging/commit operation, not an incidental side effect of create.
7. Do not overstate exception-safe staging as crash-atomic. Abandoned staging
   and orphan content-addressed files still need startup reconciliation before
   dedicated `:engine` migration can be called durable.

Current mapping:

- `CreateInstanceRequest` and the metadata AIDL endpoint implement the central
  create command; `CloneCreateUseCase` and Launcher only prepare/persist the
  client operation identity.
- `DefaultVirtualizationEngineCore` validates request replay, package
  generation, sibling reuse, first-import rollback, and instance persistence.
- `InstalledPackageImporter` provides content-addressed staged artifacts and
  collision-safe split names; `AppModule` provides archive/manifest identity
  validation and `ProductionVirtualInstallService` propagates resolver failure.
- Proxy Activity slot assignment is the next single-writer gap. Until it moves
  behind `VirtualActivityService`, commercial status remains `BLOCK`.

## Proxy Activity Slot Authority Comparison - 2026-07-13

Pinned-source conclusions remain consistent across the three reference
families:

- VirtualApp `7d739c85` allocates virtual process and stub Activity resources
  through its server-side Activity manager/stack before the client binds and
  patches the launch record. Client code consumes the selected stub; it is not
  a peer writer of a shared slot file.
- BlackBox `ffe950f7` similarly keeps process/stub selection in its central
  Activity/process services and sends the resulting proxy component to the
  client. Its exact records are not copied, but the ownership boundary is the
  relevant invariant.
- DroidPlugin `c6ebf652` centralizes Stub Activity selection with its plugin
  manager/Activity manager structures, but it has no VirtualApp-style
  per-instance generation and dataRoot model. It remains useful for stub-pool
  mechanics, not as MultiApp's durable multi-instance authority.

MultiApp decisions:

1. Keep the persistent assignment map inside the engine server. All app and
   guest paths use strict query/reserve/CAS commands and fail closed when the
   authority cannot answer.
2. Bind candidates to the authoritative instance runtime's host package,
   process slot, and normalized launch mode. A client cannot ask the server to
   reserve a proxy from another virtual process.
3. Preserve valid assignments across guest process death and temporary
   absence from recents. Explicit instance deletion or engine startup
   reconciliation owns durable release.
4. Keep Activity record rollback process-local, but make assignment rollback
   an authoritative CAS so a stale failure cannot overwrite a newer owner.
5. Reject a missing engine-selected foreground proxy instead of widening to
   the complete stub registry. This prevents a launch from silently changing
   processSlot/task identity.
6. Improve the boundary beyond the older references with strict Bundle field
   sets, candidate budgets, reply identity checks, and an executable source
   guard for the only persistent-store constructor.
7. Do not confuse host-UID checks with a complete capability model. The next
   security step is a runtime-generation-bound, one-time Activity allocation
   capability, followed by dedicated `:engine` process migration.

Current mapping:

- `RegistryBackedVirtualActivityService` is the single assignment authority.
- `IpcBackedProxyActivitySlotAssignmentStore` and the loader provider are the
  client adapters.
- Engine startup reconciles durable assignments; instance deletion delegates
  release to the Activity authority.
- The former eight production file-store construction paths are reduced to
  one server construction site and enforced by a unit-test source boundary.

This closes the proxy-slot multi-writer gap locally. Direct owner-file reads,
client file-backed read graphs, dedicated server isolation, complete component
semantics, and device evidence remain open, so commercial status stays
`BLOCK`.

## Authority, Capability, and Recovery Comparison - 2026-07-14

The next implementation step follows the server/client boundary shared by the
VirtualApp and BlackBox families without copying their code: package,
process, task, and stub-allocation truth belongs to the virtual system server;
the guest client consumes a generation-specific result and cannot rebuild a
second authority from persistence files. DroidPlugin remains useful for stub
pool mechanics, but its package-wide plugin model is not sufficient for
MultiApp's per-instance runtime epoch and dataRoot contract.

MultiApp decisions implemented in the current tree:

1. Transfer complete runtime/package snapshots through strict Binder codecs;
   do not let client or guest processes read engine owner files as live state.
2. Combine proxy-slot reservation and launch authorization in one server
   allocation. Bind its one-time capability to instance, PID, process slot,
   runtime epoch, and engine session.
3. Make capability replay and all old slot reserve/CAS Binder APIs fail closed.
   A stale client cannot authorize a new launch by retaining an old slot.
4. Journal package-generation mutations and reconcile staging, record backup,
   tombstone, and orphan artifacts before the engine authority starts.
5. Reject ambiguous recovery rather than exposing a partially restored package
   generation to guest processes.

Current mapping:

- `EngineAuthoritativeRuntimeCodec` and the new read endpoints replace the
  production client/guest owner-file read graph.
- `EngineActivityLaunchAllocator` is the single allocation/capability issuer;
  app and loader launch paths consume `PreassignedProxyActivitySlotStore`.
- `PackageGenerationRecoveryProvider` runs before `EngineBinderProvider` and
  delegates deterministic recovery to `PackageGenerationReconciler`.
- Focused tests and the one full gate pass: 1,933 tests, 0 failures, 0 errors,
  12 skipped, and `:app:assembleDebug` successful in 7m18s. The debug APK
  SHA-256 is
  `4B4B3C727695409DA273ACAAB6422D4BBE23EE48EFF1AF59F732E7F33C3DAABD`.
- Dedicated `:engine` process isolation, server death/reconnect, complete
  virtual component semantics, and device evidence remain open. Commercial
  status therefore remains `BLOCK`.

## Dedicated Server Process Comparison - 2026-07-14

Pinned-source findings:

- VirtualApp `7d739c85`,
  [`BinderProvider`](https://github.com/asLody/VirtualApp/blob/7d739c85/VirtualApp/lib/src/main/java/com/lody/virtual/server/BinderProvider.java),
  initializes package/activity/user/notification/storage services before
  publishing an `IServiceFetcher` Binder.
  [`ServiceManagerNative`](https://github.com/asLody/VirtualApp/blob/7d739c85/VirtualApp/lib/src/main/java/com/lody/virtual/client/ipc/ServiceManagerNative.java)
  re-fetches when the cached Binder is not alive, while
  [`ContentProviderCompat`](https://github.com/asLody/VirtualApp/blob/7d739c85/VirtualApp/lib/src/main/java/com/lody/virtual/helper/compat/ContentProviderCompat.java)
  uses an unstable Provider client and bounded acquisition retries.
- BlackBox `ffe950f7` declares
  [`SystemCallProvider`](https://github.com/FBlackBox/BlackBox/blob/ffe950f7/Bcore/src/main/java/top/niunaijun/blackbox/core/system/SystemCallProvider.java)
  in its service process. It starts `BlackBoxSystem` before serving Binders;
  `BlackBoxCore` explicitly separates main, server, and guest roles and only
  reuses live cached Binders. `ProviderCall` supplies a bounded retry path.
- DroidPlugin `c6ebf652` uses
  [`PluginManagerService`](https://github.com/DroidPluginTeam/DroidPlugin/blob/c6ebf652/project/Libraries/DroidPlugin/src/main/java/com/morgoo/droidplugin/PluginManagerService.java)
  and
  [`PluginServiceProvider`](https://github.com/DroidPluginTeam/DroidPlugin/blob/c6ebf652/project/Libraries/DroidPlugin/src/main/java/com/morgoo/droidplugin/PluginServiceProvider.java)
  to publish one package-manager owner. It has useful service-connection and
  readiness mechanics but no per-instance runtime epoch/session contract.

MultiApp decisions implemented in the current tree:

1. Place both package recovery and the virtual-system Binder owner in
   `${applicationId}:engine`; recovery must finish before Binder publication.
2. Route Application startup by exact process role. The server cannot install
   its own client or guest hooks, and the host no longer installs guest
   Instrumentation/system-service adapters.
3. Use a bounded unstable-Provider acquisition path, but reject any handshake
   without a live Binder, exact server process name, different PID, and
   matching random server generation ID.
4. Keep generation-aware `DeathRecipient` replacement so an old death callback
   cannot clear a newer server connection.
5. On server restart, invalidate persisted live guest state and start with no
   launch capabilities. Do not copy daemon/foreground keep-alive behavior from
   older projects.
6. Keep loader local slot behavior fail-closed and structured; no client may
   become a reserve/CAS authority while the server is unavailable.

Current verdict:

- Local focused tests, merged-manifest inspection, and the final full gate
  pass: 1,945 tests, 0 failures, 0 errors, 12 skipped, and
  `:app:assembleDebug` successful in 4m53s. The debug APK SHA-256 is
  `313DD2618C044499EB3E49DB366A3DAD274AFAEDB349BB4606C6C49954E80E18`.
- Distinct-process startup, independent server kill/reconnect, recents
  continuity, and stale generation rejection still need device evidence.
- Complete virtual component/native semantics remain open, so commercial
  status stays `BLOCK`.

## Authoritative PMS Comparison - 2026-07-14

Pinned-source findings:

- VirtualApp `7d739c85` keeps package queries in
  `VPackageManagerService`, routes `resolvedType` through its package Binder,
  and uses a central `IntentResolver` rather than independent component-side
  string checks.
- BlackBox `ffe950f7` follows the same central shape with
  `BPackageManagerService`, `ComponentResolver`, and `IntentResolver`; its
  client API also carries `resolvedType` into server queries.
- DroidPlugin `c6ebf652` confirms the hidden-PMS hook and `resolvedType`
  argument-routing pattern, but its package-wide plugin state is not a usable
  per-instance enabled-state authority.
- The older reference implementations return fixed values or no-op for some
  component enabled-state APIs. MultiApp must not copy that limitation because
  two clones of the same package can have different durable state.
- Android 16 `IntentFilter.matchData()` remains the semantic source for MIME,
  scheme, authority/port, path, and category matching.

MultiApp decisions implemented in the current tree:

1. Preserve complete manifest filters in the package snapshot and use one
   Android-free matcher from both engine and loader.
2. Carry structured filters through strict Binder schema 2 and durable runtime
   persistence; retain legacy read compatibility without pretending the old
   flat model is complete.
3. Keep hidden `IPackageManager` overload parsing version-tolerant while
   preserving `resolvedType` and numeric flags.
4. Bind package enabled state to `instanceId + package generation`; mutations
   go to the engine authority and never to Android's real PMS.
5. Keep unknown and mixed-boundary signing checks fail-closed. A compatibility
   shortcut must not create a signature match that cannot be proven from APK
   signing data.
6. Preserve URI host and port through Activity, Service, and Broadcast plans,
   including IPv6 bracket encoding.

Current verdict:

- The four affected module suites pass 1,393 tests with no failures or errors.
- The full repository gate and `:app:assembleDebug` pass in about 5m30s. The
  nine requested module reports contain 2,115 tests, 0 failures, 0 errors,
  and 12 skipped. The debug APK is 99,939,489 bytes with SHA-256
  `B267A1A6478158EBA7432B59DE63B97A53C0AF0DCEF651E841DBAE4750334F41`.
- This closes a local VPMS semantics batch, not full PMS/AMS/component/native
  equivalence or device compatibility. Commercial status remains `BLOCK`.
