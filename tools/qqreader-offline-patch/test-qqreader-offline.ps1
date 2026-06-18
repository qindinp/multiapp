param(
    [string]$Serial = "",

    [string]$Connect = "",

    [string]$Apk = "",

    [string]$VersionTag = "v146-restore-v138-native-boundary",

    [string]$Package = "com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8",

    [string]$Activity = "com.qq.reader.activity.launch.DefaultAliasSplashActivity",

    [int]$WaitSeconds = 25,

    [ValidateSet("0", "1")]
    [string]$OnlineRunFallback = "1",

    [ValidateSet("0", "1")]
    [string]$OnlineStateFallback = "1",

    [ValidateSet("0", "1")]
    [string]$OnlineMaterializeEqct = "1",

    [ValidateSet("0", "1")]
    [string]$OnlineFailureCallback = "0",

    [ValidateSet("0", "1")]
    [string]$OnlineFileDiag = "0",

    [ValidateSet("0", "1", "core")]
    [string]$StubAppFallback = "0",

    [switch]$SkipInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $RepoRoot $Path
}

function Invoke-Adb {
    param(
        [Parameter(ValueFromRemainingArguments = $true)]
        [string[]]$ArgsList
    )

    if ($Serial -ne "") {
        & $Adb -s $Serial @ArgsList
    } else {
        & $Adb @ArgsList
    }
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($ArgsList -join ' ')"
    }
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Adb = "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
if (-not (Test-Path $Adb -PathType Leaf)) {
    throw "adb not found: $Adb"
}

if ($Apk -eq "") {
    $Apk = ".tmp\qqreader-c9f8-neutralized-$VersionTag-signed.apk"
}
$Apk = Resolve-RepoPath $Apk
if (-not $SkipInstall -and -not (Test-Path $Apk -PathType Leaf)) {
    throw "APK not found: $Apk"
}

if ($Connect -ne "") {
    & $Adb connect $Connect
    if ($LASTEXITCODE -ne 0) {
        throw "adb connect failed: $Connect"
    }
    if ($Serial -eq "") {
        $Serial = $Connect
    }
}

Write-Host "ADB devices:"
& $Adb devices

if (-not $SkipInstall) {
    Write-Host "Install APK: $Apk"
    Invoke-Adb "install" "-r" "-d" $Apk
}

$runPrefix = Resolve-RepoPath ".tmp\qqreader-$VersionTag-start"

Write-Host "Prepare runtime props"
Invoke-Adb "shell" "am" "force-stop" $Package
Invoke-Adb "logcat" "-c"
Invoke-Adb "shell" "setprop" "debug.multiapp.jiagu.explicit_load" "0"
Invoke-Adb "shell" "setprop" "debug.multiapp.jiagu.prehook_dlopen" "0"
Invoke-Adb "shell" "setprop" "debug.multiapp.patch_jiagu" "0"
Invoke-Adb "shell" "setprop" "debug.multiapp.online.state_fallback" $OnlineStateFallback
Invoke-Adb "shell" "setprop" "debug.multiapp.online.run_fallback" $OnlineRunFallback
Invoke-Adb "shell" "setprop" "debug.multiapp.online.materialize_eqct" $OnlineMaterializeEqct
Invoke-Adb "shell" "setprop" "debug.multiapp.online.failure_callback" $OnlineFailureCallback
Invoke-Adb "shell" "setprop" "debug.multiapp.online.file_diag" $OnlineFileDiag
Invoke-Adb "shell" "setprop" "debug.multiapp.stubapp.fallback" $StubAppFallback

Write-Host "Start QQ Reader clone"
Invoke-Adb "shell" "am" "start" "-W" "-n" "$Package/$Activity" |
    Tee-Object -FilePath "$runPrefix-start.txt"

Start-Sleep -Seconds $WaitSeconds

Write-Host "Collect logs: $runPrefix-*"
Invoke-Adb "logcat" "-d" "-v" "threadtime" |
    Out-File -FilePath "$runPrefix-logcat.txt" -Encoding utf8
Invoke-Adb "logcat" "-b" "crash" "-d" "-v" "threadtime" |
    Out-File -FilePath "$runPrefix-crash.txt" -Encoding utf8
Invoke-Adb "shell" "dumpsys" "activity" "exit-info" $Package |
    Out-File -FilePath "$runPrefix-exit-info.txt" -Encoding utf8

$patterns = @(
    "StubApp.load\(\) invoked OK",
    "nativeRegisterStubCoreBootstrapMethods",
    "RegisterNatives: class=com.stub.StubApp",
    "captured original interface11",
    "captured original interface20",
    "RegisterNatives: class=com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask",
    "OnlineChapterDownloadTask.run",
    "stub_online_run",
    "online_file_diag",
    "No implementation found",
    "JNI_ERR",
    "previous attempt",
    "FATAL EXCEPTION",
    "tgkill",
    "SIGKILL"
) -join "|"

Write-Host "Key evidence:"
Select-String -Path "$runPrefix-logcat.txt","$runPrefix-crash.txt","$runPrefix-exit-info.txt" -Pattern $patterns |
    Select-Object -First 120 |
    ForEach-Object {
        "{0}:{1}: {2}" -f $_.Filename, $_.LineNumber, $_.Line.Trim()
    }

Write-Host "Done"

