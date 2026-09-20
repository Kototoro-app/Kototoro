"""Print the late-frame probes of an analyzer report, one line per late frame."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

FIELDS = (
    "running_ms",
    "sleeping_ms",
    "longest_sleep_ms",
    "runnable_ms",
    "animation_ms",
    "record_ms",
    "rt_upload_ms",
    "rt_upload_texdata_ms",
    "post_wait_ms",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()

    report = json.loads(args.report.read_text(encoding="utf-8"))
    header = f"{'iter':>4s} {'fid':>10s} " + " ".join(f"{f.replace('_ms',''):>11s}" for f in FIELDS)
    print(header)
    total = 0
    for index, iteration in enumerate(report["iterations"]):
        for probe in iteration.get("overrun_probes") or []:
            total += 1
            values = " ".join(f"{str(probe.get(f, '-')):>11s}" for f in FIELDS)
            print(f"{index:4d} {probe['fid']:>10s} {values}")
    print(f"\nlate-frame probes: {total}")
    if total and all((iteration.get("overrun_probes") or [{}])[0].get("rt_upload_ms") is None
                     for iteration in report["iterations"] if iteration.get("overrun_probes")):
        print("NOTE: rt_upload_ms is still NULL everywhere — the upload search is not matching.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
