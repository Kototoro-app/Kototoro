#!/usr/bin/env python3
"""Compare interleaved real-entry reader traces side by side.

`interleave_ab_real_entry.ps1` writes `round<N>-<side>.pb`. This script reduces each trace to the
quantities the first-screen question is about - how the frames of the opening window are shaped -
so the two sides can be read off one table instead of one trace at a time.

Frame matching is reused from `analyze_reader_frames.py`, so the definition of a frame stays
identical to the archived CS-7 runs.

Usage:
    python scripts/compare_real_entry_runs.py --dir <ab-dir> [--window 40] [--trace-processor <exe>]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_reader_frames import frame_query, match_frames, percentile, query  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, required=True, help="directory holding round<N>-<side>.pb")
    parser.add_argument("--trace-processor", default="trace_processor")
    parser.add_argument("--package", default="org.skepsun.kototoro")
    parser.add_argument("--window", type=int, default=40, help="frames counted as the opening window")
    parser.add_argument(
        "--window-ms",
        default=None,
        help="also report a wall-clock window, e.g. 5000,11000 - used for the page-turn stretch "
        "of a journey run, where frame indices are not comparable between runs",
    )
    args = parser.parse_args()

    traces = sorted(
        args.dir.glob("round*.pb"),
        key=lambda p: (int(re.search(r"round(\d+)", p.name)[1]), p.name),
    )
    if not traces:
        raise SystemExit(f"no round*.pb traces in {args.dir}")

    header = (
        f"{'trace':<22} {'frames':>7} {'late':>5} {'ui p50':>7} {'ui p99':>7} {'ui max':>7} "
        f"{'rt max':>7} {'win frames':>10} {'win ui max':>10} {'win late':>8} {'win worst(ms)':>13}"
    )
    print(header)
    print("-" * len(header))
    rows = []
    for trace in traces:
        frames, _ = match_frames(query(args.trace_processor, trace, frame_query(args.package)))
        window = frames[: args.window]
        late = [f for f in frames if f["overrun_ms"] > 0]
        window_late = [f for f in window if f["overrun_ms"] > 0]
        worst_window = max((f["overrun_ms"] for f in window), default=0.0)
        worst_window_frame = max(window, key=lambda f: f["ui_ms"]) if window else None
        row = {
            "name": trace.name,
            "frames": len(frames),
            "late": len(late),
            "ui_p50": percentile([f["ui_ms"] for f in frames], 50),
            "ui_p99": percentile([f["ui_ms"] for f in frames], 99),
            "ui_max": max(f["ui_ms"] for f in frames),
            "rt_max": max(f["rt_ms"] for f in frames),
            "win_frames": len(window),
            "win_ui_max": max((f["ui_ms"] for f in window), default=0.0),
            "win_late": len(window_late),
            "win_worst": worst_window,
            "win_ui_max_at": (window.index(worst_window_frame) if worst_window_frame else -1),
            "win_ui_max_ms": (worst_window_frame["ts"] - frames[0]["ts"]) / 1e6 if worst_window_frame else 0.0,
        }
        rows.append(row)
        print(
            f"{row['name']:<22} {row['frames']:>7} {row['late']:>5} {row['ui_p50']:>7.2f} "
            f"{row['ui_p99']:>7.2f} {row['ui_max']:>7.2f} {row['rt_max']:>7.2f} {row['win_frames']:>10} "
            f"{row['win_ui_max']:>10.2f} {row['win_late']:>8} {row['win_worst']:>+13.2f}"
        )
    print()
    print(f"opening window = first {args.window} matched frames; worst-frame overrun counts deadlines missed")
    for row in rows:
        print(
            f"  {row['name']:<22} worst ui frame in window: #{row['win_ui_max_at']} "
            f"at {row['win_ui_max_ms']:.1f}ms ui={row['win_ui_max']:.2f}ms"
        )

    if args.window_ms:
        low, high = (float(part) for part in args.window_ms.split(","))
        header_ms = (
            f"{'trace':<22} {'frames':>7} {'late':>5} {'ui p50':>7} {'ui p99':>7} {'ui max':>7} "
            f"{'rt max':>7} {'worst overrun':>14}"
        )
        print()
        print(f"wall-clock window {low:.0f}..{high:.0f}ms after the first frame")
        print(header_ms)
        print("-" * len(header_ms))
        for trace in traces:
            frames, _ = match_frames(query(args.trace_processor, trace, frame_query(args.package)))
            base = frames[0]["ts"]
            selected = [f for f in frames if low <= (f["ts"] - base) / 1e6 <= high]
            if not selected:
                print(f"{trace.name:<22} {'-':>7} (no frames in window)")
                continue
            late = [f for f in selected if f["overrun_ms"] > 0]
            print(
                f"{trace.name:<22} {len(selected):>7} {len(late):>5} "
                f"{percentile([f['ui_ms'] for f in selected], 50):>7.2f} "
                f"{percentile([f['ui_ms'] for f in selected], 99):>7.2f} "
                f"{max(f['ui_ms'] for f in selected):>7.2f} "
                f"{max(f['rt_ms'] for f in selected):>7.2f} "
                f"{max(f['overrun_ms'] for f in selected):>+14.2f}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
