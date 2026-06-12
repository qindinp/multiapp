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
