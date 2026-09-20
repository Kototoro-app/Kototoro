"""Summarise the Phase D probe: frame phases per transition style, and where late frames went.

Reads the `verified/` output of `analyze_reader_frames.py` (or `probe_reader_transition_frames.py`)
for each archived scenario and prints one comparison table, so the retained-layer decision is made
on transitions that are directly comparable (same fixture, same input, style as the only variable).
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
from pathlib import Path

PHASES = ("ui_ms", "rt_ms", "cpu_ms", "overrun_ms")


def percentile(values: list[float], percent: float) -> float:
    ordered = sorted(values)
    return ordered[int((len(ordered) - 1) * percent)]


def summarise(scenario_dir: Path) -> dict | None:
    verified = scenario_dir / "verified"
    report_path = verified / "report.json"
    frame_files = sorted(glob.glob(str(verified / "frames-*.csv")))
    if not report_path.is_file() or not frame_files:
        return None
    report = json.loads(report_path.read_text(encoding="utf-8"))
    samples: dict[str, list[float]] = {name: [] for name in PHASES}
    for path in frame_files:
        with open(path, newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                for name in PHASES:
                    samples[name].append(float(row[name]))
    return {
        "scenario": report["benchmark"],
        "frames": report["pooled"]["frame_count"],
        "overruns": report["pooled"]["overrun_count"],
        "overrun_percent": report["pooled"]["overrun_percent"],
        "streak": report["pooled"]["max_consecutive_overrun"],
        "worst_overrun_ms": report["pooled"]["overrun_ms"]["p99"],
        "max_overrun_ms": max(samples["overrun_ms"]),
        "phases": {
            name: {
                "p50": percentile(values, 0.50),
                "p95": percentile(values, 0.95),
                "p99": percentile(values, 0.99),
                "max": max(values),
            }
            for name, values in samples.items()
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-dir", type=Path, required=True)
    args = parser.parse_args()

    summaries = [
        summary
        for summary in (summarise(path) for path in sorted(args.archive_dir.iterdir()) if path.is_dir())
        if summary is not None
    ]
    if not summaries:
        raise SystemExit(f"no verified scenario output under {args.archive_dir}")

    print("Phase D probe - pooled frames per transition style (platform budget 13.6666 ms)\n")
    header = f"{'scenario':32s} {'frames':>7s} {'over%':>7s} {'streak':>6s} {'worst ovr':>10s}"
    for name in PHASES:
        header += f" {name + ' p50':>12s} {name + ' p95':>12s} {name + ' max':>12s}"
    print(header)
    for summary in summaries:
        line = (
            f"{summary['scenario']:32s} {summary['frames']:7d} {summary['overrun_percent']:7.3f} "
            f"{summary['streak']:6d} {summary['max_overrun_ms']:10.3f}"
        )
        for name in PHASES:
            phase = summary["phases"][name]
            line += f" {phase['p50']:12.3f} {phase['p95']:12.3f} {phase['max']:12.3f}"
        print(line)

    print("\nHeadroom against the platform budget, from the UI-thread share:")
    for summary in summaries:
        ui = summary["phases"]["ui_ms"]
        print(
            f"  {summary['scenario']:32s} ui p95 {ui['p95']:6.3f} ms "
            f"({ui['p95'] / 13.6666 * 100:5.1f}% of budget), ui max {ui['max']:6.3f} ms"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
