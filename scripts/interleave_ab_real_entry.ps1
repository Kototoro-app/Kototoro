# Interleaved A/B of the *real* reader entry (ReaderActivity), not the benchmark journey.
#
# Why this exists: the CS-7 gate launches ReaderProductionBenchmarkActivity, which composes
# ComposeScenePagedReader directly and never consults `isExperimentalPagedSceneReaderEnabled`.
# A release build with default preferences therefore never takes that path (paged/double-page
# stay on the legacy ComposePagedReader). The gate measures the candidate default path - which
# is what a promotion decision needs - but it cannot answer "would a user see this stall?".
#
# This script launches ReaderActivity through the same intent contract AppRouter uses
# (`<pkg>.action.READ_MANGA` + https://kototoro.app/manga/<id>) at a fixture chapter that lives
# on disk in the app's own local-storage root, so the pages are byte-identical to the ones the
# `pagedLargeZoom1_5` benchmark uses and no network is involved.
#
# Protocol per round and side: install -> full AOT compile -> clear background jobs -> thermal
# gate (<=35.0C) -> refresh-rate gate (>=119Hz) -> discarded warm-up launch -> measured launch
# captured with the benchmark's own Perfetto config. Both sides are measured in the same round
# so drift lands on both.
#
# Usage:
#   ./scripts/interleave_ab_real_entry.ps1 -Rounds 4
#   ./scripts/interleave_ab_real_entry.ps1 -Rounds 1 -SkipBuild      # reuse existing APKs

param(
    [string]$BaselineRepo = "E:\kototoro_demo\baseline-67a243fc6",
    [string]$CurrentRepo = "E:\kototoro_demo\Kototoro",
    [string]$OutDir = "E:\kototoro_demo\reader-bench\realentry-20260921\ab",
    [int]$Rounds = 4,
    [string]$Serial = "ecd4369c",
    [string]$MangaId = "-722638835630210246",
    [ValidateSet("screen", "journey")][string]$Mode = "screen",
    [int]$JourneyTurns = 4,
    [int]$ThermalLimitC = 350,
    [int]$CooldownCapSeconds = 480,
    [switch]$SkipBuild,
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
$pkg = "org.skepsun.kototoro"
$apkRel = "app\build\outputs\apk\benchmark\app-arm64-v8a-benchmark.apk"
$repoRoot = Split-Path -Parent $PSScriptRoot
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Adb([string[]]$Arguments) {
    # Native stderr must not become a terminating error; callers inspect the text instead.
    $out = cmd /c ("adb -s $Serial " + ($Arguments -join " ") + " 2>&1")
    return ($out -join "`n")
}

function AdbRoot([string]$Command) {
    return (Adb @("shell", "su", "-c", "`"$Command`""))
}

function Get-BatteryTemp {
    $text = Adb @("shell", "dumpsys", "battery")
    $match = [regex]::Match($text, "temperature:\s*(\d+)")
    if (-not $match.Success) { return -1 }
    return [int]$match.Groups[1].Value
}

function Get-PeakRefreshRate {
    $text = Adb @("shell", "dumpsys", "display")
    $match = [regex]::Match($text, "mActiveSfDisplayMode=.*?peakRefreshRate=([\d.]+)", "Singleline")
    if (-not $match.Success) { return -1 }
    return [double]$match.Groups[1].Value
}

function Build-Side([string]$repo) {
    if ($SkipBuild) { return }
    Push-Location $repo
    try {
        Write-Output "    building $repo"
        cmd /c "gradlew.bat :app:assembleBenchmark --console=plain -q" | Out-Null
    } finally {
        Pop-Location
    }
}

function Install-Side([string]$repo) {
    $apk = Join-Path $repo $apkRel
    if (-not (Test-Path $apk)) { throw "missing APK: $apk" }
    $digest = (Get-FileHash $apk -Algorithm SHA256).Hash.Substring(0, 12)
    $text = Adb @("install", "-r", "-t", "`"$apk`"")
    if ($text -notmatch "Success") { throw "install failed for $apk`n$text" }
    return $digest
}

function Clear-Background {
    Adb @("shell", "am", "force-stop", $pkg) | Out-Null
    cmd /c "adb -s $Serial shell cmd jobscheduler stop -u 0 $pkg 2>nul" | Out-Null
}

function Enable-FullCompilation {
    if ($SkipCompile) { return "skipped" }
    $text = AdbRoot "cmd package compile -m speed -f $pkg"
    if ($text -notmatch "Success") { return "compile-output: $text" }
    return "speed"
}

function Assert-DeviceReady([string]$phase) {
    # A locked or sleeping device silently ruins this measurement: activities still start, but the
    # window is never visible, so the app draws almost nothing and the frame matcher finds two
    # frames where the benchmark found six hundred. Fail loudly instead of measuring nothing.
    $power = Adb @("shell", "dumpsys", "power")
    $window = Adb @("shell", "dumpsys", "window")
    $awake = $power -match "mWakefulness=Awake"
    $locked = $window -match "isKeyguardShowing=true"
    if (-not $awake -or $locked) {
        throw "device not ready ($phase): awake=$awake keyguardShowing=$locked - unlock the device and keep the screen on"
    }
}

function Assert-ReaderVisible([string]$label) {
    $window = Adb @("shell", "dumpsys", "window")
    if ($window -notmatch "mCurrentFocus=Window\{[^}]*org\.skepsun\.kototoro/") {
        throw "reader window is not focused after $label - the run drew no visible frames"
    }
}

function Wait-Thermal {
    # No `KEYCODE_SLEEP` here on purpose: this device has a secure pattern lock, so every screen-off
    # re-locks it and the next launch happens with an invisible window (the failure mode that cost a
    # whole round). Cooling therefore happens with the screen on and the app force-stopped, and the
    # measured temperature is written to runs.txt next to every run.
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $CooldownCapSeconds) {
        $temp = Get-BatteryTemp
        if ($temp -le $ThermalLimitC -and $temp -gt 0) {
            Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
            Adb @("shell", "svc", "power", "stayon", "true") | Out-Null
            Adb @("shell", "wm", "dismiss-keyguard") | Out-Null
            Start-Sleep -Seconds 2
            Assert-DeviceReady "after cooldown"
            return $temp
        }
        Start-Sleep -Seconds 15
    }
    throw "thermal precondition not met after $CooldownCapSeconds s (last=${temp})"
}

function Wait-RefreshRate {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $hz = Get-PeakRefreshRate
        if ($hz -ge 119) { return $hz }
        Start-Sleep -Seconds 5
        Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
    }
    throw "refresh-rate precondition not met (last=${hz}Hz)"
}

function Prepare-Side([string]$repo) {
    $digest = Install-Side $repo
    Clear-Background
    $compile = Enable-FullCompilation
    Clear-Background
    Start-Sleep -Seconds 3
    # The reader resumes the saved position, so every measured run has to start from the same one.
    & (Join-Path $PSScriptRoot "real_entry_fixture.ps1") -Action reset -Serial $Serial | Out-Null
    Clear-Background
    AdbRoot "sh /data/local/tmp/real_entry_reader_run.sh - warmup $MangaId" | Out-Null
    return [pscustomobject]@{ Digest = $digest; Compile = $compile }
}

function Measure-Side([string]$label, [int]$round) {
    $temp = Wait-Thermal
    $hz = Wait-RefreshRate
    $trace = Join-Path $OutDir "round$round-$label-$Mode.pb"
    AdbRoot "rm -f /data/misc/perfetto-traces/real_entry.pb" | Out-Null
    $launch = AdbRoot "JOURNEY_TURNS=$JourneyTurns sh /data/local/tmp/real_entry_reader_run.sh /data/misc/perfetto-traces/real_entry.pb $Mode $MangaId"
    $launch | Set-Content -Path (Join-Path $OutDir "round$round-$label-$Mode.launch.txt") -Encoding UTF8
    Assert-ReaderVisible "$label r$round"
    Adb @("pull", "/data/misc/perfetto-traces/real_entry.pb", "`"$trace`"") | Out-Null
    # Reading position after the run is the validity check for the journey: it is the only cheap
    # evidence that the injected turns actually committed (page advances, percent leaves -1).
    $position = ((& (Join-Path $PSScriptRoot "real_entry_fixture.ps1") -Action position -Serial $Serial) -join " ") -replace "\s+", " "
    $meta = "round=$round side=$label mode=$Mode turns=$JourneyTurns temp=$temp hz=$hz trace=$(Test-Path $trace) position=[$($position.Trim())]"
    $meta | Add-Content -Path (Join-Path $OutDir "runs.txt")
    Write-Output "    [$label r$round] $meta"
}

Write-Output "== real-entry A/B: rounds=$Rounds out=$OutDir"
Adb @("push", "`"$(Join-Path $repoRoot 'scripts\perfetto_reader_real_entry.cfg')`"", "/data/local/tmp/real_entry.cfg") | Out-Null
Adb @("push", "`"$(Join-Path $repoRoot 'scripts\real_entry_reader_run.sh')`"", "/data/local/tmp/real_entry_reader_run.sh") | Out-Null
# Perfetto refuses configs outside /data/misc/perfetto-configs, so the pushed copy is installed there.
AdbRoot "mkdir -p /data/misc/perfetto-configs; cp /data/local/tmp/real_entry.cfg /data/misc/perfetto-configs/real_entry.cfg; chmod 644 /data/misc/perfetto-configs/real_entry.cfg" | Out-Null

"# real-entry A/B run log" | Set-Content -Path (Join-Path $OutDir "runs.txt") -Encoding UTF8
Add-Content -Path (Join-Path $OutDir "runs.txt") -Value "baseline_repo=$(git -C $BaselineRepo log -1 --format='%h') current_repo=$(git -C $CurrentRepo log -1 --format='%h') manga_id=$MangaId mode=$Mode turns=$JourneyTurns"
Adb @("shell", "svc", "power", "stayon", "true") | Out-Null
Assert-DeviceReady "before round 1"

for ($round = 1; $round -le $Rounds; $round++) {
    Write-Output "== round $round"
    foreach ($side in @(
            [pscustomobject]@{ Label = "baseline"; Repo = $BaselineRepo },
            [pscustomobject]@{ Label = "current"; Repo = $CurrentRepo }
        )) {
        Build-Side $side.Repo
        $prepared = Prepare-Side $side.Repo
        Add-Content -Path (Join-Path $OutDir "runs.txt") -Value "round=$round side=$($side.Label) apk=$($prepared.Digest) compile=$($prepared.Compile)"
        Measure-Side $side.Label $round
        Clear-Background
    }
}
Write-Output "== done: $OutDir"
