param(
    [string]$Serial = "",
    [string]$OutputDir = ".tmp",
    [string]$AdbPath = "",
    [string]$InstanceId = ""
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Resolve-AdbPath {
    param([string]$ExplicitPath)

    $candidates = @()
    if ($ExplicitPath.Trim().Length -gt 0) {
        $candidates += $ExplicitPath
    }
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    }
    $candidates += "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $pathAdb = Get-Command adb -ErrorAction SilentlyContinue
    if ($pathAdb) {
        return $pathAdb.Source
    }

    throw "adb.exe not found. Pass -AdbPath or install Android platform-tools."
}

function Invoke-Adb {
    param([string[]]$Arguments)
    & $adb @adbTarget @Arguments
}

function Append-HostedEvidenceFile {
    param([string]$Name)

    $trimmed = $Name.Trim()
    if ($trimmed.Length -eq 0) { return }
    "===files/hosted_launch_evidence/$trimmed===" | Tee-Object -Append -FilePath $runtimeEvidence | Out-Null
    Invoke-Adb @(
        "shell",
        "run-as",
        "com.multiapp.app",
        "cat",
        "files/hosted_launch_evidence/$trimmed"
    ) 2>&1 | Tee-Object -Append -FilePath $runtimeEvidence | Out-Null
    "" | Tee-Object -Append -FilePath $runtimeEvidence | Out-Null
}

$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repo

$adb = Resolve-AdbPath $AdbPath
$adbTarget = @()
if ($Serial.Trim().Length -gt 0) {
    if ($Serial -match "^[^:]+:\d+$") {
        & $adb connect $Serial | Out-Null
    }
    $adbTarget += @("-s", $Serial)
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $OutputDir "manual-hosted-launch-$timestamp"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$summary = Join-Path $evidenceDir "summary.txt"
$logcat = Join-Path $evidenceDir "logcat.txt"
$activity = Join-Path $evidenceDir "activity.txt"
$exitInfo = Join-Path $evidenceDir "exit-info.txt"
$runtimeEvidence = Join-Path $evidenceDir "hosted-launch-evidence.txt"

try {
    (& $adb devices) -join "`n" | Set-Content -Path (Join-Path $evidenceDir "adb-devices.txt") -Encoding UTF8

    Invoke-Adb @("shell", "getprop", "ro.product.manufacturer") | Set-Content -Path (Join-Path $evidenceDir "device-manufacturer.txt") -Encoding UTF8
    Invoke-Adb @("shell", "getprop", "ro.product.model") | Set-Content -Path (Join-Path $evidenceDir "device-model.txt") -Encoding UTF8
    Invoke-Adb @("shell", "getprop", "ro.build.version.release") | Set-Content -Path (Join-Path $evidenceDir "android-release.txt") -Encoding UTF8
    Invoke-Adb @("shell", "getprop", "ro.miui.ui.version.name") | Set-Content -Path (Join-Path $evidenceDir "miui-version.txt") -Encoding UTF8

    Invoke-Adb @("logcat", "-d", "-v", "time") | Tee-Object -FilePath $logcat | Out-Null
    Invoke-Adb @("shell", "dumpsys", "activity", "activities") | Tee-Object -FilePath $activity | Out-Null
    Invoke-Adb @("shell", "dumpsys", "activity", "exit-info", "com.multiapp.app") | Tee-Object -FilePath $exitInfo | Out-Null

    if ($InstanceId.Trim().Length -gt 0) {
        $pattern = "$InstanceId*.properties"
        $files = (& $adb @adbTarget shell run-as com.multiapp.app ls files/hosted_launch_evidence/$pattern) 2>&1

        if ($LASTEXITCODE -ne 0) {
            "hosted_launch_evidence files missing for instanceId=$InstanceId" | Tee-Object -FilePath $runtimeEvidence | Out-Null
            $files | Tee-Object -Append -FilePath $runtimeEvidence | Out-Null
        } else {
            foreach ($file in $files) {
                Append-HostedEvidenceFile $file
            }
        }
    } else {
        $files = (& $adb @adbTarget shell run-as com.multiapp.app ls files/hosted_launch_evidence) 2>&1

        if ($LASTEXITCODE -ne 0) {
            "hosted_launch_evidence directory missing" | Tee-Object -FilePath $runtimeEvidence | Out-Null
            $files | Tee-Object -Append -FilePath $runtimeEvidence | Out-Null
        } else {
            foreach ($file in $files) {
                Append-HostedEvidenceFile $file
            }
        }
    }

    $statusLine = Select-String -LiteralPath $runtimeEvidence -Pattern "^status=" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    $status = if ($statusLine) { $statusLine.Line } else { "status=UNKNOWN" }

    @(
        $status,
        "timestamp=$timestamp",
        "adb=$adb",
        "serial=$Serial",
        "instanceId=$InstanceId",
        "evidenceDir=$evidenceDir",
        "logcat=$logcat",
        "exitInfo=$exitInfo",
        "runtimeEvidence=$runtimeEvidence"
    ) | Set-Content -Path $summary -Encoding UTF8

    Write-Host "Manual hosted launch evidence: $evidenceDir"
    Get-Content -Path $summary
} catch {
    @(
        "status=FAIL_CAPTURE",
        "timestamp=$timestamp",
        "adb=$adb",
        "serial=$Serial",
        "instanceId=$InstanceId",
        "error=$($_.Exception.Message)",
        "evidenceDir=$evidenceDir"
    ) | Set-Content -Path $summary -Encoding UTF8
    Write-Error $_
    exit 1
}
