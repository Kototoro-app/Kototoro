#!/usr/bin/env python3
"""Extract Android 12+ reader frames and verify them against Macrobenchmark 1.5.0.

Uses the full capture, like FrameTimingQuery; measureBlock is reported separately.
Produces evidence, not an SLO verdict. Requires only Python's standard library and
Perfetto trace_processor. See docs/architecture/reader-scene-zoom-trace-analysis-2026-09.md.
"""

import argparse
import csv
import hashlib
import io
import json
import re
import subprocess
from collections import Counter
from pathlib import Path


def query(processor, trace, sql):
    result = subprocess.run(
        [processor, str(trace), "-Q", sql], capture_output=True, text=True, check=True,
    )
    return list(csv.DictReader(io.StringIO(result.stdout)))


def frame_query(package):
    package = "'" + package.replace("'", "''") + "'"
    return f"""
        SELECT s.id, s.name, s.ts, s.dur, t.utid, '' AS jank_type, '' AS present_type
        FROM slice s JOIN thread_track tr ON s.track_id=tr.id JOIN thread t USING(utid)
        JOIN process p USING(upid)
        WHERE p.name={package} AND
          ((s.name GLOB 'Choreographer#doFrame*' AND t.tid=p.pid) OR
           (s.name GLOB 'DrawFrame*' AND t.name='RenderThread'))
        UNION ALL
        SELECT a.id, 'actual ' || a.name, a.ts, a.dur, 0, a.jank_type, a.present_type
        FROM actual_frame_timeline_slice a JOIN process p USING(upid) WHERE p.name={package}
        UNION ALL
        SELECT e.id, 'expected ' || e.name, e.ts, e.dur, 0, '', ''
        FROM expected_frame_timeline_slice e JOIN process p USING(upid) WHERE p.name={package}
        ORDER BY ts;
    """


def match_frames(rows):
    # AndroidX 1.5.0 FrameTimingQuery: RT/UI by frame ID, actual by overlap,
    # expected by actual ID, consume actual once, retain the RT-end workaround.
    groups = {key: [] for key in ("Choreographer#doFrame", "DrawFrame", "actual", "expected")}
    for row in rows:
        row = dict(row)
        for key in ("id", "ts", "dur", "utid"):
            row[key] = int(row[key])
        if row["dur"] <= 0 or "resynced" in row["name"]:
            continue
        row["end"] = row["ts"] + row["dur"]
        row["frame_id"] = int(row["name"].split()[1])
        for prefix, group in groups.items():
            if row["name"].startswith(prefix):
                group.append(row)
                break
    indexes = {}
    for kind in ("Choreographer#doFrame", "expected"):
        group = groups[kind]
        indexes[kind] = {r["frame_id"]: r for r in group}
        if len(indexes[kind]) != len(group):
            raise ValueError(f"Ambiguous {kind} frame IDs; inspect multiple processes/layers")
    actual_pool = groups["actual"].copy()
    frames = []
    for rt in groups["DrawFrame"]:
        ui = indexes["Choreographer#doFrame"].get(rt["frame_id"])
        if ui is None:
            continue
        midpoint = ui["ts"] + ui["dur"] // 2
        actual = next((a for a in reversed(actual_pool)
                       if a["ts"] < ui["ts"] + 50_000 and a["ts"] <= midpoint < a["end"]), None)
        if actual is None:
            continue
        actual_pool.remove(actual)
        expected = indexes["expected"].get(actual["frame_id"])
        if expected is None:
            continue
        frames.append({
            "frame_id": rt["frame_id"], "ui_id": ui["id"], "rt_id": rt["id"],
            "actual_id": actual["id"], "expected_id": expected["id"], "utid": ui["utid"],
            "ts": ui["ts"], "ui_end": ui["end"], "actual_end": actual["end"],
            "expected_ts": expected["ts"], "deadline": expected["end"],
            "cpu_ms": (rt["end"] - ui["ts"]) / 1e6,
            "overrun_ms": (max(actual["end"], rt["end"]) - expected["end"]) / 1e6,
            "ui_ms": ui["dur"] / 1e6, "rt_ms": rt["dur"] / 1e6,
            "jank_type": actual["jank_type"], "present_type": actual["present_type"],
        })
    frames.sort(key=lambda f: (f["ts"], f["rt_id"]))
    if not frames:
        raise ValueError("No complete frame matches; evidence is insufficient")
    return frames, {k: len(v) for k, v in groups.items()}


def percentile(values, percent):
    values = sorted(values)
    rank = (len(values) - 1) * percent / 100
    low = int(rank)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (rank - low)


def summarize(frames):
    streak = maximum = 0
    for frame in frames:
        streak = streak + 1 if frame["overrun_ms"] > 0 else 0
        maximum = max(maximum, streak)
    late = [f for f in frames if f["overrun_ms"] > 0]
    return {
        "frame_count": len(frames), "overrun_count": len(late),
        "overrun_percent": 100 * len(late) / len(frames), "max_consecutive_overrun": maximum,
        "cpu_ms": {f"p{p}": percentile([f["cpu_ms"] for f in frames], p) for p in (50, 95, 99)},
        "overrun_ms": {f"p{p}": percentile([f["overrun_ms"] for f in frames], p) for p in (50, 95, 99)},
        "worst_frame": max(frames, key=lambda f: f["overrun_ms"]),
        "overrun_jank_types": dict(Counter(f["jank_type"] for f in late)),
    }


def validate(frames, benchmark, iteration):
    expected_count = benchmark["metrics"]["frameCount"]["runs"][iteration]
    if len(frames) != expected_count:
        raise ValueError(f"Iteration {iteration}: frameCount {len(frames)} != {expected_count}")
    for metric, field in (("frameDurationCpuMs", "cpu_ms"), ("frameOverrunMs", "overrun_ms")):
        expected = sorted(benchmark["sampledMetrics"][metric]["runs"][iteration])
        if sorted(f[field] for f in frames) != expected:
            raise ValueError(f"Iteration {iteration}: {metric} does not match the original samples")


def probe_query(frames):
    # Bound every thread-state duration to the UI slice; report wall time and
    # actual running separately. Nested texture-upload slices are not summed twice.
    values = ",".join(
        f"({f['frame_id']},{f['ui_id']},{f['rt_id']},{f['utid']},{f['ts']},{f['ui_end']})"
        for f in frames
    )
    states = []
    for name, condition, aggregate in (
        ("running_ms", "state='Running'", "sum"), ("sleeping_ms", "state='S'", "sum"),
        ("longest_sleep_ms", "state='S'", "max"), ("runnable_ms", "state IN ('R','R+')", "sum"),
    ):
        states.append(f"""(SELECT {aggregate}(min(st.ts+st.dur,f.finish)-max(st.ts,f.ts))/1e6
            FROM thread_state st WHERE st.utid=f.utid AND st.dur>0
            AND st.ts<f.finish AND st.ts+st.dur>f.ts AND {condition}) AS {name}""")
    return f"""
        WITH frames(fid,ui_id,rt_id,utid,ts,finish) AS (VALUES {values})
        SELECT fid, {','.join(states)},
          (SELECT max(dur)/1e6 FROM descendant_slice(f.ui_id) WHERE name='animation') animation_ms,
          (SELECT max(dur)/1e6 FROM descendant_slice(f.ui_id) WHERE name='Record View#draw()') record_ms,
          (SELECT sum(dur)/1e6 FROM descendant_slice(f.rt_id) WHERE name GLOB 'Texture upload*') rt_upload_ms,
          (SELECT max(dur)/1e6 FROM descendant_slice(f.ui_id) WHERE name='postAndWait') post_wait_ms
        FROM frames f;
    """


METADATA_QUERY = """
    SELECT 'measure' kind,ts,dur,name FROM slice WHERE name='measureBlock'
    UNION ALL
    SELECT 'health',0,value,name FROM stats WHERE value>0 AND severity!='info'
    UNION ALL
    SELECT 'setup_error',0,0,str_value FROM metadata WHERE name='ftrace_setup_errors'
    UNION ALL
    SELECT 'stack_samples',0,count(*),'' FROM cpu_profile_stack_sample;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace-processor", default="trace_processor")
    parser.add_argument("--trace-dir", type=Path, required=True)
    parser.add_argument("--benchmark-json", type=Path, required=True)
    parser.add_argument("--benchmark-name", required=True)
    parser.add_argument("--package", default="org.skepsun.kototoro")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    benchmarks = json.loads(args.benchmark_json.read_text())["benchmarks"]
    matches = [b for b in benchmarks if b["name"] == args.benchmark_name]
    if len(matches) != 1:
        raise ValueError("Expected exactly one matching benchmark in JSON")
    benchmark = matches[0]
    traces = sorted(args.trace_dir.glob(f"*_{args.benchmark_name}_iter*.perfetto-trace"))
    iterations = [int(re.search(r"_iter(\d+)_", p.name)[1]) for p in traces]
    if iterations != list(range(len(benchmark["metrics"]["frameCount"]["runs"]))):
        raise ValueError("Missing or duplicate iteration traces; evidence is insufficient")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "processor": subprocess.check_output([args.trace_processor, "--version"], text=True).strip(),
        "benchmark": args.benchmark_name, "package": args.package,
        "benchmark_json_sha256": hashlib.sha256(args.benchmark_json.read_bytes()).hexdigest(),
        "scope": "Full trace, matching AndroidX Macrobenchmark 1.5.0 FrameTimingQuery",
        "percentiles": "Linear interpolation at (N-1)*p; pooled samples, not median of run P99s",
        "iterations": [],
    }
    all_frames = []
    for iteration, trace in zip(iterations, traces):
        frames, counts = match_frames(query(args.trace_processor, trace, frame_query(args.package)))
        validate(frames, benchmark, iteration)
        result = summarize(frames)
        metadata = query(args.trace_processor, trace, METADATA_QUERY)
        late = [f for f in frames if f["overrun_ms"] > 0]
        probes = query(args.trace_processor, trace, probe_query(late)) if late else []
        result.update(iteration=iteration, trace=str(trace.resolve()),
                      sha256=hashlib.sha256(trace.read_bytes()).hexdigest(), slice_counts=counts,
                      metadata=metadata, overrun_probes=probes, original_samples_match=True)
        with (args.output_dir / f"frames-{iteration:03}.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(frames[0]))
            writer.writeheader()
            writer.writerows(frames)
        report["iterations"].append(result)
        all_frames.extend(frames)
        print(f"iter {iteration}: {len(frames)} frames, {result['overrun_count']} overruns "
              f"({result['overrun_percent']:.3f}%), streak {result['max_consecutive_overrun']}, "
              f"P99 {result['overrun_ms']['p99']:.3f} ms, max {result['worst_frame']['overrun_ms']:.3f} ms",
              flush=True)
    pooled = summarize(all_frames)
    # Iterations are separate journeys: never join their streaks across boundaries.
    pooled["max_consecutive_overrun"] = max(r["max_consecutive_overrun"] for r in report["iterations"])
    report["pooled"] = pooled
    for metric, field in (("frameDurationCpuMs", "cpu_ms"), ("frameOverrunMs", "overrun_ms")):
        for p in (50, 95, 99):
            if abs(pooled[field][f"p{p}"] - benchmark["sampledMetrics"][metric][f"P{p}"]) > 1e-9:
                raise ValueError(f"Pooled {metric} P{p} differs from the original report")
    (args.output_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Verified all original samples; pooled {pooled['overrun_count']}/{len(all_frames)} "
          f"({pooled['overrun_percent']:.3f}%), P99 {pooled['overrun_ms']['p99']:.3f} ms")


if __name__ == "__main__":
    main()
