# Repository Cleanup Plan

This file tracks the current cleanup pass so generated artifacts and useful
QQ Reader evidence are not mixed together.

## Current Priority

Temporarily record the QQ Reader blocker, then normalize the repository layout:

- keep source, reusable tools, and canonical docs;
- remove only generated files that can be recreated;
- ask before deleting large references, APKs, reverse-analysis outputs, or
  anything with unclear provenance.

## Keep

- Source modules under `app`, `core`, and build configuration.
- Reusable patch/build tools under `tools`.
- Current QQ Reader offline patch docs and evidence references.
- `tmp_apks\cn.xihan.qdds\QDReaderHook_3.3.6.apk` and related reference
  projects until the user explicitly approves deletion.
- Recent v86/v87 QQ Reader APKs and logs until the blocker is resolved.

## Safe-To-Delete Candidates

These are generated from local build or inspection commands and should be
recreated by scripts when needed:

- `**\build`
- `.gradle`
- `.kotlin`
- `core\hook\.cxx`
- `.tmp\app-debug-extract`
- `.tmp\app-debug-extract.zip`
- `.tmp\ListRuntimeNativeLoad.java`
- `.tmp\ListRuntimeNativeLoad.class`
- `.tmp\runtime-probe`
- `.tmp\runtime-probe.zip`
- `.tmp\dex-tools-classes`
- `.tmp\dumpdexclass-classes`
- `.tmp\dump-classrefs-classes`
- `.tmp\finddexrefs-classes`
- `.tmp\qqreader-tool-compile-check`
- `.tmp\verify_neutralized`
- `tmp_apks\stub_dex`

## Completed Cleanup

Deleted generated build/cache artifacts only:

- `.gradle`
- `.kotlin`
- module `build` directories
- `core\hook\.cxx`
- `.tmp` Java/dex helper compile outputs
- `.tmp\app-debug-extract*`
- `.tmp\runtime-probe*`
- `tmp_apks\stub_dex`
- `tools\qqreader-offline-patch\*.class`

No QQ Reader APKs, logs, dump files, reference projects, or reverse-analysis
directories were deleted.

## Ask Before Deleting

- `.tmp\backup`
- `.tmp\qdhook336`
- `.tmp\inspect_origin`
- `.tmp\qdaa-inspect*`
- `.tmp\inspect-*`
- `.tmp\*-dump*.txt`
- `.tmp\v*-*.txt`
- `.tmp\qqreader-*.apk`
- `.tmp\qqreader-*.txt`
- `.tmp\qqreader-*.xml`
- `.tmp\*.idsig`
- `.mimocode`
- `.vscode`
- `tmp_apks`
- archive files such as `*.zip` outside `.tmp`

## Code Hygiene Findings

These are not cleanup deletions. They should be handled as focused runtime
fixes after the repository cleanup pass:

- `LoaderFactory.kt`: log says `Skipping packer native preload`, but the code
  still calls `preloadPackerLibViaGuestClassLoader()`.
- `LoaderFactory.kt`: `P0 DUMP` copies runtime dumps to
  `/sdcard/Download/MultiApp_dump`; this should be gated by a debug flag or
  removed from normal runtime.
- `LoaderFactory.kt`: `patchJiaguSoIfPresent()` mutates `libjiagu_vip.so`,
  which makes native-load diagnosis harder to attribute.
- `native-hook.cpp`: `stub_interface_app()` returns before the old
  YWLoginManager/Fock re-registration block, leaving unreachable code below.
- `native-hook.cpp`: `Fock.sn` currently defaults to a diagnostic MD5 path
  unless `debug.multiapp.fock.call_original=1`; this may conflict with the
  BookCity/network-content blocker.
- `native-hook.cpp`: `stub_online_run` is only forward-declared. Do not add it
  as a no-op if the goal is normal QQ Reader reading, because that prevents
  chapter content from loading.

## Next Actions

1. Ask the user before deleting any item in the "Ask Before Deleting" section.
2. Fix the runtime code hygiene findings in small, reviewable changes.
3. Rebuild with:

```powershell
.\gradlew.bat --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
```

4. Repack and retest QQ Reader only after the runtime cleanup is complete.
