# Reader macrobenchmark

Two harnesses live in this module:

- `ReaderRendererBenchmark` — the fixed 100-page geometry fixture comparing the three renderer
  backends (`lazy`, `compose_scene`, `view_scene`) on **renderer cost alone**. It deliberately
  excludes network, source acquisition, image decoding and power measurement.
- `ReaderProductionBenchmark` — the production journeys used for the Scene Reader acceptance work:
  webtoon burst (`burst*`), sustained traversal (`sustained*`), energy (`energy*`), the ultra-long
  fixture (`ultraLong*`) and the paged matrix (`paged*`, including the oversized-page and the
  zoom ladder). These drive the real benchmark activity with real fixtures.

The older note that "those require separate real-chapter and sustained-scroll journeys" described
only the renderer comparison; the production journeys above already cover real chapters, oversized
pages, double-page turns and sustained scrolling.

## Running

```bash
./gradlew :macrobenchmark:connectedCheck
./gradlew :macrobenchmark:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.skepsun.kototoro.macrobenchmark.ReaderProductionBenchmark#pagedLargeZoomedSceneFull
```

## Regression gate (CS-7)

Thresholds are the per-group SLOs in
[`docs/architecture/reader-scene-improvement-plan-2026-09.md`](../docs/architecture/reader-scene-improvement-plan-2026-09.md)
section 4.2.1 (groups: `regular` 常规阅读 / `large` 大图常用倍率 / `high_zoom` 高倍率压力), derived
from the archived 2026-09-20 baselines.

```bash
# run the journeys, archive every trace, then judge them
python scripts/run_reader_benchmark_gate.py --group all --archive-dir <archive-dir>

# judge an existing archive (no device needed)
python scripts/check_reader_benchmark.py --campaign-root <archive-dir>

# validate the judge itself: 44 threshold cases + 11 evidence-chain cases
python scripts/check_reader_benchmark.py --selftest
```

Exit codes: `0` pass, `1` SLO violation, `2` evidence insufficient.

The judge never passes on partial evidence. It re-hashes the archived traces and the benchmark
JSON, recomputes the pooled percentiles from the archived frame CSVs, and cross-checks them against
both `verified/report.json` and the harness JSON's own `sampledMetrics`. The following are
**insufficient evidence**, not a pass: missing `frameDurationCpuMs`/`frameOverrunMs` in
`sampledMetrics` (the documented silent failure when the harness cannot read its perfetto trace),
a JSON edited after extraction, a frame CSV that disagrees with the archived summary, changed trace
bytes, fewer than five iterations, a run recorded as failed, a display below 119Hz, or a scenario
without an agreed SLO. The judgement covers the tail and residency dimensions — timeout ratio, worst
consecutive timeouts, worst single frame, worst main-thread frame, `RssAnon` peak and its growth
across iterations, `ActivePresentationAssets` boundedness and the hard-fail class (any main-thread
frame or single-frame overrun ≥100ms) — never just the mean.

Recorded but not gated: tile residency bytes/count and evictions, per-iteration RSS spread, and
`PresentationWidthPx`. Tile residency may legitimately exceed its budget while visible tiles are
pinned (`TileMemoryBudget` documents this), so it is reported for review instead of failing a run.

## Device protocol

1. **Install the variant you built.** This module is self-instrumenting, so
   `connectedDebugAndroidTest` does **not** reinstall the target app — it measures whatever
   `org.skepsun.kototoro` is already on the device:
   ```bash
   ./gradlew :app:assembleBenchmark
   adb install -r -t app/build/outputs/apk/benchmark/app-arm64-v8a-benchmark.apk
   ```
   `scripts/run_reader_benchmark_gate.py` does this and then proves identity: `lastUpdateTime`
   must change, and the pulled installed `base.apk` must be byte-identical to the built APK.
2. **Refresh-rate precondition.** The harness asserts ≥119Hz while setting up. MIUI/HyperOS applies
   a window's high-refresh request late, which fails the run with
   `Expected ~120Hz display refresh rate, actual=60.000004 Hz`. The gate warms the switch and reads
   `mActiveSfDisplayMode.peakRefreshRate` before measuring. Note the platform's frame **budget** in
   this configuration is a constant 13.6666ms, so "overrun ≤ 0" means "inside 13.6666ms", not
   "inside one 8.33ms vsync".
3. **Keep the device clean and cool.** Check `adb shell free -m` and `ps -A | grep perfetto` first:
   a leftover root perfetto process or a memory-starved device fabricates long tails. The gate waits
   (bounded) for the battery to fall back to ≤35.0°C before each journey and records the temperature
   before/after.
4. **Input injection** on MIUI/HyperOS requires `setprop persist.security.adbinput 1`, otherwise
   `input`/UiAutomator swipes are silently dropped.
5. **Trace ownership.** If `/data/misc/perfetto-traces/trace_output.pb` is `root:root 0600`, the
   harness cannot read it (`IllegalStateException: Cannot check size of ...`) and
   `frameDurationCpuMs`/`frameOverrunMs` vanish from every journey while memory counters keep
   reporting. Run the instrumentation as root, or re-apply `chmod a+r` for the duration of the run.
   A benchmark JSON without those two metrics is evidence-insufficient.
