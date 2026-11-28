# Upstream: backdrop

- **Artifact:** `io.github.kyant0:backdrop:2.0.0`
- **Upstream repository:** <https://github.com/Kyant0/AndroidLiquidGlass>
- **License:** Apache-2.0 (full text in [`LICENSE`](LICENSE))

## Provenance

The module uses Kotlin Multiplatform with Android and JVM targets at the same upstream version, 2.0.0.

- `src/main/kotlin` is configured as `commonMain`; its existing local patches are retained.
- `src/androidMain/kotlin` contains the four original Android `actual` files.
- `src/skikoMain/kotlin` contains the four original Skiko `actual` files; `jvmMain` depends on it.
- The four formerly flattened common files again contain their published `expect` declarations.

There are 38 Kotlin files: 30 common, four Android and four Skiko. All restored platform sources and
common declarations come from the published 2.0.0 source jars, not the changing upstream branch:

| Source archive | SHA-256 |
| --- | --- |
| `backdrop-2.0.0-sources.jar` | `8277e21a2dc270931a3dfb175805dac5303f541e20e01bf05d1a714e8f6c032a` |
| `backdrop-android-2.0.0-sources.jar` | `23a987f64d7de69cc1f6751da96712030bc4bc488a5f48d8244935264b0ec5d6` |

The initial Android-only import omitted `skikoMain` and flattened `expect/actual`. That restriction was
removed for Windows UI sharing on 2026-10-04. Android keeps its SDK support checks; desktop uses Skia
RuntimeEffect, shaders and render effects from the original Skiko implementation.

## Local modifications
-------------------
1. `com/kyant/backdrop/DrawBackdropModifier.kt` -- `DrawBackdropNode.onGloballyPositioned`
   published `layoutCoordinates` on every layout pass, and the property is backed by
   `neverEqualPolicy()`, so each publish notified its readers and the window never stopped
   invalidating. The node now remembers the position/size of the last publish and republishes
   only when that geometry changes, using `Backdrop.relativePositionOf` (`LayerBackdrop` reports
   `layerCoordinates.localPositionOf(coordinates)` so route transitions moving the backdrop and its
   consumers together do not republish). The coordinates *instance* is deliberately not part of the
   comparison: with a shared-transition scope in the tree, the callback alternates between the
   lookahead and the regular `LayoutCoordinates` of the same node, so an instance check
   republishes forever even while nothing moves.

   Measured on a Redmi K70 (e2ed20be), untouched history screen: this write was 35% of all
   snapshot writes and is now zero. The screen still redraws for unrelated reasons (the lazy
   grid re-measures every frame), so this is a real reduction, not a settled page.

2. `com/kyant/backdrop/DrawBackdropModifier.kt`, `com/kyant/backdrop/BackdropEffectScope.kt` --
   when a surface has a render effect (blur/lens), `DrawBackdropNode` records its backdrop layer
   at reduced resolution and scales it back up (originally a fixed 1/2, now
   `BackdropQuality.inputScale`). The backdrop is
   re-rendered every frame while content scrolls underneath, and on the iOS-style glass chrome
   that was the dominant RenderThread/GPU cost (Perfetto: `flush layers` 1.5-1.7 ms per frame,
   3-5 ms in the slowest frames). `BackdropEffectScopeImpl.update` takes the scale and hands
   effects a matching density and size, so dp-based parameters (blur radius, lens height,
   corner radii) keep their visual size; the backdrop itself is still positioned with the real
   density so `layerBlock` inverse transforms stay correct. Surfaces without a render effect
   are unchanged (scale 1).
   `BackdropQuality.inputScale` (snapshot state) sets the scale process-wide with Haze's
   `HazePerformanceMode` presets: `PERFORMANCE` 0.5, `BALANCED` sqrt(0.625) ~= 0.79 (default),
   `QUALITY` 1 (full resolution). Nodes read it while resolving effects, so live surfaces
   re-resolve and redraw.
   An individual backdrop can supply an `effectInputScale` multiplier. Android's compact chapter
   sheet uses 0.5 on Performance and Balanced, and 1 on Quality for full-resolution glass.
   Its foreground and the neighbouring dock stay at their usual resolution.
   Effects can set `BackdropEffectScope.requiresFullResolution` when a zero-radius region must meet
   unfiltered content. The immersive progressive blur uses this to avoid a resampling seam at its content edge.

   Status: compiled, not yet measured or visually compared on device.

3. `com/kyant/backdrop/shadow/ShadowModifier.kt`, `shadow/InnerShadowModifier.kt`,
   `highlight/HighlightModifier.kt`, new `internal/RecordKey.kt` -- ported from the upstream
   performance proposal [#126](https://github.com/Kyant0/AndroidLiquidGlass/issues/126), item 1
   (branch `ntvien/AndroidLiquidGlass:perf/record-shadow-layers-once`, not merged upstream). The
   shadow, inner shadow and highlight layers are recorded again only when size, density, layout
   direction, inner shape or a parameter other than alpha changes; alpha stays a layer property,
   so press/fade animations no longer re-record. Also resets the inner shadow's `prevRadius` on
   detach.

4. `com/kyant/backdrop/effects/Lens.kt`, `internal/Shaders.kt`, `effects/ColorFilter.kt` -- ported
   from #126 item 4 (branch `perf/vibrancy-in-lens`). `lens(..., saturation)` applies saturation
   inside the refraction shader, so `vibrancy()` + `lens()` costs one render pass less; it falls
   back to `colorControls(saturation)` when the lens cannot run (API < 33, zero refraction). The
   old four-argument `lens` stays as a hidden overload. Kototoro's `SharedLiquidGlassSurface` and
   `SwipeableFilterChip` pass `saturation = 1.5f` instead of calling `vibrancy()` when a lens runs.

   The compact chapter panel also opts into `BottomClippedBackdrop`. Its child keeps the
   rounded outline clip with automatic compositing via `Backdrop.prefersAutoCompositing` (which
   other source-over surfaces can also opt into). Its filtered input covers the visible
   height plus overscan in 128-texel increments. The full logical panel still defines the lens.

   Status for 3 and 4 (Android 17 device, iOS interface style, debug build, A/B against the same
   build without them, two interleaved rounds): bottom-tab switching GPU p99 17 -> 12-14 ms, p90
   10 -> 9 ms; home scrolling unchanged. Glass over vivid covers differs by at most 21/255 (the
   saturation now runs after the blur instead of before); the top bar differs by at most 3/255.
