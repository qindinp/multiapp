param(
    [string]$Adb = "",
    [string]$Device = "",
    [string]$HostPackage = "com.multiapp.app",
    [string]$OriginPackage = "com.qq.reader",
    [string]$InstanceId = "",
    [string]$OutDir = ".tmp",
    [int]$WaitSeconds = 15
)

$ErrorActionPreference = "Stop"

function Resolve-AdbPath {
    param([string]$ExplicitPath)

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
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
    $candidates += "C:\adb\platform-tools\adb.exe"
    $candidates += "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $pathAdb = Get-Command adb -ErrorAction SilentlyContinue
    if ($pathAdb) { return $pathAdb.Source }
    throw "adb.exe not found. Pass -Adb or install Android platform-tools."
}

function Resolve-AdbArgs {
    if ([string]::IsNullOrWhiteSpace($Device)) { return @() }
    return @("-s", $Device)
}

function Invoke-DeviceAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $deviceArgs = Resolve-AdbArgs
    & $script:AdbPath @deviceArgs @Args
}

function Save-Text {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $Lines | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Append-RunAsFile {
    param(
        [string]$DevicePath,
        [string]$TargetFile
    )
    "`n===$DevicePath===" | Add-Content -LiteralPath $TargetFile -Encoding UTF8
    Invoke-DeviceAdb shell run-as $HostPackage cat $DevicePath 2>&1 |
        Add-Content -LiteralPath $TargetFile -Encoding UTF8
}

function Capture-HostedEvidence {
    param([string]$TargetFile)

    "=== hosted evidence ===" | Set-Content -LiteralPath $TargetFile -Encoding UTF8
    $files = Invoke-DeviceAdb shell run-as $HostPackage ls files/hosted_launch_evidence 2>&1
    if ($LASTEXITCODE -ne 0) {
        $files | Add-Content -LiteralPath $TargetFile -Encoding UTF8
        return
    }
    foreach ($file in $files) {
        $name = $file.Trim()
        if ($name.Length -eq 0) { continue }
        if (-not [string]::IsNullOrWhiteSpace($InstanceId) -and -not $name.StartsWith($InstanceId)) { continue }
        Append-RunAsFile -DevicePath "files/hosted_launch_evidence/$name" -TargetFile $TargetFile
    }
}

function Capture-InstanceRecords {
    param([string]$TargetFile)

    "=== instance records ===" | Set-Content -LiteralPath $TargetFile -Encoding UTF8
    $files = Invoke-DeviceAdb shell run-as $HostPackage ls files/instances 2>&1
    if ($LASTEXITCODE -ne 0) {
        $files | Add-Content -LiteralPath $TargetFile -Encoding UTF8
        return
    }
    foreach ($file in $files) {
        $name = $file.Trim()
        if ($name.Length -eq 0) { continue }
        if (-not [string]::IsNullOrWhiteSpace($InstanceId) -and -not $name.StartsWith($InstanceId)) { continue }
        Append-RunAsFile -DevicePath "files/instances/$name" -TargetFile $TargetFile
    }
}

function Get-MarkerCount {
    param(
        [string]$Path,
        [string]$Pattern
    )
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    return (Select-String -LiteralPath $Path -Pattern $Pattern -ErrorAction SilentlyContinue).Count
}

function Get-Interface20Verdict {
    param([string]$LogText)

    $hasJniOnLoad = $LogText -match "JNI_OnLoad|jni_onload|onLoad"
    $hasRegisterNatives = $LogText -match "RegisterNatives|register_natives"
    $hasStubClass = $LogText -match "com\.stub\.StubApp|com\.qihoo\.util\.StubApp"
    $hasFallback = $LogText -match "fallback|registerBusinessNativeStubs|business native stubs|MultiApp fallback"
    $hasUnsatisfied = $LogText -match "UnsatisfiedLinkError|dlopen failed|couldn't find .*\.so|cannot locate symbol"

    if ($hasRegisterNatives -and $hasStubClass -and -not $hasFallback) {
        return @("ORIGINAL_SHELL_REGISTERED", "RegisterNatives markers reference known shell StubApp classes")
    }
    if ($hasFallback) {
        return @("FALLBACK_REGISTERED", "Fallback/business native registration marker was observed; this is not a clean original-shell verdict")
    }
    if ($hasUnsatisfied -or (-not $hasJniOnLoad -and -not $hasRegisterNatives)) {
        return @("JNI_ONLOAD_NOT_EXECUTED", "No JNI_OnLoad/RegisterNatives evidence, or native load failed")
    }
    if ($hasJniOnLoad -and -not $hasRegisterNatives) {
        return @("REGISTER_NATIVES_NOT_EXECUTED", "JNI_OnLoad marker exists but RegisterNatives marker is missing")
    }
    if ($hasRegisterNatives -and -not $hasStubClass) {
        return @("REGISTER_NATIVES_WRONG_CLASS", "RegisterNatives marker exists but known shell StubApp class marker is missing")
    }
    return @("INSUFFICIENT_EVIDENCE", "Markers are insufficient for a reliable interface20 verdict")
}

function Get-SimpleVerdict {
    param(
        [bool]$Pass,
        [bool]$Fail,
        [string]$PassLabel = "PASS",
        [string]$FailLabel = "FAIL"
    )
    if ($Pass) { return $PassLabel }
    if ($Fail) { return $FailLabel }
    return "UNKNOWN"
}

$script:AdbPath = Resolve-AdbPath $Adb
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $OutDir "qqreader-hosted-diagnostics-$timestamp"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$devicesFile = Join-Path $evidenceDir "devices.txt"
$hostPackageFile = Join-Path $evidenceDir "host-package.txt"
$originPackageFile = Join-Path $evidenceDir "origin-package.txt"
$logcatFile = Join-Path $evidenceDir "logcat.txt"
$crashFile = Join-Path $evidenceDir "crash.txt"
$exitInfoFile = Join-Path $evidenceDir "exit-info.txt"
$processFile = Join-Path $evidenceDir "process.txt"
$evidenceFile = Join-Path $evidenceDir "hosted-launch-evidence.txt"
$instancesFile = Join-Path $evidenceDir "instances.txt"
$storageFile = Join-Path $evidenceDir "storage-files.txt"
$summaryFile = Join-Path $evidenceDir "summary.txt"

Write-Host "Device: $Device"
Write-Host "Output: $evidenceDir"
Write-Host "Capture mode: hosted QQ Reader diagnostics, observe-only. Start the hosted instance manually before or during the wait window."

Save-Text $devicesFile (Invoke-DeviceAdb devices -l)
Save-Text $hostPackageFile (Invoke-DeviceAdb shell dumpsys package $HostPackage)
Save-Text $originPackageFile (Invoke-DeviceAdb shell dumpsys package $OriginPackage)

Invoke-DeviceAdb logcat -c | Out-Null
Start-Sleep -Seconds $WaitSeconds

Save-Text $logcatFile (Invoke-DeviceAdb logcat -d -v threadtime)
Save-Text $crashFile (Invoke-DeviceAdb logcat -b crash -d -v time)
Save-Text $exitInfoFile (Invoke-DeviceAdb shell dumpsys activity exit-info $HostPackage)
Save-Text $processFile (Invoke-DeviceAdb shell ps -A)
Capture-HostedEvidence -TargetFile $evidenceFile
Capture-InstanceRecords -TargetFile $instancesFile
Save-Text $storageFile (Invoke-DeviceAdb shell run-as $HostPackage find files/instance_data -maxdepth 4 -type f 2>&1)

$logText = ""
if (Test-Path -LiteralPath $logcatFile) { $logText += Get-Content -LiteralPath $logcatFile -Raw -Encoding UTF8 }
if (Test-Path -LiteralPath $crashFile) { $logText += "`n" + (Get-Content -LiteralPath $crashFile -Raw -Encoding UTF8) }
if (Test-Path -LiteralPath $evidenceFile) { $logText += "`n" + (Get-Content -LiteralPath $evidenceFile -Raw -Encoding UTF8) }

$interfaceVerdict = Get-Interface20Verdict -LogText $logText
$nativeLoadFail = $logText -match "UnsatisfiedLinkError|dlopen failed|couldn't find .*\.so|cannot locate symbol"
$nativeLoadPass = $logText -match "JNI_OnLoad|System\.loadLibrary|loadLibrary|dlopen"
$registerPass = $logText -match "RegisterNatives|register_natives"
$findClassPass = $logText -match "FindClass|find_class"
$selfKillFail = $logText -match "killProcess|Process\.killProcess|System\.exit|SIGKILL|REASON_SIGNALED"
$fatalFail = $logText -match "FATAL EXCEPTION|AndroidRuntime|SIGSEGV|signal 11|native crash"

$summary = @(
    "status=CAPTURED",
    "timestamp=$timestamp",
    "adb=$script:AdbPath",
    "device=$Device",
    "hostPackage=$HostPackage",
    "originPackage=$OriginPackage",
    "instanceId=$InstanceId",
    "waitSeconds=$WaitSeconds",
    "mode=hosted-register-natives-only-diagnostics",
    "lsplantEnabled=false",
    "xposedEnabled=false",
    "businessNativeStubsEnabled=false",
    "businessNativeWrappersEnabled=false",
    "noOpPatchesEnabled=false",
    "nativeLoadVerdict=$(Get-SimpleVerdict -Pass $nativeLoadPass -Fail $nativeLoadFail)",
    "registerNativesVerdict=$(Get-SimpleVerdict -Pass $registerPass -Fail $false)",
    "findClassVerdict=$(Get-SimpleVerdict -Pass $findClassPass -Fail $false)",
    "selfKillVerdict=$(Get-SimpleVerdict -Pass $false -Fail $selfKillFail -PassLabel 'NOT_OBSERVED' -FailLabel 'OBSERVED')",
    "fatalVerdict=$(Get-SimpleVerdict -Pass $false -Fail $fatalFail -PassLabel 'NOT_OBSERVED' -FailLabel 'OBSERVED')",
    "interface20Verdict=$($interfaceVerdict[0])",
    "interface20VerdictReason=$($interfaceVerdict[1])",
    "jniOnLoadMarkers=$(Get-MarkerCount -Path $logcatFile -Pattern 'JNI_OnLoad|jni_onload|onLoad')",
    "registerNativesMarkers=$(Get-MarkerCount -Path $logcatFile -Pattern 'RegisterNatives|register_natives')",
    "findClassMarkers=$(Get-MarkerCount -Path $logcatFile -Pattern 'FindClass|find_class')",
    "fatalMarkers=$(Get-MarkerCount -Path $logcatFile -Pattern 'FATAL EXCEPTION|AndroidRuntime|SIGSEGV|signal 11|UnsatisfiedLinkError')",
    "logcat=$logcatFile",
    "crash=$crashFile",
    "exitInfo=$exitInfoFile",
    "hostedEvidence=$evidenceFile",
    "instances=$instancesFile",
    "storageFiles=$storageFile"
)
Save-Text $summaryFile $summary

Write-Host "Capture complete: $summaryFile"
