# Current Repository State

Date: 2026-06-12

Branch:

```text
minimal-poc
```

## Commit Scope

The current commit is intended to preserve the active MultiApp runtime work:

- LSPlant/native hook integration.
- Protected app loading and guest `ClassLoader`/native-load fixes.
- Activity intent remapping support.
- QQ Reader sign/native compatibility experiments.
- QQ Reader offline clone patch tooling.
- Documentation for current runtime plans, cleanup rules, and known blockers.
- Removal of tracked old `.tmp` crash logs that are now treated as local
  artifacts.

## Current QQ Reader Blocker

QQ Reader can reach the main UI in the current offline clone path, but normal
chapter loading is still blocked by missing native binding:

```text
java.lang.UnsatisfiedLinkError:
No implementation found for void
com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run()
```

Do not replace `OnlineChapterDownloadTask.run()` with a no-op if the goal is a
usable reader. A no-op can avoid the crash but prevents chapter content from
loading. The next real fix should make the original native implementation
register against the guest runtime class.

## Known Runtime Cleanup Risks

These items are recorded for follow-up fixes and should not be hidden by a
large cleanup commit:

- `LoaderFactory.kt` still contains misleading packer native preload logging.
- `LoaderFactory.kt` contains `P0 DUMP` logic that copies diagnostic files to
  `/sdcard/Download/MultiApp_dump`.
- `patchJiaguSoIfPresent()` mutates extracted `libjiagu_vip.so`, which makes
  attribution of native-load fixes harder.
- `Fock.sn` currently defaults to a diagnostic MD5 path unless
  `debug.multiapp.fock.call_original=1`; this can conflict with QQ Reader
  network/content verification.
- `stub_interface_app()` has unreachable legacy registration code after an
  early return.

## Local Artifact Policy

The following paths are ignored and should not be committed unless explicitly
promoted to source/reference material:

```text
.tmp/
tmp_apks/
.mimocode/
.vscode/
```

Recent QQ Reader APKs, logs, UI dumps, reverse-analysis dumps, and reference
APKs are intentionally left on disk for now. Delete them only after explicit
confirmation.

## Verification Command

Preferred build verification on this machine:

```powershell
.\gradlew.bat --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
```
