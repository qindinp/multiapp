param(
    [string]$VersionTag = "v120-resume",

    [string]$InputCloneApk = ".tmp\qqreader-c9f8-current-base.apk",

    [string]$OutputApk = "",

    [switch]$Build,

    [string]$ReusePayloadTag = "",

    [switch]$ForceExtract,

    [switch]$ForceRepack,

    [switch]$SkipVerify,

    [switch]$PatchFockSign = $true,

    [string]$StubPackage = "com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $RepoRoot $Path
}

function Assert-File([string]$Path, [string]$Name) {
    if (-not (Test-Path $Path -PathType Leaf)) {
        throw "$Name not found: $Path"
    }
}

function Assert-Dir([string]$Path, [string]$Name) {
    if (-not (Test-Path $Path -PathType Container)) {
        throw "$Name not found: $Path"
    }
}

function Expand-ApkIfNeeded([string]$Apk, [string]$Destination, [switch]$Force) {
    $loaderDex = Join-Path $Destination "assets\loader.dex"
    $arm64Native = Join-Path $Destination "lib\arm64-v8a\libmultiapp-native.so"
    $armNative = Join-Path $Destination "lib\armeabi-v7a\libmultiapp-native.so"

    if ((-not $Force) -and
        (Test-Path $loaderDex -PathType Leaf) -and
        (Test-Path $arm64Native -PathType Leaf) -and
        (Test-Path $armNative -PathType Leaf)) {
        Write-Host "Reuse extracted app payload: $Destination"
        return
    }

    if (Test-Path $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force
    }
    New-Item -ItemType Directory -Force $Destination | Out-Null

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Write-Host "Extract app payload: $Apk -> $Destination"
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Apk, $Destination)

    Assert-File $loaderDex "Extracted loader.dex"
    Assert-File $arm64Native "Extracted arm64 libmultiapp-native.so"
    Assert-File $armNative "Extracted armeabi-v7a libmultiapp-native.so"
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$InputCloneApk = Resolve-RepoPath $InputCloneApk
if ($OutputApk -eq "") {
    $OutputApk = ".tmp\qqreader-c9f8-neutralized-$VersionTag-signed.apk"
}
$OutputApk = Resolve-RepoPath $OutputApk

$appDebugApk = Resolve-RepoPath "app\build\outputs\apk\debug\app-debug.apk"
if ($ReusePayloadTag -ne "") {
    $extractDir = Resolve-RepoPath ".tmp\app-debug-extract-$ReusePayloadTag"
} else {
    $extractDir = Resolve-RepoPath ".tmp\app-debug-extract-$VersionTag"
}
$patchWorkDir = Resolve-RepoPath ".tmp\qqreader-offline-patch-$VersionTag"
$patchScript = Resolve-RepoPath "tools\qqreader-offline-patch\patch-qqreader-clone.ps1"

Assert-File $InputCloneApk "Input clone APK"
Assert-File $patchScript "Offline patch script"

if ((Test-Path $OutputApk -PathType Leaf) -and -not $ForceRepack -and -not $Build -and -not $ForceExtract) {
    $apkInfo = Get-Item $OutputApk
    Write-Host "Reuse signed QQ Reader clone APK: $($apkInfo.FullName) ($($apkInfo.Length) bytes, $($apkInfo.LastWriteTime))"
    Write-Host "Use -ForceRepack to rebuild this APK, or -Build -ForceExtract -ForceRepack after runtime code changes."
    exit 0
}

if ($Build -or -not (Test-Path $appDebugApk -PathType Leaf)) {
    Write-Host "Build requested or app-debug.apk missing; running Gradle."
    & (Resolve-RepoPath "gradlew.bat") --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed: $LASTEXITCODE"
    }
} else {
    Write-Host "Reuse app-debug.apk: $appDebugApk"
}

Assert-File $appDebugApk "app-debug.apk"
Expand-ApkIfNeeded $appDebugApk $extractDir -Force:$ForceExtract

$loaderDex = Join-Path $extractDir "assets\loader.dex"
$nativeLibDir = Join-Path $extractDir "lib"
Assert-File $loaderDex "loader.dex"
Assert-Dir $nativeLibDir "native lib dir"

if ((Test-Path $OutputApk -PathType Leaf) -and -not $ForceRepack) {
    $apkInfo = Get-Item $OutputApk
    Write-Host "Reuse signed QQ Reader clone APK: $($apkInfo.FullName) ($($apkInfo.Length) bytes, $($apkInfo.LastWriteTime))"
    Write-Host "Use -ForceRepack to rebuild this APK, or -Build -ForceExtract -ForceRepack after runtime code changes."
    exit 0
}

Write-Host "Repack QQ Reader offline clone: $OutputApk"
if ($SkipVerify -and $PatchFockSign) {
    & $patchScript -InputCloneApk $InputCloneApk -OutputApk $OutputApk -WorkDir $patchWorkDir -LoaderDex $loaderDex -NativeLibDir $nativeLibDir -StubPackage $StubPackage -SkipVerify -PatchFockSign
} elseif ($SkipVerify) {
    & $patchScript -InputCloneApk $InputCloneApk -OutputApk $OutputApk -WorkDir $patchWorkDir -LoaderDex $loaderDex -NativeLibDir $nativeLibDir -StubPackage $StubPackage -SkipVerify
} elseif ($PatchFockSign) {
    & $patchScript -InputCloneApk $InputCloneApk -OutputApk $OutputApk -WorkDir $patchWorkDir -LoaderDex $loaderDex -NativeLibDir $nativeLibDir -StubPackage $StubPackage -PatchFockSign
} else {
    & $patchScript -InputCloneApk $InputCloneApk -OutputApk $OutputApk -WorkDir $patchWorkDir -LoaderDex $loaderDex -NativeLibDir $nativeLibDir -StubPackage $StubPackage
}
if ($LASTEXITCODE -ne 0) {
    throw "Offline repack failed: $LASTEXITCODE"
}

Assert-File $OutputApk "Output APK"
$apk = Get-Item $OutputApk
Write-Host "Done: $($apk.FullName) ($($apk.Length) bytes, $($apk.LastWriteTime))"
