param(
    [string]$Serial = "192.168.2.42:10001",
    [int]$UserId = 10,
    [string]$PackageName = "com.qq.reader",
    [string]$Component = "com.qq.reader/.activity.DefaultAliasActivity",
    [int]$WaitSeconds = 25,
    [int]$DiagSamples = 8,
    [int]$DiagIntervalMs = 500,
    [int]$LogcatLines = 1200,
    [switch]$ForceRegister,
    [switch]$NoSwitchUser,
    [string]$VersionTag = "root-user10-live-diag"
)

$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$adb = "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
if (!(Test-Path -LiteralPath $adb)) {
    $adb = "adb"
}

$patcher = Join-Path $repo ".tmp\qqmempatch-arm64"
$watcher = Join-Path $repo "tools\root-dump\qqpatch_watch.sh"
if (!(Test-Path -LiteralPath $patcher)) {
    throw "Missing $patcher. Build it before running this script."
}
if (!(Test-Path -LiteralPath $watcher)) {
    throw "Missing $watcher."
}

$outDir = Join-Path $repo ".tmp\$VersionTag"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb -s $Serial @Args
}

function Shell {
    param([string]$Command)
    Invoke-Adb shell $Command
}

Invoke-Adb get-state | Set-Content -LiteralPath (Join-Path $outDir "adb-state.txt") -Encoding ASCII
Invoke-Adb push $patcher /data/local/tmp/qqmempatch | Set-Content -LiteralPath (Join-Path $outDir "push-qqmempatch.txt") -Encoding UTF8
Invoke-Adb push $watcher /data/local/tmp/qqpatch_watch.sh | Set-Content -LiteralPath (Join-Path $outDir "push-watch.txt") -Encoding UTF8
Shell "chmod 755 /data/local/tmp/qqmempatch /data/local/tmp/qqpatch_watch.sh" | Set-Content -LiteralPath (Join-Path $outDir "chmod.txt") -Encoding UTF8

if (!$NoSwitchUser) {
    Shell "am start-user $UserId; am switch-user $UserId" |
        Set-Content -LiteralPath (Join-Path $outDir "switch-user.txt") -Encoding UTF8
}

Shell "cmd package install-existing --user $UserId $PackageName; am get-current-user; cmd package resolve-activity --user $UserId --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PackageName" |
    Set-Content -LiteralPath (Join-Path $outDir "pm-before.txt") -Encoding UTF8

$watchLog = "/data/local/tmp/qqpatch_watch_${VersionTag}.log"
$watchOut = "/data/local/tmp/qqpatch_watch_${VersionTag}.out"
$options = @("diag-samples=$DiagSamples", "diag-interval-ms=$DiagIntervalMs")
if ($ForceRegister) {
    $options = @("force-register") + $options
}
$optionText = $options -join " "

Shell "su -c 'rm -f $watchLog $watchOut; am force-stop --user $UserId $PackageName; logcat -c 2>/dev/null || true'" |
    Set-Content -LiteralPath (Join-Path $outDir "pre-clean.txt") -Encoding UTF8

Shell "su -c 'sh /data/local/tmp/qqpatch_watch.sh $PackageName $watchLog 1000 0.005 $optionText >$watchOut 2>&1 &'" |
    Set-Content -LiteralPath (Join-Path $outDir "watch-start.txt") -Encoding UTF8

Start-Sleep -Milliseconds 150
Shell "am start --user $UserId -n $Component" |
    Set-Content -LiteralPath (Join-Path $outDir "app-start.txt") -Encoding UTF8

Start-Sleep -Seconds $WaitSeconds

Shell "pidof $PackageName || true" |
    Set-Content -LiteralPath (Join-Path $outDir "pid.txt") -Encoding ASCII
Shell "dumpsys activity top | grep -E 'TASK|ACTIVITY|$PackageName|pid=' | head -120" |
    Set-Content -LiteralPath (Join-Path $outDir "activity-top.txt") -Encoding UTF8
Shell "su -c 'cat $watchLog $watchOut 2>/dev/null'" |
    Set-Content -LiteralPath (Join-Path $outDir "watcher.txt") -Encoding UTF8
Invoke-Adb logcat -d -v time -t $LogcatLines |
    Set-Content -LiteralPath (Join-Path $outDir "logcat.txt") -Encoding UTF8

Write-Host "wrote=$outDir"
Get-Content -LiteralPath (Join-Path $outDir "watcher.txt")
