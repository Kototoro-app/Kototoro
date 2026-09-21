"""Find slices whose name matches a pattern, and report the process and thread they belong to.

Written because two SQL attempts to locate a suspicious `GoogleDriveSyncWorker` slice by joining
thread and process returned nothing, while a plain name search had found it. This dumps the matches
with their ownership, which is what decides whether an entry belongs to the app under test at all.

Usage:
    python scripts/find_slice_owner.py --trace <trace.pb> --trace-processor <shell> --pattern Drive
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_reader_frames import query  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--trace-processor", default="trace_processor_shell")
    parser.add_argument("--pattern", required=True)
    args = parser.parse_args()

    sql = f"""
        SELECT
          s.name AS slice_name,
          round(s.dur / 1e6, 1) AS dur_ms,
          t.name AS thread_name,
          p.name AS process_name,
          p.pid AS pid
        FROM slice s
        LEFT JOIN thread_track tr ON s.track_id = tr.id
        LEFT JOIN thread t ON tr.utid = t.utid
        LEFT JOIN process p ON t.upid = p.upid
        WHERE s.name LIKE '%{args.pattern}%'
        ORDER BY s.dur DESC
        LIMIT 20;
    """
    rows = query(args.trace_processor, args.trace, sql)
    if not rows:
        print(f"no slice name contains '{args.pattern}'")
        return 0
    for row in rows:
        print(
            f"{row.get('process_name')} pid={row.get('pid')} thread={row.get('thread_name')} "
            f"dur={row.get('dur_ms')}ms  {row.get('slice_name')}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
