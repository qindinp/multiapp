param(
    [Parameter(Mandatory = $true)]
    [string]$InputCloneApk,

    [string]$OutputApk = ".tmp\qqreader-neutralized-signed.apk",

    [string]$WorkDir = ".tmp\qqreader-offline-patch",

    [string]$Keystore = "$env:USERPROFILE\.android\debug.keystore",

    [string]$StorePass = "android",

    [string]$KeyPass = "android",

    [string]$KeyAlias = "androiddebugkey",

    [switch]$SkipVerify,

    [switch]$KeepWork,

    [string]$NativeLibDir = "",

    [string]$LoaderDex = "",

    [string]$StubPackage = "com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8",

    [switch]$PatchFockSign
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path $RepoRoot $Path
}

function Find-FirstFile([string[]]$Roots, [string]$Filter) {
    foreach ($root in $Roots) {
        if (-not (Test-Path $root)) {
            continue
        }
        $match = Get-ChildItem -Path $root -Recurse -Filter $Filter -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($match) {
            return $match.FullName
        }
    }
    throw "Cannot find $Filter under: $($Roots -join ', ')"
}

function Remove-ZipEntries([System.IO.Compression.ZipArchive]$Zip, [scriptblock]$Predicate) {
    $entries = @($Zip.Entries)
    foreach ($entry in $entries) {
        if (& $Predicate $entry) {
            $entry.Delete()
        }
    }
}

function Replace-ZipEntry(
    [string]$ZipPath,
    [string]$EntryName,
    [string]$SourcePath,
    [System.IO.Compression.CompressionLevel]$CompressionLevel = [System.IO.Compression.CompressionLevel]::Optimal
) {
    $zip = [System.IO.Compression.ZipFile]::Open($ZipPath, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $old = $zip.GetEntry($EntryName)
        if ($old) {
            $old.Delete()
        }
        $newEntry = $zip.CreateEntry($EntryName, $CompressionLevel)
        $inStream = [System.IO.File]::OpenRead($SourcePath)
        try {
            $outStream = $newEntry.Open()
            try {
                $inStream.CopyTo($outStream)
            } finally {
                $outStream.Dispose()
            }
        } finally {
            $inStream.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
}

function Extract-ZipEntry([string]$ZipPath, [string]$EntryName, [string]$OutputPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entry = $zip.GetEntry($EntryName)
        if (-not $entry) {
            throw "Missing $EntryName in $ZipPath"
        }
        $parent = Split-Path -Parent $OutputPath
        New-Item -ItemType Directory -Force $parent | Out-Null
        $inStream = $entry.Open()
        try {
            $outStream = [System.IO.File]::Create($OutputPath)
            try {
                $inStream.CopyTo($outStream)
            } finally {
                $outStream.Dispose()
            }
        } finally {
            $inStream.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$InputCloneApk = Resolve-RepoPath $InputCloneApk
$OutputApk = Resolve-RepoPath $OutputApk
$WorkDir = Resolve-RepoPath $WorkDir

if (-not (Test-Path $InputCloneApk)) {
    throw "Input clone APK not found: $InputCloneApk"
}
if (-not (Test-Path $Keystore)) {
    throw "Keystore not found: $Keystore"
}

$ResolvedLoaderDex = ""
if ($LoaderDex -ne "") {
    $ResolvedLoaderDex = Resolve-RepoPath $LoaderDex
    if (-not (Test-Path $ResolvedLoaderDex)) {
        throw "LoaderDex not found: $ResolvedLoaderDex"
    }
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$gradleCache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"
$dexlib = Find-FirstFile @((Join-Path $gradleCache "org.smali\dexlib2")) "dexlib2-*.jar"
$guava = Find-FirstFile @((Join-Path $gradleCache "com.google.guava\guava")) "guava-*.jar"

$buildToolRoots = @(
    "C:\Users\Administrator\.openclaw\workspace\apk_analysis\build-tools",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools")
)
$zipalign = Find-FirstFile $buildToolRoots "zipalign.exe"
$apksigner = Find-FirstFile $buildToolRoots "apksigner.bat"

if (Test-Path $WorkDir) {
    Remove-Item -LiteralPath $WorkDir -Recurse -Force
}
New-Item -ItemType Directory -Force $WorkDir | Out-Null

$classesDir = Join-Path $WorkDir "classes"
$dexDir = Join-Path $WorkDir "origin-dex"
$outerPatchedDexDir = Join-Path $WorkDir "outer-patched-dex"
$inspectDir = Join-Path $WorkDir "inspect"
New-Item -ItemType Directory -Force $classesDir, $dexDir, $outerPatchedDexDir, $inspectDir | Out-Null

$originApk = Join-Path $WorkDir "origin.apk"
$patchedOriginApk = Join-Path $WorkDir "origin-patched.apk"
$outerUnsigned = Join-Path $WorkDir "clone-unsigned.apk"
$outerAligned = Join-Path $WorkDir "clone-aligned.apk"
$originCertBlock = Join-Path $WorkDir "origin-cert.block"

Write-Host "Input clone: $InputCloneApk"
Write-Host "Work dir:    $WorkDir"
Write-Host "Output APK:  $OutputApk"

Extract-ZipEntry $InputCloneApk "assets/origin.apk" $originApk
Copy-Item -LiteralPath $originApk -Destination $patchedOriginApk -Force

$originZip = [System.IO.Compression.ZipFile]::Open($patchedOriginApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $certEntry = @($originZip.Entries) |
        Where-Object { $_.FullName -match "^META-INF/.*\.(RSA|DSA|EC)$" } |
        Select-Object -First 1
    if ($certEntry) {
        $stream = $certEntry.Open()
        try {
            $out = [System.IO.File]::Create($originCertBlock)
            try {
                $stream.CopyTo($out)
            } finally {
                $out.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
        Write-Host "Preserved origin cert block: $($certEntry.FullName)"
    } else {
        Write-Warning "No origin META-INF certificate block found"
    }

    Remove-ZipEntries $originZip { param($entry) $entry.FullName -like "META-INF/*" }
    foreach ($entry in @($originZip.Entries)) {
        if ($entry.FullName -match "^classes[0-9]*\.dex$") {
            $dexPath = Join-Path $dexDir $entry.FullName
            $stream = $entry.Open()
            try {
                $out = [System.IO.File]::Create($dexPath)
                try {
                    $stream.CopyTo($out)
                } finally {
                    $out.Dispose()
                }
            } finally {
                $stream.Dispose()
            }
        }
    }
} finally {
    $originZip.Dispose()
}

$javaSources = @(
    (Join-Path $PSScriptRoot "NeutralizeDex.java"),
    (Join-Path $PSScriptRoot "InspectDexMethods.java")
)
$classpath = "$dexlib;$guava"
& javac -cp $classpath -d $classesDir $javaSources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed: $LASTEXITCODE"
}

$dexFiles = Get-ChildItem -Path $dexDir -Filter "classes*.dex" | Sort-Object Name
if (-not $dexFiles) {
    throw "No classes*.dex found in origin.apk"
}

$neutralizeJvmArgs = @()
if ($ResolvedLoaderDex -ne "") {
    $neutralizeJvmArgs += "-Dmultiapp.signCompatDex=$ResolvedLoaderDex"
}
if ($PatchFockSign) {
    $neutralizeJvmArgs += "-Dmultiapp.patchFockSign=true"
}
if ($StubPackage -ne "") {
    $neutralizeJvmArgs += "-Dmultiapp.stubPackage=$StubPackage"
}
& java @neutralizeJvmArgs -cp "$classesDir;$classpath" NeutralizeDex @($dexFiles.FullName)
if ($LASTEXITCODE -ne 0) {
    throw "NeutralizeDex failed: $LASTEXITCODE"
}

foreach ($dexFile in $dexFiles) {
    Replace-ZipEntry $patchedOriginApk $dexFile.Name $dexFile.FullName
}
if (Test-Path $originCertBlock) {
    Replace-ZipEntry $patchedOriginApk "assets/multiapp_origin_cert.RSA" $originCertBlock
}

if (-not $SkipVerify) {
    & java -cp "$classesDir;$classpath" InspectDexMethods $patchedOriginApk $inspectDir
    if ($LASTEXITCODE -ne 0) {
        throw "InspectDexMethods failed: $LASTEXITCODE"
    }
}

Copy-Item -LiteralPath $InputCloneApk -Destination $outerUnsigned -Force
$outerZip = [System.IO.Compression.ZipFile]::Open($outerUnsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    Remove-ZipEntries $outerZip { param($entry) $entry.FullName -like "META-INF/*" }
} finally {
    $outerZip.Dispose()
}
Replace-ZipEntry $outerUnsigned "assets/origin.apk" $patchedOriginApk

$outerPatchedDexEntries = @()
$outerZip = [System.IO.Compression.ZipFile]::OpenRead($outerUnsigned)
try {
    foreach ($entry in @($outerZip.Entries)) {
        if ($entry.FullName -match "^assets/patched/classes[0-9]*\.dex$") {
            $fileName = $entry.FullName.Replace("/", "_")
            $dexPath = Join-Path $outerPatchedDexDir $fileName
            $stream = $entry.Open()
            try {
                $out = [System.IO.File]::Create($dexPath)
                try {
                    $stream.CopyTo($out)
                } finally {
                    $out.Dispose()
                }
            } finally {
                $stream.Dispose()
            }
            $outerPatchedDexEntries += [pscustomobject]@{
                EntryName = $entry.FullName
                Path = $dexPath
            }
        }
    }
} finally {
    $outerZip.Dispose()
}

if ($outerPatchedDexEntries.Count -gt 0) {
    Write-Host "Patch outer assets/patched dex count: $($outerPatchedDexEntries.Count)"
    & java @neutralizeJvmArgs -cp "$classesDir;$classpath" NeutralizeDex @($outerPatchedDexEntries.Path)
    if ($LASTEXITCODE -ne 0) {
        throw "NeutralizeDex outer patched dex failed: $LASTEXITCODE"
    }

    foreach ($item in $outerPatchedDexEntries) {
        Replace-ZipEntry $outerUnsigned $item.EntryName $item.Path
    }
}

if ($LoaderDex -ne "") {
    $LoaderDex = $ResolvedLoaderDex
    Write-Host "Inject loader dex: classes.dex <- $LoaderDex"
    Replace-ZipEntry $outerUnsigned "classes.dex" $LoaderDex
    Write-Host "Inject loader dex: assets/loader.dex <- $LoaderDex"
    Replace-ZipEntry $outerUnsigned "assets/loader.dex" $LoaderDex
}

if ($NativeLibDir -ne "") {
    $NativeLibDir = Resolve-RepoPath $NativeLibDir
    if (-not (Test-Path $NativeLibDir)) {
        throw "NativeLibDir not found: $NativeLibDir"
    }

    $nativeEntries = @()
    $outerZip = [System.IO.Compression.ZipFile]::OpenRead($outerUnsigned)
    try {
        $nativeEntries = @(
            $outerZip.Entries |
                Where-Object { $_.FullName -match "^lib/[^/]+/(libmultiapp-native|liblsplant)\.so$" } |
                Select-Object -ExpandProperty FullName
        )
    } finally {
        $outerZip.Dispose()
    }

    foreach ($entryName in $nativeEntries) {
        $abi = ($entryName -split "/")[1]
        $libName = Split-Path $entryName -Leaf
        $nativeLib = Join-Path $NativeLibDir (Join-Path $abi $libName)
        if (Test-Path $nativeLib) {
            Write-Host "Inject native lib: $entryName <- $nativeLib"
            Replace-ZipEntry $outerUnsigned $entryName $nativeLib ([System.IO.Compression.CompressionLevel]::NoCompression)
        } else {
            Write-Warning "Missing native lib for $abi under $NativeLibDir"
        }
    }
}

foreach ($path in @($outerAligned, $OutputApk, "$OutputApk.idsig")) {
    if (Test-Path $path) {
        Remove-Item -LiteralPath $path -Force
    }
}

& $zipalign -p -f 4 $outerUnsigned $outerAligned
if ($LASTEXITCODE -ne 0) {
    throw "zipalign failed: $LASTEXITCODE"
}

& $apksigner sign `
    --ks $Keystore `
    --ks-pass "pass:$StorePass" `
    --key-pass "pass:$KeyPass" `
    --ks-key-alias $KeyAlias `
    --out $OutputApk `
    $outerAligned
if ($LASTEXITCODE -ne 0) {
    throw "apksigner failed: $LASTEXITCODE"
}

Write-Host "Signed APK written: $OutputApk"

if (-not $KeepWork) {
    Remove-Item -LiteralPath $WorkDir -Recurse -Force
}
