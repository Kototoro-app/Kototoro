[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $CompatibilityDirectory,
    [ValidateRange(21, 99)]
    [int] $JavaVersion = 21,
    [string] $BridgeExecutable,
    [string] $DataDirectory,
    [string] $ImportJar
)

$ErrorActionPreference = "Stop"
$taskProjectRoot = (Resolve-Path (Join-Path "$PSScriptRoot" "..")).Path
$taskCompatibilityRoot = (Resolve-Path -LiteralPath "$CompatibilityDirectory").Path
if (!(Test-Path -LiteralPath "$taskCompatibilityRoot" -PathType Container)) {
    throw "CompatibilityDirectory must be a directory containing the pinned APIs."
}
$taskArguments = @("-PwithDesktopApp", "-PmihonCompatibilityDirectory=$taskCompatibilityRoot",
    "-PmihonCompatibilityJavaVersion=$JavaVersion", ":desktop-app:run", "--console=plain")
if ($BridgeExecutable) {
    $taskBridge = (Resolve-Path -LiteralPath "$BridgeExecutable").Path
    $taskArguments += "-PdesktopBridgeExecutable=$taskBridge"
}
if ($DataDirectory) {
    $taskArguments += "-PdesktopDataDirectory=$([System.IO.Path]::GetFullPath($DataDirectory))"
}
if ($ImportJar) {
    $taskJar = (Resolve-Path -LiteralPath "$ImportJar").Path
    $taskArguments += "-PdesktopImportJar=$taskJar"
}
Push-Location "$taskProjectRoot"
try {
    & "$taskProjectRoot/gradlew.bat" @taskArguments
    if ($LASTEXITCODE -ne 0) { throw "Desktop run failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }
