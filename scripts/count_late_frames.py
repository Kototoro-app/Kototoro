"""Count late frames in one trace, without needing a benchmarkData.json.

`analyze_reader_frames.py` cross-checks its reading against the benchmark's own samples, which the
device-side protocol does not write. When the question is "how many frames were late, and how late"
rather than "does this match the harness", the frames can be matched with the same query and counted
directly. The frame matcher itself is reused so the definition of a frame does not drift.

Usage:
    python scripts/count_late_frames.py --trace <trace.pb> --trace-processor <shell>
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_reader_frames import frame_query, match_frames, query  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--trace-processor", default="trace_processor_shell")
    parser.add_argument("--package", default="org.skepsun.kototoro")
    args = parser.parse_args()

    frames, _ = match_frames(query(args.trace_processor, args.trace, frame_query(args.package)))
    late = [f for f in frames if f["overrun_ms"] > 0]
    worst = max((f["overrun_ms"] for f in frames), default=0.0)
    longest_streak = 0
    current_streak = 0
    for frame in frames:
        if frame["overrun_ms"] > 0:
            current_streak += 1
            longest_streak = max(longest_streak, current_streak)
        else:
            current_streak = 0
    print(
        f"{args.trace.name}: frames={len(frames)} late={len(late)} "
        f"({len(late) / len(frames) * 100:.3f}%) streak={longest_streak} worst={worst:.2f}ms"
    )
    for frame in sorted(late, key=lambda f: -f["overrun_ms"])[:4]:
        print(
            f"    worst late: overrun={frame['overrun_ms']:.2f}ms ui={frame['ui_ms']:.2f} "
            f"rt={frame['rt_ms']:.2f} cpu={frame['cpu_ms']:.2f}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
