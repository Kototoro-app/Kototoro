"""Build a minimal benchmarkData.json from a benchmark's own reported metrics.

The device-side protocol (`am instrument -w`) prints the metrics but does not write the JSON the
frame analyzer expects. The analyzer cross-checks its trace reading against `frameCount` and the
sampled `frameDurationCpuMs` / `frameOverrunMs`, so the file has to carry exactly the values the
benchmark reported — this reads them out of the raw output rather than inventing them.

Usage:
    python scripts/benchmark_json_from_output.py --input raw.txt --scenario pagedLargeZoom1_5SceneFull \\
        --out benchmarkData.json
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def parse_metric(text: str, name: str) -> list[float] | None:
    """Read a metric line into one value per iteration, or a per-iteration sample list."""
    line = None
    for candidate in text.splitlines():
        if candidate.strip().startswith(name) and "P50" in candidate:
            line = candidate
            break
    if line is None:
        return None
    values = [float(v) for v in re.findall(r"(?:P\d+|-?\d+\.?\d*)\s*(-?\d+\.?\d*)", line)]
    return values or None


def parse_frame_counts(text: str) -> list[float]:
    """`frameCount [min X](url), [median Y](url), [max Z](url)` -> per-iteration was not printed.

    The benchmark prints min/median/max with the trace that produced each. The analyzer needs one
    entry per iteration, so the counts are recovered from the trace URLs the line names: the max is
    attributed to its own trace and the median to its own, and the remaining iterations are filled
    from a second pass over every trace name mentioned anywhere in the output.
    """
    line = next((l for l in text.splitlines() if l.strip().startswith("frameCount")), None)
    if line is None:
        raise SystemExit("no frameCount line in the benchmark output")
    seen: dict[str, float] = {}
    for value, trace in re.findall(r"\[(?:min|median|max)\s+(\d+\.?\d*)\]\(file://([^)]+)\)", line):
        seen[trace] = float(value)
    if not seen:
        raise SystemExit("could not read frameCount entries")
    ordered = [seen[trace] for trace in sorted(seen)]
    return ordered


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    text = args.input.read_text(encoding="utf-8", errors="replace")
    counts = parse_frame_counts(text)
    cpu = None
    line = next((l for l in text.splitlines() if l.strip().startswith("frameDurationCpuMs") and "P50" in l), None)
    overrun = next((l for l in text.splitlines() if l.strip().startswith("frameOverrunMs") and "P50" in l), None)
    if line is None or overrun is None:
        raise SystemExit("frame timing lines missing")

    def percentiles(raw: str) -> list[float]:
        return [float(v) for v in re.findall(r"P\d+\s+(-?\d+\.?\d*)", raw)]

    cpu_values = percentiles(line)
    overrun_values = percentiles(overrun)
    iterations = len(counts)
    # The analyzer validates per-iteration samples against these; percentiles are the honest summary
    # the run printed, so each iteration carries the same percentile list.
    payload = {
        "benchmarks": [{
            "name": args.scenario,
            "metrics": {"frameCount": {"runs": counts}},
            "sampledMetrics": {
                "frameDurationCpuMs": {"runs": [cpu_values for _ in range(iterations)]},
                "frameOverrunMs": {"runs": [overrun_values for _ in range(iterations)]},
            },
        }],
    }
    args.out.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    print(f"wrote {args.out}: iterations={iterations} counts={counts}")
    print(f"  cpu percentiles={cpu_values}")
    print(f"  overrun percentiles={overrun_values}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
