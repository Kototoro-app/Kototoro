#!/usr/bin/env python3
"""Print a window of matched reader frames with the per-frame thread probes.

The CS-7 verdict is about one frame in a window (startup frames 25-32 in the benchmark
journey), so counting late frames is not enough: the shape of every frame in the window has to
be visible, including frames that are not late. Frame matching is reused from
`analyze_reader_frames.py` so the definition of a frame does not drift from the archived runs.

Usage:
    python scripts/print_frame_window.py --trace <trace.pb> [--from 0] [--to 60]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_reader_frames import frame_query, match_frames, probe_query, query  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--trace-processor", default="trace_processor")
    parser.add_argument("--package", default="org.skepsun.kototoro")
    parser.add_argument("--from", dest="start", type=int, default=0)
    parser.add_argument("--to", dest="end", type=int, default=60)
    parser.add_argument(
        "--probe-above",
        type=float,
        default=6.0,
        help="probe frames whose UI slice exceeds this many ms (late frames are always probed)",
    )
    args = parser.parse_args()

    frames, counts = match_frames(query(args.trace_processor, args.trace, frame_query(args.package)))
    base = frames[0]["ts"]
    window = frames[args.start:args.end]
    wanted = [f for f in window if f["ui_ms"] > args.probe_above or f["overrun_ms"] > 0]
    probes = {p["fid"]: p for p in (query(args.trace_processor, args.trace, probe_query(wanted)) if wanted else [])}

    print(f"{args.trace.name}")
    print(f"  frames={len(frames)} matched; slices={counts}")
    print(f"  window #{args.start}..#{args.end - 1} (elapsed measured from the first matched frame)")
    for index, frame in enumerate(window, start=args.start):
        elapsed = (frame["ts"] - base) / 1e6
        line = (
            f"  #{index:4d} t={elapsed:8.1f}ms ui={frame['ui_ms']:7.2f} rt={frame['rt_ms']:6.2f} "
            f"cpu={frame['cpu_ms']:7.2f} overrun={frame['overrun_ms']:+7.2f}"
        )
        probe = probes.get(frame["frame_id"])
        if probe:
            line += (
                f" | run={float(probe['running_ms'] or 0):5.2f} sleep={float(probe['sleeping_ms'] or 0):6.2f}"
                f" post={probe['post_wait_ms']} rtup={probe['rt_upload_ms']} rec={probe['record_ms']}"
            )
        print(line)
    late = [f for f in frames if f["overrun_ms"] > 0]
    print(f"  late frames: {len(late)}/{len(frames)}")
    for index, frame in enumerate(frames):
        if frame["overrun_ms"] > 0:
            elapsed = (frame["ts"] - base) / 1e6
            print(
                f"    late #{index:4d} t={elapsed:8.1f}ms overrun={frame['overrun_ms']:+7.2f} "
                f"ui={frame['ui_ms']:7.2f} rt={frame['rt_ms']:6.2f}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
