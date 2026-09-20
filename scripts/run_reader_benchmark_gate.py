#!/usr/bin/env python3
"""Run the reader production journeys on a device and judge them against the group SLOs.

This is the scripting half of CS-7: one command builds the benchmark variant, verifies that the
package it installs is the package it built, runs the selected journeys with the refresh-rate and
thermal preconditions enforced, archives every trace, then hands each run to
check_reader_benchmark.py for the threshold judgement.

    python scripts/run_reader_benchmark_gate.py --group all --archive-dir ../reader-bench/run
    python scripts/run_reader_benchmark_gate.py --scenarios pagedSingleSceneFull --skip-build

Thresholds and their justification are in docs/architecture/reader-scene-improvement-plan-2026-09.md
section 4.2.1. Device protocol and known OEM pitfalls are in macrobenchmark/README.md.

Exit codes: 0 = every judged scenario passed, 1 = at least one SLO violation,
2 = evidence insufficient (or the harness/preconditions failed).
"""

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

from check_reader_benchmark import SCENARIO_GROUPS

PACKAGE = "org.skepsun.kototoro"
BENCHMARK_CLASS = "org.skepsun.kototoro.macrobenchmark.ReaderProductionBenchmark"
BENCHMARK_ACTIVITY = "org.skepsun.kototoro/.reader.benchmark.ReaderProductionBenchmarkActivity"
ADDITIONAL_OUTPUT = "macrobenchmark/build/outputs/connected_android_test_additional_output/debug/connected"
REFRESH_MIN_HZ = 119.0
BATTERY_COOL_LIMIT = 350  # 35.0 C, tenths of a degree
COOL_LIMIT_SECONDS = 480


def run(command, serial=None, check=False, capture=True):
    if serial and command and command[0] == "adb":
        command = ["adb", "-s", serial] + command[1:]
    result = subprocess.run(command, capture_output=capture, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed: {result.stderr or result.stdout}")
    return result


def sha256_of(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def adb_shell(serial, command, check=False):
    return run(["adb", "shell", command], serial=serial, check=check)


def battery_temp(serial):
    match = re.search(r"temperature:\s*(\d+)", adb_shell(serial, "dumpsys battery").stdout or "")
    return int(match.group(1)) if match else None


def last_update_time(serial):
    output = adb_shell(serial, f"dumpsys package {PACKAGE}").stdout or ""
    match = re.search(r"lastUpdateTime=([0-9:\- ]+)", output)
    return match.group(1).strip() if match else None


def active_refresh_hz(serial):
    output = adb_shell(serial, "dumpsys display").stdout or ""
    match = re.search(r"mActiveSfDisplayMode=.*?peakRefreshRate=([\d.]+)", output, re.S)
    return float(match.group(1)) if match else None


def warm_refresh_precondition(serial, attempts=3):
    """MIUI applies a window's high-refresh request late; the harness asserts >=119Hz at setup."""
    rate = None
    for attempt in range(1, attempts + 1):
        adb_shell(serial, "input keyevent KEYCODE_WAKEUP")
        adb_shell(serial, f"am force-stop {PACKAGE}")
        adb_shell(serial, f"am start -n {BENCHMARK_ACTIVITY} --es backend scene_paged "
                          f"--es fixture_mode paged_large --es zoom_mode fit_height --ef default_scale 1.0")
        time.sleep(8)
        rate = active_refresh_hz(serial)
        adb_shell(serial, f"am force-stop {PACKAGE}")
        print(f"    refresh precondition attempt {attempt}: {rate} Hz")
        if rate is not None and rate >= REFRESH_MIN_HZ:
            break
        time.sleep(10)
    return rate


def cool_down(serial, limit=BATTERY_COOL_LIMIT, cap=COOL_LIMIT_SECONDS):
    waited = 0
    while waited < cap:
        temp = battery_temp(serial)
        if temp is None or temp <= limit:
            break
        print(f"    cooling: battery={temp / 10:.1f}C, waited={waited}s")
        time.sleep(20)
        waited += 20
    time.sleep(15)


def install_and_verify(repo, serial, apk, verify_bytes=True):
    digest = sha256_of(apk)
    before = last_update_time(serial)
    run(["adb", "install", "-r", "-t", str(apk)], serial=serial, check=True)
    after = last_update_time(serial)
    print(f"    installed {apk.name} sha256={digest[:16]} lastUpdateTime {before} -> {after}")
    if before == after:
        raise RuntimeError("lastUpdateTime did not change: the device may still run an older package")

    if not verify_bytes:
        return digest
    path_match = re.search(r"package:(.+)", adb_shell(serial, f"pm path {PACKAGE}").stdout or "")
    if not path_match:
        print("    WARNING: pm path gave no path; skipped the installed-bytes check")
        return digest
    remote = "/data/local/tmp/gate-installed.apk"
    if adb_shell(serial, f"su -c 'cp {path_match.group(1).strip()} {remote}'").returncode != 0:
        print("    WARNING: no root; skipped the installed-bytes check")
        return digest
    local = Path(apk).parent / "gate-installed.apk"
    run(["adb", "pull", remote, str(local)], serial=serial, check=True)
    installed = sha256_of(local)
    local.unlink(missing_ok=True)
    adb_shell(serial, f"rm -f {remote}")
    if installed != digest:
        raise RuntimeError(f"installed package {installed} != built APK {digest}")
    print("    installed bytes match the built APK")
    return digest


def additional_output_dirs(repo):
    root = Path(repo) / ADDITIONAL_OUTPUT
    return sorted(p for p in root.glob("*") if p.is_dir()) if root.exists() else []


def extract_frames(repo, scenario_dir, scenario, trace_processor):
    json_files = list(scenario_dir.glob("*-benchmarkData.json"))
    if len(json_files) != 1:
        print(f"    extraction skipped: {len(json_files)} benchmarkData.json files")
        return False
    command = [
        sys.executable, str(Path(repo) / "scripts" / "analyze_reader_frames.py"),
        "--trace-processor", trace_processor,
        "--trace-dir", str(scenario_dir),
        "--benchmark-json", str(json_files[0]),
        "--benchmark-name", scenario,
        "--output-dir", str(scenario_dir / "verified"),
    ]
    result = subprocess.run(command, text=True, capture_output=True)
    tail = (result.stdout or "").strip().splitlines()[-1:] or [""]
    print(f"    extract exit={result.returncode}: {tail[0]}")
    if result.returncode != 0:
        print(f"    extract stderr: {(result.stderr or '').strip()[-400:]}")
    return result.returncode == 0


def run_journey(repo, serial, scenario, archive, apk_digest, timeout_seconds, trace_processor):
    scenario_dir = Path(archive) / scenario
    scenario_dir.mkdir(parents=True, exist_ok=True)
    for old in scenario_dir.glob("*.perfetto-trace"):
        old.unlink()
    for out_dir in additional_output_dirs(repo):
        for stale in out_dir.glob("*"):
            if stale.is_file():
                stale.unlink()

    cool_down(serial)
    rate = warm_refresh_precondition(serial)
    temp_before = battery_temp(serial)
    started = time.strftime("%Y-%m-%d %H:%M:%S")
    log_path = scenario_dir / "gradle.log"
    command = [
        str(Path(repo) / ("gradlew.bat" if sys.platform == "win32" else "gradlew")),
        ":macrobenchmark:connectedDebugAndroidTest",
        f"-Pandroid.testInstrumentationRunnerArguments.class={BENCHMARK_CLASS}#{scenario}",
        "--console=plain",
    ]
    with log_path.open("w") as log:
        exit_code = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT,
                                   timeout=timeout_seconds).returncode

    copied = 0
    for out_dir in additional_output_dirs(repo):
        for artifact in out_dir.glob("*"):
            if artifact.is_file():
                shutil.copy2(artifact, scenario_dir / artifact.name)
                copied += 1
    traces = list(scenario_dir.glob("*.perfetto-trace"))
    (scenario_dir / "run-summary.txt").write_text(
        f"scenario={scenario}\n"
        f"gradle_exit={exit_code}\n"
        f"package={PACKAGE}\n"
        f"apk_sha256={apk_digest}\n"
        f"device_serial={serial}\n"
        f"refresh_precondition_hz={rate if rate is not None else 'unknown'}\n"
        f"battery_temp_before={temp_before if temp_before is not None else 'unknown'}\n"
        f"battery_temp_after={battery_temp(serial)}\n"
        f"started={started}\n"
    )
    print(f"    archived {copied} files ({len(traces)} traces) exit={exit_code}")
    if exit_code != 0 or not traces:
        return False
    return extract_frames(repo, scenario_dir, scenario, trace_processor)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenarios", default="", help="comma separated benchmark method names")
    parser.add_argument("--group", choices=["regular", "large", "high_zoom", "all"], default=None,
                        help="run every scenario of one SLO group")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--archive-dir", type=Path, required=True)
    parser.add_argument("--serial", default=None, help="adb device serial (default: the only device)")
    parser.add_argument("--apk", type=Path, default=None)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--skip-judge", action="store_true")
    parser.add_argument("--skip-installed-bytes-check", action="store_true")
    parser.add_argument("--trace-processor", default="trace_processor")
    parser.add_argument("--timeout-seconds", type=int, default=900, help="per journey gradle timeout")
    parser.add_argument("--keep-going", action="store_true", help="continue after a failed journey")
    args = parser.parse_args()

    scenarios = [s for s in args.scenarios.split(",") if s]
    if args.group:
        wanted = set(SCENARIO_GROUPS.values()) if args.group == "all" else {args.group}
        scenarios += [name for name, group in SCENARIO_GROUPS.items()
                      if group in wanted and name not in scenarios]
    if not scenarios:
        parser.error("pass --scenarios and/or --group")
    unknown = [s for s in scenarios if s not in SCENARIO_GROUPS]
    if unknown:
        print(f"WARNING: no SLO is defined for {', '.join(unknown)}; they will be collected but "
              "judged as insufficient evidence")

    serial = args.serial
    if serial is None:
        devices = [line.split()[0] for line in run(["adb", "devices"]).stdout.splitlines()[1:]
                   if line.strip().endswith("device")]
        if len(devices) != 1:
            parser.error(f"expected exactly one attached device, found {devices}")
        serial = devices[0]
    print(f"device: {serial}")

    apk = args.apk or (args.repo / "app/build/outputs/apk/benchmark/app-arm64-v8a-benchmark.apk")
    if not args.skip_build:
        print("building the benchmark variant...")
        run([str(args.repo / ("gradlew.bat" if sys.platform == "win32" else "gradlew")),
             ":app:assembleBenchmark", "--console=plain"], check=True, capture=False)
    if not apk.exists():
        raise SystemExit(f"benchmark APK not found: {apk}")
    digest = sha256_of(apk)
    if not args.skip_install:
        digest = install_and_verify(args.repo, serial, apk,
                                    verify_bytes=not args.skip_installed_bytes_check)

    outcomes = {}
    for scenario in scenarios:
        print(f"\n== {scenario}")
        try:
            ok = run_journey(args.repo, serial, scenario, args.archive_dir, digest,
                             args.timeout_seconds, args.trace_processor)
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            print(f"    journey failed: {error}")
            ok = False
        outcomes[scenario] = ok
        if not ok and not args.keep_going:
            print("stopping: pass --keep-going to continue past a failed journey")
            return 2

    if args.skip_judge:
        return 0

    print("\n== judging")
    judge = [
        sys.executable, str(Path(__file__).resolve().parent / "check_reader_benchmark.py"),
        "--campaign-root", str(args.archive_dir),
        "--json-out", str(Path(args.archive_dir) / "gate-verdict.json"),
    ]
    result = subprocess.run(judge, text=True)
    if result.returncode != 0:
        return result.returncode
    return 0 if all(outcomes.values()) else 2


if __name__ == "__main__":
    sys.exit(main())
