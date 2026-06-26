# QQ Reader Offline Clone Patch

This workflow is for isolating QQ Reader clone crashes from the MultiApp UI build path.
It starts from an already built clone APK, patches the embedded `assets/origin.apk`,
then repacks and signs the outer clone APK.

## Input

Use an existing QQ Reader clone APK as the outer shell:

```powershell
.\tools\qqreader-offline-patch\patch-qqreader-clone.ps1 `
  -InputCloneApk .tmp\patchedclone-base.apk `
  -OutputApk .tmp\qqreader-neutralized-signed.apk
```

For resumed work after context compression, use the checkpoint wrapper first.
It skips Gradle, APK extraction, and final repack when the expected artifacts
already exist:

```powershell
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v119-bootstrap-interface11
```

After changing runtime code, force the complete path explicitly:

```powershell
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v120-next `
  -Build `
  -ForceExtract `
  -ForceRepack
```

This avoids the common failure mode where a resumed session repeats a full
Gradle build, extracts a 400MB APK, and signs another clone even though the
previous artifact is already available under `.tmp`.

The script expects:

- `assets/origin.apk` inside the clone APK.
- `dexlib2` and `guava` jars in the local Gradle cache.
- Android build-tools with `zipalign.exe` and `apksigner.bat`.
- The default Android debug keystore at `%USERPROFILE%\.android\debug.keystore`.

## Patched Methods

The fixed target set is:

```text
com.qq.reader.ReaderApplication->initLoginSDK
com.qq.reader.ReaderApplication->initPushSDK
com.qq.reader.shortcut.ShortcutManager->cihai
com.qq.reader.abtest_sdk.qdab->cihai
com.bytedance.android.dy.sdk.pangle.ZeusPlatformUtils->initZeus
```

Return behavior after patch:

```text
void methods      -> return-void
primitive methods -> const/4 v0, 0; return v0
wide methods      -> const-wide/16 v0, 0; return-wide v0
object methods    -> const/4 v0, 0; return-object v0
```

## Output

The script writes a signed APK, by default:

```text
.tmp\qqreader-neutralized-signed.apk
```

Because the APK is signed with the local debug key, it cannot overwrite a clone
installed with a different signing certificate. Uninstall the existing clone with
the same package name before installing this APK.

## Verification

By default the script runs `InspectDexMethods` against the patched inner
`origin.apk` before signing the outer APK. The expected key instructions are:

```text
ReaderApplication.initPushSDK -> RETURN_VOID
ShortcutManager.cihai(Context) -> CONST_4, RETURN_OBJECT
ShortcutManager.cihai() -> RETURN_VOID
```

After install, diagnose with:

```powershell
$adb = 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe'
$pkg = '<patched-clone-package>'
& $adb shell am force-stop $pkg
& $adb logcat -c
& $adb shell am start -W -n "$pkg/com.qq.reader.activity.launch.DefaultAliasSplashActivity"
Start-Sleep -Seconds 10
& $adb logcat -d -v threadtime > .tmp\qqreader-offline-logcat.txt
& $adb shell dumpsys activity exit-info $pkg > .tmp\qqreader-offline-exit-info.txt
```

## Current Blocker

As of the v87 offline clone test, startup and the main shell can enter the UI,
but normal reading is still blocked by QQ Reader's native chapter download path.

Latest tested package:

```text
com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

Latest APKs and evidence:

```text
.tmp\qqreader-c9f8-neutralized-v86-bgstatefix-signed.apk
.tmp\qqreader-c9f8-neutralized-v87-online-state-stubs-signed.apk
.tmp\qqreader-v87-start-logcat.txt
.tmp\qqreader-v87-start-crash.txt
.tmp\qqreader-v87-start-exit-info.txt
.tmp\qqreader-v87-start-ui.xml
```

The v87 fallback registers state/helper native methods on
`com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask`, but the real
worker method is still not bound:

```text
java.lang.UnsatisfiedLinkError:
No implementation found for void
com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run()
```

Do not stub `OnlineChapterDownloadTask.run()` if the goal is normal reading:
a no-op avoids the crash but leaves chapters unloaded. The root issue to solve
is the real native library failing to register `run()` against the guest
ClassLoader/runtime class.

## 2026-06-12 QQ Reader Sign Cleanup

The offline `-PatchFockSign` path rewrites QQ Reader's
`com.yuewen.fock.Fock.sign(String)` to `QqReaderSignCompat.sign(String)`.
This helper now treats `com.yuewen.fockrt.FockRT.sn(String)` as the only normal
signing path.

Diagnostic MD5 fallback is disabled by default because it can avoid crashes
while still producing invalid request signatures and causing BookCity/chapter
network failures. Enable it only for controlled diagnosis with:

```text
-Dmultiapp.qqreader.sign.diagnostic_md5=true
```

The native `Fock.sn([BI)` wrapper also calls the original implementation by
default. Its diagnostic MD5 fallback requires:

```text
debug.multiapp.fock.diagnostic_md5=1
```

## 2026-06-13 Chapter Run Checkpoint

Current task: make QQ Reader chapter body loading work in the offline clone.
Do not restart from the old startup-crash or `scids=0` investigations after
context compression.

Latest reproducible blocker:

```text
OnlineChapterDownloadTask.run()
```

Important verified facts:

- `state_fallback=1` is still needed as a scheduling support layer. Disabling
  it stops the app earlier at methods such as `setBackgroundRun(boolean)`.
- A no-op `run()` is not a fix. It only avoids a crash and leaves chapter
  content empty.
- v151/v152 proved that direct `ChapBatAuthWithPD` fallback is insufficient:

```text
ReadOnline.search resultCode=0
ReadOnlineFile chapterId=<cid>
path=.../<bookId>_<cid>_s size=0
fileUrlEmpty=1 resourceUrlEmpty=1
expected .eqct missing
```

- v152 fixed the false-success path: `stub_online_run` must not dispatch
  `getBookSucces` unless a usable non-zero chapter file exists or is
  materialized from a real downloadable `ReadOnlineFile`.
- Paid/VIP chapters still correctly surface `resultCode=-9`; the active test
  target is free chapters that return empty metadata.

Current evidence files:

```text
.tmp\qqreader-v151-online-info-json-diag-read-logcat.txt
.tmp\qqreader-v152-no-fake-success-with-empty-readonlinefile-read-logcat.txt
.tmp\qqreader-v152-no-fake-success-with-empty-readonlinefile-click-logcat.txt
.tmp\OnlineChapterDownloadTask-current-verbose.dump.txt
.tmp\OnlineChapterDownloadTask-inner1.dump.txt
.tmp\OnlineChapterDownloadTask-inner2.dump.txt
.tmp\OnlineChapterDownloadTask-inner3.dump.txt
.tmp\OnlineChapterDownloadTask-inner4.dump.txt
.tmp\OnlineChapterDownloadTask-inner5.dump.txt
.tmp\refs-ReadOnline-search.txt
.tmp\refs-ReaderProtocolTask.txt
.tmp\com-yuewen-component-businesstask-ordinal-ReaderDownloadTask.dump.txt
.tmp\com-yuewen-component-businesstask-ordinal-ReaderProtocolJSONTask.dump.txt
.tmp\ReaderProtocolTask.dump.txt
```

Post-v152 reverse notes:

- `ReadOnline.search(InputStream, OnlineTag, String)` has no normal Java caller
  outside its own helper path; current fallback calls it manually.
- `OnlineChapterDownloadTask$3/$4/$5.onConnectionRecieveData(...)` call
  `ReadOnline.search(ReadOnlineResult, paypageModel)`, but those callbacks are
  login/risk/pay-page handlers, not the raw chapter tar parser.
- Constructors for `OnlineChapterDownloadTask$1/$3/$4/$5` have no Java
  reference, so they are likely instantiated by native `run()` through
  JNI/reflection.
- `ReaderDownloadTask(Context, url, path)` is not the right replacement for
  chapter auth. Its `run()` calls:

```text
com.yuewen.networking.http.qdaa.search(
  url, null, "GET", null, null, null, null, timeout, timeout
)
```

  That means it does not automatically apply `ReaderProtocolTask` basic headers
  or interceptors; it is roughly an OkHttp wrapper around the same incomplete
  direct URL fetch.
- `ReaderProtocolTask.run()` does build the richer path:

```text
initBasicHeader(...)
refreshHeader(...)
getApplicationInterceptor()
getNetworkInterceptor()
```

  But the base class has no useful URL setter. The next useful target is a
  concrete `ReaderProtocolTask` subclass that can carry an arbitrary URL or the
  original native creation path for the chapter auth task.
- `TtsPreloadChapterInfoTask` is a relevant but insufficient reference. It is a
  `ReaderProtocolJSONTask` subclass and builds:

```text
https://<host>/ChapBatAuthWithPD?bookId=<bid>&vipVoice=<voiceType>&scids=<cid>&adState=1
```

  PC-side checks against the same free chapter showed that `base`,
  `adState=1`, and `vipVoice=0/1` variants all still produce `info.txt`
  without `ctebchaptercosurl`, `epubPureUrl`, or `epubResourceUrl`.
  Therefore `vipVoice/adState` is not the missing normal-reading auth path.
  Evidence:

```text
.tmp\chap-auth-variants\
.tmp\TtsPreloadChapterInfoTask.dump.txt
```

Next execution direction:

1. Do not use `ReaderDownloadTask` as the chapter fix unless logs prove it adds
   the missing auth/session/sign fields.
2. Find a concrete `ReaderProtocolTask` subclass used for URL-based GET tasks,
   or identify how native `OnlineChapterDownloadTask.run()` creates the
   protocol task and callback.
3. Prefer restoring original protected native registration of
   `OnlineChapterDownloadTask.run()` if that becomes reproducible; otherwise
   implement only a faithful Java/C++ equivalent that obtains real
   `ctebchaptercosurl`/`epubPureUrl`/`epubResourceUrl` metadata and writes
   non-zero `.eqct/.eres` files.

## 2026-06-13 v153 Pending Device Validation

v153 has been built locally but has not been validated on a device yet because
`adb devices` currently shows no online device.

Built APK:

```text
.tmp\qqreader-c9f8-neutralized-v153-direct-fetch-with-qqreader-headers-signed.apk
```

Code change under test:

- `stub_online_run` direct fallback now calls QQ Reader's own request header
  provider:

```text
com.qq.reader.common.readertask.ordinal.qdaa.search()
```

- The fallback applies the returned headers to `URLConnection` before calling
  `ReadOnline.search(InputStream, OnlineTag, String)`.
- Logs intentionally print only header count/key names, not header values.

Important test-script correction:

- `tools\qqreader-offline-patch\test-qqreader-offline.ps1` now defaults to the
  current chapter-body fallback path:

```text
debug.multiapp.online.state_fallback=1
debug.multiapp.online.run_fallback=1
debug.multiapp.online.materialize_eqct=0
debug.multiapp.online.failure_callback=0
debug.multiapp.stubapp.fallback=core
```

- To run an original-native comparison later, explicitly pass
  `-OnlineRunFallback 0 -StubAppFallback 0`.

Next device command when wireless debugging is available:

```powershell
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 `
  -Connect <ip:port> `
  -VersionTag v153-direct-fetch-with-qqreader-headers `
  -WaitSeconds 45
```

After startup, open a known free chapter and capture a click/read window. The
evidence to check is:

```text
stub_online_run: applied QQReader request headers count=...
ReadOnline.search returned resultCode=0
ReadOnlineFile ... fileUrlEmpty=0
ReadOnlineFile ... resourceUrlEmpty=0
expected .eqct ... size>0
```

If v153 still returns `fileUrlEmpty=1 resourceUrlEmpty=1`, the missing piece is
not simple QQ Reader common headers. Continue with one of these two paths:

1. restore the original protected native registration for
   `OnlineChapterDownloadTask.run()`;
2. reverse how native `run()` builds the chapter auth task/callback and
   reproduce that path instead of direct `URLConnection`.

## 2026-06-13 v154 ReaderProtocolTask Fallback

v154 implements the next fallback step after v153:

- New helper:

```text
core\hook\src\main\java\com\multiapp\core\hook\QqReaderOnlineProtocolFallback.java
```

- Native registration now captures the `NativeHookBridge` classloader so
  `stub_online_run` can load the helper even though `run()` is registered on a
  guest QQ Reader class.
- `stub_online_run` now tries this order:

```text
1. ReaderProtocolTask fallback
2. direct URLConnection fallback from v153
```

The ReaderProtocolTask fallback does this through reflection:

```text
new ReaderProtocolTask()
set ReaderProtocolTask.mUrl = current ChapBatAuthWithPD URL
Proxy(com.yuewen.component.task.ordinal.qdab)
ReaderProtocolTask.run()
listener search(task, InputStream, length) -> ReadOnline.search(InputStream, OnlineTag, url)
```

This path should reuse QQ Reader's own `ReaderProtocolTask.run()` network stack:

```text
initBasicHeader(...)
refreshHeader(...)
getApplicationInterceptor()
getNetworkInterceptor()
com.yuewen.networking.http.qdaa.search(...)
```

It still does not fake success. Existing `stub_online_run` checks remain in
place:

```text
resultCode must be 0
ReadOnlineResult must contain a usable chapter file
expected .eqct must be non-zero, or a real ReadOnlineFile URL must be materialized
```

Built APK:

```text
.tmp\qqreader-c9f8-neutralized-v154-readerprotocol-fallback-signed.apk
```

Local build result:

```text
build-qqreader-offline.ps1 v154-readerprotocol-fallback: success
```

Device validation status:

```text
adb devices: no online devices
```

Next command after wireless debugging is available:

```powershell
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 `
  -Connect <ip:port> `
  -VersionTag v154-readerprotocol-fallback `
  -WaitSeconds 45
```

Then open a known free chapter and capture the read window. Required evidence:

```text
remember_hook_classloader: hook ClassLoader captured
stub_online_run: attempting ReaderProtocolTask ReadOnline fetch
stub_online_run: protocol fallback ReaderProtocolTask.run url=...
stub_online_run: protocol fallback ReadOnline.search result=...
stub_online_run: protocol fallback returned resultCode=...
stub_online_run: post-search files expectedEqct=...
```

If v154 still returns empty `ReadOnlineFile` data, the next step is no longer
manual headers or generic HTTP. The remaining root problem is the protected
native `OnlineChapterDownloadTask.run()` path: it either adds hidden request
parameters/signatures before the auth request, or uses a different callback
chain than the one reconstructed here.

## 2026-06-14 v154 Device Regression

A MultiApp-generated QQ Reader clone was tested on:

```text
device=100.110.94.8:41895
package=com.qq.reader.clonestub_a536097086ad4b60bd869d38d402d44f
```

Captured evidence:

```text
.tmp\qqreader-clone-20s-20260614-092101-logcat.txt
.tmp\qqreader-clone-20s-20260614-092101-crash.txt
.tmp\qqreader-clone-20s-20260614-092101-exit-info.txt
```

The app crashes before chapter-body validation:

```text
Fatal signal 11 (SIGSEGV)
reason=5 (APP CRASH(NATIVE)) status=11
#00 libart.so art::JavaVMExt::DecodeWeakGlobal
#03 libmultiapp-native.so _JNIEnv::CallObjectMethod
#04 libmultiapp-native.so Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterDownloadFallbackStubs+180
```

Interpretation:

- v154 cannot currently be judged at the `ReaderProtocolTask` chapter fallback
  layer because the clone dies first during fallback native registration.
- The immediate fix is to make
  `nativeRegisterOnlineChapterDownloadFallbackStubs()` use a local,
  exception-checked `ClassLoader.loadClass` lookup instead of any cached/global
  classloader method path.
- After that startup crash is fixed, rebuild/install and retest. Only if the
  app reaches a free chapter should the logs be evaluated for:

```text
stub_online_run: attempting ReaderProtocolTask ReadOnline fetch
stub_online_run: protocol fallback ReaderProtocolTask.run url=...
stub_online_run: protocol fallback ReadOnline.search result=...
```

The installed MultiApp-generated clone was also checked with `run-as`:

```text
run-as: package not debuggable
```

This means the current installed clone is not useful for convenient file/cache
inspection. Rebuild the current MultiApp APK, regenerate the QQ Reader clone
once, and verify `run-as` before further repeated tests.

Fix status:

```text
core/hook/src/main/cpp/native-hook.cpp
```

The native class-loading paths introduced for v154 were narrowed:

- `nativeRegisterOnlineChapterDownloadFallbackStubs()` now uses a local,
  exception-checked `ClassLoader.loadClass` lookup.
- `read_online_search_via_reader_protocol()` loads
  `QqReaderOnlineProtocolFallback` with the same safe helper.
- The older global `g_classloader_loadclass` path is left in place for existing
  guest-ClassLoader code, but it is no longer used by the two v154 paths that
  were implicated in the tombstone.

Build/install verification:

```text
:core:hook:assembleDebug = BUILD SUCCESSFUL
:app:assembleDebug = BUILD SUCCESSFUL
installed com.multiapp.app on 100.110.94.8:41895
com.multiapp.app lastUpdateTime=2026-06-14 09:37:01
```

Next required test:

1. Regenerate the QQ Reader clone from the updated MultiApp.
2. Verify the generated clone is debuggable with `run-as`.
3. Capture a startup log and confirm the previous tombstone is gone:

```text
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterDownloadFallbackStubs+180
libart.so art::JavaVMExt::DecodeWeakGlobal
```

## 2026-06-14 v155 Offline Clone Test

Offline APK:

```text
.tmp\qqreader-c9f8-neutralized-v155-jni-instance-classloader-fix-signed.apk
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
device=100.110.94.8:41895
install=Success
```

Captured evidence:

```text
.tmp\qqreader-v155-offline-start-20260614-100606-logcat.txt
.tmp\qqreader-v155-offline-start-20260614-100606-crash.txt
.tmp\qqreader-v155-offline-start-20260614-100606-exit-info.txt
.tmp\qqreader-v155-offline-start-20260614-100606-amstart.txt
```

Startup result:

```text
Status: ok
Activity: com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8/com.qq.reader.activity.launch.DefaultAliasSplashActivity
pidof com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 = 21678
crash buffer = empty
```

The v154/v155 JNI classloader crash is cleared for this offline build:

```text
remember_hook_classloader: hook ClassLoader captured
nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback registered
nativeRegisterOnlineChapterDownloadFallbackStubs: registered 15 methods
```

No new `Fatal signal 11` / `DecodeWeakGlobal` tombstone appeared in this run.

Chapter-path result:

```text
stub_online_run: attempting ReaderProtocolTask ReadOnline fetch ... scids=1
stub_online_run: protocol fallback failed java.lang.NoSuchFieldException: No field mUrl in class Lcom/yuewen/component/businesstask/ordinal/ReaderProtocolTask;
stub_online_run: ReaderProtocolTask fetch failed; falling back to direct URLConnection
stub_online_run: applied QQReader request headers count=34
stub_online_run ReadOnline.search threw; falling back
stub_online_run: ReadOnline.search failed ok=0 result=null
stub_online_run: no usable existing ReadOnlineResult; real network implementation still missing
```

File-system evidence:

```text
/sdcard/Android/data/com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8/files/QQReader/Online/58358897/book.meta
/sdcard/Android/data/com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8/files/QQReader/Online/58358897/chapter.q
/sdcard/Android/data/com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8/files/QQReader/Online/58358897/adv.m
/sdcard/Android/data/com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8/files/QQReader/Online/58358897/{"code": -1, "msg": "deny", "version":""}
```

There is still no `1.eqct` / `2.eqct` output. The current blocker is no longer
startup stability; it is the reconstructed chapter request path. The
`ReaderProtocolTask` reflection currently assumes a field named `mUrl`, but this
QQ Reader build has no such field on
`com.yuewen.component.businesstask.ordinal.ReaderProtocolTask`. The direct
URLConnection fallback reaches QQ Reader's parser but receives/produces a deny
payload, so it cannot produce a usable `ReadOnlineResult`.

Debuggability:

```text
run-as: package not debuggable: com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

Next step:

1. Dump `ReaderProtocolTask` and its concrete subclasses/constructor fields from
   the current origin dex.
2. Replace the hard-coded `mUrl` reflection in
   `QqReaderOnlineProtocolFallback.fetch(...)` with the actual URL field or a
   concrete request task class.
3. Rebuild as v156 and retest only the chapter path. Do not repeat the
   `DecodeWeakGlobal` startup investigation unless a new tombstone proves it
   regressed.

## 2026-06-14 v156 ReaderProtocolTask setUrl Result

Code change:

```text
core\hook\src\main\java\com\multiapp\core\hook\QqReaderOnlineProtocolFallback.java
```

`QqReaderOnlineProtocolFallback.fetch(...)` no longer writes
`ReaderProtocolTask.mUrl` directly. The current QQ Reader runtime exposes
`setUrl(String)` / `getUrl()` and the direct field reflection failed on-device.
The fallback now uses:

```text
ReaderProtocolTask.setUrl(String)
ReaderProtocolTask.getUrl()
```

with the old `mUrl` field path kept only as a compatibility fallback.

Build/test:

```text
:core:hook:assembleDebug = BUILD SUCCESSFUL
apk=.tmp\qqreader-c9f8-neutralized-v156-readerprotocol-seturl-signed.apk
device=100.110.94.8:41895
install=Success
```

Captured evidence:

```text
.tmp\qqreader-v156-readerprotocol-seturl-start-20260614-102533-logcat.txt
.tmp\qqreader-v156-readerprotocol-seturl-start-20260614-102533-crash.txt
.tmp\qqreader-v156-readerprotocol-seturl-start-20260614-102533-exit-info.txt
```

Startup/crash status:

```text
crash buffer = empty
exit-info contains only force-stop/package-update/system isolated-process exits
nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback registered
nativeRegisterOnlineChapterDownloadFallbackStubs: registered 15 methods
```

Chapter-path improvement:

```text
stub_online_run: attempting ReaderProtocolTask ReadOnline fetch ... scids=1
stub_online_run: protocol fallback url applied via setUrl(String)
stub_online_run: protocol fallback ReaderProtocolTask.run url=https://newminerva-tgw.reader.qq.com/ChapBatAuthWithPD?bookId=58358897&type=0&tafauth=1&useindex=1&scids=1
server: url https://newminerva-tgw.reader.qq.com/ChapBatAuthWithPD?bookId=58358897&type=0&tafauth=1&useindex=1&scids=1
stub_online_run: protocol fallback ReadOnline.search result=com.qq.reader.common.protocol.ReadOnline$ReadOnlineResult
stub_online_run: protocol fallback returned resultCode=0
```

This proves the v155 `NoSuchFieldException: mUrl` blocker is fixed and the
request now goes through QQ Reader's own `ReaderProtocolTask.run()` network
stack.

Remaining chapter blocker:

```text
stub_online_run: direct-fetch ReadOnlineResult resultCode=0 filesSize=1 effectiveCid=1
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=1 path=.../58358897_1_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
stub_online_run: ReadOnlineResult success but no usable chapter file allow=0 materialized=0 hasUsable=0 expectedEqct=.../1.eqct expectedEqctSize=-1
```

The same shape repeated for `scids=3`.

On-device file evidence:

```text
58358897_1_s size=0
58358897_3_s size=0
58358897_ALL_o size=25852
chapter.q size=25852
md5(chapter.q)       = 9d9b6321c8f07bc970df6b1a0918a556
md5(58358897_ALL_o)  = 9d9b6321c8f07bc970df6b1a0918a556
md5(58358897_1_s)    = d41d8cd98f00b204e9800998ecf8427e
md5(58358897_3_s)    = d41d8cd98f00b204e9800998ecf8427e
```

Interpretation:

- `58358897_ALL_o` is identical to `chapter.q`; it is chapter list metadata, not
  a usable chapter body source.
- `ReadOnline.search(...)` can parse the response into a nominal
  `ReadOnlineResult resultCode=0`, but the resulting `ReadOnlineFile` has no
  usable file URL/resource URL and writes only zero-byte `*_s` files.
- Therefore v156 fixed the Java protocol-task construction error, but did not
  restore normal chapter content.

Do not repeat:

- Do not return to the `mUrl` reflection issue; it is fixed by `setUrl(String)`.
- Do not copy `*_ALL_o` to `.eqct`; v151-v156 prove it is metadata, not body.
- Do not treat `resultCode=0` alone as success. A usable chapter requires a
  non-zero expected file or real `ReadOnlineFile` URL/materialization path.

Next v157 direction:

1. Reverse the original `OnlineChapterDownloadTask.run()` path further, focused
   on how it obtains real `ctebchaptercosurl` / `epubPureUrl` /
   `epubResourceUrl` metadata.
2. Inspect the chapter-related Java helpers referenced in the current dex:
   `com.qq.reader.module.bookchapter.online.qdae`,
   `com.qq.reader.module.bookchapter.search.qdaa`, and
   `OnlineChapterDownloadTask$1/$3/$4/$5`.
3. If staying with fallback, add a diagnostic capture around the exact
   `InputStream` passed from `ReaderProtocolTask.onFinish(...)` to
   `ReadOnline.search(...)`, recording only tar entry names/sizes and whether
   URL-bearing fields exist. Do not log or dump chapter body text.

## 2026-06-14 v157 Static Reverse Progress

No new APK was built in this step. This pass continued from v156 and only
expanded local dex evidence, using `.tmp\origin-dex-v153` as the known-good dex
source. Do not use `.tmp\v129-origin.apk` for these classes; earlier attempts
against that source returned `CLASS_NOT_FOUND`.

New dump files:

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

Findings:

- `com.qq.reader.module.bookchapter.search.qdaa` creates
  `QueryBookIntroTask` and posts it through `ReaderTaskHandler.addTask(...)`.
  It is a book/chapter metadata refresh path, not the chapter body downloader.
- `com.qq.reader.module.bookchapter.online.qdae` creates
  `QueryBookIntroTask`, `QueryMediaBookInfoTask`, and
  `QueryMediaBookIndexTask`. These update online book metadata and indexes.
- `QueryBookIntroTask`, `QueryMediaBookInfoTask`, and
  `QueryMediaBookIndexTask` are `ReaderProtocolJSONTask` subclasses. Their
  constructors build JSON metadata URLs from `appconfig.qdae.bH`,
  `appconfig.qdae.bI`, and `appconfig.qdae.ab`; they are not the
  `ChapBatAuthWithPD` tar body request.
- `OnlineChapterDownloadTask$1` is an error/retry/reporting callback. It calls
  `OnlineChapterDownloadTask.access$000/access$300` and records
  `PullChapter` failures; it is not the success parser.
- `com.qq.reader.module.bookchapter.online.qdac` is a metadata/cache model. It
  parses fields such as `downloadinfo`, `downloadUrl`, `cteb`, `txt`, price,
  discount, and chapter count from JSON. The current dump does not show direct
  parsing of `ctebchaptercosurl`, `epubPureUrl`, or `epubResourceUrl`.
- `com.qq.reader.module.bookchapter.online.qdab.search(I)` is the clearest Java
  implementation of QQ Reader's own directory tar download path. It builds a
  request map containing values such as `qqnum`, `loginType`, `qrsn`,
  `qrsn_new`, `safekey`, `trustedid`, `ywkey`, `ywguid`, `usid`, `uid`, `timi`,
  `mldt`, `sift`, and `ibex`; it then calls:

```text
com.yuewen.networking.http.qdaa.search(
    String url,
    byte[] body,
    String method,
    HashMap headersOrParams,
    String contentType,
    List requestInterceptors,
    List networkInterceptors,
    long connectTimeout,
    long readTimeout
)
```

  The response is parsed as a tar archive and written under `OnlineTag.a()`.
  This matches the observed `chapter.q` / `*_ALL_o` metadata files. It does not
  by itself explain missing `.eqct` body files.

Updated conclusion:

- v156 already proved `ReaderProtocolTask.setUrl(String)` works and reaches QQ
  Reader's network stack, but the response still materializes only metadata and
  zero-byte `*_s` files.
- v157 static evidence narrows the remaining blocker to the protected/native
  `OnlineChapterDownloadTask.run()` body path or an equivalent Java recreation
  of that exact path.
- The `bookchapter.online/search` Java helpers are useful references for QQ
  Reader's request signing/session parameters and tar extraction style, but they
  are not a drop-in replacement for chapter body download.

Next implementation step:

1. Add safe diagnostics around the fallback chapter path to log only:
   request URL, sanitized request keys, tar entry names/sizes, and whether
   URL-bearing fields are present. Do not log or dump chapter text.
2. If staying in Java fallback, reuse the `com.yuewen.networking.http.qdaa`
   request style from `bookchapter.online.qdab.search(I)` instead of plain
   `URLConnection`, then pass the resulting tar stream into
   `ReadOnline.search(InputStream, OnlineTag, String)`.
3. In parallel, continue reversing the original native
   `OnlineChapterDownloadTask.run()` to identify how it builds the real body
   request and callbacks. Do not repeat `mUrl`, `DecodeWeakGlobal`, or
   `*_ALL_o -> .eqct` experiments.

## 2026-06-14 v158 TrustedId Fallback Result

v158 was built and installed as:

```text
.tmp\qqreader-c9f8-neutralized-v158-trustedid-online-tag-url-signed.apk
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
device=100.110.94.8:41895
```

Code change:

```text
core\hook\src\main\cpp\native-hook.cpp
```

- Added `get_qqreader_trusted_id(JNIEnv*)`.
- Replaced the empty `OnlineTag.f("")` argument with the runtime value from:

```text
com.qq.reader.common.conn.search.qdad.search().judian()
```

Evidence from `.tmp\qqreader-v158-trustedid-online-tag-url-read-logcat.txt`:

```text
stub_online_run: OnlineTag.f trustedId applied len=36 fallbackEmpty=0
stub_online_run: protocol fallback returned resultCode=0
stub_online_run: post-search files expectedEqct=.../58358897/6.eqct expectedEqctSize=-1 ... allSize=25852
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=6 path=.../58358897_6_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
stub_online_run: ReadOnlineResult success but no usable chapter file ... dispatching failed
```

The same shape repeats for chapters `11` and `31` of book `58358897`, and for
multiple chapters of book `58157841`.

Conclusion:

- Passing `trustedId` into `OnlineTag.f(...)` fixed a real mismatch with
  QQ Reader's Java request style, but it is not the missing chapter body step.
- The current `ReaderProtocolTask.run()` + `ReadOnline.search(...)` fallback
  still receives/parses metadata only. It creates zero-byte `*_s` files and no
  `.eqct` / `.eres` body files.
- `ReadOnlineResult resultCode=0` is still not sufficient. A successful chapter
  load must be validated by non-zero body files or URL-bearing
  `ReadOnlineFile` fields.

Do not repeat:

```text
OnlineTag.f("") vs trustedId
ReaderProtocolTask.setUrl(String)
DecodeWeakGlobal
copy *_ALL_o to .eqct
generic header/URLConnection experiments
```

Next target:

```text
v159 = identify or recreate the missing OnlineChapterDownloadTask.run() body request.
```

The next useful work is to inspect how the protected native path uses
`buildUrl(qdac)`, `obtainHeaders()`, `downloadChapterFile(String)`, and the
`OnlineChapterDownloadTask$3/$4/$5` callback chain. The fallback must stop
assuming the visible `ChapBatAuthWithPD?...scids=<cid>` tar response is the
final body response; v158 proves it is not enough.

## 2026-06-14 v159 Protocol Response Shape

v159 package:

```text
.tmp\qqreader-c9f8-neutralized-v159-protocol-info-shape-signed.apk
```

Code change:

```text
core\hook\src\main\java\com\multiapp\core\hook\QqReaderOnlineProtocolFallback.java
```

- The `ReaderProtocolTask` listener now copies the returned `InputStream` into
  memory, logs only tar entry names/sizes and safe `info.txt` field names, then
  passes a `ByteArrayInputStream` into `ReadOnline.search(...)`.
- It does not print token values or chapter body text.

Build/install:

```text
build OK
install Success
device=100.110.94.8:41895
pid=14148
```

Evidence:

```text
.tmp\qqreader-v159-protocol-info-shape-read-logcat.txt
```

Representative log:

```text
stub_online_run: protocol fallback tar bytes=29696 entries=[58358897_ALL_o:25852, 58358897_34_s:0, info.txt:895]
stub_online_run: protocol fallback info array len=2 hasBodyUrl=0
item0 safe=[code=0, message=ok, free=0, restype=0]
item1 safe=[code=0, message=OK, chapter_id=34, chapter_uuid=35, encode=1, paycheckmode=0, mediaFiles=[], multiModal=false, unlockCondition=0]
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=34 path=.../58358897_34_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
```

The same shape was observed across multiple chapters/books:

```text
58358897: cid=20,34,40
57642138: cid=14,22,31
```

Conclusion:

- The current fallback is not failing because `ReadOnline.search(...)` cannot
  parse the response. It parses the response correctly.
- The response itself lacks the body URL fields:

```text
ctebchaptercosurl
epubPureUrl
epubResourceUrl
```

- The visible `ChapBatAuthWithPD?bookId=...&type=0&tafauth=1&useindex=1&scids=<cid>`
  path returns chapter authorization metadata plus zero-byte `*_s`
  placeholders, not usable chapter body content.
- `code=0` and `paycheckmode=0` do not mean the body file is available. The
  actual pass criterion remains a non-zero `.eqct/.eres` or URL-bearing
  `ReadOnlineFile`.

Additional device check:

```text
com.qq.reader is installed.
/sdcard/Android/data/com.qq.reader/files/QQReader/Online exists.
No comparable .eqct/.eres/*_s files were found there during this pass.
```

Next target:

```text
v160 = stop improving the visible metadata fallback and focus on restoring the
original native OnlineChapterDownloadTask.run() implementation, or identify the
hidden request/sign/e2ee input that makes ChapBatAuthWithPD return body URLs.
```

## 2026-06-14 External Qidian Source Note

Checked external source:

```text
https://www.yckceo.com/yuedu/shuyuan/json/id/7026.json
```

This is a Legado/Yuedu-style book source for Qidian web pages. It defines
routes such as:

```text
bookSourceUrl=https://m.qidian.com#我的书架
tocUrl=https://vipreader.qidian.com/ajax/book/category?...&bookId=...
chapterUrl=https://vipreader.qidian.com/chapter/<bid>/<chapterId>/
content rule=class.read-content@class.content-wrap@text
```

Usefulness:

- Useful as a reference for an external web-scraping fallback or for checking
  whether Qidian web login/cookie access works.
- Useful evidence that the public/web reading path is different from QQ
  Reader's protected app path.
- It highlights a bridge field already present in v159 app responses:
  `chapter_ccid`. The web source constructs chapter pages as:

```text
https://vipreader.qidian.com/chapter/<bookId>/<chapter.id>/
```

  In v159 `info.txt`, `chapter_ccid` has the same shape as the web
  `chapter.id` value. This may be useful for a separate WebView/browser
  fallback for free chapters.

Not a direct fix:

- It does not use QQ Reader's `OnlineChapterDownloadTask`.
- It does not produce QQ Reader `.eqct/.eres` files.
- It does not expose `ctebchaptercosurl`, `epubPureUrl`, or
  `epubResourceUrl`.
- It cannot by itself restore normal QQ Reader in-app chapter loading.
- A direct PC request to
  `https://vipreader.qidian.com/chapter/<bookId>/<chapter_ccid>/` returned
  HTTP `202` with no `read-content` / `content-wrap` markers, so it likely
  requires the browser/WebView/cookie/runtime environment expected by Qidian.

Decision:

Keep this as a side reference only. It suggests a possible later fallback:
after `ChapBatAuthWithPD` yields `chapter_ccid`, open/fetch the Qidian WebView
chapter route for free chapters and parse `read-content`. That would be a
separate compatibility path, not normal QQ Reader runtime restoration. The main
v160 path remains restoring the original protected native chapter downloader or
identifying the hidden app request/sign/e2ee inputs that make QQ Reader's app
endpoint return body URLs.

## 2026-06-14 External QQ Reader Source 6016

Checked external source:

```text
https://www.yckceo.com/yuedu/shuyuan/json/id/6016.json
```

This one is materially more useful than source `7026`. It is a QQ Reader source
using public/mobile endpoints:

```text
bookSourceName=QQ阅读[男频]
bookSourceUrl=https://ubook.reader.qq.com/
tocUrl=https://ubook.reader.qq.com/api/book/chapter-list?bid={{$.introinfo.book.id}}
chapterUrl=https://wxmini.reader.qq.com/api/chapter/content?bid=<bid>&cid=<seq>
content=$.data.content
```

Local structural validation, without printing chapter text:

```text
https://ubook.reader.qq.com/api/book/chapter-list?bid=58358897
  code/data/isLogin/lskey/skey/stGuest
  sample seq=1 free=true

https://wxmini.reader.qq.com/api/chapter/content?bid=58358897&cid=34
  code=0 msg=success
  data keys include content, ccid, chapterId, auth, authType, fockEncrypt
  content_len=2419
  auth=1 authType=0 fockEncrypt=false
  ccid=95172115669516185 chapterId=34

https://wxmini.reader.qq.com/api/chapter/content?bid=57642138&cid=14
  code=0 msg=success
  content_len=2340
  auth=1 authType=0 fockEncrypt=false
  ccid=93296353412706489 chapterId=14
```

Important correlation:

- v159 `ChapBatAuthWithPD` metadata returned `chapter_ccid` values.
- The `wxmini.reader.qq.com/api/chapter/content` response returns matching
  `ccid` values for the same `bid/cid`.
- Therefore the v159 metadata fallback already gives enough identifiers to
  call the public QQ Reader mini endpoint for free chapters.

What this can solve:

- It can become a compatibility fallback for free chapters when the protected
  native downloader is unavailable.
- It is much more actionable than the Qidian web source because it returns JSON
  directly with `data.content`.

What it does not solve by itself:

- It still does not restore the original QQ Reader
  `OnlineChapterDownloadTask.run()` native implementation.
- It does not tell us the `.eqct/.eres` binary/encryption format.
- It may not work for paid/VIP chapters without the proper QQ Reader account
  state/cookie/session.

New implementation branch:

```text
v160-mini-content-fallback:
  if ReaderProtocolTask/ReadOnline.search returns no body URL and no .eqct,
  call https://wxmini.reader.qq.com/api/chapter/content?bid=<bid>&cid=<cid>,
  parse JSON data.content/title/auth/fockEncrypt,
  then test the smallest safe integration path.
```

Open design question for v160:

- If `.eqct` is plain text or a lightly wrapped text format, write
  `data.content` to the expected chapter file and dispatch success.
- If `.eqct` requires QQ Reader encryption/serialization, do not fake success;
  instead use this endpoint as a separate reader-content provider or continue
  restoring the original native writer.

## 2026-06-14 Original App Comparison And v160 Diagnostic

Original QQ Reader was captured on device `100.110.94.8:41895`:

```text
package=com.qq.reader
pid=28451
topActivity=com.qq.reader/.activity.ReaderPageActivity
log=.tmp/qqreader-original-v160-capture-logcat-2.txt
```

Observed original app behavior for `bookId=58358897`:

```text
server: url https://androidtgw.reader.qq.com/v7_6_6/queryChapterLoad?bid=58358897
QROnlineFileProvider: /storage/emulated/0/Android/data/com.qq.reader/files/QQReader/Online/58358897/20.eqct
server: url https://androidtgw.reader.qq.com/v7_6_6/chapterOver?bid=58358897&cid=20&chapterUuid=20&showScore=0&bookType=0
```

Original app local storage contains real chapter files:

```text
/sdcard/Android/data/com.qq.reader/files/QQReader/Online/58358897/
  1.eqct ... 43.eqct
  chapter.q
  book.meta
  adv.m
```

The offline clone storage for the same book still only contains metadata and
zero-byte placeholders:

```text
/sdcard/Android/data/com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8/files/QQReader/Online/58358897/
  58358897_ALL_o
  58358897_<cid>_s size=0
  chapter.q
  book.meta
  adv.m
  no <cid>.eqct
```

Pulled original files for format comparison:

```text
.tmp/qqreader-original-online-58358897/20.eqct size=3712
.tmp/qqreader-original-online-58358897/34.eqct size=3840
.tmp/qqreader-original-online-58358897/chapter.q size=25852
.tmp/qqreader-original-online-58358897/book.meta size=3787
```

Result:

- `chapter.q`, `book.meta`, and `adv.m` are plain text/JSON-like metadata.
- `.eqct` is high-entropy binary content, not plain text and not JSON.
- Therefore `wxmini.reader.qq.com/api/chapter/content` text cannot simply be
  written to `<cid>.eqct` and treated as a valid QQ Reader chapter file.

PC-side direct endpoint check:

```text
https://androidtgw.reader.qq.com/v7_6_6/queryChapterLoad?bid=58358897
  -> {"retCode":"-3","msg":"no login",...}

https://androidtgw.reader.qq.com/v7_6_6/chapterOver?... 
  -> {"code": -1, "msg": "deny", "version":""}

https://wxmini.reader.qq.com/api/chapter/content?bid=58358897&cid=34
  -> code=0, msg=success, content_len>0, auth=1, authType=0, fockEncrypt=false
```

Conclusion:

- Original QQ Reader's normal body path is not the visible
  `ChapBatAuthWithPD` metadata tar fallback.
- Original uses an app-session/native path around
  `OnlineChapterDownloadTask.run()` and `androidtgw.reader.qq.com/v7_6_6/queryChapterLoad`,
  then writes valid `.eqct` files.
- The current offline clone still cannot be considered normally usable for
  QQ Reader article reading because it does not generate valid `.eqct`.
- `wxmini` proves free chapter text is reachable, but it is only a diagnostic
  or possible separate content-provider path until we find a valid QQ Reader
  rendering/writer integration.

Implemented diagnostic build:

```text
v160-mini-content-diagnostic
.tmp/qqreader-c9f8-neutralized-v160-mini-content-diagnostic-signed.apk
```

Code changes:

```text
core/hook/src/main/java/com/multiapp/core/hook/QqReaderOnlineProtocolFallback.java
core/hook/src/main/cpp/native-hook.cpp
```

v160 behavior:

- Keep the existing `ReaderProtocolTask + ReadOnline.search(...)` diagnostic.
- When that path returns `resultCode=0` but no usable `.eqct`/body file, call
  `wxmini.reader.qq.com/api/chapter/content?bid=<bid>&cid=<cid>`.
- Log only structure and lengths:

```text
status, bytes, code, msg, dataKeys, auth, authType, fockEncrypt, chapterId, ccid, contentLen
```

No chapter text is printed and no fake `.eqct` success is dispatched.

v160 device test status:

```text
device=100.110.94.8:41895
apk=.tmp/qqreader-c9f8-neutralized-v160-mini-content-diagnostic-signed.apk
install=success; adb install command timed out after install, but pm path exists
start=success
topActivity=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8/com.qq.reader.activity.MainFlutterActivity
logs:
  .tmp/qqreader-v160-mini-content-diagnostic-read-logcat.txt
  .tmp/qqreader-v160-mini-content-diagnostic-manual-read-logcat.txt
```

The two v160 log windows did not trigger `OnlineChapterDownloadTask.run()`:

```text
no stub_online_run
no mini content shape
no QROnlineFileProvider
```

Reason: the clone stayed on `MainFlutterActivity`; no confirmed reader-page
chapter load happened during the capture windows. The v160 APK is installed,
but the diagnostic path still needs a manual or automated reader-page trigger.

v160 reader-page trigger result:

```text
log=.tmp/qqreader-v160-readerpage-input-trigger-logcat.txt
bookId=59424549
cid=6 and cid=11
```

The clone reached the reader page and reproduced the known storage boundary:

```text
QROnlineFileProvider:
  .../QQReader/Online/59424549/6.eqct

stub_online_run:
  native run unavailable
  attempting ReaderProtocolTask ReadOnline fetch ... scids=6

protocol fallback:
  tar bytes=9216
  entries=[59424549_ALL_o:5315, 59424549_6_s:0, info.txt:942]
  info array len=2 hasBodyUrl=0
  ReadOnlineResult resultCode=0
  ReadOnlineFile path=.../59424549_6_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
  expectedEqct=.../59424549/6.eqct expectedEqctSize=-1
```

The new mini-content diagnostic succeeded for the same chapter identifiers:

```text
cid=6:
  status=200
  code=0
  msg=success
  auth=1
  authType=0
  fockEncrypt=false
  chapterId=6
  ccid=96733406896907065
  contentLen=2448

cid=11:
  status=200
  code=0
  msg=success
  auth=1
  authType=0
  fockEncrypt=false
  chapterId=11
  ccid=96759121402970615
  contentLen=2431
```

Updated conclusion:

- The fallback has enough metadata to fetch free chapter text from the QQ
  Reader mini endpoint.
- The mini endpoint's `ccid` matches `chapter_ccid` from the app metadata tar,
  so the identifier mapping is correct.
- The remaining blocker is not network access and not chapter authorization for
  these free chapters. It is the rendering/storage integration:

```text
QQ Reader reader page expects valid <cid>.eqct files or an equivalent provider path.
The clone currently has only zero-byte *_s files and no valid .eqct.
```

Next implementation target:

```text
v161 = find QROnlineFileProvider / reader content provider integration point.
Either reuse QQ Reader's own writer/encryptor to create valid .eqct, or hook the
provider/read path so the mini endpoint text can be supplied without pretending
it is a valid .eqct file.
```

## 2026-06-14 v161 Online File Diagnostic

Implemented diagnostic build:

```text
v161-online-file-diag
.tmp/qqreader-c9f8-neutralized-v161-online-file-diag-signed.apk
```

Code changes:

```text
core/hook/src/main/cpp/native-hook.cpp
  debug.multiapp.online.file_diag=1
  logs libc open/openat/access/stat/lstat/fopen for /QQReader/Online/ paths

tools/qqreader-offline-patch/test-qqreader-offline.ps1
  added -OnlineFileDiag 0|1
```

Build verification:

```text
./gradlew.bat --no-daemon --console=plain :core:hook:assembleDebug
BUILD SUCCESSFUL
```

During this pass an existing compile blocker was fixed:

```text
g_lsplant_hook_stubs declaration had been swallowed by a malformed comment.
It is now a real static std::unordered_map<void*, void*> declaration.
```

Device test:

```text
device=100.110.94.8:41895
log=.tmp/qqreader-v161-online-file-diag-read-logcat.txt
```

Reader-page evidence still reproduces the same boundary:

```text
QROnlineFileProvider initBookInfo is YWReadBookInfo(... filePath=.../QQReader/Online/59424549/ ...)
ReadPageLog: QROnlineFileProvider getFilesAllExistQctFileName qct directory 0
QROnlineFileProvider: .../QQReader/Online/59424549/11.eqct
stub_online_run: native run unavailable
stub_online_run: protocol fallback ... 59424549_11_s:0 ... hasBodyUrl=0
stub_online_run: expectedEqct=.../59424549/11.eqct expectedEqctSize=-1
stub_online_run: mini content shape status=200 code=0 auth=1 authType=0 fockEncrypt=false contentLen=2431
```

New v161 finding:

```text
online_file_diag count=0
```

Interpretation:

- The reader page/provider logs `.eqct` paths, but the native libc hooks did not
  see `open/openat/access/stat/lstat/fopen` for these paths.
- Therefore the currently visible `QROnlineFileProvider getFilesAllExistQctFileName`
  boundary is likely Java/Kotlin-level `java.io.File` logic or another runtime
  path not passing through our current libc hooks at that moment.
- The next hook target should move up one layer: Java file/provider diagnostics,
  not more native file I/O logging.

Next implementation target:

```text
v162 = Java-layer file/provider diagnostic.
Hook java.io.File.exists/listFiles/length/getAbsolutePath for /QQReader/Online,
or locate the obfuscated class that emits QROnlineFileProvider and hook its
getFilesAllExistQctFileName / content-read methods directly.
```

## 2026-06-14 v162 Java Directory Shape Diagnostic

User selected option 1: do a low-risk active Java directory scan instead of
hooking global `java.io.File` methods.

Implemented diagnostic build:

```text
v162-java-dir-shape
.tmp/qqreader-c9f8-neutralized-v162-java-dir-shape-signed.apk
```

Code changes:

```text
core/hook/src/main/java/com/multiapp/core/hook/QqReaderOnlineProtocolFallback.java
  added logOnlineDirShape(baseDir, expectedEqct, cid)

core/hook/src/main/cpp/native-hook.cpp
  calls log_online_dir_shape(...) after mini content diagnostic at the no-usable-file boundary
```

Build verification:

```text
./gradlew.bat --no-daemon --console=plain :core:hook:assembleDebug
BUILD SUCCESSFUL
```

Packaging note:

```text
powershell.exe -ExecutionPolicy Bypass -File tools/qqreader-offline-patch/build-qqreader-offline.ps1 -VersionTag v162-java-dir-shape -Build -ForceExtract -ForceRepack
```

The packaging command printed a Kotlin daemon connection failure near the end,
but the signed APK was produced:

```text
.tmp/qqreader-c9f8-neutralized-v162-java-dir-shape-signed.apk
```

Device test:

```text
device=100.110.94.8:41895
install=success after extended timeout
lastUpdateTime=2026-06-14 13:38:47
log=.tmp/qqreader-v162-java-dir-shape-read-logcat.txt
```

Representative evidence:

```text
stub_online_run: protocol fallback ... hasBodyUrl=0
stub_online_run: expectedEqct=.../58816978/5.eqct expectedEqctSize=-1
stub_online_run: direct-fetch ReadOnlineFile[0] path=.../58816978_5_s size=0 fileUrlEmpty=1 resourceUrlEmpty=1
stub_online_run: mini content shape status=200 code=0 auth=1 authType=0 fockEncrypt=false chapterId=5 ccid=95568324859063427 contentLen=3451
stub_online_run: java dir shape baseDir=.../QQReader/Online/58816978/ dirExists=true dirIsDir=true expected=.../5.eqct expectedExists=false expectedLen=-1 cid=5
stub_online_run: java dir shape files count=8 interesting=[58816978_1_s:len=0, 58816978_2_s:len=0, 58816978_5_s:len=0, adv.m:len=4532, book.meta:len=2959, chapter.q:len=16642]
```

Additional repeated examples in the same log:

```text
bookId=58724023 cid=8/13/18
expected .eqct does not exist
matching <bookId>_<cid>_s exists but len=0
chapter.q/book.meta/adv.m exist and are non-empty
```

Updated conclusion:

- Java-level directory state matches the earlier native/ReadOnline evidence.
- The clone has valid-looking metadata files but no usable chapter body file.
- The public mini endpoint can fetch free chapter text, but QQ Reader's reader
  provider still requires a valid `.eqct` or a provider/read-path replacement.
- Since native file hooks saw no direct `.eqct` open/stat and Java active scan
  confirms missing `.eqct`, the next useful step is no longer more passive file
  diagnostics. It is one of:

```text
A. implement a controlled provider/read-path replacement for QQ Reader reader page, or
B. implement LSPlant pass-through backup support, then hook the actual provider/File methods without changing behavior, or
C. restore/reuse QQ Reader's real .eqct writer/encryptor.
```

Recommended next step:

```text
v163 = add LSPlant backup/pass-through support or locate the exact obfuscated
QROnlineFileProvider class/method from runtime stack traces, then hook that
method rather than global java.io.File.
```

## v163 LSPlant pass-through + Java File diagnostics

Decision for the `哪个好选哪个` branch:

```text
Choose B first: implement LSPlant backup/pass-through support, then use it for
transparent Java-side diagnostics.
```

Reasoning:

- v162 already proved the runtime directory state: metadata exists, `<bookId>_<cid>_s`
  is zero bytes, and expected `<cid>.eqct` does not exist.
- Directly fabricating `.eqct` is not safe yet because original `.eqct` files are
  high-entropy binary, not plain chapter text.
- The exact obfuscated `QROnlineFileProvider` class/method has not been located.
- Existing `HookEngine.hookMethod(...)` is skip/replacement mode. Using it on
  `java.io.File` or provider methods would change behavior and create false
  results. A pass-through hook is required before broader Java diagnostics.

Implemented changes:

```text
core/hook/src/main/cpp/native-hook.cpp
  added nativeHookMethodWithBackupImpl(...)
  kept nativeHookMethod(...) Boolean behavior unchanged
  added nativeHookMethodWithBackup(...) returning LSPlant backup Executable

core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt
  hookMethod(...) delegates to hookMethodWithBackup(...) != null
  added hookMethodWithBackup(...): Executable?

core/hook/src/main/java/com/multiapp/core/hook/SimpleHooker.kt
  stores LSPlant backup Executable
  added callOriginal(args) for Method backup invocation

core/hook/src/main/java/com/multiapp/core/hook/HookEngine.kt
  kept hookMethod(...) skip-mode semantics unchanged
  added hookMethodPassThrough(Method, beforeCallback, afterCallback)

core/hook/src/main/java/com/multiapp/core/hook/QqReaderFileJavaDiag.kt
  new diagnostic helper gated by debug.multiapp.online.java_file_diag=1
  pass-through hooks java.io.File.exists(), length(), listFiles()
  only logs paths under /QQReader/Online/ containing .eqct/.eres/_s/chapter.q/book.meta/adv.m

core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
  installs QqReaderFileJavaDiag after LSPlant init when enabled
```

Build verification:

```text
./gradlew.bat --no-daemon --console=plain :core:hook:assembleDebug :core:loader:assembleDebug
BUILD SUCCESSFUL
```

Next validation package:

```text
VersionTag=v163-lsplant-pass-through-file-diag
Required runtime prop: debug.multiapp.online.java_file_diag=1
Expected log tags: QqReaderFileJavaDiag / java_file_diag
```
## 2026-06-14 v164 LSPatch-style LSPlant Init Review

User pointed to the local LSPatch reference under:

```text
tmp_apks/LSPatch-0.8
```

Relevant reference points:

```text
tmp_apks/LSPatch-0.8/patch-loader/src/main/jni/api/patch_main.cpp
  JNI_OnLoad -> PatchLoader::Load(env)

tmp_apks/LSPatch-0.8/patch-loader/src/main/jni/src/patch_loader.cpp
  PatchLoader::Load -> InitArtHooker(env, initInfo) before Java entry load

tmp_apks/LSPatch-0.8/core/Vector-master/native/src/core/context.cpp
  Context::InitArtHooker -> lsplant::Init(env, initInfo)
  Context::InitHooks -> MakeDexFileTrusted for injected dex files

core/hook/src/main/cpp/lsplant/lsplant.hpp
  Init() requires an env without hidden API restriction; JNI_OnLoad is recommended.
```

Diagnosis from v163 logs:

```text
LSPlant.init failed in normal Kotlin/JNI path
DexFile(ByteBuffer) missing / restricted
Executable.accessFlags hiddenapi denied
```

Implemented v164 alignment:

```text
core/hook/src/main/cpp/native-hook.cpp
  extracted init_lsplant_internal(JNIEnv*, const char*)
  added JNI_OnLoad that calls init_lsplant_internal(env, nullptr)
  kept NativeHookBridge.nativeInitLsplant(libDir) as fallback / status path
  initialized ShadowHook before LSPlant early init
  changed LSPlant unhook stub map erase path to unique_lock
  kept Hook() backup return path from v163

core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt
  still calls AndroidCompat.bypassHiddenApis() before native fallback init
```

Review result:

- The previous v163 pass-through API direction remains valid.
- The missing piece was init timing: current code initialized LSPlant too late compared with LSPatch.
- v164 moves LSPlant init to native library `JNI_OnLoad`, closer to LSPatch.
- A review issue was fixed before packaging: early LSPlant init now calls `init_shadowhook_for_runtime("nativeInitLsplant")` before using ShadowHook as LSPlant inline hooker.

Build verification:

```text
./gradlew.bat --no-daemon --console=plain :core:hook:assembleDebug
BUILD SUCCESSFUL

./gradlew.bat --no-daemon --console=plain :core:loader:assembleDebug
BUILD SUCCESSFUL
```

Next test package:

```text
VersionTag=v164-lspatch-style-lsplant-init
Expected first validation:
  JNI_OnLoad: early LSPlant init begin
  nativeInitLsplant: LSPlant initialized successfully
  initLsplant: result=true or already initialized
  QqReaderFileJavaDiag java_file_diag installed=true when debug.multiapp.online.java_file_diag=1
```
## 2026-06-14 v164 Device Result

Package produced and installed:

```text
.tmp/qqreader-c9f8-neutralized-v164-lspatch-style-lsplant-init-signed.apk
install=success
device=100.110.94.8:41895
log=.tmp/qqreader-v164-lspatch-style-lsplant-init-start-logcat.txt
```

Result: not fixed yet.

Evidence:

```text
JNI_OnLoad: early LSPlant init begin
nativeInitLsplant: shadowhook_init mode=SHARED ret=12(Init linker mod failed)
nativeInitLsplant: shadowhook_init mode=UNIQUE ret=12(Init linker mod failed)
nativeInitLsplant: libart.so handle=0x0
libart resolver: path=/apex/com.android.art/lib64/libart.so
libart resolver: exact not found: _ZN3artL15GetMethodShortyEP7_JNIEnvP10_jmethodID
libart resolver: prefix not found: _ZN3artL15GetMethodShortyEP7_JNIEnvP10_jmethodID
libart resolver: exact not found: _ZN3art15GetMethodShortyEP7_JNIEnvP10_jmethodID
LSPlant: Failed to find GetMethodShorty
LSPlant: Failed to init art method
JNI_OnLoad: early LSPlant init result=0
```

Additional bug found and fixed after this test:

```text
AndroidCompat.bypassViaLSPosed caught Exception only.
Missing org.lsposed.hiddenapibypass.HiddenApiBypass throws NoClassDefFoundError,
so VMRuntime/Unsafe fallback was skipped.
Changed those bypass catch blocks to Throwable.
```

Resolver evidence:

```text
adb pull /apex/com.android.art/lib64/libart.so .tmp/device-libart-arm64.so
llvm-readelf -S .tmp/device-libart-arm64.so
  .dynsym exists
  .gnu_debugdata exists
llvm-nm -D / llvm-readelf -s: no GetMethodShorty in exported dynamic symbols
```

Conclusion:

- v164 proved that moving LSPlant init to `JNI_OnLoad` matches LSPatch timing but
  is insufficient with the current resolver/backend.
- The current resolver only scans `.dynsym/.symtab` directly from libart.so.
- This device's libart has `.gnu_debugdata`; LSPatch's local `ElfImage` supports
  decompressing `.gnu_debugdata`, while our resolver does not.
- Next useful step is v165: port or implement enough of LSPatch `ElfImage`
  behavior to resolve ART symbols from `.gnu_debugdata`, then retry LSPlant init.
```
## 2026-06-14 v165 GetMethodShorty Fallback

Instead of immediately porting the full LSPatch `.gnu_debugdata` resolver, v165
adds a narrower compatibility fallback for the first hard LSPlant blocker:

```text
LSPlant requested art::GetMethodShorty(JNIEnv*, jmethodID)
Device libart exports no dynamic GetMethodShorty symbol
```

Implemented:

```text
core/hook/src/main/cpp/native-hook.cpp
  resolver returns multiapp_get_method_shorty for both known GetMethodShorty symbol names
  nativeHookMethodWithBackupImpl caches shorty before calling LSPlant Hook()
  cache key: jmethodID from FromReflectedMethod(...)
  shorty source: Java reflection on Method/Constructor return/parameter types

core/common/src/main/java/com/multiapp/core/common/AndroidCompat.kt
  HiddenApiBypass fallback catch widened from Exception to Throwable
```

Build verification:

```text
./gradlew.bat --no-daemon --console=plain :core:hook:assembleDebug :core:common:assembleDebug
BUILD SUCCESSFUL
```

Purpose:

- Determine whether LSPlant can pass `ArtMethod::Init` without the real ART
  `GetMethodShorty` symbol.
- If it passes, validate `hookMethodWithBackup` with `java.io.File` diagnostics.
- If it fails on another ART symbol, that becomes the next concrete target.

## 2026-06-15 v165 New Device Full Validation (192.168.2.44:41379)

Device:

```text
192.168.2.44:41379
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
apk=.tmp/qqreader-c9f8-neutralized-v165-getmethodshorty-fallback-signed.apk
install=Success (via adb push + pm install)
pid=22312
```

Install note: `adb install -r -d` failed with `INSTALL_FAILED_USER_RESTRICTED`.
Workaround: `adb push` to `/data/local/tmp/v165.apk` then `pm install -r -d`.

### Gate 1: Shell native boundary — PASSED

```text
RegisterNatives: class=com.stub.StubApp count=10
caller=.../libjiagu_vip.so offset=0x1116b4
captured original interface5=0x...820
captured original interface11=0x...e2c
captured original interface20=0x...3f4
captured original interface21=0x...bd4
RegisterNatives: result=0 class=com.stub.StubApp
```

### Gate 2: Self-kill callsite — PASSED

```text
GOT tgkill intercepted: tgid=3215 tid=3215 sig=9 caller=.../libjiagu_vip.so offset=0x11cb88
patch_jiagu_self_kill_from_return_address: exact self-kill caller=0x74afc73b88
patch_jiagu_self_kill_from_return_address: instructions prev=0x52800120 insn=0x97ffb823 next=0x140000e8
patch_jiagu_self_kill_from_return_address: patched caller-4 callsite=0x74afc73b84 before=0x97ffb823 after=0xd503201f
GOT tgkill intercepted: suppressing exact jiagu self-kill caller
stub_interface20 forwarding original=0x74afc643f4
stub_interface20 original result=1
```

### Gate 3: Chapter native binding — PASSED

```text
nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback registered
nativeRegisterOnlineChapterDownloadFallbackStubs: registered 15 methods
remember_hook_classloader: hook ClassLoader captured
```

No `UnsatisfiedLinkError` for `OnlineChapterDownloadTask.run()`.

### Gate 4: Chapter body loading — NOT PASSED (known blocker)

Tested bookId=58245597, chapters cid=1,2,6. All produced the same shape:

```text
stub_online_run: protocol fallback url applied via setUrl(String)
stub_online_run: protocol fallback ReaderProtocolTask.run url=https://newminerva-tgw.reader.qq.com/ChapBatAuthWithPD?bookId=58245597&type=0&tafauth=1&useindex=1&scids=1
stub_online_run: protocol fallback tar bytes=31744 entries=[58245597_ALL_o:27857, 58245597_1_s:0, info.txt:910]
stub_online_run: protocol fallback info array len=2 hasBodyUrl=0
stub_online_run: direct-fetch ReadOnlineFile[0] chapterId=1 ... size=0 fileUrlEmpty=1 resourceUrlEmpty=1
stub_online_run: ReadOnlineResult success but no usable chapter file allow=0 materialized=0 hasUsable=0
stub_online_run: mini content shape status=200 code=0 auth=1 fockEncrypt=false contentLen=5860
stub_online_run: java dir shape expected=.../1.eqct expectedExists=false expectedLen=-1
```

Crash log: empty. Process stable.

### Original App Comparison on Same Device

Original QQ Reader v8.5.1.890 (com.qq.reader) captured on 192.168.2.44:41379.
Books 58325558 and 58764139 have real `.eqct` files (3-8KB each, 13 chapters).

Pulled original files for format inspection:

```text
.tmp/qqreader-original-58325558/58325558/1.eqct  size=5408
.tmp/qqreader-original-58325558/58325558/2.eqct  size=4872
...
.tmp/qqreader-original-58325558/58325558/13.eqct size=5976
```

`.eqct` format: high-entropy binary (161/256 unique byte values in first 256 bytes),
not plain text, not JSON. Cannot be generated from `wxmini` text endpoint.

#### Critical API endpoint difference

Original app does NOT use `ChapBatAuthWithPD`. Its chapter flow uses a completely
different endpoint:

```text
Original:
  androidtgw.reader.qq.com/v7_6_6/queryChapterLoad?bid=58325558
  androidtgw.reader.qq.com/v7_6_6/chapterOver?bid=58325558&cid=1&chapterUuid=1&showScore=0&bookType=0

Clone (v165):
  newminerva-tgw.reader.qq.com/ChapBatAuthWithPD?bookId=58245597&type=0&tafauth=1&useindex=1&scids=1
```

Original app's `queryChapterLoad` endpoint returns chapter load metadata including
download URLs or triggers native download that produces valid `.eqct` files.
The clone's `ChapBatAuthWithPD` only returns chapter authorization metadata
(`chapter_ccid`, `paycheckmode`, etc.) without body download URLs.

Original app logcat shows no `ChapBatAuthWithPD`, no `ReadOnline.search`,
no `OnlineChapterDownloadTask.run()` in Java logs. The original's jiagu shell
works normally and `OnlineChapterDownloadTask.run()` is registered as a native
method by `libjiagu_vip.so`, operating below Java log level.

Original app request pattern visible in logcat:

```text
server: url https://commontgw.reader.qq.com/... (startup, config, account)
server: url https://newminerva-tgw.reader.qq.com/sk?... (session key)
server: url https://androidtgw.reader.qq.com/v7_6_6/queryChapterLoad?bid=... (chapter load)
server: url https://androidtgw.reader.qq.com/v7_6_6/chapterOver?bid=...&cid=... (chapter done)
QROnlineFileProvider: .../QQReader/Online/58325558/1.eqct (file access)
```

#### Updated root cause

The clone's `stub_online_run` fallback calls `ChapBatAuthWithPD` which is the
wrong endpoint for chapter body content. It only returns auth metadata, not
download URLs. The original app uses `queryChapterLoad` on `androidtgw` which
returns the real chapter download path.

#### Next direction

1. Replace the clone's chapter request from `ChapBatAuthWithPD` to
   `queryChapterLoad?bid=<bookId>` on `androidtgw.reader.qq.com`, or
2. Investigate how the original's native `OnlineChapterDownloadTask.run()` uses
   `queryChapterLoad` response to download and write `.eqct` files, then
   reproduce that exact path in `stub_online_run`.

Do not repeat:

```text
ChapBatAuthWithPD as chapter body source
wxmini text as .eqct content
generic URLConnection experiments
```
## v168 LSPatch-style LSPlant pass-through result

Goal:

```text
Use the local LSPatch/LSPlant source as reference and make LSPlant pass-through
usable inside the offline QQ Reader clone, so Java-side file/provider diagnostics
can call original methods instead of changing behavior.
```

Changes made after v163-v166:

```text
core/stub/build.gradle.kts
  loaderRuntimeFiles now includes hiddenapibypass and shadowhook, so loader.dex
  contains org.lsposed.hiddenapibypass.HiddenApiBypass and ShadowHook classes.

tools/qqreader-offline-patch/patch-qqreader-clone.ps1
  Native library injection now includes:
    libmultiapp-native.so
    liblsplant.so
    libshadowhook.so
    libshadowhook_nothing.so
    libc++_shared.so

core/hook/src/main/cpp/native-hook.cpp
  LSPlant initialization moved/duplicated into JNI_OnLoad timing, matching the
  local LSPatch style more closely.
  nativeInitLsplant(...) remains as a Kotlin-side confirmation/fallback.

tmp_apks/LSPatch-0.8/core/Vector-master/external/lsplant/...
  Experimental local LSPlant source patch:
    RegisterNative/UnregisterNative missing symbols are warning-only.
    interpreter bridge missing symbols are warning-only.
  The rebuilt liblsplant.so is copied into core/hook/src/main/jniLibs.
```

Why this was needed:

```text
v164:
  hiddenapibypass class missing from loader.dex
  shadowhook_init failed before shadowhook.so was injected

v165:
  HiddenApiBypass fixed
  shadowhook_init OK
  LSPlant failed at ClassLinker::Init because device libart.so lacks exported
  RegisterNative/FixupStaticTrampolines symbols

v166:
  RegisterNative/UnregisterNative made optional
  LSPlant still failed because SetEntryPointsToInterpreter/GetOptimizedCodeFor/
  art_quick_to_interpreter_bridge are also unavailable on this device libart.so

v168:
  interpreter bridge symbols made optional
  LSPlant initialized successfully
```

Build/package/test evidence:

```text
APK:
.tmp/qqreader-c9f8-neutralized-v168-lspatch-lsplant-optional-bridge-signed.apk

Package lib sizes:
lib/arm64-v8a/liblsplant.so   109592
lib/armeabi-v7a/liblsplant.so  89484

Device log:
.tmp/qqreader-v168-lspatch-lsplant-optional-bridge-start-logcat.txt
```

Representative log evidence:

```text
JNI_OnLoad: early LSPlant init begin
shadowhook_init mode=SHARED ret=0(OK)
RegisterNative/UnregisterNative hooks unavailable; continuing without native propagation
interpreter bridge symbols unavailable; continuing without deopt bridge
nativeInitLsplant: LSPlant initialized successfully!
JNI_OnLoad: early LSPlant init result=1
nativeInitLsplant: already initialized
nativeHookMethodWithBackup: hook succeeded ... java.io.File.exists
hookMethodPassThrough: successfully hooked java.io.File.exists
QqReaderFileJavaDiag: java_file_diag method=exists .../QQReader/Online/58192983/21.eqct result=false
QqReaderFileJavaDiag: java_file_diag method=length .../QQReader/Online/58192983/58192983_21_s result=0
```

Updated conclusion:

- LSPlant pass-through is now usable for diagnostic Java hooks in the QQ Reader clone.
- The reader path really checks `<cid>.eqct` from Java-side file APIs.
- Current clone still does not produce valid `.eqct` files; `_s` files exist but are zero bytes.
- This confirms the next fix should target the body file/provider/read-path, not more startup hooks.

Risk review:

```text
The local LSPlant patch weakens native-method propagation and deopt/interpreter
bridge support. It is acceptable for the current diagnostic target
(java.io.File pass-through logging), but should not be treated as a full LSPlant
replacement for arbitrary method hooks until tested separately.
```

## v169 QROnlineFileProvider / chapter validity reverse result

Goal:

```text
Do not repeat ChapBatAuthWithPD/body-source experiments. Locate the Java reader
provider that turns book/chapter ids into the .eqct/.eres paths observed in v168,
and identify the next concrete hook/diagnostic point.
```

New dex analysis tool:

```text
tools/qqreader-offline-patch/FindDexStringRefs.java
```

It reports the class/method/instruction that references a target string. This was
needed because `QROnlineFileProvider` is a log tag; the real class is obfuscated.

String reference results:

```text
Lcom/qq/reader/ywreader/component/compatible/qdaf;
  search()V
    " QROnlineFileProvider getFilesAllExistQctFileName ..."
  getOnlineChapterFilePath(JJLjava/lang/String;Z)Ljava/lang/String;
    "QROnlineFileProvider"
    ".eqct"
  search(JJLjava/lang/String;Z)Ljava/lang/String;
    ".eres"

Lcom/qq/reader/common/db/handle/qdch;
  builds legacy .qct/.eqct paths and caches existing .qct names.
```

Provider behavior from `v169-ywreader-compatible-qdaf.dump.txt`:

```text
constructor(String bookId)
  calls search() to list existing files under QQReader/Online/<bookId>/

search()V
  scans the directory and stores existing "*.qct" file names in a HashSet.

getOnlineChapterFilePath(long cid, long uuid, String bookId, boolean preload)
  if HashSet contains "<cid>.qct": return .../<cid>.qct
  else: return .../<cid>.eqct

search(long cid, long uuid, String bookId, boolean preload)
  returns .../<cid>.eres
```

Callers:

```text
Lcom/qq/reader/activity/ReaderPageActivity$qdae;
Lcom/qq/reader/utils/qdcc;
Lcom/qq/reader/ywreader/component/chaptermanager/qdaf;  // TXT online split manager
Lcom/qq/reader/ywreader/component/chaptermanager/qdae;  // EPUB online split manager
```

Chapter validity result:

```text
Lcom/qq/reader/ywreader/component/chaptermanager/qdaf;
  search(OnlineChapter): boolean
    path = fileProvider.getOnlineChapterFilePath(...)
    valid = Lcom/yuewen/reader/framework/utils/qdag;->judian(path)

Lcom/yuewen/reader/framework/utils/qdag;->judian(String): boolean
  returns true only when:
    File(path).exists() == true
    File(path).length() > 0
```

This matches v168 runtime evidence exactly:

```text
.eqct exists=false
*_s length=0
```

Updated conclusion:

- `QROnlineFileProvider` is not a downloader and not an encryption/writer.
- It is only a path provider that chooses legacy `.qct` when present, otherwise
  `.eqct`.
- Forcing `qdag.judian(path)` to return true would only bypass the first file
  validity check; the reader engine would still later open/parse the missing or
  invalid `.eqct` file. Treat this as a diagnostic-only option, not a real fix.
- The remaining real blocker is still generation or equivalent delivery of a
  valid QQ Reader chapter body file/object.

New diagnostic hook:

```text
core/hook/src/main/java/com/multiapp/core/hook/QqReaderProviderDiag.kt
```

It is pass-through only and enabled by:

```text
debug.multiapp.online.provider_diag=1
```

It logs:

```text
provider_path cid=<cid> uuid=<uuid> bookId=<bookId> result=<path> file=exists=<...> length=<...>
provider_res_path cid=<cid> uuid=<uuid> bookId=<bookId> result=<path> file=exists=<...> length=<...>
file_validity path=<path> result=<true|false> file=exists=<...> length=<...>
```

Build verification:

```text
.\gradlew.bat --no-daemon --console=plain :core:hook:assembleDebug :core:stub:generateLoaderDex
BUILD SUCCESSFUL
```

Next step:

```text
Build v169 with provider_diag + java_file_diag enabled, then test one known free
chapter. If provider_validity remains false because .eqct is absent, do not hook
validity to true; instead continue at either:
  1. original queryChapterLoad -> .eqct writer/encryptor path, or
  2. reader engine content-object provider path before .eqct parsing.
```

## v169 device result and next protocol diagnostic

APK:

```text
.tmp/qqreader-c9f8-neutralized-v169-provider-validity-diag-signed.apk
```

Device log:

```text
.tmp/qqreader-v169-provider-validity-diag-logcat.txt
```

Diagnostic hooks installed successfully:

```text
QqReaderProviderDiag provider_diag installed=true
hooked com.qq.reader.ywreader.component.compatible.qdaf.getOnlineChapterFilePath
hooked com.qq.reader.ywreader.component.compatible.qdaf.search
hooked com.yuewen.reader.framework.utils.qdag.judian
```

Runtime proof:

```text
provider_path cid=1 uuid=1 bookId=58911595 .../QQReader/Online/58911595/1.eqct file=exists=false length=0
file_validity .../QQReader/Online/58911595/1.eqct result=false file=exists=false length=0
provider_path cid=2 uuid=2 bookId=58911595 .../2.eqct file=exists=false length=0
file_validity .../2.eqct result=false
provider_path cid=7 uuid=7 bookId=58911595 .../7.eqct file=exists=false length=0
file_validity .../7.eqct result=false
```

The clone also wrote only metadata/zero-byte marker files:

```text
58911595_1_s len=0
58911595_2_s len=0
58911595_7_s len=0
adv.m len=4535
book.meta len=3889
chapter.q len=15751
no 1.eqct / 2.eqct / 7.eqct
```

Network observations in v169:

```text
https://androidtgw.reader.qq.com/v7_6_6/queryChapterLoad?bid=58911595
https://androidtgw.reader.qq.com/v7_6_6/chapterOver?bid=58911595&cid=7&chapterUuid=7&showScore=0&bookType=0
```

Field/method reverse result:

```text
appconfig.qdae.aV = androidtgw.../v7_6_6/queryChapterLoad?
  only real reader reference found:
    QueryChapterBuyInfoTask.<init>(String bid, int restype)

appconfig.qdae.cO = androidtgw.../v7_6_6/chapterOver?
  reference found:
    QueryChapterMoreInfoTask.<init>(String bid, List cidList, long chapterUuid, int showScore, int bookType)
```

Conclusion:

- `queryChapterLoad` and `chapterOver` are live in the clone, but current
  evidence shows they are metadata / purchase / chapter-end tasks, not the
  direct `.eqct` body writer.
- `ChapBatAuthWithPD` still returns successful auth metadata with no body URL:

```text
protocol fallback info array len=2 hasBodyUrl=0
safe=[code=0, message=OK, chapter_id=7, chapter_uuid=7, encode=1, mediaFiles=[]]
```

`com.qq.reader.cservice.onlineread.qdae` is now identified as the local body
file transformer/writer:

```text
qdae.search(Context, String, long, int) -> builds cache/<bookId>/<cid>.s and calls
qdae.search(String sourcePath, String destPath, String bookId, int chapterId)

qdae.search(String sourcePath, String destPath, String bookId, int chapterId)
  reads sourcePath
  when destPath endsWith .eqct: applies qdae.search(bookId, chapterId, bytes)
  applies qdae.search(userId, bytes, bookId, chapterId, sourceFile)
  writes destPath
```

This means the missing part is not provider/path logic. It is the native
`OnlineChapterDownloadTask.run()` equivalent that should obtain the true source
body file/bytes and feed `onlineread.qdae`.

New v170 diagnostic added:

```text
core/hook/src/main/java/com/multiapp/core/hook/QqReaderProtocolDiag.kt
debug.multiapp.online.protocol_diag=1
```

It pass-through hooks:

```text
ReaderProtocolJSONTask.onFinish(okhttp3.Response)
```

Before the original consumes the response, it uses `Response.peekBody(8192)` to
log task class, URL, and response body prefix for chapter-related endpoints.

Build verification:

```text
.\gradlew.bat --no-daemon --console=plain :core:hook:assembleDebug :core:stub:generateLoaderDex
BUILD SUCCESSFUL
```

Next test:

```text
Build/install v170, enable:
  debug.multiapp.online.java_file_diag=1
  debug.multiapp.online.provider_diag=1
  debug.multiapp.online.protocol_diag=1

Then open a known free chapter and inspect QqReaderProtocolDiag output for
queryChapterLoad/chapterOver/other chapter endpoints.
```

## 2026-06-15 v172-v174: mini 正文落地与 `.eqct` 读取兼容

### v172 `EasyEncrypt.decrypt([B)[B` 缺失修复

基于 v171 日志：

```text
mini content shape status=200 ... fockEncrypt=false ... contentLen=2617
mini materialize failed java.lang.UnsatisfiedLinkError:
No implementation found for byte[] com.qq.reader.common.utils.crypto.EasyEncrypt.decrypt(byte[])
```

在 `core/hook/src/main/cpp/native-hook.cpp` 补齐：

```text
EasyEncrypt.decrypt([B)[B -> byte[] identity copy
EasyEncrypt.encrypt([B)[B -> byte[] identity copy
```

v172 构建并安装后，`qdae.search(sourcePath, expectedEqct, bid, cid)` 能继续执行，日志证明 `.eqct` 被生成：

```text
stub_easyencrypt_bytes_identity: len=7757
mini materialize result expectedEqct=.../QQReader/Online/58172870/16.eqct size=7757
completed via mini materialized eqct resultCode=0 .../16.eqct size=7757
```

但同一轮随后 `.eqct` 又变成不存在：

```text
provider_path cid=16 .../16.eqct file=exists=false length=0
completed via mini materialized eqct ... size=-1
```

结论：v172 已跨过“下载 mini 正文”和 `EasyEncrypt.decrypt` 缺失，但 `.eqct` 被后续阅读解析链删除。

### v173 删除栈定位

在 `QqReaderFileJavaDiag` 增加 `File.delete()` hook，并对 `.eqct/.eres/.mini_*` 打短调用栈。

v173 真机日志定位到删除者：

```text
java_file_diag method=delete path=.../QQReader/Online/58172870/30.eqct result=true
java_file_diag delete_stack path=.../30.eqct
  MultiAppHooker_.delete:8 <-
  com.qq.reader.cservice.onlineread.qdae.search:273 <-
  com.qq.reader.ywreader.component.compatible.qdae.search:190 <-
  format.txt.qdaa.search:66 <-
  format.qdaa.search:38 <-
  com.qq.reader.ywreader.component.chaptermanager.qdaf.judian:183 <-
  com.qq.reader.ywreader.component.chaptermanager.qdaf.search:0 <-
  com.qq.reader.ywreader.component.chaptermanager.qdaf$qdab.getBookSucces:56 <-
  com.qq.reader.cservice.onlineread.qdbb.getBookSucces:154 <-
  com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run:-2
```

反编译确认：

```text
com.qq.reader.cservice.onlineread.qdae.search(String sourcePath, String bookId, int cid): byte[]
  if sourcePath endsWith .eqct:
    qdae.search(bookId, cid, bytes)
    qdae.search(userId, bytes, bookId, cid, sourceFile)
  on failure:
    delete source .eqct
```

结论：阅读引擎打开 `.eqct` 后会走 QQ 阅读原加密/Fock/GZIP 解包流程；我们生成的是 mini 明文字节落地文件，不满足原 `.eqct` 加密封装，所以原解密失败并删除文件。

### v174 `.eqct` 明文读取兼容

新增：

```text
core/hook/src/main/java/com/multiapp/core/hook/QqReaderEqctPlaintextCompat.kt
```

同时在 `HookEngine.kt` 增加 `hookMethodAround(...)`，支持条件调用原方法或跳过原方法。

安装点：

```text
LoaderFactory.kt -> QQReader loader 初始化阶段
QqReaderEqctPlaintextCompat.install(hookEngine, guestCl)
```

hook 目标：

```text
com.qq.reader.cservice.onlineread.qdae.search(String sourcePath, String bookId, int cid): byte[]
```

条件：

```text
sourcePath contains /QQReader/Online/<bookId>/
sourcePath endsWith /<cid>.eqct
same directory has .mini_<cid>.txt marker
.eqct exists and length > 0
```

满足条件时直接返回 `.eqct` 文件字节；其它调用继续 `callOriginal(args)`。

v174 证据：

```text
QqReaderEqctCompat: eqct plaintext compat installed=true
mini materialize result expectedEqct=.../9.eqct size=7188 source=.../.mini_9.txt
QqReaderEqctCompat: eqct plaintext return path=.../9.eqct bookId=58172870 cid=9 size=7188
completed via mini materialized eqct resultCode=0 .../9.eqct size=7188
```

文件状态证明 `.eqct` 已保留：

```text
.../QQReader/Online/58172870/1.eqct  7845
.../QQReader/Online/58172870/2.eqct  7145
.../QQReader/Online/58172870/3.eqct  15593
.../QQReader/Online/58172870/4.eqct  7611
.../QQReader/Online/58172870/5.eqct  8158
.../QQReader/Online/58172870/6.eqct  7337
.../QQReader/Online/58172870/7.eqct  6877
.../QQReader/Online/58172870/8.eqct  9195
.../QQReader/Online/58172870/9.eqct  7188
.../QQReader/Online/58172870/10.eqct 7743
.../QQReader/Online/58172870/11.eqct 7794
```

进程状态：

```text
pidof com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 -> 10495
mLastPausedActivity: .../com.qq.reader.activity.ReaderPageActivity
```

当前结论：

- v174 已解决 v173 明确定位的 `.eqct` 被二次解密失败删除问题。
- 当前日志没有 QQ 阅读主进程 `FATAL EXCEPTION`。
- 仍需人工确认 UI 是否已能看到正文；如果 UI 仍空白，下一步应抓 `QTxtEngineSDK/openOnlineChapterFile/chaptermanager` 相关日志，而不是回到网络 URL 或壳加载顺序。

下一步验证建议：

1. 打开已生成 `.eqct` 的章节，例如 `58172870` 第 `9/10/11` 章。
2. 抓 30 秒日志，只筛：

```text
QqReaderEqctCompat
QTxtEngineSDK
openOnlineChapterFile
chaptermanager
file_validity
java_file_diag method=length .*\.eqct
FATAL EXCEPTION
```

3. 如果页面仍不显示正文，检查引擎是否要求章节 model/cache 刷新或 `.eres` 文件，而不是继续处理 `.eqct` 生成。

### v174 UI 验证补充

通过手机截图确认正文已经显示：

```text
.tmp/v174-current-screen-pull.png
```

截图内容显示 QQ 阅读阅读页已进入正文，顶部为：

```text
重生成妖，我修成真龙
```

正文片段可见，说明 v174 不只是生成 `.eqct` 文件，阅读引擎也已经能从兼容路径读出内容并渲染。

当前实机状态：

```text
pidof com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 -> 16919
topResumedActivity -> com.qq.reader.activity.MainFlutterActivity
screenshot -> 正文阅读页可见
```

阶段结论更新：

- QQ 阅读分身“正文加载不出来”的核心阻塞已被 v174 打通。
- 当前方案依赖 mini 免费章节接口 + 本地 `.eqct` materialize + `.eqct` 明文读取兼容。
- 该方案已验证免费正文显示；后续重点转为稳定性、翻章、付费章节边界、诊断开关收敛。

### v174 端口 32973 续测

设备重新连接：

```text
adb connect 192.168.2.44:32973 -> device
```

前台状态：

```text
pidof com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 -> 17083
topResumedActivity -> com.qq.reader.activity.ReaderPageActivity
```

本地 `.eqct` 数量继续增加：

```text
find .../QQReader/Online -name "*.eqct" | wc -l -> 24
```

阅读页 60 秒温和翻页验证日志：

```text
.tmp/qqreader-v174-port32973-readpage-60s-logcat.txt
```

关键命中：

```text
file_validity .../QQReader/Online/58192983/10.eqct result=false file=exists=false length=0
mini materialize result expectedEqct=.../58192983/10.eqct size=7824 source=.../.mini_10.txt contentLen=2716
QqReaderEqctCompat: eqct plaintext return path=.../58192983/10.eqct bookId=58192983 cid=10 size=7824
completed via mini materialized eqct resultCode=0 expectedEqct=.../58192983/10.eqct size=7824
```

负向检查：

```text
FATAL EXCEPTION: none
AndroidRuntime crash: none
No implementation found: none in this 60s readpage window
UnsatisfiedLinkError: none in this 60s readpage window
.eqct delete_stack: none
method=delete *.eqct: none
```

文件状态：

```text
.../QQReader/Online/58192983/5.eqct  6671
.../QQReader/Online/58192983/6.eqct  7134
.../QQReader/Online/58192983/7.eqct  7343
.../QQReader/Online/58192983/8.eqct  6652
.../QQReader/Online/58192983/9.eqct  8014
.../QQReader/Online/58192983/10.eqct 7824
```

截图证据：

```text
.tmp/v174-port32973-readpage.png
```

截图显示 QQ 阅读分身停留在阅读页，第 9 章正文正常渲染。

当前阶段判断：

- 免费章节正文链路已可连续生成、保留、读取和渲染。
- v174 当前没有复现正文加载闪退，也没有复现 `.eqct` 被删除。
- 后续进入收敛阶段：关掉不必要的诊断日志，保留必要兼容；再测应用冷启动、换书、翻到未缓存章节、付费章节提示边界。

### v175 标题修复与默认启用收敛

用户反馈阅读页标题消失，显示成正文第一句话。v175 的修复点：

```text
core/hook/src/main/java/com/multiapp/core/hook/QqReaderOnlineProtocolFallback.java
```

mini 接口返回 `data.title` 后，写入本地源文本时改为：

```text
title + "\n\n" + content
```

验证证据：

```text
.tmp/v175-title-check.png
.tmp/qqreader-v175-title-default-read-60s-logcat.txt
```

关键日志：

```text
mini materialize result ... titleLen=9 contentLen=4135
QqReaderEqctCompat ... eqct plaintext return ... size=11966
```

截图显示标题恢复为：

```text
第2章 我叔总城隍
```

v175 同时将正文 fallback 默认启用：

```text
debug.multiapp.online.run_fallback=0/false/off 才关闭
debug.multiapp.online.materialize_eqct=0/false/off 才关闭
QqReaderEqctPlaintextCompat 默认启用
```

阶段结论：免费章节正文与标题链路已验证可用，剩余重点转为登录和更多页面兼容。

### v176 登录闪退定位

设备端口：

```text
adb connect 192.168.2.44:33547 -> device
```

v175 登录采集窗口没有看到新的 QQ 阅读 `FATAL EXCEPTION`，但系统 activity 栈显示存在微信 `MMWebViewUI` 历史任务，说明登录链路涉及外部微信/网页跳转。为进一步定位，v176 加入 `IntentRemappingInstrumentation` 诊断日志，记录 `execStartActivity*` 的 caller、target、requestCode、component、package、action、data、categories、extras key。

v176 构建产物：

```text
.tmp/qqreader-c9f8-neutralized-v176-login-intent-diag-signed.apk
```

安装并启动后，抓到明确崩溃：

```text
.tmp/qqreader-v176-postinstall-launch-crash.txt
.tmp/qqreader-v176-postinstall-launch-exit-info.txt
.tmp/qqreader-v176-postinstall-launch-logcat.txt
.tmp/qqreader-v176-postinstall-launch-activities.txt
```

关键崩溃栈：

```text
FATAL EXCEPTION: main
Process: com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
java.lang.RuntimeException: Unable to start activity ComponentInfo{.../com.qq.reader.login.scanqrcode.ui.QRLoginScanQrCodeActivity}
Caused by: java.lang.IllegalStateException: You need to use a Theme.AppCompat theme (or descendant) with this activity.
    at androidx.appcompat.app.AppCompatDelegateImpl.createSubDecor
    at androidx.appcompat.app.AppCompatActivity.setContentView
    at com.qq.reader.activity.ReaderBaseActivity.setContentView
    at com.qq.reader.login.scanqrcode.ui.QRLoginScanQrCodeActivity.onCreate
```

系统侧启动记录也显示登录 Activity 的外层 manifest theme 为 0：

```text
Try to add startingWindow ... QRLoginActivity ... resolvedTheme = 0 theme = 0
Try to add startingWindow ... QRLoginScanQrCodeActivity ... resolvedTheme = 0 theme = 0
```

阶段判断：这次“登录闪退”的直接原因不是正文 fallback、微信回调或 YWLogin native，而是分身外层 manifest/运行期主题补齐不完整，导致 `QRLoginScanQrCodeActivity` 作为 AppCompat Activity 启动时没有 AppCompat theme。

### v177 登录主题修复

只读排查确认：当前 QQ 阅读离线 patch 路径是基于已有 clone 外壳替换 `assets/origin.apk`、loader dex 和 native lib 后重签名，没有重新跑 `StubBuilder.enrichWithThemeIds()` / `BinaryXmlEncoder`，因此不会刷新外层 `AndroidManifest.xml` 的 activity theme。运行期只能依赖 `LoaderFactory` 从内置 `origin.apk` 读取并补 theme。

v177 修改：

```text
core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
```

在 `applyActivityThemeIfKnown(activity, className)` 中，非零 `themeId` 时不再只改 `activity.applicationInfo.theme`，而是更早直接执行：

```text
activity.setTheme(themeId)
activity.applicationInfo.theme = themeId
replaceFieldIfPresent(activity, "mThemeResource", themeId)
```

理由：AppCompat 的检查读取当前 Activity theme。只修改 `ApplicationInfo.theme` 对已实例化 Activity 不一定生效，容易晚于 `AppCompatActivity.setContentView()`。

v177 构建已完成：

```text
.tmp/qqreader-c9f8-neutralized-v177-login-appcompat-theme-early-signed.apk
```

待验证项：

1. 安装 v177 后再次启动 QQ 阅读分身。
2. 点击登录/扫码登录入口。
3. 确认不再出现：

```text
You need to use a Theme.AppCompat theme (or descendant) with this activity.
```

4. 如果登录页能打开，再继续抓微信/QQ 登录跳转和回调链路。

后续更根本的修复方向：离线 patch 脚本应增加重建外层 manifest 的步骤，用 patched/original `origin.apk` 重新生成 activity theme，而不是长期依赖运行期补 theme。

## 2026-06-17 v177 Login Native Hook2

本轮目标是处理 v177 主题修复后继续出现的登录闪退。真机环境：

```text
device=192.168.2.78:39549
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

### 新崩溃点

进入扫码登录页后，主题崩溃已经消失，但出现新的 JNI 缺失：

```text
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.qrCodeV2(
  com.yuewen.ywlogin.callbacks.DefaultYWCallback
)
```

旧账号登录路径也存在同类问题：

```text
YWLoginManager.pwdLogin(Activity, String, String, YWCallBack)
```

### hook2 修改

修改文件：

```text
core/hook/src/main/cpp/native-hook.cpp
```

变更内容：

- `RegisterNatives` logger 增加 `YWLoginManager` 登录动作捕获。
- 捕获到原始 `pwdLogin/sendPhoneCode/qrCodeV2` 函数指针时，wrapper 先记录再转调原始实现。
- 捕获不到原始函数时，注册 fallback wrapper，避免 `UnsatisfiedLinkError` 直接杀进程。
- `nativeRegisterBusinessStubs` 注册数从 6 增加到 9，新增：

```text
YWLoginManager.pwdLogin(...)
YWLoginManager.sendPhoneCode(Context, String, int, int, YWCallBack)
YWLoginManager.qrCodeV2(DefaultYWCallback)
```

构建与打包：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 -VersionTag v177-login-hook2 -InputCloneApk .tmp\qqreader-c9f8-current-base.apk -OutputApk .tmp\qqreader-c9f8-neutralized-v177-login-hook2-signed.apk -ForceExtract -ForceRepack -SkipVerify
```

产物：

```text
.tmp\qqreader-c9f8-neutralized-v177-login-hook2-signed.apk
```

### 真机结果

日志文件：

```text
.tmp\device-logs\qqreader-v177-login-hook2-launch-20260617-103250.full.log
.tmp\device-logs\qqreader-v177-login-hook2-login-button-20260617-103633.full.log
.tmp\device-logs\qqreader-v177-login-hook2-pwd-login-20260617-103842.full.log
.tmp\device-logs\qqreader-v177-login-hook2-pwd-login-20260617-103842.exit-info.txt
```

验证结论：

- 分身启动成功，进程存活。
- 进入登录页成功，不再出现 AppCompat theme 崩溃。
- `qrCodeV2/pwdLogin` 不再触发 `UnsatisfiedLinkError` 闪退。
- `exit-info` 中 hook2 验证期间没有新的主进程 `APP CRASH(EXCEPTION)`；最近主进程 crash 仍是 10:16 的旧包崩溃。

关键日志：

```text
nativeRegisterBusinessStubs: registered=9 failed=0
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
RegisterNatives YWLoginManager: wrapped sendPhoneCode original=0x0
RegisterNatives YWLoginManager: wrapped qrCodeV2 original=0x0
wrapped_ywlogin_pwdLogin: original native not registered; reported callback error
```

### 当前阻塞

hook2 解决的是“登录动作 JNI 缺失导致闪退”，还没有解决“正常登录成功”。账号登录点击后 UI 仍卡在：

```text
正在登录，请稍候
```

原因是当前没有捕获到原始登录 native 实现，wrapper 只能防崩和上报错误，不能生成真实账号态、token、风控参数或服务端登录结果。候选库已经通过 guest ClassLoader 加载：

```text
libywad-own.so
libnativekey.so
libapp.so
libentryexpro.so
libQmt.so
```

但日志里没有出现来自这些库的 `YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2` 注册，只有 MultiApp 自己的 fallback 注册。

### 下一步

正常登录的下一步不是把登录方法 no-op，而是找出原始登录实现为什么没有注册：

1. 重新解压当前 hook2 APK 或原始 QQ 阅读 APK，扫全部 `.so` 和 dex，确认登录 JNI 是否被隐藏、动态解密或根本不在候选库。
2. 对 `System.loadLibrary/System.load/Runtime.nativeLoad` 加更细日志，记录 QQ 阅读尝试加载的原始库名和失败原因。
3. 如果原始登录实现确实不在 native 层可见，需要转向 Java 层登录网络调用 hook，而不是继续补 native no-op。

## 2026-06-17 v178/v179 Login Fallback Split

用户确认当前重点不是签名问题，而是手机号登录为什么不能正常完成。基于 v177 hook2 日志重新拆分登录 fallback。

### v178 结论

hook2 的 `nativeRegisterBusinessStubs: registered=9` 能防止登录页 JNI 缺失闪退，但会提前把这些登录动作注册成 MultiApp wrapper：

```text
YWLoginManager.pwdLogin(...)
YWLoginManager.sendPhoneCode(...)
YWLoginManager.qrCodeV2(...)
```

手机号/密码登录点击后进入的是 `wrapped_ywlogin_pwdLogin`，且：

```text
wrapped_ywlogin_pwdLogin: original native not registered; reported callback error
```

这说明卡在“正在登录，请稍候”的原因不是签名失败，也不是服务端拒绝，而是原始 `pwdLogin/sendPhoneCode` native 没注册出来；MultiApp fallback 只是在防崩，不能产生真实登录态。

v178 修改：

```text
core/hook/src/main/cpp/native-hook.cpp
```

- 新增 `debug.multiapp.ywlogin.action_fallback`。
- 默认不注册 `pwdLogin/sendPhoneCode` fallback，让真实 YWLogin SDK 有机会自己注册。
- 保留 `RegisterNatives` 捕获日志，用于判断是否出现非 `libmultiapp-native.so` 的原始函数指针。

构建产物：

```text
.tmp\qqreader-c9f8-neutralized-v178-login-no-action-fallback-signed.apk
```

验证启动时确认：

```text
nativeRegisterBusinessStubs: YWLoginManager action fallback disabled; waiting for real SDK RegisterNatives
nativeRegisterBusinessStubs: registered=6 failed=0
```

### v178 新暴露问题

设备：

```text
192.168.2.119:35353
```

日志：

```text
.tmp\device-logs\qqreader-v178-crash-20260617-161005.full.log
.tmp\device-logs\qqreader-v178-crash-20260617-161005.crash.log
.tmp\device-logs\qqreader-v178-crash-20260617-161005.exit-info.txt
.tmp\device-logs\qqreader-v178-no-online-fallback-start-20260617-161159.full.log
```

第一层崩溃来自章节 fallback，而不是登录：

```text
Fatal signal 11 (SIGSEGV)
libmultiapp-native.so
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterDownloadFallbackStubs
libart.so art::JavaVMExt::DecodeWeakGlobal
```

因此登录验证不能默认带入章节实验 fallback。测试脚本已调整：

```text
tools/qqreader-offline-patch/test-qqreader-offline.ps1
OnlineStateFallback = 0
```

关掉章节 fallback 后，启动进入扫码登录页时暴露真实登录 JNI 缺失：

```text
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.qrCodeV2(
  com.yuewen.ywlogin.callbacks.DefaultYWCallback
)
at com.qq.reader.login.scanqrcode.viewmodel.qdaa.b
at com.qq.reader.login.scanqrcode.ui.QRLoginScanQrCodeActivity.onCreate
```

### v179 当前策略

扫码页会在 `onCreate` 默认调用 `qrCodeV2`，它不是手机号/密码登录动作本身，但会阻断进入登录页。因此 v179 采用更细拆分：

- `qrCodeV2` 默认 fallback 防崩，允许登录页打开。
- `pwdLogin/sendPhoneCode` 默认不 fallback，继续暴露真实手机号登录 native 是否注册。
- `debug.multiapp.ywlogin.action_fallback=1` 才恢复旧的手机号/验证码动作 fallback。
- `debug.multiapp.ywlogin.qrcode_fallback=0` 可关闭二维码 fallback 做纯诊断。

当前必须继续验证的点：

1. 重包 v179 并安装。
2. 启动时确认：

```text
YWLoginManager action fallback disabled
YWLoginManager qrCodeV2 fallback enabled
```

3. 进入手机号登录后点击登录/发送验证码。
4. 如果出现 `pwdLogin/sendPhoneCode UnsatisfiedLinkError`，说明原始登录 native 仍未注册，下一步要追 `ReaderApplication.initLoginSDK` / YWLogin SDK 初始化链路或转 Java 登录网络链路。
5. 如果捕获到 `RegisterNatives YWLoginManager: captured pwdLogin original=...`，再继续看服务端返回、风控参数和账号态。

## 2026-06-17 v180-v183 Login Native vs Free Reading Split

本轮继续验证 QQ 阅读分身登录链路时，必须记住一个关键边界：

```text
登录诊断包 != 免费正文阅读包
```

v174/v175 已验证免费章节正文可读，依赖的是：

```text
debug.multiapp.online.run_fallback=1
debug.multiapp.online.materialize_eqct=1
QqReaderEqctPlaintextCompat
```

如果为了登录诊断关闭或不注册 `OnlineChapterDownloadTask.run()` fallback，免费文章仍会在阅读页触发后台下载任务，然后因为 `run()` 没有 native 实现而闪退。

### v180-v183 登录链路进展

v179 之后继续拆分 YWLogin fallback：

- `debug.multiapp.ywlogin.action_fallback=0` 默认不注册 `pwdLogin/sendPhoneCode` fallback，让真实 native 缺失直接暴露。
- `debug.multiapp.ywlogin.qrcode_fallback=1` 默认注册 `qrCodeV2` fallback，避免扫码登录页启动即崩。
- 新增 `OnlineChapterDownloadTask` state/queue 最小 stub，但不注册 `run()`：

```text
getDownloadChap()
getDownloadChapters()
setToDownloadChapters(List)
isBackgroundRun()
setBackgroundRun(boolean)
hasRetryTag()
setRetryTag()
getScene()
setScene(String)
```

v183 当前测试包：

```text
.tmp\qqreader-c9f8-neutralized-v183-online-task-queue-state-signed.apk
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
device=192.168.2.119:35353
```

该包适合登录 native 缺失诊断，不适合作为免费正文阅读验证包。

### 手机号登录当前阻塞

手动点击手机号/密码登录后，真机日志确认崩溃点为：

```text
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(
  android.app.Activity,
  java.lang.String,
  java.lang.String,
  com.yuewen.ywlogin.login.YWCallBack
)
```

证据文件：

```text
.tmp\device-logs\qqreader-v183-user-phone-action-watch-20260617-170402.full.log
.tmp\device-logs\qqreader-v183-user-phone-action-watch-20260617-170402.crash.log
.tmp\device-logs\qqreader-v183-user-phone-action-watch-20260617-170402.exit-info.txt
```

同一类问题也出现在验证码链路：

```text
YWLoginManager.sendPhoneCode(Context, String, int, int, YWCallBack)
```

结论：

- 不是签名问题。
- 当前只看到 MultiApp 自己注册 `YWLoginManager.getInstance/registerParameter/resetParameter/setDefaultParameters/fetchSettings/qrCodeV2`。
- 没看到 `libywad-own.so/libnativekey.so/libapp.so/libentryexpro.so/libQmt.so` 注册真实 `pwdLogin/sendPhoneCode`。
- `debug.multiapp.ywlogin.action_fallback=1` 只能防崩或回调错误，不能产生真实登录态。
- 下一步要么找到真实 YWLogin SDK native 注册入口，要么绕开这些 native 方法，改走 Java 登录网络链路并调用 `YWLoginManager.saveLoginStatus(...)` 写回登录态。

### 2026-06-17 免费文章闪退复盘

用户反馈“免费文章也会闪退”后重新抓日志，确认这不是 v174/v175 正文方案失效，而是当前安装的是登录诊断包，`OnlineChapterDownloadTask.run()` 没有注册。

证据文件：

```text
.tmp\device-logs\qqreader-free-read-retry-20260617-171836.full.log
.tmp\device-logs\qqreader-free-read-retry-20260617-171836.exit-info.txt
```

关键崩溃：

```text
java.lang.UnsatisfiedLinkError: No implementation found for void
com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run()
  at com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run(Native Method)
  at com.qq.reader.cservice.onlineread.qdaa$qdaa.run(Unknown Source:2)
  at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1302)
```

`exit-info` 同步确认：

```text
timestamp=2026-06-17 17:17:18.837
process=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
reason=4 (APP CRASH(EXCEPTION))
```

操作判断：

- 要验证登录：使用 v183 这类登录诊断包，保持 `pwdLogin/sendPhoneCode` 不 fallback，允许真实缺失暴露。
- 要验证免费阅读：必须打“阅读版”包，启用 `OnlineChapterDownloadTask.run()` fallback、mini materialize 和 `QqReaderEqctPlaintextCompat`。
- 不要用登录诊断包判断“免费章节方案坏了”；它本来就没有注册 `run()` fallback。

下一步建议：

1. 立刻重包一个 v184 reading 包，恢复：

```text
debug.multiapp.online.run_fallback=1
debug.multiapp.online.materialize_eqct=1
QqReaderEqctPlaintextCompat enabled
```

2. 登录链路另起 v184-login-java 或 v184-login-native-diag，不要污染阅读包。
3. 后续文档和 APK 命名必须区分：

```text
qqreader-*-reading-*.apk
qqreader-*-login-diag-*.apk
```

### 2026-06-17 v184 reading full fallback 注册阻塞

v184 reading 包已经恢复 `OnlineChapterDownloadTask.run()` fallback，但启动阶段在 full fallback 注册处 native 崩溃，阻塞免费正文验证。

证据文件：

```text
.tmp\qqreader-v184-reading-run-fallback-start-logcat.txt
.tmp\qqreader-v184-reading-run-fallback-start-crash.txt
.tmp\qqreader-v184-reading-run-fallback-start-exit-info.txt
```

关键日志：

```text
Installing OnlineChapterDownloadTask full fallback stubs
Fatal signal 11 (SIGSEGV)
libart.so art::JavaVMExt::DecodeWeakGlobal
libmultiapp-native.so Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterDownloadFallbackStubs
```

当前判断：

- 下一步阻塞点不是登录，而是阅读包 full fallback 注册本身。
- 登录诊断包和阅读包继续分开；不要用登录包验证免费正文。
- v184 崩溃点落在 native `CallObjectMethod` 路径，优先移除 `nativeRegisterOnlineChapterDownloadFallbackStubs()` 内部对 `remember_hook_classloader(env, clazz)` 的依赖。

v185 修复方向：

- Kotlin 侧由 `NativeHookBridge::class.java.classLoader` 显式传入 hook classloader。
- native 侧只保存这个传入对象的 `NewGlobalRef`，不再对 `jclass clazz` 调 `Class.getClassLoader()`。
- full fallback 注册改用本次传入 guest `classLoader` 的局部 `ClassLoader.loadClass`，并在 `loadClass`、批量 `RegisterNatives`、`run` 注册前记录日志。

验证目标：

```text
remember_hook_classloader_object: hook ClassLoader captured from NativeHookBridge
nativeRegisterOnlineChapterDownloadFallbackStubs: loading OnlineChapterDownloadTask
nativeRegisterOnlineChapterDownloadFallbackStubs: registering state/listener/url stubs
nativeRegisterOnlineChapterDownloadFallbackStubs: registering run fallback
nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback registered
```

如果 v185 仍闪退，优先看日志最后停在哪个阶段，而不是继续猜登录链路。

### 2026-06-17 v189 恢复 LSPlant 旧成功初始化路径

用户指出不应绕过 LSPlant，应先修复 LSPlant 初始化。经 `git log -S` / `git show` 对比确认：

- `f666d38 chore: 保存项目基线 - QQ Reader v174 正文加载打通` 中存在旧成功路径。
- `4603d45 feat: 项目整改 - 安全修复 + CI/CD + 模块拆分 + LSPlant修复` 对 `core/hook/src/main/cpp/native-hook.cpp` 做了大规模改动，并删除/改掉以下关键兼容点：
  - `multiapp_get_method_shorty`
  - `GetMethodShorty fallback`
  - `init_lsplant_internal`
  - `JNI_OnLoad: early LSPlant init begin`

v188 的失败根因不是 v175 文档方案失效，而是 Android 16 / API 36 上 LSPlant 找不到 ART 私有符号：

```text
LSPlant : Failed to find GetMethodShorty
nativeInitLsplant: lsplant::Init returned false
```

v189 修复内容：

- 在 `resolve_libart_symbol()` 中恢复 `_ZN3artL15GetMethodShortyEP7_JNIEnvP10_jmethodID` / `_ZN3art15GetMethodShortyEP7_JNIEnvP10_jmethodID` 到 `multiapp_get_method_shorty` 的 fallback。
- 恢复 `GetMethodShorty fallback` shorty 缓存，在调用 `g_lsplant_hook()` 前缓存 target/callback shorty。
- `nativeInitLsplant()` 开始时调用 `init_shadowhook_for_runtime("nativeInitLsplant")`。
- 恢复 `JNI_OnLoad` early init，避免 Java 层后置初始化错过早期 hook 时机。

验证命令：

```powershell
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug :app:assembleDebug
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 -VersionTag v189-restore-lsplant-v168 -OutputApk .tmp\qqreader-c9f8-neutralized-v189-restore-lsplant-v168-signed.apk -ForceExtract -ForceRepack -SkipVerify
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 -Connect 192.168.2.119:40097 -VersionTag v189-restore-lsplant-v168 -Apk .tmp\qqreader-c9f8-neutralized-v189-restore-lsplant-v168-signed.apk -WaitSeconds 45
```

v189 证据文件：

```text
.tmp\qqreader-v189-restore-lsplant-v168-start-logcat.txt
.tmp\qqreader-v189-restore-lsplant-v168-start-crash.txt
```

关键日志：

```text
JNI_OnLoad: early LSPlant init begin
libart resolver: using MultiApp GetMethodShorty fallback for _ZN3artL15GetMethodShortyEP7_JNIEnvP10_jmethodID
nativeInitLsplant: LSPlant initialized successfully!
HookEngine: === LSPlant.init() 成功 ===
nativeRegisterBusinessStubs: YWLoginManager action fallback disabled; waiting for real SDK RegisterNatives
nativeRegisterBusinessStubs: registered=9 failed=0
```

结论：

- LSPlant 初始化问题已经从“找不到 GetMethodShorty”推进为“初始化成功”。
- v189 仍闪退，但已不是 LSPlant 初始化失败。
- 新崩溃点为 JNI 引用类型错误：

```text
Abort message: 'Attempt to delete global reference reference as local JNI reference'
native: Java_com_multiapp_core_hook_NativeHookBridge_nativeHookMethodWithBackup
```

### 2026-06-17 v190 修复 LSPlant backup 引用删除崩溃

v189 崩溃原因：

- `g_lsplant_hook()` 在当前 Android 16 设备上返回的 backup 是 global reference。
- 当前 `nativeHookMethodWithBackup()` 对它执行：

```cpp
jobject globalBackup = env->NewGlobalRef(backup);
env->DeleteLocalRef(backup);
return globalBackup;
```

- `DeleteLocalRef(global)` 会触发 ART abort：

```text
local_reference_table.cc:452] Attempt to delete global reference reference as local JNI reference
```

v190 修复内容：

- `nativeHookMethodWithBackup()` 不再 `NewGlobalRef(backup)`，也不再 `DeleteLocalRef(backup)`，直接返回 LSPlant backup。
- `nativeHookMethod()` 成功后也不再 `DeleteLocalRef(backup)`，避免同类 abort。

验证状态：

```text
BUILD SUCCESSFUL: :core:hook:assembleDebug :app:assembleDebug
Generated APK: .tmp\qqreader-c9f8-neutralized-v190-lsplant-backup-ref-fix-signed.apk
```

v190 尚未完成真机验证。原因是设备无线调试端口在验证前掉线：

```text
192.168.2.119:40097 offline
adb install failed: device offline
adb connect 192.168.2.119:40097 timed out
```

下一步拿到新的无线调试端口后直接执行：

```powershell
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 -Connect <ip:port> -VersionTag v190-lsplant-backup-ref-fix -Apk .tmp\qqreader-c9f8-neutralized-v190-lsplant-backup-ref-fix-signed.apk -WaitSeconds 60
```

v190 验证目标：

```text
必须仍看到：
JNI_OnLoad: early LSPlant init begin
using MultiApp GetMethodShorty fallback
nativeInitLsplant: LSPlant initialized successfully
HookEngine: === LSPlant.init() 成功 ===

必须不再看到：
Attempt to delete global reference reference as local JNI reference
Fatal signal 6 at nativeHookMethodWithBackup
```

如果 v190 继续闪退，下一步不要回滚 LSPlant 修复，直接抓新日志并定位新的最后崩溃点。当前已证明 LSPlant 初始化本身可以恢复。

### 2026-06-18 v191/v192 LSPlant pass-through 异常语义修复

新的无线调试端口：

```text
device=192.168.2.119:37869
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

v190 真机验证结果：

- `v190-lsplant-backup-ref-fix` 已确认不再出现 `Attempt to delete global reference reference as local JNI reference`。
- LSPlant 保持初始化成功，`nativeHookMethodWithBackup` 能返回 backup。
- 新崩溃变为 Java 层 `LoaderFactory.preloadNativeForClass()` NPE，根因不是 LSPlant，而是通用反检测 hook 改变了 `Class.forName` 语义。

v190 关键证据：

```text
.tmp\qqreader-v190-lsplant-backup-ref-fix-start-logcat.txt
.tmp\qqreader-v190-lsplant-backup-ref-fix-start-crash.txt
HookEngine: === LSPlant.init() 成功 ===
nativeHookMethodWithBackup: success
LoaderFactory POC failed
Caused by: java.lang.NullPointerException
  at com.multiapp.core.loader.LoaderFactory.preloadNativeForClass(LoaderFactory.kt:2526)
```

v191 修复内容：

- `PackerDetectionBypass.hookClassForName()` 改用 `hookMethodPassThrough(...)`，普通类名继续调用原方法。
- `PackerDetectionBypass.hookClassLoaderLoadClass()` 也改用 `hookMethodPassThrough(...)`。
- 命中 hook 框架特征类名时，不再让 `beforeCallback` 返回 `null`，而是把类名改写到 `com.multiapp.blocked.*`，让原始 `Class.forName/loadClass` 自然返回 `ClassNotFoundException`。

v191 真机结果：

```text
APK=.tmp\qqreader-c9f8-neutralized-v191-class-forname-pass-through-signed.apk
log=.tmp\qqreader-v191-class-forname-pass-through-start-logcat.txt
crash=.tmp\qqreader-v191-class-forname-pass-through-start-crash.txt
```

v191 证明 `LoaderFactory.preloadNativeForClass` 的 NPE 已消失，但暴露出更底层的 pass-through 异常吞噬问题：

```text
java.lang.RuntimeException: Unable to start activity ... DefaultAliasSplashActivity
Caused by: android.view.InflateException: Binary XML file line #29 in android:layout/screen_simple
Caused by: java.lang.NullPointerException: Attempt to invoke virtual method
'java.lang.Class java.lang.Class.asSubclass(java.lang.Class)' on a null object reference
HookEngine: hookMethodPassThrough: callOriginal failed for forName
Caused by: java.lang.ClassNotFoundException: android.widget.ViewStub
Caused by: java.lang.NullPointerException: ClassLoader.loadClass returned null for android.widget.ViewStub
```

根因：

- `SimpleHooker.callback()` 对所有 hook 回调异常都 catch 后返回默认值。
- 对 `hookMethodPassThrough` 来说这是错误语义；`ClassNotFoundException` 应该继续抛给 Android framework，由 `LayoutInflater` 捕获后尝试下一个 view 前缀。
- 被吞掉后对象返回值变成 `null`，最终形成 `Class.asSubclass()` NPE。

v192 修复内容：

- `SimpleHooker.callOriginal()` 解包 `InvocationTargetException`，抛出真实 `targetException`。
- `SimpleHooker` 增加 `swallowCallbackExceptions` 参数，默认保持旧 skip-mode 行为。
- `HookEngine.hookMethodPassThrough()` 和 `HookEngine.hookMethodAround()` 使用 `swallowCallbackExceptions=false`，不再把原方法异常吞成默认返回值。

验证命令：

```powershell
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug :app:assembleDebug
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 -VersionTag v192-pass-through-exception-propagation -OutputApk .tmp\qqreader-c9f8-neutralized-v192-pass-through-exception-propagation-signed.apk -ForceExtract -ForceRepack -SkipVerify
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 -Connect 192.168.2.119:37869 -VersionTag v192-pass-through-exception-propagation -Apk .tmp\qqreader-c9f8-neutralized-v192-pass-through-exception-propagation-signed.apk -WaitSeconds 60
```

v192 结果：

```text
BUILD SUCCESSFUL
install=Success
LaunchState=COLD
Activity=com.qq.reader.activity.launch.DefaultAliasSplashActivity
No new AndroidRuntime FATAL in v192 crash file
pidof=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 -> 20732
```

v192 阅读链路证据：

```text
.tmp\qqreader-v192-pass-through-exception-propagation-start-logcat.txt
HookEngine: === LSPlant.init() 成功 ===
nativeHookMethodWithBackup: success
stub_online_run: protocol fallback returned resultCode=0
stub_online_run: mini materialize result .../14.eqct size=6782
stub_online_run: mini materialize result .../15.eqct size=8184
stub_online_run: mini materialize result .../13.eqct size=7279
stub_online_run: completed via mini materialized eqct resultCode=0
```

UI 验证：

```text
adb wakeup + am start 后，uiautomator 显示当前 package 为
com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
并处于阅读器页面，包含：
com.qq.reader:id/cvCurrentPager
com.qq.reader:id/pagefooter
com.qq.reader:id/read_page_status_bar
```

当前结论：

- LSPlant 初始化已经恢复，不应再把当前问题归类为 LSPlant 初始化失败。
- v189 修复 LSPlant 初始化，v190 修复 LSPlant backup JNI 引用，v191 修复 `Class.forName` 普通路径，v192 修复 pass-through 异常传播。
- v192 已能启动并进入阅读器页，免费章节 fallback 已能生成 `.eqct`。
- 手机号登录尚未在 v192 上完成手动真机验证；登录仍要重点确认 `YWLoginManager.pwdLogin/sendPhoneCode` 是否能走真实 native 或 Java 登录链路。

下一步：

1. 在手机上从 v192 当前应用进入登录页，手动触发手机号登录/发送验证码。
2. Codex 只负责抓日志，不需要用户解释崩溃；重点搜索：

```text
YWLoginManager
pwdLogin
sendPhoneCode
qrCodeV2
nativeRegisterBusinessStubs
wrapped_ywlogin
AndroidRuntime
FATAL EXCEPTION
UnsatisfiedLinkError
```

3. 如果仍是 `pwdLogin/sendPhoneCode UnsatisfiedLinkError`，下一步不要再改阅读 fallback，转到登录链路：恢复真实 YWLogin SDK native 注册，或实现 Java 登录网络链路并写回 `YWLoginManager.saveLoginStatus(...)`。

补充抓取：

```text
.tmp\qqreader-v192-login-manual-window-logcat.txt
.tmp\qqreader-v192-login-manual-window-crash.txt
.tmp\qqreader-v192-login-manual-window-exit-info.txt
```

该 90 秒窗口没有捕获到 QQ 阅读 `YWLoginManager/pwdLogin/sendPhoneCode/qrCodeV2` 相关动作，也没有新的 QQ 阅读 `AndroidRuntime FATAL`。这只能证明 v192 当前阅读页稳定，不能证明手机号登录已成功；登录验证仍需要手动触发登录按钮后重新抓日志。

### 2026-06-18 v193 最终 pass-through 语义包

v192 后做了一次代码语义收尾：

- `hookMethod()` 恢复默认 `SimpleHooker(method) { ... }`，保留 skip-mode/防崩 hook 的旧异常吞噬语义。
- `hookMethodPassThrough()` 使用 `swallowCallbackExceptions=false`。
- `hookMethodAround()` 也使用 `swallowCallbackExceptions=false`。

验证产物：

```text
APK=.tmp\qqreader-c9f8-neutralized-v193-pass-through-final-semantics-signed.apk
log=.tmp\qqreader-v193-pass-through-final-semantics-start-logcat.txt
crash=.tmp\qqreader-v193-pass-through-final-semantics-start-crash.txt
```

验证结果：

```text
install=Success
LaunchState=COLD
pidof=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 -> 24098
HookEngine: === LSPlant.init() 成功 ===
nativeHookMethodWithBackup: success
hookMethodPassThrough: successfully hooked java.lang.Class.forName
hookMethodPassThrough: successfully hooked java.lang.ClassLoader.loadClass
hookMethodAround: successfully hooked com.qq.reader.cservice.onlineread.qdae.search
```

v193 未再出现 v191 的崩溃特征：

```text
ClassLoader.loadClass returned null
Class.asSubclass(...) on a null object reference
AndroidRuntime FATAL EXCEPTION
```

UI 证据：

```text
uiautomator package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
页面包含书籍详情/试读内容、下载、听书、免费试读等节点。
```

当前可交付结论：

- v193 是目前应继续使用的 QQ 阅读专项包。
- LSPlant 初始化、backup hook、pass-through 异常传播、阅读页启动均已在真机验证。
- 登录仍未完成验证；下一步只围绕手机号登录按钮实际点击后的日志判断，不再回到 LSPlant 初始化问题。

### 2026-06-18 v193 手动登录日志

提交点：

```text
9de2d18 fix: stabilize QQ Reader clone startup
```

手动测试后抓取：

```text
device=192.168.2.119:37869
log=.tmp\qqreader-v193-login-manual-$(Get-Date -Format yyyyMMdd-HHmmss)-logcat.txt
crash=.tmp\qqreader-v193-login-manual-$(Get-Date -Format yyyyMMdd-HHmmss)-crash.txt
exit=.tmp\qqreader-v193-login-manual-$(Get-Date -Format yyyyMMdd-HHmmss)-exit-info.txt
```

注意：本次抓取脚本里的 PowerShell tag 字符串使用了单引号，文件名保留了字面量 `$(Get-Date -Format yyyyMMdd-HHmmss)`，但文件内容有效。

验证到的登录路径：

```text
QRLoginActivity -> LoginActivity
```

密码登录崩溃：

```text
06-18 09:08:16.564 AndroidRuntime: FATAL EXCEPTION: main
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(
  android.app.Activity,
  java.lang.String,
  java.lang.String,
  com.yuewen.ywlogin.login.YWCallBack
)
  at com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(Native Method)
  at com.yuewen.ywlogin.YWLogin.pwdLogin
  at com.yuewen.ywlogin.ui.model.LoginModel.pwdLogin
  at com.yuewen.ywlogin.ui.presenter.LoginPresenter.loginByAccount
  at com.yuewen.ywlogin.ui.activity.LoginActivity.loginByPassWord
  at com.yuewen.ywlogin.ui.activity.LoginActivity.onClick
```

发送验证码崩溃：

```text
06-18 09:09:16.777 AndroidRuntime: FATAL EXCEPTION: main
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.sendPhoneCode(
  android.content.Context,
  java.lang.String,
  int,
  int,
  com.yuewen.ywlogin.login.YWCallBack
)
  at com.yuewen.ywlogin.login.YWLoginManager.sendPhoneCode(Native Method)
  at com.yuewen.ywlogin.YWLogin.sendPhoneCode
  at com.qq.reader.qrlogin.qdab.search
  at com.qq.reader.qrlogin.apiimpl.LoginServerImpl.search
  at com.qq.reader.login.client.impl.QRLoginActivity.search
  at com.qq.reader.login.client.impl.QRLoginActivity$18.onClick
```

exit-info：

```text
2026-06-18 09:08:20 pid=24098 reason=4 (APP CRASH(EXCEPTION))
2026-06-18 09:09:16 pid=24491 reason=4 (APP CRASH(EXCEPTION))
```

当前结论：

- 登录崩溃不是 LSPlant 初始化问题，v193 中 LSPlant 仍成功。
- 手机号/密码登录和发送验证码都卡在 YWLogin SDK native 方法未注册。
- `debug.multiapp.ywlogin.action_fallback=1` 只能防崩或造回调，不能产生真实登录态；真正修复要么恢复真实 YWLogin native 注册链路，要么实现 Java 登录网络链路并写回登录态。

### 2026-06-18 v194 YWLogin 绑定诊断修正

v193 日志里有一个容易误判的点：

```text
Stage2 native method bound after libywad-own.so:
com.yuewen.ywlogin.login.YWLoginManager.getInstance
```

这只能证明 `YWLoginManager.getInstance()` 被绑定；它不能证明真正登录动作已经绑定。v193 手动登录已经反证：

```text
YWLoginManager.pwdLogin(...) -> UnsatisfiedLinkError
YWLoginManager.sendPhoneCode(...) -> UnsatisfiedLinkError
```

本轮代码修正：

- `NativeHookBridge.getYwLoginBindingReport()` 新增 native 绑定状态报告。
- native 侧直接输出已捕获的原始函数指针：

```text
pwdLogin=bound|missing ptr=...
sendPhoneCode=bound|missing ptr=...
qrCodeV2=bound|missing ptr=...
```

- `LoaderFactory.preloadGuestRuntimeNativeLibraries()` 在 Stage2 预加载后记录：

```text
Stage2 YWLoginManager.getInstance preload result=...
Stage2 YWLogin native binding report: ...
Stage2 YWLogin login actions are not bound; pwdLogin/sendPhoneCode will fail unless debug.multiapp.ywlogin.action_fallback=1
```

预期判断：

- 如果下一包日志显示 `pwdLogin=missing sendPhoneCode=missing`，说明继续加载 `libywad-own.so/libnativekey.so/libapp.so/libentryexpro.so/libQmt.so` 仍没有恢复真实登录 native，不能再把 Stage2 `.so` 加载成功当成登录链路修复。
- 如果日志显示 `pwdLogin=bound sendPhoneCode=bound`，再进入真实手机号登录测试，继续看服务端返回、风控参数、验证码和登录态写入。
- `debug.multiapp.ywlogin.action_fallback=1` 仍只作为防闪退诊断开关；它不能作为“正常登录可用”的完成标准。

### 2026-06-18 v194 手动登录日志结论

提交：

```text
516e828 fix: add QQ Reader login binding diagnostics
```

手动登录抓取文件：

```text
.tmp\qqreader-login-manual-20260618-094100-logcat.txt
.tmp\qqreader-login-manual-20260618-094100-crash.txt
.tmp\qqreader-login-manual-20260618-094100-exit-info.txt
```

设备：

```text
192.168.2.119:37869
```

分身包：

```text
com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

关键证据：

```text
Stage2 YWLoginManager.getInstance preload result=true
Stage2 YWLogin native binding report: pwdLogin=missing ptr=0x0 sendPhoneCode=missing ptr=0x0 qrCodeV2=missing ptr=0x0
Stage2 YWLogin login actions are not bound; pwdLogin/sendPhoneCode will fail unless debug.multiapp.ywlogin.action_fallback=1
```

同一轮手动测试里出现 3 次 Java exception crash：

```text
09:40:49 pwdLogin -> UnsatisfiedLinkError
09:41:12 sendPhoneCode -> UnsatisfiedLinkError
09:41:34 pwdLogin -> UnsatisfiedLinkError
```

crash buffer 中的两条代表性堆栈：

```text
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(...)
  at com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(Native Method)
  at com.yuewen.ywlogin.YWLogin.pwdLogin(Unknown Source:13)
  at com.yuewen.ywlogin.ui.model.LoginModel.pwdLogin(Unknown Source:11)
  at com.yuewen.ywlogin.ui.presenter.LoginPresenter.loginByAccount(Unknown Source:4)
  at com.yuewen.ywlogin.ui.activity.LoginActivity.loginByPassWord(Unknown Source:92)
```

```text
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.sendPhoneCode(...)
  at com.yuewen.ywlogin.login.YWLoginManager.sendPhoneCode(Native Method)
  at com.yuewen.ywlogin.YWLogin.sendPhoneCode(Unknown Source:9)
  at com.qq.reader.qrlogin.qdab.search(Unknown Source:85)
  at com.qq.reader.login.client.impl.QRLoginActivity.search(Unknown Source:29)
```

exit-info：

```text
09:41:34 reason=4 (APP CRASH(EXCEPTION))
09:41:13 reason=4 (APP CRASH(EXCEPTION))
09:40:51 reason=4 (APP CRASH(EXCEPTION))
```

结论：

- 当前设备上运行的是包含 v194 绑定报告的新包，不是旧包误判。
- QQ 阅读登录闪退仍不是主题、LSPlant、启动或阅读页问题。
- `libywad-own.so/libnativekey.so/libapp.so/libentryexpro.so/libQmt.so` 可以 `nativeLoad` 成功，但没有注册真实 `pwdLogin/sendPhoneCode`。
- `NativeHookBridge.nativeRegisterBusinessStubs` 只注册了当前 MultiApp stub/fallback 方法，例如 `getInstance/registerParameter/resetParameter/setDefaultParameters/fetchSettings/qrCodeV2`；它没有恢复真实登录动作。
- 下一步必须查真实 YWLogin native 注册入口或走 Java 登录链路；继续只加 `.so` 预加载没有证据能解决手机号登录。

### 2026-06-18 v197-v199 StubApp core native 保留与无效实验

v197 增加 `StubApp` 原始 native 绑定报告后，发现 `PackerRuntime.Jiagu` 阶段已经捕获到原始 core native：

```text
installStubFallback: QQ Reader StubApp native binding report: interface5=bound ... interface11=bound ... interface20=bound ...
installStubFallback: QQ Reader preserves original StubApp core natives
```

但同一包在 `:pushcore` 子进程里，`LoaderFactory` 后续逻辑又覆盖注册了 MultiApp 的 core fallback：

```text
QQ Reader child process ...:pushcore registers StubApp core fallback
```

v198 修正为：子进程也先读取 `NativeHookBridge.getStubAppBindingReport()`，只有 `interface11/interface20` 缺失时才注册 fallback；如果原始 core native 已绑定，就保留原始实现。

v198 证据：

```text
PackerRuntime.Jiagu: installStubFallback: QQ Reader StubApp native binding report: interface5=bound ... interface11=bound ... interface20=bound ...
PackerRuntime.Jiagu: installStubFallback: QQ Reader preserves original StubApp core natives
MultiApp.POC: QQ Reader child process ...:pushcore keeps original StubApp core natives
```

结论：

- v198 解决的是 `LoaderFactory` 子进程覆盖原始 `StubApp.interface11/interface20` 的问题。
- v198 不能解决登录，因为真实登录动作仍显示缺失：

```text
Stage2 YWLogin native binding report: pwdLogin=missing ptr=0x0 sendPhoneCode=missing ptr=0x0 qrCodeV2=missing ptr=0x0
YWLoginManager.pwdLogin(...) -> UnsatisfiedLinkError
```

v199 曾尝试在 `installPostLoadHooks` 中提前强制初始化 `YWLoginManager`，结果证明方向错误：

```text
installPostLoadHooks: QQ Reader forced YWLoginManager init failed: UnsatisfiedLinkError: No implementation found for void com.stub.StubApp.interface11(int)
Rejecting re-init on previously-failed class java.lang.Class<com.yuewen.ywlogin.login.YWLoginManager>
```

该实验会在 `installStubFallback` 之前触发 `YWLoginManager.<clinit>`，一旦当时 `StubApp.interface11` 还没有可用实现，就会把 `YWLoginManager` 标记为初始化失败类，后续无法恢复。因此不要再走“pre-stub 强制 `<clinit>`”路线。

当前保留策略：

- 保留 `getStubAppBindingReport()` 诊断。
- QQ Reader 专项路径下，原始 `StubApp.interface11/interface20` 已绑定时保留原始 native，不再覆盖。
- 登录问题继续聚焦真实 `YWLoginManager.pwdLogin/sendPhoneCode` 注册入口，或另行恢复 Java 登录链路；`debug.multiapp.ywlogin.action_fallback=1` 仍只能防闪退，不能作为登录成功标准。

### 2026-06-18 v200 新设备登录日志

设备：

```text
192.168.2.86:43063
```

安装确认：

```text
lastUpdateTime=2026-06-18 13:48:52
```

日志文件：

```text
.tmp\qqreader-login-v200-192.168.2.86-43063-20260618-135037-logcat.txt
.tmp\qqreader-login-v200-192.168.2.86-43063-aftercrash-20260618-135210-crash.txt
.tmp\qqreader-login-v200-192.168.2.86-43063-aftercrash-20260618-135210-exit-info.txt
```

启动阶段主进程证据：

```text
PackerRuntime.Jiagu: loadPackerLibrary: StubApp.load() invoked OK
PackerRuntime.Jiagu: verifyRegisterNatives: StubApp.interface20 registered=true
PackerRuntime.Jiagu: installStubFallback: QQ Reader StubApp native binding report:
interface5=missing ptr=0x0 interface11=missing ptr=0x0 interface20=missing ptr=0x0 interface21=missing ptr=0x0
PackerRuntime.Jiagu: installStubFallback: QQ Reader original StubApp core natives missing; registering core fallback
nativeRegisterStubCoreBootstrapMethods: registered interface5/interface11/interface20/interface21
```

Stage2 登录 native 仍缺失：

```text
Stage2 YWLoginManager.getInstance preload result=true
Stage2 YWLogin native binding report: pwdLogin=missing ptr=0x0 sendPhoneCode=missing ptr=0x0 qrCodeV2=missing ptr=0x0
```

手动密码登录崩溃：

```text
06-18 13:50:00.531 AndroidRuntime: FATAL EXCEPTION: main
java.lang.UnsatisfiedLinkError: No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(
  android.app.Activity,
  java.lang.String,
  java.lang.String,
  com.yuewen.ywlogin.login.YWCallBack
)
  at com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(Native Method)
  at com.yuewen.ywlogin.YWLogin.pwdLogin(Unknown Source:13)
  at com.yuewen.ywlogin.ui.model.LoginModel.pwdLogin(Unknown Source:11)
  at com.yuewen.ywlogin.ui.presenter.LoginPresenter.loginByAccount(Unknown Source:4)
  at com.yuewen.ywlogin.ui.activity.LoginActivity.loginByPassWord(Unknown Source:92)
  at com.yuewen.ywlogin.ui.activity.LoginActivity.onClick(Unknown Source:67)
```

exit-info：

```text
timestamp=2026-06-18 13:50:02.692
process=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
reason=4 (APP CRASH(EXCEPTION))
description=crash
```

补充判断：

- v200 已排除 v199 的 pre-stub 强制 `<clinit>` 失败路径；本次没有 `Rejecting re-init on previously-failed class`。
- `HookEngine ... loadClass com.yuewen.ywlogin.login.YWLoginManager.pwdLogin` 是全局 `ClassLoader.loadClass` 透传 hook 打出的 `ClassNotFoundException` 噪声，不是登录 hook 成功或失败的直接证据。
- v198 的“原始 `StubApp.interface11/interface20` 已绑定”证据出现在 `:pushcore`，v200 主进程登录链路仍没有拿到原始 `StubApp` core native。
- 下一步不能再只调 `.so` 预加载；要么找到主进程真实 `YWLoginManager.pwdLogin/sendPhoneCode` 注册入口，要么转 Java 层登录链路，在 `LoginModel.pwdLogin` / `LoginPresenter.loginByAccount` 之前拦截并实现真实登录/登录态写入诊断。

### 2026-06-18 v201 Java 登录链路拦截诊断

本轮新增 `QqReaderYwLoginJavaDiag`，用 LSPlant hook `com.yuewen.ywlogin.YWLogin` 的 Java 静态入口：

```text
pwdLogin(Activity, String, String, YWCallBack)
sendPhoneCode(Context, String, int, int, YWCallBack)
phoneLogin(String, String, String, YWCallBack)
```

静态 dump 证据：

```text
.tmp\v200-com_yuewen_ywlogin_ui_activity_LoginActivity.dump.txt
LoginActivity.loginByPassWord()
  -> LoginPresenter.loginByAccount(String,String)

.tmp\v200-com_yuewen_ywlogin_ui_presenter_LoginPresenter.dump.txt
LoginPresenter.loginByAccount(String,String)
  -> LoginModel.pwdLogin(String,String)

.tmp\v200-com_yuewen_ywlogin_ui_model_LoginModel.dump.txt
LoginModel.pwdLogin(String,String)
  -> YWLogin.pwdLogin(Activity,String,String,YWCallBack)

.tmp\v200-com_yuewen_ywlogin_YWLogin.dump.txt
YWLogin.pwdLogin(...)
  -> YWLoginManager.getInstance().pwdLogin(...)
```

v201 构建与安装：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
BUILD SUCCESSFUL

.tmp\qqreader-c9f8-neutralized-v201-java-login-diag-signed.apk
lastUpdateTime=2026-06-18 14:27:52
device=192.168.2.86:43063
```

v201 启动时 hook 安装成功：

```text
QqReaderYwLoginJavaDiag: java login diag installed=true results=[true, true, true]
MultiApp.POC: QQReader YWLogin java diag installed: true
```

手动点账号密码登录后，Java hook 已经在 native 调用前命中，且拿到账号、密码长度和 callback 类型：

```text
QqReaderYwLoginJavaDiag: pwdLogin before
activity=com.yuewen.ywlogin.ui.activity.LoginActivity
account=17***08
passwordLen=10
callback=com.yuewen.ywlogin.ui.model.LoginModel$1
```

随后原始 native 仍失败，但这次异常被 Java hook 捕获，没有再触发 `AndroidRuntime FATAL EXCEPTION`：

```text
QqReaderYwLoginJavaDiag: pwdLogin native missing; suppressing process crash and notifying callback:
No implementation found for void
com.yuewen.ywlogin.login.YWLoginManager.pwdLogin(
  android.app.Activity,
  java.lang.String,
  java.lang.String,
  com.yuewen.ywlogin.login.YWCallBack
)

QqReaderYwLoginJavaDiag: callback onError invoked code=-90101
```

运行状态证据：

```text
pidof com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
20578

.tmp\qqreader-login-v201-192-168-2-86-43063-20260618-143232-crash.txt
未出现新的 AndroidRuntime / FATAL EXCEPTION / YWLoginManager.pwdLogin 崩溃
```

结论：

- Java 层登录链路可以稳定拦截，位置选在 `YWLogin.pwdLogin(...)` 是有效的。
- v201 只解决“点击登录不再因为 `YWLoginManager.pwdLogin` 未注册而闪退”，没有产生真实登录态。
- 当前真实阻塞仍是 `YWLoginManager.pwdLogin/sendPhoneCode/phoneLogin/saveLoginStatus` native 未注册。
- 下一步要继续做真实登录，不能停在 `onError` fallback：
  - 优先继续追真实 native 注册入口，确认为什么主进程 `libjiagu_vip.so` 没有为 `YWLoginManager` 注册动作方法。
  - 备选路线是实现 Java 网络登录链路，但还需要找到真实接口、签名参数、响应结构，以及绕过/替代 native `saveLoginStatus(...)` 的登录态落盘路径。

### 2026-06-18 v203 Java 密码登录链路继续推进

v202 证明直接运行 `YWLoginManager$g0` 会继续撞到缺失 native：

```text
java.lang.UnsatisfiedLinkError:
No implementation found for com.yuewen.ywlogin.login.YWLoginManager.getSignCallback()
```

v203 在业务 native stub 中补齐：

```text
YWLoginManager.getSignCallback()
YWLoginManager.setSignCallback(ParamsSignCallback)
```

同时把 `QqReaderYwLoginJavaDiag.notifyError(...)` 改为主线程派发，避免 worker 线程直接回调 UI。

构建与安装：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug
BUILD SUCCESSFUL

.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:loader:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL

.tmp\qqreader-c9f8-neutralized-v203-ywlogin-sign-callback-signed.apk
device=192.168.2.86:43063
```

启动注册证据：

```text
nativeRegisterBusinessStubs: com.yuewen.ywlogin.login.YWLoginManager.getSignCallback OK
nativeRegisterBusinessStubs: com.yuewen.ywlogin.login.YWLoginManager.setSignCallback OK
nativeRegisterBusinessStubs: registered=15 failed=0
QqReaderYwLoginJavaDiag: java login diag installed=true results=[true, true, true]
Stage2 YWLogin native binding report: pwdLogin=missing ptr=0x0 sendPhoneCode=missing ptr=0x0 qrCodeV2=missing ptr=0x0
```

手动账号密码登录后，v203 已经不再因为 `YWLoginManager.pwdLogin(...)` 或 `getSignCallback()` 缺失而闪退，Java fallback 能跑完整个网络任务：

```text
QqReaderYwLoginJavaDiag: pwdLogin before ...
QqReaderYwLoginJavaDiag: pwdLogin native missing; suppressing process crash ...
QqReaderYwLoginJavaDiag: pwdLogin java fallback scheduled
QqReaderYwLoginJavaDiag: pwdLogin java task running
stub_ywlogin_getSignCallback: returning instance callback=0x0
YWLoginMtaNet: response.isSuccessful() is true
QqReaderYwLoginJavaDiag: pwdLogin java task returned
```

但登录还没有成功，界面仍停在登录页，业务层失败日志为：

```text
YWLoginNewMtaUtil: /sdk/staticlogin - 350
YWLoginNewMtaUtil: 阅文账号登录失败 - yw_login - 3
```

结论：

- v203 解决的是登录点击闪退和 `getSignCallback()` native 缺失。
- 当前阻塞已经从“native 未注册导致进程崩溃”推进到“Java 登录网络任务返回业务失败”。
- 不能把 v203 视为登录完成；下一步需要抓取脱敏后的响应 `code/message/data.nextAction`，判断是账号密码业务错误、风控/滑块/短信二次验证，还是登录态保存 native 缺失。

### 2026-06-18 v204 响应解析诊断

为避免继续凭 `YWLoginNewMtaUtil` 的聚合码猜测，v204 在 `QqReaderYwLoginJavaDiag` 新增两个 LSPlant 诊断点：

```text
b.a.a.search.qdaa.search(YWHttpResponse, Handler, YWCallBack, boolean)
b.a.a.search.qdaa.search(int, String, Handler, YWCallBack)
```

日志只记录脱敏诊断字段：

```text
response success/code/businessCode
originUrl/lastActionUrl 去除 query
apiCode/message
data keys
data.nextAction
hasYwGuid/hasYwKey/hasTicket/hasToken/hasPhoneKey
```

明确不打印账号、密码、token、ticket、完整响应体。v204 的目标是确认真实失败分支，再决定下一步：

- `apiCode != 0`：优先处理服务端业务错误或风控路径。
- `nextAction` 存在：实现对应滑块/短信/验证链路，而不是强行写登录态。
- `apiCode == 0` 且进入 `saveLoginStatus(...)` 后失败：再补 `saveLoginStatus(JSONObject)` 等登录态 native stub。

### 2026-06-18 v205/v206 手机号 Java 登录链路

v205 在 `QqReaderYwLoginJavaDiag` 中继续扩展手机验证码链路：

```text
YWLogin.sendPhoneCode(Context, String, int, int, YWCallBack)
  -> native missing
  -> YWLoginManager$c Java task fallback

YWLogin.phoneLogin(String, String, String, YWCallBack)
  -> native missing
  -> 从 callback.this$0.mSessionKey 取短信 sessionKey
  -> YWLoginManager$z Java task fallback
```

静态依据：

```text
YWLoginManager$c:
  写入 phone/type/sessionKey/code/sig/needRegister
  POST Urls.I()
  成功后 callback.onSendPhoneCode(sessionKey)

YWLoginManager$z:
  写入 phone/phonecode/sessionKey
  POST Urls.p()
  走 qdaa.i(...) 解析并保存登录状态

LoginModel$1.onSendPhoneCode(sessionKey):
  同步写回 mPhoneKey 和 mSessionKey
```

v205 构建和打包通过：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
BUILD SUCCESSFUL

.tmp\qqreader-c9f8-neutralized-v205-ywlogin-phone-java-fallback-signed.apk
```

v205 启动证据：

```text
QqReaderYwLoginJavaDiag: java login diag installed=true results=[true, true, true, true, true]
nativeRegisterBusinessStubs: registered=15 failed=0
Stage2 YWLogin native binding report: pwdLogin=missing ptr=0x0 sendPhoneCode=missing ptr=0x0 qrCodeV2=missing ptr=0x0
```

v205 用 ADB 坐标点击“我的”页登录横幅没有触发跳转；直接 `adb shell am start` 非导出 `QRLoginActivity` 被系统拒绝：

```text
java.lang.SecurityException:
Permission Denial: starting Intent ... QRLoginActivity ... not exported from uid 10424
```

因此 v206 新增一个只用于 QQ 阅读专项调试的自动打开入口：

```text
debug.multiapp.ywlogin.auto_open=1
```

实现位置：`LoaderFactory.registerActivityContextWrapper(...).onActivityResumed`。当满足以下条件时，使用当前 Activity 在同包进程内启动 `com.qq.reader.login.client.impl.QRLoginActivity`：

```text
cloneProfile == QQ_READER_SPECIAL 或 originalPkg == com.qq.reader
debug.multiapp.ywlogin.auto_open=1
当前 Activity 不是 QRLoginActivity / YWLogin UI
```

默认不开启，不影响普通启动。v206 构建和打包通过：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
BUILD SUCCESSFUL

.tmp\qqreader-c9f8-neutralized-v206-ywlogin-auto-open-signed.apk
```

v206 真机验证：

```text
device=192.168.2.86:43063
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
pid=4049

QQ Reader auto_open login started from com.qq.reader.activity.launch.DefaultAliasSplashActivity
topResumedActivity=.../com.qq.reader.login.client.impl.QRLoginActivity
```

随后已通过 UI tree 进入手机号验证码页：

```text
resource-id="com.qq.reader:id/ywlogin_phoneInput" text="请输入手机号"
resource-id="com.qq.reader:id/ywlogin_sendCode" text="获取验证码" enabled="false"
resource-id="com.qq.reader:id/ywlogin_codeInput" text="请输入短信验证码"
resource-id="com.qq.reader:id/ywlogin_phoneLoginOrBind" text="登 录" enabled="false"
```

当前状态：

- v206 解决了“ADB 无法稳定打开登录页”的验证阻塞。
- `sendPhoneCode/phoneLogin` Java fallback 已实现，但还没有用户输入手机号并触发“获取验证码”的运行证据。
- 下一步必须在真机上输入手机号并点击“获取验证码”，抓取以下日志判断：

```text
sendPhoneCode before
sendPhoneCode native missing
sendPhoneCode java fallback scheduled/running/returned
response parser before/after
login error dispatch
```

如果验证码发送成功，再输入短信验证码并点击登录，继续抓：

```text
phoneLogin before
phoneLogin native missing
phoneLogin java fallback scheduled/running/returned
response parser before/after
saveLoginStatus / AndroidRuntime / FATAL EXCEPTION
```

### 2026-06-18 v207/v208 阅文账号登录参数链路修复

用户确认主线不是验证码登录，而是“阅文账号登录”；验证码页“获取验证码”控件禁用属于旁支。v207 的真机日志仍然有价值：它证明短信 Java fallback 已经能拦住 native missing，不再让进程崩溃，但公共参数为空：

```text
sendPhoneCode native missing; suppressing process crash
sendPhoneCode java fallback params normalized=true ywguid=false ywkey=false
response parser before ... origin=https://ptlogin.yuewen.com/sdk/sendphonecode ... apiCode=3 message=登录失败，请稍后重试
```

同一轮密码登录日志也显示 Java fallback 能跑 `YWLoginManager$g0`，但服务端返回：

```text
origin=https://ptlogin.yuewen.com/sdk/staticlogin
apiCode=3 message=登录失败，请稍后重试
hasYwGuid=false hasYwKey=false
```

静态 dump 复核：

```text
YWLoginManager$g0.run():
  manager.getDefaultParameters()
  put username
  put password(md5/encoded)
  POST Urls.s() -> /sdk/staticlogin
```

因此当前阻塞不是 `LoginActivity/LoginPresenter/LoginModel` UI 层，也不是 LSPlant。阻塞点是 `YWLoginManager.getDefaultParameters()` 返回的公共参数不完整，导致 `/sdk/staticlogin` 和 `/sdk/sendphonecode` 都被服务端以业务码 3 拒绝。

v208 修复点：

```text
native-hook.cpp:
  registerParameter(IParameterGetter) 保存原 App 的 parameterGetter
  setDefaultParameters(Application, ContentValues) 保存 mContext/mDefaultParameters
  resetParameter(key, value) 写入缓存
  getDefaultParameters()/getCommonParamaters() 返回缓存并合并 parameterGetter.getParameter()
  日志只输出 ContentValues key 集合，不输出 token/账号/密码等值

QqReaderYwLoginJavaDiag.kt:
  pwdLogin Java fallback 增加 ywguid/ywkey 是否存在的脱敏诊断
```

v208 本地验证：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug
BUILD SUCCESSFUL

.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
BUILD SUCCESSFUL
```

下一轮真机验证重点：

```text
stub_ywlogin_registerParameter_tracked: stored getter=...
stub_ywlogin_setDefaultParameters_tracked: ... keys=[...]
stub_ywlogin_getDefaultParameters_tracked: ... mergedDynamic=1 keys=[..., ywguid, ywkey, ...]
pwdLogin java fallback params ywguid=true ywkey=true
response parser before ... /sdk/staticlogin ... apiCode=...
```

如果 v208 仍然返回 `apiCode=3` 且 `ywguid/ywkey=true`，下一步才进入签名回调 `ParamsSignCallback.signParams(...)` 或服务端风控链路，而不是继续改 UI 点击路径。

### 2026-06-18 v209-v213 阅文登录签名回调与最终请求诊断

v209-v213 继续沿“阅文账号密码登录”主线验证，目标是确认 `/sdk/staticlogin` 失败到底是请求签名缺失，还是更早的真实 native 登录链路未恢复。

本轮关键变化：

```text
QqReaderYwLoginJavaDiag.kt:
  YWLogin.init(...) 后检查并注入 ParamsSignCallback 动态代理
  hook YWLogin.setParamsSignCallback(...) 记录原 App 是否设置回调
  hook YWHttp.post(String, ContentValues) 记录脱敏入参
  hook OkHttpClient.newCall(Request) 记录最终 ptlogin 请求是否带关键 header
  pwdLogin/sendPhoneCode/phoneLogin native missing 时继续 Java task fallback
```

v212 构建、安装、启动和登录点击验证通过：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug :app:assembleDebug
BUILD SUCCESSFUL

.tmp\qqreader-c9f8-neutralized-v212-ywlogin-head-sign-signed.apk
```

v212 真机证据：

```text
ParamsSignCallback 已注入
signParams(...) 被 OkPostRunnable.post() 调用
QqReaderSignCompat.sign(...) 通过 FockRT 产出 38 位签名
请求已补 qrsn/c_version/version_code/ttime/ssign/ssign_version/signature/sign
```

但服务端仍然返回：

```text
origin=https://ptlogin.yuewen.com/sdk/staticlogin
apiCode=3
message=登录失败，请稍后重试
```

v213 继续增加 `OkHttpClient.newCall(Request)` 最终请求诊断，并补 `trace_id`。该版本验证了“最终发出的 request header”已经包含当前能补齐的签名字段：

```text
qrsn=present
c_version=present
version_code=present
ttime=present
ssign=present
ssign_version=present
signature=present
sign=present
trace_id=present
```

v213 证据文件：

```text
.tmp\qqreader-v213-ui.xml
.tmp\qqreader-v213-login-click-20260618-181912-logcat.txt
.tmp\qqreader-v213-login-click-20260618-181912-filtered.txt
```

结论：

- 当前不是 LSPlant 初始化失败；`QqReaderYwLoginJavaDiag`、`ParamsSignCallback`、`OkHttpClient.newCall(...)` hook 都已生效。
- 当前不是“最终请求没带签名 header”；v213 已确认最终 `Request` 带有关键签名 header。
- v175 的成功案例是免费章节正文/标题链路，不是阅文账号登录成功案例，不能直接当作登录修复回滚目标。
- 登录主阻塞仍是 `YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2` 的真实 native 实现没有注册；Java fallback 能发请求，但服务端业务返回 `apiCode=3`。
- `ParamsSignCallback.signParams(...)` 在 `OkPostRunnable.post()` 中发生在 `FormBody.build()` 之后，因此它只能补 header，不能修改已经构造好的 body。
- 继续只补 header 已经没有收益。下一步应该查真实登录 native 注册入口，尤其确认离线 patch 移除 outer `libjiagu_vip*.so` 是否让 `YWLoginManager` 动作方法永远缺失。

后续实验建议：

```text
1. 做一个保留 outer libjiagu_vip*.so 的实验包。
2. 启动后抓 RegisterNatives YWLoginManager 日志。
3. 如果出现非 libmultiapp-native.so 的 pwdLogin/sendPhoneCode/qrCodeV2 函数指针，
   优先走 wrapped_ywlogin_* 转发真实 native。
4. 如果仍然没有真实 native 注册，再继续深挖 Java fallback 的 body 等价性：
   password 加密、ticket/ywguid/ywkey 生成、fetchSettings/refreshParameters/getLoginData。
```

### 2026-06-18 v213 端口 37839 现场状态

设备切换到：

```text
device=192.168.2.86:37839
```

现场确认：

```text
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
main pid=26151
pushcore pid=20049
lastUpdateTime=2026-06-18 18:16:51
```

`ApplicationExitInfo` 中出现一次主进程 native crash：

```text
timestamp=2026-06-18 18:17:09.297
process=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
reason=5 (APP CRASH(NATIVE))
status=11
```

但现场进程随后又被拉起并保持存活，当前 UI 停在“阅文账号登录”页，账号/密码输入框已有内容，登录按钮可点击。后续日志和文档必须继续脱敏，不能记录账号、密码、token、ticket 明文。

### 2026-06-18 原版 QQ 阅读登录对比

为避免继续只看分身侧日志，已在同一台设备、同一网络、同一账号输入状态下启动原版 QQ 阅读并抓取登录点击日志：

```text
device=192.168.2.86:37839
package=com.qq.reader
versionName=8.5.1.890
versionCode=414
entry=com.qq.reader/.activity.DefaultAliasActivity
pid=19379
```

证据文件：

```text
.tmp\qqreader-original-login-click-20260618-182557-logcat.txt
.tmp\qqreader-original-login-click-20260618-182557-filtered.txt
.tmp\qqreader-original-login-click-20260618-182557-wide-filtered.txt
.tmp\qqreader-original-login-click-20260618-182557-ui.xml
.tmp\qqreader-original-login-key-extract.txt
```

原版点击“阅文账号登录”后的关键路径：

```text
YWLoginMtaNet: response.isSuccessful() is true
YWLoginNewMtaUtil: /sdk/staticlogin - 247
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，触发 - yw_login_action_tencentCaptcha - 1
YWLoginNewMtaUtil: 二次验证-腾讯滑块触发 - tencentCaptcha_verification - 1
YWLoginNewMtaUtil: 腾讯滑块验证成功 - tencentCaptcha_verification - 2
YWLoginNewMtaUtil: /sdk/checkcodelogin - 280
YWLoginNewMtaUtil: 阅文账号登录失败 - yw_login - 3
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，失败 - yw_login_action_tencentCaptcha - 3
```

原版没有主进程 Java crash 或 native crash：

```text
pidof com.qq.reader -> 19379
dumpsys activity exit-info com.qq.reader:
  本轮只有 WebView isolated process 被系统回收，以及手动 force-stop 记录；
  没有 com.qq.reader 主进程 APP CRASH。
```

和分身 v213 的关键差异：

```text
分身 v213:
  pwdLogin native missing
  Java fallback 调用 YWLoginManager$g0
  YWHttp.post /sdk/staticlogin body keys=[appid, areaid, auto, autotime, devicetype, ibex, osversion, password, qimei, qimei36, sdkversion, source, ticket, username, version]
  ywguid=false ywkey=false
  final Request headers 包含 qrsn/c_version/version_code/ttime/ssign/ssign_version/signature/sign/trace_id
  response apiCode=3 data=null
  未进入 tencentCaptcha 分支

原版:
  /sdk/staticlogin 返回 247
  进入 tencentCaptcha 二次验证
  滑块成功后继续 /sdk/checkcodelogin
```

当前更准确的目标不是“让分身直接登录成功”，而是先让分身请求等价到原版行为：`/sdk/staticlogin -> 247 -> tencentCaptcha`。只要分身仍然直接 `apiCode=3` 且 `data=null`，说明 Java fallback 请求与原版真实登录请求仍不等价。

下一步优先级：

```text
1. 恢复或捕获真实 YWLoginManager.pwdLogin native 注册。
   这是最可能让 staticlogin 从 apiCode=3 变成原版 247 的路径。

2. 做保留 outer libjiagu_vip*.so 的实验包，抓：
   RegisterNatives YWLoginManager: captured pwdLogin original=...
   RegisterNatives YWLoginManager: captured sendPhoneCode original=...
   wrapped_ywlogin_pwdLogin: forwarding original=...

3. 如果真实 native 仍无法恢复，再比较 Java fallback body：
   是否缺少 native 生成的 ywguid/ywkey 或其它 body 字段；
   是否密码加密/签名前 raw 串与真实 native 不一致；
   是否需要先跑 fetchSettings/refreshParameters 后再 pwdLogin。
```

### 2026-06-18 v214 preserve outer jiagu 实验

为验证“离线 patch 删除 outer `libjiagu_vip*.so` 是否导致登录 native 丢失”，新增并构建了保留 outer jiagu 的实验包：

```text
apk=.tmp\qqreader-c9f8-neutralized-v214-preserve-outer-jiagu-signed.apk
device=192.168.2.86:37839
package=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
lastUpdateTime=2026-06-18 18:37:10
```

脚本变化：

```text
tools\qqreader-offline-patch\patch-qqreader-clone.ps1
  新增 -PreserveOuterJiagu

tools\qqreader-offline-patch\build-qqreader-offline.ps1
  新增 -PreserveOuterJiagu，并改为 hashtable splatting 调用 patch 脚本
```

v214 APK 内容同时包含 outer jiagu 与 MultiApp native：

```text
lib/arm64-v8a/libjiagu_vip.so
lib/arm64-v8a/libjiagu_vip_x86.so
lib/armeabi-v7a/libjiagu_vip.so
lib/armeabi-v7a/libjiagu_vip_x86.so
lib/arm64-v8a/libmultiapp-native.so
lib/armeabi-v7a/libmultiapp-native.so
```

证据文件：

```text
.tmp\qqreader-v214-preserve-jiagu-start-20260618-183903-logcat.txt
.tmp\qqreader-v214-preserve-jiagu-start-20260618-183903-filtered.txt
.tmp\qqreader-v214-preserve-jiagu-start-20260618-183903-ui.xml
```

关键日志：

```text
patchJiaguSo: disabled; preserving original libjiagu_vip.so
PackerRuntime.Jiagu: detect: libjiagu_vip.so found at ...
nativeloader: Load .../libjiagu_vip.so ... ok
nativeGotHookLibrary: hooking GOT for libjiagu_vip.so
got_hook: hooked 7 functions in ... libjiagu_vip.so
```

但真实登录动作 native 仍未恢复：

```text
nativeRegisterBusinessStubs: YWLoginManager action fallback disabled; waiting for real SDK RegisterNatives
Stage2 YWLogin native binding report: pwdLogin=missing ptr=0x0 sendPhoneCode=missing ptr=0x0 qrCodeV2=missing ptr=0x0
Stage2 YWLogin login actions are not bound; pwdLogin/sendPhoneCode will fail unless debug.multiapp.ywlogin.action_fallback=1
```

结论：

```text
保留 outer libjiagu_vip*.so 能让 jiagu 加载并应用 GOT hook，
但没有让原版 YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2 注册回来。

因此“删除 outer jiagu”不是登录 native 丢失的唯一原因。
下一步要定位真正注册登录动作 native 的库或 native 自检条件。
```

### 2026-06-18 18:42 原版二次登录采样

按同口径重新抓取原版 `com.qq.reader` 登录记录，用户操作同一条“阅文账号登录”链路。日志已脱敏，敏感值不写入文档。

证据文件：

```text
.tmp\qqreader-original-live-20260618-184254-logcat.txt
.tmp\qqreader-original-live-20260618-184254-filtered.txt
.tmp\qqreader-original-live-20260618-184254-ui.xml
```

关键路径：

```text
YWLoginNewMtaUtil: 阅文账号登录触达 - yw_login - 1
YWLoginMtaNet: response.isSuccessful() is true
YWLoginNewMtaUtil: /sdk/staticlogin - 246
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，触发 - yw_login_action_tencentCaptcha - 1
YWLoginNewMtaUtil: 二次验证-腾讯滑块触发 - tencentCaptcha_verification - 1
YWLoginNewMtaUtil: 腾讯滑块验证成功 - tencentCaptcha_verification - 2
YWLoginNewMtaUtil: /sdk/checkcodelogin - 318
YWLoginNewMtaUtil: 阅文账号登录失败 - yw_login - 3
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，失败 - yw_login_action_tencentCaptcha - 3
```

对比结论更新：

```text
原版两次采样都能从 /sdk/staticlogin 进入 tencentCaptcha 风控链路。
分身 v213/v214 仍然没有真实 pwdLogin native，Java fallback 只能自造 /sdk/staticlogin 请求。
分身请求虽然补了 qrsn/c_version/version_code/ttime/ssign/ssign_version/signature/sign/trace_id，
但仍缺少原版 native 登录动作产生的等价状态，表现为 apiCode=3、data=null、没有 tencentCaptcha。
```

### 2026-06-18 19:12 v215 原版对比与结论

v215 改动目标：让 `YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2` 在真实 native 指针缺失时主动抛出
`UnsatisfiedLinkError`，由 Java 侧诊断 hook 捕获并进入已有 Java fallback，避免直接崩溃。同时默认开启
`debug.multiapp.ywlogin.action_fallback`，显式设置 `0/false` 才关闭。

构建与安装：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug

.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v215-action-fallback-throw `
  -InputCloneApk .tmp\qqreader-c9f8-current-base.apk `
  -OutputApk .tmp\qqreader-c9f8-neutralized-v215-action-fallback-throw-signed.apk `
  -ForceRepack -SkipVerify -PreserveOuterJiagu

adb install -r -d .tmp\qqreader-c9f8-neutralized-v215-action-fallback-throw-signed.apk
```

证据文件：

```text
.tmp\qqreader-original-login-live-20260618-190612-logcat.txt
.tmp\qqreader-original-login-live-20260618-190612-narrow.txt
.tmp\qqreader-original-login-live-20260618-190612-ui.redacted.xml

.tmp\qqreader-v215-login-live-20260618-190904-logcat.txt
.tmp\qqreader-v215-login-live-20260618-190904-narrow.txt
.tmp\qqreader-v215-login-live-20260618-190904-ui.redacted.xml
```

原版 `com.qq.reader` 同路径“阅文账号登录”链路：

```text
YWLoginNewMtaUtil: /sdk/staticlogin - 182
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，触发 - yw_login_action_tencentCaptcha - 1
YWLoginNewMtaUtil: 二次验证-腾讯滑块触发 - tencentCaptcha_verification - 1
YWLoginNewMtaUtil: 腾讯滑块验证成功 - tencentCaptcha_verification - 2
YWLoginNewMtaUtil: /sdk/checkcodelogin - 253
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，失败 - yw_login_action_tencentCaptcha - 3
```

v215 分身同路径链路：

```text
nativeInitLsplant: LSPlant initialized successfully
nativeRegisterBusinessStubs: YWLoginManager action fallback enabled by debug.multiapp.ywlogin.action_fallback
nativeRegisterBusinessStubs: registered=17 failed=0
PackerRuntime.Jiagu: installStubFallback: QQ Reader StubApp native binding report: interface5=missing ptr=0x0 interface11=missing ptr=0x0 interface20=missing ptr=0x0 interface21=missing ptr=0x0
stub_interface11 fallback value=59494
wrapped_ywlogin_pwdLogin: original native not registered; throwing for Java fallback
pwdLogin native missing; suppressing process crash and notifying callback
pwdLogin java fallback scheduled
signParams signed via com.multiapp.core.loader.QqReaderSignCompat.sign rawLen=1193 signLen=38
YWLoginNewMtaUtil: /sdk/staticlogin - 154
YWHttp.post after ... apiCode=3 message=登录失败，请稍后重试 data=null nextAction=none
```

退出状态：

```text
pidof com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 => 27220
本轮登录后进程仍在，crash buffer 无新 FATAL；最近 exit-info 是本轮测试前 force-stop。
```

当前结论：

```text
1. v215 证明 LSPlant 初始化不是当前登录失败的阻塞点。
2. Java fallback 已能注入 ParamsSignCallback，并通过 QqReaderSignCompat 生成 38 位签名。
3. 即使请求头和 sign/ssign 补齐，分身仍只返回 apiCode=3，没有进入原版的 tencentCaptcha/checkcodelogin。
4. 关键缺口仍是原版壳侧 StubApp.interface11(59494) 没有真实执行，导致 YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2 的真实 native 指针为 0x0。
5. 下一步不应继续堆 Java fallback 参数，而应恢复或复现 interface11(59494) 对 YWLoginManager 登录动作 native 的真实注册路径。
```

### 2026-06-18 20:09 原版重抓对比

设备与对象：

```text
device: 192.168.2.86:37839
original package: com.qq.reader
clone reference: com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

证据文件：

```text
.tmp\qqreader-original-compare-20260618-200948-logcat.txt
.tmp\qqreader-original-compare-20260618-200948-narrow.redacted.txt
.tmp\qqreader-original-compare-20260618-200948-keyflow.redacted.txt
.tmp\qqreader-original-compare-20260618-200948-ui.xml
.tmp\qqreader-v215-login-live-20260618-190904-keyflow.redacted.txt
```

原版 `com.qq.reader` 这次再次复现同一条风控链路：

```text
YWLoginNewMtaUtil: /sdk/staticlogin - 427
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，触发 - yw_login_action_tencentCaptcha - 1
YWLoginNewMtaUtil: 二次验证-腾讯滑块触发 - tencentCaptcha_verification - 1
YWLoginNewMtaUtil: 腾讯滑块验证成功 - tencentCaptcha_verification - 2
YWLoginNewMtaUtil: /sdk/checkcodelogin - 428
YWLoginNewMtaUtil: 账号登录触发风控后进行滑块登录，失败 - yw_login_action_tencentCaptcha - 3
```

和 v215 分身日志计数对比：

```text
original-20260618-200948:
  /sdk/staticlogin=1
  tencentCaptcha=4
  /sdk/checkcodelogin=1
  apiCode=3=0
  FATAL EXCEPTION=0
  Fatal signal=0

clone-v215-20260618-190904:
  /sdk/staticlogin=7
  tencentCaptcha=0
  /sdk/checkcodelogin=0
  apiCode=3=3
  ParamsSignCallback=16
  QqReaderSignCompat=37
  pwdLogin original=0x0=2
  sendPhoneCode original=0x0=2
  FATAL EXCEPTION=0
  Fatal signal=0
```

追加结论：

```text
1. 原版同机同日重抓确认：阅文账号登录失败前会先进入 tencentCaptcha/checkcodelogin 风控链路。
2. v215 分身不是缺少 Java 层请求触发，也不是单纯缺少 sign/ssign；它已经能发 /sdk/staticlogin 并补签名，但服务端直接返回 apiCode=3。
3. 分身缺失的是原版 native 登录动作产生的等价上下文/注册状态；当前最强证据仍指向 YWLoginManager.pwdLogin/sendPhoneCode 的 original native pointer 为 0x0。
4. 下一步应继续围绕 StubApp.interface11(59494) / YWLoginManager native 注册恢复，而不是继续补 Java fallback 参数。
```

### 2026-06-18 20:34 v221 主动调用 original interface11(59494) 尝试

目标：在捕获到 original `StubApp.interface11` 后，主动调用一次 `interface11(59494)`，
触发壳对 `YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2` 的真实 native 注册。

代码改动：

```text
core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt
  - 新增 callOriginalStubInterface11(classLoader, className, value): Boolean
  - 新增 private external fun nativeCallOriginalStubInterface11(...)

core/hook/src/main/cpp/native-hook.cpp
  - 新增 Java_com_multiapp_core_hook_NativeHookBridge_nativeCallOriginalStubInterface11
  - 复用已有 g_orig_stub_interface11 全局变量，调用前检查是否为 nullptr
  - 调用前后打印 YWLoginManager pwdLogin/sendPhoneCode/qrCodeV2 绑定状态
  - 补充 clear_logged_exception 的 forward declaration

core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
  - 新增 qqReaderOriginalInterface11Attempted 标志位
  - 新增 maybeRunQqReaderOriginalInterface11(bridge, classLoader, targetClass, reason)
  - 在 tryRunQqReaderStubAppAttachedLoad 的 before/after report 后调用
  - 在 preloadPackerLib 的 StubApp fallback 判断后调用
```

构建与打包：

```text
.\gradlew.bat --no-daemon --no-build-cache "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
BUILD SUCCESSFUL in 2m 35s

.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v221-call-original-interface11-59494 `
  -InputCloneApk .tmp\qqreader-c9f8-current-base.apk `
  -OutputApk .tmp\qqreader-c9f8-neutralized-v221-call-original-interface11-59494-signed.apk `
  -ForceRepack -SkipVerify
```

APK 产出：

```text
.tmp\qqreader-c9f8-neutralized-v221-call-original-interface11-59494-signed.apk (413920565 bytes)
不带 -PreserveOuterJiagu，outer jiagu libs 已移除
```

### v221 默认启动测试（stubapp.fallback=0）

设备：`192.168.2.86:37839`

```text
adb install -r -d .tmp\qqreader-c9f8-neutralized-v221-call-original-interface11-59494-signed.apk
setprop debug.multiapp.stubapp.fallback 0
setprop debug.multiapp.ywlogin.auto_open 1
setprop debug.multiapp.ywlogin.java_diag 1
setprop debug.multiapp.ywlogin.action_fallback 1
```

证据文件：

```text
.tmp\qqreader-v221-interface11-59494-start-20260618-203647-logcat.txt
.tmp\qqreader-v221-interface11-59494-start-20260618-203647-filtered.txt
```

关键日志：

```text
RegisterNatives: class=com.stub.StubApp count=4  (MultiApp fallback, 不是原壳 count=10)
RegisterNatives: class=com.stub.StubApp count=10 = 0
captured original interface11 = 0
captured original interface20 = 0
nativeCallOriginalStubInterface11 = 0  (新桥从未执行)
pwdLogin = missing, sendPhoneCode = missing
FATAL EXCEPTION = 0, Fatal signal = 0
PID = 18341 (进程存活)
```

登录触发：

```text
wrapped_ywlogin_pwdLogin: original native not registered; throwing for Java fallback
YWHttp.post after ... apiCode=3 message=登录失败，请稍后重试 data=null nextAction=none
```

结论：新增的 `nativeCallOriginalStubInterface11` 桥没有机会执行，因为 `g_orig_stub_interface11`
从未被赋值 — 原壳 `RegisterNatives: class=com.stub.StubApp count=10` 根本没出现。

### v221 explicit_load + prehook_dlopen 诊断测试

为验证能否通过 explicit dlopen + GOT prehook 恢复原壳注册：

```text
setprop debug.multiapp.jiagu.explicit_load 1
setprop debug.multiapp.jiagu.prehook_dlopen 1
```

证据文件：

```text
.tmp\qqreader-v221-explicit-jiagu-20260618-203956-logcat.txt
.tmp\qqreader-v221-explicit-jiagu-20260618-203956-filtered.txt
```

关键日志：

```text
PackerRuntime.Jiagu: loadPackerLibrary: explicit load enabled for libjiagu_vip.so
nativeDlopenOnly: pre-parsed .../libjiagu_vip.so (open=1 openat=0 ...)
nativeDlopenOnly: dlopen OK .../libjiagu_vip.so
got_hook: found library .../libjiagu_vip.so at 0x74aca3c000
got_hook: hooked 3 functions in .../libjiagu_vip.so

patch_jiagu_vip_self_kill_callsite:
  path=.../libjiagu_vip.so base=0x74aca3c000
  callsite=0x74acb58b84 offset=0x11cb84
  prev=0x00000000 insn=0x00000000 next=0x00000000
  no patch applied
  checked=1 patched=0

nativeLoadLibraryForGuest: FAILED: JNI_ERR returned from JNI_OnLoad in ".../libjiagu_vip.so"

RegisterNatives: class=com.stub.StubApp count=4  (MultiApp fallback)
RegisterNatives: class=com.stub.StubApp count=10 = 0
captured original interface11 = 0
captured original interface20 = 0
nativeCallOriginalStubInterface11 = 0
pwdLogin = missing (x2), sendPhoneCode = missing (x2)
```

与历史成功边界对比（v110/v131）：

```text
v110/v131 成功时：
  RegisterNatives: class=com.stub.StubApp count=10 ... libjiagu_vip.so offset=0x1116b4
  GOT tgkill intercepted: caller=.../libjiagu_vip.so offset=0x11cb88
  patch_jiagu_self_kill_from_return_address:
    prev=0x52800120 insn=0x97ffb823 next=0x140000e8
    patched caller-4 callsite=0x...b84 before=0x97ffb823 after=0xd503201f
  => JNI_OnLoad 完成，StubApp count=10 注册成功

v221 失败时：
  patch_jiagu_vip_self_kill_callsite:
    prev=0x00000000 insn=0x00000000 next=0x00000000
  => 0x11cb84 处全是零字节，BL 指令不在
  => JNI_OnLoad 返回 JNI_ERR，壳中断
  => RegisterNatives count=10 从未出现
```

差异分析：

```text
1. v110/v131 在 0x11cb84 处有 0x97ffb823 (BL) + 0x52800120 (MOVN W0,#0)，
   tgkill GOT hook 拦截后动态 NOP 了 BL 调用。
2. v221 在同一 offset 0x11cb84 处全为 0x00000000 — 说明 libjiagu_vip.so 的
   内存布局或 .text 段内容已改变，或 GOT hook 时机不对，壳在 RegisterNatives
   之前就进入自毁/解密失败路径。
3. v221 打包脚本 `patchJiaguSo: disabled; preserving original libjiagu_vip.so`，
   没有对 .so 做文件级 patch。而 `nativeLoadLibraryForGuest` 返回 JNI_ERR
   说明壳的 JNI_OnLoad 自己判断环境不对后主动返回 -1。
```

当前阻塞总结：

```text
1. libjiagu_vip.so 的 JNI_OnLoad 在 clone 环境下返回 JNI_ERR，阻止 StubApp count=10 注册。
2. 0x11cb84 处指令全为零，现有的 self-kill callsite patch 无法命中有效指令。
3. 因此 g_orig_stub_interface11/interface20 从未被赋值，新增的主动调用桥无法工作。
4. 登录仍走 Java fallback -> /sdk/staticlogin -> apiCode=3，无法进入原版 tencentCaptcha 风控链。
```

下一步建议：

```text
1. 对比 v131 APK 中的 libjiagu_vip.so 和当前 APK 中的 libjiagu_vip.so 的 ELF 布局：
   用 objdump/readelf 比较 .text 段、JNI_OnLoad 的 vaddr/size、0x11cb84 处的指令。
2. 如果 .so 内容一致，问题可能在运行时 ClassLoader/namespace 差异导致壳自检失败。
3. 如果 .so 内容不同，需要找到当前版本壳的正确 self-kill offset 和 RegisterNatives 入口。
4. 备选方案：从 v131 成功包中提取已知可用的 libjiagu_vip.so，替换到当前 APK 中测试。
```

### 2026-06-21 libjiagu_vip.so ELF 分析

从当前 base APK 提取 `lib/arm64-v8a/libjiagu_vip.so`（889192 bytes）：

```text
.tmp\inspect-jiagu-so\libjiagu_vip-arm64.so
```

ELF64 PT_LOAD 段布局：

```text
PT_LOAD[0]: vaddr=0x0      filesz=0x1c910   offset=0x1000   (headers + rodata)
PT_LOAD[1]: vaddr=0x2d790  filesz=0xae10a   offset=0x1e790  (.text, 到 vaddr 0xdb89a)
PT_LOAD[2]: vaddr=0x257000 filesz=0x84f8    offset=0xcd000
PT_LOAD[3]: vaddr=0x26fc20 filesz=0x480     offset=0xd5c20
PT_LOAD[4]: vaddr=0x2710a0 filesz=0x2f98    offset=0xd60a0
```

关键虚拟地址映射：

```text
0x11cb84 (self-kill callsite) -> file offset 0x10db84
  PT_LOAD[1] filesz 覆盖 vaddr 0x2d790..0xdb89a
  0x11cb84 > 0xdb89a => 超出文件映射，属于 memsz > filesz 的 BSS/解密区
  文件中全为 0x00000000

0x1116b4 (v110 RegisterNatives count=10 caller) -> file offset 0x1026b4
  同样超出 filesz，文件中全为零
```

结论：

```text
1. 0x11cb84 和 0x1116b4 都在 ELF 的 BSS/运行时解密区域，文件中全为零。
2. 壳在 JNI_OnLoad 执行期间自行解密写入这些指令。
3. v110 成功路径：JNI_OnLoad -> 环境自检通过 -> 解密 -> RegisterNatives count=10
   -> 解密到 self-kill 区域 -> BL tgkill -> GOT hook 拦截 -> patch BL -> 正常返回。
4. v221 失败路径：JNI_OnLoad -> 环境自检失败 -> 返回 JNI_ERR
   -> RegisterNatives count=10 从未执行 -> 0x11cb84 始终为零。
5. 问题核心不是 self-kill patch，而是壳的环境检测为什么在当前 clone 环境下失败。
6. proactive patch（dlopen 后、JNI_OnLoad 前修改指令）不可行，
   因为指令在文件中为零、只在壳解密后才存在。
```

下一步聚焦：

```text
1. 对比 v110 和 v221 的 JNI_OnLoad 调用环境差异：
   ClassLoader namespace、data dir、process name、/proc/self/maps 可见性。
2. 检查当前 LoaderFactory 中是否有 JNI_OnLoad 前置操作
   改变了壳依赖的环境状态（FindClass hook、GOT hook 范围、path redirection）。
3. 如果找到环境差异，尝试在 JNI_OnLoad 前恢复 v110 的等价环境。
```

## 2026-06-21 v222-v229 壳 JNI_OnLoad 恢复与 YWLogin 注册

### 关键发现：YWLoginManager 注册机制

通过 v175 dump 文件发现 `YWLoginManager.<clinit>` 的字节码：

```
METHOD <clinit>[]V
  [0] CONST rA=0 narrow=59494 wide=59494
  [1] INVOKE_STATIC ref=Lcom/stub/StubApp;->interface11(I)V
  [2] RETURN_VOID
```

`YWLoginManager` 类的静态初始化块调用 `StubApp.interface11(59494)`。壳的 native
代码在该上下文中通过 `RegisterNatives` 注册 `pwdLogin/sendPhoneCode/qrCodeV2`。

壳的 `interface11` 是一个**分类分发器**：接收 token → 查内部加密表 → 调 `RegisterNatives`
注册对应类的 native 方法。壳不使用 `JNI FindClass`，而是从内部加密元数据获取类/方法信息。

### v222 spoofProcSelf 修复

根因：`JiaguRuntime.prepareFiles()` 从未调用 `spoofProcSelf()`。壳的 `JNI_OnLoad`
读 `/proc/self/cmdline` 检测进程名，分身环境进程名是 `clonestub_xxx` 而非 `com.qq.reader`。

修复：在 `prepareFiles()` 中添加 `bridge.spoofProcSelf(pid, originalPackageName)`。

v222 验证结果：

```
✅ /proc/self spoofed to 'com.qq.reader'
✅ RegisterNatives: class=com.stub.StubApp count=10  ← 壳成功注册
✅ captured original interface5/11/20/21 全部 bound
✅ 进程存活，无 FATAL EXCEPTION
❌ YWLogin 仍 missing（interface11(59494) 执行但未注册）
```

### v226b-v228 YWLoginManager `<clinit>` 触发

方案 A：通过 `Class.forName("...YWLoginManager", true, guestCl)` 触发 `<clinit>`，
让壳的 `interface11(59494)` 从正确的调用上下文注册 YWLogin native 方法。

关键时序问题：`installPostLoadHooks` 在 `installStubFallback` **之前**执行。
`YWLoginManager.<clinit>` 调用 `StubApp.interface11(59494)`，但 `interface11`
此时还是未注册的 native 方法 → `UnsatisfiedLinkError`。

v227 修复：把 `<clinit>` 触发移到 `installStubFallback` 之后。

v228 结果：

```
✅ 壳 JNI_OnLoad 成功：RegisterNatives count=10
✅ <clinit> 触发成功
✅ 进程存活
❌ interface11(59494) 仍未注册 YWLogin（壳内部条件不满足）
```

### v229 最终状态

在触发 `<clinit>` 之前先注册 YWLogin stubs（`registerBusinessStubs`），避免
`UnsatisfiedLinkError`。壳的 `interface11(59494)` 如果成功会覆盖 stubs。

v229 结果：

```
✅ 壳 JNI_OnLoad 成功（v228 验证 count=10）
✅ pwdLogin/sendPhoneCode/qrCodeV2 由 fallback 注册（registered=17 failed=0）
✅ 登录页打开：LoginActivity + QRLoginActivity
✅ 用户输入账号密码：account=17***08 passwordLen=10
✅ Java hook 拦截：pwdLogin native missing; suppressing crash
✅ ParamsSignCallback 注入成功
✅ 进程存活，无 FATAL EXCEPTION
❌ 服务端返回 apiCode=3（Java fallback 请求与 native 不等价）
```

### 当前阻塞

壳的 `interface11(59494)` 执行了但不注册 YWLogin native 方法。可能原因：
1. 壳的内部状态检查未通过（需要 `interface5(Application)` 先初始化）
2. 壳使用 `dl_iterate_phdr` 或其他非 JNI 机制查找类
3. 壳的加密元数据中 token 59494 映射的注册条件未满足

Java fallback 能发送 `/sdk/staticlogin` 请求，但服务端返回 `apiCode=3` 而非
原版的 `247`（进入 tencentCaptcha 风控链路）。差距在于请求 body 中 native
`pwdLogin` 产生的加密/签名字段。

### 下一步

1. **抓包对比**：用 mitmproxy 抓原版和分身的 `/sdk/staticlogin` 请求，找出
   请求 body 差异
2. **补齐请求参数**：Java fallback 需要与 native `pwdLogin` 产生完全相同的请求 body
3. **或绕过壳**：用 LSPlant hook 登录相关 Java 方法，直接实现网络请求
