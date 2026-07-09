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
