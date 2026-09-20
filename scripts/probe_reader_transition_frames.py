"""Archive one reader production journey and decompose its frames.

The Gradle benchmark run leaves its artifacts in `connected_android_test_additional_output`, and a
later run overwrites them, so a scenario is only evidence once it is copied out. This wraps the
three steps the campaign already standardises on: run the journey, archive its artifacts, then hand
them to `analyze_reader_frames.py` for the per-frame decomposition (ui_ms, cpu_ms, overrun_ms plus
the `Record View#draw()` slice the retained-layer question depends on).

Phase D probe: the point is to learn whether transition frames have budget headroom before any
retained-GraphicsLayer work is written. Exit code is the analysis exit code.

Usage:
    python scripts/probe_reader_transition_frames.py --scenario pagedSingleSceneCoverFull \\
        --archive-dir E:/kototoro_demo/reader-bench/stageD-probe-20260920
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

BENCHMARK_CLASS = "org.skepsun.kototoro.macrobenchmark.ReaderProductionBenchmark"
ADDITIONAL_OUTPUT = Path("macrobenchmark/build/outputs/connected_android_test_additional_output/debug/connected")
ANALYZER = Path("scripts/analyze_reader_frames.py")


def run_journey(repo: Path, scenario: str, timeout_seconds: int, archive_dir: Path) -> None:
    print(f"== running {scenario}", flush=True)
    command = [
        str(repo / ("gradlew.bat" if sys.platform == "win32" else "gradlew")),
        ":macrobenchmark:connectedDebugAndroidTest",
        f"-Pandroid.testInstrumentationRunnerArguments.class={BENCHMARK_CLASS}#{scenario}",
        "--console=plain",
    ]
    completed = subprocess.run(command, cwd=repo, capture_output=True, text=True, timeout=timeout_seconds)
    archive_dir.mkdir(parents=True, exist_ok=True)
    log = archive_dir / f"gradle-{scenario}.log"
    log.write_text((completed.stdout or "") + (completed.stderr or ""), encoding="utf-8")
    print(f"== gradle log: {log}", flush=True)
    if completed.returncode != 0:
        # A failing journey is evidence too: keep the reason next to the artifacts.
        keywords = ("Caused by", "Exception", "Error", "OutOfMemory", "FAILED", "expected", "assert")
        interesting = [
            line for line in (completed.stdout or "").splitlines()
            if any(word in line for word in keywords)
        ]
        print("== failure lines", flush=True)
        for line in interesting[:25]:
            print(f"   {line.strip()}", flush=True)
        raise SystemExit(f"journey failed for {scenario} (gradle exit {completed.returncode})")


def archive(repo: Path, scenario: str, archive_dir: Path) -> Path:
    scenario_dir = archive_dir / scenario
    scenario_dir.mkdir(parents=True, exist_ok=True)
    source = repo / ADDITIONAL_OUTPUT
    if not source.is_dir():
        raise SystemExit(f"no artifact directory at {source}")
    copied = []
    # The traces carry the scenario name; the benchmark JSON does not, so it is matched by shape
    # and only when it belongs to the journey that just ran (one test method per run).
    for artifact in source.rglob("*.perfetto-trace"):
        if scenario not in artifact.name:
            continue
        destination = scenario_dir / artifact.name
        shutil.copy2(artifact, destination)
        copied.append(destination.name)
    for artifact in source.rglob("*benchmarkData.json"):
        destination = scenario_dir / artifact.name
        shutil.copy2(artifact, destination)
        copied.append(destination.name)
    if not copied:
        raise SystemExit(f"no artifacts for '{scenario}' in {source}; evidence is insufficient")
    print(f"== archived {len(copied)} artifact(s) to {scenario_dir}", flush=True)
    for name in sorted(copied):
        print(f"   {name}", flush=True)
    return scenario_dir


def analyze(repo: Path, scenario_dir: Path, scenario: str, trace_processor: str) -> int:
    benchmark_json = next(scenario_dir.glob("*benchmarkData.json"), None)
    if benchmark_json is None:
        raise SystemExit("archived run has no benchmarkData.json")
    command = [
        sys.executable,
        str(repo / ANALYZER),
        "--trace-processor", trace_processor,
        "--trace-dir", str(scenario_dir),
        "--benchmark-json", str(benchmark_json),
        "--benchmark-name", scenario,
        "--output-dir", str(scenario_dir / "verified"),
    ]
    print("== analyzing frames", flush=True)
    return subprocess.run(command, cwd=repo).returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--archive-dir", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--trace-processor", default="trace_processor.py")
    parser.add_argument("--skip-run", action="store_true",
                        help="reuse artifacts already archived in the scenario directory")
    args = parser.parse_args()

    repo = args.repo.resolve()
    if not args.skip_run:
        run_journey(repo, args.scenario, args.timeout_seconds, args.archive_dir.resolve())
    scenario_dir = archive(repo, args.scenario, args.archive_dir.resolve())
    return analyze(repo, scenario_dir, args.scenario, args.trace_processor)


if __name__ == "__main__":
    raise SystemExit(main())
