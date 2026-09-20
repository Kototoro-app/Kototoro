"""Compare the long-chapter group: does anything grow with the number of pages in the chapter?"""

from __future__ import annotations

import argparse
import glob
import json
import os
from pathlib import Path

LABELS = {
    "pagedLongChapter50Full": "50 pages",
    "pagedLongChapter500Full": "500 pages",
    "pagedLongChapter5000Full": "5000 pages",
}


def median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[len(ordered) // 2]


def flatten(runs: list) -> list[float]:
    """Sampled metrics arrive as one list of samples per iteration; other metrics are scalars."""
    flat: list[float] = []
    for run in runs:
        if isinstance(run, list):
            flat.extend(float(value) for value in run)
        else:
            flat.append(float(run))
    return flat


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-dir", type=Path, required=True)
    args = parser.parse_args()

    print("Long chapter group - page count is the only variable (median of 5 iterations)\n")
    header = (
        f"{'chapter':>10s} {'frames':>7s} {'cpu p50':>8s} {'cpu p95':>8s} "
        f"{'rssAnon max':>12s} {'gpu max':>9s} {'heap max':>9s} {'assets':>7s}"
    )
    print(header)
    for scenario, label in LABELS.items():
        matches = glob.glob(os.path.join(args.archive_dir, scenario, "*benchmarkData.json"))
        if not matches:
            print(f"{label:>10s}   (no data)")
            continue
        data = json.loads(Path(matches[0]).read_text(encoding="utf-8"))
        entries = [b for b in data["benchmarks"] if b["name"] == scenario]
        if not entries:
            print(f"{label:>10s}   (no entry)")
            continue
        metrics = entries[0]["metrics"]
        # Frame timing lives in sampledMetrics (that split is itself the documented silent-failure
        # mode of a benchmark that cannot read its trace).
        sampled = entries[0].get("sampledMetrics", {}) or {}

        def med(name: str) -> float | None:
            runs = metrics.get(name, {}).get("runs") or sampled.get(name, {}).get("runs") or []
            return median(flatten(runs))

        def p95(name: str) -> float | None:
            runs = metrics.get(name, {}).get("runs") or sampled.get(name, {}).get("runs") or []
            samples = sorted(flatten(runs))
            if not samples:
                return None
            return samples[int((len(samples) - 1) * 0.95)]

        frames = med("frameCount") or -1.0
        cpu_p50 = med("frameDurationCpuMs") or -1.0
        cpu_p95 = p95("frameDurationCpuMs") or -1.0
        rss = med("memoryMaxRssAnonMaxKb") or 0.0
        gpu = med("memoryMaxGpuMaxKb") or 0.0
        heap = med("memoryMaxHeapSizeMaxKb") or 0.0
        assets = med("ActivePresentationAssets_Max") or -1.0
        print(
            f"{label:>10s} {frames:7.0f} {cpu_p50:8.2f} {cpu_p95:8.2f} "
            f"{rss / 1024:11.1f}M {gpu / 1024:8.1f}M {heap / 1024:8.1f}M {assets:7.0f}"
        )

    print(
        "\nA flat row set means the window queries and per-frame allocation do not scale with the\n"
        "chapter length; a rising column names what does.",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
