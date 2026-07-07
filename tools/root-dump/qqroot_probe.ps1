param(
    [string]$Serial = "192.168.2.42:10001",
    [string]$OutDir = ".tmp\qqroot-probe",
    [string]$OriginalPackage = "com.qq.reader",
    [string]$ClonePackage = "com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8",
    [switch]$Launch,
    [switch]$IncludeEnv
)

$ErrorActionPreference = "Stop"

$adb = "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
if (!(Test-Path -LiteralPath $adb)) {
    $adb = "adb"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb -s $Serial @Args
}

function Invoke-AdbShell {
    param([string]$Command)
    Invoke-Adb shell $Command
}

function Invoke-AdbRootShell {
    param([string]$Command)
    $escaped = $Command.Replace('"', '\"')
    Invoke-Adb shell "su -c ""$escaped"""
}

function Save-Text {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    $parent = Split-Path -Parent $Path
    if ($parent -and !(Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Lines | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Save-Shell {
    param(
        [string]$Path,
        [string]$Command,
        [switch]$Root
    )
    try {
        if ($Root) {
            $lines = Invoke-AdbRootShell $Command
        } else {
            $lines = Invoke-AdbShell $Command
        }
        Save-Text $Path $lines
    } catch {
        Save-Text $Path @("ERROR: $($_.Exception.Message)", "COMMAND: $Command")
    }
}

function Start-Package {
    param([string]$PackageName)
    Invoke-AdbShell "am force-stop $PackageName" | Out-Null
    Start-Sleep -Milliseconds 300
    Invoke-AdbShell "monkey -p $PackageName -c android.intent.category.LAUNCHER 1" | Out-Null
    Start-Sleep -Seconds 5
}

function Get-Pid {
    param([string]$PackageName)
    $procId = (Invoke-AdbShell "pidof $PackageName 2>/dev/null | awk '{print `$1}'" | Select-Object -First 1)
    if ($procId) { return $procId.Trim() }
    return ""
}

function Save-ProcessSnapshot {
    param(
        [string]$PackageName,
        [string]$Label
    )

    $pkgDir = Join-Path $OutDir $Label
    New-Item -ItemType Directory -Force -Path $pkgDir | Out-Null

    if ($Launch) {
        Start-Package $PackageName
    }

    Save-Shell (Join-Path $pkgDir "pm_path.txt") "pm path $PackageName"
    Save-Shell (Join-Path $pkgDir "pm_dump_focus.txt") "dumpsys package $PackageName | grep -E 'Package \[|userId=|pkg=|codePath=|resourcePath=|legacyNativeLibraryDir=|primaryCpuAbi=|secondaryCpuAbi=|dataDir=|seinfo=|signatures=|versionName=|versionCode=|installerPackageName='"
    Save-Shell (Join-Path $pkgDir "data_dirs.txt") "ls -ld /data/data/$PackageName /data/user/0/$PackageName 2>&1; ls -l /data/data/$PackageName 2>&1 | head -80"

    $procId = Get-Pid $PackageName
    Save-Text (Join-Path $pkgDir "pid.txt") @($procId)
    if (!$procId) {
        Save-Text (Join-Path $pkgDir "process_missing.txt") @("pidof returned empty for $PackageName")
        return
    }

    Save-Shell (Join-Path $pkgDir "cmdline.txt") "cat /proc/$procId/cmdline | tr '\000' '\n'" -Root
    Save-Shell (Join-Path $pkgDir "status.txt") "cat /proc/$procId/status" -Root
    Save-Shell (Join-Path $pkgDir "cgroup.txt") "cat /proc/$procId/cgroup" -Root
    Save-Shell (Join-Path $pkgDir "mountinfo.txt") "cat /proc/$procId/mountinfo" -Root
    Save-Shell (Join-Path $pkgDir "maps.txt") "cat /proc/$procId/maps" -Root
    Save-Shell (Join-Path $pkgDir "maps_focus.txt") "grep -E 'libjiagu|base.apk|com.qq.reader|clonestub|multiapp|shadowhook|lsplant|\\[anon:.bss\\]' /proc/$procId/maps" -Root
    Save-Shell (Join-Path $pkgDir "links.txt") "for x in exe cwd root; do echo `$x; readlink /proc/$procId/`$x 2>&1; done" -Root
    Save-Shell (Join-Path $pkgDir "fds_focus.txt") "ls -l /proc/$procId/fd 2>&1 | grep -E 'apk|jiagu|qq.reader|clonestub|multiapp|/data/app|/data/data' | head -120" -Root

    if ($IncludeEnv) {
        Save-Shell (Join-Path $pkgDir "environ.txt") "cat /proc/$procId/environ | tr '\000' '\n'" -Root
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Save-Shell (Join-Path $OutDir "device.txt") "getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; id; getenforce"
Save-Shell (Join-Path $OutDir "packages.txt") "pm list packages | grep -E 'com.qq.reader|clonestub'"

Save-ProcessSnapshot $OriginalPackage "original"
Save-ProcessSnapshot $ClonePackage "clone"

$summaryCommand = "echo original=`$(pidof $OriginalPackage); echo clone=`$(pidof $ClonePackage); echo ---; ps -A | grep -E '$OriginalPackage|$ClonePackage'"
Save-Shell (Join-Path $OutDir "summary_diff_hint.txt") $summaryCommand

Write-Host "root probe saved: $OutDir"
