param(
    [string]$Adb = "",
    [string]$Device = "",
    [string]$HostPackage = "com.multiapp.app",
    [string]$OriginPackage = "com.qq.reader",
    [string]$InstanceId = "",
    [string]$OutDir = ".tmp",
    [int]$WaitSeconds = 15,
    [switch]$EnablePr11Diagnostics
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

function Get-RepoHead {
    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) { return "" }
    $head = (& git rev-parse --short=12 HEAD 2>$null)
    if ($LASTEXITCODE -ne 0) { return "" }
    return ($head | Select-Object -First 1).Trim()
}

function Get-DeviceValue {
    param([string[]]$Args)
    $value = Invoke-DeviceAdb @Args 2>$null
    if ($LASTEXITCODE -ne 0 -or $null -eq $value) { return "" }
    return (($value | Select-Object -First 1).ToString()).Trim()
}

function Get-ProcessIds {
    param(
        [string]$ProcessFile,
        [string[]]$Names
    )
    if (-not (Test-Path -LiteralPath $ProcessFile)) { return @() }
    $ids = New-Object System.Collections.Generic.List[string]
    foreach ($line in (Get-Content -LiteralPath $ProcessFile -Encoding UTF8)) {
        foreach ($name in $Names) {
            if ([string]::IsNullOrWhiteSpace($name)) { continue }
            if ($line -notmatch [Regex]::Escape($name)) { continue }
            $columns = $line -split "\s+"
            if ($columns.Count -ge 2 -and $columns[1] -match "^\d+$") {
                $ids.Add($columns[1])
            }
        }
    }
    return $ids | Sort-Object -Unique
}

function Save-FilteredLogcat {
    param(
        [string]$SourceFile,
        [string]$TargetFile,
        [string[]]$Pids
    )
    if (-not (Test-Path -LiteralPath $SourceFile)) {
        Save-Text $TargetFile @()
        return
    }
    if ($Pids.Count -eq 0) {
        Copy-Item -LiteralPath $SourceFile -Destination $TargetFile -Force
        return
    }
    $pidAlternation = ($Pids | ForEach-Object { [Regex]::Escape($_) }) -join "|"
    $pattern = "^\S+\s+\S+\s+(?<pid>$pidAlternation)\s+"
    Get-Content -LiteralPath $SourceFile -Encoding UTF8 |
        Where-Object { $_ -match $pattern -or $_ -match "MultiApp|RegisterNatives|JNI_OnLoad|interface20|StubApp|AndroidRuntime" } |
        Set-Content -LiteralPath $TargetFile -Encoding UTF8
}

function Get-HostedEvidenceProperties {
    param([string]$EvidenceText)
    $props = @{}
    if ([string]::IsNullOrWhiteSpace($EvidenceText)) { return $props }
    $blocks = [Regex]::Matches(
        $EvidenceText,
        "(?ms)^===files/hosted_launch_evidence/(?<name>[^=]+\.properties)===\r?\n(?<body>.*?)(?=^===files/hosted_launch_evidence/|\z)"
    )
    foreach ($block in $blocks) {
        $name = $block.Groups["name"].Value
        if ($name -notmatch "\.protected-verdict\.properties$" -and
            $name -notmatch "\.register-natives\.properties$" -and
            $name -notmatch "\.native-load\.properties$") {
            continue
        }
        foreach ($line in ($block.Groups["body"].Value -split "\r?\n")) {
            if ($line -notmatch "^\s*([^#=\s][^=]*)=(.*)$") { continue }
            $key = $Matches[1].Trim()
            $value = $Matches[2].Trim()
            if (-not $props.ContainsKey($key)) {
                $props[$key] = $value
            }
        }
    }
    return $props
}

function Get-PropOrDefault {
    param(
        [hashtable]$Props,
        [string]$Key,
        [string]$Default
    )
    if ($Props.ContainsKey($Key) -and -not [string]::IsNullOrWhiteSpace($Props[$Key])) {
        return $Props[$Key]
    }
    return $Default
}

$script:AdbPath = Resolve-AdbPath $Adb
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $OutDir "qqreader-hosted-diagnostics-$timestamp"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$devicesFile = Join-Path $evidenceDir "devices.txt"
$hostPackageFile = Join-Path $evidenceDir "host-package.txt"
$originPackageFile = Join-Path $evidenceDir "origin-package.txt"
$logcatFile = Join-Path $evidenceDir "logcat.txt"
$filteredLogcatFile = Join-Path $evidenceDir "logcat-filtered.txt"
$crashFile = Join-Path $evidenceDir "crash.txt"
$exitInfoFile = Join-Path $evidenceDir "exit-info.txt"
$processFile = Join-Path $evidenceDir "process.txt"
$evidenceFile = Join-Path $evidenceDir "hosted-launch-evidence.txt"
$instancesFile = Join-Path $evidenceDir "instances.txt"
$storageFile = Join-Path $evidenceDir "storage-files.txt"
$metadataFile = Join-Path $evidenceDir "metadata.txt"
$summaryFile = Join-Path $evidenceDir "summary.txt"

Write-Host "Device: $Device"
Write-Host "Output: $evidenceDir"
Write-Host "Capture mode: hosted QQ Reader diagnostics, observe-only. Start the hosted instance manually before or during the wait window."

$captureStartedAt = (Get-Date).ToString("o")
$repoHead = Get-RepoHead
$deviceSerial = Get-DeviceValue @("get-serialno")
$androidSdk = Get-DeviceValue @("shell", "getprop", "ro.build.version.sdk")
$deviceAbi = Get-DeviceValue @("shell", "getprop", "ro.product.cpu.abi")
$logcatCleared = "false"

if ($EnablePr11Diagnostics) {
    Invoke-DeviceAdb shell setprop debug.multiapp.pr11.register_natives_diagnostics 1 | Out-Null
    Invoke-DeviceAdb shell setprop debug.multiapp.qqreader.diagnostics 0 | Out-Null
}

Save-Text $devicesFile (Invoke-DeviceAdb devices -l)
Save-Text $hostPackageFile (Invoke-DeviceAdb shell dumpsys package $HostPackage)
Save-Text $originPackageFile (Invoke-DeviceAdb shell dumpsys package $OriginPackage)

Invoke-DeviceAdb logcat -c | Out-Null
if ($LASTEXITCODE -eq 0) { $logcatCleared = "true" }
Start-Sleep -Seconds $WaitSeconds

Save-Text $logcatFile (Invoke-DeviceAdb logcat -d -v threadtime)
Save-Text $crashFile (Invoke-DeviceAdb logcat -b crash -d -v time)
Save-Text $exitInfoFile (Invoke-DeviceAdb shell dumpsys activity exit-info $HostPackage)
Save-Text $processFile (Invoke-DeviceAdb shell ps -A)
$processIds = @(Get-ProcessIds -ProcessFile $processFile -Names @($HostPackage, $OriginPackage))
Save-FilteredLogcat -SourceFile $logcatFile -TargetFile $filteredLogcatFile -Pids $processIds
Capture-HostedEvidence -TargetFile $evidenceFile
Capture-InstanceRecords -TargetFile $instancesFile
Save-Text $storageFile (Invoke-DeviceAdb shell run-as $HostPackage find files/instance_data -maxdepth 4 -type f 2>&1)
$captureEndedAt = (Get-Date).ToString("o")

Save-Text $metadataFile @(
    "captureStartedAt=$captureStartedAt",
    "captureEndedAt=$captureEndedAt",
    "repoHead=$repoHead",
    "adb=$script:AdbPath",
    "device=$Device",
    "deviceSerial=$deviceSerial",
    "androidSdk=$androidSdk",
    "abi=$deviceAbi",
    "logcatCleared=$logcatCleared",
    "enablePr11Diagnostics=$($EnablePr11Diagnostics.IsPresent)",
    "hostPackage=$HostPackage",
    "originPackage=$OriginPackage",
    "instanceId=$InstanceId",
    "processIds=$($processIds -join ',')"
)

$logText = ""
if (Test-Path -LiteralPath $filteredLogcatFile) { $logText += Get-Content -LiteralPath $filteredLogcatFile -Raw -Encoding UTF8 }
if (Test-Path -LiteralPath $crashFile) { $logText += "`n" + (Get-Content -LiteralPath $crashFile -Raw -Encoding UTF8) }
if (Test-Path -LiteralPath $evidenceFile) { $logText += "`n" + (Get-Content -LiteralPath $evidenceFile -Raw -Encoding UTF8) }

$hostedEvidenceText = ""
if (Test-Path -LiteralPath $evidenceFile) {
    $hostedEvidenceText = Get-Content -LiteralPath $evidenceFile -Raw -Encoding UTF8
}
$hostedProps = Get-HostedEvidenceProperties -EvidenceText $hostedEvidenceText
$interfaceVerdict = Get-Interface20Verdict -LogText $logText
$nativeLoadFail = $logText -match "UnsatisfiedLinkError|dlopen failed|couldn't find .*\.so|cannot locate symbol"
$nativeLoadPass = $logText -match "JNI_OnLoad|System\.loadLibrary|loadLibrary|dlopen"
$registerPass = $logText -match "RegisterNatives|register_natives"
$findClassPass = $logText -match "FindClass|find_class"
$selfKillFail = $logText -match "killProcess|Process\.killProcess|System\.exit|SIGKILL|REASON_SIGNALED"
$fatalFail = $logText -match "FATAL EXCEPTION|AndroidRuntime|SIGSEGV|signal 11|native crash"
$verdictSource = if ($hostedProps.ContainsKey("interface20Verdict")) { "hostedEvidence" } else { "logcatFallback" }

$nativeLoadVerdict = Get-PropOrDefault -Props $hostedProps -Key "nativeLoadVerdict" -Default (Get-SimpleVerdict -Pass $nativeLoadPass -Fail $nativeLoadFail)
$jniOnLoadVerdict = Get-PropOrDefault -Props $hostedProps -Key "jniOnLoadVerdict" -Default (Get-SimpleVerdict -Pass ($logText -match "JNI_OnLoad|jni_onload") -Fail $false)
$registerNativesVerdict = Get-PropOrDefault -Props $hostedProps -Key "registerNativesVerdict" -Default (Get-SimpleVerdict -Pass $registerPass -Fail $false)
$findClassVerdict = Get-PropOrDefault -Props $hostedProps -Key "findClassVerdict" -Default (Get-SimpleVerdict -Pass $findClassPass -Fail $false)
$namespaceVerdict = Get-PropOrDefault -Props $hostedProps -Key "namespaceVerdict" -Default "UNKNOWN"
$classLoaderVerdict = Get-PropOrDefault -Props $hostedProps -Key "classLoaderVerdict" -Default "UNKNOWN"
$interface20Verdict = Get-PropOrDefault -Props $hostedProps -Key "interface20Verdict" -Default $interfaceVerdict[0]
$interface20VerdictReason = Get-PropOrDefault -Props $hostedProps -Key "interface20VerdictReason" -Default $interfaceVerdict[1]
$policyMode = Get-PropOrDefault -Props $hostedProps -Key "policyMode" -Default ""
$registerNativesObserveOnlyEnabled = Get-PropOrDefault -Props $hostedProps -Key "registerNativesObserveOnlyEnabled" -Default ""

$summary = @(
    "status=CAPTURED",
    "timestamp=$timestamp",
    "captureStartedAt=$captureStartedAt",
    "captureEndedAt=$captureEndedAt",
    "repoHead=$repoHead",
    "adb=$script:AdbPath",
    "device=$Device",
    "deviceSerial=$deviceSerial",
    "androidSdk=$androidSdk",
    "abi=$deviceAbi",
    "logcatCleared=$logcatCleared",
    "hostPackage=$HostPackage",
    "originPackage=$OriginPackage",
    "instanceId=$InstanceId",
    "waitSeconds=$WaitSeconds",
    "enablePr11Diagnostics=$($EnablePr11Diagnostics.IsPresent)",
    "mode=hosted-register-natives-only-diagnostics",
    "verdictSource=$verdictSource",
    "policyMode=$policyMode",
    "registerNativesObserveOnlyEnabled=$registerNativesObserveOnlyEnabled",
    "lsplantEnabled=false",
    "xposedEnabled=false",
    "businessNativeStubsEnabled=false",
    "businessNativeWrappersEnabled=false",
    "nativeBaseHooksEnabled=false",
    "methodReplacementEnabled=false",
    "noOpPatchesEnabled=false",
    "compatibilityClaim=false",
    "nativeLoadVerdict=$nativeLoadVerdict",
    "jniOnLoadVerdict=$jniOnLoadVerdict",
    "findClassVerdict=$findClassVerdict",
    "registerNativesVerdict=$registerNativesVerdict",
    "interface20Verdict=$interface20Verdict",
    "interface20VerdictReason=$interface20VerdictReason",
    "namespaceVerdict=$namespaceVerdict",
    "classLoaderVerdict=$classLoaderVerdict",
    "selfKillVerdict=$(Get-SimpleVerdict -Pass $false -Fail $selfKillFail -PassLabel 'NOT_OBSERVED' -FailLabel 'OBSERVED')",
    "fatalVerdict=$(Get-SimpleVerdict -Pass $false -Fail $fatalFail -PassLabel 'NOT_OBSERVED' -FailLabel 'OBSERVED')",
    "jniOnLoadMarkers=$(Get-MarkerCount -Path $filteredLogcatFile -Pattern 'JNI_OnLoad|jni_onload|onLoad')",
    "registerNativesMarkers=$(Get-MarkerCount -Path $filteredLogcatFile -Pattern 'RegisterNatives|register_natives')",
    "findClassMarkers=$(Get-MarkerCount -Path $filteredLogcatFile -Pattern 'FindClass|find_class')",
    "fatalMarkers=$(Get-MarkerCount -Path $filteredLogcatFile -Pattern 'FATAL EXCEPTION|AndroidRuntime|SIGSEGV|signal 11|UnsatisfiedLinkError')",
    "processIds=$($processIds -join ',')",
    "logcat=$logcatFile",
    "filteredLogcat=$filteredLogcatFile",
    "crash=$crashFile",
    "exitInfo=$exitInfoFile",
    "hostedEvidence=$evidenceFile",
    "instances=$instancesFile",
    "storageFiles=$storageFile",
    "metadata=$metadataFile"
)
Save-Text $summaryFile $summary

Write-Host "Capture complete: $summaryFile"
