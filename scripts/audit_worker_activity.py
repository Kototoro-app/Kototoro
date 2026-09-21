"""Report whether a background Worker contributed measurable time in each trace.

The 1.5x comparison was confounded once by a `GoogleDriveSyncWorker`; checking for it per trace is the
cheap guard that keeps a device-side comparison honest. A worker that only appears in the job
scheduler's own accounting (sub-millisecond) did not run; one with a multi-second slice did.

Usage:
    python scripts/audit_worker_activity.py --dir <archive> --trace-processor <shell>
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_reader_frames import query  # noqa: E402

WORKER_PATTERNS = ("%Worker%", "%Sync%", "%Backup%", "%drive%", "%Drive%")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, required=True, help="directory holding *.pb traces")
    parser.add_argument("--trace-processor", default="trace_processor_shell")
    args = parser.parse_args()

    conditions = " OR ".join(f"s.name LIKE '{p}'" for p in WORKER_PATTERNS)
    traces = sorted(args.dir.rglob("*.pb"))
    if not traces:
        print(f"no traces under {args.dir}")
        return 1

    for trace in traces:
        sql = f"""
            SELECT s.name AS name, round(s.dur / 1e6, 1) AS dur_ms
            FROM slice s
            WHERE {conditions}
            ORDER BY s.dur DESC
            LIMIT 6;
        """
        rows = query(args.trace_processor, trace, sql)
        significant = [r for r in rows if float(r["dur_ms"]) >= 100.0]
        label = f"{trace.parent.name}/{trace.name}"
        if significant:
            summary = ", ".join(f"{r['name'][:60]}={r['dur_ms']}ms" for r in significant[:3])
            print(f"CONTAMINATED  {label}: {summary}")
        else:
            top = rows[0]["dur_ms"] if rows else "none"
            print(f"clean         {label}: largest worker-ish slice={top}ms")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
