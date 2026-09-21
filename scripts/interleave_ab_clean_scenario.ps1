# Interleaved A/B with the app's own background work excluded.
#
# The first interleaving left a confound: one "current" trace contained a GoogleDriveSyncWorker that
# ran for sixty seconds with wake locks and thirty seconds of AsyncOpImpl, i.e. the app was doing
# Drive backup work while the journey was being measured. A benchmark that starts the app also starts
# its scheduled work, and whichever build happens to be installed when a worker fires inherits the
# cost — so the device, not just the build, has to be controlled between runs.
#
# Usage:
#   ./scripts/interleave_ab_clean_scenario.ps1 -BaselineRepo <path> -CurrentRepo <path> -OutDir <path>

param(
    [Parameter(Mandatory = $true)][string]$BaselineRepo,
    [Parameter(Mandatory = $true)][string]$CurrentRepo,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [int]$Rounds = 2,
    [string]$Scenario = "pagedLargeZoom1_5SceneFull",
    [string]$Serial = "ecd4369c"
)

$ErrorActionPreference = "Stop"
$pkg = "org.skepsun.kototoro"
$benchClass = "org.skepsun.kototoro.macrobenchmark.ReaderProductionBenchmark"
$runner = "org.skepsun.kototoro.macrobenchmark/androidx.test.runner.AndroidJUnitRunner"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Clear-Background([string]$label) {
    # Stop the app and any worker it left running, then report what is still there so an uncleared
    # run is visible in the log rather than silently biasing the numbers.
    adb -s $Serial shell am force-stop $pkg | Out-Null
    adb -s $Serial shell su -c "ps -A | grep -i -E 'drive|gms.persistent|workmanager' | grep -v grep" 2>$null | Out-Null
    $state = adb -s $Serial shell "free -m" | Select-String -Pattern 'Mem:'
    Write-Output "    [$label] $($state.Line.Trim())"
}

function Block-BackupWork() {
    # The backup worker is schedule-driven, so it can start during any run; asking the job scheduler
    # to stop the app's jobs keeps both builds facing the same conditions. Both calls are optional —
    # the job may not exist — so their output is discarded through cmd rather than pipeline parsing,
    # because PowerShell turns a native command's stderr into a terminating error here.
    cmd /c "adb -s $Serial shell cmd jobscheduler stop -u 0 $pkg 2>nul" | Out-Null
}

function Build-And-Install([string]$repo) {
    Push-Location $repo
    try {
        cmd /c "gradlew.bat :app:assembleBenchmark --console=plain -q" | Out-Null
        $apk = Join-Path $repo "app\build\outputs\apk\benchmark\app-arm64-v8a-benchmark.apk"
        $digest = (Get-FileHash $apk -Algorithm SHA256).Hash
        cmd /c "adb -s $Serial install -r `"$apk`"" | Out-Null
        return $digest.Substring(0, 12)
    } finally {
        Pop-Location
    }
}

function Measure-Once([string]$label, [string]$repo, [int]$round) {
    $digest = Build-And-Install $repo
    Clear-Background "$label r$round"
    Block-BackupWork
    Start-Sleep -Seconds 5
    $out = adb -s $Serial shell su -c "am instrument -w -e class $benchClass#$Scenario $runner" 2>&1
    $text = $out -join "`n"
    $text | Set-Content -Path (Join-Path $OutDir "round$round-$label.txt") -Encoding UTF8
    $traceOut = Join-Path $OutDir "round$round-$label.pb"
    adb -s $Serial shell su -c "cp /data/misc/perfetto-traces/trace_output.pb /data/local/tmp/ab.pb" | Out-Null
    cmd /c "adb -s $Serial pull /data/local/tmp/ab.pb `"$traceOut`" 2>nul" | Out-Null
    Write-Output "    ${label} r${round} apk=$digest trace=$(Test-Path $traceOut)"
}

for ($round = 1; $round -le $Rounds; $round++) {
    Write-Output "== round $round"
    Measure-Once "baseline" $BaselineRepo $round
    Measure-Once "current" $CurrentRepo $round
}
Write-Output "== done: $OutDir"
