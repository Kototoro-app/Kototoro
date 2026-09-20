#!/usr/bin/env python3
"""Judge an archived reader macrobenchmark run against the group SLOs.

Thresholds live in docs/architecture/reader-scene-improvement-plan-2026-09.md section 4.2.1
("分组 SLO"). This script only judges; extraction stays in analyze_reader_frames.py so the
frame samples keep one implementation.

Expected layout of one run directory (what the benchmark task plus that script produce):

    <run-dir>/<package>-benchmarkData.json    harness metrics
    <run-dir>/verified/report.json            frame extraction, with per-trace SHA-256
    <run-dir>/verified/frames-<iter>.csv      matched frames inside the measured window
    <run-dir>/*.perfetto-trace                raw traces (hashes re-verified when present)
    <run-dir>/run-summary.txt                 optional identity/condition record

Exit codes: 0 = pass, 1 = SLO violation, 2 = evidence insufficient.

Usage:
    python scripts/check_reader_benchmark.py --campaign-root <archive-dir>
    python scripts/check_reader_benchmark.py --run-dir <dir> --scenario pagedLargeZoomedSceneFull
    python scripts/check_reader_benchmark.py --selftest
"""

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

PACKAGE = "org.skepsun.kototoro"
MIN_ITERATIONS = 5
PERCENTILE_TOLERANCE = 1e-6

# Hard failure class: the pre-fix main-thread region-decoder close produced 723-753 ms
# main-thread stalls (plan deliveries 10/11). Any frame at this scale fails regardless of
# how small the timeout ratio is.
HARD_FAIL_UI_MS = 100.0
HARD_FAIL_OVERRUN_MS = 100.0

# RssAnon growth across iterations: peak of any iteration minus the first iteration.
RSS_GROWTH_ABS_KB = 60 * 1024
RSS_GROWTH_FRACTION = 0.15

# Decoded presentation assets must stay inside the retention window. Observed baseline max is 3
# (double page); the window's upper bound is the double-page slot capacity times the planner's
# <=6 requests per snapshot (plan section 8.1, delivery 5).
ASSETS_MAX = 6.0


class GroupSlo:
    def __init__(self, label, overrun_p95_max, overrun_p99_max, overrun_percent_max,
                 max_consecutive_max, worst_overrun_max_ms, max_ui_frame_max_ms,
                 rss_max_kb, gpu_max_kb):
        self.label = label
        self.overrun_p95_max = overrun_p95_max
        self.overrun_p99_max = overrun_p99_max
        self.overrun_percent_max = overrun_percent_max
        self.max_consecutive_max = max_consecutive_max
        self.worst_overrun_max_ms = worst_overrun_max_ms
        self.max_ui_frame_max_ms = max_ui_frame_max_ms
        self.rss_max_kb = rss_max_kb
        self.gpu_max_kb = gpu_max_kb


# Mirrors plan section 4.2.1. Units: ms unless the field says otherwise.
SLO = {
    "regular": GroupSlo(
        label="常规阅读组", overrun_p95_max=0.0, overrun_p99_max=0.0, overrun_percent_max=0.5,
        max_consecutive_max=1, worst_overrun_max_ms=20.0, max_ui_frame_max_ms=50.0,
        rss_max_kb=400 * 1024, gpu_max_kb=150 * 1024,
    ),
    "large": GroupSlo(
        label="大图常用倍率组", overrun_p95_max=0.0, overrun_p99_max=0.0, overrun_percent_max=0.5,
        max_consecutive_max=1, worst_overrun_max_ms=20.0, max_ui_frame_max_ms=50.0,
        rss_max_kb=550 * 1024, gpu_max_kb=250 * 1024,
    ),
    "high_zoom": GroupSlo(
        label="高倍率压力组", overrun_p95_max=0.0, overrun_p99_max=6.0, overrun_percent_max=2.5,
        max_consecutive_max=3, worst_overrun_max_ms=80.0, max_ui_frame_max_ms=80.0,
        rss_max_kb=650 * 1024, gpu_max_kb=400 * 1024,
    ),
}

SCENARIO_GROUPS = {
    "pagedSingleSceneFull": "regular",
    "pagedDoublePageSceneFull": "regular",
    "pagedLargeSceneFull": "large",
    "pagedLargeZoom1_5SceneFull": "large",
    "pagedLargeZoom2_0SceneFull": "high_zoom",
    "pagedLargeZoomSceneFull": "high_zoom",
    "pagedLargeZoomedSceneFull": "high_zoom",
}

REQUIRED_HARNESS_METRICS = (
    "frameCount",
    "memoryMaxRssAnonMaxKb",
    "memoryMaxGpuMaxKb",
    "ActivePresentationAssets_Max",
    "ActivePresentationAssets_Last",
)

# Frame timing lives in sampledMetrics. Their absence is the documented silent failure mode when
# the benchmark cannot read the perfetto trace, so they are required evidence, not a nicety.
REQUIRED_SAMPLED_METRICS = ("frameDurationCpuMs", "frameOverrunMs")

REQUIRED_FRAME_FIELDS = ("overrun_ms", "cpu_ms", "ui_ms")


def percentile(values, percent):
    """Linear interpolation at (N-1)*p, matching analyze_reader_frames.py."""
    values = sorted(values)
    rank = (len(values) - 1) * percent / 100
    low = int(rank)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (rank - low)


def max_streak(values):
    streak = maximum = 0
    for value in values:
        streak = streak + 1 if value > 0 else 0
        maximum = max(maximum, streak)
    return maximum


def evaluate(metrics, group):
    """Pure threshold judgement. `metrics` holds canonical run metrics, `group` a SLO key."""
    slo = SLO[group] if isinstance(group, str) else group
    missing = [key for key in (
        "frame_count", "overrun_count", "overrun_percent", "max_consecutive_overrun",
        "overrun_p95_ms", "overrun_p99_ms", "worst_overrun_ms", "max_ui_ms",
        "rss_max_kb", "rss_runs_kb", "gpu_max_kb", "assets_max", "assets_last",
    ) if metrics.get(key) is None]
    if missing:
        return {"verdict": "insufficient", "group": slo.label, "checks": [], "failures": [],
                "missing": missing}

    checks = []

    def check(name, value, limit, unit="ms", ok=None):
        passed = value <= limit if ok is None else ok
        checks.append({"name": name, "value": value, "limit": limit, "unit": unit, "ok": passed})

    check("overrun P95", metrics["overrun_p95_ms"], slo.overrun_p95_max)
    check("overrun P99", metrics["overrun_p99_ms"], slo.overrun_p99_max)
    check("timeout ratio", metrics["overrun_percent"], slo.overrun_percent_max, unit="%")
    check("max consecutive timeouts", metrics["max_consecutive_overrun"], slo.max_consecutive_max, unit=" frames")
    check("worst single-frame overrun", metrics["worst_overrun_ms"], slo.worst_overrun_max_ms)
    check("max main-thread frame", metrics["max_ui_ms"], slo.max_ui_frame_max_ms)
    check("RssAnon Max", metrics["rss_max_kb"], slo.rss_max_kb, unit="KB")
    check("Gpu Max", metrics["gpu_max_kb"], slo.gpu_max_kb, unit="KB")
    check("ActivePresentationAssets Max", metrics["assets_max"], ASSETS_MAX, unit=" assets")
    check("ActivePresentationAssets Last", metrics["assets_last"], ASSETS_MAX, unit=" assets")

    runs = metrics["rss_runs_kb"]
    growth = max(runs) - runs[0]
    growth_limit = max(RSS_GROWTH_ABS_KB, RSS_GROWTH_FRACTION * runs[0])
    check("RssAnon growth (peak - first)", growth, growth_limit, unit="KB")

    # The hard-fail class is absolute: it does not care about ratios.
    hard_fail = []
    if metrics["max_ui_ms"] >= HARD_FAIL_UI_MS:
        hard_fail.append(
            f"main-thread frame {metrics['max_ui_ms']:.1f}ms >= {HARD_FAIL_UI_MS:.0f}ms")
    if metrics["worst_overrun_ms"] >= HARD_FAIL_OVERRUN_MS:
        hard_fail.append(
            f"single-frame overrun {metrics['worst_overrun_ms']:.1f}ms >= {HARD_FAIL_OVERRUN_MS:.0f}ms")
    for reason in hard_fail:
        checks.append({"name": "hard-fail class", "value": reason, "limit": "forbidden",
                       "unit": "", "ok": False})

    failures = [c for c in checks if not c["ok"]]
    return {
        "verdict": "violation" if failures else "pass",
        "group": slo.label,
        "checks": checks,
        "failures": failures,
        "missing": [],
        "rss_growth_kb": growth,
        "rss_growth_limit_kb": growth_limit,
        "hard_fail": hard_fail,
    }


def insufficient(reason):
    return {"verdict": "insufficient", "group": None, "checks": [], "failures": [],
            "missing": [reason]}


def sha256_of(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_csv_frames(csv_path):
    with csv_path.open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    if not rows:
        raise ValueError(f"{csv_path.name} has no frame rows")
    for field in REQUIRED_FRAME_FIELDS:
        if field not in rows[0]:
            raise ValueError(f"{csv_path.name} is missing the {field} column")
    return [{key: float(row[key]) for key in REQUIRED_FRAME_FIELDS} for row in rows]


def collect_metrics(run_dir, scenario, verify_traces=True, allow_missing_traces=False,
                    require_identity=False):
    """Assemble canonical metrics from an archived run, verifying the evidence chain."""
    json_files = [p for p in run_dir.glob("*-benchmarkData.json")]
    if len(json_files) != 1:
        return None, insufficient(f"expected exactly one *-benchmarkData.json, found {len(json_files)}")
    json_path = json_files[0]
    report_path = run_dir / "verified" / "report.json"
    if not report_path.exists():
        return None, insufficient("verified/report.json is missing (run analyze_reader_frames.py first)")

    try:
        harness = json.loads(json_path.read_text())
        report = json.loads(report_path.read_text())
    except (OSError, ValueError) as error:
        return None, insufficient(f"unreadable benchmark artifacts: {error}")

    benchmarks = [b for b in harness.get("benchmarks", []) if b.get("name") == scenario]
    if len(benchmarks) != 1:
        return None, insufficient(f"benchmark JSON does not contain exactly one '{scenario}' entry")
    metrics = benchmarks[0].get("metrics", {})
    absent = [key for key in REQUIRED_HARNESS_METRICS if key not in metrics]
    if absent:
        return None, insufficient(f"harness metrics missing: {', '.join(absent)}")
    sampled = benchmarks[0].get("sampledMetrics", {})
    absent = [
        key for key in REQUIRED_SAMPLED_METRICS
        if key not in sampled or any(p not in sampled[key] for p in ("P50", "P95", "P99"))
    ]
    if absent:
        return None, insufficient(
            f"frame timing metrics missing from sampledMetrics: {', '.join(absent)} "
            "(the run could not read its own perfetto trace)")

    recorded_hash = report.get("benchmark_json_sha256")
    if recorded_hash != sha256_of(json_path):
        return None, insufficient("report.json does not belong to this benchmarkData.json")
    if report.get("benchmark") != scenario:
        return None, insufficient(f"report.json is for '{report.get('benchmark')}', not '{scenario}'")
    if report.get("package") != PACKAGE:
        return None, insufficient(f"report.json package is '{report.get('package')}', not '{PACKAGE}'")

    iterations = report.get("iterations") or []
    if len(iterations) < MIN_ITERATIONS:
        return None, insufficient(f"only {len(iterations)} iterations analyzed, need {MIN_ITERATIONS}")

    csv_paths = sorted((run_dir / "verified").glob("frames-*.csv"))
    if len(csv_paths) != len(iterations):
        return None, insufficient(
            f"{len(csv_paths)} frame CSVs for {len(iterations)} analyzed iterations")

    overruns, cpus, uis, streaks, frames_total, overruns_total = [], [], [], [], 0, 0
    for csv_path, iteration in zip(csv_paths, iterations):
        try:
            frames = read_csv_frames(csv_path)
        except (OSError, ValueError) as error:
            return None, insufficient(str(error))
        iteration_overruns = [f["overrun_ms"] for f in frames]
        overruns.extend(iteration_overruns)
        cpus.extend(f["cpu_ms"] for f in frames)
        uis.extend(f["ui_ms"] for f in frames)
        streaks.append(max_streak(iteration_overruns))
        frames_total += len(frames)
        overruns_total += sum(1 for value in iteration_overruns if value > 0)

        # The CSV must reproduce the summary it was archived with.
        if len(frames) != iteration["frame_count"]:
            return None, insufficient(f"{csv_path.name} frame count disagrees with report.json")
        if abs(percentile(iteration_overruns, 99) - iteration["overrun_ms"]["p99"]) > PERCENTILE_TOLERANCE:
            return None, insufficient(f"{csv_path.name} P99 disagrees with report.json")

    pooled = report.get("pooled") or {}
    for name, value, recorded in (
        ("frame_count", frames_total, pooled.get("frame_count")),
        ("overrun_count", overruns_total, pooled.get("overrun_count")),
        ("overrun_percent", 100 * overruns_total / frames_total, pooled.get("overrun_percent")),
        ("overrun_p50", percentile(overruns, 50), (pooled.get("overrun_ms") or {}).get("p50")),
        ("overrun_p95", percentile(overruns, 95), (pooled.get("overrun_ms") or {}).get("p95")),
        ("overrun_p99", percentile(overruns, 99), (pooled.get("overrun_ms") or {}).get("p99")),
        ("cpu_p99", percentile(cpus, 99), (pooled.get("cpu_ms") or {}).get("p99")),
    ):
        if recorded is None or abs(value - recorded) > PERCENTILE_TOLERANCE:
            return None, insufficient(f"recomputed {name} disagrees with report.json")
    if max(streaks) != pooled.get("max_consecutive_overrun"):
        return None, insufficient("recomputed consecutive-overrun streak disagrees with report.json")

    # Independent link: the harness's own percentiles must match the archived frame samples.
    for metric, recomputed in (
        ("frameOverrunMs", {"P50": percentile(overruns, 50), "P95": percentile(overruns, 95),
                            "P99": percentile(overruns, 99)}),
        ("frameDurationCpuMs", {"P99": percentile(cpus, 99)}),
    ):
        for point, value in recomputed.items():
            if abs(value - sampled[metric][point]) > PERCENTILE_TOLERANCE:
                return None, insufficient(
                    f"recomputed {metric} {point} disagrees with the harness JSON")

    if verify_traces:
        traces = sorted(run_dir.glob("*.perfetto-trace"))
        if not traces and not allow_missing_traces:
            return None, insufficient(
                "raw traces are absent, so trace-derived dimensions cannot be re-verified "
                "(pass --allow-missing-traces to judge from the archived extraction anyway)")
        recorded_traces = {Path(i["trace"]).name: i["sha256"] for i in iterations}
        if traces and len(traces) != len(iterations):
            return None, insufficient(f"{len(traces)} traces for {len(iterations)} iterations")
        for trace in traces:
            if trace.name not in recorded_traces:
                return None, insufficient(f"{trace.name} is not referenced by report.json")
            if sha256_of(trace) != recorded_traces[trace.name]:
                return None, insufficient(f"{trace.name} hash disagrees with report.json")

    identity = {}
    summary_path = run_dir / "run-summary.txt"
    if summary_path.exists():
        identity = dict(
            line.split("=", 1) for line in summary_path.read_text().splitlines() if "=" in line
        )
        if identity.get("gradle_exit") not in (None, "0"):
            return None, insufficient("the recorded benchmark run did not finish successfully")
        refresh = identity.get("refresh_precondition_hz")
        if refresh:
            try:
                if float(refresh) < 119.0:
                    return None, insufficient(f"display refresh precondition unmet ({refresh} Hz)")
            except ValueError:
                return None, insufficient(f"unreadable refresh precondition '{refresh}'")
    elif require_identity:
        return None, insufficient("run-summary.txt is required for identity/condition evidence")

    worst = max(overruns)
    canonical = {
        "frame_count": frames_total,
        "overrun_count": overruns_total,
        "overrun_percent": 100 * overruns_total / frames_total,
        "max_consecutive_overrun": max(streaks),
        "overrun_p50_ms": percentile(overruns, 50),
        "overrun_p95_ms": percentile(overruns, 95),
        "overrun_p99_ms": percentile(overruns, 99),
        "worst_overrun_ms": worst,
        "max_ui_ms": max(uis),
        "cpu_p99_ms": percentile(cpus, 99),
        "rss_max_kb": max(metrics["memoryMaxRssAnonMaxKb"]["runs"]),
        "rss_first_kb": metrics["memoryMaxRssAnonMaxKb"]["runs"][0],
        "rss_runs_kb": list(metrics["memoryMaxRssAnonMaxKb"]["runs"]),
        "gpu_max_kb": max(metrics["memoryMaxGpuMaxKb"]["runs"]),
        "assets_max": max(metrics["ActivePresentationAssets_Max"]["runs"]),
        "assets_last": max(metrics["ActivePresentationAssets_Last"]["runs"]),
        "iterations": len(iterations),
        "identity": identity,
    }
    return canonical, None


def fmt(value, unit=""):
    if isinstance(value, str):
        return value
    if unit == "KB":
        return f"{value / 1024:.0f}MB"
    return f"{value:.3f}{unit}" if isinstance(value, float) else f"{value}{unit}"


def report_run(scenario, canonical, verdict, stream=sys.stdout):
    print(f"\n{scenario} [{SCENARIO_GROUPS.get(scenario, 'unknown')}]", file=stream)
    if verdict["verdict"] == "insufficient":
        for reason in verdict["missing"]:
            print(f"  insufficient evidence: {reason}", file=stream)
        return
    print(f"  frames={canonical['frame_count']} overruns={canonical['overrun_count']} "
          f"({canonical['overrun_percent']:.3f}%) iterations={canonical['iterations']}", file=stream)
    for check in verdict["checks"]:
        mark = "ok  " if check["ok"] else "FAIL"
        print(f"  [{mark}] {check['name']}: {fmt(check['value'], check['unit'])} "
              f"(limit {fmt(check['limit'], check['unit'])})", file=stream)
    print(f"  -> {verdict['verdict']}", file=stream)


def judge_run(run_dir, scenario, verify_traces=True, allow_missing_traces=False,
              require_identity=False):
    if scenario not in SCENARIO_GROUPS:
        return None, insufficient(
            f"no SLO is defined for '{scenario}' (plan section 4.2.1 covers the paged journeys)")
    canonical, error = collect_metrics(
        run_dir, scenario, verify_traces=verify_traces,
        allow_missing_traces=allow_missing_traces, require_identity=require_identity,
    )
    if error is not None:
        return None, error
    return canonical, evaluate(canonical, SCENARIO_GROUPS[scenario])


# ---------------------------------------------------------------------------
# Self test: the judge has to fail the samples that must fail (plan section 4.3).
# Every fixture input is either a real archived measurement or a boundary of a SLO row.
# ---------------------------------------------------------------------------

def _baseline(hard=()):
    return {
        "frame_count": 3000, "overrun_count": 2, "overrun_percent": 0.067,
        "max_consecutive_overrun": 1, "overrun_p95_ms": -7.9, "overrun_p99_ms": -5.5,
        "worst_overrun_ms": 2.7, "max_ui_ms": 13.6, "cpu_p99_ms": 7.1,
        "rss_max_kb": 323 * 1024, "rss_first_kb": 307 * 1024,
        "rss_runs_kb": [307 * 1024, 323 * 1024, 284 * 1024, 340 * 1024, 349 * 1024],
        "gpu_max_kb": 96 * 1024, "assets_max": 2.0, "assets_last": 1.0, "iterations": 5,
    }


def _write_fixture_run(root, scenario="pagedSingleSceneFull", iterations=5, frames_per_iter=40):
    """Write a minimal but self-consistent archived run directory (no device needed)."""
    run_dir = root / scenario
    verified = run_dir / "verified"
    verified.mkdir(parents=True)

    overruns, cpus, uis = [], [], []
    streaks = []
    per_iteration_counts = []
    per_iteration_p99 = []
    traces = []
    for index in range(iterations):
        iteration_overruns = [(-5.0 + (j % 7)) if j % 19 else 3.0 for j in range(frames_per_iter)]
        iteration_cpus = [2.0 + (j % 5) * 0.5 for j in range(frames_per_iter)]
        iteration_uis = [4.0 + (j % 3) for j in range(frames_per_iter)]
        overruns.extend(iteration_overruns)
        cpus.extend(iteration_cpus)
        uis.extend(iteration_uis)
        streaks.append(max_streak(iteration_overruns))
        per_iteration_counts.append(len(iteration_overruns))
        per_iteration_p99.append(percentile(iteration_overruns, 99))
        csv_path = verified / f"frames-{index:03}.csv"
        with csv_path.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=["overrun_ms", "cpu_ms", "ui_ms"])
            writer.writeheader()
            for overrun, cpu, ui in zip(iteration_overruns, iteration_cpus, iteration_uis):
                writer.writerow({"overrun_ms": overrun, "cpu_ms": cpu, "ui_ms": ui})
        trace = run_dir / f"ReaderProductionBenchmark_{scenario}_iter{index:03}_fixture.perfetto-trace"
        trace.write_bytes(b"fixture trace " + str(index).encode())
        traces.append(trace)

    rss_runs = [300 * 1024, 305 * 1024, 298 * 1024, 302 * 1024, 301 * 1024]
    harness = {
        "benchmarks": [{
            "name": scenario,
            "params": {},
            "metrics": {
                "frameCount": {"runs": per_iteration_counts, "minimum": min(per_iteration_counts)},
                "memoryMaxRssAnonMaxKb": {"runs": rss_runs},
                "memoryMaxGpuMaxKb": {"runs": [100 * 1024] * iterations},
                "ActivePresentationAssets_Max": {"runs": [2.0] * iterations},
                "ActivePresentationAssets_Last": {"runs": [1.0] * iterations},
            },
            "sampledMetrics": {
                "frameOverrunMs": {p: percentile(overruns, float(p[1:])) for p in ("P50", "P95", "P99")},
                "frameDurationCpuMs": {p: percentile(cpus, float(p[1:])) for p in ("P50", "P95", "P99")},
            },
        }],
    }
    json_path = run_dir / f"{PACKAGE}-benchmarkData.json"
    json_path.write_text(json.dumps(harness, indent=2) + "\n")

    overrun_count = sum(1 for value in overruns if value > 0)
    report = {
        "processor": "fixture",
        "benchmark": scenario,
        "package": PACKAGE,
        "benchmark_json_sha256": sha256_of(json_path),
        "iterations": [
            {
                "iteration": index,
                "frame_count": per_iteration_counts[index],
                "overrun_ms": {"p99": per_iteration_p99[index]},
                "trace": str(traces[index]),
                "sha256": sha256_of(traces[index]),
            }
            for index in range(iterations)
        ],
        "pooled": {
            "frame_count": len(overruns),
            "overrun_count": overrun_count,
            "overrun_percent": 100 * overrun_count / len(overruns),
            "max_consecutive_overrun": max(streaks),
            "cpu_ms": {f"p{p}": percentile(cpus, p) for p in (50, 95, 99)},
            "overrun_ms": {f"p{p}": percentile(overruns, p) for p in (50, 95, 99)},
        },
    }
    (verified / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    (run_dir / "run-summary.txt").write_text(
        f"scenario={scenario}\ngradle_exit=0\nrefresh_precondition_hz=120.00001\n")
    return run_dir, harness, json_path


def selftest_evidence(stream=sys.stdout):
    """The evidence chain has to reject tampered, stale or incomplete archives."""
    import tempfile

    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        # 1. intact archive
        run_dir, harness, json_path = _write_fixture_run(root / "intact")
        canonical, error = collect_metrics(run_dir, "pagedSingleSceneFull")
        cases.append(("intact archive", error is None and canonical is not None, "accepted"))

        # 2. a run that never produced frame timing (the silent perfetto-owner failure)
        broken_dir, broken_harness, _ = _write_fixture_run(root / "no_frame_timing")
        broken_json = broken_dir / f"{PACKAGE}-benchmarkData.json"
        stripped = json.loads(broken_json.read_text())
        del stripped["benchmarks"][0]["sampledMetrics"]["frameOverrunMs"]
        broken_json.write_text(json.dumps(stripped, indent=2) + "\n")
        canonical, error = collect_metrics(broken_dir, "pagedSingleSceneFull")
        cases.append(("no frame timing metrics",
                      error is not None and "sampledMetrics" in error["missing"][0], "rejected"))

        # 3. benchmarkData.json edited after the extraction ran
        tampered_dir, _, tampered_json = _write_fixture_run(root / "tampered")
        payload = json.loads(tampered_json.read_text())
        payload["benchmarks"][0]["metrics"]["ActivePresentationAssets_Max"]["runs"] = [99.0] * 5
        tampered_json.write_text(json.dumps(payload, indent=2) + "\n")
        canonical, error = collect_metrics(tampered_dir, "pagedSingleSceneFull")
        cases.append(("harness JSON edited after extraction",
                      error is not None and "does not belong" in error["missing"][0], "rejected"))

        # 4. a frame CSV disagrees with the archived summary
        corrupt_dir, _, _ = _write_fixture_run(root / "corrupt_csv")
        corrupt_csv = corrupt_dir / "verified" / "frames-002.csv"
        rows = corrupt_csv.read_text().splitlines()
        corrupt_csv.write_text("\n".join(rows[:-1] + ["9.5,2.0,4.0"]) + "\n")
        canonical, error = collect_metrics(corrupt_dir, "pagedSingleSceneFull")
        cases.append(("frame CSV disagrees with report.json",
                      error is not None and "P99" in error["missing"][0], "rejected"))

        # 5. traces pruned from the archive
        pruned_dir, _, _ = _write_fixture_run(root / "pruned")
        for trace in pruned_dir.glob("*.perfetto-trace"):
            trace.unlink()
        canonical, error = collect_metrics(pruned_dir, "pagedSingleSceneFull")
        cases.append(("traces pruned",
                      error is not None and "raw traces are absent" in error["missing"][0], "rejected"))
        canonical, error = collect_metrics(pruned_dir, "pagedSingleSceneFull",
                                           allow_missing_traces=True)
        cases.append(("traces pruned, explicitly allowed",
                      error is None, "accepted"))

        # 6. trace bytes changed after extraction
        swapped_dir, _, _ = _write_fixture_run(root / "swapped_trace")
        next(swapped_dir.glob("*.perfetto-trace")).write_bytes(b"different bytes")
        canonical, error = collect_metrics(swapped_dir, "pagedSingleSceneFull")
        cases.append(("trace content changed",
                      error is not None and "hash disagrees" in error["missing"][0], "rejected"))

        # 7. display refresh precondition not met
        slow_dir, _, _ = _write_fixture_run(root / "slow_display")
        (slow_dir / "run-summary.txt").write_text(
            "scenario=pagedSingleSceneFull\ngradle_exit=0\nrefresh_precondition_hz=60.000004\n")
        canonical, error = collect_metrics(slow_dir, "pagedSingleSceneFull")
        cases.append(("display at 60Hz",
                      error is not None and "refresh precondition" in error["missing"][0], "rejected"))

        # 8. the recorded run did not finish
        failed_dir, _, _ = _write_fixture_run(root / "failed_run")
        (failed_dir / "run-summary.txt").write_text(
            "scenario=pagedSingleSceneFull\ngradle_exit=1\nrefresh_precondition_hz=120.0\n")
        canonical, error = collect_metrics(failed_dir, "pagedSingleSceneFull")
        cases.append(("run recorded as failed",
                      error is not None and "did not finish successfully" in error["missing"][0],
                      "rejected"))

        # 9. only four iterations archived
        short_dir, _, _ = _write_fixture_run(root / "short_run", iterations=4)
        canonical, error = collect_metrics(short_dir, "pagedSingleSceneFull")
        cases.append(("four iterations only",
                      error is not None and "iterations analyzed" in error["missing"][0], "rejected"))

        # 10. a scenario without an agreed SLO is never silently accepted
        _, verdict = judge_run(intact_dir := run_dir, "burstSceneFull")
        cases.append(("scenario without SLO", verdict["verdict"] == "insufficient", "rejected"))

    failures = 0
    for name, ok, expected in cases:
        failures += 0 if ok else 1
        print(f"  [{'ok  ' if ok else 'FAIL'}] {name}: expected {expected}", file=stream)
    return failures, len(cases)


def selftest(stream=sys.stdout):
    cases = []

    # Real archived baselines must all pass.
    for scenario, group in SCENARIO_GROUPS.items():
        metrics = _baseline()
        if group == "high_zoom":
            metrics.update(overrun_count=65, overrun_percent=1.632, max_consecutive_overrun=2,
                           overrun_p95_ms=-8.25, overrun_p99_ms=3.156, worst_overrun_ms=16.24,
                           max_ui_ms=27.1, rss_max_kb=528 * 1024,
                           rss_runs_kb=[534 * 1024, 505 * 1024, 531 * 1024, 528 * 1024, 502 * 1024],
                           gpu_max_kb=305 * 1024)
        elif group == "large":
            metrics.update(rss_max_kb=436 * 1024, gpu_max_kb=197 * 1024)
        cases.append((f"baseline {scenario}", metrics, group, "pass"))

    # Boundary rows: exactly at the limit passes, one step over fails.
    base = _baseline()
    cases.append(("regular overrun P99 at limit", dict(base, overrun_p99_ms=0.0), "regular", "pass"))
    cases.append(("regular overrun P99 over limit", dict(base, overrun_p99_ms=0.001), "regular", "violation"))
    cases.append(("regular ratio at limit", dict(base, overrun_percent=0.5), "regular", "pass"))
    cases.append(("regular ratio over limit", dict(base, overrun_percent=0.51), "regular", "violation"))
    cases.append(("regular streak at limit", dict(base, max_consecutive_overrun=1), "regular", "pass"))
    cases.append(("regular streak over limit", dict(base, max_consecutive_overrun=2), "regular", "violation"))
    cases.append(("regular worst frame at limit", dict(base, worst_overrun_ms=20.0), "regular", "pass"))
    cases.append(("regular worst frame over limit", dict(base, worst_overrun_ms=20.1), "regular", "violation"))
    cases.append(("regular ui frame at limit", dict(base, max_ui_ms=50.0), "regular", "pass"))
    cases.append(("regular ui frame over limit", dict(base, max_ui_ms=50.1), "regular", "violation"))
    cases.append(("regular rss at cap", dict(base, rss_max_kb=400 * 1024,
                                             rss_runs_kb=[400 * 1024] * 5), "regular", "pass"))
    cases.append(("regular rss over cap", dict(base, rss_max_kb=400 * 1024 + 1,
                                               rss_runs_kb=[400 * 1024 + 1] * 5), "regular", "violation"))
    cases.append(("regular gpu over cap", dict(base, gpu_max_kb=150 * 1024 + 1), "regular", "violation"))
    cases.append(("assets at bound", dict(base, assets_max=6.0, assets_last=6.0), "regular", "pass"))
    cases.append(("assets over bound", dict(base, assets_max=6.5), "regular", "violation"))

    high = _baseline()
    high.update(overrun_count=65, overrun_percent=1.632, max_consecutive_overrun=2,
                overrun_p99_ms=3.156, worst_overrun_ms=16.24, max_ui_ms=27.1,
                rss_max_kb=528 * 1024, gpu_max_kb=305 * 1024)
    cases.append(("high_zoom P99 at limit", dict(high, overrun_p99_ms=6.0), "high_zoom", "pass"))
    cases.append(("high_zoom P99 over limit", dict(high, overrun_p99_ms=6.001), "high_zoom", "violation"))
    cases.append(("high_zoom ratio at limit", dict(high, overrun_percent=2.5), "high_zoom", "pass"))
    cases.append(("high_zoom ratio over limit", dict(high, overrun_percent=2.51), "high_zoom", "violation"))
    cases.append(("high_zoom streak at limit", dict(high, max_consecutive_overrun=3), "high_zoom", "pass"))
    cases.append(("high_zoom streak over limit", dict(high, max_consecutive_overrun=4), "high_zoom", "violation"))
    cases.append(("high_zoom worst frame at limit", dict(high, worst_overrun_ms=80.0), "high_zoom", "pass"))
    cases.append(("high_zoom worst frame over limit", dict(high, worst_overrun_ms=80.1), "high_zoom", "violation"))
    cases.append(("high_zoom ui frame at limit", dict(high, max_ui_ms=80.0), "high_zoom", "pass"))
    cases.append(("high_zoom ui frame over limit", dict(high, max_ui_ms=80.1), "high_zoom", "violation"))
    # A negative P99 must not excuse a dropped-frame tail (plan section 4.3).
    cases.append(("negative P99 with a long streak", dict(high, overrun_p99_ms=-4.10,
                                                          max_consecutive_overrun=4), "high_zoom", "violation"))
    cases.append(("negative P99 with many timeouts", dict(high, overrun_p99_ms=-4.10,
                                                          overrun_percent=2.6), "high_zoom", "violation"))
    # RssAnon growth: the tolerance is max(60MB, 15% of the first iteration). At 500MB first,
    # the fraction term wins (75MB), so the boundary sits at a 575MB peak.
    cases.append(("rss growth at tolerance", dict(high, rss_max_kb=575 * 1024,
                                                  rss_runs_kb=[500 * 1024] * 4 + [575 * 1024]),
                  "high_zoom", "pass"))
    cases.append(("rss growth over tolerance", dict(high, rss_max_kb=576 * 1024,
                                                    rss_runs_kb=[500 * 1024] * 4 + [576 * 1024]),
                  "high_zoom", "violation"))

    # Real historical measurements that this gate must reject.
    cases.append(("delivery 9 2.0x P99 +9.1", dict(high, overrun_p99_ms=9.1), "high_zoom", "violation"))
    cases.append(("delivery 10 2.5x +752.9ms frame",
                  dict(high, overrun_count=41, overrun_percent=1.296, max_consecutive_overrun=2,
                       overrun_p99_ms=13.060, worst_overrun_ms=752.860, max_ui_ms=764.516),
                  "high_zoom", "violation"))
    cases.append(("delivery 11 A side +729.3ms frame",
                  dict(high, overrun_percent=1.257, overrun_p99_ms=11.577,
                       worst_overrun_ms=729.254, max_ui_ms=747.551),
                  "high_zoom", "violation"))
    # Delivery 11's fixed build measured a +50.518ms worst frame once, near a 50ms bound; the
    # gate has to accept it. That sample does not record its main-thread frame, so the fixture
    # assumes UI >= the overrun it produced (conservative).
    cases.append(("delivery 11 B side +50.5ms frame",
                  dict(high, overrun_percent=1.628, overrun_p99_ms=3.116,
                       worst_overrun_ms=50.518, max_ui_ms=50.518),
                  "high_zoom", "pass"))

    # Missing fields are insufficient evidence, never a pass.
    for field in ("overrun_p99_ms", "max_ui_ms", "rss_runs_kb", "assets_last"):
        cases.append((f"missing {field}", {k: v for k, v in base.items() if k != field},
                      "regular", "insufficient"))

    failures = 0
    print("\nmetric thresholds:", file=stream)
    for name, metrics, group, expected in cases:
        got = evaluate(metrics, group)["verdict"]
        ok = got == expected
        failures += 0 if ok else 1
        print(f"  [{'ok  ' if ok else 'FAIL'}] {name}: expected {expected}, got {got}", file=stream)

    print("\nevidence chain:", file=stream)
    evidence_failures, evidence_total = selftest_evidence(stream)

    total_cases, total_failures = len(cases) + evidence_total, failures + evidence_failures
    print(f"\nself test: {total_cases - total_failures}/{total_cases} cases as expected", file=stream)
    return total_failures


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run-dir", type=Path, help="one archived run directory")
    parser.add_argument("--scenario", help="benchmark method name for --run-dir")
    parser.add_argument("--campaign-root", type=Path,
                        help="directory holding one sub-directory per scenario")
    parser.add_argument("--allow-missing-traces", action="store_true",
                        help="judge from the archived extraction when raw traces were pruned")
    parser.add_argument("--require-identity", action="store_true",
                        help="fail when run-summary.txt is absent")
    parser.add_argument("--json-out", type=Path, help="write the machine-readable verdict here")
    parser.add_argument("--selftest", action="store_true", help="validate the judge itself")
    args = parser.parse_args()

    if args.selftest:
        return 1 if selftest() else 0

    targets = []
    if args.run_dir:
        if not args.scenario:
            parser.error("--run-dir requires --scenario")
        targets.append((args.run_dir, args.scenario))
    if args.campaign_root:
        for sub in sorted(p for p in args.campaign_root.iterdir() if p.is_dir()):
            if sub.name in SCENARIO_GROUPS:
                targets.append((sub, sub.name))
    if not targets:
        parser.error("nothing to judge: pass --run-dir/--scenario, --campaign-root or --selftest")

    results, worst = [], 0
    for run_dir, scenario in targets:
        canonical, verdict = judge_run(
            run_dir, scenario, allow_missing_traces=args.allow_missing_traces,
            require_identity=args.require_identity,
        )
        report_run(scenario, canonical or {}, verdict)
        results.append({"scenario": scenario, "run_dir": str(run_dir),
                        "verdict": verdict["verdict"], "failures": verdict["failures"],
                        "missing": verdict["missing"], "metrics": canonical})
        worst = max(worst, {"pass": 0, "insufficient": 2, "violation": 1}[verdict["verdict"]])

    counts = {}
    for result in results:
        counts[result["verdict"]] = counts.get(result["verdict"], 0) + 1
    print(f"\nsummary: {counts}")
    if args.json_out:
        args.json_out.write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n",
                                 encoding="utf-8")
    return worst


if __name__ == "__main__":
    sys.exit(main())
