# Reader renderer Macrobenchmark

This module measures the fixed 100-page renderer fixture in the app's non-debuggable `benchmark`
variant. The three backends consume the same page geometry and predecoded bitmap set:

- `lazy`: Compose `LazyColumn` control;
- `compose_scene`: `ComposeSceneRenderer`;
- `view_scene`: `AndroidViewSceneView`.

Run on a physical API 31+ device:

```bash
./gradlew :macrobenchmark:connectedCheck
```

Run only the reader comparison:

```bash
./gradlew :macrobenchmark:connectedCheck \
  -Pandroid.testInstrumentationRunnerArguments.class=org.skepsun.kototoro.macrobenchmark.ReaderRendererBenchmark
```

The fixture isolates renderer cost and reports `frameDurationCpuMs` and `frameOverrunMs` through
`FrameTimingMetric` under both partial and full compilation. It intentionally excludes network,
source acquisition, image decoding, and power measurement; those require separate real-chapter and
sustained-scroll journeys before selecting the default production backend.

### Notes for OEM devices (e.g. MIUI / HyperOS)
- Ensure "USB debugging (Security settings)" is enabled or execute `setprop persist.security.adbinput 1` to permit UiAutomator input event injection.
- Ensure the benchmark APK target package has background broadcast permissions or keep the activity resumed during compilation profile installation.
