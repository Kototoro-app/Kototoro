# Upstream: backdrop

- **Artifact:** `io.github.kyant0:backdrop:2.0.0`
- **Upstream repository:** <https://github.com/Kyant0/AndroidLiquidGlass>
- **License:** Apache-2.0 (full text in [`LICENSE`](LICENSE))

## Provenance

All Kotlin sources under `src/main/kotlin/com/kyant/backdrop/` were copied
**verbatim** (byte-faithful, original CRLF line endings preserved) from the
published sources jars of version 2.0.0:

- `io.github.kyant0:backdrop:2.0.0` → `backdrop-2.0.0-sources.jar`, `commonMain/` source set
- `io.github.kyant0:backdrop-android:2.0.0` → `backdrop-android-2.0.0-sources.jar`, `androidMain/` source set

The `skikoMain` (desktop/skiko) source set was intentionally not taken.
**File count: 30 `.kt` files** (26 verbatim `commonMain` files + 4 files where the
`commonMain` `expect` declarations were flattened into their `androidMain` `actual`
implementations, see below). No code was reformatted, renamed, or "improved";
`internal`/`private` visibility is exactly as published.

## expect/actual flattening

A plain Android library module cannot contain `expect` declarations, so the four
files that exist in both source sets were merged mechanically: the `expect`
declarations were removed from the `commonMain` copy and the `androidMain`
implementations were inserted with only the `actual` keyword stripped off.

| File | Merging applied |
| --- | --- |
| `Platform.kt` | commonMain held only the 2 `expect fun`s; file = androidMain content minus `actual ` |
| `RuntimeShader.kt` | 2 `expect fun`s replaced by their androidMain bodies (plus android-only `asAndroidRuntimeShader` and `internal class AndroidRuntimeShader`); the `interface RuntimeShader` block kept verbatim from commonMain |
| `internal/Paint.kt` | commonMain held only the 2 `internal expect fun`s; file = androidMain content minus `actual ` |
| `internal/RenderEffect.kt` | androidMain content minus `actual `; the published commonMain default `renderEffect: RenderEffect? = null` of `ColorFilterEffect` was restored (actuals may not redeclare expect defaults, so its omission there is a Kotlin expect/actual artifact, not an upstream choice) |

## Local modifications
-------------------
1. `com/kyant/backdrop/DrawBackdropModifier.kt` -- `DrawBackdropNode.onGloballyPositioned`
   published `layoutCoordinates` on every layout pass, and the property is backed by
   `neverEqualPolicy()`, so each publish notified its readers and the window never stopped
   invalidating. The node now remembers the position/size of the last publish and republishes
   only when that geometry changes. The coordinates *instance* is deliberately not part of the
   comparison: with a shared-transition scope in the tree, the callback alternates between the
   lookahead and the regular `LayoutCoordinates` of the same node, so an instance check
   republishes forever even while nothing moves.

   Measured on a Redmi K70 (e2ed20be), untouched history screen: this write was 35% of all
   snapshot writes and is now zero. The screen still redraws for unrelated reasons (the lazy
   grid re-measures every frame), so this is a real reduction, not a settled page.
