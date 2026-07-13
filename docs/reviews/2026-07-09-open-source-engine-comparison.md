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
