param(
    [string]$Serial = "",
    [string]$OutputDir = ".tmp",
    [string]$AdbPath = ""
)

$ErrorActionPreference = "Stop"

function Run-Step {
    param(
        [string]$Name,
        [scriptblock]$Body
    )
    Write-Host "==> $Name"
    & $Body
}

function Invoke-Native {
    param(
        [scriptblock]$Body,
        [string]$FailureMessage
    )

    & $Body
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage ExitCode=$LASTEXITCODE"
    }
}

function Append-HostedEvidenceFile {
    param(
        [string]$Name,
        [string]$TargetFile
    )

    $trimmed = $Name.Trim()
    if ($trimmed.Length -eq 0) { return }
    "===files/hosted_launch_evidence/$trimmed===" | Tee-Object -Append -FilePath $TargetFile | Out-Null
    & $adb @adbArgs shell run-as com.multiapp.app cat "files/hosted_launch_evidence/$trimmed" 2>&1 |
        Tee-Object -Append -FilePath $TargetFile | Out-Null
    "" | Tee-Object -Append -FilePath $TargetFile | Out-Null
}

function Capture-HostedEvidence {
    param([string]$TargetFile)

    $files = (& $adb @adbArgs shell run-as com.multiapp.app ls files/hosted_launch_evidence) 2>&1
    if ($LASTEXITCODE -ne 0) {
        "hosted_launch_evidence directory missing" | Tee-Object -FilePath $TargetFile | Out-Null
        $files | Tee-Object -Append -FilePath $TargetFile | Out-Null
        return
    }

    foreach ($file in $files) {
        Append-HostedEvidenceFile -Name $file -TargetFile $TargetFile
    }
}

function Copy-InstrumentationResults {
    param([string]$TargetDir)

    $sourceDir = Join-Path $repo "app\build\outputs\androidTest-results\connected\debug"
    $targetResultsDir = Join-Path $TargetDir "androidTest-results"
    New-Item -ItemType Directory -Force -Path $targetResultsDir | Out-Null

    if (-not (Test-Path -LiteralPath $sourceDir)) {
        throw "AndroidTest result directory missing: $sourceDir"
    }

    Copy-Item -Path (Join-Path $sourceDir "*") -Destination $targetResultsDir -Recurse -Force
    $xml = Get-ChildItem -Path $targetResultsDir -Recurse -Filter "TEST-*.xml" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $xml) {
        throw "AndroidTest XML result missing under: $targetResultsDir"
    }

    return $xml.FullName
}

function Read-InstrumentationResultSummary {
    param([string]$XmlPath)

    [xml]$doc = Get-Content -Path $XmlPath -Encoding UTF8
    $suite = $doc.testsuite
    if (-not $suite) {
        throw "AndroidTest XML has no testsuite root: $XmlPath"
    }

    return [pscustomobject]@{
        Tests = [int]$suite.tests
        Failures = [int]$suite.failures
        Errors = [int]$suite.errors
        Skipped = [int]$suite.skipped
        File = $XmlPath
    }
}

function Read-HostedEvidenceSections {
    param([string]$Path)

    $sections = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $sections
    }

    $text = Get-Content -Path $Path -Raw -Encoding UTF8
    $pattern = '(?ms)^===files/hosted_launch_evidence/(?<name>[^=]+?)===\r?\n(?<body>.*?)(?=^===files/hosted_launch_evidence/|\z)'
    foreach ($match in [regex]::Matches($text, $pattern)) {
        $name = $match.Groups['name'].Value.Trim()
        $body = $match.Groups['body'].Value
        $properties = @{}
        foreach ($line in ($body -split "`r?`n")) {
            $trimmed = $line.Trim()
            if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) { continue }
            $separator = $trimmed.IndexOf("=")
            if ($separator -lt 0) { continue }
            $key = $trimmed.Substring(0, $separator).Trim()
            $value = $trimmed.Substring($separator + 1).Trim()
            $properties[$key] = $value
        }
        if ($name.Length -gt 0) {
            $sections[$name] = $properties
        }
    }
    return $sections
}

function Get-HostedEvidenceSectionBySuffix {
    param(
        [hashtable]$Sections,
        [string]$Suffix
    )

    foreach ($key in $Sections.Keys) {
        if ($key.EndsWith($Suffix)) {
            return $Sections[$key]
        }
    }
    return $null
}

function Get-HostedEvidenceSectionByInstanceSuffix {
    param(
        [hashtable]$Sections,
        [string]$InstanceId,
        [string]$Suffix
    )

    $key = "$InstanceId$Suffix"
    if ($Sections.ContainsKey($key)) {
        return $Sections[$key]
    }
    return $null
}

function Get-HostedEvidenceInstanceIds {
    param([hashtable]$Sections)

    $ids = New-Object System.Collections.Generic.HashSet[string]
    foreach ($key in $Sections.Keys) {
        if ($key -match '^(?<id>.+?)\.(launch|activity-instrumentation|activity-context|activity-remap|provider-proxy|service-proxy|broadcast|proxy-activity)\.properties$') {
            [void]$ids.Add($Matches['id'])
        }
    }
    return @($ids | Sort-Object)
}

function Get-HostedActivityVerdictFromSections {
    param(
        [hashtable]$Sections,
        [string]$InstanceId = ""
    )

    if ($InstanceId.Trim().Length -gt 0) {
        $instrumentation = Get-HostedEvidenceSectionByInstanceSuffix -Sections $Sections -InstanceId $InstanceId -Suffix ".activity-instrumentation.properties"
        $context = Get-HostedEvidenceSectionByInstanceSuffix -Sections $Sections -InstanceId $InstanceId -Suffix ".activity-context.properties"
        $remap = Get-HostedEvidenceSectionByInstanceSuffix -Sections $Sections -InstanceId $InstanceId -Suffix ".activity-remap.properties"
    } else {
        $instrumentation = Get-HostedEvidenceSectionBySuffix -Sections $Sections -Suffix ".activity-instrumentation.properties"
        $context = Get-HostedEvidenceSectionBySuffix -Sections $Sections -Suffix ".activity-context.properties"
        $remap = Get-HostedEvidenceSectionBySuffix -Sections $Sections -Suffix ".activity-remap.properties"
    }

    $instrumentationStatus = if ($instrumentation) { $instrumentation["status"] } else { "MISSING" }
    $contextStatus = if ($context) { $context["status"] } else { "MISSING" }
    $contextInjected = if ($context) { $context["contextInjected"] } else { "" }
    $remapStatus = if ($remap) { $remap["status"] } else { "MISSING" }
    $virtualPackageName = if ($context) { $context["packageName"] } else { "" }
    $dataDir = if ($context) { $context["dataDir"] } else { "" }

    $substituted = $instrumentationStatus -eq "GUEST_ACTIVITY_SUBSTITUTED"
    $contextReady = $contextStatus -eq "GUEST_ACTIVITY_CONTEXT_INJECTED" -and $contextInjected -eq "true"
    $verdict = if ($substituted -and $contextReady) { "PASS" } else { "FAIL" }
    $reason = if ($verdict -eq "PASS") {
        "activity substituted and context injected"
    } elseif (-not $substituted) {
        "missing GUEST_ACTIVITY_SUBSTITUTED"
    } elseif (-not $contextReady) {
        "missing GUEST_ACTIVITY_CONTEXT_INJECTED with contextInjected=true"
    } else {
        "unknown"
    }

    return [pscustomobject]@{
        Verdict = $verdict
        Reason = $reason
        InstrumentationStatus = $instrumentationStatus
        ContextStatus = $contextStatus
        ContextInjected = $contextInjected
        RemapStatus = $remapStatus
        VirtualPackageName = $virtualPackageName
        DataDir = $dataDir
    }
}

function Get-HostedActivityVerdict {
    param([string]$EvidencePath)

    $sections = Read-HostedEvidenceSections -Path $EvidencePath
    return Get-HostedActivityVerdictFromSections -Sections $sections
}

function Get-HostedComponentVerdict {
    param(
        [string]$EvidencePath,
        [string]$Suffix,
        [string[]]$PassStatuses,
        [string]$MissingReason
    )

    $sections = Read-HostedEvidenceSections -Path $EvidencePath
    $section = Get-HostedEvidenceSectionBySuffix -Sections $sections -Suffix $Suffix
    if (-not $section) {
        return [pscustomobject]@{
            Verdict = "UNKNOWN"
            Reason = $MissingReason
            Status = "MISSING"
            Detail = ""
        }
    }

    $status = $section["status"]
    $detail = if ($section.ContainsKey("detail")) { $section["detail"] } else { "" }
    $verdict = if ($PassStatuses -contains $status) { "PASS" } else { "FAIL" }
    $reason = if ($verdict -eq "PASS") { "status=$status" } else { "unexpected status=$status" }
    return [pscustomobject]@{
        Verdict = $verdict
        Reason = $reason
        Status = $status
        Detail = $detail
    }
}

function Get-HostedComponentVerdictFromSections {
    param(
        [hashtable]$Sections,
        [string]$InstanceId,
        [string]$Suffix,
        [string[]]$PassStatuses,
        [string]$MissingReason
    )

    $section = Get-HostedEvidenceSectionByInstanceSuffix -Sections $Sections -InstanceId $InstanceId -Suffix $Suffix
    if (-not $section) {
        return [pscustomobject]@{
            Verdict = "UNKNOWN"
            Reason = $MissingReason
            Status = "MISSING"
            Detail = ""
        }
    }

    $status = $section["status"]
    $detail = if ($section.ContainsKey("detail")) { $section["detail"] } else { "" }
    $verdict = if ($PassStatuses -contains $status) { "PASS" } else { "FAIL" }
    $reason = if ($verdict -eq "PASS") { "status=$status" } else { "unexpected status=$status" }
    return [pscustomobject]@{
        Verdict = $verdict
        Reason = $reason
        Status = $status
        Detail = $detail
    }
}

function Get-StorageVerdictFromLogcat {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        return [pscustomobject]@{
            Verdict = "UNKNOWN"
            Reason = "logcat missing"
        }
    }

    $text = Get-Content -Path $Path -Raw -Encoding UTF8
    if ($text -notmatch "=== storage probe ===") {
        return [pscustomobject]@{
            Verdict = "UNKNOWN"
            Reason = "storage probe log missing"
        }
    }

    $hasFailure = $text -match "prefs failed:|file failed:|db failed:"
    $hasPrefs = $text -match "prefs\.launchCount:"
    $hasFile = $text -match "file\.path:"
    $hasDb = $text -match "db\.path:" -and $text -match "db\.rows:"
    $pass = -not $hasFailure -and $hasPrefs -and $hasFile -and $hasDb
    $reason = if ($pass) {
        "prefs/file/db probes present"
    } elseif ($hasFailure) {
        "storage probe contains failure"
    } else {
        "storage probe incomplete"
    }

    return [pscustomobject]@{
        Verdict = if ($pass) { "PASS" } else { "FAIL" }
        Reason = $reason
    }
}

function Test-HostedAppFileExists {
    param([string]$Path)

    if ($Path.Trim().Length -eq 0) { return $false }
    & $adb @adbArgs shell run-as com.multiapp.app test -f $Path 2>&1 | Out-Null
    return $LASTEXITCODE -eq 0
}

function Get-StorageFileVerdictFromDataDir {
    param([string]$DataDir)

    if ($DataDir.Trim().Length -eq 0) {
        return [pscustomobject]@{
            Verdict = "UNKNOWN"
            Reason = "activity context dataDir missing"
            DataDir = ""
            ProbeFileExists = $false
            SharedPrefsExists = $false
            DatabaseExists = $false
        }
    }

    $probeFile = "$DataDir/files/probe.txt"
    $sharedPrefs = "$DataDir/shared_prefs/probe.xml"
    $database = "$DataDir/databases/probe.db"
    $probeFileExists = Test-HostedAppFileExists -Path $probeFile
    $sharedPrefsExists = Test-HostedAppFileExists -Path $sharedPrefs
    $databaseExists = Test-HostedAppFileExists -Path $database
    $pass = $probeFileExists -and $sharedPrefsExists -and $databaseExists
    $reason = if ($pass) {
        "probe/shared_prefs/database files exist under dataDir"
    } else {
        "missing files under dataDir: probeFile=$probeFileExists sharedPrefs=$sharedPrefsExists database=$databaseExists"
    }

    return [pscustomobject]@{
        Verdict = if ($pass) { "PASS" } else { "FAIL" }
        Reason = $reason
        DataDir = $DataDir
        ProbeFileExists = $probeFileExists
        SharedPrefsExists = $sharedPrefsExists
        DatabaseExists = $databaseExists
    }
}

function Get-StorageFileVerdictFromDevice {
    param([string]$EvidencePath)

    $sections = Read-HostedEvidenceSections -Path $EvidencePath
    $context = Get-HostedEvidenceSectionBySuffix -Sections $sections -Suffix ".activity-context.properties"
    $dataDir = if ($context) { $context["dataDir"] } else { "" }
    if ($dataDir.Trim().Length -eq 0) {
        return [pscustomobject]@{
            Verdict = "UNKNOWN"
            Reason = "activity context dataDir missing"
            DataDir = ""
            ProbeFileExists = $false
            SharedPrefsExists = $false
            DatabaseExists = $false
        }
    }

    return Get-StorageFileVerdictFromDataDir -DataDir $dataDir
}

function Get-DualInstanceHostedVerdict {
    param([string]$EvidencePath)

    $sections = Read-HostedEvidenceSections -Path $EvidencePath
    $instanceIds = Get-HostedEvidenceInstanceIds -Sections $sections
    $records = @()
    foreach ($instanceId in $instanceIds) {
        $activity = Get-HostedActivityVerdictFromSections -Sections $sections -InstanceId $instanceId
        $provider = Get-HostedComponentVerdictFromSections -Sections $sections -InstanceId $instanceId -Suffix ".provider-proxy.properties" -PassStatuses @("PROVIDER_CREATED", "PROVIDER_CACHED") -MissingReason "provider evidence not exercised"
        $service = Get-HostedComponentVerdictFromSections -Sections $sections -InstanceId $instanceId -Suffix ".service-proxy.properties" -PassStatuses @("STARTED") -MissingReason "service evidence not exercised"
        $broadcast = Get-HostedComponentVerdictFromSections -Sections $sections -InstanceId $instanceId -Suffix ".broadcast.properties" -PassStatuses @("Delivered") -MissingReason "broadcast evidence not exercised"
        $storage = Get-StorageFileVerdictFromDataDir -DataDir $activity.DataDir
        $records += [pscustomobject]@{
            InstanceId = $instanceId
            VirtualPackageName = $activity.VirtualPackageName
            DataDir = $activity.DataDir
            ActivityVerdict = $activity.Verdict
            ProviderVerdict = $provider.Verdict
            ServiceVerdict = $service.Verdict
            BroadcastVerdict = $broadcast.Verdict
            StorageFilesVerdict = $storage.Verdict
            ProbeFileExists = $storage.ProbeFileExists
            SharedPrefsExists = $storage.SharedPrefsExists
            DatabaseExists = $storage.DatabaseExists
        }
    }

    $validRecords = @($records | Where-Object { $_.ActivityVerdict -eq "PASS" })
    $dataRoots = @($validRecords | ForEach-Object { $_.DataDir } | Where-Object { $_.Trim().Length -gt 0 } | Sort-Object -Unique)
    $virtualPackages = @($validRecords | ForEach-Object { $_.VirtualPackageName } | Where-Object { $_.Trim().Length -gt 0 } | Sort-Object -Unique)
    $componentFailures = @($records | Where-Object {
        $_.ActivityVerdict -ne "PASS" -or
        $_.ProviderVerdict -ne "PASS" -or
        $_.ServiceVerdict -ne "PASS" -or
        $_.BroadcastVerdict -ne "PASS" -or
        $_.StorageFilesVerdict -ne "PASS"
    })
    $instanceCountPass = $records.Count -ge 2
    $dataRootDifferent = $dataRoots.Count -ge 2
    $virtualPackageDifferent = $virtualPackages.Count -ge 2
    $pass = $instanceCountPass -and $dataRootDifferent -and $virtualPackageDifferent -and $componentFailures.Count -eq 0

    $reason = if ($pass) {
        "two hosted instances passed component and storage isolation checks"
    } elseif (-not $instanceCountPass) {
        "less than two hosted instances found: count=$($records.Count)"
    } elseif (-not $dataRootDifferent) {
        "hosted instances do not have distinct dataRoot values"
    } elseif (-not $virtualPackageDifferent) {
        "hosted instances do not have distinct virtualPackageName values"
    } else {
        "one or more per-instance component/storage verdicts failed"
    }

    $recordSummary = @($records | ForEach-Object {
        "$($_.InstanceId):pkg=$($_.VirtualPackageName),dataDir=$($_.DataDir),activity=$($_.ActivityVerdict),provider=$($_.ProviderVerdict),service=$($_.ServiceVerdict),broadcast=$($_.BroadcastVerdict),storageFiles=$($_.StorageFilesVerdict)"
    }) -join " | "

    return [pscustomobject]@{
        Verdict = if ($pass) { "PASS" } else { "FAIL" }
        Reason = $reason
        InstanceCount = $records.Count
        DataRootDifferent = $dataRootDifferent
        VirtualPackageNameDifferent = $virtualPackageDifferent
        Summary = $recordSummary
    }
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
    $candidates += "C:\adb\platform-tools\adb.exe"
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

$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $repo

$adb = Resolve-AdbPath $AdbPath

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $OutputDir "minimal-hosted-baseline-$timestamp"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

$adbArgs = @()
if ($Serial.Trim().Length -gt 0) {
    $adbArgs += @("-s", $Serial)
}

$summary = Join-Path $evidenceDir "summary.txt"
$gradleLog = Join-Path $evidenceDir "gradle-output.txt"
$logcatFile = Join-Path $evidenceDir "logcat.txt"
$runtimeEvidence = Join-Path $evidenceDir "hosted-launch-evidence.txt"
$script:instrumentationXml = ""
$script:instrumentationTests = 0
$script:instrumentationFailures = 0
$script:instrumentationErrors = 0
$script:instrumentationSkipped = 0
$script:activityVerdict = "UNKNOWN"
$script:activityVerdictReason = ""
$script:activityInstrumentationStatus = ""
$script:activityContextStatus = ""
$script:activityContextInjected = ""
$script:activityRemapStatus = ""
$script:providerVerdict = "UNKNOWN"
$script:providerVerdictReason = ""
$script:providerStatus = ""
$script:serviceVerdict = "UNKNOWN"
$script:serviceVerdictReason = ""
$script:serviceStatus = ""
$script:broadcastVerdict = "UNKNOWN"
$script:broadcastVerdictReason = ""
$script:broadcastStatus = ""
$script:storageVerdict = "UNKNOWN"
$script:storageVerdictReason = ""
$script:storageFilesVerdict = "UNKNOWN"
$script:storageFilesVerdictReason = ""
$script:storageDataDir = ""
$script:storageProbeFileExists = "false"
$script:storageSharedPrefsExists = "false"
$script:storageDatabaseExists = "false"
$script:dualInstanceVerdict = "UNKNOWN"
$script:dualInstanceVerdictReason = ""
$script:dualInstanceCount = 0
$script:dualInstanceDataRootDifferent = "false"
$script:dualInstanceVirtualPackageNameDifferent = "false"
$script:dualInstanceSummary = ""

try {
    if ($Serial -match "^[^:]+:\d+$") {
        Run-Step "Connect adb over TCP" {
            Invoke-Native {
                & $adb connect $Serial 2>&1 |
                    Tee-Object -FilePath (Join-Path $evidenceDir "adb-connect.txt")
            } "adb TCP connect failed."
        }
    }

    Run-Step "Check adb device" {
        $devices = (& $adb devices) -join "`n"
        $devices | Tee-Object -FilePath (Join-Path $evidenceDir "adb-devices.txt")
        if ($Serial.Trim().Length -gt 0) {
            $escapedSerial = [regex]::Escape($Serial)
            if ($devices -notmatch "$escapedSerial\s+device") {
                throw "ADB device is not online: $Serial"
            }
        } elseif ($devices -notmatch "\bdevice\b") {
            throw "No adb device is available. Connect a device or pass -Serial."
        }
    }

    Run-Step "Build minimal fixture APK" {
        Invoke-Native {
            .\gradlew.bat :test-fixtures:minimal-app:assembleDebug --console=plain --no-build-cache 2>&1 |
                Tee-Object -FilePath $gradleLog
        } "Gradle minimal fixture build failed."
    }

    $minimalApk = Join-Path $repo "test-fixtures\minimal-app\build\outputs\apk\debug\minimal-app-debug.apk"
    if (-not (Test-Path $minimalApk)) {
        throw "Minimal APK not found: $minimalApk"
    }

    Run-Step "Install minimal fixture APK" {
        Invoke-Native {
            & $adb @adbArgs install -r $minimalApk 2>&1 |
                Tee-Object -FilePath (Join-Path $evidenceDir "adb-install-minimal.txt")
        } "Minimal fixture APK install failed."
    }

    Run-Step "Clear logcat" {
        Invoke-Native { & $adb @adbArgs logcat -c } "logcat clear failed."
    }

    Run-Step "Run hosted container minimal baseline instrumentation" {
        Invoke-Native {
            .\gradlew.bat :app:connectedDebugAndroidTest `
                "-Pandroid.testInstrumentationRunnerArguments.class=com.multiapp.app.HostedContainerMinimalBaselineTest" `
                --console=plain --no-build-cache 2>&1 |
                Tee-Object -Append -FilePath $gradleLog
        } "Hosted container minimal baseline instrumentation failed."
    }

    Run-Step "Validate instrumentation result XML" {
        $script:instrumentationXml = Copy-InstrumentationResults -TargetDir $evidenceDir
        $instrumentation = Read-InstrumentationResultSummary -XmlPath $script:instrumentationXml
        $script:instrumentationTests = $instrumentation.Tests
        $script:instrumentationFailures = $instrumentation.Failures
        $script:instrumentationErrors = $instrumentation.Errors
        $script:instrumentationSkipped = $instrumentation.Skipped

        if ($script:instrumentationTests -le 0) {
            throw "HostedContainerMinimalBaselineTest did not execute. tests=$script:instrumentationTests xml=$script:instrumentationXml"
        }
        if ($script:instrumentationFailures -gt 0 -or $script:instrumentationErrors -gt 0) {
            throw "HostedContainerMinimalBaselineTest failed. tests=$script:instrumentationTests failures=$script:instrumentationFailures errors=$script:instrumentationErrors xml=$script:instrumentationXml"
        }
    }

    Run-Step "Capture hosted runtime evidence" {
        Capture-HostedEvidence -TargetFile $runtimeEvidence
    }

    Run-Step "Validate hosted Activity evidence" {
        $activity = Get-HostedActivityVerdict -EvidencePath $runtimeEvidence
        $script:activityVerdict = $activity.Verdict
        $script:activityVerdictReason = $activity.Reason
        $script:activityInstrumentationStatus = $activity.InstrumentationStatus
        $script:activityContextStatus = $activity.ContextStatus
        $script:activityContextInjected = $activity.ContextInjected
        $script:activityRemapStatus = $activity.RemapStatus

        if ($script:activityVerdict -ne "PASS") {
            throw "Hosted Activity evidence failed. verdict=$script:activityVerdict reason=$script:activityVerdictReason instrumentation=$script:activityInstrumentationStatus context=$script:activityContextStatus contextInjected=$script:activityContextInjected"
        }
    }

    Run-Step "Summarize hosted component evidence" {
        $provider = Get-HostedComponentVerdict `
            -EvidencePath $runtimeEvidence `
            -Suffix ".provider-proxy.properties" `
            -PassStatuses @("PROVIDER_CREATED", "PROVIDER_CACHED") `
            -MissingReason "provider evidence not exercised"
        $script:providerVerdict = $provider.Verdict
        $script:providerVerdictReason = $provider.Reason
        $script:providerStatus = $provider.Status

        $service = Get-HostedComponentVerdict `
            -EvidencePath $runtimeEvidence `
            -Suffix ".service-proxy.properties" `
            -PassStatuses @("STARTED") `
            -MissingReason "service evidence not exercised"
        $script:serviceVerdict = $service.Verdict
        $script:serviceVerdictReason = $service.Reason
        $script:serviceStatus = $service.Status

        $broadcast = Get-HostedComponentVerdict `
            -EvidencePath $runtimeEvidence `
            -Suffix ".broadcast.properties" `
            -PassStatuses @("Delivered") `
            -MissingReason "broadcast evidence not exercised"
        $script:broadcastVerdict = $broadcast.Verdict
        $script:broadcastVerdictReason = $broadcast.Reason
        $script:broadcastStatus = $broadcast.Status
    }

    Run-Step "Capture logcat" {
        & $adb @adbArgs logcat -d -v time | Tee-Object -FilePath $logcatFile | Out-Null
    }

    Run-Step "Validate storage probe log" {
        $storage = Get-StorageVerdictFromLogcat -Path $logcatFile
        $script:storageVerdict = $storage.Verdict
        $script:storageVerdictReason = $storage.Reason
        if ($script:storageVerdict -eq "FAIL") {
            throw "Storage probe failed. reason=$script:storageVerdictReason"
        }
    }

    Run-Step "Validate hosted storage files" {
        $storageFiles = Get-StorageFileVerdictFromDevice -EvidencePath $runtimeEvidence
        $script:storageFilesVerdict = $storageFiles.Verdict
        $script:storageFilesVerdictReason = $storageFiles.Reason
        $script:storageDataDir = $storageFiles.DataDir
        $script:storageProbeFileExists = $storageFiles.ProbeFileExists.ToString().ToLowerInvariant()
        $script:storageSharedPrefsExists = $storageFiles.SharedPrefsExists.ToString().ToLowerInvariant()
        $script:storageDatabaseExists = $storageFiles.DatabaseExists.ToString().ToLowerInvariant()
        if ($script:storageFilesVerdict -eq "FAIL") {
            throw "Hosted storage files failed. reason=$script:storageFilesVerdictReason dataDir=$script:storageDataDir"
        }
    }

    Run-Step "Validate dual hosted instance isolation" {
        $dual = Get-DualInstanceHostedVerdict -EvidencePath $runtimeEvidence
        $script:dualInstanceVerdict = $dual.Verdict
        $script:dualInstanceVerdictReason = $dual.Reason
        $script:dualInstanceCount = $dual.InstanceCount
        $script:dualInstanceDataRootDifferent = $dual.DataRootDifferent.ToString().ToLowerInvariant()
        $script:dualInstanceVirtualPackageNameDifferent = $dual.VirtualPackageNameDifferent.ToString().ToLowerInvariant()
        $script:dualInstanceSummary = $dual.Summary
        if ($script:dualInstanceVerdict -ne "PASS") {
            throw "Dual hosted instance isolation failed. reason=$script:dualInstanceVerdictReason summary=$script:dualInstanceSummary"
        }
    }

    @(
        "status=PASS",
        "timestamp=$timestamp",
        "adb=$adb",
        "minimalApk=$minimalApk",
        "gradleLog=$gradleLog",
        "logcat=$logcatFile",
        "runtimeEvidence=$runtimeEvidence",
        "instrumentationXml=$script:instrumentationXml",
        "instrumentationTests=$script:instrumentationTests",
        "instrumentationFailures=$script:instrumentationFailures",
        "instrumentationErrors=$script:instrumentationErrors",
        "instrumentationSkipped=$script:instrumentationSkipped",
        "activityVerdict=$script:activityVerdict",
        "activityVerdictReason=$script:activityVerdictReason",
        "activityInstrumentationStatus=$script:activityInstrumentationStatus",
        "activityContextStatus=$script:activityContextStatus",
        "activityContextInjected=$script:activityContextInjected",
        "activityRemapStatus=$script:activityRemapStatus",
        "providerVerdict=$script:providerVerdict",
        "providerVerdictReason=$script:providerVerdictReason",
        "providerStatus=$script:providerStatus",
        "serviceVerdict=$script:serviceVerdict",
        "serviceVerdictReason=$script:serviceVerdictReason",
        "serviceStatus=$script:serviceStatus",
        "broadcastVerdict=$script:broadcastVerdict",
        "broadcastVerdictReason=$script:broadcastVerdictReason",
        "broadcastStatus=$script:broadcastStatus",
        "storageVerdict=$script:storageVerdict",
        "storageVerdictReason=$script:storageVerdictReason",
        "storageFilesVerdict=$script:storageFilesVerdict",
        "storageFilesVerdictReason=$script:storageFilesVerdictReason",
        "storageDataDir=$script:storageDataDir",
        "storageProbeFileExists=$script:storageProbeFileExists",
        "storageSharedPrefsExists=$script:storageSharedPrefsExists",
        "storageDatabaseExists=$script:storageDatabaseExists",
        "dualInstanceVerdict=$script:dualInstanceVerdict",
        "dualInstanceVerdictReason=$script:dualInstanceVerdictReason",
        "dualInstanceCount=$script:dualInstanceCount",
        "dualInstanceDataRootDifferent=$script:dualInstanceDataRootDifferent",
        "dualInstanceVirtualPackageNameDifferent=$script:dualInstanceVirtualPackageNameDifferent",
        "dualInstanceSummary=$script:dualInstanceSummary",
        "testClass=com.multiapp.app.HostedContainerMinimalBaselineTest"
    ) | Set-Content -Path $summary -Encoding UTF8

    Write-Host "Baseline evidence: $evidenceDir"
    exit 0
} catch {
    try {
        & $adb @adbArgs logcat -d -v time | Tee-Object -FilePath $logcatFile | Out-Null
    } catch {}
    try {
        Capture-HostedEvidence -TargetFile $runtimeEvidence
        $activity = Get-HostedActivityVerdict -EvidencePath $runtimeEvidence
        $script:activityVerdict = $activity.Verdict
        $script:activityVerdictReason = $activity.Reason
        $script:activityInstrumentationStatus = $activity.InstrumentationStatus
        $script:activityContextStatus = $activity.ContextStatus
        $script:activityContextInjected = $activity.ContextInjected
        $script:activityRemapStatus = $activity.RemapStatus
        $provider = Get-HostedComponentVerdict -EvidencePath $runtimeEvidence -Suffix ".provider-proxy.properties" -PassStatuses @("PROVIDER_CREATED", "PROVIDER_CACHED") -MissingReason "provider evidence not exercised"
        $script:providerVerdict = $provider.Verdict
        $script:providerVerdictReason = $provider.Reason
        $script:providerStatus = $provider.Status
        $service = Get-HostedComponentVerdict -EvidencePath $runtimeEvidence -Suffix ".service-proxy.properties" -PassStatuses @("STARTED") -MissingReason "service evidence not exercised"
        $script:serviceVerdict = $service.Verdict
        $script:serviceVerdictReason = $service.Reason
        $script:serviceStatus = $service.Status
        $broadcast = Get-HostedComponentVerdict -EvidencePath $runtimeEvidence -Suffix ".broadcast.properties" -PassStatuses @("Delivered") -MissingReason "broadcast evidence not exercised"
        $script:broadcastVerdict = $broadcast.Verdict
        $script:broadcastVerdictReason = $broadcast.Reason
        $script:broadcastStatus = $broadcast.Status
    } catch {}
    try {
        $storage = Get-StorageVerdictFromLogcat -Path $logcatFile
        $script:storageVerdict = $storage.Verdict
        $script:storageVerdictReason = $storage.Reason
    } catch {}
    try {
        $storageFiles = Get-StorageFileVerdictFromDevice -EvidencePath $runtimeEvidence
        $script:storageFilesVerdict = $storageFiles.Verdict
        $script:storageFilesVerdictReason = $storageFiles.Reason
        $script:storageDataDir = $storageFiles.DataDir
        $script:storageProbeFileExists = $storageFiles.ProbeFileExists.ToString().ToLowerInvariant()
        $script:storageSharedPrefsExists = $storageFiles.SharedPrefsExists.ToString().ToLowerInvariant()
        $script:storageDatabaseExists = $storageFiles.DatabaseExists.ToString().ToLowerInvariant()
    } catch {}
    try {
        $dual = Get-DualInstanceHostedVerdict -EvidencePath $runtimeEvidence
        $script:dualInstanceVerdict = $dual.Verdict
        $script:dualInstanceVerdictReason = $dual.Reason
        $script:dualInstanceCount = $dual.InstanceCount
        $script:dualInstanceDataRootDifferent = $dual.DataRootDifferent.ToString().ToLowerInvariant()
        $script:dualInstanceVirtualPackageNameDifferent = $dual.VirtualPackageNameDifferent.ToString().ToLowerInvariant()
        $script:dualInstanceSummary = $dual.Summary
    } catch {}
    try {
        if (Test-Path -LiteralPath (Join-Path $repo "app\build\outputs\androidTest-results\connected\debug")) {
            $script:instrumentationXml = Copy-InstrumentationResults -TargetDir $evidenceDir
            $instrumentation = Read-InstrumentationResultSummary -XmlPath $script:instrumentationXml
            $script:instrumentationTests = $instrumentation.Tests
            $script:instrumentationFailures = $instrumentation.Failures
            $script:instrumentationErrors = $instrumentation.Errors
            $script:instrumentationSkipped = $instrumentation.Skipped
        }
    } catch {}

    @(
        "status=FAIL",
        "timestamp=$timestamp",
        "adb=$adb",
        "error=$($_.Exception.Message)",
        "gradleLog=$gradleLog",
        "logcat=$logcatFile",
        "runtimeEvidence=$runtimeEvidence",
        "instrumentationXml=$script:instrumentationXml",
        "instrumentationTests=$script:instrumentationTests",
        "instrumentationFailures=$script:instrumentationFailures",
        "instrumentationErrors=$script:instrumentationErrors",
        "instrumentationSkipped=$script:instrumentationSkipped",
        "activityVerdict=$script:activityVerdict",
        "activityVerdictReason=$script:activityVerdictReason",
        "activityInstrumentationStatus=$script:activityInstrumentationStatus",
        "activityContextStatus=$script:activityContextStatus",
        "activityContextInjected=$script:activityContextInjected",
        "activityRemapStatus=$script:activityRemapStatus",
        "providerVerdict=$script:providerVerdict",
        "providerVerdictReason=$script:providerVerdictReason",
        "providerStatus=$script:providerStatus",
        "serviceVerdict=$script:serviceVerdict",
        "serviceVerdictReason=$script:serviceVerdictReason",
        "serviceStatus=$script:serviceStatus",
        "broadcastVerdict=$script:broadcastVerdict",
        "broadcastVerdictReason=$script:broadcastVerdictReason",
        "broadcastStatus=$script:broadcastStatus",
        "storageVerdict=$script:storageVerdict",
        "storageVerdictReason=$script:storageVerdictReason",
        "storageFilesVerdict=$script:storageFilesVerdict",
        "storageFilesVerdictReason=$script:storageFilesVerdictReason",
        "storageDataDir=$script:storageDataDir",
        "storageProbeFileExists=$script:storageProbeFileExists",
        "storageSharedPrefsExists=$script:storageSharedPrefsExists",
        "storageDatabaseExists=$script:storageDatabaseExists",
        "dualInstanceVerdict=$script:dualInstanceVerdict",
        "dualInstanceVerdictReason=$script:dualInstanceVerdictReason",
        "dualInstanceCount=$script:dualInstanceCount",
        "dualInstanceDataRootDifferent=$script:dualInstanceDataRootDifferent",
        "dualInstanceVirtualPackageNameDifferent=$script:dualInstanceVirtualPackageNameDifferent",
        "dualInstanceSummary=$script:dualInstanceSummary",
        "testClass=com.multiapp.app.HostedContainerMinimalBaselineTest"
    ) | Set-Content -Path $summary -Encoding UTF8

    Write-Error $_
    exit 1
}
