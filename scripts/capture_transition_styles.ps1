# CS-2 real-device visual confirmation: one capture per transition style, at the same drag position.
#
# The closure plan's device method (§真机验证方法) needs three things that are easy to get wrong:
#  1. MIUI/HyperOS silently drops injected input until `persist.security.adbinput` is set;
#  2. screenshots must go through `cmd /c adb exec-out screencap >` because PowerShell `>` corrupts
#     the binary;
#  3. the frames must be taken at the *same* drag position, with the finger still down, or the
#     transition has already settled and every style looks identical.
#
# Raw is captured (16-byte header + RGBA8888) and converted to PNG by the probe after the fact.

param(
    [string]$Backend = "scene_paged",
    [string]$FixtureMode = "paged",
    [string]$OutDir = "E:\kototoro_demo\reader-bench\cs2-visual-20260921",
    [string]$Serial = "ecd4369c"
)

$ErrorActionPreference = "Stop"
$pkg = "org.skepsun.kototoro"
$activity = "$pkg/.reader.benchmark.ReaderProductionBenchmarkActivity"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Capture([string]$name) {
    $raw = Join-Path $OutDir "$name.raw"
    # cmd redirection, not PowerShell: `>` mangles the stream.
    cmd /c "adb -s $Serial exec-out screencap > `"$raw`"" | Out-Null
    $len = (Get-Item $raw).Length
    Write-Output "  captured $name ($len bytes)"
    return $raw
}

function Launch([string]$animation) {
    adb -s $Serial shell am force-stop $pkg | Out-Null
    Start-Sleep -Seconds 1
    adb -s $Serial shell am start -n $activity --es backend $Backend --es fixture_mode $FixtureMode --es animation $animation | Out-Null
    # The oversized fixtures are rendered on first use, and the activity requests 120Hz on start.
    Start-Sleep -Seconds 14
}

Write-Output "== enabling injected input on MIUI/HyperOS"
adb -s $Serial shell su -c "setprop persist.security.adbinput 1" | Out-Null
Write-Output "   persist.security.adbinput=$(adb -s $Serial shell getprop persist.security.adbinput)"

$display = adb -s $Serial shell wm size
Write-Output "== display: $display"
$midY = 1386
$leftX = 160
$rightX = 1120

foreach ($style in @("none", "default", "advanced", "simulation")) {
    Write-Output "== style=$style"
    Launch $style
    $rest = Capture "$style-rest"

    # One slow drag from the right edge toward the centre, with the finger still down when the
    # capture happens: a fast swipe would fling and settle before the screenshot lands.
    adb -s $Serial shell input motionevent DOWN $rightX $midY | Out-Null
    for ($x = $rightX; $x -ge 640; $x -= 40) {
        adb -s $Serial shell input motionevent MOVE $x $midY | Out-Null
    }
    $drag = Capture "$style-drag"
    adb -s $Serial shell input motionevent UP 640 $midY | Out-Null
    Start-Sleep -Milliseconds 400

    Write-Output "   rest=$rest drag=$drag"
}

Write-Output "== done; captures in $OutDir"
