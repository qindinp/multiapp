# Current Repository State

Date: 2026-06-13

Branch:

```text
minimal-poc
```

## Progress Preservation Rule

每个可验证阶段都必须把结论写回本文件，避免对话压缩后丢失上下文。

记录时至少包含：

- APK/version tag。
- 设备与包名。
- 关键 runtime props。
- 日志文件路径。
- 可复现证据。
- 结论和下一步。

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

Latest correction after v150: QQ Reader's current reproducible blocker is
chapter body loading, not the older `RegisterNatives count=10` gate.

QQ Reader can reach the main UI when MultiApp installs fallback native methods.
That is still not a complete protected-runtime success, but the historical
`libjiagu_vip.so RegisterNatives count=10` boundary is not reproducible on the
current device/run even with the old v131 APK. The same v131 APK now reaches UI
through MultiApp `StubApp` core fallback and then fails at the chapter native
method:

```text
No implementation found for void
com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run()
```

Therefore the active gate is:

```text
OnlineChapterDownloadTask.run() must either bind to the original protected
native implementation, or be replaced by an equivalent implementation that
produces real QQ Reader chapter files.
```

Do not replace `OnlineChapterDownloadTask.run()` with a no-op if the goal is a
usable reader. A no-op can avoid the crash but prevents chapter content from
loading.

Historical shell boundary evidence remains useful, but it is no longer the next
pass/fail gate:

```text
libjiagu_vip.so must complete original StubApp RegisterNatives
=> RegisterNatives: class=com.stub.StubApp count=10
=> captured original interface11
=> captured original interface20
```

Current runtime direction:

- Do not inject `NativeLibLoader.loadLibrary("jiagu_vip")` into `StubApp.load()`.
  v124 showed that this can poison the later load with
  `JNI_OnLoad failed on a previous attempt`.
- Keep `debug.multiapp.jiagu.explicit_load=0`.
- Keep `debug.multiapp.jiagu.prehook_dlopen=0`. v133 showed that preloading
  `libjiagu_vip.so` with `dlopenOnly` can cause the later `System.loadLibrary`
  path to reuse the handle without rerunning the shell `JNI_OnLoad`.
- Let original `StubApp.load()` run first. v131 proved that this can produce
  the original shell registration:

```text
RegisterNatives: class=com.stub.StubApp count=10
RegisterNatives StubApp: captured original interface11=...
RegisterNatives StubApp: captured original interface20=...
```

- Do not register MultiApp `StubApp` core fallback when
  `debug.multiapp.stubapp.fallback=0`. v147 showed that registering the
  fallback makes later `StubApp.interface11(...)` calls land in
  `stub_interface11 fallback`, hiding the fact that the original shell
  registration never happened.
- Use `debug.multiapp.stubapp.fallback=core` only as a diagnostic mode, not as a
  pass criterion.
- Do not treat the direct `ChapBatAuthWithPD` fallback as a final fix. v139-v145
  showed that it can return `ReadOnline.search resultCode=0`, but the resulting
  chapter file is often `*_s size=0` with empty `fileUrl/resourceUrl`; copying
  `*_ALL_o` to `.eqct` was disproved by "chapter data load failed".
- v147 live `run_fallback=1` confirmed the same boundary again:

```text
nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback registered
stub_online_run: ReadOnline.search returned resultCode=0
stub_online_run: ReadOnlineFile chapterId=37 path=.../58340402_37_s size=0
stub_online_run: getBookSucces dispatched ok=1
```

But the phone directory contained no usable `.eqct/.eres`; it contained
zero-byte `58340402_<cid>_s` files and `58340402_ALL_o`. Therefore
`stub_online_run` is only a diagnostic bridge, not chapter content repair.
- `stub_online_run` must not copy `*_ALL_o` into `.eqct`. That path creates a
  false success callback while the reader still cannot parse the chapter.
- For v151 diagnostics, use `debug.multiapp.online.run_fallback=1` and
  `debug.multiapp.online.materialize_eqct=0`. This does not claim a fix; it
  only logs whether `ReadOnline.search(...)` receives usable chapter metadata.
- Keep `debug.multiapp.online.run_fallback=0` only when explicitly testing
  whether the real native `OnlineChapterDownloadTask.run()` has been restored.

Latest verified package:

```text
.tmp\qqreader-c9f8-neutralized-v148-preserve-stubapp-no-fake-all-o-signed.apk
```

v148 status:

- Built and repacked locally.
- Installed successfully on `192.168.2.125:41451`.
- Default props used by `test-qqreader-offline.ps1`:

```text
debug.multiapp.jiagu.explicit_load=0
debug.multiapp.jiagu.prehook_dlopen=0
debug.multiapp.patch_jiagu=0
debug.multiapp.online.state_fallback=1
debug.multiapp.online.run_fallback=0
debug.multiapp.online.materialize_eqct=0
debug.multiapp.online.failure_callback=0
debug.multiapp.stubapp.fallback=0
```

- Result: crash is expected and useful because it proves fallback no longer
  masks the shell boundary:

```text
No implementation found for void com.stub.StubApp.interface11(int)
No implementation found for boolean com.stub.StubApp.interface20()
Stage2 OnlineChapterDownloadTask.run not bound
```

- Important: v148 did not fix QQ Reader. It clarified that the project must
  restore original `libjiagu_vip.so` `RegisterNatives count=10` before further
  chapter loading work is meaningful.

Recent logs:

```text
.tmp\qqreader-v147-run-fallback-live-logcat.txt
.tmp\qqreader-v147-run-fallback-live-crash.txt
.tmp\qqreader-v147-run-fallback-live-exit-info.txt
.tmp\qqreader-v148-preserve-stubapp-no-fake-all-o-start-logcat.txt
.tmp\qqreader-v148-preserve-stubapp-no-fake-all-o-start-crash.txt
.tmp\qqreader-v148-preserve-stubapp-no-fake-all-o-start-exit-info.txt
```

## Known Runtime Cleanup Risks

These items are recorded for follow-up fixes and should not be hidden by a
large cleanup commit:

- `LoaderFactory.kt` packer native preload logging was corrected to match the
  actual guest-ClassLoader preload behavior.
- `LoaderFactory.kt` `P0 DUMP` is gated behind
  `-Dmultiapp.dump.enabled=true`; it should remain off during normal QQ Reader
  compatibility testing.
- `patchJiaguSoIfPresent()` mutates extracted `libjiagu_vip.so`, which makes
  attribution of native-load fixes harder.
- `Fock.sn` now calls the original native implementation by default. Diagnostic
  MD5 fallback requires `debug.multiapp.fock.diagnostic_md5=1`.
- Offline `QqReaderSignCompat.sign()` now accepts `FockRT.sn` as the real path;
  diagnostic MD5 fallback requires `-Dmultiapp.qqreader.sign.diagnostic_md5=true`.
- `stub_interface_app()` unreachable legacy registration code was removed.

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

## Resumable QQ Reader Packaging

Use the checkpoint wrapper after context compression or tool interruption. It
returns immediately when the requested signed clone APK already exists:

```powershell
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v146-restore-v138-native-boundary
```

Current reusable APK:

```text
.tmp\qqreader-c9f8-neutralized-v148-preserve-stubapp-no-fake-all-o-signed.apk
```

After runtime code changes, force the complete path explicitly:

```powershell
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v147-next `
  -Build `
  -ForceExtract `
  -ForceRepack
```

## Next Device Verification

When wireless debugging is available again, run the current v148 package with
the fixed prop set:

```powershell
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 `
  -Connect <IP:PORT> `
  -VersionTag v148-preserve-stubapp-no-fake-all-o
```

If the phone exposes a new wireless debugging port, replace only the `-Connect`
value. The script installs the APK, starts QQ Reader, captures `logcat`,
`crash`, and `exit-info`, then prints the key evidence lines.

The pass/fail boundary for the next run is:

- Pass first boundary: `RegisterNatives: class=com.stub.StubApp count=10` appears
  from `libjiagu_vip.so`, and original `interface11/interface20` are captured.
- Fail first boundary: only the MultiApp `RegisterNatives ... count=4` appears,
  `No implementation found for com.stub.StubApp.interface11/interface20`
  appears, or `JNI_ERR returned from JNI_OnLoad` appears.
- Only after the first boundary passes should chapter loading be tested. If
  `OnlineChapterDownloadTask.run()` is still unbound with
  `debug.multiapp.online.run_fallback=0`, the next fix must restore the original
  protected native registration rather than copying `*_ALL_o` to `.eqct`.

## Latest Code Changes After v148

- `core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt`
  - `debug.multiapp.stubapp.fallback=0` now truly preserves shell registrations.
  - MultiApp `StubApp` core fallback is registered only when the property is
    explicitly set to `core`.
- `core/hook/src/main/cpp/native-hook.cpp`
  - `stub_online_run` no longer materializes `.eqct` from `*_ALL_o`.
  - A missing real chapter source is logged as failure instead of being
    converted into a fake success.

## 2026-06-13 v149 Investigation Notes

Context after compression: continue from the QQ Reader chapter-body blocker.
Do not restart from old direct-fetch/no-op routes.

New evidence checked after v148:

- v131 and v148 both installed the `RegisterNatives` logger successfully.
  Therefore the missing
  `RegisterNatives: class=com.stub.StubApp count=10` in v148 is not because the
  probe was absent.
- v148 did load `libjiagu_vip.so` through Android's native loader:

```text
nativeloader: Load .../lib/arm64/libjiagu_vip.so using isolated ns ...: ok
preloadPackerLib: StubApp.load() invoked OK
```

  But no original `com.stub.StubApp count=10` registration followed, and the
  app later crashed on `StubApp.interface20()` being unbound.
- v131 successful boundary:

```text
RegisterNatives: class=com.stub.StubApp count=10 ... libjiagu_vip.so offset=0x1116b4
RegisterNatives StubApp: captured original interface11=...
RegisterNatives StubApp: captured original interface20=...
GOT tgkill intercepted ... libjiagu_vip.so offset=0x11cb88
patch_jiagu_vip_self_kill_callsite ... prev=0x52800120 insn=0x97ffb823 next=0x140000e8
```

- v148 boundary:

```text
patch_jiagu_vip_self_kill_callsite ... offset=0x11cb84 prev=0x00000000 insn=0x00000000 next=0x00000000
No implementation found for boolean com.stub.StubApp.interface20()
```

- Package `assets/origin.apk` `lib/arm64-v8a/libjiagu_vip.so` is not the
  difference between v131 and v147:

```text
SHA256=12D2956C655BB8618A4345FF900506DA995AC1D324AB7E69B5E1C3A798223E66
```

  This means the v131 success came from runtime conditions or MultiApp native
  runtime code, not from a different QQ Reader shell library.
- `libmultiapp-native.so` and `loader.dex` did change between v131 and v148:

```text
v131 libmultiapp-native.so SHA256=8219668898E594DB2CBCA1726DE544DF24300472C18501498AA5AB87E93FE88E
v148 libmultiapp-native.so SHA256=AA77B001BA44FA008F70285CC3DC0A69AC9BD6AAAD95BA14A953B99E86DC578D
v131 loader.dex SHA256=03B88206FA59D1831AA1409E1F3360874CA4AB11715C4E258A0A693E8AE8C5EB
v148 loader.dex SHA256=734B763D34CEF6F58CCC1A3717B8E315B406CED6EA212DFC6C22A159BD9CC1CB
```

- A concrete v131/v148 runtime property difference was found:

```text
v131: setprop debug.multiapp.origin_libs.writable 1
v148: no debug.multiapp.origin_libs.writable property set
```

Next controlled test:

1. Re-run the current package with `debug.multiapp.origin_libs.writable=1`.
2. Force native lib cache refresh if needed so the marker records
   `nativeLibsWritable=true`.
3. Pass condition remains only:

```text
RegisterNatives: class=com.stub.StubApp count=10
captured original interface11
captured original interface20
```

If this does not restore the v131 boundary, the next fix should compare the
v131/v148 `libmultiapp-native.so` behavior around `got_hook`, `dlopen`,
`FindClass`, and `StubApp.load()` rather than changing
`OnlineChapterDownloadTask.run()`.

v149 controlled test result:

- APK used:

```text
.tmp\qqreader-c9f8-neutralized-v148-preserve-stubapp-no-fake-all-o-signed.apk
```

- Runtime-only change:

```text
debug.multiapp.origin_libs.writable=1
```

- Logs:

```text
.tmp\qqreader-v149-writable-origin-libs-same-v148-apk-start-logcat.txt
.tmp\qqreader-v149-writable-origin-libs-same-v148-apk-start-crash.txt
.tmp\qqreader-v149-writable-origin-libs-same-v148-apk-start-exit-info.txt
```

- Evidence:

```text
Origin native libs kept writable: .../lib/arm64
Extracted 48 origin native libs for arm64-v8a
preloadPackerLib: StubApp.load() invoked OK
patch_jiagu_vip_self_kill_callsite ... offset=0x11cb84 prev=0x00000000 insn=0x00000000 next=0x00000000
No implementation found for void com.stub.StubApp.interface11(int)
No implementation found for boolean com.stub.StubApp.interface20()
Stage2 OnlineChapterDownloadTask.run not bound
```

- Conclusion: `debug.multiapp.origin_libs.writable=1` is not sufficient to
  restore the v131 shell boundary. Continue comparing the v131 and v148
  MultiApp runtime payloads. The current blocker remains earlier than chapter
  `run()`: original `libjiagu_vip.so` does not register
  `com.stub.StubApp count=10`.

v150 retest of the historical v131 APK on the current device:

- APK used:

```text
.tmp\qqreader-c9f8-neutralized-v131-shadowhook-init-fallback-signed.apk
```

- Logs:

```text
.tmp\qqreader-v150-retest-v131-runtime-boundary-start-logcat-late.txt
```

- Result:

```text
preloadPackerLib: StubApp.load() invoked OK
patch_jiagu_vip_self_kill_callsite ... offset=0x11cb84 prev=0x00000000 insn=0x00000000 next=0x00000000
preloadPackerLib: registering StubApp core bootstrap methods (interface5/interface11/interface20/interface21)
RegisterNatives: class=com.stub.StubApp count=4 ... libmultiapp-native.so
stub_interface11 fallback ...
No implementation found for void com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run()
```

- Important correction: the historical v131 log did show original
  `libjiagu_vip.so` `RegisterNatives count=10`, but the same v131 APK did not
  reproduce that boundary on the current device/run. v131 can still enter the
  UI because its older runtime registers MultiApp `StubApp` core fallback even
  when `debug.multiapp.stubapp.fallback=0`.
- Current reproducible blocker is therefore:

```text
OnlineChapterDownloadTask.run() is still unbound when the reader tries to load chapter body.
```

  The `count=10` shell boundary remains valuable evidence, but it is no longer
  a reproducible gate for the next step. Continue from the actually reproduced
  chapter `run()` failure.

## 2026-06-13 v151 Plan

Purpose: diagnose `OnlineChapterDownloadTask.run()` fallback without creating
fake chapter files.

Code change prepared:

- `core/hook/src/main/cpp/native-hook.cpp`
  - Add bounded `info.txt` preview logging after `ReadOnline.search(...)`.
  - Log expected `.eqct` size, `*_ALL_o` size, and `ReadOnlineFile` list size.
  - Log each `ReadOnlineFile` metadata entry:
    `chapterId`, dest path, dest size, `fileUrlEmpty`, `resourceUrlEmpty`, and
    truncated URL previews.
  - Keep `debug.multiapp.online.materialize_eqct=0`; no `*_ALL_o -> .eqct`
    copy is reintroduced.

Next verification tag:

```text
v151-online-info-json-diag
```

Expected decisive evidence:

- If `infoPreview` lacks `ctebchaptercosurl`, `epubPureUrl`, and
  `epubResourceUrl`, the direct `ChapBatAuthWithPD` request is still missing
  auth/sign/session inputs.
- If those fields exist but `ReadOnlineFile` destination files stay zero bytes,
  fix the file-download/materialization path using
  `ReadOnlineFile.getDestFile()`.
- If files become non-zero and QQ Reader still reports chapter load failure,
  inspect the reader-side expected suffix/path contract (`.eqct`, `.eres`,
  `*_s`, `chapter.q`) before dispatching success.

## 2026-06-13 v151 Result

APK:

```text
.tmp\qqreader-c9f8-neutralized-v151-online-info-json-diag-signed.apk
```

Device:

```text
192.168.2.125:41451
```

Runtime props used for diagnosis:

```text
debug.multiapp.stubapp.fallback=core
debug.multiapp.online.state_fallback=1
debug.multiapp.online.run_fallback=1
debug.multiapp.online.materialize_eqct=0
debug.multiapp.online.failure_callback=0
```

Log:

```text
.tmp\qqreader-v151-online-info-json-diag-read-logcat.txt
```

Key evidence:

```text
stub_online_run: attempting direct ReadOnline fetch ... scids=38
stub_online_run: ReadOnline.search returned resultCode=0
stub_online_run: post-search ... expectedEqct=.../38.eqct expectedEqctSize=-1
stub_online_run: post-search ... infoPath=.../info.txt infoSize=-1 infoPreview=
stub_online_run: post-search ... allPath=.../58340402_ALL_o allSize=19586
stub_online_run: direct-fetch ReadOnlineResult resultCode=0 filesSize=1 effectiveCid=38
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=38
  path=.../58340402_38_s size=0
  fileUrlEmpty=1 resourceUrlEmpty=1
```

Repeated for nearby chapters (`37`, `39`, `40`, `41`) with the same shape:

```text
ReadOnline.search resultCode=0
ReadOnlineFile size=0
fileUrlEmpty=1
resourceUrlEmpty=1
expected .eqct missing
```

Conclusion:

- The direct `ChapBatAuthWithPD` fallback is not enough to produce readable
  chapter body files.
- `ReadOnline.search(...)` can parse enough metadata to create a
  `ReadOnlineFile`, but the returned metadata lacks real
  `ctebchaptercosurl`/`epubPureUrl`/`epubResourceUrl`-derived URLs.
- Current fallback must not dispatch `getBookSucces` when the matching
  `ReadOnlineFile` is zero-byte and has no file/resource URL. That creates a
  false success and leads to "chapter data load failed".

Next code fix:

- Add a reusable `ReadOnlineResult` usability check.
- Only dispatch success when the effective chapter has a non-zero destination
  file, or when materialization/download has produced one.
- Otherwise dispatch failure and continue reverse-engineering the real auth
  inputs for `OnlineChapterDownloadTask.run()`.

## 2026-06-13 v152 Plan

Purpose: remove the remaining false-success path in `stub_online_run`.

Code change prepared:

- `core/hook/src/main/cpp/native-hook.cpp`
  - Add `online_result_has_usable_file(...)`.
  - Treat `ReadOnlineResult resultCode=0` as success only when:
    - expected `.eqct` exists and is non-zero, or
    - matching `ReadOnlineFile.getDestFile()` exists and is non-zero, or
    - `materialize_online_eqct(...)` produced a usable file.
  - If result code is `0` but no usable file exists, log:
    `ReadOnlineResult success but no usable chapter file ... dispatching failed`.

Next verification tag:

```text
v152-no-fake-success-with-empty-readonlinefile
```

## 2026-06-13 v152 Result

APK:

```text
.tmp\qqreader-c9f8-neutralized-v152-no-fake-success-with-empty-readonlinefile-signed.apk
```

Logs:

```text
.tmp\qqreader-v152-no-fake-success-with-empty-readonlinefile-read-logcat.txt
.tmp\qqreader-v152-no-fake-success-with-empty-readonlinefile-click-logcat.txt
```

First 90-second start window:

- App installed and started.
- `OnlineChapterDownloadTask` fallback registration succeeded.
- No `stub_online_run` trigger occurred before user interaction.

Second click-triggered window:

```text
stub_online_run: attempting direct ReadOnline fetch ... bookId=58817180 ... scids=52
stub_online_run: ReadOnline.search returned resultCode=0
stub_online_run: direct-fetch ReadOnlineResult resultCode=0 filesSize=1 effectiveCid=52
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=52
  path=.../58817180_52_s size=0
  fileUrlEmpty=1 resourceUrlEmpty=1
stub_online_run: ReadOnlineResult success but no usable chapter file
  allow=0 materialized=0 hasUsable=0
  expectedEqct=.../52.eqct expectedEqctSize=-1
  dispatching failed
```

The same shape repeated for other free/nearby chapters:

```text
chapter 7  -> resultCode=0, fileUrlEmpty=1, resourceUrlEmpty=1, size=0
chapter 28 -> resultCode=0, fileUrlEmpty=1, resourceUrlEmpty=1, size=0
chapter 42 -> resultCode=0, fileUrlEmpty=1, resourceUrlEmpty=1, size=0
```

For paid/VIP chapters, direct fallback returns:

```text
resultCode=-9
filesSize=0
getBookNeedVIPOrPay dispatched ok=1
```

Conclusion:

- v152 fixed the false-success path.
- Current blocker is now sharply defined:

```text
Direct ChapBatAuthWithPD fallback lacks the real auth/sign/session inputs used
by protected OnlineChapterDownloadTask.run(); it returns metadata without
usable ctebchaptercosurl/epubPureUrl/epubResourceUrl, so no readable .eqct/.eres
can be produced.
```

Next real fix direction:

1. Reverse or observe original `OnlineChapterDownloadTask.run()` request
   construction.
2. Identify how `buildUrl(...)`, `obtainHeaders()`, `downloadChapterFile(...)`,
   `sessionKey`, `usid`, `bookTaskId`, and any shell-generated sign/auth fields
   are supposed to combine.
3. Implement only the missing real request/auth path, not fake
   `ReadOnlineResult` success.

## 2026-06-13 Post-v152 Reverse Notes

Additional dumps created:

```text
.tmp\com-qq-reader-common-conn-search-qdad.dump.txt
.tmp\com-qq-reader-common-conn-search-qdaa.dump.txt
.tmp\com-qq-reader-common-conn-http-search-qdaf_qdaa.dump.txt
```

Findings:

- `OnlineTag.f("")` only builds the base authorization URL:

```text
bookId=<bid>&type=<type>&tafauth=1&useindex=1&scids=0
```

  Replacing `scids=0` with the current cid is necessary but not sufficient.
- `qdbb` creates `OnlineChapterDownloadTask`, sets priority/background/scene,
  then enqueues it through `qdaa$qdad.search(task)`.
- `qdaa$qdaa` queue wrapper sets:

```text
OnlineChapterDownloadTask.bookTaskId = <bid>-<System.nanoTime()>
OnlineChapterDownloadTask.taskDownloadListener = this
```

  Then it calls only:

```text
OnlineChapterDownloadTask.run()
```

- `common/conn/search/qdad` is not the chapter downloader. It is an online
  host/IP provider:
  - It stores a `qdac` host record.
  - `judian()` returns `https://<host>`.
  - `search(qdac)` resets/replaces the host after failures.
- `common/conn/search/qdaa` is a small async checker with a fixed thread pool,
  not the chapter auth downloader.
- `common/conn/http/search/qdaf$qdaa` is only a progress/done/failed callback
  interface.

Conclusion:

The missing logic is still inside protected native
`OnlineChapterDownloadTask.run()`. Java-side queueing and host fallback are
visible, but they do not construct the real auth payload. The next useful
reverse target is the actual HTTP task class invoked by native `run()`, or the
native request construction in `libjiagu_vip.so`/related protected libraries.

Additional reference search:

```text
.tmp\refs-ReadOnline-search.txt
.tmp\refs-OnlineChapterDownloadTask-inner-inits.txt
.tmp\refs-common-conn-http-search.txt
```

Findings:

- `ReadOnline.search(InputStream, OnlineTag, String)` has no normal Java caller
  outside its own helper calls. The direct fallback calls it manually.
- `OnlineChapterDownloadTask$3/$4/$5.onConnectionRecieveData(...)` call
  `ReadOnline.search(ReadOnlineResult, paypageModel)`; these are login/pay/risk
  response handlers, not the chapter tar parser.
- No Java method references constructors for
  `OnlineChapterDownloadTask$1/$2/$3/$4/$5`.

Conclusion:

The important inner callbacks are likely instantiated from native
`OnlineChapterDownloadTask.run()` through JNI/reflection. Static Java-only
reconstruction is therefore insufficient; the next step is native library
string/callsite analysis for `OnlineChapterDownloadTask`, `ReaderProtocolTask`,
`ChapBatAuthWithPD`, and the inner callback class names.

## 2026-06-13 v154 QQ Reader Status

Current APK built:

```text
.tmp\qqreader-c9f8-neutralized-v154-readerprotocol-fallback-signed.apk
```

Implemented after v153:

- Added `QqReaderOnlineProtocolFallback`.
- `stub_online_run` now tries `ReaderProtocolTask.run()` first, using a
  reflected `Proxy` listener for `com.yuewen.component.task.ordinal.qdab`.
- The listener passes `ReaderProtocolTask.onFinish(...).byteStream()` to:

```text
ReadOnline.search(InputStream, OnlineTag, url)
```

- If this fails, v154 falls back to the v153 direct `URLConnection` path.
- The existing success guard remains: no non-zero `.eqct`/usable
  `ReadOnlineFile`, no success callback.

Validation status:

```text
build: success
adb devices: no online devices
device test: pending
```

Next test command:

```powershell
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 `
  -Connect <ip:port> `
  -VersionTag v154-readerprotocol-fallback `
  -WaitSeconds 45
```

Required log evidence:

```text
remember_hook_classloader: hook ClassLoader captured
stub_online_run: attempting ReaderProtocolTask ReadOnline fetch
stub_online_run: protocol fallback ReaderProtocolTask.run url=...
stub_online_run: protocol fallback ReadOnline.search result=...
stub_online_run: protocol fallback returned resultCode=...
```

## 2026-06-14 MultiApp-Generated Clone Checkpoint

Device and package:

```text
device=100.110.94.8:41895
package=com.qq.reader.clonestub_a536097086ad4b60bd869d38d402d44f
```

Latest 20-second log capture:

```text
.tmp\qqreader-clone-20s-20260614-092101-logcat.txt
.tmp\qqreader-clone-20s-20260614-092101-crash.txt
.tmp\qqreader-clone-20s-20260614-092101-exit-info.txt
```

Current result:

```text
reason=5 (APP CRASH(NATIVE)) status=11
Fatal signal 11 (SIGSEGV)
#00 libart.so art::JavaVMExt::DecodeWeakGlobal
#03 libmultiapp-native.so _JNIEnv::CallObjectMethod
#04 libmultiapp-native.so Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterDownloadFallbackStubs+180
#07 base.apk com.multiapp.core.hook.NativeHookBridge.registerOnlineChapterDownloadFallbackStubs
#09 base.apk com.multiapp.core.loader.LoaderFactory.swapClassLoader
```

Conclusion:

- The current MultiApp-generated QQ Reader clone does not reach the chapter
  body test anymore.
- It crashes earlier while installing
  `OnlineChapterDownloadTask` fallback stubs.
- This is a new startup blocker introduced by the v154 helper/classloader work,
  not evidence that the chapter `ReaderProtocolTask` fallback succeeded or
  failed.

Likely code issue to fix next:

- `nativeRegisterOnlineChapterDownloadFallbackStubs()` currently calls
  `ClassLoader.loadClass(...)` from native code and crashes inside ART
  `DecodeWeakGlobal`.
- Do not reuse a global/cached `ClassLoader.loadClass` method path in this
  registration entry.
- Use a local `FindClass("java/lang/ClassLoader")` +
  `GetMethodID("loadClass", "(Ljava/lang/String;)Ljava/lang/Class;")` inside
  the function, then check and clear JNI exceptions after the call.
- Apply the same safe helper-load pattern to the
  `QqReaderOnlineProtocolFallback` loading path before retesting v154.

Debuggability check:

```text
dumpsys package: pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
run-as: package not debuggable
```

The installed clone is not debuggable even though current manifest generator
code contains `android:debuggable="true"`. After rebuilding/installing the
current MultiApp APK and regenerating the QQ Reader clone once, verify with:

```powershell
adb -s 100.110.94.8:41895 shell run-as com.qq.reader.clonestub_a536097086ad4b60bd869d38d402d44f id
```

Pass criterion for the next run:

```text
No SIGSEGV in nativeRegisterOnlineChapterDownloadFallbackStubs
run-as succeeds for the generated clone
Only then continue chapter OnlineChapterDownloadTask.run()/ReaderProtocolTask validation
```

Fix applied:

```text
file=core/hook/src/main/cpp/native-hook.cpp
change=added load_class_with_loader(...) with local ClassLoader.loadClass lookup
change=nativeRegisterOnlineChapterDownloadFallbackStubs now uses the local helper
change=read_online_search_via_reader_protocol now loads QqReaderOnlineProtocolFallback through the same local helper
```

Verification:

```text
.\gradlew.bat --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug
result=BUILD SUCCESSFUL

.\gradlew.bat --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
result=BUILD SUCCESSFUL
```

Installed updated MultiApp debug APK:

```text
device=100.110.94.8:41895
apk=app\build\outputs\apk\debug\app-debug.apk
package=com.multiapp.app
lastUpdateTime=2026-06-14 09:37:01
pkgFlags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
```

Important: the already-installed QQ Reader clone was built before this fix and
will not automatically receive the new `libmultiapp-native.so`/`loader.dex`.
Regenerate the QQ Reader clone with the updated MultiApp APK, then verify:

```powershell
adb -s 100.110.94.8:41895 shell run-as <new.qq.reader.clone.package> id
adb -s 100.110.94.8:41895 logcat -c
```

The first post-fix log target is still startup stability, not chapter content.
Only after `nativeRegisterOnlineChapterDownloadFallbackStubs` no longer appears
in a SIGSEGV tombstone should chapter `ReaderProtocolTask` fallback testing
continue.

## 2026-06-14 v155 Offline Clone Result

The offline clone route was tested directly, without waiting for MultiApp UI
clone generation:

```text
apk=.tmp\qqreader-c9f8-neutralized-v155-jni-instance-classloader-fix-signed.apk
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
device=100.110.94.8:41895
install=Success
startup=Status: ok
crash buffer=empty
process alive=pid 21678
```

Evidence files:

```text
.tmp\qqreader-v155-offline-start-20260614-100606-logcat.txt
.tmp\qqreader-v155-offline-start-20260614-100606-crash.txt
.tmp\qqreader-v155-offline-start-20260614-100606-exit-info.txt
```

Current conclusion:

- The previous `DecodeWeakGlobal` /
  `nativeRegisterOnlineChapterDownloadFallbackStubs` startup crash is fixed in
  the v155 offline clone.
- `OnlineChapterDownloadTask` fallback registration succeeds, including the
  `run()` fallback.
- The app reaches QQ Reader UI and `ReaderPageActivity`.
- Chapter body loading is still not fixed.

Current chapter blocker:

```text
stub_online_run: attempting ReaderProtocolTask ReadOnline fetch ... scids=1
stub_online_run: protocol fallback failed java.lang.NoSuchFieldException: No field mUrl in class Lcom/yuewen/component/businesstask/ordinal/ReaderProtocolTask;
stub_online_run: ReaderProtocolTask fetch failed; falling back to direct URLConnection
stub_online_run: applied QQReader request headers count=34
stub_online_run ReadOnline.search threw; falling back
stub_online_run: ReadOnline.search failed ok=0 result=null
```

On-device file evidence:

```text
book.meta, chapter.q, adv.m created under QQReader/Online/58358897
no 1.eqct / 2.eqct generated
zero-size file name observed: {"code": -1, "msg": "deny", "version":""}
```

Do not repeat the startup SIGSEGV investigation unless a new tombstone proves a
regression. Next work item is v156: inspect the real `ReaderProtocolTask`
fields/subclasses and fix `QqReaderOnlineProtocolFallback.fetch(...)` so it uses
the actual QQ Reader request task shape instead of the nonexistent `mUrl` field.

## 2026-06-14 v156 ReaderProtocolTask Boundary

v156 was built and installed as an offline clone:

```text
apk=.tmp\qqreader-c9f8-neutralized-v156-readerprotocol-seturl-signed.apk
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
device=100.110.94.8:41895
install=Success
```

Evidence files:

```text
.tmp\qqreader-v156-readerprotocol-seturl-start-20260614-102533-logcat.txt
.tmp\qqreader-v156-readerprotocol-seturl-start-20260614-102533-crash.txt
.tmp\qqreader-v156-readerprotocol-seturl-start-20260614-102533-exit-info.txt
```

Code correction:

```text
core\hook\src\main\java\com\multiapp\core\hook\QqReaderOnlineProtocolFallback.java
```

- Replaced the hard-coded `ReaderProtocolTask.mUrl` reflection path with
  `ReaderProtocolTask.setUrl(String)`.
- Kept `mUrl` only as fallback.

Verified improvement:

```text
stub_online_run: protocol fallback url applied via setUrl(String)
stub_online_run: protocol fallback ReaderProtocolTask.run url=https://newminerva-tgw.reader.qq.com/ChapBatAuthWithPD?bookId=58358897&type=0&tafauth=1&useindex=1&scids=1
server: url https://newminerva-tgw.reader.qq.com/ChapBatAuthWithPD?bookId=58358897&type=0&tafauth=1&useindex=1&scids=1
stub_online_run: protocol fallback ReadOnline.search result=com.qq.reader.common.protocol.ReadOnline$ReadOnlineResult
stub_online_run: protocol fallback returned resultCode=0
```

Remaining blocker:

```text
ReadOnlineFile[0] chapterId=1 path=.../58358897_1_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
ReadOnlineFile[0] chapterId=3 path=.../58358897_3_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
expected .eqct missing
```

On-device evidence:

```text
md5(chapter.q)      = 9d9b6321c8f07bc970df6b1a0918a556
md5(58358897_ALL_o) = 9d9b6321c8f07bc970df6b1a0918a556
58358897_1_s size=0
58358897_3_s size=0
```

Current conclusion:

- v156 fixed the `NoSuchFieldException: mUrl` issue.
- The fallback now reaches QQ Reader's own `ReaderProtocolTask.run()`.
- The chapter auth response still lacks usable body URLs/files. `resultCode=0`
  is not sufficient; `*_s` files are zero-byte and `.eqct` is absent.
- `58358897_ALL_o` is identical to `chapter.q`, so it must not be copied into
  `.eqct`.

Next work item:

```text
v157 = reverse or instrument the real OnlineChapterDownloadTask.run() equivalent.
```

Focus on how the original protected path obtains real URL-bearing metadata
(`ctebchaptercosurl`, `epubPureUrl`, `epubResourceUrl`) rather than changing
generic HTTP headers again.

## 2026-06-14 v157 Static Reverse Update

No new offline clone APK was built in this step. The work continued from the
verified v156 boundary and expanded local dex evidence only.

New local evidence files:

```text
.tmp\bookchapter-online-qdae.dump.txt
.tmp\bookchapter-search-qdaa.dump.txt
.tmp\OnlineChapterDownloadTask-inner1-v157.dump.txt
.tmp\v157-com-qq-reader-module-bookchapter-online-qdac.dump.txt
.tmp\v157-com-qq-reader-module-bookchapter-online-qdab.dump.txt
.tmp\v157-com-qq-reader-module-bookchapter-online-qdad.dump.txt
.tmp\v157-com-qq-reader-common-readertask-protocol-QueryBookIntroTask.dump.txt
.tmp\v157-com-qq-reader-common-readertask-protocol-QueryMediaBookInfoTask.dump.txt
.tmp\v157-com-qq-reader-common-readertask-protocol-QueryMediaBookIndexTask.dump.txt
.tmp\v157-com-yuewen-component-businesstask-ordinal-ReaderProtocolTask.dump.txt
```

Current evidence-based boundary:

- `bookchapter.search.qdaa` and `bookchapter.online.qdae` are metadata/index
  paths. They create `QueryBookIntroTask`, `QueryMediaBookInfoTask`, and
  `QueryMediaBookIndexTask`, then post through `ReaderTaskHandler`.
- The `Query*Task` classes are `ReaderProtocolJSONTask` subclasses and build
  JSON metadata URLs from `appconfig.qdae.*`; they are not the
  `ChapBatAuthWithPD` chapter body tar path.
- `OnlineChapterDownloadTask$1` is failure/retry/reporting support, not the
  successful chapter parser.
- `bookchapter.online.qdab.search(I)` is useful as a reference implementation
  for QQ Reader's own request parameters and tar extraction. It builds a rich
  request map (`qrsn`, `qrsn_new`, `safekey`, `trustedid`, `ywkey`, `ywguid`,
  `usid`, `uid`, `timi`, `mldt`, `sift`, `ibex`, etc.) and calls
  `com.yuewen.networking.http.qdaa.search(...)`, then extracts a tar into
  `OnlineTag.a()`.
- That `qdab.search(I)` path aligns with the observed `chapter.q` /
  `58358897_ALL_o` metadata output. It does not explain or produce usable
  `.eqct` chapter body files.

Do not repeat:

```text
DecodeWeakGlobal startup crash investigation
ReaderProtocolTask.mUrl reflection fix
copying *_ALL_o to .eqct
plain generic HTTP header experiments
```

Next concrete task:

```text
v157/v158 = instrument or recreate OnlineChapterDownloadTask.run() body path.
```

Implementation preference:

1. Add safe diagnostics around the fallback chapter request: URL, sanitized
   request keys, tar entry names/sizes, and URL-bearing field presence only.
2. Replace any remaining plain `URLConnection` path with the app's own
   `com.yuewen.networking.http.qdaa.search(...)` style, modeled after
   `bookchapter.online.qdab.search(I)`.
3. Continue native/Java boundary reversing for the protected
   `OnlineChapterDownloadTask.run()` implementation; the missing part is the
   real body request/callback chain, not startup or Activity virtualization.

## 2026-06-14 v158 TrustedId Validation

v158 package:

```text
.tmp\qqreader-c9f8-neutralized-v158-trustedid-online-tag-url-signed.apk
```

Runtime evidence:

```text
.tmp\qqreader-v158-trustedid-online-tag-url-start-logcat.txt
.tmp\qqreader-v158-trustedid-online-tag-url-read-logcat.txt
```

Verified:

- The offline clone installs and enters the reader page.
- `stub_online_run` obtains QQ Reader's runtime trusted id through
  `com.qq.reader.common.conn.search.qdad.search().judian()`.
- `OnlineTag.f(trustedId)` is actually used:

```text
stub_online_run: OnlineTag.f trustedId applied len=36 fallbackEmpty=0
```

Still failing:

```text
stub_online_run: protocol fallback returned resultCode=0
stub_online_run: post-search files expectedEqct=.../58358897/6.eqct expectedEqctSize=-1
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=6 path=.../58358897_6_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
stub_online_run: ReadOnlineResult success but no usable chapter file ... dispatching failed
```

Observed for book `58358897` chapters `6`, `11`, `31`, and for book
`58157841` chapters `1`, `2`, `5`, `46`, `63`.

Current blocker:

```text
The fallback can reach QQ Reader's protocol task and parse a nominal result,
but it still does not reproduce the protected native
OnlineChapterDownloadTask.run() body request. The response contains metadata
and zero-byte *_s placeholders, not chapter body .eqct/.eres files.
```

Current decision:

- Keep the state fallback and `run()` fallback as diagnostic scaffolding.
- Keep the `trustedId` fix; it matches QQ Reader's own Java request style.
- Do not treat `resultCode=0` as success unless a usable chapter body file or
  URL-bearing `ReadOnlineFile` exists.
- v159 should focus on the missing `OnlineChapterDownloadTask.run()` internals:
  `buildUrl(qdac)`, `obtainHeaders()`, `downloadChapterFile(String)`, and the
  native-created callback/task chain.

## 2026-06-14 v159 Runtime Evidence

v159 APK:

```text
.tmp\qqreader-c9f8-neutralized-v159-protocol-info-shape-signed.apk
```

Log:

```text
.tmp\qqreader-v159-protocol-info-shape-read-logcat.txt
```

What changed:

- `QqReaderOnlineProtocolFallback` now logs tar entry names/sizes and safe
  `info.txt` schema fields before passing the response into
  `ReadOnline.search(...)`.

Verified on device `100.110.94.8:41895`:

```text
stub_online_run: protocol fallback tar bytes=29696 entries=[58358897_ALL_o:25852, 58358897_34_s:0, info.txt:895]
stub_online_run: protocol fallback info array len=2 hasBodyUrl=0
safe=[code=0, message=OK, chapter_id=34, chapter_uuid=35, encode=1, paycheckmode=0, mediaFiles=[], multiModal=false, unlockCondition=0]
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=34 ... size=0 fileUrlEmpty=1 resourceUrlEmpty=1
```

Repeated shape:

```text
58358897 cid=20/34/40
57642138 cid=14/22/31
```

Current conclusion:

```text
ReaderProtocolTask + ReadOnline.search is working as far as it can. The
response being fed to it does not contain body URLs or body files.
```

Therefore the active blocker is no longer "parse the tar" or "pass trustedId".
The blocker is one level earlier:

```text
Find or restore the hidden protected request path that makes chapter auth return
ctebchaptercosurl / epubPureUrl / epubResourceUrl, or restores the original
OnlineChapterDownloadTask.run() implementation that downloads/materializes the
body itself.
```

Do not repeat for v160:

```text
plain ReaderProtocolTask URL retry
TtsPreloadChapterInfoTask URL retry
OnlineTag.f trustedId retry
copying *_ALL_o or zero-byte *_s
```

## 2026-06-15 QQ Reader 收敛总结 (v174-v177)

### 关键突破

v174-v177 打通了 QQ 阅读分身免费章节正文加载的完整链路：

| 版本 | 突破 | 证据 |
|------|------|------|
| v172 | `EasyEncrypt.decrypt` native 补齐 | `.eqct` 文件成功生成 (7757 bytes) |
| v173 | 定位 `.eqct` 被二次解密删除 | delete_stack 指向 `qdae.search` 失败删除 |
| **v174** | **`.eqct` 明文读取兼容** | **正文首次显示：截图确认 "重生成妖，我修成真龙"** |
| v175 | 标题修复 + 默认启用收敛 | 标题恢复正常显示 |
| v176 | 登录闪退定位 | `Theme.AppCompat` 主题缺失 |
| v177 | 登录主题早期修复 | `activity.setTheme(themeId)` 先于 AppCompat 检查 |

### v174 核心技术方案

```text
wxmini 免费章节接口 → 明文正文 → 写入 .eqct
  ↓
QqReaderEqctPlaintextCompat hook
  ↓
绕过原解密流程，直接返回 .eqct 字节
  ↓
阅读引擎正常渲染
```

### 当前状态

- 免费章节正文链路已连续可用：下载 → materialize → 保留 → 读取 → 渲染
- 无 FATAL EXCEPTION、无 .eqct 删除、无 UnsatisfiedLinkError
- 已验证多本书、多章节连续翻页

### 后续重点

1. 登录链路验证 (v177 待测)
2. 付费章节边界处理
3. 诊断开关收敛 (关闭冗余日志)
4. 冷启动 / 换书 / 未缓存章节稳定性
5. 离线 patch 脚本增加 manifest 主题重建步骤
