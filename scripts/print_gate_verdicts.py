"""Print the recorded SLO verdicts side by side, so a regression claim sits next to its baseline."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def load(path: Path) -> dict[str, dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return {entry["scenario"]: entry for entry in data}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verdicts", type=Path, required=True,
                        help="gate-verdict.json from a campaign")
    parser.add_argument("--scenarios", default="", help="optional comma separated filter")
    args = parser.parse_args()

    entries = load(args.verdicts)
    wanted = [s for s in args.scenarios.split(",") if s] or list(entries)
    header = f"{'scenario':32s} {'verdict':10s} {'frames':>7s} {'late':>5s} {'late %':>8s} {'streak':>6s} {'worst ms':>9s} {'max ui':>8s}"
    print(header)
    for scenario in wanted:
        entry = entries.get(scenario)
        if entry is None:
            print(f"{scenario:32s} (absent)")
            continue
        metrics = entry["metrics"]
        print(
            f"{scenario:32s} {entry['verdict']:10s} {metrics['frame_count']:7d} "
            f"{metrics['overrun_count']:5d} {metrics['overrun_percent']:8.3f} "
            f"{metrics['max_consecutive_overrun']:6d} {metrics['worst_overrun_ms']:9.3f} "
            f"{metrics['max_ui_ms']:8.3f}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
