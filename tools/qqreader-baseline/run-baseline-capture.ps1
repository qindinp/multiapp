param(
    [string]$Adb = "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe",
    [string]$Device = "",
    [string]$MultiAppApk = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$ClonePackage = "",
    [string]$LaunchActivity = "com.qq.reader.activity.launch.DefaultAliasSplashActivity",
    [string]$OutDir = ".tmp",
    [int]$WaitSeconds = 25,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

function Resolve-AdbArgs {
    if ([string]::IsNullOrWhiteSpace($Device)) {
        return @()
    }
    return @("-s", $Device)
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $Adb @Args
}

function Invoke-DeviceAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $deviceArgs = Resolve-AdbArgs
    & $Adb @deviceArgs @Args
}

function Save-Text {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Lines | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-OnlineDevices {
    $lines = Invoke-Adb devices -l
    return $lines | Where-Object { $_ -match "\sdevice\s" } | ForEach-Object { ($_ -split "\s+")[0] }
}

function Resolve-Device {
    if (-not [string]::IsNullOrWhiteSpace($Device)) {
        return $Device
    }
    $devices = @(Get-OnlineDevices)
    if ($devices.Count -eq 0) {
        throw "No online adb device. Connect USB/wireless adb first."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple adb devices online: $($devices -join ', '). Pass -Device <serial>."
    }
    return $devices[0]
}

function Resolve-ClonePackage {
    if (-not [string]::IsNullOrWhiteSpace($ClonePackage)) {
        return $ClonePackage
    }
    $packages = Invoke-DeviceAdb shell pm list packages | Where-Object { $_ -match "com\.qq\.reader\.clonestub" }
    $names = @($packages | ForEach-Object { $_.Replace("package:", "").Trim() } | Sort-Object)
    if ($names.Count -eq 0) {
        throw "No QQ Reader clone package found. Generate/install the baseline clone first."
    }
    if ($names.Count -gt 1) {
        $withTimes = foreach ($name in $names) {
            $dump = Invoke-DeviceAdb shell dumpsys package $name
            $last = ($dump | Select-String -Pattern "lastUpdateTime=" | Select-Object -First 1).Line
            [pscustomobject]@{ Package = $name; LastUpdate = $last }
        }
        $withTimes | Format-Table -AutoSize | Out-String | Write-Host
        throw "Multiple QQ Reader clone packages found. Pass -ClonePackage <package>."
    }
    return $names[0]
}

$Device = Resolve-Device
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$prefix = Join-Path $OutDir "qqreader-baseline-$timestamp"

Write-Host "Device: $Device"
Write-Host "Output prefix: $prefix"

Save-Text "$prefix-devices.txt" (Invoke-Adb devices -l)

if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $MultiAppApk)) {
        throw "MultiApp APK not found: $MultiAppApk. Run .\gradlew.bat :app:assembleDebug first."
    }
    Write-Host "Installing MultiApp APK: $MultiAppApk"
    Save-Text "$prefix-multiapp-install.txt" (Invoke-DeviceAdb install -r $MultiAppApk)
}

$clone = Resolve-ClonePackage
$component = "$clone/$LaunchActivity"
Write-Host "Clone package: $clone"
Write-Host "Launch component: $component"

Save-Text "$prefix-multiapp-package.txt" (Invoke-DeviceAdb shell dumpsys package com.multiapp.app)
Save-Text "$prefix-clone-package.txt" (Invoke-DeviceAdb shell dumpsys package $clone)

Invoke-DeviceAdb logcat -c | Out-Null
Invoke-DeviceAdb shell am force-stop $clone | Out-Null

Write-Host "Starting baseline clone..."
Save-Text "$prefix-start.txt" (Invoke-DeviceAdb shell am start -W -n $component)

Start-Sleep -Seconds $WaitSeconds

Save-Text "$prefix-logcat.txt" (Invoke-DeviceAdb logcat -d -v threadtime)
Save-Text "$prefix-crash.txt" (Invoke-DeviceAdb logcat -b crash -d -v time)
Save-Text "$prefix-exit-info.txt" (Invoke-DeviceAdb shell dumpsys activity exit-info $clone)
Save-Text "$prefix-process.txt" (Invoke-DeviceAdb shell ps -A)

$summary = @()
$summary += "device=$Device"
$summary += "clonePackage=$clone"
$summary += "component=$component"
$summary += "waitSeconds=$WaitSeconds"
$summary += "logcat=$prefix-logcat.txt"
$summary += "crash=$prefix-crash.txt"
$summary += "exitInfo=$prefix-exit-info.txt"
$summary += "bootstrapMarkers=" + ((Select-String -LiteralPath "$prefix-logcat.txt" -Pattern "BOOTSTRAP stage=" -ErrorAction SilentlyContinue).Count)
$summary += "fatalMarkers=" + ((Select-String -LiteralPath "$prefix-logcat.txt" -Pattern "FATAL EXCEPTION|AndroidRuntime|signal 11|SIGSEGV|UnsatisfiedLinkError" -ErrorAction SilentlyContinue).Count)
Save-Text "$prefix-summary.txt" $summary

Write-Host "Capture complete. Summary: $prefix-summary.txt"
