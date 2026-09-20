# Single-scenario SLO probe for a fixed build, using the device-side protocol.
#
# The full gate takes ~20 minutes per scenario because it cools the device between journeys. For
# attribution (does this one commit cause this one violation?) the plan's device method offers a much
# shorter loop: install the app, run the macrobenchmark class directly as root, read the metrics from
# the instrumentation output. This script does the rebuild + install + run for one hotspot scenario
# so an A/B costs minutes instead of an hour.
#
# Usage:
#   ./scripts/probe_slo_scenario.ps1 -Scenario pagedLargeZoom1_5SceneFull -OutFile E:\...\ab-A.txt

param(
    [string]$Scenario = "pagedLargeZoom1_5SceneFull",
    [string]$OutFile = "",
    [string]$Serial = "ecd4369c",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$pkg = "org.skepsun.kototoro"
$benchPkg = "org.skepsun.kototoro.macrobenchmark"
$runner = "$benchPkg/androidx.test.runner.AndroidJUnitRunner"

if (-not $SkipBuild) {
    Write-Output "== building the benchmark app and the macrobenchmark module"
    # Through cmd: Gradle writes progress to stderr, and PowerShell treats native stderr as a
    # terminating error once $ErrorActionPreference is Stop.
    cmd /c "gradlew.bat :app:assembleBenchmark :app:installBenchmark --console=plain -q" | Out-Null
    cmd /c "gradlew.bat :macrobenchmark:assembleDebug --console=plain -q" | Out-Null
    $benchApk = "macrobenchmark\build\outputs\apk\debug\macrobenchmark-debug.apk"
    cmd /c "adb -s $Serial install -r $benchApk" | Out-Null
}

Write-Output "== confirming the installed app is the one just built"
$installed = adb -s $Serial shell pm path $pkg
Write-Output "   $installed"

Write-Output "== running $Scenario as root (the harness and perfetto must share a uid for frame timing)"
$output = adb -s $Serial shell su -c "am instrument -w -e class $benchPkg.ReaderProductionBenchmark#$Scenario $runner" 2>&1
$text = $output -join "`n"

if ($OutFile) {
    $text | Set-Content -Path $OutFile -Encoding UTF8
    Write-Output "== raw output: $OutFile"
}

# Report just the numbers that decide the SLO, so an A/B can be read at a glance.
foreach ($metric in @("frameCount", "frameDurationCpuMs", "frameOverrunMs", "memoryMaxRssAnonMaxKb", "memoryMaxGpuMaxKb")) {
    $match = [regex]::Matches($text, "(?m)^\s*$metric.*$")
    foreach ($m in $match) { Write-Output "   $($m.Value.Trim())" }
}
$failure = [regex]::Match($text, "(?s)(Exception|Error|FAILURES).{0,200}")
if ($failure.Success) { Write-Output "   !! $($failure.Value -replace "`n", ' ')" }
