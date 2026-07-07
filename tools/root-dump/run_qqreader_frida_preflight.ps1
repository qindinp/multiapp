param(
    [string]$Serial = "192.168.2.42:10001",
    [string]$PackageName = "com.qq.reader",
    [string]$VersionTag = "root-frida-preflight",
    [string]$FridaExe = "",
    [string]$FridaHost = "127.0.0.1:27042",
    [switch]$SearchDownloads
)

$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$adb = "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
if (!(Test-Path -LiteralPath $adb)) {
    $adb = "adb"
}

$outDir = Join-Path $repo ".tmp\$VersionTag"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Save-Lines {
    param([string]$Path, [string[]]$Lines)
    $Lines | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Try-Run {
    param(
        [string]$Path,
        [scriptblock]$Block
    )
    try {
        $output = & $Block 2>&1
        Save-Lines $Path $output
        return $output
    } catch {
        Save-Lines $Path @("ERROR: $($_.Exception.Message)")
        return @("ERROR: $($_.Exception.Message)")
    }
}

function Resolve-FridaExe {
    param([string]$Explicit)
    if ($Explicit -and (Test-Path -LiteralPath $Explicit)) {
        return (Resolve-Path -LiteralPath $Explicit).Path
    }

    $candidates = @(
        "$env:APPDATA\Python\Python312\Scripts\frida.exe",
        "$env:USERPROFILE\AppData\Roaming\Python\Python312\Scripts\frida.exe",
        "F:\Anaconda3\envs\frida\Scripts\frida.exe",
        "$env:USERPROFILE\Anaconda3\envs\frida\Scripts\frida.exe",
        "$env:USERPROFILE\miniconda3\envs\frida\Scripts\frida.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $cmd = Get-Command frida -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    if ($SearchDownloads -and (Test-Path -LiteralPath "D:\360Downloads")) {
        $found = Get-ChildItem -LiteralPath "D:\360Downloads" -Recurse -Filter "frida.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($found) {
            return $found.FullName
        }
    }

    return ""
}

$summary = [ordered]@{}
$summary["repo"] = $repo
$summary["adb"] = $adb
$summary["serial"] = $Serial
$summary["packageName"] = $PackageName
$summary["fridaHost"] = $FridaHost

$fridaServerX64 = Join-Path $repo ".tmp\frida-17.3.2\frida-server-17.3.2-android-x86_64"
$fridaServerArm64 = Join-Path $repo ".tmp\frida-17.3.2\frida-server-17.3.2-android-arm64"
$summary["fridaServerX86_64"] = if (Test-Path -LiteralPath $fridaServerX64) { $fridaServerX64 } else { "<not-found>" }
$summary["fridaServerArm64"] = if (Test-Path -LiteralPath $fridaServerArm64) { $fridaServerArm64 } else { "<not-found>" }

$adbVersion = Try-Run (Join-Path $outDir "adb-version.txt") { & $adb version }
$devices = Try-Run (Join-Path $outDir "adb-devices.txt") { & $adb devices }
$summary["adbVersionFirstLine"] = ($adbVersion | Select-Object -First 1)

$serialState = ""
foreach ($line in $devices) {
    if ($line -match "^$([regex]::Escape($Serial))\s+(\S+)") {
        $serialState = $Matches[1]
        break
    }
}
if (!$serialState) {
    $serialState = "missing"
}
$summary["serialState"] = $serialState

$resolvedFrida = Resolve-FridaExe $FridaExe
$summary["fridaExe"] = if ($resolvedFrida) { $resolvedFrida } else { "<not-found>" }
if ($resolvedFrida) {
    $fridaVersion = Try-Run (Join-Path $outDir "frida-version.txt") { & $resolvedFrida --version }
    $fridaHelp = Try-Run (Join-Path $outDir "frida-help.txt") { & $resolvedFrida --help }
    $summary["fridaVersion"] = ($fridaVersion | Select-Object -First 1)
    $helpText = ($fridaHelp -join "`n")
    $summary["fridaSupportsNoPause"] = if ($helpText -match "--no-pause") { "yes" } else { "no" }
    $summary["fridaSupportsPause"] = if ($helpText -match "--pause") { "yes" } else { "no" }
} else {
    Save-Lines (Join-Path $outDir "frida-version.txt") @("frida.exe not found")
    Save-Lines (Join-Path $outDir "frida-help.txt") @("frida.exe not found")
    $summary["fridaVersion"] = "<not-found>"
    $summary["fridaSupportsNoPause"] = "<unknown>"
    $summary["fridaSupportsPause"] = "<unknown>"
}

if ($serialState -eq "device") {
    Try-Run (Join-Path $outDir "adb-state.txt") { & $adb -s $Serial get-state } | Out-Null
    Try-Run (Join-Path $outDir "device-id.txt") { & $adb -s $Serial shell id } | Out-Null
    $suId = Try-Run (Join-Path $outDir "device-su-id.txt") { & $adb -s $Serial shell "su -c id" }
    Try-Run (Join-Path $outDir "device-props.txt") {
        & $adb -s $Serial shell "getprop ro.product.cpu.abi; getprop ro.build.version.sdk; getprop ro.product.model"
    } | Out-Null
    Try-Run (Join-Path $outDir "frida-processes.txt") {
        & $adb -s $Serial shell "ps -A | grep -i frida || true"
    } | Out-Null
    Try-Run (Join-Path $outDir "package-pid.txt") {
        & $adb -s $Serial shell "pidof $PackageName 2>/dev/null || true"
    } | Out-Null
    $summary["suRoot"] = if (($suId -join "`n") -match "uid=0") { "yes" } else { "no-or-unknown" }
} else {
    Save-Lines (Join-Path $outDir "device-skipped.txt") @("serialState=$serialState; device checks skipped")
    $summary["suRoot"] = "<not-checked>"
}

$summaryLines = foreach ($entry in $summary.GetEnumerator()) {
    "$($entry.Key)=$($entry.Value)"
}
Save-Lines (Join-Path $outDir "summary.txt") $summaryLines

Write-Host "wrote=$outDir"
$summaryLines | ForEach-Object { Write-Host $_ }
