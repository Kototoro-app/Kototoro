"""Compare where a journey's time goes, across every frame, between two analyzer reports.

Section A of the SLO judgement says *that* frames were late; this says *what they were waiting for*,
which is what attribution needs. Reuses the per-frame CSVs so it covers all frames rather than the
handful the probe reports.

Usage:
    python scripts/compare_frame_phases.py --baseline <verified dir> --current <verified dir>
"""

from __future__ import annotations

import argparse
import csv
import glob
from pathlib import Path


def load(directory: Path) -> list[dict[str, float]]:
    rows: list[dict[str, float]] = []
    for path in sorted(glob.glob(str(directory / "frames-*.csv"))):
        with open(path, newline="", encoding="utf-8") as handle:
            for row in csv.DictReader(handle):
                rows.append({key: float(value) for key, value in row.items()
                             if key not in ("jank_type", "present_type")})
    return rows


def share(values: list[float], predicate) -> float:
    return sum(1 for v in values if predicate(v)) / len(values) if values else 0.0


def percentile(values: list[float], percent: float) -> float:
    ordered = sorted(values)
    return ordered[int((len(ordered) - 1) * percent)]


def report(label: str, rows: list[dict[str, float]]) -> None:
    ui = [r["ui_ms"] for r in rows]
    rt = [r["rt_ms"] for r in rows]
    overrun = [r["overrun_ms"] for r in rows]
    late = [r for r in rows if r["overrun_ms"] > 0]
    print(f"{label}: frames={len(rows)} late={len(late)} ({len(late) / len(rows) * 100:.3f}%)")
    print(
        f"   ui p50={percentile(ui, .5):6.2f} p99={percentile(ui, .99):7.2f} max={max(ui):7.2f} | "
        f"rt p50={percentile(rt, .5):5.2f} p99={percentile(rt, .99):6.2f} max={max(rt):7.2f}"
    )
    if late:
        late_ui = [r["ui_ms"] for r in late]
        late_rt = [r["rt_ms"] for r in late]
        print(
            f"   LATE frames: ui p50={percentile(late_ui, .5):7.2f} max={max(late_ui):7.2f} | "
            f"rt p50={percentile(late_rt, .5):6.2f} max={max(late_rt):7.2f} | "
            f"overrun max={max(r['overrun_ms'] for r in late):7.2f}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    args = parser.parse_args()

    print("Whole-journey frame phases (all frames, pooled across iterations)\n")
    report("baseline", load(args.baseline))
    print()
    report("current ", load(args.current))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
