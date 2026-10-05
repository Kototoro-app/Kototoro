[CmdletBinding(DefaultParameterSetName = "Source")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Download")]
    [string] $CompatibilityUrl,
    [Parameter(Mandatory = $true, ParameterSetName = "Local")]
    [string] $CompatibilityArchive,
    [Parameter(ParameterSetName = "Source")]
    [string] $SourceJavaHome = $env:JAVA_HOME_25_X64
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskRoot = (Resolve-Path -LiteralPath "$PSScriptRoot/../..").Path
$taskBuildRoot = Join-Path "$taskRoot" "build"
$taskDownloadRoot = Join-Path "$taskBuildRoot" "windows-ci-downloads"
New-Item -ItemType Directory -Path "$taskDownloadRoot" -Force | Out-Null

function Get-Download([string] $Url, [string] $Destination, [string] $Sha256 = "") {
    if (!(Test-Path -LiteralPath "$Destination") -or !$Sha256 -or
        (Get-FileHash -LiteralPath "$Destination" -Algorithm SHA256).Hash -ne $Sha256) {
        if (([uri] $Url).Scheme -ne "https") { throw "Build input downloads require HTTPS." }
        & curl.exe --fail --location --retry 3 --connect-timeout 20 --max-time 300 --silent --show-error `
            --output "$Destination" "$Url"
        if ($LASTEXITCODE -ne 0) { throw "Build input download failed." }
    }
    if ($Sha256 -and (Get-FileHash -LiteralPath "$Destination" -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Build input SHA-256 mismatch: $([IO.Path]::GetFileName($Destination))"
    }
}

function Copy-ZipEntry($Entry, [string] $Destination) {
    $taskInput = $Entry.Open()
    try {
        $taskOutput = [IO.File]::Create($Destination)
        try { $taskInput.CopyTo($taskOutput) } finally { $taskOutput.Dispose() }
    } finally { $taskInput.Dispose() }
}

if ($PSCmdlet.ParameterSetName -eq "Source") {
    if (!$SourceJavaHome -or !(Test-Path -LiteralPath "$SourceJavaHome/bin/java.exe")) {
        throw "Source bootstrap requires JDK 25; pass -SourceJavaHome or set JAVA_HOME_25_X64."
    }
    $taskSourceRevision = "eb2dc0b19a9571b27c02bebc5c883e404b7bd7fb"
    $taskPatchRevision = "b86cead7dd1387b09c71e7f4b15c1e020463e422"
    $taskResources = @{
        "patches/suwayomi-ios-runtime.patch" = "ec18dcb53cfe7cd5c3b1bcaac665e5a8b76755a2911d9bdccf1d2197429788d0"
        "gradle/copy-suwayomi-runtime.init.gradle" = "fe3efd267a55adb796599b49e6ea92f2efe76da960ca955af00e80873559ee02"
    }
    foreach ($taskResource in $taskResources.Keys) {
        $taskFile = Join-Path "$taskDownloadRoot" ([IO.Path]::GetFileName($taskResource))
        if (!(Test-Path -LiteralPath "$taskFile") -or
            (Get-FileHash -LiteralPath "$taskFile" -Algorithm SHA256).Hash -ne $taskResources[$taskResource]) {
            $taskApiFile = "$taskFile.json"
            Get-Download "https://api.github.com/repos/az4521/TachiyomiAzIOS/contents/Scripts/${taskResource}?ref=$taskPatchRevision" "$taskApiFile"
            $taskResponse = Get-Content -LiteralPath "$taskApiFile" -Raw | ConvertFrom-Json
            [IO.File]::WriteAllBytes($taskFile, [Convert]::FromBase64String($taskResponse.content))
        }
        if ((Get-FileHash -LiteralPath "$taskFile" -Algorithm SHA256).Hash -ne $taskResources[$taskResource]) {
            throw "Pinned upstream resource SHA-256 mismatch: $taskResource"
        }
    }
    # Build in a fresh directory: never reset or clean an existing checkout.
    $taskSourceRoot = Join-Path "$taskBuildRoot" "windows-ci-suwayomi-$([guid]::NewGuid().ToString('N'))"
    & git init --quiet "$taskSourceRoot"
    if ($LASTEXITCODE -ne 0) { throw "Cannot initialize runtime source checkout." }
    & git -C "$taskSourceRoot" -c core.autocrlf=false fetch --quiet --depth=1 `
        "https://github.com/Suwayomi/Suwayomi-Server.git" "$taskSourceRevision"
    if ($LASTEXITCODE -ne 0) { throw "Cannot fetch pinned Suwayomi source." }
    & git -C "$taskSourceRoot" -c core.autocrlf=false checkout --quiet --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw "Cannot check out pinned Suwayomi source." }
    & git -C "$taskSourceRoot" apply "$taskDownloadRoot/suwayomi-ios-runtime.patch"
    if ($LASTEXITCODE -ne 0) { throw "Cannot apply pinned runtime patch." }
    # Preserve the original compatibility artifact's metadata so its existing SHA-256 pin remains reproducible.
    $taskServerBuildFile = Join-Path "$taskSourceRoot" "server/build.gradle.kts"
    $taskServerBuild = [IO.File]::ReadAllText($taskServerBuildFile)
    $taskTimeExpression = 'Instant.now().epochSecond.toString()'
    if ($taskServerBuild.Split(@($taskTimeExpression), [StringSplitOptions]::None).Count -ne 2) {
        throw "Upstream build timestamp expression changed."
    }
    [IO.File]::WriteAllText($taskServerBuildFile, $taskServerBuild.Replace($taskTimeExpression, '"1790898154"'),
        [Text.UTF8Encoding]::new($false))
    $taskRuntimeRoot = Join-Path "$taskSourceRoot" "runtime-output"
    $taskPreviousJavaHome = $env:JAVA_HOME
    $taskPreviousBuildType = $env:ProductBuildType
    Push-Location "$taskSourceRoot"
    try {
        $env:JAVA_HOME = $SourceJavaHome
        $env:ProductBuildType = "Preview"
        & "./gradlew.bat" :server:copyTachiazRuntime --no-daemon --max-workers=2 --console=plain `
            "-Dorg.gradle.jvmargs=-Xmx4g" "-Pkotlin.daemon.jvmargs=-Xmx4g" `
            --init-script "$taskDownloadRoot/copy-suwayomi-runtime.init.gradle" "-PtachiazRuntimeOutput=$taskRuntimeRoot"
        if ($LASTEXITCODE -ne 0) { throw "Pinned compatibility runtime build failed." }
    } finally {
        $env:JAVA_HOME = $taskPreviousJavaHome
        $env:ProductBuildType = $taskPreviousBuildType
        Pop-Location
    }
} elseif ($PSCmdlet.ParameterSetName -eq "Download") {
    $CompatibilityArchive = Join-Path "$taskDownloadRoot" "compatibility.zip"
    Get-Download "$CompatibilityUrl" "$CompatibilityArchive"
}
$taskPins = @{}
Get-Content -LiteralPath "$taskRoot/mihon-desktop-compat/windows-runtime-pins.properties" | ForEach-Object {
    if ($_ -match '^([^#=]+\.jar)=([a-fA-F0-9]{64})$') { $taskPins[$Matches[1]] = $Matches[2] }
}
if ($taskPins.Count -eq 0) { throw "Windows runtime pin list is empty." }
$taskCompatibilityRoot = Join-Path "$taskBuildRoot" "windows-ci-compatibility"
New-Item -ItemType Directory -Path "$taskCompatibilityRoot" -Force | Out-Null
$taskZip = if ($CompatibilityArchive) {
    [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath "$CompatibilityArchive").Path)
} else { $null }
try {
    foreach ($taskName in $taskPins.Keys) {
        # Only copy pinned JAR basenames, including bundles with a containing directory. Never extract arbitrary paths.
        $taskJar = Join-Path "$taskCompatibilityRoot" "$taskName"
        if ($taskZip) {
            $taskEntries = @($taskZip.Entries | Where-Object { $_.Name -ceq $taskName })
            if ($taskEntries.Count -ne 1) { throw "Runtime archive must contain exactly one $taskName." }
            Copy-ZipEntry $taskEntries[0] "$taskJar"
        } else {
            Copy-Item -LiteralPath "$taskRuntimeRoot/$taskName" -Destination "$taskJar"
        }
        if ((Get-FileHash -LiteralPath "$taskJar" -Algorithm SHA256).Hash -ne $taskPins[$taskName]) {
            throw "Windows runtime SHA-256 mismatch: $taskName"
        }
    }
} finally { if ($taskZip) { $taskZip.Dispose() } }
Write-Output "Verified $($taskPins.Count) Windows runtime JARs."

$taskSdkVersion = "1.0.4258.31"
$taskSdkArchive = Join-Path "$taskDownloadRoot" "microsoft.web.webview2.$taskSdkVersion.nupkg"
Get-Download "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$taskSdkVersion/microsoft.web.webview2.$taskSdkVersion.nupkg" `
    "$taskSdkArchive" "56f7f4b8bf9aee4b8efefbbdd4f67d5f74ebd1b100ed0806da71bf76af481aa9"
$taskSdkRoot = Join-Path "$taskBuildRoot" "webview2-sdk/$taskSdkVersion/extracted"
New-Item -ItemType Directory -Path "$taskSdkRoot" -Force | Out-Null
$taskSdkEntries = @{
    "Microsoft.Web.WebView2.Core.dll" = "lib/net462/Microsoft.Web.WebView2.Core.dll"
    "Microsoft.Web.WebView2.WinForms.dll" = "lib/net462/Microsoft.Web.WebView2.WinForms.dll"
    "WebView2Loader.dll" = "runtimes/win-x64/native/WebView2Loader.dll"
}
$taskZip = [IO.Compression.ZipFile]::OpenRead($taskSdkArchive)
try {
    foreach ($taskName in $taskSdkEntries.Keys) {
        $taskEntry = $taskZip.GetEntry($taskSdkEntries[$taskName])
        if ($null -eq $taskEntry) { throw "WebView2 SDK is missing $taskName." }
        Copy-ZipEntry $taskEntry (Join-Path "$taskSdkRoot" "$taskName")
    }
} finally { $taskZip.Dispose() }
