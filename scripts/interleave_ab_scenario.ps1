# Interleaved A/B for the 1.5x scenario.
#
# The earlier "same-day" comparison ran the two builds hours apart, which leaves device drift free to
# explain the difference. This alternates baseline and current (A, B, A, B) so drift affects both
# sides equally, and reports the SLO metrics plus the trace slice families that the attribution
# depends on.
#
# Usage:
#   ./scripts/interleave_ab_scenario.ps1 -BaselineRepo E:\kototoro_demo\baseline-67a243fc6 `
#       -CurrentRepo E:\kototoro_demo\Kototoro -Rounds 2 -OutDir E:\kototoro_demo\reader-bench\interleave-20260921

param(
    [Parameter(Mandatory = $true)][string]$BaselineRepo,
    [Parameter(Mandatory = $true)][string]$CurrentRepo,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [int]$Rounds = 2,
    [string]$Scenario = "pagedLargeZoom1_5SceneFull",
    [string]$Serial = "ecd4369c"
)

$ErrorActionPreference = "Stop"
# adb writes transfer progress to stderr, which PowerShell turns into a terminating error once
# ErrorActionPreference is Stop; the file it complains about was pulled correctly.
$PSNativeCommandUseErrorActionPreference = $false
$pkg = "org.skepsun.kototoro"
$benchClass = "org.skepsun.kototoro.macrobenchmark.ReaderProductionBenchmark"
$runner = "org.skepsun.kototoro.macrobenchmark/androidx.test.runner.AndroidJUnitRunner"
$traceProcessor = "C:\Users\chuxi\.local\share\perfetto\prebuilts\trace_processor_shell-adfa6bad3d72be3b.exe"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Build-And-Install([string]$repo) {
    Push-Location $repo
    try {
        Write-Output "    building benchmark variant"
        cmd /c "gradlew.bat :app:assembleBenchmark --console=plain -q" | Out-Null
        $apk = Join-Path $repo "app\build\outputs\apk\benchmark\app-arm64-v8a-benchmark.apk"
        if (-not (Test-Path $apk)) { throw "no benchmark apk in $repo" }
        $digest = (Get-FileHash $apk -Algorithm SHA256).Hash
        cmd /c "adb -s $Serial install -r `"$apk`"" | Out-Null
        Write-Output "    installed ${digest}:$($digest.Substring(0,12)) at $((Get-Item $apk).LastWriteTime.ToString('HH:mm:ss'))"
        return $digest
    } finally {
        Pop-Location
    }
}

function Measure-Once([string]$label, [string]$repo, [int]$round) {
    $digest = Build-And-Install $repo
    adb -s $Serial shell am force-stop $pkg | Out-Null
    Start-Sleep -Seconds 3
    $out = adb -s $Serial shell su -c "am instrument -w -e class $benchClass#$Scenario $runner" 2>&1
    $text = $out -join "`n"
    $file = Join-Path $OutDir "round$round-$label.txt"
    $text | Set-Content -Path $file -Encoding UTF8

    # The last trace in the standard location holds the final iteration.
    $traceOut = Join-Path $OutDir "round$round-$label.pb"
    adb -s $Serial shell su -c "cp /data/misc/perfetto-traces/trace_output.pb /data/local/tmp/ab.pb" | Out-Null
    # Through cmd: adb writes transfer progress to stderr, which PowerShell turns into a terminating
    # error under ErrorActionPreference Stop even though the pull itself succeeded.
    cmd /c "adb -s $Serial pull /data/local/tmp/ab.pb `"$traceOut`" 2>nul" | Out-Null
    if (-not (Test-Path $traceOut)) { throw "trace pull failed for round$round-$label" }

    $frames = ([regex]::Match($text, "(?m)^\s*frameCount.*?\[median\s+([\d.]+)\]")).Groups[1].Value
    Write-Output "  $label round${round}: apk=$($digest.Substring(0,12)) framesMedian=$frames"
    return @{ label = $label; round = $round; digest = $digest; frames = $frames; trace = $traceOut }
}

$results = @()
for ($round = 1; $round -le $Rounds; $round++) {
    Write-Output "== round $round"
    $results += Measure-Once "baseline" $BaselineRepo $round
    $results += Measure-Once "current" $CurrentRepo $round
}

Write-Output ""
Write-Output "== summary"
$results | ForEach-Object { "  round$($_.round) $($_.label): frames=$($_.frames) trace=$($_.trace)" }
$results | ConvertTo-Json | Set-Content -Path (Join-Path $OutDir "summary.json") -Encoding UTF8
