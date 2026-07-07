param(
    [string]$Serial = "192.168.2.42:10001",
    [string]$PackageName = "com.qq.reader",
    [string]$Component = "com.qq.reader/com.qq.reader.activity.launch.DefaultAliasSplashActivity",
    [string]$UserName = "qqreader_clone",
    [switch]$CreateUser,
    [switch]$SwitchUser,
    [switch]$StartApp
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

function Shell {
    param([string]$Command)
    Invoke-Adb shell $Command
}

function Find-UserId {
    param([string]$Name)
    $users = Shell "pm list users"
    foreach ($line in $users) {
        if ($line -match "UserInfo\{(\d+):$([regex]::Escape($Name))[:}]") {
            return $Matches[1]
        }
    }
    return $null
}

$userId = Find-UserId $UserName
if (!$userId -and $CreateUser) {
    $created = Shell "pm create-user $UserName"
    $created | Write-Host
    if (($created -join "`n") -match "id (\d+)") {
        $userId = $Matches[1]
    } else {
        $userId = Find-UserId $UserName
    }
}

if (!$userId) {
    throw "User '$UserName' not found. Re-run with -CreateUser."
}

Shell "cmd package install-existing --user $userId $PackageName" | Write-Host

if ($SwitchUser) {
    Shell "am start-user $userId" | Write-Host
    Shell "am switch-user $userId" | Write-Host
}

if ($StartApp) {
    Shell "am force-stop $PackageName" | Write-Host
    Start-Sleep -Milliseconds 300
    Shell "am start --user $userId -n $Component" | Write-Host
}

Write-Host "userId=$userId package=$PackageName component=$Component"
