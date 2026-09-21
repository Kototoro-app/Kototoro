"""List every late frame with its position in the run, to separate start-up cost from steady state."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_reader_frames import frame_query, match_frames, query  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--trace-processor", default="trace_processor_shell")
    parser.add_argument("--package", default="org.skepsun.kototoro")
    args = parser.parse_args()

    frames, _ = match_frames(query(args.trace_processor, args.trace, frame_query(args.package)))
    start = frames[0]["ts"]
    late = [(i, f) for i, f in enumerate(frames) if f["overrun_ms"] > 0]
    print(f"{args.trace.name}: {len(frames)} frames, {len(late)} late")
    for index, frame in late:
        elapsed = (frame["ts"] - start) / 1e6
        print(
            f"    frame#{index:4d} at {elapsed:8.1f}ms overrun={frame['overrun_ms']:+7.2f} "
            f"ui={frame['ui_ms']:6.2f} rt={frame['rt_ms']:5.2f} cpu={frame['cpu_ms']:6.2f}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
