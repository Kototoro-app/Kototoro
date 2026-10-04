[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $CompatibilityDirectory,
    [ValidateRange(21, 99)]
    [int] $JavaVersion = 21
)

$ErrorActionPreference = "Stop"
$taskProjectRoot = (Resolve-Path (Join-Path "$PSScriptRoot" "..")).Path
$taskCompatibilityRoot = (Resolve-Path -LiteralPath "$CompatibilityDirectory").Path
if (!(Test-Path -LiteralPath "$taskCompatibilityRoot" -PathType Container)) {
    throw "CompatibilityDirectory must be the pinned runtime directory."
}
$taskArguments = @("-PwithDesktopApp", "-PwithWindowsDistribution",
    "-PmihonCompatibilityDirectory=$taskCompatibilityRoot", "-PmihonCompatibilityJavaVersion=$JavaVersion",
    ":desktop-app:windowsDistributionTest", ":desktop-app:windowsMsiContentsCheck", ":desktop-app:windowsPortableZip",
    "--no-configuration-cache", "--console=plain")
Push-Location "$taskProjectRoot"
try {
    & "$taskProjectRoot/gradlew.bat" @taskArguments
    if ($LASTEXITCODE -ne 0) { throw "Windows local preview packaging failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
