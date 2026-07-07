param(
    [string]$Serial = "192.168.2.42:10001",
    [int]$UserId = 10,
    [string]$PackageName = "com.qq.reader",
    [string]$Component = "com.qq.reader/com.qq.reader.activity.launch.DefaultAliasSplashActivity",
    [string]$CaptureJson = ".tmp\root-rn-capture-original\ywlogin-register-table.json",
    [int]$WaitSeconds = 25,
    [string]$VersionTag = "root-ywlogin-register-replay",
    [string]$FridaExe = "",
    [string]$FridaHost = "127.0.0.1:27042",
    [string]$FridaServerLocal = "",
    [string]$FridaServerRemote = "/data/local/tmp/frida-server",
    [switch]$StartServer,
    [switch]$NoForward,
    [switch]$DryRun,
    [Alias("Pid")]
    [int]$TargetPid = 0
)

$ErrorActionPreference = "Stop"

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$adb = "C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe"
if (!(Test-Path -LiteralPath $adb)) {
    $adb = "adb"
}

if (!$FridaExe) {
    $candidates = @(
        "$env:APPDATA\Python\Python312\Scripts\frida.exe",
        "$env:USERPROFILE\AppData\Roaming\Python\Python312\Scripts\frida.exe",
        "F:\Anaconda3\envs\frida\Scripts\frida.exe",
        "$env:USERPROFILE\Anaconda3\envs\frida\Scripts\frida.exe",
        "$env:USERPROFILE\miniconda3\envs\frida\Scripts\frida.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            $FridaExe = (Resolve-Path -LiteralPath $candidate).Path
            break
        }
    }
    if (!$FridaExe) {
        $cmd = Get-Command frida -ErrorAction SilentlyContinue
        if ($cmd) { $FridaExe = $cmd.Source }
    }
}
if ((!$FridaExe -or !(Test-Path -LiteralPath $FridaExe)) -and !$DryRun) {
    throw "frida.exe not found. Pass -FridaExe."
}

function Resolve-InputPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return (Resolve-Path -LiteralPath $PathValue).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $repo $PathValue)).Path
}

$capturePath = Resolve-InputPath $CaptureJson
$template = Join-Path $repo "tools\root-dump\frida_ywlogin_register_replay.js"
if (!(Test-Path -LiteralPath $template)) {
    throw "Missing $template."
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

function Quote-Arg {
    param([string]$Value)
    if ($Value -match '[\s"]') {
        return '"' + $Value.Replace('"', '\"') + '"'
    }
    return $Value
}

$tableJson = Get-Content -LiteralPath $capturePath -Raw
$validationText = Join-Path $outDir "ywlogin-register-table-validation.txt"
python (Join-Path $repo "tools\root-dump\validate_ywlogin_register_table.py") $capturePath --strict-startup |
    Set-Content -LiteralPath $validationText -Encoding UTF8
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath $validationText -ErrorAction SilentlyContinue | Write-Host
    throw "Captured table is not replayable for startup. See $validationText"
}
$generated = Join-Path $outDir "frida_ywlogin_register_replay.generated.js"
$source = Get-Content -LiteralPath $template -Raw
$source = $source.Replace("JSON.parse('[]')", $tableJson)
$source | Set-Content -LiteralPath $generated -Encoding UTF8

$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) {
    & $node.Source --check $generated *> (Join-Path $outDir "generated-node-check.txt")
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath (Join-Path $outDir "generated-node-check.txt") -ErrorAction SilentlyContinue | Write-Host
        throw "Generated replay script failed node --check. See generated-node-check.txt"
    }
}

if ($DryRun) {
    Write-Host "dryRun=1 wrote=$outDir"
    Get-Content -LiteralPath $validationText
    if (Test-Path -LiteralPath (Join-Path $outDir "generated-node-check.txt")) {
        Get-Content -LiteralPath (Join-Path $outDir "generated-node-check.txt")
    }
    return
}

Invoke-Adb get-state | Set-Content -LiteralPath (Join-Path $outDir "adb-state.txt") -Encoding ASCII

if ($StartServer) {
    if ($FridaServerLocal) {
        if (!(Test-Path -LiteralPath $FridaServerLocal)) {
            throw "Frida server not found: $FridaServerLocal"
        }
        Invoke-Adb push $FridaServerLocal $FridaServerRemote |
            Set-Content -LiteralPath (Join-Path $outDir "push-frida-server.txt") -Encoding UTF8
    }
    Shell "su -c 'chmod 755 $FridaServerRemote; for p in `$(pidof frida-server 2>/dev/null); do kill -9 `$p 2>/dev/null || true; done; nohup $FridaServerRemote >/data/local/tmp/frida-server.log 2>&1 &'" |
        Set-Content -LiteralPath (Join-Path $outDir "start-frida-server.txt") -Encoding UTF8
    Start-Sleep -Seconds 1
}

if (!$NoForward) {
    Invoke-Adb forward tcp:27042 tcp:27042 |
        Set-Content -LiteralPath (Join-Path $outDir "adb-forward-27042.txt") -Encoding UTF8
    Invoke-Adb forward tcp:52736 tcp:52736 |
        Set-Content -LiteralPath (Join-Path $outDir "adb-forward-52736.txt") -Encoding UTF8
}

Shell "logcat -c 2>/dev/null || true" |
    Set-Content -LiteralPath (Join-Path $outDir "logcat-clear.txt") -Encoding UTF8

if ($TargetPid -le 0) {
    Shell "am force-stop --user $UserId $PackageName 2>/dev/null || true; am start --user $UserId -n $Component" |
        Set-Content -LiteralPath (Join-Path $outDir "app-start.txt") -Encoding UTF8
    Start-Sleep -Milliseconds 350
    $pidLine = (Shell "pidof $PackageName 2>/dev/null | awk '{print `$1}'" | Select-Object -First 1)
    if (!$pidLine) {
        throw "pidof $PackageName returned empty"
    }
    $TargetPid = [int]($pidLine.Trim())
}

$fridaArgs = @()
if ($FridaHost) {
    $fridaArgs += @("-H", $FridaHost)
} else {
    $fridaArgs += "-U"
}
$fridaArgs += @("-p", "$TargetPid", "-l", $generated)

$argLine = ($fridaArgs | ForEach-Object { Quote-Arg $_ }) -join " "
$argLine | Set-Content -LiteralPath (Join-Path $outDir "frida-args.txt") -Encoding UTF8

$stdout = Join-Path $outDir "frida-stdout.txt"
$stderr = Join-Path $outDir "frida-stderr.txt"
$process = Start-Process -FilePath $FridaExe -ArgumentList $argLine -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr

Start-Sleep -Seconds $WaitSeconds

if (!$process.HasExited) {
    Stop-Process -Id $process.Id -Force
    Start-Sleep -Milliseconds 300
}

Invoke-Adb shell logcat -d |
    Set-Content -LiteralPath (Join-Path $outDir "logcat.txt") -Encoding UTF8

Write-Host "wrote=$outDir"
Get-Content -LiteralPath $stdout -ErrorAction SilentlyContinue |
    Select-String -Pattern "RN_REPLAY", "Error", "Failed", "No implementation found" -SimpleMatch
