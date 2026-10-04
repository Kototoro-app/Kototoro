[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $MsiPath,
    [Parameter(Mandatory = $true)] [string] $ImageDirectory,
    [Parameter(Mandatory = $true)] [string] $WixDirectory,
    [Parameter(Mandatory = $true)] [string] $OutputDirectory
)

$ErrorActionPreference = "Stop"
function Get-ArtifactHash([string] $Path) {
    $taskStream = [System.IO.File]::OpenRead($Path)
    $taskDigest = [System.Security.Cryptography.SHA256]::Create()
    try { return [System.BitConverter]::ToString($taskDigest.ComputeHash($taskStream)) }
    finally { $taskDigest.Dispose(); $taskStream.Dispose() }
}
$taskMsiPath = (Resolve-Path -LiteralPath "$MsiPath").Path
$taskImageRoot = (Resolve-Path -LiteralPath "$ImageDirectory").Path
$taskDark = (Resolve-Path -LiteralPath (Join-Path "$WixDirectory" "dark.exe")).Path
$taskOutputRoot = [System.IO.Path]::GetFullPath("$OutputDirectory")
New-Item -ItemType Directory -Path "$taskOutputRoot" -Force | Out-Null
$taskXmlPath = Join-Path "$taskOutputRoot" "package.wxs"
& "$taskDark" -nologo -x "$taskOutputRoot" "$taskMsiPath" "$taskXmlPath"
if ($LASTEXITCODE -ne 0) { throw "MSI decompilation failed with exit code $LASTEXITCODE." }
[xml] $taskXml = Get-Content -LiteralPath "$taskXmlPath" -Raw
$taskProduct = $taskXml.Wix.Product
if ($taskProduct.Package.InstallPrivileges -ne "limited") { throw "Installer must require only per-user privileges." }
if ($taskProduct.UpgradeCode -ne "{78F89549-27EC-4C2C-8B87-297C43349A44}") { throw "Unexpected installer upgrade identity." }
$taskInstall = $taskXml.SelectSingleNode('//*[local-name()="Directory" and @Id="INSTALLDIR"]')
if ($taskInstall.Name -ne "Kototoro-App" -or $taskInstall.ParentNode.Id -ne "LocalAppDataFolder") {
    throw "Program installation must be separated from LOCALAPPDATA/Kototoro user data."
}
$taskRegistry = @($taskXml.SelectNodes('//*[local-name()="RegistryValue"]'))
if (@($taskRegistry | Where-Object { $_.Root -ne "HKCU" }).Count -ne 0) { throw "Installer writes outside HKCU." }
$taskFiles = @($taskXml.SelectNodes('//*[local-name()="File"]'))
$taskSeenFiles = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$taskChecked = 0
$taskPackageMarkers = 0
foreach ($taskFile in $taskFiles) {
    $taskParts = @("$($taskFile.Name)")
    $taskParent = $taskFile.ParentNode.ParentNode
    while ($taskParent.LocalName -eq "Directory" -and $taskParent.Id -ne "INSTALLDIR") {
        $taskParts = @("$($taskParent.Name)") + $taskParts
        $taskParent = $taskParent.ParentNode
    }
    if ($taskParent.Id -ne "INSTALLDIR") { throw "Unexpected file outside the program installation directory." }
    $taskRelative = $taskParts -join [System.IO.Path]::DirectorySeparatorChar
    if (!$taskSeenFiles.Add($taskRelative)) { throw "MSI duplicated an application image file: $taskRelative" }
    $taskInstalledFile = [System.IO.Path]::GetFullPath((Join-Path "$taskImageRoot" "$taskRelative"))
    $taskExtractedFile = [System.IO.Path]::GetFullPath("$($taskFile.Source)")
    if (!$taskInstalledFile.StartsWith("$taskImageRoot\", [System.StringComparison]::OrdinalIgnoreCase) -or
        !$taskExtractedFile.StartsWith("$taskOutputRoot\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "MSI file path escaped the inspected artifact directories."
    }
    # jpackage replaces build-only app/.jpackage.xml with this installed-package name marker.
    if ($taskRelative -eq "app\.package") {
        if ([System.IO.File]::ReadAllText($taskExtractedFile) -cne "Kototoro") { throw "Unexpected installed-package marker." }
        $taskPackageMarkers++
        continue
    }
    if (!(Test-Path -LiteralPath "$taskInstalledFile" -PathType Leaf)) { throw "MSI added an unexpected file: $taskRelative" }
    if ((Get-ArtifactHash "$taskInstalledFile") -ne (Get-ArtifactHash "$taskExtractedFile")) {
        throw "MSI payload differs from the verified application image: $taskRelative"
    }
    $taskChecked++
}
$taskImageMetadata = Join-Path "$taskImageRoot" "app/.jpackage.xml"
if (!(Test-Path -LiteralPath "$taskImageMetadata" -PathType Leaf) -or $taskPackageMarkers -ne 1) {
    throw "Missing jpackage image metadata or installed-package marker."
}
$taskImageCount = @(Get-ChildItem -LiteralPath "$taskImageRoot" -Recurse -File -Force |
    Where-Object { $_.FullName -ne $taskImageMetadata.Replace('/', '\') }).Count
if ($taskChecked -ne $taskImageCount) { throw "MSI omitted application image files: MSI=$taskChecked image=$taskImageCount" }
$taskReport = @("MSI_CONTENTS_OK=$taskChecked", "JPACKAGE_METADATA=1 validated package marker; build-only image metadata omitted",
    "INSTALL_DIRECTORY=LOCALAPPDATA/Kototoro-App",
    "USER_DATA_DIRECTORY=LOCALAPPDATA/Kototoro", "REGISTRY=HKCU", "UPGRADE_CODE=$($taskProduct.UpgradeCode)")
$taskReport | Set-Content -LiteralPath (Join-Path "$taskOutputRoot" "result.txt") -Encoding UTF8
$taskReport | Write-Output
